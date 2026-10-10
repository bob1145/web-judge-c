import json
import subprocess
import sys
import tempfile
import unittest
import uuid
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "runner" / "scripts" / "task_runner.py"
sys.path.insert(0, str(ROOT / "runner" / "scripts"))
from runner_manifest import load_manifest


class TaskRunnerContractTest(unittest.TestCase):
    def test_runner_writes_ac_summary_and_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            work_dir = Path(tmp).resolve()
            self._write_sources(
                work_dir,
                user='#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
                oracle='#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
            )
            spec = self._write_spec(work_dir, test_cases=2)

            completed = self._run(work_dir, spec)

            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            summary = json.loads((self.attempt_dir / "runner-summary.json").read_text(encoding="utf-8"))
            events = self._read_events(work_dir)
            self.assertEqual(summary["ac"], 2)
            self.assertEqual(events[-1]["type"], "COMPLETED")
            self.assertEqual([event["sequence"] for event in events], list(range(1, len(events) + 1)))
            self.assertTrue(all(event["protocolVersion"] == 2 for event in events))
            self.assertTrue(all(event["attemptId"] == self.attempt_dir.name for event in events))

    def test_compile_error_has_stable_exit_code_and_terminal_event(self):
        with tempfile.TemporaryDirectory() as tmp:
            work_dir = Path(tmp).resolve()
            self._write_sources(
                work_dir,
                user="int main(){ invalid c++ }\n",
                oracle='#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
            )
            spec = self._write_spec(work_dir, test_cases=1)

            completed = self._run(work_dir, spec)

            self.assertEqual(completed.returncode, 10, completed.stdout + completed.stderr)
            events = self._read_events(work_dir)
            self.assertEqual(events[-1]["type"], "SYSTEM_ERROR")
            self.assertIn("compile failed", events[-1]["message"])

    def test_v2_capability_probe_is_explicit_about_unimplemented_decimal_comparison(self):
        completed = subprocess.run([sys.executable, "-B", str(RUNNER), "--capabilities"],
                                   text=True, capture_output=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        capabilities = json.loads(completed.stdout)
        self.assertEqual(capabilities["versions"], [2])
        self.assertIn("LEGACY_TEXT", capabilities["comparisonModes"])
        self.assertIn("DECIMAL_ABSOLUTE", capabilities["comparisonModes"])

    def test_v2_rejects_all_unsafe_source_path_forms_without_writing(self):
        values = ["/tmp/outside.cpp", "../outside.cpp", "a/../user.cpp", "C:/user.cpp", "C:user.cpp",
                  "\\\\server\\share\\user.cpp", "//server/share/user.cpp", "a\\user.cpp", "./user.cpp",
                  "a//user.cpp", "", "control/user.cpp", "Control/user.cpp", "workspace/user.cpp", "NUL.cpp",
                  "user.cpp.", "user.cpp ", "user.cpp:stream", "missing.cpp"]
        for value in values:
            with self.subTest(path=value), tempfile.TemporaryDirectory() as tmp:
                root, spec = self._fixture(Path(tmp))
                manifest = json.loads(spec.read_text())
                manifest["sourcePaths"]["USER"] = value
                spec.write_text(json.dumps(manifest))
                self._assert_rejected_unchanged(root, spec)

    def test_v2_rejects_each_missing_field_unknown_fields_and_duplicates_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, spec = self._fixture(Path(tmp))
            original = json.loads(spec.read_text())
            for field in original:
                with self.subTest(missing=field):
                    manifest = dict(original)
                    manifest.pop(field)
                    spec.write_text(json.dumps(manifest))
                    self._assert_rejected_unchanged(root, spec)
            for field in ("workDir", "caseTimeLimit", "precision", "commands"):
                with self.subTest(unknown=field):
                    spec.write_text(json.dumps(dict(original, **{field: "untrusted"})))
                    self._assert_rejected_unchanged(root, spec)
            spec.write_text('{"protocolVersion":2,' + json.dumps(original)[1:])
            self._assert_rejected_unchanged(root, spec)

    def test_v2_rejects_wrong_versions_limits_comparison_and_types_without_writing(self):
        changes = [("protocolVersion", value) for value in (1, 99, 2.0, True)]
        changes += [("testCases", value) for value in (0, -1, 2.0, True, 2**31)]
        for field in ("caseTimeLimitMillis", "maxTaskRuntimeMillis", "memoryLimitBytes", "maxOutputBytesPerCase"):
            changes += [(field, value) for value in (0, -1, "1000", True, 2**63)]
        changes += [("comparison", {"mode": "LEGACY_TEXT", "precision": value})
                    for value in (None, 0, True, "NaN", "Infinity", "-0.1", "1e-9")]
        changes += [("comparison", {"mode": "UNKNOWN", "precision": "0"})]
        changes += [("comparison", {"mode": "DECIMAL_ABSOLUTE", "precision": value})
                    for value in (None, 0, True, "NaN", "Infinity", "-0.1", "1e1001", "1e-1001", "0" * 4097)]
        changes += [("stopOnFirstNonAc", value) for value in (None, "false", 0)]
        changes += [("retention", {"completedMillis": 1, "failedMillis": 1, "cancelledMillis": 0})]
        with tempfile.TemporaryDirectory() as tmp:
            root, spec = self._fixture(Path(tmp))
            original = json.loads(spec.read_text())
            for field, value in changes:
                with self.subTest(field=field, value=value):
                    spec.write_text(json.dumps(dict(original, **{field: value})))
                    self._assert_rejected_unchanged(root, spec)

    def test_v2_preserves_nonzero_decimal_precision_and_accepts_inclusive_budget(self):
        for precision in ("1.00000000000000000000000000000000000001", "1e1000", "1e-1000", "0" * 4096):
            with self.subTest(precision=precision), tempfile.TemporaryDirectory() as temporary:
                root, spec = self._fixture(Path(temporary))
                manifest = json.loads(spec.read_text())
                manifest["comparison"] = {"mode": "DECIMAL_ABSOLUTE", "precision": precision}
                spec.write_text(json.dumps(manifest))
                loaded, _, _, _ = load_manifest(spec, root)
                self.assertEqual(loaded["comparison"], manifest["comparison"])

    def test_v2_rejects_linked_source_before_creating_workspace_or_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root, spec = self._fixture(base / "task")
            outside = base / "outside.cpp"
            outside.write_text("outside must stay unchanged")
            (root / "linked.cpp").symlink_to(outside)
            manifest = json.loads(spec.read_text())
            manifest["sourcePaths"]["USER"] = "linked.cpp"
            spec.write_text(json.dumps(manifest))
            self._assert_rejected_unchanged(root, spec, base)

    def test_v2_rejects_linked_control_directory_and_root_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            outside, spec = self._fixture(base / "outside")
            root = base / "task"
            root.mkdir()
            (root / "control").symlink_to(outside / "control", target_is_directory=True)
            aliased_spec = root / spec.relative_to(outside)
            self._assert_rejected_unchanged(root, aliased_spec, base)
            alias = base / "root-link"
            alias.symlink_to(outside, target_is_directory=True)
            self._assert_rejected_unchanged(alias, alias / spec.relative_to(outside), base)

    def test_v2_runs_at_a_different_root_without_host_paths_or_host_history_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            host, spec = self._fixture(base / "host-storage")
            audit = ''.join(json.dumps({"event": event}) + "\n" for event in ("CREATED", "QUEUED", "STARTED", "POLICY"))
            (host / "events.jsonl").write_text(audit)
            (host / "summary.json").write_text("host summary")
            before = self._snapshot(host)
            execution = base / "different-execution-root"
            shutil.copytree(host, execution)
            moved_spec = execution / spec.relative_to(host)
            self.assertNotIn(str(host), moved_spec.read_text())
            completed = self._run(execution, moved_spec)
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertEqual(self._snapshot(host), before)
            self.assertEqual((execution / "events.jsonl").read_text(), audit)
            self.assertEqual((execution / "summary.json").read_text(), "host summary")
            events = [json.loads(line) for line in (moved_spec.parent / "runner-events.jsonl").read_text().splitlines()]
            self.assertEqual(events[0]["type"], "COMPILE_STARTED")
            self.assertEqual(events[-1]["type"], "COMPLETED")

    def test_v2_rejects_replayed_attempt_and_preserves_old_stream_during_new_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, first_spec = self._fixture(Path(tmp))
            completed = self._run(root, first_spec)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            first_events = (first_spec.parent / "runner-events.jsonl").read_bytes()
            self._assert_rejected_unchanged(root, first_spec)
            second_spec = self._write_spec(root, 1)
            completed = self._run(root, second_spec)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertNotEqual(first_spec, second_spec)
            self.assertEqual((first_spec.parent / "runner-events.jsonl").read_bytes(), first_events)

    def test_v2_special_judge_mode_executes_explicitly(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, spec = self._fixture(Path(tmp))
            (root / "spj.cpp").write_text("int main(){return 0;}")
            manifest = json.loads(spec.read_text())
            manifest["comparison"] = {"mode": "SPECIAL_JUDGE", "precision": "0"}
            manifest["sourcePaths"].pop("ORACLE")
            manifest["sourcePaths"]["SPECIAL_JUDGE"] = "spj.cpp"
            spec.write_text(json.dumps(manifest))
            completed = self._run(root, spec)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(json.loads((spec.parent / "runner-summary.json").read_text())["ac"], 1)

    def _fixture(self, root):
        root.mkdir(parents=True, exist_ok=True)
        self._write_sources(root, "int main(){return 0;}", "int main(){return 0;}")
        return root, self._write_spec(root, 1)

    def _snapshot(self, root):
        result = {}
        for path in sorted(root.rglob("*")):
            key = str(path.relative_to(root))
            result[key] = ("link", str(path.readlink())) if path.is_symlink() else (
                ("file", path.read_bytes()) if path.is_file() else ("directory",))
        return result

    def _assert_rejected_unchanged(self, root, spec, snapshot_root=None):
        snapshot_root = snapshot_root or root
        before = self._snapshot(snapshot_root)
        completed = self._run(root, spec)
        self.assertEqual(completed.returncode, 30, completed.stdout + completed.stderr)
        self.assertIn("manifest rejected before execution", completed.stderr)
        self.assertEqual(self._snapshot(snapshot_root), before)

    def _run(self, root, spec):
        return subprocess.run([sys.executable, "-B", str(RUNNER), "--work-root", str(root), str(spec)],
                              cwd=ROOT, text=True, capture_output=True, timeout=30)

    def _write_sources(self, work_dir: Path, user: str, oracle: str):
        (work_dir / "generator.cpp").write_text(
            '#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
            encoding="utf-8",
        )
        (work_dir / "user.cpp").write_text(user, encoding="utf-8")
        (work_dir / "oracle.cpp").write_text(oracle, encoding="utf-8")

    def _write_spec(self, work_dir: Path, test_cases: int) -> Path:
        spec = {
            "protocolVersion": 2,
            "judgeId": "runner-test",
            "attemptId": str(uuid.uuid4()),
            "comparison": {"mode": "LEGACY_TEXT", "precision": "0"},
            "sourcePaths": {
                "GENERATOR": "generator.cpp",
                "USER": "user.cpp",
                "ORACLE": "oracle.cpp",
            },
            "testCases": test_cases,
            "caseTimeLimitMillis": 2000,
            "maxTaskRuntimeMillis": 30000,
            "memoryLimitBytes": 134217728,
            "maxOutputBytesPerCase": 4096,
            "stopOnFirstNonAc": False,
            "retention": {"completedMillis": 86400000, "failedMillis": 86400000, "cancelledMillis": 86400000},
        }
        self.attempt_dir = work_dir / "control" / "attempts" / spec["attemptId"]
        self.attempt_dir.mkdir(parents=True)
        spec_path = self.attempt_dir / "task-spec.json"
        spec_path.write_text(json.dumps(spec), encoding="utf-8")
        return spec_path

    def _read_events(self, work_dir: Path):
        return [
            json.loads(line)
            for line in (self.attempt_dir / "runner-events.jsonl").read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]


if __name__ == "__main__":
    unittest.main()
