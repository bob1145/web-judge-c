#!/usr/bin/env python3
import argparse
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional


EXIT_COMPLETED = 0
EXIT_COMPILE_ERROR = 10
EXIT_CANCELLED = 20
EXIT_BUDGET_EXCEEDED = 21
EXIT_SECURITY_VIOLATION = 30
EXIT_SYSTEM_ERROR = 40

MAX_EVENT_MESSAGE_BYTES = 4096
COMPILE_TIMEOUT_SECONDS = 60
COMPILE_OUTPUT_LIMIT_BYTES = 64 * 1024
POLL_SECONDS = 0.02


@dataclass
class ProcessOutcome:
    status: str
    exit_code: int
    stdout: bytes
    stderr: bytes
    elapsed_ms: int
    peak_memory_kb: int


class TaskRunner:
    def __init__(self, spec_path: Path):
        self.spec_path = spec_path.resolve()
        with self.spec_path.open("r", encoding="utf-8") as file:
            self.spec = json.load(file)
        self.judge_id = self.spec["judgeId"]
        self.work_dir = Path(self.spec["workDir"]).resolve()
        self.events_path = self.work_dir / "events.jsonl"
        self.summary_path = self.work_dir / "summary.json"
        self.cancel_path = self.work_dir / "cancel.requested"
        self.bin_dir = self.work_dir / "bin"
        self.started_at = time.monotonic()
        self.summary = {
            "totalCases": int(self.spec["testCases"]),
            "completedCases": 0,
            "ac": 0,
            "wa": 0,
            "tle": 0,
            "mle": 0,
            "re": 0,
            "systemError": 0,
            "outputLimitExceeded": 0,
            "firstFailedCase": None,
            "failureSamples": [],
            "slowSamples": [],
            "stoppedReason": None,
        }

    def run(self) -> int:
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.bin_dir.mkdir(parents=True, exist_ok=True)
        self.events_path.write_text("", encoding="utf-8")
        try:
            self.validate_paths()
            executables = self.compile_sources()
            return self.run_cases(executables)
        except Cancelled:
            self.summary["stoppedReason"] = "Cancellation requested"
            self.write_summary()
            self.emit("CANCELLED", "Task cancelled")
            return EXIT_CANCELLED
        except BudgetExceeded:
            self.summary["stoppedReason"] = "Task runtime budget exceeded"
            self.write_summary()
            self.emit("BUDGET_EXCEEDED", "Task runtime budget exceeded")
            return EXIT_BUDGET_EXCEEDED
        except SecurityViolation as exc:
            self.summary["stoppedReason"] = "Security violation"
            self.write_summary()
            self.emit("SECURITY_VIOLATION", str(exc))
            return EXIT_SECURITY_VIOLATION
        except CompileError as exc:
            self.emit("SYSTEM_ERROR", "compile failed: " + bounded(exc.message))
            return EXIT_COMPILE_ERROR
        except Exception as exc:
            self.summary["stoppedReason"] = "Runner system error"
            self.write_summary()
            self.emit("SYSTEM_ERROR", "runner system error: " + bounded(str(exc)))
            return EXIT_SYSTEM_ERROR

    def validate_paths(self) -> None:
        if not self.spec_path.resolve().is_relative_to(self.work_dir):
            raise SecurityViolation("task spec must stay inside mounted workDir")
        for role, value in self.spec.get("sourcePaths", {}).items():
            path = Path(value).resolve()
            if not path.is_relative_to(self.work_dir):
                raise SecurityViolation(f"source path {role} must stay inside mounted workDir")

    def compile_sources(self) -> dict[str, Path]:
        self.emit("COMPILE_STARTED", "compile started")
        executables: dict[str, Path] = {}
        source_paths = self.spec.get("sourcePaths", {})
        for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"):
            if role not in source_paths:
                continue
            source = Path(source_paths[role]).resolve()
            output = self.bin_dir / executable_name(role.lower())
            command = ["g++", str(source), "-o", str(output), "-O2", "-std=c++17"]
            outcome = run_process(
                command=command,
                stdin=b"",
                cwd=self.work_dir,
                timeout_seconds=COMPILE_TIMEOUT_SECONDS,
                memory_limit_bytes=max(int(self.spec["memoryLimitBytes"]) * 2, 256 * 1024 * 1024),
                output_limit_bytes=COMPILE_OUTPUT_LIMIT_BYTES,
                cancel_path=self.cancel_path,
            )
            if outcome.status == "CANCELLED":
                raise Cancelled()
            if outcome.status == "TIME_LIMIT_EXCEEDED":
                self.emit("COMPILE_FINISHED", f"compile timed out for {role}", status="COMPILE_ERROR")
                raise CompileError(f"{role} compile timed out")
            if outcome.status != "SUCCESS" or outcome.exit_code != 0:
                message = decode_limited(outcome.stderr or outcome.stdout, MAX_EVENT_MESSAGE_BYTES)
                self.emit("COMPILE_FINISHED", f"compile failed for {role}: {message}", status="COMPILE_ERROR")
                raise CompileError(message)
            executables[role] = output
        required = {"GENERATOR", "USER"}
        if not required.issubset(executables):
            missing = ", ".join(sorted(required - set(executables)))
            self.emit("COMPILE_FINISHED", f"missing required executable: {missing}", status="COMPILE_ERROR")
            raise CompileError("missing required source")
        if "ORACLE" not in executables and "SPECIAL_JUDGE" not in executables:
            self.emit("COMPILE_FINISHED", "missing oracle or special judge", status="COMPILE_ERROR")
            raise CompileError("missing oracle or special judge")
        self.emit("COMPILE_FINISHED", "compile finished", status="OK")
        return executables

    def run_cases(self, executables: dict[str, Path]) -> int:
        total = int(self.spec["testCases"])
        for case_number in range(1, total + 1):
            self.check_control_files()
            input_bytes = self.run_generator(executables["GENERATOR"], case_number)
            self.write_case_artifacts(case_number, input_bytes=input_bytes)
            case_status, elapsed_ms, peak_memory_kb = self.run_user_and_judge(executables, case_number, input_bytes)
            self.accept_case_result(case_number, case_status, elapsed_ms, peak_memory_kb)
            if self.should_stop_after_result(case_status):
                self.summary["stoppedReason"] = "Stopped after first non-AC test case"
                break
        self.write_summary()
        self.emit("SUMMARY", "summary", status="DONE", summary=self.summary)
        self.emit("COMPLETED", "completed")
        return EXIT_COMPLETED

    def run_generator(self, executable: Path, case_number: int) -> bytes:
        outcome = run_process(
            command=[str(executable)],
            stdin=f"{case_number}\n".encode("utf-8"),
            cwd=self.work_dir,
            timeout_seconds=self.case_time_limit_seconds(),
            memory_limit_bytes=int(self.spec["memoryLimitBytes"]),
            output_limit_bytes=int(self.spec["maxOutputBytesPerCase"]),
            cancel_path=self.cancel_path,
        )
        self.raise_control_outcome(outcome)
        if outcome.status != "SUCCESS" or outcome.exit_code != 0:
            raise RuntimeError("generator failed")
        return outcome.stdout

    def run_user_and_judge(self, executables: dict[str, Path], case_number: int, input_bytes: bytes) -> tuple[str, int, int]:
        self.emit("RUN_STARTED", f"case {case_number} started", case_number=case_number)
        user = run_process(
            command=[str(executables["USER"])],
            stdin=input_bytes,
            cwd=self.work_dir,
            timeout_seconds=self.case_time_limit_seconds(),
            memory_limit_bytes=int(self.spec["memoryLimitBytes"]),
            output_limit_bytes=int(self.spec["maxOutputBytesPerCase"]),
            cancel_path=self.cancel_path,
        )
        self.raise_control_outcome(user)
        self.write_case_artifacts(case_number, user_output=user.stdout)
        if user.status == "TIME_LIMIT_EXCEEDED":
            return "TLE", user.elapsed_ms, user.peak_memory_kb
        if user.status == "MEMORY_LIMIT_EXCEEDED":
            return "MLE", user.elapsed_ms, user.peak_memory_kb
        if user.status == "OUTPUT_LIMIT_EXCEEDED":
            return "OUTPUT_LIMIT_EXCEEDED", user.elapsed_ms, user.peak_memory_kb
        if user.status != "SUCCESS" or user.exit_code != 0:
            return "RE", user.elapsed_ms, user.peak_memory_kb
        if "SPECIAL_JUDGE" in executables:
            status = self.run_special_judge(executables["SPECIAL_JUDGE"], case_number, input_bytes, user.stdout)
        else:
            status, oracle_output = self.run_oracle(executables["ORACLE"], input_bytes, user.stdout)
            self.write_case_artifacts(case_number, answer_output=oracle_output)
        return status, user.elapsed_ms, user.peak_memory_kb

    def run_oracle(self, executable: Path, input_bytes: bytes, user_output: bytes) -> tuple[str, bytes]:
        oracle = run_process(
            command=[str(executable)],
            stdin=input_bytes,
            cwd=self.work_dir,
            timeout_seconds=self.case_time_limit_seconds(),
            memory_limit_bytes=int(self.spec["memoryLimitBytes"]),
            output_limit_bytes=int(self.spec["maxOutputBytesPerCase"]),
            cancel_path=self.cancel_path,
        )
        self.raise_control_outcome(oracle)
        if oracle.status != "SUCCESS" or oracle.exit_code != 0:
            return "System Error", oracle.stdout
        status = "AC" if normalize_output(user_output) == normalize_output(oracle.stdout) else "WA"
        return status, oracle.stdout

    def run_special_judge(self, executable: Path, case_number: int, input_bytes: bytes, user_output: bytes) -> str:
        input_path = self.work_dir / f"{case_number}.in"
        user_path = self.work_dir / f"{case_number}.out"
        spj = run_process(
            command=[str(executable), str(input_path), str(user_path)],
            stdin=b"",
            cwd=self.work_dir,
            timeout_seconds=self.case_time_limit_seconds() * 2,
            memory_limit_bytes=int(self.spec["memoryLimitBytes"]),
            output_limit_bytes=int(self.spec["maxOutputBytesPerCase"]),
            cancel_path=self.cancel_path,
        )
        self.raise_control_outcome(spj)
        return "AC" if spj.status == "SUCCESS" and spj.exit_code == 0 else "WA"

    def accept_case_result(self, case_number: int, status: str, elapsed_ms: int, memory_kb: int) -> None:
        result = {
            "caseNumber": case_number,
            "status": status,
            "timeUsed": elapsed_ms,
            "memoryUsed": memory_kb,
        }
        self.summary["completedCases"] += 1
        if status == "AC":
            self.summary["ac"] += 1
        elif status == "WA":
            self.summary["wa"] += 1
        elif status == "TLE":
            self.summary["tle"] += 1
        elif status == "MLE":
            self.summary["mle"] += 1
        elif status == "RE":
            self.summary["re"] += 1
        elif status == "OUTPUT_LIMIT_EXCEEDED":
            self.summary["outputLimitExceeded"] += 1
        else:
            self.summary["systemError"] += 1
        if status != "AC" and self.summary["firstFailedCase"] is None:
            self.summary["firstFailedCase"] = case_number
        if status != "AC" and len(self.summary["failureSamples"]) < 20:
            self.summary["failureSamples"].append(result.copy())
        self.emit(
            "RUN_FINISHED",
            f"case {case_number} {status}",
            case_number=case_number,
            status=status,
            result=result,
        )
        self.write_summary()

    def should_stop_after_result(self, status: str) -> bool:
        enabled = self.spec.get("stopOnFirstNonAc", False)
        if isinstance(enabled, str):
            enabled = enabled.strip().lower() == "true"
        return bool(enabled) and status != "AC"

    def write_case_artifacts(
        self,
        case_number: int,
        input_bytes: Optional[bytes] = None,
        user_output: Optional[bytes] = None,
        answer_output: Optional[bytes] = None,
    ) -> None:
        artifacts = (
            (".in", input_bytes),
            (".out", user_output),
            (".ans", answer_output),
        )
        for extension, content in artifacts:
            if content is not None:
                (self.work_dir / f"{case_number}{extension}").write_bytes(normalize_artifact_bytes(content))

    def raise_control_outcome(self, outcome: ProcessOutcome) -> None:
        if outcome.status == "CANCELLED":
            raise Cancelled()

    def check_control_files(self) -> None:
        if self.cancel_path.exists():
            raise Cancelled()
        max_runtime = parse_duration_seconds(self.spec["maxTaskRuntime"])
        if time.monotonic() - self.started_at > max_runtime:
            raise BudgetExceeded()

    def case_time_limit_seconds(self) -> float:
        return parse_duration_seconds(self.spec["caseTimeLimit"])

    def emit(self, event_type: str, message: str, **extra) -> None:
        event = {
            "judgeId": self.judge_id,
            "type": event_type,
            "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "message": bounded(message),
        }
        if "case_number" in extra:
            extra["caseNumber"] = extra.pop("case_number")
        event.update(extra)
        with self.events_path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(event, separators=(",", ":")) + "\n")

    def write_summary(self) -> None:
        tmp_path = self.summary_path.with_suffix(".json.tmp")
        with tmp_path.open("w", encoding="utf-8") as file:
            json.dump(self.summary, file, separators=(",", ":"))
        os.replace(tmp_path, self.summary_path)


def run_process(
    command: list[str],
    stdin: bytes,
    cwd: Path,
    timeout_seconds: float,
    memory_limit_bytes: int,
    output_limit_bytes: int,
    cancel_path: Path,
) -> ProcessOutcome:
    start = time.monotonic()
    peak_memory_kb = 0
    killed_for_memory = threading.Event()
    killed_for_cancel = threading.Event()
    creationflags = 0
    popen_kwargs = {}
    if os.name == "nt":
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    else:
        popen_kwargs["start_new_session"] = True
    process = subprocess.Popen(
        command,
        cwd=str(cwd),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        creationflags=creationflags,
        **popen_kwargs,
    )

    def monitor() -> None:
        nonlocal peak_memory_kb
        while process.poll() is None:
            memory = process_memory_bytes(process.pid)
            peak_memory_kb = max(peak_memory_kb, memory // 1024)
            if memory_limit_bytes > 0 and memory > memory_limit_bytes:
                killed_for_memory.set()
                kill_process_tree(process)
                return
            if cancel_path.exists():
                killed_for_cancel.set()
                kill_process_tree(process)
                return
            time.sleep(POLL_SECONDS)

    monitor_thread = threading.Thread(target=monitor, daemon=True)
    monitor_thread.start()
    try:
        stdout, stderr = process.communicate(stdin, timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        kill_process_tree(process)
        stdout, stderr = process.communicate()
        elapsed = elapsed_ms(start)
        return ProcessOutcome("TIME_LIMIT_EXCEEDED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    finally:
        monitor_thread.join(timeout=1)
    elapsed = elapsed_ms(start)
    if killed_for_cancel.is_set():
        return ProcessOutcome("CANCELLED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    if killed_for_memory.is_set():
        return ProcessOutcome("MEMORY_LIMIT_EXCEEDED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    if len(stdout) > output_limit_bytes or len(stderr) > output_limit_bytes:
        return ProcessOutcome("OUTPUT_LIMIT_EXCEEDED", process.returncode or 0, stdout[:output_limit_bytes], stderr[:output_limit_bytes], elapsed, peak_memory_kb)
    return ProcessOutcome("SUCCESS", process.returncode or 0, stdout, stderr, elapsed, peak_memory_kb)


def kill_process_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(process.pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except Exception:
            process.kill()


def process_memory_bytes(pid: int) -> int:
    if os.name == "nt":
        return windows_working_set_bytes(pid)
    status = Path(f"/proc/{pid}/status")
    try:
        for line in status.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    except Exception:
        return 0
    return 0


def windows_working_set_bytes(pid: int) -> int:
    try:
        import ctypes
        from ctypes import wintypes

        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        PROCESS_VM_READ = 0x0010

        class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
            _fields_ = [
                ("cb", wintypes.DWORD),
                ("PageFaultCount", wintypes.DWORD),
                ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t),
            ]

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, False, pid)
        if not handle:
            return 0
        counters = PROCESS_MEMORY_COUNTERS()
        counters.cb = ctypes.sizeof(counters)
        ok = psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
        kernel32.CloseHandle(handle)
        return int(counters.WorkingSetSize) if ok else 0
    except Exception:
        return 0


def parse_duration_seconds(value) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip().upper()
        if text.startswith("PT") and text.endswith("S"):
            return float(text[2:-1])
        if text.startswith("PT") and text.endswith("M"):
            return float(text[2:-1]) * 60
        if text.startswith("PT") and "M" in text and text.endswith("S"):
            minutes, seconds = text[2:-1].split("M", 1)
            return float(minutes) * 60 + float(seconds)
    raise ValueError(f"unsupported duration value: {value!r}")


def normalize_output(value: bytes) -> str:
    return value.decode("utf-8", errors="replace").strip()


def normalize_artifact_bytes(value: bytes) -> bytes:
    return value.replace(b"\r\n", b"\n")


def decode_limited(value: bytes, limit: int) -> str:
    return value[:limit].decode("utf-8", errors="replace")


def bounded(message: str) -> str:
    encoded = (message or "").encode("utf-8")
    if len(encoded) <= MAX_EVENT_MESSAGE_BYTES:
        return message or ""
    return encoded[:MAX_EVENT_MESSAGE_BYTES].decode("utf-8", errors="ignore")


def elapsed_ms(start: float) -> int:
    return int((time.monotonic() - start) * 1000)


def executable_name(name: str) -> str:
    return f"{name}.exe" if os.name == "nt" else name


class CompileError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class Cancelled(Exception):
    pass


class BudgetExceeded(Exception):
    pass


class SecurityViolation(Exception):
    pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a sandbox task spec and emit stable judge events.")
    parser.add_argument("task_spec", help="Path to sandbox-task.json")
    args = parser.parse_args()
    return TaskRunner(Path(args.task_spec)).run()


if __name__ == "__main__":
    sys.exit(main())
