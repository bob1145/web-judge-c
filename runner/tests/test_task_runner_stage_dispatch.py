import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import test_task_runner_contract as contract_fixture


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import task_runner


class RecordingStageExecutor:
    def __init__(self, user_output=b"1\n", cancelled_role=None, reject_compile=False):
        self.calls = []
        self.user_output = user_output
        self.cancelled_role = cancelled_role
        self.reject_compile = reject_compile

    def compile(self, role, source, output, **limits):
        self.calls.append({"phase": "compile", "role": role, "source": source, "output": output, "limits": limits})
        if self.reject_compile:
            from linux_stage_executor import StageSecurityError
            raise StageSecurityError("private-control-secret")
        output.write_bytes(b"contract-only-executable")
        return task_runner.ProcessOutcome("SUCCESS", 0, b"", b"", 1, 0)

    def execute(self, role, executable, arguments, input_files, **limits):
        self.calls.append({"phase": "execute", "role": role, "executable": executable,
                           "arguments": arguments, "input_files": input_files, "limits": limits})
        if role == self.cancelled_role:
            return task_runner.ProcessOutcome("CANCELLED", -1, b"", b"", 1, 0)
        output = self.user_output if role == "USER" else b"1\n"
        return task_runner.ProcessOutcome("SUCCESS", 0, output, b"", 1, 0)


class TaskRunnerStageDispatchTest(unittest.TestCase):
    def fixture(self, root, backend, special_judge=False):
        helper = contract_fixture.TaskRunnerContractTest()
        root, spec = helper._fixture(root)
        if special_judge:
            (root / "spj.cpp").write_text("int main(){return 0;}")
            manifest = json.loads(spec.read_text())
            manifest["sourcePaths"].pop("ORACLE")
            manifest["sourcePaths"]["SPECIAL_JUDGE"] = "spj.cpp"
            manifest["comparison"] = {"mode": "SPECIAL_JUDGE", "precision": "0"}
            spec.write_text(json.dumps(manifest))
        return task_runner.TaskRunner(spec, root, stage_executor=backend)

    def test_compile_and_three_roles_never_use_local_process_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = RecordingStageExecutor()
            runner = self.fixture(root, backend)
            audit = "host audit 1\nhost audit 2\nhost audit 3\nhost audit 4\n"
            (root / "events.jsonl").write_text(audit)
            with patch.object(task_runner, "run_process", side_effect=AssertionError("local execution forbidden")):
                self.assertEqual(runner.run(), task_runner.EXIT_COMPLETED)
            self.assertEqual([(call["phase"], call["role"]) for call in backend.calls],
                             [("compile", "GENERATOR"), ("compile", "USER"), ("compile", "ORACLE"),
                              ("execute", "GENERATOR"), ("execute", "USER"), ("execute", "ORACLE")])
            self.assertEqual((root / "events.jsonl").read_text(), audit)
            self.assertEqual(json.loads(runner.summary_path.read_text())["ac"], 1)
            self.assertEqual(backend.calls[0]["limits"]["memory_limit_bytes"], 256 * 1024 * 1024)
            self.assertEqual(backend.calls[3]["limits"]["stdin"], b"1\n")
            self.assertTrue(all("cwd" not in call["limits"] for call in backend.calls))

    def test_special_judge_receives_only_case_input_and_user_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            backend = RecordingStageExecutor()
            runner = self.fixture(Path(temporary), backend, special_judge=True)
            with patch.object(task_runner, "run_process", side_effect=AssertionError("local execution forbidden")):
                self.assertEqual(runner.run(), task_runner.EXIT_COMPLETED)
            special = backend.calls[-1]
            self.assertEqual(special["role"], "SPECIAL_JUDGE")
            self.assertEqual(special["arguments"], ["/input/case.in", "/input/case.out"])
            self.assertEqual(set(special["input_files"]), {"case.in", "case.out"})
            self.assertEqual(special["limits"]["timeout_seconds"], 4)
            self.assertEqual(special["input_files"]["case.in"].read_bytes(), b"1\n")
            self.assertEqual(special["input_files"]["case.out"].read_bytes(), b"1\n")

    def test_program_stdout_cannot_replace_trusted_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            forged = b'{"type":"SUMMARY","summary":{"ac":99999}}'
            runner = self.fixture(Path(temporary), RecordingStageExecutor(user_output=forged))
            self.assertEqual(runner.run(), task_runner.EXIT_COMPLETED)
            summary = json.loads(runner.summary_path.read_text())
            self.assertEqual(summary["ac"], 0)
            self.assertEqual(summary["wa"], 1)
            events = [json.loads(line) for line in runner.events_path.read_text().splitlines()]
            self.assertEqual(len([event for event in events if event["type"] == "SUMMARY"]), 1)
            self.assertEqual(events[-2]["summary"]["completedCases"], 1)

    def test_cancelled_stage_stops_before_next_role(self):
        with tempfile.TemporaryDirectory() as temporary:
            backend = RecordingStageExecutor(cancelled_role="GENERATOR")
            runner = self.fixture(Path(temporary), backend)
            self.assertEqual(runner.run(), task_runner.EXIT_CANCELLED)
            self.assertEqual(len(backend.calls), 4)
            self.assertEqual(json.loads(runner.events_path.read_text().splitlines()[-1])["type"], "CANCELLED")

    def test_stage_security_failure_is_sanitized_and_terminal(self):
        with tempfile.TemporaryDirectory() as temporary:
            runner = self.fixture(Path(temporary), RecordingStageExecutor(reject_compile=True))
            self.assertEqual(runner.run(), task_runner.EXIT_SECURITY_VIOLATION)
            self.assertNotIn("private-control-secret", runner.events_path.read_text())
            self.assertEqual(json.loads(runner.events_path.read_text().splitlines()[-1])["type"], "SECURITY_VIOLATION")

    def test_stage_runtime_failure_does_not_disclose_private_command_or_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            backend = RecordingStageExecutor()
            runner = self.fixture(Path(temporary), backend)
            with patch.object(backend, "compile", side_effect=RuntimeError("private-control-secret /run/private.sock")):
                self.assertEqual(runner.run(), task_runner.EXIT_SYSTEM_ERROR)
            events = runner.events_path.read_text()
            self.assertNotIn("private-control-secret", events)
            self.assertNotIn("/run/private.sock", events)
            self.assertEqual(json.loads(events.splitlines()[-1])["type"], "SYSTEM_ERROR")

    def test_direct_role_dispatch_preserves_existing_arguments_and_limits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = self.fixture(root, None)
            expected = task_runner.ProcessOutcome("SUCCESS", 0, b"", b"", 1, 0)
            with patch.object(task_runner, "run_process", return_value=expected) as local:
                actual = runner.run_role("SPECIAL_JUDGE", root / "program", b"",
                                         input_files={"case.in": root / "case.in", "case.out": root / "case.out"},
                                         timeout_multiplier=2)
            self.assertIs(actual, expected)
            self.assertEqual(local.call_args.kwargs["command"],
                             [str(root / "program"), str(root / "case.in"), str(root / "case.out")])
            self.assertEqual(local.call_args.kwargs["timeout_seconds"], 4)
            self.assertEqual(local.call_args.kwargs["memory_limit_bytes"], 134217728)
            self.assertEqual(local.call_args.kwargs["cwd"], runner.execution_dir)


if __name__ == "__main__":
    unittest.main()
