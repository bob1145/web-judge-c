import json
import sys
import unittest
from pathlib import Path

from stage_probe import finish_resource_cleanup, validate_stage_inspection, validate_volume


class StageProbeValidatorsTest(unittest.TestCase):
    def volume_fixture(self):
        return {"Name": "stage-fixture", "Driver": "local", "Labels": {"io.webjudge.attempt": "attempt"},
                "Options": {"type": "tmpfs", "device": "tmpfs", "o": "size=33554432,uid=0,gid=0,mode=0755,nodev,nosuid"},
                "Mountpoint": "/var/lib/wjc/docker/volumes/stage-fixture/_data"}

    def validate_volume(self, payload):
        return validate_volume(payload, "stage-fixture", {"io.webjudge.attempt": "attempt"})

    def stage_fixture(self):
        return {"Id": "a" * 64,
                "Config": {"User": "65532:65532", "Labels": {"io.webjudge.attempt": "attempt", "io.webjudge.managed": "stage"}},
                "HostConfig": {"ReadonlyRootfs": True, "NetworkMode": "none", "Memory": 256 * 1024 ** 2,
                               "MemorySwap": 256 * 1024 ** 2, "PidsLimit": 32, "NanoCpus": 500000000,
                               "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"],
                               "Mounts": [{"Target": "/input", "VolumeOptions": {"NoCopy": True}},
                                          {"Target": "/scratch", "VolumeOptions": {"NoCopy": True}}]},
                "Mounts": [{"Type": "volume", "Name": "stage-fixture", "Destination": "/input", "RW": False},
                           {"Type": "volume", "Name": "stage-fixture", "Destination": "/scratch", "RW": True}]}

    def validate_stage(self, payload):
        validate_stage_inspection(payload, "a" * 64, "attempt", "stage-fixture")

    def test_expected_volume_is_accepted(self):
        self.assertEqual(Path("/var/lib/wjc/docker/volumes/stage-fixture/_data"), self.validate_volume(self.volume_fixture()))

    def test_foreign_volume_is_rejected(self):
        payload = self.volume_fixture()
        payload["Labels"]["io.webjudge.attempt"] = "foreign"
        with self.assertRaises(RuntimeError):
            self.validate_volume(payload)

    def test_unbounded_volume_is_rejected(self):
        payload = self.volume_fixture()
        payload["Options"]["o"] = "uid=0,gid=0,mode=0755,nodev,nosuid"
        with self.assertRaises(RuntimeError):
            self.validate_volume(payload)

    def test_unexpected_volume_mountpoint_is_rejected(self):
        payload = self.volume_fixture()
        payload["Mountpoint"] = "/lab/control"
        with self.assertRaises(RuntimeError):
            self.validate_volume(payload)

    def test_expected_stage_is_accepted(self):
        self.validate_stage(self.stage_fixture())

    def test_volume_copy_up_is_rejected_for_both_mounts(self):
        for index in range(2):
            with self.subTest(index=index):
                payload = self.stage_fixture()
                payload["HostConfig"]["Mounts"][index]["VolumeOptions"]["NoCopy"] = False
                with self.assertRaisesRegex(RuntimeError, "copy-up"):
                    self.validate_stage(payload)

    def test_writable_input_is_rejected(self):
        payload = self.stage_fixture()
        payload["Mounts"][0]["RW"] = True
        with self.assertRaises(RuntimeError):
            self.validate_stage(payload)

    def test_extra_stage_mount_is_rejected(self):
        payload = self.stage_fixture()
        payload["Mounts"].append({"Destination": "/control"})
        with self.assertRaises(RuntimeError):
            self.validate_stage(payload)

    def test_missing_stage_memory_limit_is_rejected(self):
        payload = self.stage_fixture()
        payload["HostConfig"]["Memory"] = 0
        with self.assertRaises(RuntimeError):
            self.validate_stage(payload)

    def test_foreign_stage_is_rejected(self):
        payload = self.stage_fixture()
        payload["Config"]["Labels"]["io.webjudge.attempt"] = "foreign"
        with self.assertRaises(RuntimeError):
            self.validate_stage(payload)

    def test_normal_teardown_invokes_owned_resource_cleanup(self):
        calls = []
        records = []
        result = finish_resource_cleanup(False, lambda: calls.append("cleanup"),
                                         lambda *args, **kwargs: records.append((args, kwargs)), "attempt")
        self.assertTrue(result)
        self.assertEqual(["cleanup"], calls)
        self.assertEqual([], records)

    def test_domain_stop_skips_all_resource_cleanup_without_success_claim(self):
        records = []
        def forbidden_cleanup():
            self.fail("stage, holder or volume cleanup must not run")
        result = finish_resource_cleanup(True, forbidden_cleanup,
                                         lambda *args, **kwargs: records.append((args, kwargs)), "attempt")
        self.assertFalse(result)
        self.assertEqual(1, len(records))
        self.assertEqual(("stage-resource-cleanup-skipped-for-domain-stop",), records[0][0])
        self.assertTrue(records[0][1]["requiresDomainStop"])
        self.assertFalse(records[0][1]["resourcesVerifiedRemoved"])

    def test_normal_cleanup_failure_is_not_reported_as_success(self):
        records = []
        def failing_cleanup():
            raise RuntimeError("fixture cleanup failed")
        with self.assertRaisesRegex(RuntimeError, "fixture cleanup failed"):
            finish_resource_cleanup(False, failing_cleanup,
                                    lambda *args, **kwargs: records.append((args, kwargs)), "attempt")
        self.assertEqual([], records)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(StageProbeValidatorsTest))
    print(json.dumps({"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                      "skipped": len(result.skipped)}))
    sys.exit(0 if result.wasSuccessful() and result.testsRun == 13 else 1)
