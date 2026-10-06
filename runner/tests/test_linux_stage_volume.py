import json
import os
import stat
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from linux_stage_executor import StageSecurityError
from linux_stage_volume import verify_stage_volume


@unittest.skipUnless(os.name == "posix", "Linux mount verification contract")
class LinuxStageVolumeContractTest(unittest.TestCase):
    """Metadata and syscall doubles only; not real container isolation evidence."""

    def setUp(self):
        self.root = "/var/lib/docker/volumes/task-stage/_data"
        self.config = SimpleNamespace(stage_root=Path(self.root), stage_volume="task-stage")
        self.attempt = "11111111-1111-4111-8111-111111111111"
        self.holder_id = "a" * 64
        self.limit = 33554432
        self.volume = {
            "Name": "task-stage", "Driver": "local", "Scope": "local",
            "Mountpoint": self.root,
            "Labels": {"io.webjudge.attempt": self.attempt, "io.webjudge.managed": "stage-volume"},
            "Options": {"type": "tmpfs", "device": "tmpfs",
                        "o": "size=33554432,nodev,nosuid,uid=0,gid=0,mode=0755"},
        }
        self.holder = {
            "Id": self.holder_id, "State": {"Running": True},
            "Config": {"Labels": {"io.webjudge.attempt": self.attempt,
                                    "io.webjudge.managed": "stage-volume-holder"}},
            "HostConfig": {"Privileged": False, "NetworkMode": "none",
                           "PidMode": "", "IpcMode": "private"},
            "Mounts": [{"Type": "volume", "Name": "task-stage", "Source": self.root,
                        "Destination": "/stage-root", "RW": True}],
        }
        self.commands = []
        self.mountinfo = ("1 0 0:1 / / rw - ext4 /dev/root rw\n"
                          f"2 1 0:2 / {self.root} rw,nosuid,nodev - tmpfs tmpfs rw,size=32768k\n")
        self.info = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0, st_gid=0)
        self.capacity = SimpleNamespace(f_frsize=4096, f_blocks=8192)
        self.start_patch("linux_stage_volume.open_directory", return_value=17)
        self.start_patch("linux_stage_volume.os.fstat", side_effect=lambda descriptor: self.info)
        self.start_patch("linux_stage_volume.os.fstatvfs", side_effect=lambda descriptor: self.capacity)
        self.close = self.start_patch("linux_stage_volume.os.close")
        self.start_patch("linux_stage_volume.Path.read_text", side_effect=lambda **kwargs: self.mountinfo)

    def start_patch(self, target, **kwargs):
        patcher = patch(target, **kwargs)
        mocked = patcher.start()
        self.addCleanup(patcher.stop)
        return mocked

    def control(self, *arguments):
        self.commands.append(arguments)
        payload = self.volume if arguments == ("volume", "inspect", "task-stage") else self.holder
        self.assertIn(arguments, [("volume", "inspect", "task-stage"),
                                  ("container", "inspect", self.holder_id)])
        return subprocess.CompletedProcess(arguments, 0, json.dumps([payload]).encode(), b"")

    def verify(self, **overrides):
        arguments = dict(config=self.config, attempt_id=self.attempt, volume_limit_bytes=self.limit,
                         holder_id=self.holder_id, control=self.control)
        arguments.update(overrides)
        return verify_stage_volume(**arguments)

    def reject(self, **overrides):
        with self.assertRaisesRegex(StageSecurityError, "^stage volume verification failed$") as raised:
            self.verify(**overrides)
        self.assertIsNone(raised.exception.__cause__)
        self.assertTrue(raised.exception.__suppress_context__)

    def test_accepts_exact_owned_tmpfs_and_running_holder(self):
        self.assertIsNone(self.verify())
        self.assertEqual(self.commands, [("volume", "inspect", "task-stage"),
                                        ("container", "inspect", self.holder_id)])
        self.close.assert_called_once_with(17)

    def test_mountinfo_size_units_are_exact(self):
        for size in ("33554432", "32768k", "32m"):
            with self.subTest(size=size):
                self.mountinfo = self.mountinfo.replace(self.mountinfo.split("size=")[-1].strip(), size)
                self.assertIsNone(self.verify())

    def test_explicit_identity_and_positive_limit_required(self):
        for override in ({"volume_limit_bytes": 0}, {"volume_limit_bytes": True},
                         {"volume_limit_bytes": 1.0}, {"holder_id": "a" * 12},
                         {"attempt_id": "other-attempt"}):
            with self.subTest(override=override):
                self.reject(**override)
        self.assertEqual(self.commands, [])

    def test_foreign_volume_labels_rejected(self):
        self.volume["Labels"]["io.webjudge.attempt"] = "foreign"
        self.reject()

    def test_ordinary_or_remote_volume_rejected(self):
        for key, value in (("Options", {}), ("Driver", "foreign"), ("Scope", "global")):
            with self.subTest(key=key):
                previous = self.volume[key]
                self.volume[key] = value
                self.reject()
                self.volume[key] = previous

    def test_options_must_bound_size_and_secure_mount(self):
        initial = self.volume["Options"]["o"]
        for options in (initial.replace("33554432", "33554433"),
                        initial.replace("33554432", "32m"),
                        initial.replace("nodev,", ""), initial.replace("uid=0", "uid=65532"),
                        initial.replace("0755", "0777"), initial + ",suid", initial + ",size=33554432"):
            with self.subTest(options=options):
                self.volume["Options"]["o"] = options
                self.reject()

    def test_wrong_mountpoint_rejected(self):
        self.volume["Mountpoint"] = "/foreign"
        self.reject()

    def test_normalized_absolute_root_required(self):
        for root in (Path("relative"), Path("/var/../foreign"), Path("/")):
            with self.subTest(root=root):
                self.config.stage_root = root
                self.reject()

    def test_plain_directory_without_exact_mount_rejected(self):
        self.mountinfo = "1 0 0:1 / / rw - ext4 /dev/root rw\n"
        self.reject()
        self.close.assert_called_once_with(17)

    def test_mount_type_size_flags_and_bind_root_rejected(self):
        initial = self.mountinfo
        for old, new in (("tmpfs tmpfs", "ext4 /dev/root"), ("size=32768k", "size=65536k"),
                         ("rw,nosuid,nodev", "rw,nodev"), ("rw,nosuid,nodev", "ro,nosuid,nodev"),
                         ("0:2 / ", "0:2 /subdirectory ")):
            with self.subTest(old=old, new=new):
                self.mountinfo = initial.replace(old, new)
                self.reject()

    def test_ambiguous_stacked_mounts_rejected(self):
        self.mountinfo += self.mountinfo.splitlines()[-1] + "\n"
        self.reject()

    def test_actual_capacity_cannot_exceed_explicit_limit(self):
        self.capacity.f_blocks += 1
        self.reject()

    def test_root_must_be_owned_by_root_and_nonwritable_to_stage(self):
        for key, value in (("st_uid", 65532), ("st_gid", 65532), ("st_mode", stat.S_IFDIR | 0o777)):
            with self.subTest(key=key):
                previous = getattr(self.info, key)
                setattr(self.info, key, value)
                self.reject()
                setattr(self.info, key, previous)

    def test_foreign_or_stopped_holder_rejected(self):
        for target, key, value in ((self.holder, "Id", "b" * 64),
                                   (self.holder["State"], "Running", False),
                                   (self.holder["Config"]["Labels"], "io.webjudge.attempt", "foreign")):
            with self.subTest(key=key):
                previous = target[key]
                target[key] = value
                self.reject()
                target[key] = previous

    def test_holder_cannot_share_host_namespaces_or_other_mounts(self):
        for key, value in (("Privileged", True), ("NetworkMode", "host"),
                           ("PidMode", "host"), ("IpcMode", "host")):
            with self.subTest(key=key):
                previous = self.holder["HostConfig"][key]
                self.holder["HostConfig"][key] = value
                self.reject()
                self.holder["HostConfig"][key] = previous
        self.holder["Mounts"].append({"Type": "bind", "Source": "/run/docker.sock"})
        self.reject()

    def test_holder_mount_must_be_exact_expected_volume(self):
        mount = self.holder["Mounts"][0]
        for key, value in (("Type", "bind"), ("Name", "foreign"), ("Source", "/foreign"),
                           ("Destination", "/unexpected"), ("RW", False)):
            with self.subTest(key=key):
                previous = mount[key]
                mount[key] = value
                self.reject()
                mount[key] = previous

    def test_missing_or_malformed_inspection_is_sanitized(self):
        for payload in ([], [self.volume, self.volume], None, {"private": "secret"}):
            with self.subTest(payload=payload):
                control = lambda *arguments: subprocess.CompletedProcess(arguments, 0, json.dumps(payload).encode(), b"")
                self.reject(control=control)
        def failed_control(*arguments):
            raise RuntimeError("private runtime socket secret")
        self.reject(control=failed_control)

    def test_missing_holder_is_rejected_without_mutation(self):
        def missing_holder(*arguments):
            if arguments[0] == "volume":
                return self.control(*arguments)
            return subprocess.CompletedProcess(arguments, 1, b"", b"private runtime details")
        self.reject(control=missing_holder)
        self.assertEqual(self.commands, [("volume", "inspect", "task-stage")])


if __name__ == "__main__":
    unittest.main()
