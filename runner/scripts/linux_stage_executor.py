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


_ADMISSION_CLOSED = "stage-admission.closed"
_ADMISSION_PENDING = "stage-admission.pending"
_ADMISSION_LIMIT_BYTES = 65536


def _unique_fields(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("duplicate control field")
        result[name] = value
    return result


def _require_stage_journal_identity(journal_root, directory, owner_uid):
    metadata = os.fstat(directory)
    current = open_directory(journal_root)
    try:
        actual = os.fstat(current)
        if (not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != owner_uid or metadata.st_mode & 0o022
                or (metadata.st_dev, metadata.st_ino) != (actual.st_dev, actual.st_ino)
                or actual.st_uid != owner_uid or actual.st_mode & 0o022):
            raise StageSecurityError("stage journal requires trusted recovery")
    finally:
        os.close(current)


def _open_locked_stage_journal(journal_root, owner_uid):
    import fcntl

    directory = None
    try:
        directory = open_directory(journal_root)
        _require_stage_journal_identity(journal_root, directory, owner_uid)
        try:
            fcntl.flock(directory, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise StageSecurityError("stage journal is locked or unavailable") from None
        _require_stage_journal_identity(journal_root, directory, owner_uid)
        return directory
    except BaseException as error:
        if directory is not None:
            os.close(directory)
        if isinstance(error, OSError):
            raise StageSecurityError("stage journal requires trusted recovery") from None
        raise


def _admission_entry_exists(directory, name):
    try:
        os.stat(name, dir_fd=directory, follow_symlinks=False)
        return True
    except FileNotFoundError:
        return False


def _require_admission_file(metadata, owner_uid):
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1
            or metadata.st_uid != owner_uid or stat.S_IMODE(metadata.st_mode) != 0o600
            or not 0 < metadata.st_size <= _ADMISSION_LIMIT_BYTES):
        raise StageSecurityError("stage admission marker requires trusted recovery")


def _same_admission_file(before, after):
    return all(getattr(before, field) == getattr(after, field)
               for field in ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink",
                             "st_size", "st_mtime_ns", "st_ctime_ns"))


def _read_closed_admission(directory, attempt_id, owner_uid):
    descriptor = os.open(_ADMISSION_CLOSED, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    try:
        before = os.fstat(descriptor)
        _require_admission_file(before, owner_uid)
        payload = bytearray()
        while len(payload) <= _ADMISSION_LIMIT_BYTES:
            chunk = os.read(descriptor, min(65536, _ADMISSION_LIMIT_BYTES + 1 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
        after = os.fstat(descriptor)
        current = os.stat(_ADMISSION_CLOSED, dir_fd=directory, follow_symlinks=False)
        if (len(payload) > _ADMISSION_LIMIT_BYTES or not _same_admission_file(before, after)
                or not _same_admission_file(before, current)):
            raise StageSecurityError("stage admission marker changed during reading")
    finally:
        os.close(descriptor)
    record = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_fields)
    if (not isinstance(record, dict) or set(record) != {"admissionVersion", "attemptId", "state"}
            or type(record["admissionVersion"]) is not int or record["admissionVersion"] != 1
            or record["attemptId"] != attempt_id or record["state"] != "CLOSED"):
        raise StageSecurityError("stage admission marker identity or version differs")
    return record


def close_existing_stage_admission(journal_root, attempt_id, owner_uid):
    try:
        if (type(attempt_id) is not str or str(uuid.UUID(attempt_id)) != attempt_id
                or type(owner_uid) is not int or owner_uid < 0):
            raise ValueError("explicit canonical stage admission identity is required")
        journal_root = Path(journal_root)
        directory = _open_locked_stage_journal(journal_root, owner_uid)
        try:
            if _admission_entry_exists(directory, _ADMISSION_PENDING):
                raise StageSecurityError("stage admission publication is incomplete")
            if _admission_entry_exists(directory, _ADMISSION_CLOSED):
                result = _read_closed_admission(directory, attempt_id, owner_uid)
                os.fsync(directory)
                _read_closed_admission(directory, attempt_id, owner_uid)
                _require_stage_journal_identity(journal_root, directory, owner_uid)
                return result
            result = {"admissionVersion": 1, "attemptId": attempt_id, "state": "CLOSED"}
            payload = json.dumps(result, separators=(",", ":")).encode("utf-8")
            descriptor = os.open(_ADMISSION_PENDING, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=directory)
            try:
                os.fchmod(descriptor, 0o600)
                os.fsync(directory)
                _require_stage_journal_identity(journal_root, directory, owner_uid)
                pending = memoryview(payload)
                while pending:
                    written = os.write(descriptor, pending)
                    if written <= 0:
                        raise OSError("stage admission publication made no progress")
                    pending = pending[written:]
                os.fsync(descriptor)
                written_metadata = os.fstat(descriptor)
                _require_admission_file(written_metadata, owner_uid)
                current = os.stat(_ADMISSION_PENDING, dir_fd=directory, follow_symlinks=False)
                if not _same_admission_file(written_metadata, current):
                    raise StageSecurityError("stage admission pending identity changed")
            finally:
                os.close(descriptor)
            _require_stage_journal_identity(journal_root, directory, owner_uid)
            if (_admission_entry_exists(directory, _ADMISSION_CLOSED)
                    or not _same_admission_file(written_metadata,
                                               os.stat(_ADMISSION_PENDING, dir_fd=directory, follow_symlinks=False))):
                raise StageSecurityError("stage admission publication identity changed")
            os.replace(_ADMISSION_PENDING, _ADMISSION_CLOSED, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
            result = _read_closed_admission(directory, attempt_id, owner_uid)
            _require_stage_journal_identity(journal_root, directory, owner_uid)
            return result
        finally:
            os.close(directory)
    except (OSError, ValueError, TypeError):
        raise StageSecurityError("stage admission closure requires trusted recovery") from None


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
    JOURNAL_VERSION = 1
    JOURNAL_LIMIT_BYTES = 65536

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
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(record, stream, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        descriptor = open_directory(self.journal_root)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _check_journal(self, directory):
        try:
            metadata = os.fstat(directory)
            if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
                raise ValueError("untrusted journal directory")
            with os.scandir(directory) as entries:
                for entry in entries:
                    if entry.name == "runtime-client":
                        metadata = os.stat(entry.name, dir_fd=directory, follow_symlinks=False)
                        if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
                            raise ValueError("untrusted runtime client directory")
                        continue
                    stage = entry.name.removesuffix(".json")
                    if str(uuid.UUID(stage)) != stage or entry.name != stage + ".json":
                        raise ValueError("unexpected journal entry")
                    descriptor = os.open(entry.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                    try:
                        before = os.fstat(descriptor)
                        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                                or before.st_uid != os.geteuid() or before.st_mode & 0o022
                                or not 0 < before.st_size <= self.JOURNAL_LIMIT_BYTES):
                            raise ValueError("untrusted journal record")
                        payload = bytearray()
                        while len(payload) <= self.JOURNAL_LIMIT_BYTES:
                            chunk = os.read(descriptor, self.JOURNAL_LIMIT_BYTES + 1 - len(payload))
                            if not chunk:
                                break
                            payload.extend(chunk)
                        after = os.fstat(descriptor)
                        if len(payload) > self.JOURNAL_LIMIT_BYTES or any(
                                getattr(before, field) != getattr(after, field)
                                for field in ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink",
                                              "st_size", "st_mtime_ns", "st_ctime_ns")):
                            raise ValueError("journal record changed")
                    finally:
                        os.close(descriptor)
                    record = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_fields)
                    required = {"journalVersion", "creationState", "stage", "attempt", "role",
                                "phase", "name", "labels", "volume", "state"}
                    if not isinstance(record, dict) or not required <= record.keys() or record.keys() - required - {"containerId", "outcome"}:
                        raise ValueError("invalid journal fields")
                    expected_labels = {"io.webjudge.attempt": self.attempt_id, "io.webjudge.stage": stage,
                                       "io.webjudge.role": record["role"], "io.webjudge.managed": "stage"}
                    if (type(record["journalVersion"]) is not int or record["journalVersion"] != self.JOURNAL_VERSION
                            or record["attempt"] != self.attempt_id or record["stage"] != stage
                            or record["volume"] != self.config.stage_volume or record["name"] != "wjc-stage-" + stage
                            or record["role"] not in {"GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"}
                            or record["phase"] not in {"compile", "execute"} or record["labels"] != expected_labels
                            or record["state"] != "CLEANED" or record["creationState"] not in {"NOT_REQUESTED", "CONFIRMED"}):
                        raise ValueError("journal requires recovery")
                    if record["creationState"] == "CONFIRMED":
                        if not isinstance(record.get("containerId"), str) or not re.fullmatch(r"[0-9a-f]{64}", record["containerId"]):
                            raise ValueError("journal identity missing")
                    elif "containerId" in record or "outcome" in record:
                        raise ValueError("unrequested journal contains runtime state")
        except Exception:
            raise StageSecurityError("stage journal requires trusted recovery") from None

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
        self._journal(record, "REMOVED" if record["creationState"] == "CONFIRMED" else "CLEANUP_PENDING")

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
        descriptor = _open_locked_stage_journal(self.journal_root, os.geteuid())
        try:
            if (_admission_entry_exists(descriptor, _ADMISSION_CLOSED)
                    or _admission_entry_exists(descriptor, _ADMISSION_PENDING)):
                raise StageSecurityError("stage admission is closed; trusted recovery required")
            self._check_journal(descriptor)
            _require_stage_journal_identity(self.journal_root, descriptor, os.geteuid())
            return self._stage_locked(role, phase, inputs, command, output, limits)
        finally:
            os.close(descriptor)

    def _stage_locked(self, role, phase, inputs, command, output, limits):
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
        record = {"journalVersion": self.JOURNAL_VERSION, "creationState": "NOT_REQUESTED",
                  "stage": stage, "attempt": self.attempt_id, "role": role, "phase": phase,
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
                mount = f"type=volume,source={self.config.stage_volume},target=/{name},volume-subpath={stage}/{name},volume-nocopy"
                argv.extend(["--mount", mount + (",readonly" if name == "input" else "")])
            if limits["cancel_path"].exists():
                return self._cancelled_outcome()
            record["creationState"] = "UNKNOWN"
            self._journal(record, "CREATE_REQUESTED")
            created = True
            result = self._control(*argv, self.config.executor_image, *command)
            identity = result.stdout.decode("ascii").strip()
            if not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise RuntimeError("runtime did not return a complete container identity")
            record["containerId"] = identity
            container = self._inspect(record)
            if container is None:
                raise RuntimeError("created stage container is missing")
            record["creationState"] = "CONFIRMED"
            self._journal(record, "CREATED")
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
            if record["creationState"] == "UNKNOWN":
                try:
                    self._remove(record)
                except BaseException:
                    pass
                try:
                    self._journal(record, "CREATE_UNKNOWN")
                except BaseException:
                    raise StageSecurityError("stage creation outcome is unknown; journal recovery required") from None
                raise StageSecurityError("stage creation outcome is unknown; recovery required") from None
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
