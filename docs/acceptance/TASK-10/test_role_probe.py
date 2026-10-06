import json
import sys
import unittest
from pathlib import Path

from role_probe import FORGED_OUTPUT, ROLES, manifest_fixture, role_source

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "runner/scripts"))
from runner_manifest import validate_manifest


class RoleProbeFixturesTest(unittest.TestCase):
    def source(self, role="USER", pid=123):
        return role_source(role, ["/lab/control/spec.json", "/proc/123/fd/9"],
                           ["/lab/control/spec.json"], pid, 9, "TASK10_SENTINEL", "/lab/private-data.txt")

    def test_manifest_matches_product_contract(self):
        fixture = manifest_fixture("12345678-1234-1234-1234-123456789abc")
        validate_manifest(fixture)
        self.assertEqual("LEGACY_TEXT", fixture["comparison"]["mode"])
        self.assertEqual({"GENERATOR", "USER", "ORACLE"}, set(fixture["sourcePaths"]))

    def test_fixture_limits_keep_compile_at_256_mib(self):
        fixture = manifest_fixture("12345678-1234-1234-1234-123456789abc")
        self.assertEqual(1, fixture["testCases"])
        self.assertEqual(128 * 1024 ** 2, fixture["memoryLimitBytes"])
        self.assertEqual(256 * 1024 ** 2, max(fixture["memoryLimitBytes"] * 2, 256 * 1024 ** 2))

    def test_every_role_source_is_fully_substituted(self):
        for role in ROLES:
            with self.subTest(role=role):
                source = self.source(role)
                self.assertNotIn("@", source)
                self.assertIn('const char* role = "' + role + '";', source)
                self.assertIn("kill(123, SIGCONT)", source)
                self.assertIn("errno != ESRCH", source)

    def test_include_guard_and_descriptor_path_are_explicit(self):
        source = self.source()
        self.assertIn('#if __has_include("/lab/control/spec.json")', source)
        self.assertIn("#error TASK10_CONTROL_HEADER_VISIBLE", source)
        self.assertIn('"/proc/123/fd/9"', source)
        self.assertIn('link.find("/lab/")', source)

    def test_forged_output_is_finite_non_authoritative_data(self):
        events = [json.loads(line) for line in FORGED_OUTPUT.splitlines()]
        self.assertLess(len(FORGED_OUTPUT.encode()), 4096)
        self.assertEqual(["SUMMARY", "COMPLETED"], [event["type"] for event in events])
        self.assertEqual(999, events[0]["summary"]["ac"])

    def test_pid_one_is_not_a_valid_trusted_sentinel(self):
        with self.assertRaisesRegex(RuntimeError, "identity"):
            self.source(pid=1)

    def test_unknown_role_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "identity"):
            self.source(role="ADMIN")


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RoleProbeFixturesTest))
    print(json.dumps({"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                      "skipped": len(result.skipped)}))
    sys.exit(0 if result.wasSuccessful() and result.testsRun == 7 else 1)
