#!/usr/bin/env python3
import argparse
import errno
import io
import json
import os
import re
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
from runner_manifest import CAPABILITIES, DECIMAL_TOKEN, ManifestError, load_manifest, parse_decimal, validate_token


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
CLEANUP_SECONDS = 2.0


@dataclass
class ProcessOutcome:
    status: str
    exit_code: int
    stdout: bytes
    stderr: bytes
    elapsed_ms: int
    peak_memory_kb: int


class TaskRunner:
    def __init__(self, spec_path: Path, work_root: Path, stage_executor=None):
        self.spec, self.work_dir, self.attempt_dir, self.execution_dir = load_manifest(spec_path, work_root)
        self.stage_executor = stage_executor
        self.judge_id = self.spec["judgeId"]
        self.events_path = self.attempt_dir / "runner-events.jsonl"
        self.summary_path = self.attempt_dir / "runner-summary.json"
        self.cancel_path = self.attempt_dir / "cancel.requested"
        self.bin_dir = self.execution_dir / "bin"
        self.started_at = time.monotonic()
        self.event_sequence = 0
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
        with self.events_path.open("x", encoding="utf-8"):
            pass
        self.bin_dir.mkdir(parents=True, exist_ok=False)
        try:
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



    def compile_sources(self) -> dict[str, Path]:
        self.emit("COMPILE_STARTED", "compile started")
        executables: dict[str, Path] = {}
        source_paths = self.spec.get("sourcePaths", {})
        for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"):
            if role not in source_paths:
                continue
            source = self.work_dir / source_paths[role]
            output = self.bin_dir / executable_name(role.lower())
            remaining_runtime = self.check_control_files()
            limits = {
                "stdin": b"",
                "timeout_seconds": min(COMPILE_TIMEOUT_SECONDS, remaining_runtime),
                "memory_limit_bytes": max(int(self.spec["memoryLimitBytes"]) * 2, 256 * 1024 * 1024),
                "output_limit_bytes": COMPILE_OUTPUT_LIMIT_BYTES,
                "cancel_path": self.cancel_path,
            }
            if self.stage_executor is None:
                outcome = run_process(command=["g++", str(source), "-o", str(output), "-O2", "-std=c++17"],
                                      cwd=self.execution_dir, **limits)
            else:
                outcome = self.call_stage("compile", role, source, output, **limits)
            if outcome.status == "CANCELLED":
                raise Cancelled()
            if outcome.status == "TIME_LIMIT_EXCEEDED" and remaining_runtime <= COMPILE_TIMEOUT_SECONDS:
                raise BudgetExceeded()
            self.check_control_files()
            if outcome.status == "TIME_LIMIT_EXCEEDED":
                self.emit("COMPILE_FINISHED", f"compile timed out for {role}", status="COMPILE_ERROR")
                raise CompileError(f"{role} compile timed out")
            if outcome.status == "OUTPUT_LIMIT_EXCEEDED":
                message = f"OUTPUT_LIMIT_EXCEEDED while compiling {role}: " + decode_limited(
                    outcome.stderr or outcome.stdout, MAX_EVENT_MESSAGE_BYTES)
                self.emit("COMPILE_FINISHED", message, status="COMPILE_ERROR")
                raise CompileError(message)
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
            generated = self.run_generator(executables["GENERATOR"], case_number)
            input_bytes = generated.stdout
            self.write_case_artifacts(case_number, input_bytes=input_bytes)
            if generated.status == "OUTPUT_LIMIT_EXCEEDED":
                case_status, elapsed_ms, peak_memory_kb = generated.status, generated.elapsed_ms, generated.peak_memory_kb
            else:
                case_status, elapsed_ms, peak_memory_kb = self.run_user_and_judge(executables, case_number, input_bytes)
            self.accept_case_result(case_number, case_status, elapsed_ms, peak_memory_kb)
            if self.should_stop_after_result(case_status):
                self.summary["stoppedReason"] = "Stopped after first non-AC test case"
                break
        self.write_summary()
        self.emit("SUMMARY", "summary", status="DONE", summary=self.summary)
        self.emit("COMPLETED", "completed")
        return EXIT_COMPLETED

    def run_generator(self, executable: Path, case_number: int) -> ProcessOutcome:
        outcome = self.run_role("GENERATOR", executable, f"{case_number}\n".encode("utf-8"))
        self.raise_control_outcome(outcome)
        if outcome.status != "OUTPUT_LIMIT_EXCEEDED" and (outcome.status != "SUCCESS" or outcome.exit_code != 0):
            raise RuntimeError("generator failed")
        return outcome

    def run_user_and_judge(self, executables: dict[str, Path], case_number: int, input_bytes: bytes) -> tuple[str, int, int]:
        self.emit("RUN_STARTED", f"case {case_number} started", case_number=case_number)
        user = self.run_role("USER", executables["USER"], input_bytes)
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
        oracle = self.run_role("ORACLE", executable, input_bytes)
        self.raise_control_outcome(oracle)
        if oracle.status == "OUTPUT_LIMIT_EXCEEDED":
            return "OUTPUT_LIMIT_EXCEEDED", oracle.stdout
        if oracle.status != "SUCCESS" or oracle.exit_code != 0:
            return "System Error", oracle.stdout
        try:
            status = "AC" if outputs_match(user_output, oracle.stdout, self.spec["comparison"]) else "WA"
        except (UnicodeError, ValueError, ArithmeticError):
            status = "System Error"
        return status, oracle.stdout

    def run_special_judge(self, executable: Path, case_number: int, input_bytes: bytes, user_output: bytes) -> str:
        input_path = self.work_dir / f"{case_number}.in"
        user_path = self.work_dir / f"{case_number}.out"
        spj = self.run_role("SPECIAL_JUDGE", executable, b"",
                            input_files={"case.in": input_path, "case.out": user_path}, timeout_multiplier=2)
        self.raise_control_outcome(spj)
        if spj.status == "OUTPUT_LIMIT_EXCEEDED":
            return "OUTPUT_LIMIT_EXCEEDED"
        if spj.status != "SUCCESS":
            return "System Error"
        return "AC" if spj.exit_code == 0 else "WA"

    def call_stage(self, method, *arguments, **limits):
        from linux_stage_executor import StageSecurityError
        try:
            return getattr(self.stage_executor, method)(*arguments, **limits)
        except StageSecurityError:
            raise SecurityViolation("isolated execution stage rejected") from None
        except Exception:
            raise RuntimeError("isolated execution stage failed") from None

    def run_role(self, role, executable, stdin, input_files=None, timeout_multiplier=1):
        files = input_files or {}
        remaining_runtime = self.check_control_files()
        case_timeout = self.case_time_limit_seconds() * timeout_multiplier
        limits = {
            "stdin": stdin,
            "timeout_seconds": min(case_timeout, remaining_runtime),
            "memory_limit_bytes": int(self.spec["memoryLimitBytes"]),
            "output_limit_bytes": int(self.spec["maxOutputBytesPerCase"]),
            "cancel_path": self.cancel_path,
        }
        if self.stage_executor is None:
            outcome = run_process(command=[str(executable)] + [str(path) for path in files.values()],
                                  cwd=self.execution_dir, **limits)
        else:
            outcome = self.call_stage("execute", role, executable, ["/input/" + name for name in files], files, **limits)
        if outcome.status == "CANCELLED":
            return outcome
        if outcome.status == "TIME_LIMIT_EXCEEDED" and remaining_runtime <= case_timeout:
            raise BudgetExceeded()
        self.check_control_files()
        return outcome

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

    def check_control_files(self) -> float:
        if self.cancel_path.exists():
            raise Cancelled()
        remaining_runtime = self.spec["maxTaskRuntimeMillis"] / 1000 - (time.monotonic() - self.started_at)
        if remaining_runtime <= 0:
            raise BudgetExceeded()
        return remaining_runtime

    def case_time_limit_seconds(self) -> float:
        return self.spec["caseTimeLimitMillis"] / 1000

    def emit(self, event_type: str, message: str, **extra) -> None:
        self.event_sequence += 1
        event = {
            "protocolVersion": self.spec["protocolVersion"],
            "judgeId": self.judge_id,
            "attemptId": self.spec["attemptId"],
            "sequence": self.event_sequence,
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
    execution_deadline = start + timeout_seconds
    cleanup_deadline = None
    peak_memory_kb = 0
    killed_for_memory = threading.Event()
    killed_for_cancel = threading.Event()
    killed_for_output = threading.Event()
    capture_lock = threading.Lock()
    stdout_buffer = bytearray()
    stderr_buffer = bytearray()
    total_output_bytes = 0
    io_errors = []
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

    def fail_io(error: Exception) -> None:
        with capture_lock:
            io_errors.append(error)
        kill_process_tree(process)

    def capture(pipe, is_stdout: bool) -> None:
        nonlocal total_output_bytes
        try:
            with pipe:
                while chunk := pipe.read1(io.DEFAULT_BUFFER_SIZE):
                    with capture_lock:
                        total_output_bytes = min(output_limit_bytes + 1, total_output_bytes + len(chunk))
                        if is_stdout:
                            kept_bytes = min(len(chunk), output_limit_bytes - len(stdout_buffer))
                            del stderr_buffer[output_limit_bytes - len(stdout_buffer) - kept_bytes:]
                            stdout_buffer.extend(chunk[:kept_bytes])
                        else:
                            remaining = output_limit_bytes - len(stdout_buffer) - len(stderr_buffer)
                            stderr_buffer.extend(chunk[:remaining])
                        must_stop = total_output_bytes > output_limit_bytes and not killed_for_output.is_set()
                        if must_stop:
                            killed_for_output.set()
                    if must_stop:
                        kill_process_tree(process)
        except Exception as error:
            fail_io(error)

    def feed_input() -> None:
        try:
            with process.stdin:
                process.stdin.write(stdin)
        except BrokenPipeError:
            pass
        except OSError as error:
            if os.name != "nt" or error.errno != errno.EINVAL:
                fail_io(error)
        except Exception as error:
            fail_io(error)

    monitor_thread = threading.Thread(target=monitor, daemon=True)
    pumps = [threading.Thread(target=capture, args=(process.stdout, True), daemon=True),
             threading.Thread(target=capture, args=(process.stderr, False), daemon=True),
             threading.Thread(target=feed_input, daemon=True)]
    monitor_thread.start()
    timed_out = False
    try:
        for pump in pumps:
            pump.start()
        try:
            process.wait(timeout=max(0, execution_deadline - time.monotonic()))
            for pump in pumps:
                pump.join(timeout=max(0, execution_deadline - time.monotonic()))
            if any(pump.is_alive() for pump in pumps):
                raise subprocess.TimeoutExpired(command, timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            cleanup_deadline = time.monotonic() + CLEANUP_SECONDS
            kill_process_tree(process, max(0, cleanup_deadline - time.monotonic()))
            try:
                process.wait(timeout=max(0, cleanup_deadline - time.monotonic()))
            except subprocess.TimeoutExpired as error:
                raise OSError("Process cleanup deadline exceeded; execution domain stop is not confirmed") from error
    except BaseException:
        if cleanup_deadline is None:
            cleanup_deadline = time.monotonic() + CLEANUP_SECONDS
        kill_process_tree(process, max(0, cleanup_deadline - time.monotonic()))
        raise
    finally:
        if cleanup_deadline is None:
            cleanup_deadline = time.monotonic() + CLEANUP_SECONDS
        for pump in pumps:
            if pump.ident is not None:
                pump.join(timeout=max(0, cleanup_deadline - time.monotonic()))
        monitor_thread.join(timeout=max(0, cleanup_deadline - time.monotonic()))
        if process.poll() is None or any(pump.is_alive() for pump in pumps) or monitor_thread.is_alive():
            raise OSError("Process cleanup deadline exceeded; execution domain stop is not confirmed")
        for pipe in (process.stdin, process.stdout, process.stderr):
            pipe.close()
    if io_errors:
        raise io_errors[0]
    stdout = bytes(stdout_buffer)
    stderr = bytes(stderr_buffer)
    elapsed = elapsed_ms(start)
    if timed_out:
        return ProcessOutcome("TIME_LIMIT_EXCEEDED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    if killed_for_cancel.is_set():
        return ProcessOutcome("CANCELLED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    if killed_for_memory.is_set():
        return ProcessOutcome("MEMORY_LIMIT_EXCEEDED", process.returncode or -1, stdout, stderr, elapsed, peak_memory_kb)
    if killed_for_output.is_set():
        return ProcessOutcome("OUTPUT_LIMIT_EXCEEDED", process.returncode or 0, stdout, stderr, elapsed, peak_memory_kb)
    return ProcessOutcome("SUCCESS", process.returncode or 0, stdout, stderr, elapsed, peak_memory_kb)


def kill_process_tree(process: subprocess.Popen, timeout_seconds: float = CLEANUP_SECONDS) -> None:
    if os.name == "nt":
        if process.poll() is not None:
            return
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(process.pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=timeout_seconds,
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





def normalize_output(value: bytes) -> str:
    return value.decode("utf-8").strip(" \t\r\n\f\v")


def outputs_match(user_output: bytes, oracle_output: bytes, comparison: dict) -> bool:
    user_text = normalize_output(user_output)
    oracle_text = normalize_output(oracle_output)
    user_tokens = re.findall(r"[^ \t\n\r\f\v]+", user_text)
    oracle_tokens = re.findall(r"[^ \t\n\r\f\v]+", oracle_text)
    for token in user_tokens + oracle_tokens:
        validate_token(token)
    if comparison["mode"] == "LEGACY_TEXT":
        return user_text == oracle_text
    precision = parse_decimal(comparison["precision"])
    if len(user_tokens) != len(oracle_tokens):
        return False
    for user_token, oracle_token in zip(user_tokens, oracle_tokens):
        if re.fullmatch(r"[+-]?[0-9]+", user_token) and re.fullmatch(r"[+-]?[0-9]+", oracle_token):
            if int(user_token) != int(oracle_token):
                return False
        elif DECIMAL_TOKEN.fullmatch(user_token) and DECIMAL_TOKEN.fullmatch(oracle_token):
            user_value = parse_decimal(user_token)
            oracle_value = parse_decimal(oracle_token)
            common_exponent = min(user_value.as_tuple().exponent, oracle_value.as_tuple().exponent,
                                  precision.as_tuple().exponent)
            difference = abs(scaled_coefficient(user_value, common_exponent)
                             - scaled_coefficient(oracle_value, common_exponent))
            if difference > scaled_coefficient(precision, common_exponent):
                return False
        elif user_token != oracle_token:
            return False
    return True


def scaled_coefficient(value, common_exponent: int) -> int:
    parts = value.as_tuple()
    coefficient = 0
    for digit in parts.digits:
        coefficient = coefficient * 10 + digit
    if parts.sign:
        coefficient = -coefficient
    return coefficient * 10 ** (parts.exponent - common_exponent)


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
    parser.add_argument("task_spec", nargs="?", help="Path to v2 task-spec.json")
    parser.add_argument("--work-root", type=Path, default=Path.cwd())
    parser.add_argument("--capabilities", action="store_true")
    args = parser.parse_args()
    if args.capabilities:
        print(json.dumps(CAPABILITIES, separators=(",", ":")))
        return 0
    if args.task_spec is None:
        parser.error("task_spec is required")
    try:
        return TaskRunner(Path(args.task_spec), args.work_root).run()
    except (ManifestError, OSError, ValueError):
        print("runner manifest rejected before execution", file=sys.stderr)
        return EXIT_SECURITY_VIOLATION


if __name__ == "__main__":
    sys.exit(main())
