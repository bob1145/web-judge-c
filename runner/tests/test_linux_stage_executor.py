import json
import hashlib
import os
import select
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from linux_stage_executor import LinuxStageConfig, LinuxStageExecutor, StageSecurityError, copy_regular
import linux_stage_executor as stage_module
from task_runner import ProcessOutcome


class RuntimeContractDouble:
    def __init__(self, test):
        self.test = test
        self.commands = []
        self.container = None
        self.create_timeout = False
        self.delayed_create = False
        self.pending_container = None
        self.create_returncode = 0
        self.create_stdout = b"a" * 64 + b"\n"
        self.inspect_failures = 0
        self.foreign_labels = False
        self.stage = None
        self.cancel_on_create = False
        self.kill_failure = False
        self.kill_timeout = False
        self.kill_still_running = False
        self.kill_oom = False

    def __call__(self, command, **kwargs):
        self.commands.append(command)
        self.test.assertEqual(kwargs["timeout"], self.test.config.command_timeout_seconds)
        self.test.assertEqual(command[:5], ["/usr/bin/docker", "--config", str(self.test.journal / "runtime-client"),
                                           "--host", "unix:///run/test-stage/docker.sock"])
        action = command[6]
        if action == "create":
            records = [path for path in self.test.journal.glob("*.json")
                       if json.loads(path.read_text())["state"] == "CREATE_REQUESTED"]
            self.test.assertEqual(len(records), 1)
            record = json.loads(records[0].read_text())
            self.test.assertEqual(record["state"], "CREATE_REQUESTED")
            self.test.assertEqual(record["creationState"], "UNKNOWN")
            self.test.assertEqual(record["journalVersion"], 1)
            self.stage = record["stage"]
            labels = {value.split("=", 1)[0]: value.split("=", 1)[1]
                      for index, value in enumerate(command) if index and command[index - 1] == "--label"}
            if self.foreign_labels:
                labels["io.webjudge.attempt"] = str(uuid.uuid4())
            self.container = {"Id": "a" * 64, "Config": {"Labels": labels},
                              "State": {"Running": False, "ExitCode": 0, "OOMKilled": False}}
            if self.cancel_on_create:
                self.test.limits["cancel_path"].touch()
            if self.delayed_create:
                self.pending_container = self.container
                self.container = None
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            if self.create_timeout:
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return subprocess.CompletedProcess(command, self.create_returncode, self.create_stdout, b"private runtime failure")
        if action == "inspect":
            if self.inspect_failures:
                self.inspect_failures -= 1
                raise RuntimeError("private runtime inspect failure")
            if self.container is None:
                return subprocess.CompletedProcess(command, 1, b"", ("Error: No such container: " + command[-1]).encode())
            return subprocess.CompletedProcess(command, 0, json.dumps([self.container]).encode(), b"")
        if action == "rm":
            self.test.assertEqual(command[-1], "a" * 64)
            self.container = None
            return subprocess.CompletedProcess(command, 0, b"a" * 64, b"")
        if action == "kill":
            self.test.assertEqual(command[7:], ["--signal", "KILL", "a" * 64])
            self.test.assertEqual(self.test.record()["state"], "STOPPING")
            if self.kill_timeout:
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            if self.kill_failure:
                return subprocess.CompletedProcess(command, 1, b"", b"kill failed")
            self.container["State"].update(Running=self.kill_still_running, ExitCode=137, OOMKilled=self.kill_oom)
            return subprocess.CompletedProcess(command, 0, b"a" * 64, b"")
        raise AssertionError("unexpected runtime operation: " + action)


@unittest.skipUnless(os.name == "posix", "descriptor-relative Linux stage contract")
class LinuxStageExecutorContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage_root = self.root / "stage-volume"
        self.stage_root.mkdir()
        self.journal = self.root / "journal"
        self.config = LinuxStageConfig("/usr/bin/docker", "unix:///run/test-stage/docker.sock",
                                       "sha256:" + "b" * 64, "test-bounded-volume", self.stage_root,
                                       65532, 65532, 32, 0.5, 4096, 8192, 5, 33554432, "c" * 64)
        self.runtime = RuntimeContractDouble(self)
        self.limits = dict(stdin=b"", timeout_seconds=1, memory_limit_bytes=67108864,
                           output_limit_bytes=4096, cancel_path=self.root / "cancel")
        self.program = self.root / "program"
        self.program.write_bytes(b"program")
        self.source = self.root / "source.cpp"
        self.source.write_bytes(b"int main(){}")
        self.process_calls = []
        self.artifact_writer = lambda path: path.write_bytes(b"compiled-program")
        self.outcome = ProcessOutcome("SUCCESS", 0, b"plain stdout", b"", 7, 987)
        self.executor = LinuxStageExecutor(self.config, str(uuid.uuid4()), self.journal, self.run_process_double)
        self.patcher = patch("linux_stage_executor.subprocess.run", self.runtime)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.volume_patcher = patch("linux_stage_volume.verify_stage_volume")
        self.volume_verifier = self.volume_patcher.start()
        self.addCleanup(self.volume_patcher.stop)

    def test_volume_is_verified_before_runtime_creation(self):
        self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.volume_verifier.assert_called_once_with(self.config, self.executor.attempt_id,
                                                     33554432, "c" * 64, self.executor._control)

    def test_invalid_volume_prevents_inputs_journal_and_runtime_creation(self):
        self.volume_verifier.side_effect = StageSecurityError("stage volume verification failed")
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.assertFalse(list(self.stage_root.iterdir()))
        self.assertFalse(list(self.journal.glob("*.json")))

    def run_process_double(self, **kwargs):
        self.process_calls.append(kwargs)
        self.assertEqual(kwargs["memory_limit_bytes"], 0)
        self.assertEqual(kwargs["cwd"], self.journal)
        record = self.record()
        self.assertEqual(record["state"], "STARTING")
        self.assertEqual(record["containerId"], "a" * 64)
        if record["phase"] == "compile":
            self.artifact_writer(self.stage_root / self.runtime.stage / "scratch" / "program")
        return self.outcome

    def record(self):
        path = self.journal / (self.runtime.stage + ".json") if self.runtime.stage else next(self.journal.glob("*.json"))
        return json.loads(path.read_text())

    def test_configuration_is_explicit_and_immutable(self):
        with self.assertRaises(TypeError):
            LinuxStageConfig()
        for field, value in (("executor_image", "runner:latest"), ("executor_image", "sha256:abc"),
                             ("runtime_endpoint", "tcp://host:2375"), ("runtime_endpoint", "unix://relative"),
                             ("runtime_command", "docker"), ("uid", 0), ("gid", True),
                             ("cpus", float("inf")), ("stage_volume", "unsafe,volume"),
                             ("volume_limit_bytes", 0), ("volume_limit_bytes", True),
                             ("volume_holder_id", "short"), ("volume_holder_id", None)):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                replace(self.config, **{field: value})

    def test_minimal_mounts_and_explicit_security_options(self):
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        command = self.runtime.commands[0]
        mounts = [value for index, value in enumerate(command) if index and command[index - 1] == "--mount"]
        self.assertEqual(mounts, [f"type=volume,source=test-bounded-volume,target=/input,volume-subpath={self.runtime.stage}/input,volume-nocopy,readonly",
                                  f"type=volume,source=test-bounded-volume,target=/scratch,volume-subpath={self.runtime.stage}/scratch,volume-nocopy"])
        for flag, value in (("--network", "none"), ("--user", "65532:65532"), ("--cap-drop", "ALL"),
                            ("--log-driver", "none"),
                            ("--security-opt", "no-new-privileges"), ("--pids-limit", "32"),
                            ("--memory", "67108864"), ("--memory-swap", "67108864")):
            self.assertEqual(command[command.index(flag) + 1], value)
        self.assertIn("--read-only", command)
        self.assertFalse(any("docker.sock" in mount or str(self.journal) in mount for mount in mounts))
        self.assertEqual(outcome.peak_memory_kb, 0)
        self.assertEqual(outcome.stdout, b"plain stdout")
        self.assertEqual(self.record()["state"], "CLEANED")
        self.assertFalse(list(self.stage_root.iterdir()))

    def test_compile_transfers_regular_artifact_after_removal(self):
        destination = self.root / "compiled"
        original = copy_regular
        def checked_transfer(source, target, limit, mode):
            if Path(target) == destination:
                self.assertIsNone(self.runtime.container)
                self.assertEqual(self.record()["state"], "REMOVED")
            return original(source, target, limit, mode)
        with patch("linux_stage_executor.copy_regular", checked_transfer):
            self.executor.compile("GENERATOR", self.source, destination, **self.limits)
        self.assertEqual(destination.read_bytes(), b"compiled-program")
        self.assertEqual(self.runtime.commands[0][-6:], ["g++", "/input/source.cpp", "-o", "/scratch/program", "-O2", "-std=c++17"])

    def test_create_timeout_removes_observed_container_but_preserves_unknown(self):
        self.runtime.create_timeout = True
        with self.assertRaisesRegex(StageSecurityError, "^stage creation outcome is unknown; recovery required$"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assertTrue(any(command[6] == "rm" for command in self.runtime.commands))
        self.assert_unknown_creation()
        self.assertFalse(self.process_calls)

    def assert_unknown_creation(self):
        self.assertEqual(self.record()["state"], "CREATE_UNKNOWN")
        self.assertEqual(self.record()["creationState"], "UNKNOWN")
        self.assertTrue((self.stage_root / self.runtime.stage).is_dir())

    def test_delayed_create_after_missing_inspection_never_claims_cleanup(self):
        self.runtime.delayed_create = True
        with self.assertRaisesRegex(StageSecurityError, "^stage creation outcome is unknown; recovery required$"):
            self.executor.compile("USER", self.source, self.root / "compiled", **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assertTrue(any(command[6] == "inspect" for command in self.runtime.commands))
        self.assertFalse(any(command[6] == "rm" for command in self.runtime.commands))
        self.assert_unknown_creation()
        self.runtime.container = self.runtime.pending_container
        self.assertIsNotNone(self.runtime.container)
        self.assert_unknown_creation()
        self.assertFalse((self.root / "compiled").exists())
        self.assertFalse(self.process_calls)

    def test_invalid_create_reply_remains_unknown_after_observed_removal(self):
        self.runtime.create_stdout = b"private-invalid-container-id"
        with self.assertRaisesRegex(StageSecurityError, "^stage creation outcome is unknown; recovery required$"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assert_unknown_creation()
        self.assertFalse(self.process_calls)

    def test_nonzero_create_reply_remains_unknown(self):
        self.runtime.create_returncode = 1
        with self.assertRaisesRegex(StageSecurityError, "^stage creation outcome is unknown; recovery required$"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assert_unknown_creation()
        self.assertFalse(self.process_calls)

    def test_initial_inspection_failure_remains_unknown_after_later_removal(self):
        self.runtime.inspect_failures = 1
        with self.assertRaisesRegex(StageSecurityError, "^stage creation outcome is unknown; recovery required$"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assert_unknown_creation()
        self.assertFalse(self.process_calls)

    def test_unknown_creation_blocks_current_and_fresh_executors(self):
        self.runtime.create_timeout = True
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.runtime.commands.clear()
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        for executor in (self.executor, fresh):
            with self.subTest(fresh=executor is fresh), self.assertRaisesRegex(StageSecurityError, "^stage journal requires trusted recovery$"):
                executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.assert_unknown_creation()

    def test_foreign_labels_are_never_removed(self):
        self.runtime.foreign_labels = True
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(any(command[6] == "rm" for command in self.runtime.commands))
        self.assertFalse(any(command[6] == "kill" for command in self.runtime.commands))
        self.assertEqual(self.record()["state"], "CREATE_UNKNOWN")
        self.assertTrue((self.stage_root / self.runtime.stage).is_dir())

    def test_cancel_still_removes_container(self):
        self.outcome = replace(self.outcome, status="CANCELLED", exit_code=-9)
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "CANCELLED")
        self.assertIsNone(self.runtime.container)
        self.assertFalse(any(command[6] == "kill" for command in self.runtime.commands))

    def test_precancelled_stage_does_not_create_or_start(self):
        self.limits["cancel_path"].touch()
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome, ProcessOutcome("CANCELLED", -1, b"", b"", 0, 0))
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.assertFalse(list(self.stage_root.iterdir()))
        self.assertFalse(list(self.journal.glob("*.json")))

    def test_precancelled_compile_does_not_create_artifact(self):
        self.limits["cancel_path"].touch()
        destination = self.root / "compiled"
        outcome = self.executor.compile("USER", self.source, destination, **self.limits)
        self.assertEqual(outcome.status, "CANCELLED")
        self.assertFalse(destination.exists())
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)

    def test_cancellation_during_transfer_prevents_create(self):
        original = copy_regular
        def cancelling_transfer(*arguments):
            original(*arguments)
            self.limits["cancel_path"].touch()
        with patch("linux_stage_executor.copy_regular", cancelling_transfer):
            outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "CANCELLED")
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.assertEqual(self.record()["state"], "CLEANED")

    def test_cancellation_during_create_removes_without_start(self):
        self.runtime.cancel_on_create = True
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual((outcome.status, outcome.exit_code), ("CANCELLED", -1))
        self.assertFalse(self.process_calls)
        self.assertIsNone(self.runtime.container)
        self.assertEqual(self.record()["state"], "CLEANED")

    def test_cancellation_immediately_before_start_prevents_start(self):
        original = self.executor._journal
        def cancelling_journal(record, state):
            original(record, state)
            if state == "STARTING":
                self.limits["cancel_path"].touch()
        self.executor._journal = cancelling_journal
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "CANCELLED")
        self.assertFalse(self.process_calls)
        self.assertIsNone(self.runtime.container)

    def running_outcome(self, status):
        self.outcome = replace(self.outcome, status=status, exit_code=-9)
        original = self.executor.process_runner
        def process(**kwargs):
            outcome = original(**kwargs)
            self.runtime.container["State"].update(Running=True, ExitCode=0)
            return outcome
        self.executor.process_runner = process

    def assert_verified_stop(self, status):
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual((outcome.status, outcome.exit_code), (status, 137))
        operations = [command[6] for command in self.runtime.commands]
        self.assertLess(operations.index("kill"), operations.index("rm"))
        self.assertEqual(operations[operations.index("kill") + 1], "inspect")
        self.assertEqual(self.record()["outcome"]["exitCode"], 137)
        self.assertIsNone(self.runtime.container)

    def test_cancel_running_stage_uses_stopped_exit_and_oom_state(self):
        self.running_outcome("CANCELLED")
        self.runtime.kill_oom = True
        self.assert_verified_stop("CANCELLED")
        self.assertTrue(self.record()["outcome"]["oomKilled"])

    def test_timeout_running_stage_uses_stopped_exit_state(self):
        self.running_outcome("TIME_LIMIT_EXCEEDED")
        self.assert_verified_stop("TIME_LIMIT_EXCEEDED")

    def test_output_limit_running_stage_uses_stopped_exit_state(self):
        self.running_outcome("OUTPUT_LIMIT_EXCEEDED")
        self.assert_verified_stop("OUTPUT_LIMIT_EXCEEDED")

    def test_kill_failure_raises_instead_of_returning_placeholder_exit(self):
        self.running_outcome("CANCELLED")
        self.runtime.kill_failure = True
        with self.assertRaisesRegex(RuntimeError, "runtime control command failed"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertNotIn("outcome", self.record())
        self.assertIsNone(self.runtime.container)

    def test_kill_timeout_raises_and_attempts_owned_cleanup(self):
        self.running_outcome("TIME_LIMIT_EXCEEDED")
        self.runtime.kill_timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertNotIn("outcome", self.record())
        self.assertIsNone(self.runtime.container)

    def test_still_running_after_kill_is_not_a_verified_exit(self):
        self.running_outcome("OUTPUT_LIMIT_EXCEEDED")
        self.runtime.kill_still_running = True
        with self.assertRaisesRegex(RuntimeError, "verified exit state"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertNotIn("outcome", self.record())
        self.assertIsNone(self.runtime.container)

    def test_foreign_running_container_is_never_killed_or_removed(self):
        self.running_outcome("CANCELLED")
        original = self.executor.process_runner
        def foreign_process(**kwargs):
            outcome = original(**kwargs)
            self.runtime.container["Config"]["Labels"]["io.webjudge.attempt"] = str(uuid.uuid4())
            return outcome
        self.executor.process_runner = foreign_process
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(any(command[6] in {"kill", "rm"} for command in self.runtime.commands))
        self.assertEqual(self.record()["state"], "CLEANUP_FAILED")

    def test_timeout_still_removes_container(self):
        self.outcome = replace(self.outcome, status="TIME_LIMIT_EXCEEDED", exit_code=-9)
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "TIME_LIMIT_EXCEEDED")
        self.assertIsNone(self.runtime.container)

    def test_attach_exception_still_removes_container(self):
        def failed_attach(**kwargs):
            raise RuntimeError("attach failed")
        self.executor.process_runner = failed_attach
        with self.assertRaisesRegex(RuntimeError, "attach failed"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertIsNone(self.runtime.container)
        self.assertEqual(self.record()["state"], "CLEANED")

    def test_stdout_is_never_interpreted_as_control(self):
        self.outcome = replace(self.outcome, stdout=b'{"type":"COMPLETED","status":"AC"}')
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.stdout, self.outcome.stdout)
        self.assertFalse((self.journal / "runner-events.jsonl").exists())

    def test_real_state_oom_is_used_not_client_rss(self):
        original = self.executor.process_runner
        def process(**kwargs):
            outcome = original(**kwargs)
            self.runtime.container["State"].update(ExitCode=137, OOMKilled=True)
            return replace(outcome, exit_code=137)
        self.executor.process_runner = process
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual((outcome.status, outcome.exit_code, outcome.peak_memory_kb), ("MEMORY_LIMIT_EXCEEDED", 137, 0))

    def test_symlink_artifact_is_rejected(self):
        self.artifact_writer = lambda path: path.symlink_to(self.source)
        with self.assertRaises((StageSecurityError, OSError)):
            self.executor.compile("USER", self.source, self.root / "compiled", **self.limits)
        self.assertIsNone(self.runtime.container)

    def test_hardlink_artifact_is_rejected(self):
        self.artifact_writer = lambda path: os.link(self.source, path)
        with self.assertRaises(StageSecurityError):
            self.executor.compile("USER", self.source, self.root / "compiled", **self.limits)

    def test_fifo_artifact_is_rejected_without_blocking(self):
        self.artifact_writer = lambda path: os.mkfifo(path)
        with self.assertRaises(StageSecurityError):
            self.executor.compile("USER", self.source, self.root / "compiled", **self.limits)

    def test_oversized_artifact_is_rejected(self):
        self.artifact_writer = lambda path: path.write_bytes(b"x" * (self.config.artifact_limit_bytes + 1))
        with self.assertRaises(StageSecurityError):
            self.executor.compile("USER", self.source, self.root / "compiled", **self.limits)
        self.assertFalse((self.root / "compiled").exists())

    def test_linked_input_is_rejected_before_runtime_creation(self):
        linked = self.root / "linked"
        os.link(self.program, linked)
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", linked, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)

    def test_parent_symlink_is_rejected(self):
        parent = self.root / "linked-parent"
        parent.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(StageSecurityError):
            self.executor.execute("USER", parent / "program", [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)

    def recovery_record(self):
        stage = str(uuid.uuid4())
        record = {"journalVersion": 1, "creationState": "CONFIRMED", "stage": stage,
                  "attempt": self.executor.attempt_id, "role": "USER", "phase": "execute",
                  "name": "wjc-stage-" + stage, "volume": self.config.stage_volume,
                  "state": "CLEANED", "containerId": "a" * 64,
                  "labels": {"io.webjudge.attempt": self.executor.attempt_id, "io.webjudge.stage": stage,
                             "io.webjudge.role": "USER", "io.webjudge.managed": "stage"}}
        return self.journal / (stage + ".json"), record

    def assert_journal_blocks_execution(self):
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        with self.assertRaisesRegex(StageSecurityError, "^stage journal requires trusted recovery$"):
            fresh.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.assertFalse(list(self.stage_root.iterdir()))

    def test_cleaned_record_allows_next_stage_and_fresh_executor(self):
        self.executor.execute("USER", self.program, [], {}, **self.limits)
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        outcome = fresh.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "SUCCESS")
        records = [json.loads(path.read_text()) for path in self.journal.glob("*.json")]
        self.assertEqual(len(records), 2)
        self.assertTrue(all(record["state"] == "CLEANED" and record["creationState"] == "CONFIRMED" for record in records))

    def test_not_requested_cleaned_record_allows_next_stage(self):
        path, record = self.recovery_record()
        record["creationState"] = "NOT_REQUESTED"
        del record["containerId"]
        path.write_text(json.dumps(record))
        outcome = self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(outcome.status, "SUCCESS")

    def test_incomplete_legacy_or_mismatched_journal_blocks_new_stage(self):
        path, original = self.recovery_record()
        mutations = [("state", state) for state in ("INTENT", "CREATE_REQUESTED", "CREATED", "REMOVED", "CREATE_UNKNOWN", "CLEANUP_FAILED")]
        mutations += [("journalVersion", 0), ("journalVersion", True), ("journalVersion", None),
                      ("attempt", str(uuid.uuid4())), ("stage", str(uuid.uuid4())),
                      ("volume", "foreign-volume"), ("name", "foreign-container"),
                      ("role", "UNKNOWN"), ("phase", "UNKNOWN"), ("labels", {}),
                      ("creationState", "UNKNOWN"), ("creationState", "NOT_REQUESTED"),
                      ("containerId", "short"), ("containerId", None), ("unexpected", True)]
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                record = {**original, field: value}
                if value is None:
                    del record[field]
                path.write_text(json.dumps(record))
                self.assert_journal_blocks_execution()

    def test_malformed_or_duplicate_journal_blocks_new_stage(self):
        path, record = self.recovery_record()
        for payload in ("{", "[]", json.dumps(record)[:-1] + ',"state":"CLEANED"}'):
            with self.subTest(payload=payload):
                path.write_text(payload)
                self.assert_journal_blocks_execution()

    def test_symlink_journal_is_rejected_without_following_target(self):
        path, record = self.recovery_record()
        target = self.root / "outside-journal"
        target.write_text(json.dumps(record))
        path.symlink_to(target)
        self.assert_journal_blocks_execution()
        self.assertEqual(json.loads(target.read_text()), record)

    def test_hardlinked_journal_is_rejected(self):
        path, record = self.recovery_record()
        target = self.root / "outside-journal"
        target.write_text(json.dumps(record))
        os.link(target, path)
        self.assert_journal_blocks_execution()

    def test_fifo_journal_is_rejected_without_blocking(self):
        path, _ = self.recovery_record()
        os.mkfifo(path)
        self.assert_journal_blocks_execution()

    def test_oversized_journal_is_rejected(self):
        path, _ = self.recovery_record()
        path.write_bytes(b"x" * (LinuxStageExecutor.JOURNAL_LIMIT_BYTES + 1))
        self.assert_journal_blocks_execution()

    def test_writable_journal_record_is_rejected(self):
        path, record = self.recovery_record()
        path.write_text(json.dumps(record))
        path.chmod(0o666)
        self.assert_journal_blocks_execution()

    def test_interrupted_journal_temporary_file_blocks_new_stage(self):
        (self.journal / (str(uuid.uuid4()) + ".pending.tmp")).write_text("{}")
        self.assert_journal_blocks_execution()

    def test_directory_lock_rejects_another_executor_then_releases(self):
        import fcntl

        descriptor = os.open(self.journal, os.O_RDONLY | os.O_DIRECTORY)
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(StageSecurityError, "^stage journal is locked or unavailable$"):
                fresh.execute("USER", self.program, [], {}, **self.limits)
            self.assertFalse(self.runtime.commands)
            self.assertFalse(list(self.journal.glob("*.json")))
        finally:
            os.close(descriptor)
        self.assertEqual(fresh.execute("USER", self.program, [], {}, **self.limits).status, "SUCCESS")

    def test_directory_lock_failure_never_continues_execution(self):
        with patch("fcntl.flock", side_effect=OSError("private lock failure")):
            with self.assertRaisesRegex(StageSecurityError, "^stage journal is locked or unavailable$"):
                self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.assertFalse(list(self.journal.glob("*.json")))

    def test_directory_lock_is_held_through_stage_cleanup(self):
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        original = self.executor._control
        observed = []
        def checked_control(*arguments, **kwargs):
            if arguments[:2] in {("container", "create"), ("container", "rm")}:
                with self.assertRaisesRegex(StageSecurityError, "^stage journal is locked or unavailable$"):
                    fresh.execute("USER", self.program, [], {}, **self.limits)
                observed.append(arguments[1])
            return original(*arguments, **kwargs)
        self.executor._control = checked_control
        self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertEqual(observed, ["create", "rm"])

    def admission_close(self, root=None, attempt=None, owner=None):
        close = getattr(stage_module, "close_existing_stage_admission", None)
        self.assertTrue(callable(close), "persistent stage admission closure must be implemented")
        return close(self.journal if root is None else root,
                     self.executor.attempt_id if attempt is None else attempt,
                     os.geteuid() if owner is None else owner)

    def admission_payload(self):
        return {"admissionVersion": 1, "attemptId": self.executor.attempt_id, "state": "CLOSED"}

    def admission_marker(self):
        return self.journal / "stage-admission.closed"

    def admission_pending(self):
        return self.journal / "stage-admission.pending"

    def write_admission_marker(self, payload=None):
        marker = self.admission_marker()
        marker.write_bytes(json.dumps(self.admission_payload() if payload is None else payload,
                                     separators=(",", ":")).encode())
        marker.chmod(0o600)
        return marker

    def assert_admission_blocks_all_stage_entries(self):
        before = {path.name: path.read_bytes() for path in self.journal.glob("*.json")}
        fresh = LinuxStageExecutor(self.config, self.executor.attempt_id, self.journal, self.run_process_double)
        for executor in (self.executor, fresh):
            for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"):
                with self.subTest(fresh=executor is fresh, role=role, phase="compile"):
                    with self.assertRaises(StageSecurityError):
                        executor.compile(role, self.source, self.root / (role + "-compiled"), **self.limits)
                with self.subTest(fresh=executor is fresh, role=role, phase="execute"):
                    with self.assertRaises(StageSecurityError):
                        executor.execute(role, self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.assertFalse(self.process_calls)
        self.volume_verifier.assert_not_called()
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.journal.glob("*.json")})

    def admission_child(self, body, *arguments):
        script = "import sys\nsys.path.insert(0, sys.argv[1])\n" + body
        child = subprocess.Popen([sys.executable, "-B", "-c", script,
                                  str(Path(stage_module.__file__).parent), str(self.journal), self.executor.attempt_id,
                                  str(os.geteuid()), *map(str, arguments)], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        def cleanup():
            if child.poll() is None:
                child.kill()
            child.communicate(timeout=5)
        self.addCleanup(cleanup)
        return child

    def child_ready(self, child):
        readable, _, _ = select.select([child.stdout], [], [], 5)
        self.assertTrue(readable, "independent process did not reach the bounded synchronization point")
        self.assertEqual(child.stdout.readline().strip(), "READY")

    def test_close_existing_admission_persists_exact_private_marker_without_runtime(self):
        before = self.journal.stat()
        result = self.admission_close()
        self.assertEqual(result, self.admission_payload())
        marker = self.admission_marker()
        self.assertEqual(json.loads(marker.read_text()), self.admission_payload())
        metadata = marker.stat()
        self.assertTrue(stat.S_ISREG(metadata.st_mode))
        self.assertEqual(stat.S_IMODE(metadata.st_mode), 0o600)
        self.assertEqual((metadata.st_uid, metadata.st_nlink), (os.geteuid(), 1))
        self.assertFalse(self.admission_pending().exists())
        self.assertEqual((self.journal.stat().st_ino, self.journal.stat().st_mode), (before.st_ino, before.st_mode))
        self.assertFalse(self.runtime.commands)
        self.volume_verifier.assert_not_called()

    def test_closed_admission_rejects_every_role_phase_for_same_and_new_executor(self):
        self.admission_close()
        self.assert_admission_blocks_all_stage_entries()
        self.assertFalse(list(self.stage_root.iterdir()))
        self.assertFalse(list(self.root.glob("*-compiled")))

    def test_admission_pending_directory_entry_is_synced_before_file_and_publication(self):
        sync = os.fsync
        publish = os.replace
        observed = []
        def tracking_sync(descriptor):
            if stat.S_ISDIR(os.fstat(descriptor).st_mode):
                observed.append(("directory", self.admission_pending().exists(), self.admission_marker().exists()))
            else:
                observed.append(("file", self.admission_pending().exists(), self.admission_marker().exists()))
            sync(descriptor)
        def tracking_publish(*arguments, **keywords):
            observed.append(("publish", self.admission_pending().exists(), self.admission_marker().exists()))
            return publish(*arguments, **keywords)
        with patch("linux_stage_executor.os.fsync", tracking_sync), patch("linux_stage_executor.os.replace", tracking_publish):
            self.admission_close()
        self.assertEqual(observed, [("directory", True, False), ("file", True, False),
                                    ("publish", True, False), ("directory", False, True)])

    def test_repeated_admission_close_retains_inode_hash_and_unknown_record(self):
        path, record = self.recovery_record()
        record.update(creationState="UNKNOWN", state="CREATE_UNKNOWN")
        record.pop("containerId")
        path.write_text(json.dumps(record))
        path.chmod(0o600)
        before_record = path.read_bytes()
        before_inode = path.stat().st_ino
        self.admission_close()
        marker = self.admission_marker()
        identity = (marker.stat().st_ino, hashlib.sha256(marker.read_bytes()).hexdigest())
        self.assertEqual(self.admission_close(), self.admission_payload())
        self.assertEqual(identity, (marker.stat().st_ino, hashlib.sha256(marker.read_bytes()).hexdigest()))
        self.assertEqual((path.stat().st_ino, path.read_bytes()), (before_inode, before_record))
        self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_requires_existing_directory_and_correct_explicit_identity(self):
        missing = self.root / "missing-journal"
        for root, attempt, owner in ((missing, self.executor.attempt_id, os.geteuid()),
                                     (self.journal, "not-a-uuid", os.geteuid()),
                                     (self.journal, self.executor.attempt_id, os.geteuid() + 1),
                                     (self.journal, self.executor.attempt_id, True)):
            with self.subTest(root=str(root), attempt=attempt, owner=owner), self.assertRaises(StageSecurityError):
                self.admission_close(root, attempt, owner)
        self.assertFalse(missing.exists())
        self.assertFalse(self.admission_marker().exists())
        self.assertFalse(self.admission_pending().exists())
        self.assertFalse(self.runtime.commands)

    def test_admission_close_rejects_writable_or_linked_journal_directory(self):
        self.journal.chmod(0o777)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.journal.chmod(0o700)
        alias = self.root / "journal-alias"
        alias.symlink_to(self.journal, target_is_directory=True)
        with self.assertRaises(StageSecurityError):
            self.admission_close(root=alias)
        self.assertFalse(self.admission_marker().exists())
        self.assertFalse(self.runtime.commands)

    def test_admission_close_refuses_malformed_duplicate_and_wrong_identity_marker(self):
        valid = self.admission_payload()
        payloads = [b"{", b"[]", b"null", json.dumps(valid).encode() + b"{}",
                    (json.dumps(valid)[:-1] + ',"state":"CLOSED"}').encode()]
        for field, value in (("admissionVersion", True), ("admissionVersion", 0), ("admissionVersion", 1.0),
                             ("attemptId", str(uuid.uuid4())), ("state", "OPEN"), ("extra", True)):
            payloads.append(json.dumps({**valid, field: value}).encode())
        for field in valid:
            payloads.append(json.dumps({key: value for key, value in valid.items() if key != field}).encode())
        marker = self.admission_marker()
        for payload in payloads:
            with self.subTest(payload=payload):
                marker.write_bytes(payload)
                marker.chmod(0o600)
                inode = marker.stat().st_ino
                with self.assertRaises(StageSecurityError):
                    self.admission_close()
                self.assertEqual((marker.stat().st_ino, marker.read_bytes()), (inode, payload))
                self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_rejects_insecure_marker_modes_without_overwrite(self):
        marker = self.write_admission_marker()
        payload = marker.read_bytes()
        for mode in (0o644, 0o666, 0o400, 0o4600):
            with self.subTest(mode=oct(mode)):
                marker.chmod(mode)
                with self.assertRaises(StageSecurityError):
                    self.admission_close()
                self.assertEqual(marker.read_bytes(), payload)
                self.assertEqual(stat.S_IMODE(marker.stat().st_mode), mode)
                self.assert_admission_blocks_all_stage_entries()
        marker.chmod(0o600)

    def test_admission_close_rejects_symlink_marker_without_following_target(self):
        target = self.root / "outside-admission"
        target.write_text(json.dumps(self.admission_payload()))
        before = target.read_bytes()
        self.admission_marker().symlink_to(target)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(target.read_bytes(), before)
        self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_rejects_foreign_marker_owner_without_overwrite(self):
        marker = self.write_admission_marker()
        inode = marker.stat().st_ino
        before = marker.read_bytes()
        original = os.fstat
        def foreign_owner(descriptor):
            metadata = original(descriptor)
            if metadata.st_ino == inode and stat.S_ISREG(metadata.st_mode):
                values = list(metadata)
                values[4] = os.geteuid() + 1
                return os.stat_result(values)
            return metadata
        with patch("linux_stage_executor.os.fstat", foreign_owner), self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(marker.read_bytes(), before)
        self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_rejects_hardlinked_marker_without_modifying_alias(self):
        marker = self.write_admission_marker()
        outside = self.root / "outside-admission"
        os.link(marker, outside)
        before = outside.read_bytes()
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(outside.read_bytes(), before)
        self.assertEqual(marker.stat().st_nlink, 2)
        self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_rejects_fifo_and_directory_markers_without_blocking(self):
        marker = self.admission_marker()
        os.mkfifo(marker, 0o600)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assert_admission_blocks_all_stage_entries()
        marker.unlink()
        marker.mkdir(mode=0o700)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assert_admission_blocks_all_stage_entries()

    def test_admission_close_rejects_oversized_marker_without_truncation(self):
        marker = self.admission_marker()
        payload = b"x" * 65537
        marker.write_bytes(payload)
        marker.chmod(0o600)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(marker.read_bytes(), payload)
        self.assert_admission_blocks_all_stage_entries()

    def test_pending_admission_marker_always_blocks_closure_and_all_stages(self):
        pending = self.admission_pending()
        pending.write_text(json.dumps(self.admission_payload()))
        pending.chmod(0o600)
        before = pending.read_bytes()
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(pending.read_bytes(), before)
        self.assertFalse(self.admission_marker().exists())
        self.assert_admission_blocks_all_stage_entries()
        self.write_admission_marker()
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(pending.read_bytes(), before)

    def test_independent_process_directory_lock_blocks_close_until_release_then_stays_closed(self):
        child = self.admission_child("import os, fcntl\n"
                                     "directory = os.open(sys.argv[2], os.O_RDONLY | os.O_DIRECTORY)\n"
                                     "fcntl.flock(directory, fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
                                     "print('READY', flush=True)\nsys.stdin.readline()\nos.close(directory)\n")
        self.child_ready(child)
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertFalse(self.admission_marker().exists())
        self.assertFalse(self.admission_pending().exists())
        output, error = child.communicate("release\n", timeout=5)
        self.assertEqual((child.returncode, error), (0, ""))
        self.admission_close()
        self.assert_admission_blocks_all_stage_entries()

    def test_parent_directory_lock_blocks_independent_closer_without_side_effects(self):
        import fcntl

        directory = os.open(self.journal, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(directory, fcntl.LOCK_EX | fcntl.LOCK_NB)
            child = self.admission_child("from pathlib import Path\n"
                                         "from linux_stage_executor import close_existing_stage_admission, StageSecurityError\n"
                                         "try:\n close_existing_stage_admission(Path(sys.argv[2]), sys.argv[3], int(sys.argv[4]))\n"
                                         "except StageSecurityError:\n print('BUSY', flush=True)\n sys.exit(0)\n"
                                         "sys.exit(1)\n")
            output, error = child.communicate(timeout=5)
            self.assertEqual((child.returncode, output, error), (0, "BUSY\n", ""))
            self.assertFalse(self.admission_marker().exists())
            self.assertFalse(self.admission_pending().exists())
        finally:
            os.close(directory)
        self.admission_close()
        self.assert_admission_blocks_all_stage_entries()

    def test_independent_closer_holds_same_inode_lock_and_marker_blocks_after_release(self):
        child = self.admission_child("import os, stat\nfrom pathlib import Path\nimport linux_stage_executor as stage\n"
                                     "original = os.fsync\npaused = False\n"
                                     "def pause(descriptor):\n global paused\n original(descriptor)\n"
                                     " if not paused and stat.S_ISREG(os.fstat(descriptor).st_mode):\n"
                                     "  paused = True\n  print('READY', flush=True)\n  sys.stdin.readline()\n"
                                     "stage.os.fsync = pause\n"
                                     "stage.close_existing_stage_admission(Path(sys.argv[2]), sys.argv[3], int(sys.argv[4]))\n")
        self.child_ready(child)
        with self.assertRaisesRegex(StageSecurityError, "^stage journal is locked or unavailable$"):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        output, error = child.communicate("release\n", timeout=5)
        self.assertEqual((child.returncode, error), (0, ""))
        self.assertEqual(json.loads(self.admission_marker().read_text()), self.admission_payload())
        self.assert_admission_blocks_all_stage_entries()

    def test_close_detects_directory_replacement_after_flock_without_publishing_to_replacement(self):
        import fcntl

        original = fcntl.flock
        moved = self.root / "moved-journal"
        replaced = []
        def replace_after_lock(descriptor, operation):
            result = original(descriptor, operation)
            if not replaced and operation & fcntl.LOCK_EX:
                replaced.append(True)
                self.journal.rename(moved)
                self.journal.mkdir(mode=0o700)
            return result
        with patch("fcntl.flock", replace_after_lock), self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertFalse(self.admission_marker().exists())
        self.assertFalse((moved / "stage-admission.closed").exists())
        self.assertFalse(self.runtime.commands)

    def test_stage_detects_directory_replacement_after_flock_before_runtime_or_intent(self):
        import fcntl

        original = fcntl.flock
        moved = self.root / "moved-journal"
        replaced = []
        def replace_after_lock(descriptor, operation):
            result = original(descriptor, operation)
            if not replaced and operation & fcntl.LOCK_EX:
                replaced.append(True)
                self.journal.rename(moved)
                self.journal.mkdir(mode=0o700)
            return result
        with patch("fcntl.flock", replace_after_lock), self.assertRaises(StageSecurityError):
            self.executor.execute("USER", self.program, [], {}, **self.limits)
        self.assertFalse(self.runtime.commands)
        self.volume_verifier.assert_not_called()
        self.assertFalse(list(self.stage_root.iterdir()))
        self.assertFalse(list(self.journal.glob("*.json")))
        self.assertFalse(list(moved.glob("*.json")))

    def test_close_rechecks_directory_identity_after_durable_publication(self):
        original = os.fsync
        moved = self.root / "moved-journal"
        replaced = []
        def replace_after_sync(descriptor):
            original(descriptor)
            if not replaced and stat.S_ISDIR(os.fstat(descriptor).st_mode) and self.admission_marker().exists():
                replaced.append(True)
                self.journal.rename(moved)
                self.journal.mkdir(mode=0o700)
        with patch("linux_stage_executor.os.fsync", replace_after_sync), self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertTrue(replaced)
        self.assertFalse(list(self.journal.iterdir()))
        self.assertFalse(self.runtime.commands)

    def test_marker_appearing_before_publication_is_not_overwritten(self):
        original = os.fsync
        payload = b"broken concurrent marker"
        def create_conflict_after_file_sync(descriptor):
            original(descriptor)
            if stat.S_ISREG(os.fstat(descriptor).st_mode):
                self.admission_marker().write_bytes(payload)
                self.admission_marker().chmod(0o600)
        with patch("linux_stage_executor.os.fsync", create_conflict_after_file_sync), self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assertEqual(self.admission_marker().read_bytes(), payload)
        self.assertTrue(self.admission_pending().is_file())
        self.assert_admission_blocks_all_stage_entries()

    def test_interruption_before_marker_publication_leaves_pending_and_never_reopens(self):
        with patch("linux_stage_executor.os.replace", side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            self.admission_close()
        self.assertTrue(self.admission_pending().is_file())
        self.assertFalse(self.admission_marker().exists())
        self.assert_admission_blocks_all_stage_entries()
        with self.assertRaises(StageSecurityError):
            self.admission_close()

    def test_process_crash_after_pending_fsync_retains_durable_denial(self):
        child = self.admission_child("import os, stat\nfrom pathlib import Path\nimport linux_stage_executor as stage\n"
                                     "original = os.fsync\n"
                                     "def crash(descriptor):\n original(descriptor)\n"
                                     " if stat.S_ISREG(os.fstat(descriptor).st_mode):\n  os._exit(73)\n"
                                     "stage.os.fsync = crash\n"
                                     "stage.close_existing_stage_admission(Path(sys.argv[2]), sys.argv[3], int(sys.argv[4]))\n")
        output, error = child.communicate(timeout=5)
        self.assertEqual(child.returncode, 73, error)
        self.assertTrue(self.admission_pending().is_file())
        self.assertFalse(self.admission_marker().exists())
        with self.assertRaises(StageSecurityError):
            self.admission_close()
        self.assert_admission_blocks_all_stage_entries()

    def test_file_and_directory_fsync_failure_never_returns_closed_success(self):
        original = os.fsync
        for fail_kind in ("file", "directory"):
            with self.subTest(kind=fail_kind):
                for marker in (self.admission_marker(), self.admission_pending()):
                    if marker.exists():
                        marker.unlink()
                def failing_sync(descriptor):
                    is_file = stat.S_ISREG(os.fstat(descriptor).st_mode)
                    if is_file == (fail_kind == "file"):
                        raise OSError("injected admission durability failure")
                    return original(descriptor)
                with patch("linux_stage_executor.os.fsync", failing_sync), self.assertRaises(StageSecurityError):
                    self.admission_close()
                self.assertTrue(self.admission_marker().exists() or self.admission_pending().exists())
                self.assert_admission_blocks_all_stage_entries()


if __name__ == "__main__":
    unittest.main()
