"""Trusted stage-only supervisor; static capabilities are not a security probe.

Owner and mode checks protect control-file integrity, not confidentiality.
"""

import argparse
import json
import os
import stat
import sys
from dataclasses import fields
from pathlib import Path

import task_runner
from linux_stage_executor import LinuxStageConfig, LinuxStageExecutor, StageSecurityError, open_directory
from linux_stage_volume import verify_stage_volume
from runner_manifest import CAPABILITIES, load_manifest, require_fields, unique_fields


CONFIG_LIMIT_BYTES = 64 * 1024
CONFIG_FIELDS = {field.name for field in fields(LinuxStageConfig)}
REJECTION_MESSAGE = "supervisor configuration or execution rejected"


def _open_trusted_attempt(work_root, attempt_dir, attempt_id, config_owner_uid):
    if attempt_dir != work_root / "control" / "attempts" / attempt_id:
        raise StageSecurityError("supervisor attempt location does not match execution root")
    trusted_owners = {config_owner_uid, os.geteuid()}
    descriptor = open_directory(work_root)
    try:
        for component in (None, "control", "attempts", attempt_id):
            if component is not None:
                child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            metadata = os.fstat(descriptor)
            if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid not in trusted_owners or metadata.st_mode & 0o022:
                raise StageSecurityError("supervisor control directory integrity is not trusted")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def load_stage_config(config_path, attempt_dir, attempt_id, work_root, config_owner_uid):
    path = Path(config_path).absolute()
    if path != attempt_dir / "supervisor-config.json":
        raise StageSecurityError("supervisor configuration location does not match attempt")
    if type(config_owner_uid) is not int or config_owner_uid < 0:
        raise StageSecurityError("configuration writer identity must be an explicit nonnegative integer")
    parent = _open_trusted_attempt(Path(work_root).absolute(), attempt_dir, attempt_id, config_owner_uid)
    try:
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    finally:
        os.close(parent)
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1 or metadata.st_size > CONFIG_LIMIT_BYTES
                or metadata.st_uid != config_owner_uid or metadata.st_mode & 0o022):
            raise StageSecurityError("supervisor configuration must be a bounded trusted regular file")
        payload = bytearray()
        while len(payload) <= CONFIG_LIMIT_BYTES:
            chunk = os.read(descriptor, CONFIG_LIMIT_BYTES + 1 - len(payload))
            if not chunk:
                break
            payload.extend(chunk)
        final_metadata = os.fstat(descriptor)
        if len(payload) > CONFIG_LIMIT_BYTES or any(
                getattr(metadata, name) != getattr(final_metadata, name)
                for name in ("st_size", "st_nlink", "st_mtime_ns", "st_ctime_ns", "st_uid", "st_mode")):
            raise StageSecurityError("supervisor configuration changed during reading")
    finally:
        os.close(descriptor)
    config = json.loads(payload.decode("utf-8"), object_pairs_hook=unique_fields)
    require_fields(config, CONFIG_FIELDS | {"configVersion", "attemptId"}, "supervisor configuration")
    if type(config["configVersion"]) is not int or config["configVersion"] != 1 or config["attemptId"] != attempt_id:
        raise StageSecurityError("supervisor configuration identity or version mismatch")
    if not isinstance(config["stage_root"], str):
        raise StageSecurityError("stage root must be an explicit path string")
    parameters = {name: config[name] for name in CONFIG_FIELDS}
    parameters["stage_root"] = Path(parameters["stage_root"])
    result = LinuxStageConfig(**parameters)
    if result.uid in {config_owner_uid, os.geteuid()}:
        raise StageSecurityError("workload identity must differ from trusted control identities")
    return result


def run_supervisor(task_spec, work_root, stage_config, config_owner_uid):
    task_spec = Path(task_spec)
    work_root = Path(work_root)
    manifest, work_root, attempt_dir, _ = load_manifest(task_spec, work_root)
    config = load_stage_config(stage_config, attempt_dir, manifest["attemptId"], work_root, config_owner_uid)
    executor = LinuxStageExecutor(config, manifest["attemptId"], attempt_dir / "stage-journal",
                                  task_runner.run_process)
    verify_stage_volume(config, manifest["attemptId"], config.volume_limit_bytes,
                        config.volume_holder_id, executor._control)
    return task_runner.TaskRunner(task_spec, work_root, stage_executor=executor).run()


class SupervisorArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError("invalid supervisor arguments")


def parse_config_owner_uid(value):
    if not value.isascii() or not value.isdecimal():
        raise ValueError("configuration writer UID must be a nonnegative decimal integer")
    return int(value)


def main(arguments=None):
    parser = SupervisorArgumentParser(description=__doc__)
    parser.add_argument("task_spec", nargs="?", type=Path)
    parser.add_argument("--work-root", type=Path)
    parser.add_argument("--stage-config", type=Path)
    parser.add_argument("--config-owner-uid", type=parse_config_owner_uid)
    parser.add_argument("--capabilities", action="store_true")
    try:
        args = parser.parse_args(arguments)
        if args.capabilities:
            if any(value is not None for value in (args.task_spec, args.work_root, args.stage_config, args.config_owner_uid)):
                raise ValueError("capabilities must be requested separately")
            print(json.dumps(CAPABILITIES, separators=(",", ":")))
            return 0
        if any(value is None for value in (args.task_spec, args.work_root, args.stage_config, args.config_owner_uid)):
            raise ValueError("task spec, work root, stage config and configuration writer UID are required")
        return run_supervisor(args.task_spec, args.work_root, args.stage_config, args.config_owner_uid)
    except Exception:
        print(REJECTION_MESSAGE, file=sys.stderr)
        return task_runner.EXIT_SECURITY_VIOLATION


if __name__ == "__main__":
    sys.exit(main())
