import json
import sys
import unittest
from pathlib import Path


output_path = Path(sys.argv[1])
suite = unittest.defaultTestLoader.discover("runner/tests", pattern="test_*.py")
result = unittest.TextTestRunner(verbosity=2).run(suite)
exit_code = 0 if result.wasSuccessful() and result.testsRun > 0 else 1
summary = {
    "command": [sys.executable, "-B", "runner/tests/run_contract_suite.py", str(output_path)],
    "exit_code": exit_code,
    "tests": result.testsRun,
    "failures": len(result.failures),
    "errors": len(result.errors),
    "skipped": len(result.skipped),
    "nonpassing": [{"test": str(test), "detail": detail} for test, detail in result.failures + result.errors],
}
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
sys.exit(exit_code)
