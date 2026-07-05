import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "runner" / "scripts" / "task_runner.py"


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

            completed = subprocess.run([sys.executable, str(RUNNER), str(spec)], cwd=ROOT, text=True, capture_output=True)

            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            summary = json.loads((work_dir / "summary.json").read_text(encoding="utf-8"))
            events = self._read_events(work_dir)
            self.assertEqual(summary["ac"], 2)
            self.assertEqual(events[-1]["type"], "COMPLETED")

    def test_compile_error_has_stable_exit_code_and_terminal_event(self):
        with tempfile.TemporaryDirectory() as tmp:
            work_dir = Path(tmp).resolve()
            self._write_sources(
                work_dir,
                user="int main(){ invalid c++ }\n",
                oracle='#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
            )
            spec = self._write_spec(work_dir, test_cases=1)

            completed = subprocess.run([sys.executable, str(RUNNER), str(spec)], cwd=ROOT, text=True, capture_output=True)

            self.assertEqual(completed.returncode, 10, completed.stdout + completed.stderr)
            events = self._read_events(work_dir)
            self.assertEqual(events[-1]["type"], "SYSTEM_ERROR")
            self.assertIn("compile failed", events[-1]["message"])

    def _write_sources(self, work_dir: Path, user: str, oracle: str):
        (work_dir / "generator.cpp").write_text(
            '#include <iostream>\nint main(){int x; if(std::cin>>x) std::cout << x << "\\n";}\n',
            encoding="utf-8",
        )
        (work_dir / "user.cpp").write_text(user, encoding="utf-8")
        (work_dir / "oracle.cpp").write_text(oracle, encoding="utf-8")

    def _write_spec(self, work_dir: Path, test_cases: int) -> Path:
        spec = {
            "judgeId": "runner-test",
            "userId": "runner-user",
            "profile": "worker-prod",
            "workDir": str(work_dir),
            "sourcePaths": {
                "GENERATOR": str(work_dir / "generator.cpp"),
                "USER": str(work_dir / "user.cpp"),
                "ORACLE": str(work_dir / "oracle.cpp"),
            },
            "testCases": test_cases,
            "caseTimeLimit": 2,
            "maxTaskRuntime": 30,
            "memoryLimitBytes": 134217728,
            "maxOutputBytesPerCase": 4096,
            "stopOnFirstNonAc": False,
            "retention": {"completed": 86400, "failed": 86400, "cancelled": 86400},
        }
        spec_path = work_dir / "sandbox-task.json"
        spec_path.write_text(json.dumps(spec), encoding="utf-8")
        return spec_path

    def _read_events(self, work_dir: Path):
        return [
            json.loads(line)
            for line in (work_dir / "events.jsonl").read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]


if __name__ == "__main__":
    unittest.main()
