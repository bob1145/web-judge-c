import errno
import json
import math
import os
import re
import shutil
import stat
import subprocess
import uuid
from dataclasses import dataclass, replace
from pathlib import Path


class StageSecurityError(ValueError):
    pass


@dataclass(frozen=True)
class LinuxStageConfig:
    runtime_command: str
    runtime_endpoint: str
    executor_image: str
    stage_volume: str
    stage_root: Path
    uid: int
    gid: int
    pids_limit: int
    cpus: float
    input_limit_bytes: int
    artifact_limit_bytes: int
    command_timeout_seconds: float
    volume_limit_bytes: int
    volume_holder_id: str

    def __post_init__(self):
        if not isinstance(self.runtime_command, str) or not Path(self.runtime_command).is_absolute():
            raise ValueError("runtime command must be an absolute executable path")
        if not re.fullmatch(r"unix:///[^\s,\x00]+", self.runtime_endpoint):
            raise ValueError("runtime endpoint must be an explicit Unix socket")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", self.executor_image):
            raise ValueError("executor image must be a complete immutable image ID")
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]+", self.stage_volume):
            raise ValueError("invalid stage volume")
        for value in (self.uid, self.gid, self.pids_limit, self.input_limit_bytes, self.artifact_limit_bytes, self.volume_limit_bytes):
            if type(value) is not int or value <= 0:
                raise ValueError("identity and resource limits must be positive integers")
        for value in (self.cpus, self.command_timeout_seconds):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                raise ValueError("CPU and command timeout must be finite and positive")
        if not isinstance(self.stage_root, Path) or not self.stage_root.is_absolute():
            raise ValueError("stage root must be an absolute directory")
        if not isinstance(self.volume_holder_id, str) or not re.fullmatch(r"[0-9a-f]{64}", self.volume_holder_id):
            raise ValueError("stage volume holder must be a complete container ID")


def open_directory(path):
    path = Path(path)
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("directory must be absolute and normalized")
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for component in path.parts[1:]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def copy_regular(source, destination, limit, mode):
    source = Path(source)
    destination = Path(destination)
    try:
        parent = open_directory(source.parent)
        try:
            descriptor = os.open(source.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        finally:
            os.close(parent)
    except OSError as error:
        if error.errno in (errno.ELOOP, errno.ENOTDIR):
            raise StageSecurityError("stage transfer path contains a link or non-directory") from None
        raise
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
            raise StageSecurityError("stage transfer requires a bounded, unlinked regular file")
        destination_parent = open_directory(destination.parent)
        try:
            output = os.open(destination.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode, dir_fd=destination_parent)
            try:
                remaining = limit
                while True:
                    data = os.read(descriptor, min(65536, remaining + 1))
                    if not data:
                        break
                    if len(data) > remaining:
                        raise StageSecurityError("stage transfer exceeded its limit")
                    remaining -= len(data)
                    pending = memoryview(data)
                    while pending:
                        written = os.write(output, pending)
                        if written <= 0:
                            raise OSError("stage transfer made no progress")
                        pending = pending[written:]
                os.fchmod(output, mode)
                os.fsync(output)
            except BaseException:
                os.unlink(destination.name, dir_fd=destination_parent)
                raise
            finally:
                os.close(output)
        finally:
            os.close(destination_parent)
    finally:
        os.close(descriptor)


class LinuxStageExecutor:
    def __init__(self, config, attempt_id, journal_root, process_runner):
        if str(uuid.UUID(attempt_id)) != attempt_id:
            raise ValueError("attempt identity must be a canonical UUID")
        self.config = config
        self.attempt_id = attempt_id
        self.journal_root = Path(journal_root)
        self.process_runner = process_runner
        self.journal_root.mkdir(mode=0o700, exist_ok=True)
        for directory in (config.stage_root, self.journal_root):
            descriptor = open_directory(directory)
            os.close(descriptor)
        self.client_config = self.journal_root / "runtime-client"
        self.client_config.mkdir(mode=0o700, exist_ok=True)
        descriptor = open_directory(self.client_config)
        os.close(descriptor)

    def compile(self, role, source, output, **limits):
        return self._stage(role, "compile", {"source.cpp": source},
                           ["g++", "/input/source.cpp", "-o", "/scratch/program", "-O2", "-std=c++17"],
                           Path(output), limits)

    def execute(self, role, executable, arguments, input_files, **limits):
        if not isinstance(arguments, list) or not all(isinstance(value, str) and "\x00" not in value for value in arguments):
            raise ValueError("arguments must be explicit strings")
        if not isinstance(input_files, dict) or not set(input_files).issubset({"case.in", "case.out"}):
            raise ValueError("unexpected stage input")
        return self._stage(role, "execute", {"program": executable, **input_files},
                           ["/input/program", *arguments], None, limits)

    def _argv(self, *arguments):
        return [self.config.runtime_command, "--config", str(self.client_config),
                "--host", self.config.runtime_endpoint, *arguments]

    def _control(self, *arguments, check=True):
        result = subprocess.run(self._argv(*arguments), stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=self.config.command_timeout_seconds, check=False)
        if len(result.stdout) > self.config.input_limit_bytes or len(result.stderr) > self.config.input_limit_bytes:
            raise RuntimeError("runtime control response exceeds the configured input limit")
        if check and result.returncode != 0:
            raise RuntimeError("stage runtime control command failed")
        return result

    def _journal(self, record, state):
        record["state"] = state
        target = self.journal_root / (record["stage"] + ".json")
        temporary = target.with_suffix("." + uuid.uuid4().hex + ".tmp")
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(record, stream, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        descriptor = open_directory(self.journal_root)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _inspect(self, record):
        result = self._control("container", "inspect", record["name"], check=False)
        if result.returncode != 0:
            error = result.stderr.decode("utf-8", errors="replace").strip()
            if error in ("Error: No such container: " + record["name"],
                         "Error response from daemon: No such container: " + record["name"],
                         "Error: No such object: " + record["name"]):
                return None
            raise RuntimeError("cannot establish stage container ownership")
        payload = json.loads(result.stdout)
        if not isinstance(payload, list) or len(payload) != 1:
            raise RuntimeError("invalid stage container inspection")
        container = payload[0]
        if any(container.get("Config", {}).get("Labels", {}).get(key) != value for key, value in record["labels"].items()):
            raise StageSecurityError("stage container ownership mismatch")
        identity = container.get("Id", "")
        if not re.fullmatch(r"[0-9a-f]{64}", identity) or (record.get("containerId") and record["containerId"] != identity):
            raise StageSecurityError("stage container identity mismatch")
        return container

    def _remove(self, record):
        container = self._inspect(record)
        if container is not None:
            record["containerId"] = container["Id"]
            self._journal(record, "REMOVING")
            self._control("container", "rm", "--force", container["Id"])
            if self._inspect(record) is not None:
                raise RuntimeError("stage container remains after removal")
        self._journal(record, "REMOVED")

    @staticmethod
    def _cancelled_outcome():
        from task_runner import ProcessOutcome
        return ProcessOutcome("CANCELLED", -1, b"", b"", 0, 0)

    @staticmethod
    def _exit_state(container):
        state = container.get("State", {})
        if type(state.get("ExitCode")) is not int or type(state.get("Running")) is not bool or type(state.get("OOMKilled")) is not bool:
            raise RuntimeError("stage inspection contains an invalid exit state")
        return state

    def _stage(self, role, phase, inputs, command, output, limits):
        if role not in {"GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"}:
            raise ValueError("unknown executable role")
        required = {"stdin", "timeout_seconds", "memory_limit_bytes", "output_limit_bytes", "cancel_path"}
        if set(limits) != required or type(limits["memory_limit_bytes"]) is not int or limits["memory_limit_bytes"] <= 0:
            raise ValueError("explicit stage process limits are required")
        if not isinstance(limits["stdin"], bytes) or type(limits["output_limit_bytes"]) is not int or limits["output_limit_bytes"] <= 0:
            raise ValueError("bounded byte input/output are required")
        timeout = limits["timeout_seconds"]
        if isinstance(timeout, bool) or not isinstance(timeout, (float, int)) or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("stage timeout must be finite and positive")
        if limits["cancel_path"].exists():
            return self._cancelled_outcome()
        from linux_stage_volume import verify_stage_volume
        verify_stage_volume(self.config, self.attempt_id, self.config.volume_limit_bytes,
                            self.config.volume_holder_id, self._control)
        stage = str(uuid.uuid4())
        labels = {"io.webjudge.attempt": self.attempt_id, "io.webjudge.stage": stage,
                  "io.webjudge.role": role, "io.webjudge.managed": "stage"}
        record = {"stage": stage, "attempt": self.attempt_id, "role": role, "phase": phase,
                  "name": "wjc-stage-" + stage, "labels": labels, "volume": self.config.stage_volume}
        self._journal(record, "INTENT")
        directory = self.config.stage_root / stage
        directory.mkdir(mode=0o755)
        directory.chmod(0o755)
        created = False
        removed = False
        try:
            (directory / "input").mkdir(mode=0o755)
            (directory / "input").chmod(0o755)
            (directory / "scratch").mkdir(mode=0o777)
            (directory / "scratch").chmod(0o777)
            for name, source in inputs.items():
                copy_regular(source, directory / "input" / name,
                             self.config.artifact_limit_bytes if name == "program" else self.config.input_limit_bytes,
                             0o555 if name == "program" else 0o444)
            argv = ["container", "create", "--name", record["name"], "--network", "none",
                    "--log-driver", "none",
                    "--user", f"{self.config.uid}:{self.config.gid}", "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--memory", str(limits["memory_limit_bytes"]),
                    "--memory-swap", str(limits["memory_limit_bytes"]), "--cpus", str(self.config.cpus),
                    "--pids-limit", str(self.config.pids_limit), "--env", "TMPDIR=/scratch",
                    "--workdir", "/scratch", "--interactive"]
            for key, value in labels.items():
                argv.extend(["--label", key + "=" + value])
            for name in ("input", "scratch"):
                mount = f"type=volume,source={self.config.stage_volume},target=/{name},volume-subpath={stage}/{name}"
                argv.extend(["--mount", mount + (",readonly" if name == "input" else "")])
            if limits["cancel_path"].exists():
                return self._cancelled_outcome()
            created = True
            result = self._control(*argv, self.config.executor_image, *command)
            identity = result.stdout.decode("ascii").strip()
            if not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise RuntimeError("runtime did not return a complete container identity")
            record["containerId"] = identity
            self._journal(record, "CREATED")
            container = self._inspect(record)
            if container is None:
                raise RuntimeError("created stage container is missing")
            self._journal(record, "STARTING")
            if limits["cancel_path"].exists():
                return self._cancelled_outcome()
            outcome = self.process_runner(command=self._argv("container", "start", "--attach", "--interactive", identity),
                                          cwd=self.journal_root, **{**limits, "memory_limit_bytes": 0})
            container = self._inspect(record)
            if container is None:
                raise RuntimeError("stage container disappeared before inspection")
            state = self._exit_state(container)
            status = outcome.status
            if state["Running"] and status in {"CANCELLED", "TIME_LIMIT_EXCEEDED", "OUTPUT_LIMIT_EXCEEDED"}:
                self._journal(record, "STOPPING")
                self._control("container", "kill", "--signal", "KILL", container["Id"])
                container = self._inspect(record)
                if container is None:
                    raise RuntimeError("stage container disappeared before stopped-state inspection")
                state = self._exit_state(container)
            if state["Running"]:
                raise RuntimeError("stage did not reach a verified exit state")
            if status == "SUCCESS":
                if state["OOMKilled"]:
                    status = "MEMORY_LIMIT_EXCEEDED"
                elif outcome.exit_code != state["ExitCode"]:
                    raise RuntimeError("stage attach and container exit disagree")
            outcome = replace(outcome, status=status, exit_code=state["ExitCode"], peak_memory_kb=0)
            record["outcome"] = {"status": status, "exitCode": outcome.exit_code, "oomKilled": state["OOMKilled"]}
            self._remove(record)
            removed = True
            if output is not None and outcome.status == "SUCCESS" and outcome.exit_code == 0:
                copy_regular(directory / "scratch" / "program", output, self.config.artifact_limit_bytes, 0o555)
            return outcome
        finally:
            try:
                if created and not removed:
                    self._remove(record)
                if not shutil.rmtree.avoids_symlink_attacks:
                    raise RuntimeError("stage cleanup requires descriptor-relative removal")
                shutil.rmtree(directory)
                self._journal(record, "CLEANED")
            except BaseException:
                self._journal(record, "CLEANUP_FAILED")
                raise
