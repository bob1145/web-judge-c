import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import test_task_runner_contract as contract_fixture


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import linux_supervisor
import task_runner
from linux_stage_executor import LinuxStageConfig, LinuxStageExecutor, StageSecurityError
from runner_manifest import CAPABILITIES


@unittest.skipUnless(os.name == "posix", "descriptor-relative Linux supervisor contract")
class LinuxSupervisorContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        fixture = contract_fixture.TaskRunnerContractTest()
        self.work, self.spec = fixture._fixture(self.root / "work")
        self.attempt = self.spec.parent
        self.config_owner_uid = os.geteuid()
        self.control_directories = [self.work, self.work / "control", self.work / "control" / "attempts", self.attempt]
        for directory in self.control_directories:
            directory.chmod(0o755)
        self.stage_root = self.root / "bounded-stage-volume"
        self.stage_root.mkdir()
        self.config_path = self.attempt / "supervisor-config.json"
        self.config = {
            "configVersion": 1, "attemptId": self.attempt.name,
            "runtime_command": "/usr/bin/docker",
            "runtime_endpoint": "unix:///run/contract/docker.sock",
            "executor_image": "sha256:" + "a" * 64,
            "stage_volume": "test-stage-volume", "stage_root": str(self.stage_root),
            "uid": 65532 if self.config_owner_uid != 65532 else 65533,
            "gid": 65532, "pids_limit": 32, "cpus": 0.5,
            "input_limit_bytes": 4096, "artifact_limit_bytes": 8192,
            "command_timeout_seconds": 5, "volume_limit_bytes": 67108864,
            "volume_holder_id": "b" * 64,
        }
        self.write_config(self.config)
        self.config_path.chmod(0o644)

    def write_config(self, config):
        self.config_path.write_text(json.dumps(config), encoding="utf-8")

    def arguments(self, config_path=None, config_owner_uid=None):
        owner = self.config_owner_uid if config_owner_uid is None else config_owner_uid
        return [str(self.spec), "--work-root", str(self.work),
                "--stage-config", str(config_path or self.config_path), "--config-owner-uid", str(owner)]

    def load_config(self, config_owner_uid=None):
        owner = self.config_owner_uid if config_owner_uid is None else config_owner_uid
        return linux_supervisor.load_stage_config(self.config_path, self.attempt, self.attempt.name, self.work, owner)

    def simulate_owner(self, paths, owner):
        identities = {(path.stat().st_dev, path.stat().st_ino) for path in paths}
        original = os.fstat
        def metadata(descriptor):
            actual = original(descriptor)
            if (actual.st_dev, actual.st_ino) not in identities:
                return actual
            result = SimpleNamespace(**{name: getattr(actual, name) for name in
                                      ("st_mode", "st_uid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")})
            result.st_uid = owner
            return result
        return patch.object(linux_supervisor.os, "fstat", side_effect=metadata)

    def assert_no_task_output(self):
        self.assertFalse((self.work / "workspace").exists())
        self.assertFalse((self.attempt / "runner-events.jsonl").exists())
        self.assertFalse((self.attempt / "runner-summary.json").exists())

    def assert_config_rejected(self, config_path=None, config_owner_uid=None):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch.object(linux_supervisor, "LinuxStageExecutor") as executor, \
                patch.object(linux_supervisor, "verify_stage_volume") as volume, \
                patch.object(task_runner, "TaskRunner") as runner, \
                patch.object(task_runner, "run_process") as direct, \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = linux_supervisor.main(self.arguments(config_path, config_owner_uid))
        self.assertEqual(result, task_runner.EXIT_SECURITY_VIOLATION)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(stderr.getvalue(), linux_supervisor.REJECTION_MESSAGE + "\n")
        executor.assert_not_called()
        volume.assert_not_called()
        runner.assert_not_called()
        direct.assert_not_called()
        self.assert_no_task_output()
        self.assertFalse((self.attempt / "stage-journal").exists())

    def test_loads_all_explicit_config_fields_and_converts_only_stage_root(self):
        config = self.load_config()
        self.assertIsInstance(config, LinuxStageConfig)
        self.assertEqual(config.stage_root, self.stage_root)
        for field in linux_supervisor.CONFIG_FIELDS - {"stage_root"}:
            self.assertEqual(getattr(config, field), self.config[field])
        self.assert_no_task_output()

    def test_every_missing_field_is_rejected_without_execution(self):
        for field in self.config:
            with self.subTest(field=field):
                config = dict(self.config)
                config.pop(field)
                self.write_config(config)
                self.assert_config_rejected()

    def test_unknown_field_cannot_enable_local_fallback(self):
        self.write_config(dict(self.config, allow_local_fallback=True))
        self.assert_config_rejected()

    def test_duplicate_config_field_is_rejected(self):
        self.config_path.write_text('{"uid":65532,' + json.dumps(self.config)[1:], encoding="utf-8")
        self.assert_config_rejected()

    def test_version_must_be_exact_integer_one(self):
        for version in (True, 1.0, "1", 0, 2, None):
            with self.subTest(version=version):
                self.write_config(dict(self.config, configVersion=version))
                self.assert_config_rejected()

    def test_attempt_id_must_match_validated_manifest(self):
        self.write_config(dict(self.config, attemptId=str(uuid.uuid4())))
        self.assert_config_rejected()

    def test_resource_and_path_types_are_not_coerced(self):
        cases = [("uid", "65532"), ("gid", True), ("pids_limit", 0),
                 ("cpus", float("nan")), ("command_timeout_seconds", float("inf")),
                 ("volume_limit_bytes", 0), ("volume_holder_id", "short"),
                 ("runtime_command", "docker"), ("runtime_endpoint", "tcp://host:2375"),
                 ("executor_image", "executor:latest"), ("stage_root", 1),
                 ("stage_root", None), ("stage_root", "relative")]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                self.write_config(dict(self.config, **{field: value}))
                self.assert_config_rejected()

    def test_malformed_json_and_nonobject_config_are_rejected(self):
        for payload in (b"not-json", b"[]", b"null", b"\xff"):
            with self.subTest(payload=payload):
                self.config_path.write_bytes(payload)
                self.assert_config_rejected()

    def test_oversized_config_is_rejected_before_read(self):
        self.config_path.write_bytes(b" " * (linux_supervisor.CONFIG_LIMIT_BYTES + 1))
        with patch.object(linux_supervisor.os, "read", side_effect=AssertionError("must reject before read")) as reader:
            self.assert_config_rejected()
        reader.assert_not_called()

    def test_config_at_exact_byte_limit_is_accepted(self):
        payload = json.dumps(self.config).encode()
        self.config_path.write_bytes(payload + b" " * (linux_supervisor.CONFIG_LIMIT_BYTES - len(payload)))
        config = self.load_config()
        self.assertEqual(config.stage_root, self.stage_root)

    def test_external_config_path_is_rejected_without_opening(self):
        external = self.root / "external-config.json"
        external.write_text(json.dumps(self.config), encoding="utf-8")
        with patch.object(linux_supervisor, "open_directory", side_effect=AssertionError("must not open external path")) as opener:
            self.assert_config_rejected(external)
        opener.assert_not_called()

    def test_parent_traversal_spelling_is_rejected(self):
        path = self.attempt / ".." / self.attempt.name / "supervisor-config.json"
        self.assert_config_rejected(path)

    def test_config_symlink_is_rejected(self):
        target = self.root / "linked-config.json"
        target.write_text(json.dumps(self.config), encoding="utf-8")
        self.config_path.unlink()
        self.config_path.symlink_to(target)
        self.assert_config_rejected()

    def test_config_hardlink_is_rejected(self):
        os.link(self.config_path, self.root / "linked-config.json")
        self.assert_config_rejected()

    def test_config_directory_is_rejected(self):
        self.config_path.unlink()
        self.config_path.mkdir()
        self.assert_config_rejected()

    def test_config_fifo_is_rejected_without_reading(self):
        self.config_path.unlink()
        os.mkfifo(self.config_path)
        with patch.object(linux_supervisor.os, "read", side_effect=AssertionError("must not read FIFO")) as reader:
            self.assert_config_rejected()
        reader.assert_not_called()

    def test_config_parent_symlink_is_rejected(self):
        linked_root = self.root / "linked-work"
        linked_root.symlink_to(self.work, target_is_directory=True)
        linked = linked_root / "control" / "attempts" / self.attempt.name
        with self.assertRaises(OSError):
            linux_supervisor.load_stage_config(linked / "supervisor-config.json", linked, self.attempt.name,
                                               linked_root, self.config_owner_uid)

    def test_config_changed_during_read_is_rejected(self):
        original = os.read
        changed = False
        def changing_read(descriptor, length):
            nonlocal changed
            payload = original(descriptor, length)
            if not changed:
                changed = True
                with self.config_path.open("ab") as stream:
                    stream.write(b" ")
            return payload
        with patch.object(linux_supervisor.os, "read", side_effect=changing_read):
            self.assert_config_rejected()

    def test_config_owner_must_equal_explicit_writer(self):
        with self.simulate_owner([self.config_path], self.config_owner_uid + 101):
            self.assert_config_rejected()

    def test_config_group_or_other_write_is_rejected(self):
        for mode in (0o664, 0o646, 0o666):
            with self.subTest(mode=oct(mode)):
                self.config_path.chmod(mode)
                self.assert_config_rejected()

    def test_each_control_directory_rejects_group_or_other_write(self):
        for directory in self.control_directories:
            for mode in (0o775, 0o757):
                with self.subTest(directory=str(directory), mode=oct(mode)):
                    directory.chmod(mode)
                    try:
                        self.assert_config_rejected()
                    finally:
                        directory.chmod(0o755)

    def test_each_control_directory_rejects_untrusted_owner(self):
        for directory in self.control_directories:
            with self.subTest(directory=str(directory)), self.simulate_owner([directory], self.config_owner_uid + 101):
                self.assert_config_rejected()

    def test_writer_and_supervisor_can_share_control_directory_ownership(self):
        writer = max(self.config_owner_uid, self.config["uid"]) + 101
        with self.simulate_owner([self.config_path, self.work, self.attempt], writer):
            config = self.load_config(writer)
        self.assertEqual(config.uid, self.config["uid"])

    def test_workload_uid_cannot_equal_explicit_writer(self):
        writer = self.config["uid"]
        with self.simulate_owner([self.config_path], writer):
            self.assert_config_rejected(config_owner_uid=writer)

    def test_workload_uid_cannot_equal_supervisor_euid(self):
        with patch.object(linux_supervisor.os, "geteuid", return_value=self.config["uid"]):
            self.assert_config_rejected()

    def test_expected_owner_cannot_be_supplied_by_config_json(self):
        self.write_config(dict(self.config, config_owner_uid=self.config_owner_uid))
        self.assert_config_rejected()

    def test_api_owner_uid_must_be_exact_nonnegative_integer(self):
        for owner in (True, False, -1, 0.0, "0", None):
            with self.subTest(owner=owner), self.assertRaises(StageSecurityError):
                linux_supervisor.load_stage_config(self.config_path, self.attempt, self.attempt.name, self.work, owner)
        self.assert_no_task_output()
        self.assertFalse((self.attempt / "stage-journal").exists())

    def test_invalid_manifest_is_rejected_before_stage_setup(self):
        manifest = json.loads(self.spec.read_text())
        manifest["protocolVersion"] = 99
        self.spec.write_text(json.dumps(manifest), encoding="utf-8")
        self.assert_config_rejected()

    def test_success_verifies_volume_and_injects_actual_stage_executor(self):
        order = []
        def verify(*arguments):
            order.append("verify")
            self.assert_no_task_output()
        def run(runner):
            order.append("run")
            self.assertIsInstance(runner, task_runner.TaskRunner)
            self.assertIsInstance(runner.stage_executor, LinuxStageExecutor)
            self.assertEqual(runner.stage_executor.journal_root, self.attempt / "stage-journal")
            self.assertIs(runner.stage_executor.process_runner, task_runner.run_process)
            return task_runner.EXIT_COMPLETED
        with patch.object(linux_supervisor, "verify_stage_volume", side_effect=verify) as volume, \
                patch.object(task_runner.TaskRunner, "run", autospec=True, side_effect=run), \
                patch.object(task_runner, "run_process", side_effect=AssertionError("direct execution forbidden")) as direct:
            result = linux_supervisor.run_supervisor(self.spec, self.work, self.config_path, self.config_owner_uid)
        self.assertEqual(result, task_runner.EXIT_COMPLETED)
        self.assertEqual(order, ["verify", "run"])
        arguments = volume.call_args.args
        self.assertEqual(arguments[1:4], (self.attempt.name, self.config["volume_limit_bytes"], self.config["volume_holder_id"]))
        self.assertIsInstance(arguments[4].__self__, LinuxStageExecutor)
        direct.assert_not_called()

    def test_volume_failure_is_sanitized_and_never_constructs_task_runner(self):
        stderr = io.StringIO()
        with patch.object(linux_supervisor, "verify_stage_volume", side_effect=RuntimeError("private-daemon-secret")), \
                patch.object(task_runner, "TaskRunner") as runner, \
                patch.object(task_runner, "run_process") as direct, contextlib.redirect_stderr(stderr):
            result = linux_supervisor.main(self.arguments())
        self.assertEqual(result, task_runner.EXIT_SECURITY_VIOLATION)
        self.assertEqual(stderr.getvalue(), linux_supervisor.REJECTION_MESSAGE + "\n")
        runner.assert_not_called()
        direct.assert_not_called()
        self.assert_no_task_output()


class LinuxSupervisorCliContractTest(unittest.TestCase):
    def test_capabilities_are_static_protocol_information_only(self):
        stdout = io.StringIO()
        with patch.object(linux_supervisor, "run_supervisor") as run, contextlib.redirect_stdout(stdout):
            result = linux_supervisor.main(["--capabilities"])
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(stdout.getvalue()), CAPABILITIES)
        self.assertNotIn("productionSafe", stdout.getvalue())
        run.assert_not_called()

    def test_cli_requires_task_work_root_and_stage_config_without_fallback(self):
        cases = [[], ["task-spec.json"], ["task-spec.json", "--work-root", "/work"],
                 ["task-spec.json", "--stage-config", "/config"],
                 ["--work-root", "/work", "--stage-config", "/config"],
                 ["task-spec.json", "--work-root", "/work", "--stage-config", "/config"],
                 ["--capabilities", "--config-owner-uid", "0"],
                 ["--capabilities", "task-spec.json"], ["--unknown-private-secret"]]
        for arguments in cases:
            with self.subTest(arguments=arguments):
                stderr = io.StringIO()
                with patch.object(linux_supervisor, "run_supervisor") as run, contextlib.redirect_stderr(stderr):
                    result = linux_supervisor.main(arguments)
                self.assertEqual(result, task_runner.EXIT_SECURITY_VIOLATION)
                self.assertEqual(stderr.getvalue(), linux_supervisor.REJECTION_MESSAGE + "\n")
                run.assert_not_called()

    def test_cli_owner_uid_rejects_noninteger_and_negative_values(self):
        for owner in ("true", "false", "-1", "1.0", "1e3", "+1", " 1", "１", ""):
            with self.subTest(owner=owner):
                stderr = io.StringIO()
                arguments = ["task-spec.json", "--work-root", "/work", "--stage-config", "/config",
                             "--config-owner-uid", owner]
                with patch.object(linux_supervisor, "run_supervisor") as run, contextlib.redirect_stderr(stderr):
                    result = linux_supervisor.main(arguments)
                self.assertEqual(result, task_runner.EXIT_SECURITY_VIOLATION)
                self.assertEqual(stderr.getvalue(), linux_supervisor.REJECTION_MESSAGE + "\n")
                run.assert_not_called()

    def test_cli_explicit_zero_owner_is_forwarded_without_default(self):
        arguments = ["task-spec.json", "--work-root", "/work", "--stage-config", "/config",
                     "--config-owner-uid", "0"]
        with patch.object(linux_supervisor, "run_supervisor", return_value=0) as run:
            self.assertEqual(linux_supervisor.main(arguments), 0)
        self.assertEqual(run.call_args.args, (Path("task-spec.json"), Path("/work"), Path("/config"), 0))


if __name__ == "__main__":
    unittest.main()
