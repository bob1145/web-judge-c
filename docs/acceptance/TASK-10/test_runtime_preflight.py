import json
import ast
import copy
import hashlib
import io
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path
import runtime_preflight
from supervisor_probe import supervisor_container_command, validate_supervisor_container, invoke_supervisor_container
from runtime_preflight import restore_packages, validate_network, verify_imported_image


class NetworkParserTest(unittest.TestCase):
    def test_empty_route_table_and_loopback_only_are_isolated(self):
        validate_network("", "lo: 0 0 0 0\n", "")

    def test_header_only_route_table_is_isolated(self):
        validate_network("Iface Destination Gateway\n", "lo: 0 0 0 0\n", "00000000 lo\n")

    def test_ipv4_route_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "external network"):
            validate_network("Iface Destination Gateway\neth0 00000000 0100000A\n", "lo: 0\n", "")

    def test_additional_interface_is_rejected_without_routes(self):
        with self.assertRaisesRegex(RuntimeError, "external network"):
            validate_network("", "lo: 0\nenp0s1: 0\n", "")

    def test_external_ipv6_route_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "external network"):
            validate_network("", "lo: 0\n", "00000000 eth0\n")

    def test_missing_device_table_is_not_isolation_evidence(self):
        with self.assertRaisesRegex(RuntimeError, "external network"):
            validate_network("", "", "")

    def image_fixture(self):
        return {"Id": "sha256:" + "a" * 64, "RootFS": {"Type": "layers", "Layers": ["sha256:" + "b" * 64]},
                "Config": {"Cmd": ["/bin/sh"]}, "Architecture": "amd64", "Os": "linux"}

    def test_image_store_id_difference_requires_same_content(self):
        expected = self.image_fixture()
        imported = self.image_fixture()
        imported["Id"] = "sha256:" + "c" * 64
        self.assertEqual(verify_imported_image(expected, imported), imported["Id"])

    def test_changed_layer_is_rejected(self):
        imported = self.image_fixture()
        imported["RootFS"] = {"Type": "layers", "Layers": ["sha256:" + "d" * 64]}
        with self.assertRaisesRegex(RuntimeError, "RootFS"):
            verify_imported_image(self.image_fixture(), imported)

    def test_changed_config_is_rejected(self):
        imported = self.image_fixture()
        imported["Config"] = {"Cmd": ["/malicious"]}
        with self.assertRaisesRegex(RuntimeError, "Config"):
            verify_imported_image(self.image_fixture(), imported)

    def test_changed_platform_is_rejected(self):
        for field in ("Architecture", "Os"):
            with self.subTest(field=field):
                imported = self.image_fixture()
                imported[field] = "wrong-platform"
                with self.assertRaisesRegex(RuntimeError, field):
                    verify_imported_image(self.image_fixture(), imported)

    def test_invalid_imported_id_is_rejected(self):
        imported = self.image_fixture()
        imported["Id"] = "latest"
        with self.assertRaisesRegex(RuntimeError, "imported image ID"):
            verify_imported_image(self.image_fixture(), imported)


    def archive_fixture(self, root, members):
        payload = b"signed-package-fixture"
        manifest = [{"file": "tool-1.apk", "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}]
        archive_path = root / "packages.tar"
        with tarfile.open(archive_path, "w") as archive:
            for name, kind, content in members:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.size = len(content) if kind == tarfile.REGTYPE else 0
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    member.linkname = "../../outside"
                archive.addfile(member, io.BytesIO(content) if kind == tarfile.REGTYPE else None)
        return archive_path, manifest

    def test_locked_package_archive_round_trip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, manifest = self.archive_fixture(root, [("packages/tool-1.apk", tarfile.REGTYPE, b"signed-package-fixture")])
            restore_packages(archive, manifest, root / "restored")
            self.assertEqual((root / "restored/packages/tool-1.apk").read_bytes(), b"signed-package-fixture")
            self.assertEqual((root / "restored/packages/SHA256SUMS").read_text(),
                             manifest[0]["sha256"] + "  ./tool-1.apk\n")

    def assert_archive_rejected(self, members, message):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, manifest = self.archive_fixture(root, members)
            with self.assertRaisesRegex(RuntimeError, message):
                restore_packages(archive, manifest, root / "restored")
            self.assertFalse((root / "outside").exists())

    def test_archive_traversal_is_rejected(self):
        self.assert_archive_rejected([("packages/../../outside", tarfile.REGTYPE, b"bad")], "not in locked")

    def test_archive_symbolic_link_is_rejected(self):
        self.assert_archive_rejected([("packages/tool-1.apk", tarfile.SYMTYPE, b"")], "unsafe archive")

    def test_archive_hard_link_is_rejected(self):
        self.assert_archive_rejected([("packages/tool-1.apk", tarfile.LNKTYPE, b"")], "unsafe archive")

    def test_archive_duplicate_is_rejected(self):
        entry = ("packages/tool-1.apk", tarfile.REGTYPE, b"signed-package-fixture")
        self.assert_archive_rejected([entry, entry], "duplicate archive")

    def test_archive_content_hash_mismatch_is_rejected(self):
        self.assert_archive_rejected([("packages/tool-1.apk", tarfile.REGTYPE, b"signed-package-fixturX")], "hash changed")

    def test_archive_missing_package_is_rejected(self):
        self.assert_archive_rejected([], "missing packages")

    def test_existing_mode_round_trips_remain_compatible(self):
        with patch.dict(runtime_preflight.__dict__):
            for suffix in ("", "-build", "-replay", "-stage", "-supervisor"):
                with self.subTest(suffix=suffix):
                    self.assertEqual(runtime_preflight.configure_mode("outer" + suffix), "outer")
                    self.assertEqual(runtime_preflight.phase_mode("namespace"), "namespace" + suffix)
                    self.assertFalse(runtime_preflight.SUPERVISOR_IMAGE)
                    self.assertFalse(runtime_preflight.SUPERVISOR_CONTAINER)

    def test_supervisor_image_build_mode_is_distinct(self):
        with patch.dict(runtime_preflight.__dict__):
            self.assertEqual(runtime_preflight.configure_mode("outer-supervisor-image-build"), "outer")
            self.assertTrue(runtime_preflight.SUPERVISOR_IMAGE)
            self.assertTrue(runtime_preflight.BUILD)
            self.assertFalse(runtime_preflight.STAGE)
            self.assertFalse(runtime_preflight.SUPERVISOR)
            self.assertFalse(runtime_preflight.OFFLINE_BUILD)
            self.assertEqual(runtime_preflight.MEMORY_MAX, 1504 * 1024 ** 2)
            self.assertEqual(runtime_preflight.phase_mode("pivot"), "pivot-supervisor-image-build")

    def test_supervisor_image_replay_remains_offline_in_all_phases(self):
        with patch.dict(runtime_preflight.__dict__):
            for phase in ("outer", "namespace", "pivot", "daemon"):
                self.assertEqual(runtime_preflight.configure_mode(phase + "-supervisor-image-replay"), phase)
                self.assertTrue(runtime_preflight.OFFLINE_BUILD)
                self.assertTrue(runtime_preflight.REPLAY_BUILD)
                self.assertEqual(runtime_preflight.phase_mode(phase), phase + "-supervisor-image-replay")

    def test_supervisor_image_offline_restart_retains_identity(self):
        with patch.dict(runtime_preflight.__dict__):
            self.assertEqual(runtime_preflight.configure_mode("daemon-supervisor-image-build-offline"), "daemon")
            self.assertTrue(runtime_preflight.SUPERVISOR_IMAGE)
            self.assertTrue(runtime_preflight.OFFLINE_BUILD)
            self.assertTrue(runtime_preflight.BUILD)
            self.assertFalse(runtime_preflight.STAGE)

    def test_invalid_supervisor_image_mode_is_rejected(self):
        with patch.dict(runtime_preflight.__dict__):
            with self.assertRaisesRegex(RuntimeError, "unsupported supervisor image mode"):
                runtime_preflight.configure_mode("outer-supervisor-image-stage")

    def test_supervisor_fetch_is_limited_to_official_repositories_and_tools(self):
        with patch.dict(runtime_preflight.__dict__, SUPERVISOR_IMAGE=True):
            arguments = runtime_preflight.package_fetch_arguments(["docker", "--host", "unix:///dedicated"], "seed")
        self.assertEqual([value for value in arguments if value.startswith("https://")], [
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/main",
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/community"])
        self.assertEqual(arguments[-6:], ["fetch", "--recursive", "--output", "/packages", "python3", "docker-cli"])
        self.assertNotIn("g++", arguments)
        self.assertNotIn("docker-engine", arguments)
        self.assertNotIn("--allow-untrusted", arguments)

    def test_executor_fetch_contract_is_unchanged(self):
        with patch.dict(runtime_preflight.__dict__, SUPERVISOR_IMAGE=False):
            arguments = runtime_preflight.package_fetch_arguments(["docker"], "seed")
        self.assertEqual(arguments[-5:], ["fetch", "--recursive", "--output", "/packages", "g++"])
        self.assertEqual(arguments.count("--repository"), 1)

    def supervisor_sources_fixture(self, root):
        sources = root / "sources"
        sources.mkdir()
        for name in runtime_preflight.SUPERVISOR_SOURCES:
            (sources / name).write_bytes(b"pass\n")
        digest = hashlib.sha256(b"pass\n").hexdigest()
        (sources / "SHA256SUMS").write_text("".join(digest + "  ./" + name + "\n"
                                                   for name in sorted(runtime_preflight.SUPERVISOR_SOURCES)))
        return sources

    def test_supervisor_source_snapshot_round_trip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.supervisor_sources_fixture(root)
            runtime_preflight.copy_supervisor_sources(source, root / "copied")
            self.assertEqual({path.name for path in (root / "copied").iterdir()},
                             set(runtime_preflight.SUPERVISOR_SOURCES) | {"SHA256SUMS"})

    def test_supervisor_source_content_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.supervisor_sources_fixture(root)
            (source / runtime_preflight.SUPERVISOR_SOURCES[0]).write_bytes(b"changed\n")
            with self.assertRaisesRegex(RuntimeError, "hash changed"):
                runtime_preflight.copy_supervisor_sources(source, root / "copied")
            self.assertFalse((root / "copied").exists())

    def test_supervisor_unlocked_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.supervisor_sources_fixture(root)
            (source / "extra.py").write_bytes(b"pass\n")
            with self.assertRaisesRegex(RuntimeError, "file set differs"):
                runtime_preflight.copy_supervisor_sources(source, root / "copied")

    def test_supervisor_source_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.supervisor_sources_fixture(root)
            original = source / runtime_preflight.SUPERVISOR_SOURCES[0]
            original.rename(root / "outside.py")
            original.symlink_to(root / "outside.py")
            with self.assertRaisesRegex(RuntimeError, "not regular"):
                runtime_preflight.copy_supervisor_sources(source, root / "copied")

    def supervisor_smoke_fixture(self, rejected_exit=30, source_hash="a" * 64):
        image = {"Id": "sha256:" + "b" * 64, "Config": {
            "Entrypoint": ["python3", "-B", "/opt/web-judge/linux_supervisor.py"], "User": "root"}}
        capabilities = {"testContract": 1}
        smoke = {"sourceSha256": {name: source_hash for name in runtime_preflight.SUPERVISOR_SOURCES},
                 "capabilities": capabilities, "python": "test-only", "dockerCli": "test-only"}
        rejected = SimpleNamespace(returncode=rejected_exit, stdout="",
                                   stderr="supervisor configuration or execution rejected\n")
        return image, smoke, capabilities, rejected

    def test_supervisor_smoke_checks_shipped_sources_and_fail_closed_entrypoint(self):
        image, smoke, capabilities, rejected = self.supervisor_smoke_fixture()
        with patch.object(runtime_preflight, "command", side_effect=[json.dumps(smoke), json.dumps(capabilities)]) as commands, \
                patch.object(runtime_preflight, "file_sha256", return_value="a" * 64), \
                patch.object(runtime_preflight.subprocess, "run", return_value=rejected) as process, \
                patch.object(runtime_preflight, "record") as events:
            runtime_preflight.supervisor_image_smoke(["docker", "--host", "unix:///dedicated"], image)
        ast.parse(commands.call_args_list[0].args[0][-1])
        self.assertIn("--read-only", commands.call_args_list[0].args[0])
        self.assertEqual(commands.call_args_list[1].args[0][-1], "--capabilities")
        self.assertEqual(process.call_args.args[0][-1], image["Id"])
        self.assertEqual(events.call_args.kwargs["rejectedExitCode"], 30)

    def test_supervisor_smoke_rejects_changed_image_source(self):
        image, smoke, capabilities, rejected = self.supervisor_smoke_fixture(source_hash="c" * 64)
        with patch.object(runtime_preflight, "command", return_value=json.dumps(smoke)), \
                patch.object(runtime_preflight, "file_sha256", return_value="a" * 64):
            with self.assertRaisesRegex(RuntimeError, "source identity changed"):
                runtime_preflight.supervisor_image_smoke(["docker"], image)

    def test_supervisor_smoke_rejects_successful_default_invocation(self):
        image, smoke, capabilities, rejected = self.supervisor_smoke_fixture(rejected_exit=0)
        with patch.object(runtime_preflight, "command", side_effect=[json.dumps(smoke), json.dumps(capabilities)]), \
                patch.object(runtime_preflight, "file_sha256", return_value="a" * 64), \
                patch.object(runtime_preflight.subprocess, "run", return_value=rejected):
            with self.assertRaisesRegex(RuntimeError, "did not fail closed"):
                runtime_preflight.supervisor_image_smoke(["docker"], image)

    def test_supervisor_container_mode_stays_distinct_and_offline(self):
        with patch.dict(runtime_preflight.__dict__):
            for phase in ("outer", "namespace", "pivot", "daemon"):
                self.assertEqual(runtime_preflight.configure_mode(phase + "-supervisor-container"), phase)
                self.assertTrue(runtime_preflight.SUPERVISOR_CONTAINER)
                self.assertTrue(runtime_preflight.SUPERVISOR)
                self.assertTrue(runtime_preflight.STAGE)
                self.assertFalse(runtime_preflight.BUILD)
                self.assertFalse(runtime_preflight.SUPERVISOR_IMAGE)
                self.assertEqual(runtime_preflight.MEMORY_MAX, 4 * 1024 ** 3)
                self.assertEqual(runtime_preflight.stage_tmpfs_limit_bytes(), 1792 * 1024 ** 2)
                self.assertEqual(runtime_preflight.stage_tmpfs_limit_bytes() + (3 * 64 + 32) * 1024 ** 2, 2016 * 1024 ** 2)
                self.assertLessEqual(2016 * 1024 ** 2, runtime_preflight.STORAGE_MAX)
                self.assertEqual(runtime_preflight.phase_mode(phase), phase + "-supervisor-container")

    def test_java_mode_round_trips_without_expanding_container_budget(self):
        with patch.dict(runtime_preflight.__dict__):
            for phase in ("outer", "namespace", "pivot", "daemon"):
                self.assertEqual(runtime_preflight.configure_mode(phase + "-java-supervisor"), phase)
                self.assertTrue(runtime_preflight.JAVA_SUPERVISOR)
                self.assertTrue(runtime_preflight.SUPERVISOR_CONTAINER)
                self.assertTrue(runtime_preflight.STAGE)
                self.assertFalse(runtime_preflight.BUILD)
                self.assertFalse(runtime_preflight.RECOVERY)
                self.assertEqual(runtime_preflight.MEMORY_MAX, 4 * 1024 ** 3)
                self.assertEqual(runtime_preflight.stage_tmpfs_limit_bytes(), 1792 * 1024 ** 2)
                self.assertEqual(runtime_preflight.phase_mode(phase), phase + "-java-supervisor")

    def test_java_mode_rejects_combined_and_unknown_phases(self):
        with patch.dict(runtime_preflight.__dict__):
            for mode in ("unknown-java-supervisor", "outer-build-java-supervisor", "outer-recovery-java-supervisor"):
                with self.subTest(mode=mode), self.assertRaisesRegex(RuntimeError, "unsupported Java supervisor mode"):
                    runtime_preflight.configure_mode(mode)

    def test_mode_change_resets_java_identity(self):
        with patch.dict(runtime_preflight.__dict__):
            runtime_preflight.configure_mode("outer-java-supervisor")
            runtime_preflight.configure_mode("outer-stage")
            self.assertFalse(runtime_preflight.JAVA_SUPERVISOR)
            self.assertFalse(runtime_preflight.SUPERVISOR_CONTAINER)
            self.assertEqual(runtime_preflight.MEMORY_MAX, 2 * 1024 ** 3)
            self.assertEqual(runtime_preflight.phase_mode("daemon"), "daemon-stage")

    def java_bundle_fixture(self, temporary):
        bundle = Path(temporary).resolve() / "bundle"
        bundle.mkdir()
        assets = {"classes/com/example/demo/acceptance/JavaSupervisorProbe.class": b"probe",
                  "lib/locked-1.0.jar": b"dependency"}
        for name, content in assets.items():
            target = bundle / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        manifest = {"version": 1, "mainClass": runtime_preflight.JAVA_MAIN_CLASS,
                    "probeSourceSha256": "a" * 64, "sourceSnapshotSha256": "b" * 64,
                    "files": {name: {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                              for name, content in assets.items()}, "totalBytes": sum(map(len, assets.values()))}
        return bundle, manifest

    def java_bundle_lock(self, bundle, manifest):
        target = bundle / "bundle-manifest.json"
        target.write_text(json.dumps(manifest))
        return runtime_preflight.file_sha256(target)

    def test_java_bundle_accepts_exact_runtime_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            digest = self.java_bundle_lock(bundle, manifest)
            self.assertEqual(runtime_preflight.validate_java_bundle(bundle, digest), manifest)

    def test_java_bundle_requires_external_manifest_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            self.java_bundle_lock(bundle, manifest)
            with self.assertRaisesRegex(RuntimeError, "manifest hash changed"):
                runtime_preflight.validate_java_bundle(bundle, "0" * 64)

    def test_java_bundle_rejects_changed_class_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            digest = self.java_bundle_lock(bundle, manifest)
            (bundle / "classes/com/example/demo/acceptance/JavaSupervisorProbe.class").write_bytes(b"other")
            with self.assertRaisesRegex(RuntimeError, "file hash differs"):
                runtime_preflight.validate_java_bundle(bundle, digest)

    def test_java_bundle_rejects_extra_unlisted_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            digest = self.java_bundle_lock(bundle, manifest)
            (bundle / "lib/extra.jar").write_bytes(b"extra")
            with self.assertRaisesRegex(RuntimeError, "inventory changed"):
                runtime_preflight.validate_java_bundle(bundle, digest)

    def test_java_bundle_rejects_source_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            (bundle / "classes/Source.java").write_bytes(b"source")
            manifest["files"]["classes/Source.java"] = {"bytes": 6, "sha256": hashlib.sha256(b"source").hexdigest()}
            manifest["totalBytes"] += 6
            with self.assertRaisesRegex(RuntimeError, "non-runtime asset"):
                runtime_preflight.validate_java_bundle(bundle, self.java_bundle_lock(bundle, manifest))

    def test_java_bundle_rejects_oversized_manifest_file_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            manifest["files"]["lib/locked-1.0.jar"]["bytes"] = 65 * 1024 ** 2
            with self.assertRaisesRegex(RuntimeError, "size changed"):
                runtime_preflight.validate_java_bundle(bundle, self.java_bundle_lock(bundle, manifest))

    def test_java_bundle_rejects_directory_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, manifest = self.java_bundle_fixture(temporary)
            digest = self.java_bundle_lock(bundle, manifest)
            (bundle / "linked").symlink_to(bundle / "lib", target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "unsafe entries"):
                runtime_preflight.validate_java_bundle(bundle, digest)

    def test_java_bundle_location_requires_dedicated_build_round(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary).resolve()
            with patch.object(runtime_preflight, "REPOSITORY", repository):
                runtime_preflight.java_bundle_location(repository / "target/task10-runtime/JVM-BUILD-R01/bundle")
                for suffix in ("BUILD-R01/bundle", "JVM-BUILD-R01/other", "JVM-BUILD-R001/bundle"):
                    with self.subTest(suffix=suffix), self.assertRaisesRegex(RuntimeError, "explicit JVM-BUILD artifact"):
                        runtime_preflight.java_bundle_location(repository / "target/task10-runtime" / suffix)

    def test_java_command_uses_locked_jdk_and_bounded_private_runtime(self):
        command = runtime_preflight.java_probe_arguments()
        self.assertEqual(command[0], "/usr/lib/jvm/java-21-openjdk-amd64/bin/java")
        for argument in ("-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m",
                         "-XX:MaxDirectMemorySize=32m", "-Duser.home=/nonexistent", "-Djna.tmpdir=/lab/java-tmp"):
            self.assertIn(argument, command)
        self.assertEqual(command[command.index("-cp") + 1], "/lab/java-bundle/classes:/lab/java-bundle/lib/*")
        self.assertEqual(command[-5:], [runtime_preflight.JAVA_MAIN_CLASS, "/lab/java-storage/judge-task10-java-supervisor", "/lab/evidence/java",
                                        runtime_preflight.SUPERVISOR_CONTAINER_IMAGE_ID, runtime_preflight.STAGE_IMAGE_ID])

    def java_inspect_fixture(self):
        attempt = "11111111-1111-4111-8111-111111111111"
        volume_name = "wjc-stage-volume-" + attempt
        mountpoint = "/var/lib/wjc/docker/volumes/" + volume_name + "/_data"
        journal = {"attemptId": attempt, "supervisor": {"runtimeId": "a" * 64, "name": "wjc-supervisor-" + attempt},
                   "volume": {"name": volume_name, "mountpoint": mountpoint}}
        control = "/work/control/attempts/" + attempt
        mounts = [{"Type": "volume", "Name": volume_name, "Source": mountpoint, "Destination": mountpoint, "RW": True},
                  {"Type": "bind", "Source": "/lab/java-storage/judge-task10-java-supervisor", "Destination": "/work", "RW": True, "Propagation": "rprivate"},
                  {"Type": "bind", "Source": "/run/wjc/docker.sock", "Destination": "/run/wjc/docker.sock", "RW": True, "Propagation": "rprivate"}]
        declarations = [{"Type": "volume", "Source": volume_name, "Target": mountpoint, "VolumeOptions": {"NoCopy": True}},
                        {"Type": "bind", "Source": "/lab/java-storage/judge-task10-java-supervisor", "Target": "/work"},
                        {"Type": "bind", "Source": "/run/wjc/docker.sock", "Target": "/run/wjc/docker.sock"}]
        inspected = {"Id": "a" * 64, "Name": "/wjc-supervisor-" + attempt, "Image": runtime_preflight.SUPERVISOR_CONTAINER_IMAGE_ID,
                     "Config": {"User": "0:0", "Labels": {"io.webjudge.judge": "task10-java-supervisor", "io.webjudge.attempt": attempt,
                                                         "io.webjudge.managed": "supervisor"},
                                "Entrypoint": ["python3", "-B", "/opt/web-judge/linux_supervisor.py"],
                                "Cmd": [control + "/task-spec.json", "--work-root", "/work", "--stage-config", control + "/supervisor-config.json", "--config-owner-uid", "0"]},
                     "HostConfig": {"ReadonlyRootfs": True, "Privileged": False, "NetworkMode": "none", "PidMode": "",
                                    "IpcMode": "private", "CgroupnsMode": "private", "UTSMode": "", "UsernsMode": "",
                                    "OomKillDisable": False, "CapDrop": ["ALL"], "CapAdd": [],
                                    "SecurityOpt": ["no-new-privileges"], "LogConfig": {"Type": "none"},
                                    "Memory": 256 * 1024 ** 2, "MemorySwap": 256 * 1024 ** 2, "PidsLimit": 64,
                                    "NanoCpus": 500000000, "Mounts": declarations}, "Mounts": mounts,
                     "State": {"Error": "", "OOMKilled": False, "Dead": False, "Restarting": False, "Paused": False}}
        return inspected, journal

    def test_java_independent_inspect_accepts_exact_three_mount_contract(self):
        inspected, journal = self.java_inspect_fixture()
        runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")

    def test_java_independent_inspect_rejects_mount_changes(self):
        mutations = (("Source", "/host"), ("RW", False), ("Type", "bind"), ("Name", "other-volume"))
        for field, value in mutations:
            inspected, journal = self.java_inspect_fixture()
            inspected["Mounts"][0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")
        inspected, journal = self.java_inspect_fixture()
        inspected["Mounts"].append(copy.deepcopy(inspected["Mounts"][0]))
        with self.assertRaisesRegex(RuntimeError, "mount count differs"):
            runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")

    def test_java_independent_inspect_rejects_privilege_and_budget_changes(self):
        for field, value in (("Privileged", True), ("CapAdd", ["SYS_ADMIN"]), ("NetworkMode", "host"),
                             ("Memory", 4 * 1024 ** 3), ("PidsLimit", 512), ("SecurityOpt", ["seccomp=unconfined"]),
                             ("UTSMode", None), ("UsernsMode", False), ("OomKillDisable", 0)):
            inspected, journal = self.java_inspect_fixture()
            inspected["HostConfig"][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")
        for section, field in (("HostConfig", "UTSMode"), ("HostConfig", "UsernsMode"),
                               ("HostConfig", "OomKillDisable"), ("State", "Error"), ("State", "OOMKilled")):
            inspected, journal = self.java_inspect_fixture()
            del inspected[section][field]
            with self.subTest(missing=field), self.assertRaises(RuntimeError):
                runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")
        for value in (None, False, 0, "daemon error"):
            inspected, journal = self.java_inspect_fixture()
            inspected["State"]["Error"] = value
            with self.subTest(error=value), self.assertRaisesRegex(RuntimeError, "health state differs"):
                runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")

    def test_java_independent_inspect_rejects_changed_identity(self):
        inspected, journal = self.java_inspect_fixture()
        inspected["Id"] = "b" * 64
        with self.assertRaisesRegex(RuntimeError, "identity differs"):
            runtime_preflight.validate_java_runtime_container(inspected, journal, "supervisor")

    def test_recovery_mode_is_independent_and_retains_original_stage_budget(self):
        with patch.dict(runtime_preflight.__dict__):
            for phase in ("outer", "namespace", "pivot", "daemon"):
                self.assertEqual(runtime_preflight.configure_mode(phase + "-recovery"), phase)
                self.assertTrue(runtime_preflight.RECOVERY)
                self.assertTrue(runtime_preflight.STAGE)
                self.assertFalse(runtime_preflight.BUILD)
                self.assertFalse(runtime_preflight.SUPERVISOR)
                self.assertFalse(runtime_preflight.SUPERVISOR_CONTAINER)
                self.assertFalse(runtime_preflight.SUPERVISOR_IMAGE)
                self.assertEqual(runtime_preflight.MEMORY_MAX, 2 * 1024 ** 3)
                self.assertEqual(runtime_preflight.stage_tmpfs_limit_bytes(), 1408 * 1024 ** 2)
                self.assertEqual(runtime_preflight.phase_mode(phase), phase + "-recovery")

    def test_recovery_rejects_combined_or_unknown_phase_modes(self):
        with patch.dict(runtime_preflight.__dict__):
            for mode in ("outer-supervisor-recovery", "outer-stage-recovery", "outer-build-recovery", "unknown-recovery"):
                with self.subTest(mode=mode), self.assertRaisesRegex(RuntimeError, "unsupported recovery mode"):
                    runtime_preflight.configure_mode(mode)

    def test_mode_changes_reset_recovery_and_container_only_budgets(self):
        with patch.dict(runtime_preflight.__dict__):
            runtime_preflight.configure_mode("outer-supervisor-container")
            runtime_preflight.configure_mode("outer-recovery")
            runtime_preflight.configure_mode("outer-stage")
            self.assertFalse(runtime_preflight.RECOVERY)
            self.assertFalse(runtime_preflight.SUPERVISOR_CONTAINER)
            self.assertEqual(runtime_preflight.MEMORY_MAX, 2 * 1024 ** 3)
            self.assertEqual(runtime_preflight.stage_tmpfs_limit_bytes(), 1408 * 1024 ** 2)
            self.assertEqual(runtime_preflight.phase_mode("daemon"), "daemon-stage")

    def test_image_load_resources_use_real_cgroup_fields_and_disk_values(self):
        with patch.object(runtime_preflight.shutil, "disk_usage", return_value=SimpleNamespace(total=100, used=61, free=39)), \
                patch.object(runtime_preflight.Path, "read_text", side_effect=["13\n", "21\n"]), \
                patch.object(runtime_preflight, "record") as recorded:
            runtime_preflight.record_image_load_resources("supervisor")
        recorded.assert_called_once_with("stage-image-load-resources", imageRole="supervisor",
                                         temporaryDiskTotalBytes=100, temporaryDiskUsedBytes=61, temporaryDiskFreeBytes=39,
                                         outerMemoryCurrentBytes=13, outerMemoryPeakBytes=21)

    def supervisor_container_fixture(self):
        attempt = "11111111-1111-4111-8111-111111111111"
        root = Path("/lab/stage-control/supervisor-task")
        volume = "wjc-task10-stage-" + attempt
        config = SimpleNamespace(runtime_command="/usr/bin/docker", runtime_endpoint="unix:///run/wjc/docker.sock",
                                 stage_volume=volume, stage_root=Path("/var/lib/wjc/docker/volumes") / volume / "_data")
        client = ["docker", "--host", "unix:///run/wjc/docker.sock"]
        arguments = [str(root / "control/attempts" / attempt / "task-spec.json"), "--work-root", str(root),
                     "--stage-config", str(root / "control/attempts" / attempt / "supervisor-config.json"),
                     "--config-owner-uid", "0"]
        image_id = "sha256:" + "a" * 64
        return client, image_id, root, config, attempt, "valid", arguments

    def supervisor_inspection_fixture(self):
        fixture = self.supervisor_container_fixture()
        client, image_id, root, config, attempt, case, arguments = fixture
        command, name, labels = supervisor_container_command(*fixture)
        identity = "b" * 64
        inspection = {"Id": identity, "Name": "/" + name, "Image": image_id,
                      "State": {"Status": "created", "Running": False, "OOMKilled": False, "ExitCode": 0},
                      "Config": {"Labels": labels, "User": "0:0", "Cmd": arguments,
                                 "Entrypoint": ["python3", "-B", "/opt/web-judge/linux_supervisor.py"]},
                      "HostConfig": {"Privileged": False, "ReadonlyRootfs": True, "NetworkMode": "none", "PidMode": "",
                                     "IpcMode": "private", "CgroupnsMode": "private", "Memory": 256 * 1024 ** 2,
                                     "MemorySwap": 256 * 1024 ** 2, "PidsLimit": 32, "NanoCpus": 500000000,
                                     "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"], "LogConfig": {"Type": "none"}},
                      "Mounts": [{"Type": "bind", "Source": str(root), "Destination": str(root), "RW": True},
                                 {"Type": "volume", "Name": config.stage_volume, "Source": str(config.stage_root),
                                  "Destination": str(config.stage_root), "RW": True},
                                 {"Type": "bind", "Source": "/run/wjc/docker.sock", "Destination": "/run/wjc/docker.sock", "RW": True}]}
        return fixture, command, name, labels, identity, inspection

    def test_supervisor_container_command_has_exact_three_mounts_and_limits(self):
        fixture = self.supervisor_container_fixture()
        command, name, labels = supervisor_container_command(*fixture)
        mounts = [command[index + 1] for index, value in enumerate(command) if value == "--mount"]
        self.assertEqual(mounts, [
            "type=bind,source=" + str(fixture[2]) + ",target=" + str(fixture[2]),
            "type=volume,source=" + fixture[3].stage_volume + ",target=" + str(fixture[3].stage_root) + ",volume-nocopy",
            "type=bind,source=/run/wjc/docker.sock,target=/run/wjc/docker.sock"])
        for flag, value in {"--network": "none", "--user": "0:0", "--cap-drop": "ALL",
                            "--security-opt": "no-new-privileges", "--memory": "256m", "--memory-swap": "256m",
                            "--pids-limit": "32", "--cpus": "0.5", "--log-driver": "none", "--ipc": "private"}.items():
            self.assertEqual(command[command.index(flag) + 1], value)
        self.assertIn("--read-only", command)
        self.assertNotIn("--privileged", command)
        self.assertNotIn("--pid", command)
        self.assertNotIn("--entrypoint", command)
        self.assertNotIn("--rm", command)
        self.assertEqual(labels["io.webjudge.managed"], "supervisor-probe")
        self.assertEqual(command[-8:], [fixture[1], *fixture[-1]])

    def test_supervisor_container_rejects_broader_task_root(self):
        fixture = list(self.supervisor_container_fixture())
        fixture[2] = Path("/lab")
        with self.assertRaisesRegex(RuntimeError, "new experiment root"):
            supervisor_container_command(*fixture)

    def test_supervisor_container_rejects_daemon_root_mount(self):
        fixture = self.supervisor_container_fixture()
        fixture[3].stage_root = Path("/var/lib/wjc/docker")
        with self.assertRaisesRegex(RuntimeError, "volume mountpoint"):
            supervisor_container_command(*fixture)

    def test_supervisor_container_rejects_shared_socket(self):
        fixture = self.supervisor_container_fixture()
        fixture[3].runtime_endpoint = "unix:///var/run/docker.sock"
        with self.assertRaisesRegex(RuntimeError, "socket differs"):
            supervisor_container_command(*fixture)

    def test_supervisor_container_rejects_mutable_image(self):
        fixture = list(self.supervisor_container_fixture())
        fixture[1] = "supervisor:latest"
        with self.assertRaisesRegex(RuntimeError, "immutable"):
            supervisor_container_command(*fixture)

    def test_supervisor_container_rejects_shell_argument_injection(self):
        fixture = self.supervisor_container_fixture()
        fixture[-1].extend(["/bin/sh", "-c", "true"])
        with self.assertRaisesRegex(RuntimeError, "experiment arguments"):
            supervisor_container_command(*fixture)

    def test_supervisor_container_inspection_matches_contract(self):
        fixture, command, name, labels, identity, inspection = self.supervisor_inspection_fixture()
        validate_supervisor_container(inspection, identity, name, labels, fixture[1], fixture[2], fixture[3], fixture[-1])

    def test_supervisor_container_inspection_rejects_extra_mount(self):
        fixture, command, name, labels, identity, inspection = self.supervisor_inspection_fixture()
        inspection["Mounts"].append({"Type": "bind", "Source": "/", "Destination": "/host", "RW": True})
        with self.assertRaisesRegex(RuntimeError, "unexpected mounts"):
            validate_supervisor_container(inspection, identity, name, labels, fixture[1], fixture[2], fixture[3], fixture[-1])

    def test_supervisor_container_inspection_rejects_unsafe_runtime(self):
        fixture, command, name, labels, identity, inspection = self.supervisor_inspection_fixture()
        for field, value in {"Privileged": True, "PidMode": "host", "NetworkMode": "host", "CapAdd": ["SYS_ADMIN"],
                             "ReadonlyRootfs": False, "Memory": 0, "LogConfig": {"Type": "json-file"}}.items():
            with self.subTest(field=field):
                changed = copy.deepcopy(inspection)
                changed["HostConfig"][field] = value
                with self.assertRaises(RuntimeError):
                    validate_supervisor_container(changed, identity, name, labels, fixture[1], fixture[2], fixture[3], fixture[-1])

    def test_supervisor_container_launch_retains_inspects_then_removes_full_id(self):
        fixture, create, name, labels, identity, inspection = self.supervisor_inspection_fixture()
        state = {"removed": False, "started": False}
        calls = []

        def invoke(arguments, **kwargs):
            calls.append(arguments)
            operation = arguments[len(fixture[0]):]
            stdout = b""
            stderr = b""
            returncode = 0
            if operation[:2] == ["container", "create"]:
                stdout = identity.encode() + b"\n"
            elif operation[:2] == ["container", "inspect"]:
                if state["removed"]:
                    returncode = 1
                    stderr = ("Error: No such object: " + identity).encode()
                else:
                    current = copy.deepcopy(inspection)
                    if state["started"]:
                        current["State"]["Status"] = "exited"
                    stdout = json.dumps([current]).encode()
            elif operation == ["container", "start", "--attach", identity]:
                state["started"] = True
                stdout = b"trusted-output\n"
            elif operation == ["container", "rm", "--force", identity]:
                state["removed"] = True
            else:
                self.fail("unexpected container operation: " + str(operation))
            return SimpleNamespace(returncode=returncode, stdout=stdout, stderr=stderr)

        with tempfile.TemporaryDirectory() as temporary:
            result = invoke_supervisor_container(*fixture, 30, Path(temporary), invoke, lambda *args, **kwargs: None)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b"trusted-output\n")
            self.assertTrue((Path(temporary) / "valid-before.json").is_file())
            self.assertTrue((Path(temporary) / "valid-after.json").is_file())
            self.assertTrue((Path(temporary) / "valid-cleanup.json").is_file())
        self.assertTrue(state["removed"])
        self.assertEqual(calls[-2][-4:], ["container", "rm", "--force", identity])

    def java_discovery_fixture(self):
        attempt = "12345678-1234-1234-1234-123456789abc"
        return attempt, {"freshJvm": True, "processId": 101, "handleProvided": False,
                         "runnerStartedInThisProcess": False, "productRecoveryComplete": False,
                         "maximumReportJsonBytes": 51712,
                         "budget": {"maxDirectoryEntries": 128, "maxReportEntries": 8,
                                    "maxJournalReadBytes": 262148, "maxPathUtf8Bytes": 1024},
                         "report": {"scanComplete": True, "stopReason": "SCAN_FINISHED",
                                    "directoryEntriesVisited": 12, "journalReadBytesReserved": 131074,
                                    "findings": [{"relativePath": "judge-task10-java-supervisor/control/attempts/" + attempt,
                                                  "reason": "RECOVERY_REQUIRED", "phase": "SUPERVISOR_STOPPED",
                                                  "stageEvidenceRequiresFreeze": True}]}}

    def test_java_fresh_process_discovery_observation_preserves_pending_boundary(self):
        attempt, observation = self.java_discovery_fixture()
        self.assertIsNone(runtime_preflight.validate_java_discovery_observation(observation, attempt, 100))

    def test_java_fresh_process_discovery_rejects_false_identity_budget_or_recovery_claims(self):
        mutations = [("freshJvm", False), ("processId", 100), ("processId", True), ("handleProvided", True),
                     ("runnerStartedInThisProcess", True), ("productRecoveryComplete", True),
                     ("budget.maxDirectoryEntries", 129), ("maximumReportJsonBytes", 51713),
                     ("report.scanComplete", False), ("report.stopReason", "SCAN_INCOMPLETE"),
                     ("report.directoryEntriesVisited", 129), ("report.directoryEntriesVisited", True),
                     ("report.journalReadBytesReserved", 131073), ("report.findings", [])]
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                attempt, observation = self.java_discovery_fixture()
                target = observation
                parts = field.split(".")
                for part in parts[:-1]:
                    target = target[part]
                target[parts[-1]] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_discovery_observation(observation, attempt, 100)
        for field, value in [("relativePath", "foreign/attempt"), ("reason", "RECOVERED"),
                             ("phase", "SUPERVISOR_STARTED"), ("stageEvidenceRequiresFreeze", False),
                             ("stageEvidenceRequiresFreeze", 1)]:
            with self.subTest(field=field, value=value):
                attempt, observation = self.java_discovery_fixture()
                observation["report"]["findings"][0][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_discovery_observation(observation, attempt, 100)

    def java_stage_admission_fixture(self):
        attempt = "12345678-1234-1234-1234-123456789abc"
        closed = {"admissionVersion": 1, "attemptId": attempt, "state": "CLOSED"}
        payload = json.dumps(closed, separators=(",", ":")).encode("utf-8")
        marker = {"device": 1, "inode": 17, "uid": 0, "mode": 0o600, "links": 1,
                  "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
        journals = {"00000000-0000-4000-8000-" + str(index).zfill(12) + ".json": copy.deepcopy(marker)
                    for index in range(6)}
        before = {"stageJournals": journals, "journalEntries": sorted([*journals, "runtime-client"]),
                  "runtimeState": copy.deepcopy(marker), "supervisorConfig": copy.deepcopy(marker),
                  "manifest": copy.deepcopy(marker)}
        child = {"attemptId": attempt, "processId": 20, "freshPythonProcess": True,
                 "admissionClosed": True, "productRecoveryComplete": False,
                 "runtimeCalls": [], "processCalls": [], "stageRootEmpty": True,
                 "rejections": [{"role": role, "phase": phase,
                                  "error": "stage admission is closed; trusted recovery required"}
                                 for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE")
                                 for phase in ("compile", "execute")]}
        observation = {"attemptId": attempt, "admissionClosed": True, "productRecoveryComplete": False,
                       "unknownCreateBarrierEstablished": False, "javaProductRecoveryInvoked": False,
                       "closeProcessId": 1, "closeResults": [copy.deepcopy(closed), copy.deepcopy(closed)],
                       "markerAfterFirst": copy.deepcopy(marker), "markerAfterSecond": copy.deepcopy(marker),
                       "markerAfterChild": copy.deepcopy(marker), "rejectionObservation": child}
        return observation, attempt, 20, before, copy.deepcopy(before)

    def test_java_stage_admission_validator_accepts_retained_closed_not_recovered(self):
        self.assertIsNone(runtime_preflight.validate_java_stage_admission_observation(*self.java_stage_admission_fixture()))

    def test_java_stage_admission_validator_rejects_false_recovery_or_identity_claims(self):
        for field, value in (("attemptId", "foreign"), ("admissionClosed", 1), ("admissionClosed", False),
                             ("productRecoveryComplete", True), ("unknownCreateBarrierEstablished", True),
                             ("javaProductRecoveryInvoked", True), ("closeProcessId", True), ("closeProcessId", 20)):
            with self.subTest(field=field, value=value):
                arguments = self.java_stage_admission_fixture()
                arguments[0][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_requires_exact_two_close_results(self):
        for result in (None, [], [{"state": "CLOSED"}], [{"state": "CLOSED"}] * 2):
            with self.subTest(result=result):
                arguments = self.java_stage_admission_fixture()
                arguments[0]["closeResults"] = result
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)
        for field, value in (("admissionVersion", True), ("admissionVersion", 1.0), ("attemptId", "foreign"),
                             ("state", "RECOVERED"), ("extra", True)):
            with self.subTest(field=field, value=value):
                arguments = self.java_stage_admission_fixture()
                arguments[0]["closeResults"][1][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_requires_stable_private_marker(self):
        for field, value in (("device", -1), ("inode", True), ("uid", 1), ("mode", 0o644),
                             ("links", 2), ("bytes", 0), ("bytes", 65537), ("sha256", "short")):
            with self.subTest(field=field, value=value):
                arguments = self.java_stage_admission_fixture()
                for name in ("markerAfterFirst", "markerAfterSecond", "markerAfterChild"):
                    arguments[0][name][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)
        for name in ("markerAfterSecond", "markerAfterChild"):
            with self.subTest(name=name):
                arguments = self.java_stage_admission_fixture()
                arguments[0][name]["inode"] += 1
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_binds_stable_marker_to_exact_closed_bytes(self):
        for state in ("BROKEN", "OPEN", None):
            with self.subTest(state=state):
                arguments = self.java_stage_admission_fixture()
                wrong_payload = json.dumps({"admissionVersion": 1, "attemptId": arguments[1], "state": state},
                                           separators=(",", ":")).encode("utf-8")
                for name in ("markerAfterFirst", "markerAfterSecond", "markerAfterChild"):
                    arguments[0][name]["bytes"] = len(wrong_payload)
                    arguments[0][name]["sha256"] = hashlib.sha256(wrong_payload).hexdigest()
                self.assertEqual(arguments[0]["markerAfterFirst"], arguments[0]["markerAfterSecond"])
                self.assertEqual(arguments[0]["markerAfterSecond"], arguments[0]["markerAfterChild"])
                self.assertEqual([result["state"] for result in arguments[0]["closeResults"]], ["CLOSED", "CLOSED"])
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_rejects_runtime_calls_and_incomplete_role_denial(self):
        for field, value in (("attemptId", "foreign"), ("processId", 1), ("processId", True),
                             ("freshPythonProcess", False), ("admissionClosed", False),
                             ("productRecoveryComplete", True), ("runtimeCalls", [{"arguments": ["inspect"]}]),
                             ("processCalls", [{"command": ["program"]}]), ("stageRootEmpty", False), ("rejections", [])):
            with self.subTest(field=field, value=value):
                arguments = self.java_stage_admission_fixture()
                arguments[0]["rejectionObservation"][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)
        for field, value in (("role", "USER"), ("phase", "execute"), ("error", "stage journal requires trusted recovery")):
            with self.subTest(field=field, value=value):
                arguments = self.java_stage_admission_fixture()
                arguments[0]["rejectionObservation"]["rejections"][0][field] = value
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_rejects_changed_control_or_extra_journals(self):
        for field in ("runtimeState", "supervisorConfig", "manifest"):
            with self.subTest(field=field):
                arguments = self.java_stage_admission_fixture()
                arguments[4][field]["sha256"] = "b" * 64
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)
        for mutate in (lambda value: value["stageJournals"].pop(next(iter(value["stageJournals"]))),
                       lambda value: value["journalEntries"].append("stage-admission.pending")):
            arguments = self.java_stage_admission_fixture()
            mutate(arguments[3])
            mutate(arguments[4])
            with self.assertRaises(RuntimeError):
                runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_stage_admission_validator_rejects_non_fresh_child_process_identity(self):
        for process_id in (True, 0, 1, 21):
            with self.subTest(process_id=process_id):
                arguments = list(self.java_stage_admission_fixture())
                arguments[2] = process_id
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_stage_admission_observation(*arguments)

    def test_java_container_recovery_observation_requires_actual_fresh_process_and_bounded_result(self):
        observation = {"processId": 21, "freshJvm": True, "handleProvided": False,
                       "runnerStartedInThisProcess": False, "realRuntimeCommands": True,
                       "judgeId": "judge", "attemptId": "attempt", "retainedVolumeName": "volume",
                       "confirmedStages": 6, "productRecoveryComplete": False,
                       "volumeContinuityProven": False, "deletionCredentialIssued": False, "productionAcceptance": False}
        runtime_preflight.validate_java_recovery_observation(observation, "judge", "attempt", 21, "volume")
        for field, value in (("processId", True), ("processId", 22), ("handleProvided", True),
                             ("runnerStartedInThisProcess", True), ("realRuntimeCommands", False),
                             ("confirmedStages", True), ("confirmedStages", -1), ("confirmedStages", 33),
                             ("judgeId", "foreign"), ("retainedVolumeName", "replacement")):
            with self.subTest(field=field, value=value):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_observation({**observation, field: value}, "judge", "attempt", 21, "volume")

    def test_java_container_recovery_observation_rejects_completion_or_deletion_overclaim(self):
        observation = {"processId": 21, "freshJvm": True, "handleProvided": False,
                       "runnerStartedInThisProcess": False, "realRuntimeCommands": True,
                       "judgeId": "judge", "attemptId": "attempt", "retainedVolumeName": "volume",
                       "confirmedStages": 6, "productRecoveryComplete": False,
                       "volumeContinuityProven": False, "deletionCredentialIssued": False, "productionAcceptance": False}
        for field in ("productRecoveryComplete", "volumeContinuityProven", "deletionCredentialIssued", "productionAcceptance"):
            with self.subTest(field=field):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_observation({**observation, field: True}, "judge", "attempt", 21, "volume")

    def test_java_container_recovery_trace_requires_full_identity_and_real_success(self):
        identity = "a" * 64
        prefix = ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock", "container"]
        stop = {"arguments": prefix + ["stop", "-t", "0", identity], "exitCode": 0, "stdoutBytes": 65, "stderrBytes": 0, "stdout": identity + "\n", "stderr": ""}
        remove = {"arguments": prefix + ["rm", identity], "exitCode": 0, "stdoutBytes": 65, "stderrBytes": 0, "stdout": identity + "\n", "stderr": ""}
        runtime_preflight.validate_java_recovery_trace([stop, remove], [("stop", identity), ("rm", identity)])
        for changed in ({**remove, "exitCode": 1}, {**remove, "stderrBytes": 1},
                        {**remove, "stdout": "b" * 64 + "\n"},
                        {**remove, "stdout": "Flag --time has been deprecated, use --timeout instead\n" + identity + "\n"},
                        {**remove, "arguments": prefix + ["rm", identity[:12]]},
                        {**remove, "arguments": prefix + ["rm", "--force", identity]},
                        {**remove, "arguments": prefix[:-1] + ["volume", "rm", identity]}):
            with self.subTest(changed=changed):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_trace([stop, changed], [("stop", identity), ("rm", identity)])

    def test_java_container_recovery_trace_rejects_interleaved_holder_stop_before_stage_removal(self):
        supervisor, stage, holder = "a" * 64, "b" * 64, "c" * 64
        prefix = ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock", "container"]
        expected = [("stop", supervisor), ("stop", stage), ("rm", stage), ("rm", supervisor), ("stop", holder), ("rm", holder)]
        trace = [{"arguments": prefix + ([verb, "-t", "0", identity] if verb == "stop" else [verb, identity]),
                  "exitCode": 0, "stdoutBytes": 65, "stderrBytes": 0, "stdout": identity + "\n", "stderr": ""} for verb, identity in expected]
        runtime_preflight.validate_java_recovery_trace(trace, expected)
        trace.insert(2, trace.pop(4))
        with self.assertRaises(RuntimeError):
            runtime_preflight.validate_java_recovery_trace(trace, expected)

    def test_java_container_recovery_trace_bounds_inventory_and_allows_read_only_retry(self):
        entry = {"arguments": ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock", "container", "ls", "--all"],
                 "exitCode": 0, "stdoutBytes": 0, "stderrBytes": 0}
        runtime_preflight.validate_java_recovery_trace([entry], [])
        for trace in ([], [entry] * 257, [{**entry, "exitCode": True}], [{**entry, "stdoutBytes": True}]):
            with self.subTest(entries=len(trace)):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_trace(trace, [])

    def test_recovery_fault_raw_snapshots_are_retained_before_mismatch_assertion(self):
        before = {"a" * 64: {"State": {"Running": False}, "Labels": {"attempt": "attempt"}}}
        after = {"a" * 64: {"State": {"Running": True}, "Labels": {"attempt": "attempt"}}}
        volume_before = {"Name": "volume", "Labels": {"attempt": "attempt"}}
        volume_after = {"Name": "volume", "Labels": {"attempt": "other"}}
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            runtime_preflight.write_java_recovery_runtime_evidence(evidence, before, after, volume_before, volume_after)
            with self.assertRaises(RuntimeError):
                runtime_preflight.require(before == after, "snapshot identity differs")
            for name, expected in (("containers-independent-before", before), ("containers-independent-after", after),
                                   ("volume-independent-before", volume_before), ("volume-independent-after", volume_after)):
                self.assertEqual(expected, json.loads((evidence / (name + ".json")).read_text()))

    def test_live_cancel_mode_keeps_the_existing_java_and_two_volume_budget(self):
        for phase in ("outer", "namespace", "pivot", "daemon"):
            mode = phase + "-java-supervisor-cancel"
            self.assertEqual(phase, runtime_preflight.configure_mode(mode))
            self.assertTrue(runtime_preflight.JAVA_SUPERVISOR)
            self.assertTrue(runtime_preflight.JAVA_ACTIVE_CANCEL)
            self.assertEqual(mode, runtime_preflight.phase_mode(phase))
            self.assertEqual(4 * 1024 ** 3, runtime_preflight.MEMORY_MAX)
            self.assertEqual(1792 * 1024 ** 2, runtime_preflight.stage_tmpfs_limit_bytes())

    def test_live_cancel_mode_is_reset_before_the_original_java_mode(self):
        runtime_preflight.configure_mode("outer-java-supervisor-cancel")
        runtime_preflight.configure_mode("outer-java-supervisor")
        self.assertFalse(runtime_preflight.JAVA_ACTIVE_CANCEL)
        self.assertTrue(runtime_preflight.JAVA_SUPERVISOR)

    def test_live_cancel_mode_rejects_unknown_or_combined_phases(self):
        for mode in ("outer-java-supervisor-cancel-extra", "outer-recovery-java-supervisor-cancel",
                     "unknown-java-supervisor-cancel"):
            with self.assertRaises(RuntimeError):
                runtime_preflight.configure_mode(mode)

    def test_live_cancel_observation_requires_the_actual_same_jvm_and_trusted_control(self):
        observation = {"processId": 21, "judgeId": "judge", "attemptId": "attempt", "liveStageId": "a" * 64,
                       "sameJvmStartAndCancel": True, "runnerCancelInvoked": True, "liveStageVerifiedBeforeCancel": True,
                       "stageFlockHeldBeforeCancel": True, "trustedCancelMarkerObserved": True,
                       "stageOutcomeCancelled": True, "stageContainerAbsent": True, "realRuntimeCommands": True,
                       "pendingRecoveryReported": True, "productRecoveryComplete": False, "productionAcceptance": False,
                       "activeCancellationComplete": False, "deletionCredentialIssued": False}
        runtime_preflight.validate_java_live_cancel_observation(observation, "judge", "attempt", 21)
        for field in ("sameJvmStartAndCancel", "stageFlockHeldBeforeCancel", "trustedCancelMarkerObserved", "stageContainerAbsent"):
            with self.subTest(field=field):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_live_cancel_observation({**observation, field: False}, "judge", "attempt", 21)

    def test_live_cancel_observation_rejects_completion_and_foreign_identity_claims(self):
        observation = {"processId": 21, "judgeId": "judge", "attemptId": "attempt", "liveStageId": "a" * 64,
                       "sameJvmStartAndCancel": True, "runnerCancelInvoked": True, "liveStageVerifiedBeforeCancel": True,
                       "stageFlockHeldBeforeCancel": True, "trustedCancelMarkerObserved": True,
                       "stageOutcomeCancelled": True, "stageContainerAbsent": True, "realRuntimeCommands": True,
                       "pendingRecoveryReported": True, "productRecoveryComplete": False, "productionAcceptance": False,
                       "activeCancellationComplete": False, "deletionCredentialIssued": False}
        for field,value in (("processId", True), ("judgeId", "foreign"), ("liveStageId", "a" * 12),
                            ("productRecoveryComplete", True), ("activeCancellationComplete", True), ("deletionCredentialIssued", True)):
            with self.subTest(field=field):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_live_cancel_observation({**observation, field:value}, "judge", "attempt", 21)


    def recovery_fault_observation(self, mode):
        return {"processId": 21, "freshJvm": True, "handleProvided": False,
                "runnerStartedInThisProcess": False, "realRuntimeCommands": True,
                "judgeId": "judge", "attemptId": "attempt", "retainedVolumeName": "volume",
                "confirmedStages": 6, "productRecoveryComplete": False, "volumeContinuityProven": False,
                "deletionCredentialIssued": False, "productionAcceptance": False, "faultMode": mode,
                "expectedRecoveryFailure": True, "operationCompletedBeforeLoss": mode != "stop-unreachable",
                "daemonFaultInjected": False}

    def recovery_fault_trace(self, mode):
        supervisor, stage = "a" * 64, "b" * 64
        prefix = ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock", "container"]
        events = [("stop", supervisor)]
        if mode == "supervisor-rm-ack-loss":
            events.append(("rm", supervisor))
        if mode == "stage-rm-ack-loss":
            events += [("stop", stage), ("rm", stage)]
        trace = [{"arguments": prefix + ["ls", "--all"], "exitCode": 0, "stdoutBytes": 0, "stderrBytes": 0}]
        for verb, identity in events:
            trace.append({"arguments": prefix + ([verb, "-t", "0", identity] if verb == "stop" else [verb, identity]),
                          "exitCode": 0, "stdoutBytes": 65, "stderrBytes": 0, "stdout": identity + "\n", "stderr": ""})
        fault = trace[-1]
        fault["injectedFault"] = mode
        fault["requestedArguments"] = list(fault["arguments"])
        if mode == "stop-unreachable":
            fault["arguments"] = list(fault["arguments"])
            fault["arguments"][2] = "unix:///run/wjc/unavailable-recovery.sock"
            fault.update(exitCode=1, stdoutBytes=0, stdout="", stderr="connection refused", stderrBytes=18)
        return trace, events

    def test_recovery_fault_observation_accepts_only_explicit_process_and_proof_boundary(self):
        for mode in ("stop-unreachable", "supervisor-rm-ack-loss", "stage-rm-ack-loss"):
            runtime_preflight.validate_java_recovery_fault_observation(
                self.recovery_fault_observation(mode), "judge", "attempt", 21, "volume", mode)

    def test_recovery_fault_observation_rejects_false_failure_or_daemon_claim(self):
        observation = self.recovery_fault_observation("stop-unreachable")
        for field, value in (("expectedRecoveryFailure", False), ("daemonFaultInjected", True),
                             ("operationCompletedBeforeLoss", True), ("faultMode", "unknown"),
                             ("processId", True), ("productRecoveryComplete", True)):
            with self.subTest(field=field):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_fault_observation(
                        {**observation, field: value}, "judge", "attempt", 21, "volume", "stop-unreachable")

    def test_recovery_fault_observation_rejects_loss_before_real_operation(self):
        observation = self.recovery_fault_observation("supervisor-rm-ack-loss")
        observation["operationCompletedBeforeLoss"] = False
        with self.assertRaises(RuntimeError):
            runtime_preflight.validate_java_recovery_fault_observation(
                observation, "judge", "attempt", 21, "volume", "supervisor-rm-ack-loss")

    def test_recovery_fault_trace_accepts_real_transport_failure_and_ack_loss(self):
        for mode in ("stop-unreachable", "supervisor-rm-ack-loss", "stage-rm-ack-loss"):
            trace, events = self.recovery_fault_trace(mode)
            runtime_preflight.validate_java_recovery_fault_trace(trace, mode, events)

    def test_recovery_fault_trace_rejects_fake_transport_failure(self):
        trace, events = self.recovery_fault_trace("stop-unreachable")
        for changed in ({"exitCode": 0}, {"exitCode": True}, {"stdout": "a", "stdoutBytes": 1},
                        {"stderr": "", "stderrBytes": 0}, {"stderrBytes": 19}, {"injectedFault": "other"}):
            mutated = copy.deepcopy(trace)
            mutated[-1].update(changed)
            with self.subTest(changed=changed):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_fault_trace(mutated, "stop-unreachable", events)

    def test_recovery_fault_trace_rejects_foreign_endpoint_or_mutation_target(self):
        trace, events = self.recovery_fault_trace("stop-unreachable")
        for field, index, value in (("arguments", 2, "unix:///run/wjc/docker.sock"),
                                    ("arguments", -1, "a" * 12),
                                    ("requestedArguments", 2, "unix:///shared/docker.sock")):
            mutated = copy.deepcopy(trace)
            mutated[-1][field][index] = value
            with self.subTest(field=field, index=index):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_fault_trace(mutated, "stop-unreachable", events)

    def test_recovery_fault_trace_rejects_lost_response_without_real_full_id_success(self):
        trace, events = self.recovery_fault_trace("supervisor-rm-ack-loss")
        for changed in ({"exitCode": 1}, {"stdout": "a" * 12 + "\n", "stdoutBytes": 13},
                        {"stdout": "b" * 64 + "\n"}, {"stderr": "warning", "stderrBytes": 7},
                        {"injectedFault": "stop-unreachable"}):
            mutated = copy.deepcopy(trace)
            mutated[-1].update(changed)
            with self.subTest(changed=changed):
                with self.assertRaises(RuntimeError):
                    runtime_preflight.validate_java_recovery_fault_trace(mutated, "supervisor-rm-ack-loss", events)

    def test_recovery_fault_trace_rejects_continuation_after_lost_response(self):
        trace, events = self.recovery_fault_trace("stage-rm-ack-loss")
        trace.append(copy.deepcopy(trace[0]))
        with self.assertRaises(RuntimeError):
            runtime_preflight.validate_java_recovery_fault_trace(trace, "stage-rm-ack-loss", events)

    def test_recovery_fault_trace_rejects_stage_removal_before_supervisor_stop(self):
        trace, events = self.recovery_fault_trace("stage-rm-ack-loss")
        trace[1], trace[2] = trace[2], trace[1]
        with self.assertRaises(RuntimeError):
            runtime_preflight.validate_java_recovery_fault_trace(trace, "stage-rm-ack-loss", events)


    def test_supervisor_container_cleanup_rejects_foreign_ownership(self):
        fixture, create, name, labels, identity, inspection = self.supervisor_inspection_fixture()
        inspection["Config"]["Labels"] = {**labels, "io.webjudge.attempt": "foreign"}
        calls = []

        def invoke(arguments, **kwargs):
            calls.append(arguments)
            stdout = identity.encode() if arguments == create else json.dumps([inspection]).encode()
            return SimpleNamespace(returncode=0, stdout=stdout, stderr=b"")

        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(RuntimeError, "ownership differs"):
                invoke_supervisor_container(*fixture, 30, Path(temporary), invoke, lambda *args, **kwargs: None)
        self.assertFalse(any("rm" in arguments for arguments in calls))



if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(NetworkParserTest))
    print(json.dumps({"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                      "skipped": len(result.skipped)}))
    sys.exit(0 if result.wasSuccessful() and result.testsRun == 94 else 1)
