import json
import os
import signal
import sys
import tempfile
import time
import unittest
from decimal import localcontext
from pathlib import Path
from unittest.mock import patch

import test_task_runner_contract as contract_fixture


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import task_runner


class LegacyTextComparisonTest(unittest.TestCase):
    def test_ascii_boundary_whitespace_preserves_shared_text_vectors(self):
        vectors = json.loads((contract_fixture.ROOT / "src/test/resources/comparison/ordinary-output-vectors.json")
                             .read_text(encoding="utf-8"))
        selected_ids = {"leading-nul-is-not-whitespace", "trailing-control-is-not-whitespace",
                        "nul-only-is-not-empty", "identical-control-text", "unicode-space-is-not-ascii",
                        "identical-unicode-space-text", "empty-output"}
        selected = [vector for vector in vectors if vector["id"] in selected_ids]
        self.assertEqual({vector["id"] for vector in selected}, selected_ids)
        for vector in selected:
            with self.subTest(vector=vector["id"]):
                actual = task_runner.normalize_output(vector["userOutput"].encode("utf-8"))
                expected = task_runner.normalize_output(vector["expectedOutput"].encode("utf-8"))
                self.assertEqual(actual == expected, vector["matches"])


class DecimalComparisonTest(unittest.TestCase):
    def test_all_shared_vectors_are_exact_even_with_a_small_decimal_context(self):
        vectors = json.loads((contract_fixture.ROOT / "src/test/resources/comparison/ordinary-output-vectors.json")
                             .read_text(encoding="utf-8"))
        with localcontext() as context:
            context.prec = 2
            for vector in vectors:
                with self.subTest(vector=vector["id"]):
                    arguments = (vector["userOutput"].encode("utf-8"), vector["expectedOutput"].encode("utf-8"),
                                 {"mode": "DECIMAL_ABSOLUTE", "precision": vector["precision"]})
                    if vector.get("error"):
                        self.assertEqual(vector["error"], "System Error")
                        with self.assertRaises(ValueError):
                            task_runner.outputs_match(*arguments)
                    else:
                        self.assertEqual(task_runner.outputs_match(*arguments), vector["matches"])

    def test_invalid_utf8_is_rejected_in_both_ordinary_modes_and_both_outputs(self):
        for mode in ("LEGACY_TEXT", "DECIMAL_ABSOLUTE"):
            for user, oracle in ((b"\xff", b"\xef\xbf\xbd"), (b"0", b"\xff")):
                with self.subTest(mode=mode, user=user, oracle=oracle), self.assertRaises(UnicodeError):
                    task_runner.outputs_match(user, oracle, {"mode": mode, "precision": "0"})


class RunProcessOutputLimitTest(unittest.TestCase):
    @unittest.skipUnless(hasattr(os, "fork"), "requires POSIX process groups")
    def test_exited_parent_with_inherited_pipes_is_bounded_and_never_reported_as_success(self):
        timeout_seconds = 0.3
        for escaped in (False, True):
            with self.subTest(escaped=escaped), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                pid_path = root / "child.pid"
                program = ("import os, time\n"
                           "from pathlib import Path\n"
                           "if os.fork() == 0:\n"
                           + ("    os.setsid()\n" if escaped else "")
                           + "    Path('child.pid').write_text(str(os.getpid()))\n"
                             "    time.sleep(20)\n"
                             "else:\n"
                             "    os._exit(0)\n")
                start = time.monotonic()
                try:
                    if escaped:
                        with self.assertRaisesRegex(OSError, "cleanup deadline exceeded.*stop is not confirmed"):
                            task_runner.run_process([sys.executable, "-B", "-c", program], b"", root,
                                                    timeout_seconds, 64 * 1024 * 1024, 4096, root / "cancel")
                    else:
                        outcome = task_runner.run_process([sys.executable, "-B", "-c", program], b"", root,
                                                          timeout_seconds, 64 * 1024 * 1024, 4096, root / "cancel")
                        self.assertEqual(outcome.status, "TIME_LIMIT_EXCEEDED")
                        self.assertNotEqual(outcome.exit_code, 0)
                    self.assertLess(time.monotonic() - start, timeout_seconds + task_runner.CLEANUP_SECONDS + 1)
                    self.assertTrue(pid_path.is_file())
                    status_path = Path("/proc") / pid_path.read_text() / "stat"
                    if status_path.exists():
                        state = status_path.read_text().split(")", 1)[1].split()[0]
                        self.assertNotEqual(state, "Z") if escaped else self.assertEqual(state, "Z")
                finally:
                    if pid_path.exists():
                        try:
                            os.kill(int(pid_path.read_text()), signal.SIGKILL)
                        except ProcessLookupError:
                            pass

    def test_windows_termination_command_receives_a_finite_cleanup_budget(self):
        process = unittest.mock.Mock()
        process.poll.return_value = None
        process.pid = 12345
        with patch.object(task_runner.os, "name", "nt"), patch.object(task_runner.subprocess, "run") as stop:
            task_runner.kill_process_tree(process, 0.75)
        self.assertEqual(stop.call_args.kwargs["timeout"], 0.75)
        self.assertEqual(stop.call_args.args[0], ["taskkill", "/F", "/T", "/PID", "12345"])

    def test_both_streams_share_the_limit_and_retained_prefix_budget(self):
        limit = 4096
        sizes = [(limit - 1, 0), (limit, 0), (limit + 1, 0),
                 (0, limit - 1), (0, limit), (0, limit + 1),
                 (limit // 2, limit // 2 - 1), (limit // 2, limit // 2),
                 (limit // 2, limit // 2 + 1), (limit + 1, limit + 1)]
        for stdout_bytes, stderr_bytes in sizes:
            with self.subTest(stdout=stdout_bytes, stderr=stderr_bytes), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                program = ("import os; os.write(1, b'o' * %d); os.write(2, b'e' * %d)"
                           % (stdout_bytes, stderr_bytes))
                outcome = task_runner.run_process([sys.executable, "-B", "-c", program],
                                                  b"", root, 2, 64 * 1024 * 1024, limit, root / "cancel")
                exceeds = stdout_bytes + stderr_bytes > limit
                self.assertEqual(outcome.status, "OUTPUT_LIMIT_EXCEEDED" if exceeds else "SUCCESS")
                self.assertLessEqual(len(outcome.stdout) + len(outcome.stderr), limit)
                self.assertEqual(outcome.stdout, b"o" * min(stdout_bytes, limit))
                self.assertEqual(outcome.stderr, b"e" * min(stderr_bytes, max(0, limit - stdout_bytes)))


    def test_excess_is_detected_before_the_process_closes_its_pipes(self):
        limit = 4096
        for stdout_bytes, stderr_bytes in [(limit + 1, 0), (0, limit + 1),
                                           (limit // 2, limit // 2 + 1)]:
            with self.subTest(stdout=stdout_bytes, stderr=stderr_bytes), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                program = ("import os, time; os.write(1, b'o' * %d); os.write(2, b'e' * %d); time.sleep(10)"
                           % (stdout_bytes, stderr_bytes))
                outcome = task_runner.run_process([sys.executable, "-B", "-c", program],
                                                  b"", root, 2, 64 * 1024 * 1024, limit, root / "cancel")
                self.assertEqual(outcome.status, "OUTPUT_LIMIT_EXCEEDED")
                self.assertNotEqual(outcome.exit_code, 0)
                self.assertLessEqual(len(outcome.stdout) + len(outcome.stderr), limit)

    def test_input_and_both_outputs_are_drained_without_deadlocking_or_rejecting_closed_input(self):
        stdin = b"q" * (128 * 1024)
        program = ("import sys; sys.stdout.buffer.write(b'o' * 16384); sys.stdout.buffer.flush(); "
                   "sys.stderr.buffer.write(b'e' * 16384); sys.stderr.buffer.flush(); "
                   "sys.stdout.buffer.write(sys.stdin.buffer.read())")
        for shape in ("duplex", "closed-input"):
            with self.subTest(shape=shape), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                outcome = task_runner.run_process([sys.executable, "-B", "-c", program if shape == "duplex" else "pass"],
                                                  stdin, root, 2, 64 * 1024 * 1024,
                                                  len(stdin) + 32768, root / "cancel")
                self.assertEqual(outcome.status, "SUCCESS")
                self.assertEqual(outcome.exit_code, 0)
                self.assertEqual(outcome.stdout, b"o" * 16384 + stdin if shape == "duplex" else b"")
                self.assertEqual(outcome.stderr, b"e" * 16384 if shape == "duplex" else b"")


    def test_pipe_read_failure_is_not_reported_as_success(self):
        class FailingReadPipe:
            def __init__(self, pipe):
                self.pipe = pipe

            def __enter__(self):
                return self

            def __exit__(self, exception_type, exception, traceback):
                self.close()

            def close(self):
                self.pipe.close()

            def read1(self, maximum_bytes):
                raise OSError("injected pipe read failure")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            original_popen = task_runner.subprocess.Popen

            def spawn_with_failure(*args, **kwargs):
                process = original_popen(*args, **kwargs)
                if kwargs.get("cwd") == str(root):
                    process.stdout = FailingReadPipe(process.stdout)
                return process

            with patch.object(task_runner.subprocess, "Popen", side_effect=spawn_with_failure):
                with self.assertRaisesRegex(OSError, "injected pipe read failure"):
                    task_runner.run_process([sys.executable, "-B", "-c", "import time; time.sleep(10)"],
                                            b"", root, 2, 64 * 1024 * 1024, 4096, root / "cancel")


class RecordingStageExecutor:
    def __init__(self, user_output=b"1\n", cancelled_role=None, reject_compile=False,
                 limited_role=None, limited_compile_role=None, oracle_output=b"1\n"):
        self.calls = []
        self.user_output = user_output
        self.oracle_output = oracle_output
        self.cancelled_role = cancelled_role
        self.reject_compile = reject_compile
        self.limited_role = limited_role
        self.limited_compile_role = limited_compile_role

    def compile(self, role, source, output, **limits):
        self.calls.append({"phase": "compile", "role": role, "source": source, "output": output, "limits": limits})
        if self.reject_compile:
            from linux_stage_executor import StageSecurityError
            raise StageSecurityError("private-control-secret")
        if role == self.limited_compile_role:
            return task_runner.ProcessOutcome("OUTPUT_LIMIT_EXCEEDED", -1, b"", b"bounded compiler prefix", 1, 0)
        output.write_bytes(b"contract-only-executable")
        return task_runner.ProcessOutcome("SUCCESS", 0, b"", b"", 1, 0)

    def execute(self, role, executable, arguments, input_files, **limits):
        self.calls.append({"phase": "execute", "role": role, "executable": executable,
                           "arguments": arguments, "input_files": input_files, "limits": limits})
        if role == self.cancelled_role:
            return task_runner.ProcessOutcome("CANCELLED", -1, b"", b"", 1, 0)
        if role == self.limited_role:
            return task_runner.ProcessOutcome("OUTPUT_LIMIT_EXCEEDED", -1, b"1\n", b"", 1, 0)
        output = self.user_output if role == "USER" else self.oracle_output if role == "ORACLE" else b"1\n"
        return task_runner.ProcessOutcome("SUCCESS", 0, output, b"", 1, 0)


class TaskRunnerStageDispatchTest(unittest.TestCase):
    def fixture(self, root, backend, special_judge=False, cases=1, stop_on_first=False):
        helper = contract_fixture.TaskRunnerContractTest()
        root, spec = helper._fixture(root)
        if cases != 1 or stop_on_first:
            manifest = json.loads(spec.read_text())
            manifest["testCases"] = cases
            manifest["stopOnFirstNonAc"] = stop_on_first
            spec.write_text(json.dumps(manifest))
        if special_judge:
            (root / "spj.cpp").write_text("int main(){return 0;}")
            manifest = json.loads(spec.read_text())
            manifest["sourcePaths"].pop("ORACLE")
            manifest["sourcePaths"]["SPECIAL_JUDGE"] = "spj.cpp"
            manifest["comparison"] = {"mode": "SPECIAL_JUDGE", "precision": "0"}
            spec.write_text(json.dumps(manifest))
        return task_runner.TaskRunner(spec, root, stage_executor=backend)

    def test_comparison_input_errors_are_case_system_errors_without_false_ac_or_wa(self):
        for mode in ("LEGACY_TEXT", "DECIMAL_ABSOLUTE"):
            for user, oracle in ((b"\xff", b"1"), (b"1", b"\xff"), (b"x" * 4097, b"1"), (b"1", b"1e1001")):
                with self.subTest(mode=mode, user=user[:8], oracle=oracle[:8]), tempfile.TemporaryDirectory() as temporary:
                    backend = RecordingStageExecutor(user_output=user, oracle_output=oracle)
                    runner = self.fixture(Path(temporary), backend)
                    runner.spec["comparison"] = {"mode": mode, "precision": "0"}
                    self.assertEqual(runner.run(), task_runner.EXIT_COMPLETED)
                    self.assertEqual(runner.summary["completedCases"], 1)
                    self.assertEqual(runner.summary["systemError"], 1)
                    self.assertEqual(runner.summary["ac"], 0)
                    self.assertEqual(runner.summary["wa"], 0)
                    events = [json.loads(line) for line in runner.events_path.read_text().splitlines()]
                    results = [event["result"] for event in events if event.get("result")]
                    self.assertEqual([result["status"] for result in results], ["System Error"])

    def test_output_limits_use_case_verdicts_and_preserve_the_original_stop_policy(self):
        for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"):
            for stop_on_first in (False, True):
                with self.subTest(role=role, stop=stop_on_first), tempfile.TemporaryDirectory() as temporary:
                    backend = RecordingStageExecutor(limited_role=role)
                    runner = self.fixture(Path(temporary), backend, special_judge=role == "SPECIAL_JUDGE",
                                          cases=2, stop_on_first=stop_on_first)
                    self.assertEqual(runner.run(), task_runner.EXIT_COMPLETED)
                    completed = 1 if stop_on_first else 2
                    self.assertEqual(runner.summary["completedCases"], completed)
                    self.assertEqual(runner.summary["outputLimitExceeded"], completed)
                    self.assertEqual(runner.summary["ac"], 0)
                    self.assertEqual(runner.summary["systemError"], 0)
                    self.assertEqual(runner.summary["firstFailedCase"], 1)
                    expected_roles = {"GENERATOR": ["GENERATOR"], "USER": ["GENERATOR", "USER"],
                                      "ORACLE": ["GENERATOR", "USER", "ORACLE"],
                                      "SPECIAL_JUDGE": ["GENERATOR", "USER", "SPECIAL_JUDGE"]}[role]
                    self.assertEqual([call["role"] for call in backend.calls if call["phase"] == "execute"],
                                     expected_roles * completed)
                    self.assertEqual(runner.summary["stoppedReason"],
                                     "Stopped after first non-AC test case" if stop_on_first else None)

    def test_compile_output_limit_has_an_explicit_cause_without_case_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            backend = RecordingStageExecutor(limited_compile_role="USER")
            runner = self.fixture(Path(temporary), backend)
            self.assertEqual(runner.run(), task_runner.EXIT_COMPILE_ERROR)
            events = [json.loads(line) for line in runner.events_path.read_text().splitlines()]
            finished = next(event for event in events if event["type"] == "COMPILE_FINISHED")
            self.assertIn("OUTPUT_LIMIT_EXCEEDED", finished["message"])
            self.assertIn("USER", finished["message"])
            self.assertFalse(any(event["type"] == "RUN_FINISHED" for event in events))
            self.assertEqual(runner.summary["completedCases"], 0)
            self.assertEqual([call["phase"] for call in backend.calls], ["compile", "compile"])

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

    def test_remaining_task_budget_limits_direct_and_isolated_operations(self):
        for isolated in (False, True):
            for compile_phase in (False, True):
                with self.subTest(isolated=isolated, compile_phase=compile_phase), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    backend = RecordingStageExecutor() if isolated else None
                    runner = self.fixture(root, backend)
                    runner.spec["maxTaskRuntimeMillis"] = 5000
                    runner.started_at = 96
                    runner.bin_dir.mkdir(parents=True)
                    outcome = task_runner.ProcessOutcome("SUCCESS", 0, b"", b"", 1, 0)
                    with patch.object(task_runner.time, "monotonic", return_value=100), \
                            patch.object(task_runner, "run_process", return_value=outcome) as local:
                        if compile_phase:
                            runner.compile_sources()
                        else:
                            runner.run_role("USER", root / "program", b"")
                    limits = [call["limits"] for call in backend.calls] if isolated else \
                        [call.kwargs for call in local.call_args_list]
                    self.assertTrue(limits)
                    self.assertTrue(all(limit["timeout_seconds"] == 1 for limit in limits))
                    if isolated:
                        local.assert_not_called()

    def test_budget_limited_timeout_is_not_a_case_or_compile_failure(self):
        for isolated in (False, True):
            for compile_phase in (False, True):
                with self.subTest(isolated=isolated, compile_phase=compile_phase), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    backend = RecordingStageExecutor() if isolated else None
                    runner = self.fixture(root, backend)
                    runner.spec["maxTaskRuntimeMillis"] = 5000
                    runner.started_at = 96
                    runner.bin_dir.mkdir(parents=True)
                    outcome = task_runner.ProcessOutcome("TIME_LIMIT_EXCEEDED", -1, b"", b"", 1000, 0)
                    target = backend if isolated else task_runner
                    method = "compile" if isolated and compile_phase else "execute" if isolated else "run_process"
                    with patch.object(task_runner.time, "monotonic", return_value=100), \
                            patch.object(target, method, return_value=outcome):
                        with self.assertRaises(task_runner.BudgetExceeded):
                            if compile_phase:
                                runner.compile_sources()
                            else:
                                runner.run_role("USER", root / "program", b"")
                    self.assertEqual(runner.summary["completedCases"], 0)

    def test_exhausted_budget_does_not_dispatch_another_operation(self):
        for isolated in (False, True):
            with self.subTest(isolated=isolated), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                backend = RecordingStageExecutor() if isolated else None
                runner = self.fixture(root, backend)
                runner.spec["maxTaskRuntimeMillis"] = 5000
                runner.started_at = 95
                with patch.object(task_runner.time, "monotonic", return_value=100), \
                        patch.object(task_runner, "run_process") as local:
                    with self.assertRaises(task_runner.BudgetExceeded):
                        runner.run_role("USER", root / "program", b"")
                local.assert_not_called()
                if isolated:
                    self.assertEqual(backend.calls, [])

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
