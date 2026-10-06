import hashlib
import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
from pathlib import Path


REPOSITORY = Path("/mnt/c/Users/29901/Documents/dp/web-judge-c")
ACCEPTANCE = REPOSITORY / "docs/acceptance/TASK-10"
ENVIRONMENT = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "HOME": "/nonexistent", "LANG": "C.UTF-8"}
BUILD = False
OFFLINE_BUILD = False
REPLAY_BUILD = False
STAGE = False
RECOVERY = False
SUPERVISOR = False
SUPERVISOR_IMAGE = False
SUPERVISOR_CONTAINER = False
JAVA_SUPERVISOR = False
JAVA_ACTIVE_CANCEL = False
JAVA_MAIN_CLASS = "com.example.demo.acceptance.JavaSupervisorProbe"
JAVA_EXECUTABLE = Path("/usr/lib/jvm/java-21-openjdk-amd64/bin/java")
SUPERVISOR_CONTAINER_IMAGE_ID = "sha256:6f7e70984cfff475fdd0fecc315cc7620c16b8f0e0faa23a22414b7271bbd901"
SUPERVISOR_CONTAINER_ARCHIVE_SHA256 = "5b8eb56ef08c5f1c1db854b68cd97d408e5dd906d107c7d4eac827006e4c3288"
SUPERVISOR_CONTAINER_SOURCE_HASHES = {
    "linux_stage_executor.py": "7496c1b8946a1cdd15e6a556a35a894e722846eef816060799ccc33c4a227094",
    "linux_stage_volume.py": "cded2b9db09dca3d7714a5117c483ede3d346f9570eacd5ebff809cedf87f90e",
    "linux_supervisor.py": "bd2ab7d4c0b407ab438fb7fbec1a1c5d8801043aa7f16f9f931681ba96ce8ae4",
    "task_runner.py": "bef3a6bedd2f00e8e2e8ddc2816e01b49444979f9736a2ccd1835937f872562a",
    "runner_manifest.py": "92ff4ca050ba4aa89a573dbcfb6fc7e48fabdea31fc0cf95a80939cbb3d4294f",
}
SUPERVISOR_SOURCES = ("linux_stage_executor.py", "linux_stage_volume.py", "linux_supervisor.py",
                      "task_runner.py", "runner_manifest.py")
STAGE_IMAGE_SHA256 = "9d666ec70e1dd5b115a89ea1035857a66cdacec55cd6120ed52f1e7d7bc6b832"
STAGE_IMAGE_ID = "sha256:360a2877270d6ce26675f4867588dcbffba5a743947557c169713967b2a2f33f"
MEMORY_MAX = 2 * 1024 ** 3
STORAGE_MAX = 2 * 1024 ** 3
REPORT = None


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def phase_mode(phase):
    if JAVA_SUPERVISOR:
        return phase + ("-java-supervisor-cancel" if JAVA_ACTIVE_CANCEL else "-java-supervisor")
    if RECOVERY:
        return phase + "-recovery"
    if SUPERVISOR_CONTAINER:
        return phase + "-supervisor-container"
    if SUPERVISOR_IMAGE:
        return phase + "-supervisor-image-" + ("replay" if REPLAY_BUILD else "build")
    return phase + ("-supervisor" if SUPERVISOR else "-stage" if STAGE else "-replay" if REPLAY_BUILD else "-build" if BUILD else "")


def configure_mode(mode):
    global SUPERVISOR_IMAGE, SUPERVISOR_CONTAINER, SUPERVISOR, STAGE, RECOVERY, REPLAY_BUILD, OFFLINE_BUILD, BUILD, MEMORY_MAX
    global JAVA_SUPERVISOR, JAVA_ACTIVE_CANCEL
    JAVA_ACTIVE_CANCEL = mode.endswith("-java-supervisor-cancel")
    if "-java-supervisor-cancel" in mode:
        require(re.fullmatch(r"(outer|namespace|pivot|daemon)-java-supervisor-cancel", mode), "unsupported Java cancellation mode")
    if JAVA_ACTIVE_CANCEL:
        mode = mode.removesuffix("-cancel")
    JAVA_SUPERVISOR = mode.endswith("-java-supervisor")
    if JAVA_SUPERVISOR:
        require(re.fullmatch(r"(outer|namespace|pivot|daemon)-java-supervisor", mode), "unsupported Java supervisor mode")
        mode = mode.removesuffix("-java-supervisor") + "-supervisor-container"
    RECOVERY = mode.endswith("-recovery")
    if RECOVERY:
        require(re.fullmatch(r"(outer|namespace|pivot|daemon)-recovery", mode), "unsupported recovery mode")
        mode = mode.removesuffix("-recovery")
    SUPERVISOR_CONTAINER = mode.endswith("-supervisor-container")
    if SUPERVISOR_CONTAINER:
        mode = mode.removesuffix("-container")
    SUPERVISOR_IMAGE = "-supervisor-image-" in mode
    if SUPERVISOR_IMAGE:
        require(re.fullmatch(r"(outer|namespace|pivot|daemon)-supervisor-image-(build|replay|build-offline)", mode),
                "unsupported supervisor image mode")
        mode = mode.replace("-supervisor-image-", "-")
    SUPERVISOR = mode.endswith("-supervisor")
    STAGE = RECOVERY or SUPERVISOR or mode.endswith("-stage")
    if SUPERVISOR:
        mode = mode.removesuffix("-supervisor")
    elif STAGE:
        mode = mode.removesuffix("-stage")
    REPLAY_BUILD = mode.endswith("-replay")
    OFFLINE_BUILD = mode == "daemon-build-offline" or REPLAY_BUILD
    if REPLAY_BUILD:
        mode = mode.removesuffix("-replay") + "-build"
    elif OFFLINE_BUILD:
        mode = "daemon-build"
    BUILD = mode.endswith("-build")
    MEMORY_MAX = (1504 * 1024 ** 2) if BUILD else (4 * 1024 ** 3) if SUPERVISOR_CONTAINER else (2 * 1024 ** 3)
    return mode.removesuffix("-build") if BUILD else mode


def stage_tmpfs_limit_bytes():
    return (1792 if SUPERVISOR_CONTAINER else 1408) * 1024 ** 2


def record_image_load_resources(role):
    usage = shutil.disk_usage("/")
    record("stage-image-load-resources", imageRole=role, temporaryDiskTotalBytes=usage.total,
           temporaryDiskUsedBytes=usage.used, temporaryDiskFreeBytes=usage.free,
           outerMemoryCurrentBytes=int(Path("/sys/fs/cgroup/memory.current").read_text()),
           outerMemoryPeakBytes=int(Path("/sys/fs/cgroup/memory.peak").read_text()))


def snapshot_supervisor_build(evidence):
    snapshot = evidence / "supervisor-build"
    sources = snapshot / "sources"
    sources.mkdir(parents=True, exist_ok=False)
    for filename in SUPERVISOR_SOURCES:
        shutil.copyfile(REPOSITORY / "runner/scripts" / filename, sources / filename)
    lock = "".join(file_sha256(sources / name) + "  ./" + name + "\n" for name in sorted(SUPERVISOR_SOURCES))
    (sources / "SHA256SUMS").write_text(lock, encoding="ascii")
    for name in ("Dockerfile", ".dockerignore"):
        shutil.copyfile(REPOSITORY / "runner/linux-supervisor" / name, snapshot / name)
    paths = [evidence / "runtime_preflight.py", evidence / "test_runtime_preflight.py", evidence / "supervisor_probe.py",
             snapshot / "Dockerfile", snapshot / ".dockerignore", *sorted(sources.iterdir())]
    (evidence / "source-hashes.json").write_text(json.dumps(
        {str(path.relative_to(evidence)): file_sha256(path) for path in paths}, indent=2) + "\n")


def copy_supervisor_sources(source, destination):
    require({path.name for path in source.iterdir()} == set(SUPERVISOR_SOURCES) | {"SHA256SUMS"},
            "supervisor source snapshot file set differs")
    require(all(path.is_file() and not path.is_symlink() for path in source.iterdir()),
            "supervisor source snapshot is not regular")
    expected = "".join(file_sha256(source / name) + "  ./" + name + "\n" for name in sorted(SUPERVISOR_SOURCES))
    require((source / "SHA256SUMS").read_text(encoding="ascii") == expected,
            "supervisor source snapshot hash changed")
    shutil.copytree(source, destination)


def record(event, **details):
    with (REPORT / "steps.jsonl").open("a", encoding="utf-8") as output:
        output.write(json.dumps({"event": event, **details}, ensure_ascii=False) + "\n")


def command(arguments, timeout=30):
    result = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, errors="replace", timeout=timeout,
                            env=ENVIRONMENT, close_fds=True)
    require(result.returncode == 0,
            "command failed: " + json.dumps(arguments) + " :: " + result.stderr[-6000:])
    return result.stdout.strip()


def limits(directory):
    values = {name: (directory / name).read_text().strip()
              for name in ("memory.max", "memory.swap.max", "pids.max", "cpu.max")}
    require(values["memory.max"] == str(MEMORY_MAX), "outer memory limit differs")
    require(values["memory.swap.max"] == "0", "swap is not disabled")
    require(values["pids.max"] == "512", "outer process limit differs")
    quota, period = values["cpu.max"].split()
    require(quota != "max" and int(quota) <= 2 * int(period), "outer CPU limit exceeds budget")
    return values


def namespaces():
    return {name: os.readlink("/proc/self/ns/" + name)
            for name in ("mnt", "pid", "net", "ipc", "uts", "cgroup")}


def validate_network(route_text, device_text, ipv6_text):
    routes = [line for line in route_text.splitlines() if line.strip() and line.split()[0] != "Iface"]
    interfaces = [line.split(":", 1)[0].strip() for line in device_text.splitlines() if ":" in line]
    ipv6_interfaces = [line.split()[-1] for line in ipv6_text.splitlines() if line.strip()]
    require(not routes and set(interfaces) == {"lo"} and set(ipv6_interfaces).issubset({"lo"}),
            "external network route or interface visible")


def verify_imported_image(expected, imported):
    for field in ("RootFS", "Config", "Architecture", "Os"):
        require(field in expected and imported.get(field) == expected[field], "imported image changed " + field)
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", imported.get("Id", "")), "invalid imported image ID")
    return imported["Id"]


def bind(source, target, readonly=True):
    if source.is_dir():
        target.mkdir(parents=True, exist_ok=True)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.touch(exist_ok=False)
    command(["mount", "--bind", str(source), str(target)])
    if readonly:
        command(["mount", "-o", "remount,bind,ro", str(target)])


def java_bundle_location(bundle):
    expected_parent = REPOSITORY / "target/task10-runtime"
    require(bundle.is_absolute() and bundle.resolve() == bundle and bundle.name == "bundle"
            and bundle.parent.parent == expected_parent
            and re.fullmatch(r"JVM-BUILD-R[0-9]{2}", bundle.parent.name), "Java bundle must be an explicit JVM-BUILD artifact")


def validate_java_bundle(bundle, expected_hash):
    require(re.fullmatch(r"[a-f0-9]{64}", expected_hash), "Java bundle manifest lock must be a SHA256 digest")
    require(bundle.is_dir() and bundle.resolve() == bundle, "Java bundle path is not canonical")
    manifest_path = bundle / "bundle-manifest.json"
    require(manifest_path.is_file() and not manifest_path.is_symlink() and manifest_path.stat().st_size <= 2 * 1024 ** 2,
            "Java bundle manifest is unsafe or oversized")
    require(file_sha256(manifest_path) == expected_hash, "Java bundle manifest hash changed")
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get("version") == 1 and manifest.get("mainClass") == JAVA_MAIN_CLASS, "Java bundle entrypoint differs")
    for key in ("probeSourceSha256", "sourceSnapshotSha256"):
        require(re.fullmatch(r"[a-f0-9]{64}", manifest.get(key, "")), "Java bundle source lock missing")
    files = manifest.get("files")
    require(isinstance(files, dict) and 0 < len(files) <= 10000, "Java bundle file count exceeds bound")
    actual = set()
    for count, path in enumerate(bundle.rglob("*"), 1):
        require(count <= 12000 and not path.is_symlink() and (path.is_dir() or path.is_file()), "Java bundle has unsafe entries")
        if path.is_file() and path != manifest_path:
            actual.add(path.relative_to(bundle).as_posix())
    require(actual == set(files), "Java bundle file inventory changed")
    require("classes/com/example/demo/acceptance/JavaSupervisorProbe.class" in files, "Java diagnostic class missing")
    total = 0
    for name, expected in files.items():
        require(re.fullmatch(r"(?:classes/(?:[A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+[.]class|lib/[A-Za-z0-9_.+-]+[.]jar)", name),
                "Java bundle contains a non-runtime asset")
        path = bundle / name
        require(type(expected.get("bytes")) is int and 0 <= expected["bytes"] <= 64 * 1024 ** 2
                and path.stat().st_size == expected["bytes"], "Java bundle file size changed")
        total += expected["bytes"]
        require(total <= 256 * 1024 ** 2 and file_sha256(path) == expected.get("sha256"), "Java bundle size or file hash differs")
    require(total == manifest.get("totalBytes"), "Java bundle total size differs")
    return manifest


def snapshot_java_bundle(evidence):
    require(len(sys.argv) == 5, "Java mode requires bundle directory and manifest SHA256 arguments")
    bundle, digest = Path(sys.argv[3]), sys.argv[4]
    java_bundle_location(bundle)
    manifest = validate_java_bundle(bundle, digest)
    source = Path(__file__).with_name("JavaSupervisorProbe.java")
    require(file_sha256(source) == manifest["probeSourceSha256"], "Java probe source differs from compiled bundle")
    build_report = ACCEPTANCE / bundle.parent.name
    require(file_sha256(build_report / "source-hashes.json") == manifest["sourceSnapshotSha256"], "Java source snapshot lock differs")
    shutil.copyfile(bundle / "bundle-manifest.json", evidence / "bundle-manifest.json")
    shutil.copyfile(build_report / "source-hashes.json", evidence / "java-build-source-hashes.json")
    lock = {"bundleDirectory": str(bundle), "manifestSha256": digest, "probeSourceSha256": manifest["probeSourceSha256"],
            "sourceSnapshotSha256": manifest["sourceSnapshotSha256"], "bundleBytes": manifest["totalBytes"]}
    (evidence / "java-bundle-lock.json").write_text(json.dumps(lock, indent=2) + "\n")
    record("java-bundle-locked", **lock, scope="offline compiled runtime assets only; no Maven cache or host home binding")


def java_probe_arguments():
    return [str(JAVA_EXECUTABLE), "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
            "-XX:ReservedCodeCacheSize=64m", "-XX:MaxDirectMemorySize=32m", "-XX:ActiveProcessorCount=2",
            "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-Djava.awt.headless=true", "-Duser.home=/nonexistent",
            "-Djava.io.tmpdir=/lab/java-tmp", "-Djna.tmpdir=/lab/java-tmp", "-cp", "/lab/java-bundle/classes:/lab/java-bundle/lib/*",
            JAVA_MAIN_CLASS, "/lab/java-storage/judge-task10-java-supervisor", "/lab/evidence/java", SUPERVISOR_CONTAINER_IMAGE_ID, STAGE_IMAGE_ID]


def validate_java_runtime_container(inspection, journal, resource):
    expected = journal[resource]
    supervisor = resource == "supervisor"
    identity = expected["runtimeId"]
    require(re.fullmatch(r"[a-f0-9]{64}", identity) and inspection.get("Id") == identity
            and inspection.get("Name") == "/" + expected["name"], "Java runtime container identity differs")
    image = SUPERVISOR_CONTAINER_IMAGE_ID if supervisor else STAGE_IMAGE_ID
    config, host = inspection["Config"], inspection["HostConfig"]
    labels = {"io.webjudge.judge": "task10-java-supervisor", "io.webjudge.attempt": journal["attemptId"],
              "io.webjudge.managed": "supervisor" if supervisor else "stage-volume-holder"}
    require(inspection.get("Image") == image and config.get("Labels") == labels
            and config.get("User") == ("0:0" if supervisor else "65532:65532"), "Java runtime image, labels or user differs")
    control = "/work/control/attempts/" + journal["attemptId"]
    arguments = ([control + "/task-spec.json", "--work-root", "/work", "--stage-config", control + "/supervisor-config.json",
                  "--config-owner-uid", "0"] if supervisor else ["/bin/sleep", "300"])
    require(config.get("Cmd") == arguments and (config.get("Entrypoint") ==
            ["python3", "-B", "/opt/web-judge/linux_supervisor.py"] if supervisor else not config.get("Entrypoint")),
            "Java runtime command differs")
    require(host.get("ReadonlyRootfs") is True and host.get("Privileged") is False
            and host.get("NetworkMode") == "none" and host.get("PidMode") == ""
            and host.get("IpcMode") == "private" and host.get("CgroupnsMode") == "private"
            and type(host.get("UTSMode")) is str and host["UTSMode"] == ""
            and type(host.get("UsernsMode")) is str and host["UsernsMode"] == "", "Java runtime namespace/root policy differs")
    require("OomKillDisable" in host and (host["OomKillDisable"] is None or host["OomKillDisable"] is False),
            "Java runtime OOM-kill policy differs")
    state = inspection.get("State", {})
    require(type(state.get("Error")) is str and state["Error"] == "" and state.get("OOMKilled") is False
            and state.get("Dead") is False and state.get("Restarting") is False and state.get("Paused") is False,
            "Java runtime health state differs")
    require(host.get("CapDrop") == ["ALL"] and not host.get("CapAdd")
            and host.get("SecurityOpt") == ["no-new-privileges"] and host.get("LogConfig", {}).get("Type") == "none",
            "Java runtime capabilities or logging differs")
    memory = (256 if supervisor else 64) * 1024 ** 2
    require(host.get("Memory") == memory and host.get("MemorySwap") == memory
            and host.get("PidsLimit") == (64 if supervisor else 16)
            and host.get("NanoCpus") == (500000000 if supervisor else 250000000), "Java runtime resource limits differ")
    require(all(not host.get(name) for name in ("Devices", "DeviceRequests", "Binds", "Tmpfs", "VolumesFrom", "Links", "PortBindings")),
            "Java runtime has unexpected privileges or mounts")
    volume = journal["volume"]
    mountpoint = volume["mountpoint"]
    expected_mounts = [("volume", mountpoint, mountpoint if supervisor else "/stage-root", volume["name"])]
    if supervisor:
        expected_mounts.extend([("bind", "/lab/java-storage/judge-task10-java-supervisor", "/work", None),
                                ("bind", "/run/wjc/docker.sock", "/run/wjc/docker.sock", None)])
    mounts, declarations = inspection.get("Mounts", []), host.get("Mounts", [])
    require(len(mounts) == len(declarations) == len(expected_mounts), "Java runtime mount count differs")
    for kind, source, target, name in expected_mounts:
        matching = [mount for mount in mounts if mount.get("Destination") == target]
        declared = [mount for mount in declarations if mount.get("Target") == target]
        require(len(matching) == len(declared) == 1, "Java runtime mount target differs")
        mount, declaration = matching[0], declared[0]
        require(mount.get("Type") == kind and mount.get("Source") == source and mount.get("RW") is True
                and declaration.get("Type") == kind and declaration.get("Source") == (name or source)
                and declaration.get("ReadOnly", False) is False, "Java runtime mount contract differs")
        if name:
            require(mount.get("Name") == name and declaration.get("VolumeOptions", {}).get("NoCopy") is True,
                    "Java runtime volume identity or copy policy differs")
        else:
            require(mount.get("Propagation") == "rprivate", "Java runtime bind propagation differs")


def retain_java_task_snapshot():
    root = Path("/lab/java-storage/judge-task10-java-supervisor")
    if not root.exists():
        return
    require(root.is_dir() and root.resolve() == root, "Java failure evidence root is unsafe")
    target_root = REPORT / "java/harness-retained-task"
    for count, path in enumerate(root.rglob("*"), 1):
        require(count <= 128 and not path.is_symlink(), "Java failure evidence inventory is unsafe")
        if not path.is_file() or not (path.suffix in {".json", ".jsonl", ".cpp"} or path.name in {"1.in", "1.out", "1.ans"}):
            continue
        require(path.stat().st_size <= 128 * 1024, "Java failure evidence file exceeds bound")
        target = target_root / path.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)


def validate_java_discovery_observation(observation, attempt, parent_process_id):
    require(type(parent_process_id) is int and parent_process_id > 0, "parent Java process identity is missing")
    require(observation.get("freshJvm") is True and observation.get("handleProvided") is False
            and observation.get("runnerStartedInThisProcess") is False
            and observation.get("productRecoveryComplete") is False, "fresh-JVM discovery boundary differs")
    child_process_id = observation.get("processId")
    require(type(child_process_id) is int and child_process_id > 0 and child_process_id != parent_process_id,
            "discovery process is not a distinct JVM")
    require(observation.get("budget") == {"maxDirectoryEntries": 128, "maxReportEntries": 8,
            "maxJournalReadBytes": 262148, "maxPathUtf8Bytes": 1024}
            and observation.get("maximumReportJsonBytes") == 51712, "fresh-JVM discovery budget differs")
    report = observation.get("report")
    require(isinstance(report, dict) and report.get("scanComplete") is True
            and report.get("stopReason") == "SCAN_FINISHED", "fresh-JVM discovery is incomplete")
    visited = report.get("directoryEntriesVisited")
    require(type(visited) is int and 0 < visited <= 128
            and type(report.get("journalReadBytesReserved")) is int
            and report["journalReadBytesReserved"] == 131074, "fresh-JVM discovery work budget differs")
    require(report.get("findings") == [{"relativePath": "judge-task10-java-supervisor/control/attempts/" + attempt,
            "reason": "RECOVERY_REQUIRED", "phase": "SUPERVISOR_STOPPED", "stageEvidenceRequiresFreeze": True}]
            and report["findings"][0].get("stageEvidenceRequiresFreeze") is True,
            "fresh-JVM discovery did not retain the exact unresolved attempt")
    require(len(json.dumps(report, ensure_ascii=True).encode("utf-8")) <= 51712, "discovery report exceeds its byte bound")


def java_admission_context(attempt):
    require(type(attempt) is str and str(uuid.UUID(attempt)) == attempt and os.geteuid() == 0,
            "stage admission requires the verified attempt and actual root owner")
    product = Path("/lab/product")
    require({name: file_sha256(product / name) for name in SUPERVISOR_SOURCES} == SUPERVISOR_CONTAINER_SOURCE_HASHES,
            "stage admission product snapshot differs from the locked supervisor image")
    sys.path.insert(0, str(product))
    import linux_supervisor
    import linux_stage_executor
    require(Path(linux_supervisor.__file__).resolve() == product / "linux_supervisor.py"
            and Path(linux_stage_executor.__file__).resolve() == product / "linux_stage_executor.py",
            "stage admission imported an untrusted product source")
    root = Path("/lab/java-storage/judge-task10-java-supervisor")
    attempt_dir = root / "control/attempts" / attempt
    config = linux_supervisor.load_stage_config(attempt_dir / "supervisor-config.json", attempt_dir, attempt, root, 0)
    journal_root = attempt_dir / "stage-journal"
    require(journal_root.is_dir() and journal_root.resolve() == journal_root,
            "stage admission requires the existing retained journal directory")
    require(config.stage_root.is_relative_to(Path("/var/lib/wjc/docker/volumes"))
            and config.stage_root.resolve() == config.stage_root and config.stage_root.is_dir()
            and not any(config.stage_root.iterdir()), "stage admission volume is unsafe or not empty")
    return root, attempt_dir, journal_root, config, linux_stage_executor


def java_admission_file_identity(path):
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == 0 and before.st_nlink == 1
            and stat.S_IMODE(before.st_mode) == 0o600 and 0 < before.st_size <= 65536
            and path.resolve() == path, "stage admission retained file is unsafe")
    digest = file_sha256(path)
    after = path.lstat()
    require(all(getattr(before, field) == getattr(after, field)
                for field in ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")),
            "stage admission retained file changed during reading")
    return {"device": before.st_dev, "inode": before.st_ino, "uid": before.st_uid,
            "mode": stat.S_IMODE(before.st_mode), "links": before.st_nlink, "bytes": before.st_size, "sha256": digest}


def java_admission_inventory(attempt_dir, journal_root, attempt):
    paths = sorted(journal_root.glob("*.json"))
    require(len(paths) == 6, "stage admission requires exactly six completed real stage journals")
    stages = {}
    roles = []
    identities = []
    for path in paths:
        require(str(uuid.UUID(path.stem)) == path.stem, "stage admission stage filename is unsafe")
        stages[path.name] = java_admission_file_identity(path)
        journal = json.loads(path.read_text())
        require(journal.get("attempt") == attempt and journal.get("stage") == path.stem
                and journal.get("state") == "CLEANED" and journal.get("creationState") == "CONFIRMED",
                "stage admission journal was not completed by the real supervisor")
        identities.append(journal.get("containerId"))
        roles.append((journal.get("role"), journal.get("phase")))
    require(sorted(roles) == sorted((role, phase) for role in ("GENERATOR", "USER", "ORACLE") for phase in ("compile", "execute"))
            and all(isinstance(identity, str) and re.fullmatch(r"[0-9a-f]{64}", identity) for identity in identities)
            and len(set(identities)) == 6, "stage admission real stage role or identity coverage differs")
    return {"stageJournals": stages,
            "journalEntries": sorted(path.name for path in journal_root.iterdir() if path.name != "stage-admission.closed"),
            "runtimeState": java_admission_file_identity(attempt_dir / "runtime-state.json"),
            "supervisorConfig": java_admission_file_identity(attempt_dir / "supervisor-config.json"),
            "manifest": java_admission_file_identity(attempt_dir / "task-spec.json")}


def java_closed_admission_payload(attempt):
    return json.dumps({"admissionVersion": 1, "attemptId": attempt, "state": "CLOSED"},
                      separators=(",", ":")).encode("utf-8")


def validate_java_stage_admission_observation(observation, attempt, child_pid, before, after):
    require(type(observation) is dict, "stage admission observation must be an object")
    require(observation.get("attemptId") == attempt and observation.get("admissionClosed") is True
            and observation.get("productRecoveryComplete") is False
            and observation.get("unknownCreateBarrierEstablished") is False
            and observation.get("javaProductRecoveryInvoked") is False, "stage admission claim or identity differs")
    expected = {"admissionVersion": 1, "attemptId": attempt, "state": "CLOSED"}
    results = observation.get("closeResults")
    require(type(results) is list and len(results) == 2
            and all(type(result) is dict and result == expected and type(result.get("admissionVersion")) is int for result in results),
            "stage admission close results differ")
    markers = [observation.get(name) for name in ("markerAfterFirst", "markerAfterSecond", "markerAfterChild")]
    require(all(type(marker) is dict and marker == markers[0] for marker in markers), "stage admission marker identity changed")
    marker = markers[0]
    require(set(marker) == {"device", "inode", "uid", "mode", "links", "bytes", "sha256"}
            and all(type(marker[name]) is int for name in ("device", "inode", "uid", "mode", "links", "bytes"))
            and marker["device"] >= 0 and marker["inode"] > 0 and marker["uid"] == 0
            and marker["mode"] == 0o600 and marker["links"] == 1 and 0 < marker["bytes"] <= 65536
            and type(marker["sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", marker["sha256"]),
            "stage admission marker metadata differs")
    expected_payload = java_closed_admission_payload(attempt)
    require(marker["bytes"] == len(expected_payload)
            and marker["sha256"] == hashlib.sha256(expected_payload).hexdigest(),
            "stage admission marker does not bind the exact CLOSED protocol bytes")
    require(type(observation.get("closeProcessId")) is int and observation["closeProcessId"] > 0
            and type(child_pid) is int and child_pid > 0 and child_pid != observation["closeProcessId"],
            "stage admission rejection must run in a fresh process")
    child = observation.get("rejectionObservation", {})
    require(type(child) is dict and child.get("attemptId") == attempt and type(child.get("processId")) is int and child["processId"] == child_pid
            and child.get("freshPythonProcess") is True and child.get("admissionClosed") is True
            and child.get("productRecoveryComplete") is False
            and child.get("runtimeCalls") == [] and child.get("processCalls") == [] and child.get("stageRootEmpty") is True,
            "stage admission fresh-process execution boundary differs")
    expected_rejections = [{"role": role, "phase": phase,
                            "error": "stage admission is closed; trusted recovery required"}
                           for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE") for phase in ("compile", "execute")]
    require(child.get("rejections") == expected_rejections, "stage admission did not reject every role and phase exactly")
    require(type(before) is dict and type(after) is dict and before == after
            and set(before) == {"stageJournals", "journalEntries", "runtimeState", "supervisorConfig", "manifest"}
            and type(before["stageJournals"]) is dict and len(before["stageJournals"]) == 6
            and before["journalEntries"] == sorted([*before["stageJournals"], "runtime-client"]),
            "stage admission modified retained stage or Java control evidence")


def run_java_stage_admission_rejections(attempt):
    root, attempt_dir, journal_root, config, stage = java_admission_context(attempt)
    result = {"attemptId": attempt, "processId": os.getpid(), "freshPythonProcess": True,
              "admissionClosed": False, "productRecoveryComplete": False, "runtimeCalls": [], "processCalls": [],
              "rejections": [], "stageRootEmpty": False}
    def runtime_forbidden(*arguments, **keywords):
        result["runtimeCalls"].append({"arguments": [str(value)[:256] for value in arguments[:16]]})
        raise RuntimeError("closed admission attempted a real runtime operation")
    def process_forbidden(**keywords):
        result["processCalls"].append({"command": [str(value)[:256] for value in keywords.get("command", [])[:16]]})
        raise RuntimeError("closed admission attempted a workload process")
    try:
        executor = stage.LinuxStageExecutor(config, attempt, journal_root, process_forbidden)
        executor._control = runtime_forbidden
        parameters = {"stdin": b"", "timeout_seconds": 1, "memory_limit_bytes": 64 * 1024 ** 2,
                      "output_limit_bytes": 1024, "cancel_path": attempt_dir / "cancel.requested"}
        for role in ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE"):
            for phase in ("compile", "execute"):
                try:
                    if phase == "compile":
                        executor.compile(role, root / "source-not-opened.cpp", root / "artifact-must-not-exist", **parameters)
                    else:
                        executor.execute(role, root / "program-not-opened", [], {}, **parameters)
                except stage.StageSecurityError as error:
                    require(str(error) == "stage admission is closed; trusted recovery required",
                            "stage admission failed for a reason other than the durable closed gate")
                    result["rejections"].append({"role": role, "phase": phase, "error": str(error)})
                else:
                    raise RuntimeError("closed stage admission accepted an executable role")
        require(not (root / "artifact-must-not-exist").exists() and not any(config.stage_root.iterdir()),
                "closed stage admission produced stage or output files")
        result["stageRootEmpty"] = True
        result["admissionClosed"] = True
    finally:
        with Path("/lab/evidence/java/stage-admission-child.json").open("x") as output:
            json.dump(result, output, indent=2)
            output.write("\n")


def run_java_stage_admission_probe(attempt, evidence):
    import resource

    root, attempt_dir, journal_root, config, stage = java_admission_context(attempt)
    java_marker = journal_root / "stage-admission.closed"
    require(java_marker.is_file() and not (journal_root / "stage-admission.pending").exists(),
            "real stage admission must already be closed by the Java recovery preparation")
    require(java_marker.read_bytes() == java_closed_admission_payload(attempt),
            "Java-published stage admission marker differs from the exact CLOSED protocol bytes")
    java_marker_identity = java_admission_file_identity(java_marker)
    before = java_admission_inventory(attempt_dir, journal_root, attempt)
    with (evidence / "stage-admission-before.json").open("x") as output:
        json.dump(before, output, indent=2)
    first = stage.close_existing_stage_admission(journal_root, attempt, 0)
    marker_first = java_admission_file_identity(journal_root / "stage-admission.closed")
    second = stage.close_existing_stage_admission(journal_root, attempt, 0)
    marker_second = java_admission_file_identity(journal_root / "stage-admission.closed")
    require(marker_first == java_marker_identity and marker_second == java_marker_identity,
            "python idempotent closure rewrote the Java-published admission marker")
    marker_payload = (journal_root / "stage-admission.closed").read_bytes()
    require(marker_payload == java_closed_admission_payload(attempt),
            "actual stage admission marker differs from the exact CLOSED protocol bytes")
    require(hashlib.sha256(marker_payload).hexdigest() == marker_second["sha256"],
            "stage admission marker changed before evidence retention")
    with (evidence / "stage-admission-closed-marker.json").open("xb") as output:
        output.write(marker_payload)
    log = evidence / "stage-admission-child.log"
    def process_limits():
        resource.setrlimit(resource.RLIMIT_FSIZE, (64 * 1024, 64 * 1024))
    with log.open("xb") as output:
        child = subprocess.Popen(["/usr/bin/python3", "-B", "/lab/driver.py", "java-stage-admission-child", attempt],
                                 stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                 env=ENVIRONMENT, close_fds=True, start_new_session=True, preexec_fn=process_limits)
        try:
            exit_code = child.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=5)
            raise
        finally:
            after = java_admission_inventory(attempt_dir, journal_root, attempt)
            with (evidence / "stage-admission-after.json").open("x") as snapshot:
                json.dump(after, snapshot, indent=2)
    require(exit_code == 0 and log.stat().st_size <= 65536, "fresh-process stage admission rejection failed")
    child_path = evidence / "stage-admission-child.json"
    require(child_path.stat().st_size <= 65536, "stage admission child observation exceeds its bound")
    observation = {"attemptId": attempt, "admissionClosed": True, "productRecoveryComplete": False,
                   "unknownCreateBarrierEstablished": False, "javaProductRecoveryInvoked": False,
                   "closeProcessId": os.getpid(), "closeResults": [first, second],
                   "javaPreparationMarker": java_marker_identity,
                   "markerAfterFirst": marker_first, "markerAfterSecond": marker_second,
                   "markerAfterChild": java_admission_file_identity(journal_root / "stage-admission.closed"),
                   "rejectionObservation": json.loads(child_path.read_text())}
    validate_java_stage_admission_observation(observation, attempt, child.pid, before, after)
    require(not any(config.stage_root.iterdir()), "stage admission left stage files after fresh-process rejection")
    with (evidence / "stage-admission-result.json").open("x") as output:
        json.dump(observation, output, indent=2)
        output.write("\n")
    record("java-retained-stage-admission-closed", attemptId=attempt, admissionClosed=True,
           childProcessId=child.pid, rejectedRolePhases=8, retainedStageJournals=6,
           closedBy="java-recovery-preparation", verifiedBy="python-idempotent-recheck",
           productRecoveryComplete=False, unknownCreateBarrierEstablished=False, javaProductRecoveryInvoked=False,
           scope="Java preparation published the marker on the real stage-journal; harness python closure only re-verified idempotently; no early-create or UNKNOWN recovery acceptance")


def validate_java_recovery_observation(observation, judge, attempt, process_id, volume_name):
    require(type(process_id) is int and process_id > 0 and type(observation.get("processId")) is int
            and observation.get("processId") == process_id,
            "recovery observation is not from the invoked fresh JVM")
    require(observation.get("freshJvm") is True and observation.get("handleProvided") is False
            and observation.get("runnerStartedInThisProcess") is False and observation.get("realRuntimeCommands") is True,
            "recovery observation does not prove the intended process boundary")
    require(observation.get("judgeId") == judge and observation.get("attemptId") == attempt
            and observation.get("retainedVolumeName") == volume_name, "recovery identity differs")
    require(type(observation.get("confirmedStages")) is int and 0 <= observation["confirmedStages"] <= 32,
            "recovery stage count is invalid")
    require(all(observation.get(field) is False for field in
                ("productRecoveryComplete", "volumeContinuityProven", "deletionCredentialIssued", "productionAcceptance")),
            "container recovery overclaims its proof boundary")


def validate_java_recovery_trace(trace, expected_events):
    require(isinstance(trace, list) and 0 < len(trace) <= 256, "recovery trace entry budget differs")
    actual_mutations = []
    for entry in trace:
        require(isinstance(entry, dict) and type(entry.get("exitCode")) is int and entry["exitCode"] == 0
                and type(entry.get("stderrBytes")) is int and entry["stderrBytes"] == 0
                and type(entry.get("stdoutBytes")) is int and entry["stdoutBytes"] >= 0, "real recovery command failed")
        arguments = entry.get("arguments")
        require(isinstance(arguments, list) and all(type(argument) is str for argument in arguments)
                and arguments[:4] == ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock", "container"]
                and len(arguments) >= 5, "recovery selected an unexpected runtime command")
        verb = arguments[4]
        require(verb in {"ls", "inspect", "stop", "rm"}, "recovery performed an unapproved operation")
        if verb in {"stop", "rm"}:
            identity = arguments[-1]
            require(re.fullmatch(r"[a-f0-9]{64}", identity), "recovery mutation did not use a complete container identity")
            require(arguments[5:] == (["-t", "0", identity] if verb == "stop" else [identity]),
                    "recovery mutation arguments differ")
            require(entry.get("stdout") == identity + "\n" and entry.get("stderr") == "",
                    "recovery mutation output is not the exact complete identity")
            actual_mutations.append((verb, identity))
    require(actual_mutations == expected_events, "recovery order or target identities differ")


def validate_java_recovery_fault_observation(observation, judge, attempt, process_id, volume_name, mode):
    validate_java_recovery_observation(observation, judge, attempt, process_id, volume_name)
    require(mode in {"stop-unreachable", "supervisor-rm-ack-loss", "stage-rm-ack-loss"}
            and observation.get("faultMode") == mode and observation.get("expectedRecoveryFailure") is True,
            "recovery fault mode or expected refusal differs")
    require(observation.get("daemonFaultInjected") is False
            and observation.get("operationCompletedBeforeLoss") is (mode != "stop-unreachable"),
            "recovery fault overclaims a daemon failure or operation success")


def validate_java_recovery_fault_trace(trace, mode, expected_events):
    require(mode in {"stop-unreachable", "supervisor-rm-ack-loss", "stage-rm-ack-loss"}
            and isinstance(trace, list) and 1 < len(trace) <= 256 and bool(expected_events),
            "recovery fault trace budget or mode differs")
    fault = trace[-1]
    require(fault.get("injectedFault") == mode
            and all("injectedFault" not in entry for entry in trace[:-1]), "recovery fault did not end at its injected command")
    if mode != "stop-unreachable":
        validate_java_recovery_trace(trace, expected_events)
        require(expected_events[-1][0] == "rm" and fault.get("requestedArguments") == fault.get("arguments"),
                "acknowledgement loss did not follow the actual requested rm")
        return
    validate_java_recovery_trace(trace[:-1], expected_events[:-1])
    verb, identity = expected_events[-1]
    require(verb == "stop" and re.fullmatch(r"[a-f0-9]{64}", identity), "connection fault target is not a complete stop identity")
    suffix = ["container", "stop", "-t", "0", identity]
    require(fault.get("arguments") == ["/usr/bin/docker", "--host", "unix:///run/wjc/unavailable-recovery.sock"] + suffix
            and fault.get("requestedArguments") == ["/usr/bin/docker", "--host", "unix:///run/wjc/docker.sock"] + suffix,
            "connection failure did not use only the private unreachable socket")
    require(type(fault.get("exitCode")) is int and 0 < fault["exitCode"] <= 255
            and fault.get("stdoutBytes") == 0 and fault.get("stdout") == ""
            and type(fault.get("stderrBytes")) is int and 0 < fault["stderrBytes"] <= 1024
            and isinstance(fault.get("stderr"), str) and len(fault["stderr"].encode("utf-8")) == fault["stderrBytes"],
            "connection fault lacks the actual failed CLI response")



def run_bounded_java_process(arguments, log, timeout):
    import resource
    require(0 < timeout <= 300 and log.is_relative_to(REPORT) and not log.exists(), "Java process budget or log differs")
    def process_limits():
        resource.setrlimit(resource.RLIMIT_FSIZE, (512 * 1024, 512 * 1024))
    with log.open("xb") as output:
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   env=ENVIRONMENT, close_fds=True, start_new_session=True, preexec_fn=process_limits)
        try:
            exit_code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
            record("java-private-process-timeout", command=arguments, productRecoveryComplete=False)
            raise
        finally:
            retain_java_task_snapshot()
    record("java-private-process-exited", processId=process.pid, exitCode=exit_code, command=arguments,
           logBytes=log.stat().st_size)
    require(exit_code == 0, "fresh Java process failed; inspect its bounded log and retained evidence")
    return process.pid


def java_mode_arguments(extra):
    arguments = java_probe_arguments()
    return arguments[:arguments.index(JAVA_MAIN_CLASS) + 1] + extra


def run_java_confirmed_recovery(client, judge, attempt, evidence_name):
    attempt_dir = Path("/lab/java-storage") / ("judge-" + judge) / "control/attempts" / attempt
    journal = json.loads((attempt_dir / "runtime-state.json").read_text())
    recorded_stages = [json.loads(path.read_text()) for path in sorted((attempt_dir / "stage-journal").glob("*.json"))]
    require(0 < len(recorded_stages) <= 32 and all(stage.get("creationState") == "CONFIRMED" for stage in recorded_stages),
            "real recovery fixture must contain confirmed product stage records")
    present_stages = []
    for stage in recorded_stages:
        identity = stage["containerId"]
        listed = command(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + identity]).splitlines()
        require(listed in ([], [identity]), "independent stage identity observation differs")
        if listed:
            present_stages.append(identity)
    present_roles, stopped = {}, []
    for role in ("supervisor", "holder"):
        identity = journal[role]["runtimeId"]
        listed = command(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + identity]).splitlines()
        require(listed in ([], [identity]), "independent runtime identity observation differs")
        present_roles[role] = bool(listed)
        if listed:
            inspection = json.loads(command(client + ["container", "inspect", identity]))[0]
            require(inspection["Id"] == identity and type(inspection["State"].get("Running")) is bool,
                    "independent runtime inspection differs")
            if journal[role]["state"] not in {"REMOVAL_REQUESTED", "REMOVED"}:
                stopped.append(identity)
    supervisor, holder = (journal[role]["runtimeId"] for role in ("supervisor", "holder"))
    expected_stops = ([supervisor] if supervisor in stopped else []) + present_stages + ([holder] if holder in stopped else [])
    expected_removals = present_stages + ([supervisor] if present_roles["supervisor"] else []) + ([holder] if present_roles["holder"] else [])
    expected_events = ([("stop", supervisor)] if supervisor in stopped else [])
    expected_events += [event for identity in present_stages for event in (("stop", identity), ("rm", identity))]
    expected_events += ([("rm", supervisor)] if present_roles["supervisor"] else [])
    expected_events += ([("stop", holder)] if holder in stopped else [])
    expected_events += ([("rm", holder)] if present_roles["holder"] else [])
    volume_before = json.loads(command(client + ["volume", "inspect", journal["volume"]["name"]]))[0]
    evidence = REPORT / "java" / evidence_name
    arguments = java_mode_arguments(["--recover-confirmed-only", "/lab/java-storage", str(evidence),
                                     SUPERVISOR_CONTAINER_IMAGE_ID, STAGE_IMAGE_ID, judge, attempt])
    process_id = run_bounded_java_process(arguments, REPORT / (evidence_name + ".log"), 90)
    observation = json.loads((evidence / "observation.json").read_text())
    validate_java_recovery_observation(observation, judge, attempt, process_id, journal["volume"]["name"])
    require(observation["confirmedStages"] == len(recorded_stages), "reported confirmed stage count differs")
    trace_path = evidence / "commands.json"
    require(trace_path.stat().st_size <= 128 * 1024, "recovery command trace exceeds byte budget")
    validate_java_recovery_trace(json.loads(trace_path.read_text()), expected_events)
    for identity in [supervisor, holder, *[stage["containerId"] for stage in recorded_stages]]:
        require(not command(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + identity]),
                "reclaimed full-ID container remains")
    require(not command(client + ["container", "ls", "--all", "--quiet"]), "private recovery left an unrecorded container")
    volume_after = json.loads(command(client + ["volume", "inspect", journal["volume"]["name"]]))[0]
    require(all(volume_before.get(field) == volume_after.get(field) for field in
                ("Name", "Driver", "Labels", "Options", "Mountpoint")), "recovery changed the retained volume description")
    (evidence / "volume-independent-before.json").write_text(json.dumps(volume_before, indent=2) + "\n")
    (evidence / "volume-independent-after.json").write_text(json.dumps(volume_after, indent=2) + "\n")
    record("java-confirmed-container-recovery-verified", **observation, stoppedFullIds=expected_stops,
           removedFullIds=expected_removals, evidenceDirectory=str(evidence),
           scope="real complete-ID container operations; retained volume description is not object continuity proof")
    return process_id


def recovery_container_snapshots(client, identities):
    snapshots = {}
    for identity in identities:
        require(re.fullmatch(r"[a-f0-9]{64}", identity), "fault fixture contains an incomplete identity")
        listed = command(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + identity]).splitlines()
        require(listed in ([], [identity]), "fault fixture full-ID list differs")
        if listed:
            inspection = json.loads(command(client + ["container", "inspect", identity]))
            require(isinstance(inspection, list) and len(inspection) == 1 and inspection[0]["Id"] == identity,
                    "fault fixture inspect differs")
            snapshots[identity] = inspection[0]
    return snapshots


def write_java_recovery_runtime_evidence(evidence, containers_before, containers_after, volume_before, volume_after):
    for name, value in (("containers-independent-before", containers_before), ("containers-independent-after", containers_after),
                        ("volume-independent-before", volume_before), ("volume-independent-after", volume_after)):
        (evidence / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n")


def run_java_recovery_fault(client, judge, attempt, evidence_name, mode):
    attempt_dir = Path("/lab/java-storage") / ("judge-" + judge) / "control/attempts" / attempt
    journal_path = attempt_dir / "runtime-state.json"
    before = json.loads(journal_path.read_text())
    stage_paths = sorted((attempt_dir / "stage-journal").glob("*.json"))
    stages_before = {path.name: json.loads(path.read_text()) for path in stage_paths}
    bytes_before = {path.name: path.read_bytes() for path in stage_paths}
    require(0 < len(stages_before) <= 32 and all(stage.get("creationState") == "CONFIRMED" for stage in stages_before.values()),
            "fault fixture is not confirmed product evidence")
    supervisor, holder = (before[role]["runtimeId"] for role in ("supervisor", "holder"))
    stage_ids = [stage["containerId"] for stage in stages_before.values()]
    containers_before = recovery_container_snapshots(client, [supervisor, holder, *stage_ids])
    require(supervisor in containers_before and holder in containers_before, "fault fixture lacks the retained runtime containers")
    if mode == "stage-rm-ack-loss":
        present = [identity for identity in stage_ids if identity in containers_before]
        require(len(present) == 1 and containers_before[present[0]]["State"].get("Running") is True,
                "stage response-loss fixture requires exactly one live orphan")
        target = present[0]
        expected_events = [("stop", supervisor), ("stop", target), ("rm", target)]
    else:
        require(mode in {"stop-unreachable", "supervisor-rm-ack-loss"}
                and not any(identity in containers_before for identity in stage_ids), "completed fault fixture contains a live stage")
        target = supervisor
        expected_events = [("stop", supervisor)] + ([("rm", supervisor)] if mode == "supervisor-rm-ack-loss" else [])
    require(not Path("/run/wjc/unavailable-recovery.sock").exists(), "unreachable fixture socket already exists")
    volume_before = json.loads(command(client + ["volume", "inspect", before["volume"]["name"]]))[0]
    evidence = REPORT / "java" / evidence_name
    arguments = java_mode_arguments(["--recover-confirmed-fault", "/lab/java-storage", str(evidence),
                                     SUPERVISOR_CONTAINER_IMAGE_ID, STAGE_IMAGE_ID, judge, attempt, mode])
    process_id = run_bounded_java_process(arguments, REPORT / (evidence_name + ".log"), 90)
    observation = json.loads((evidence / "failure-observation.json").read_text())
    validate_java_recovery_fault_observation(observation, judge, attempt, process_id, before["volume"]["name"], mode)
    require(observation["confirmedStages"] == len(stages_before) and observation.get("targetFullId") == target,
            "fault observation differs from the real product fixture")
    trace_path = evidence / "commands.json"
    require(trace_path.stat().st_size <= 128 * 1024, "fault command trace exceeds byte budget")
    validate_java_recovery_fault_trace(json.loads(trace_path.read_text()), mode, expected_events)
    after = json.loads(journal_path.read_text())
    expected_runtime = json.loads(json.dumps(before))
    expected_runtime["state"] = "CLEANUP_PENDING"
    expected_runtime["supervisor"]["state"] = {"stop-unreachable": "STOP_REQUESTED",
                                              "supervisor-rm-ack-loss": "REMOVAL_REQUESTED",
                                              "stage-rm-ack-loss": "STOPPED"}[mode]
    require(after == expected_runtime, "failed recovery lost intent or changed another runtime identity")
    stages_after = {path.name: json.loads(path.read_text()) for path in sorted((attempt_dir / "stage-journal").glob("*.json"))}
    expected_stages = json.loads(json.dumps(stages_before))
    for stage in expected_stages.values():
        if mode == "stage-rm-ack-loss" and stage["containerId"] == target:
            stage["state"] = "REMOVING"
    require(stages_after == expected_stages, "failed recovery lost stage evidence or invented an outcome")
    if mode != "stage-rm-ack-loss":
        require(all(path.read_bytes() == bytes_before[path.name] for path in stage_paths), "runtime-only failure rewrote stage bytes")
    containers_after = recovery_container_snapshots(client, [supervisor, holder, *stage_ids])
    volume_after = json.loads(command(client + ["volume", "inspect", before["volume"]["name"]]))[0]
    write_java_recovery_runtime_evidence(evidence, containers_before, containers_after, volume_before, volume_after)
    if mode == "stop-unreachable":
        require(containers_after == containers_before, "unreachable CLI changed actual container state")
    else:
        require(target not in containers_after and set(containers_after) == set(containers_before) - {target},
                "successful rm before response loss did not remove exactly its target")
        require(containers_after[holder] == containers_before[holder], "failed recovery touched the holder")
    require(volume_after == volume_before, "failed recovery changed the retained volume description")
    record("java-same-site-recovery-failure-verified", **observation, evidenceDirectory=str(evidence),
           durableIntentPreserved=True, sameAttemptRetained=True,
           scope="real private CLI connection failure or injected response loss after real rm; not a daemon crash")
    return process_id



def validate_java_live_cancel_observation(observation, judge, attempt, process_id):
    require(type(observation.get("processId")) is int and observation["processId"] == process_id
            and observation.get("judgeId") == judge and observation.get("attemptId") == attempt
            and isinstance(observation.get("liveStageId"), str) and re.fullmatch(r"[a-f0-9]{64}", observation["liveStageId"]),
            "live cancellation process or identity differs")
    for flag in ("sameJvmStartAndCancel", "runnerCancelInvoked", "liveStageVerifiedBeforeCancel", "stageFlockHeldBeforeCancel",
                 "trustedCancelMarkerObserved", "stageOutcomeCancelled", "stageContainerAbsent", "realRuntimeCommands", "pendingRecoveryReported"):
        require(observation.get(flag) is True, "live cancellation lacks actual product/control proof: " + flag)
    for flag in ("productRecoveryComplete", "productionAcceptance", "activeCancellationComplete", "deletionCredentialIssued"):
        require(observation.get(flag) is False, "live cancellation overclaims full recovery: " + flag)


def run_java_active_cancel(client):
    judge = "task10-java-active-recovery"
    arguments = java_mode_arguments(["--cancel-active-only", "/lab/java-storage/judge-" + judge,
                                     "/lab/evidence/java/active", SUPERVISOR_CONTAINER_IMAGE_ID, STAGE_IMAGE_ID])
    process_id = run_bounded_java_process(arguments, REPORT / "active-cancel-producer.log", 120)
    evidence = REPORT / "java/active"
    observation = json.loads((evidence / "observation.json").read_text())
    attempt = observation["attemptId"]
    require(str(uuid.UUID(attempt)) == attempt, "live cancellation attempt is not canonical")
    validate_java_live_cancel_observation(observation, judge, attempt, process_id)
    attempt_dir = Path("/lab/java-storage") / ("judge-" + judge) / "control/attempts" / attempt
    marker = attempt_dir / "cancel.requested"
    metadata = marker.lstat()
    require(stat.S_ISREG(metadata.st_mode) and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 0 and metadata.st_gid == 0 and metadata.st_nlink == 1 and metadata.st_size == 0,
            "trusted cancellation marker identity differs")
    require(not command(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + observation["liveStageId"]]),
            "cancelled stage remains in the private runtime")
    stages = [json.loads(path.read_text()) for path in sorted((attempt_dir / "stage-journal").glob("*.json"))]
    cancelled = [stage for stage in stages if stage.get("containerId") == observation["liveStageId"]]
    require(len(cancelled) == 1 and cancelled[0].get("state") == "CLEANED"
            and cancelled[0].get("outcome", {}).get("status") == "CANCELLED", "Python did not persist actual cancellation and directory cleanup")
    owner = json.loads((evidence / "supervisor-before-product-stop.json").read_text())
    journal = json.loads((attempt_dir / "runtime-state.json").read_text())
    require(owner["Id"] == journal["supervisor"]["runtimeId"] and owner["State"].get("Running") is False
            and owner["State"].get("Pid") == 0 and owner["State"].get("ExitCode") == 20
            and owner["State"].get("OOMKilled") is False, "trusted supervisor was force-killed instead of cooperatively cancelling")
    record("java-live-cancellation-control-verified", **observation,
           scope="actual same-JVM Runner.cancel while Python stage flock is held; retained runtime/volume still requires recovery")
    recovered = run_java_confirmed_recovery(client, judge, attempt, "active-recovery")
    retried = run_java_confirmed_recovery(client, judge, attempt, "active-retry")
    require(len({process_id, recovered, retried}) == 3, "live cancellation recovery reused a JVM")
    record("java-live-cancellation-retained-recovery-verified", judgeId=judge, attemptId=attempt,
           cancellationProcessId=process_id, recoveryProcessId=recovered, retryProcessId=retried,
           productRecoveryComplete=False, activeCancellationComplete=False)



def run_java_active_recovery(client):
    judge = "task10-java-active-recovery"
    arguments = java_mode_arguments(["--start-active-only", "/lab/java-storage/judge-" + judge,
                                     "/lab/evidence/java/active", SUPERVISOR_CONTAINER_IMAGE_ID, STAGE_IMAGE_ID])
    producer = run_bounded_java_process(arguments, REPORT / "active-producer.log", 120)
    observation = json.loads((REPORT / "java/active/observation.json").read_text())
    require(observation.get("producerProcessId") == producer and observation.get("judgeId") == judge
            and observation.get("liveStageVerified") is True, "active product fixture was not observed by its actual producer")
    attempt = observation["attemptId"]
    require(str(uuid.UUID(attempt)) == attempt and re.fullmatch(r"[a-f0-9]{64}", observation["liveStageId"]),
            "active product fixture identity differs")
    inspected = json.loads(command(client + ["container", "inspect", observation["liveStageId"]]))[0]
    require(inspected["Id"] == observation["liveStageId"] and inspected["State"].get("Running") is True,
            "active stage did not survive producer JVM exit")
    (REPORT / "java/active/independent-live-inspect.json").write_text(json.dumps(inspected, indent=2) + "\n")
    journal_path = Path("/lab/java-storage") / ("judge-" + judge) / "control/attempts" / attempt / "runtime-state.json"
    journal_bytes = journal_path.read_bytes()
    journal = json.loads(journal_bytes)
    supervisor = journal["supervisor"]["runtimeId"]
    owner = json.loads(command(client + ["container", "inspect", supervisor]))[0]
    require(re.fullmatch(r"[a-f0-9]{64}", supervisor) and owner["Id"] == supervisor
            and owner["State"].get("Running") is True and owner["Image"] == SUPERVISOR_CONTAINER_IMAGE_ID
            and owner["Config"].get("Labels") == {"io.webjudge.judge": judge, "io.webjudge.attempt": attempt,
                                                  "io.webjudge.managed": "supervisor"},
            "fault injection supervisor is not the exact newly created private owner")
    killed = command(client + ["container", "kill", "--signal", "KILL", supervisor])
    require(killed == supervisor, "private supervisor SIGKILL returned a different identity")
    deadline = time.monotonic() + 15
    while True:
        stopped = json.loads(command(client + ["container", "inspect", supervisor]))[0]
        if stopped["State"].get("Running") is False:
            break
        require(time.monotonic() < deadline, "private supervisor did not stop after SIGKILL")
        time.sleep(0.05)
    require(stopped["State"].get("Pid") == 0 and stopped["State"].get("ExitCode") == 137
            and stopped["State"].get("OOMKilled") is False and journal_path.read_bytes() == journal_bytes,
            "SIGKILL fixture forged durable runtime state or did not establish the intended owner crash")
    orphan = json.loads(command(client + ["container", "inspect", observation["liveStageId"]]))[0]
    require(orphan["Id"] == observation["liveStageId"] and orphan["State"].get("Running") is True,
            "live CONFIRMED stage did not survive the private supervisor crash")
    (REPORT / "java/active/supervisor-sigkill-inspect.json").write_text(json.dumps(stopped, indent=2) + "\n")
    (REPORT / "java/active/orphan-live-inspect.json").write_text(json.dumps(orphan, indent=2) + "\n")
    record("java-private-supervisor-sigkill-injected", supervisorFullId=supervisor,
           orphanStageFullId=observation["liveStageId"], durableJournalUnchanged=True,
           orphanStageStillRunning=True, productRecoveryComplete=False,
           scope="new private owner crash releases its active Python stage flock; not active cancellation acceptance")
    failed = run_java_recovery_fault(client, judge, attempt, "active-stage-rm-ack-loss", "stage-rm-ack-loss")
    recovered = run_java_confirmed_recovery(client, judge, attempt, "active-recovery")
    retried = run_java_confirmed_recovery(client, judge, attempt, "active-retry")
    require(len({producer, failed, recovered, retried}) == 4, "stage failure retry reused a JVM")
    record("java-same-site-stage-failure-retry-verified", judgeId=judge, attemptId=attempt,
           failureProcessId=failed, recoveryProcessId=recovered, retryProcessId=retried, productRecoveryComplete=False)


def run_java_supervisor_probe(client):
    lock = json.loads((REPORT / "java-bundle-lock.json").read_text())
    validate_java_bundle(Path("/lab/java-bundle"), lock["manifestSha256"])
    require(not command(client + ["container", "ls", "--all", "--quiet"])
            and not command(client + ["volume", "ls", "--quiet"]), "Java probe requires an empty dedicated runtime")
    temporary = Path("/lab/java-tmp")
    metadata = temporary.stat()
    require(metadata.st_uid == metadata.st_gid == 0 and metadata.st_mode & 0o777 == 0o700,
            "JNA temporary directory ownership or permissions differ")
    require("noexec" not in command(["findmnt", "-n", "-o", "OPTIONS", "-T", str(temporary)]).split(","),
            "JNA extraction directory does not allow executable mappings")
    arguments = java_probe_arguments()
    record("java-entry-starting", command=arguments, manifestSha256=lock["manifestSha256"],
           processDeadlineSeconds=300, productRecoveryComplete=False, productionAcceptance=False)
    log = REPORT / "java-process.log"
    import resource
    def process_limits():
        resource.setrlimit(resource.RLIMIT_FSIZE, (512 * 1024, 512 * 1024))
    with log.open("xb") as output:
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   env=ENVIRONMENT, close_fds=True, start_new_session=True, preexec_fn=process_limits)
        try:
            exit_code = process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
            record("java-entry-timeout", productRecoveryComplete=False, resourceDisposition="private-domain teardown only")
            raise
        finally:
            retain_java_task_snapshot()
    record("java-entry-exited", exitCode=exit_code, processLogBytes=log.stat().st_size)
    require(exit_code == 0, "real Java start/poll failed; inspect bounded java-process.log and retained task evidence")
    evidence = REPORT / "java"
    observation = json.loads((evidence / "java-observation.json").read_text())
    require(observation.get("realJavaStart") is True and observation.get("javaPollingVerified") is True
            and observation.get("realPostCompletionStop") is True and observation.get("repeatStopVerified") is True
            and observation.get("cancelRecoveryRemainsPending") is True
            and observation.get("freshJvmDiscoveryVerified") is True
            and observation.get("productionAcceptance") is False and observation.get("productRecoveryComplete") is False,
            "Java diagnostic observation boundary differs")
    attempt = observation["attemptId"]
    require(re.fullmatch(r"[a-f0-9-]{36}", attempt), "Java attempt identity is unsafe")
    discovery_path = evidence / "fresh-jvm-discovery.json"
    require(discovery_path.stat().st_size <= 128 * 1024, "fresh-JVM observation exceeds its evidence bound")
    validate_java_discovery_observation(json.loads(discovery_path.read_text()), attempt, observation.get("processId"))
    require(json.loads((evidence / "storage-before-discovery.json").read_text())
            == json.loads((evidence / "storage-after-discovery.json").read_text()), "discovery storage inventory changed")
    journal_path = Path("/lab/java-storage/judge-task10-java-supervisor/control/attempts") / attempt / "runtime-state.json"
    require(journal_path.is_file() and journal_path.stat().st_size <= 65536, "Java runtime journal missing or oversized")
    journal = json.loads(journal_path.read_text())
    require(journal.get("judgeId") == "task10-java-supervisor" and journal.get("attemptId") == attempt
            and journal.get("state") == "CLEANUP_PENDING"
            and journal.get("supervisor", {}).get("state") == "STOPPED", "Java runtime journal identity or pending stop differs")
    for role in ("supervisor", "holder"):
        identity = journal[role]["runtimeId"]
        inspected = json.loads(command(client + ["container", "inspect", identity]))[0]
        if role == "supervisor":
            deadline = time.monotonic() + 15
            while inspected["State"]["Running"]:
                require(time.monotonic() < deadline, "completed Java supervisor did not exit")
                time.sleep(0.1)
                inspected = json.loads(command(client + ["container", "inspect", identity]))[0]
            require(inspected["State"].get("ExitCode") == 0 and inspected["State"].get("Pid") == 0
                    and inspected["State"].get("OOMKilled") is False
                    and type(inspected["State"].get("Error")) is str and inspected["State"]["Error"] == "",
                    "Java supervisor did not exit successfully")
        else:
            require(inspected["State"].get("Running") is True, "Java volume holder exited before evidence capture")
        validate_java_runtime_container(inspected, journal, role)
        (evidence / (role + "-independent-inspect.json")).write_text(json.dumps(inspected, indent=2) + "\n")
    volume = json.loads(command(client + ["volume", "inspect", journal["volume"]["name"]]))[0]
    labels = {"io.webjudge.judge": "task10-java-supervisor", "io.webjudge.attempt": attempt, "io.webjudge.managed": "stage-volume"}
    require(volume.get("Name") == journal["volume"]["name"] and volume.get("Driver") == "local"
            and volume.get("Labels") == labels and volume.get("Mountpoint") == journal["volume"]["mountpoint"]
            and volume.get("Options") == {"type": "tmpfs", "device": "tmpfs", "o": "size=33554432,nodev,nosuid,uid=0,gid=0,mode=0755"},
            "Java task volume inspection differs")
    mountpoint = Path(volume["Mountpoint"])
    require(mountpoint.is_relative_to(Path("/var/lib/wjc/docker/volumes")) and mountpoint.resolve() == mountpoint
            and mountpoint.is_dir() and not any(mountpoint.iterdir()), "Java stage volume is unsafe or retains task artifacts")
    remaining = command(client + ["container", "ls", "--all", "--no-trunc", "--format", "{{.ID}}"]).splitlines()
    require(set(remaining) == {journal[role]["runtimeId"] for role in ("supervisor", "holder")}, "unexpected Java stage container remains")
    require(command(client + ["volume", "ls", "--format", "{{.Name}}"]).splitlines() == [volume["Name"]], "unexpected Java task volume remains")
    (evidence / "volume-independent-inspect.json").write_text(json.dumps(volume, indent=2) + "\n")
    validate_java_bundle(Path("/lab/java-bundle"), lock["manifestSha256"])
    run_java_stage_admission_probe(attempt, evidence)
    connection_failed = run_java_recovery_fault(client, "task10-java-supervisor", attempt,
                                                "completed-stop-unreachable", "stop-unreachable")
    acknowledgement_failed = run_java_recovery_fault(client, "task10-java-supervisor", attempt,
                                                     "completed-supervisor-rm-ack-loss", "supervisor-rm-ack-loss")
    recovered = run_java_confirmed_recovery(client, "task10-java-supervisor", attempt, "completed-recovery")
    retried = run_java_confirmed_recovery(client, "task10-java-supervisor", attempt, "completed-retry")
    require(len({connection_failed, acknowledgement_failed, recovered, retried}) == 4, "runtime failure retry reused a JVM")
    record("java-same-site-runtime-failure-retry-verified", judgeId="task10-java-supervisor", attemptId=attempt,
           connectionFailureProcessId=connection_failed, acknowledgementLossProcessId=acknowledgement_failed,
           recoveryProcessId=recovered, retryProcessId=retried, productRecoveryComplete=False)
    if JAVA_ACTIVE_CANCEL:
        run_java_active_cancel(client)
    else:
        run_java_active_recovery(client)
    record("java-supervisor-entry-verified", attemptId=attempt, summary=observation["summary"], stageCount=observation["stageCount"],
           remainingContainerIds=command(client + ["container", "ls", "--all", "--quiet"]).splitlines(),
           remainingVolumeNames=command(client + ["volume", "ls", "--quiet"]).splitlines(), independentInspectVerified=True,
           realPostCompletionStop=True, repeatStopVerified=True, cancelRecoveryRemainsPending=True,
           freshJvmDiscoveryVerified=True,
           productRecoveryComplete=False, productionAcceptance=False,
           resourceDisposition="containers reclaimed by fresh JVMs; volumes and task evidence retained; domain teardown is not volume recovery",
           scope="real start/poll, completed and active CONFIRMED container reclamation with fresh-JVM retry; no UNKNOWN or task-deletion recovery")


def outer(evidence):
    global REPORT
    evidence = evidence.resolve()
    require(evidence.parent == ACCEPTANCE.resolve(), "evidence must stay in TASK-10")
    require(re.fullmatch((r"JAVA-SUPERVISOR-R" if JAVA_SUPERVISOR else r"RECOVERY-R" if RECOVERY else r"SUPERVISOR-CONTAINER-R" if SUPERVISOR_CONTAINER else r"SUPERVISOR-BUILD-R" if SUPERVISOR_IMAGE else r"SUPERVISOR-R" if SUPERVISOR else r"STAGE-R" if STAGE else r"BUILD-R" if BUILD else r"ENV-R") + r"[0-9]{2}", evidence.name), "invalid evidence directory name")
    evidence.mkdir(exist_ok=False)
    REPORT = evidence
    shutil.copyfile(Path(__file__), evidence / "runtime_preflight.py")
    shutil.copyfile(Path(__file__).with_name("test_runtime_preflight.py"), evidence / "test_runtime_preflight.py")
    shutil.copyfile(Path(__file__).with_name("supervisor_probe.py"), evidence / "supervisor_probe.py")
    if JAVA_SUPERVISOR:
        snapshot_java_bundle(evidence)
    if SUPERVISOR_IMAGE:
        snapshot_supervisor_build(evidence)
    if STAGE:
        shutil.copyfile(Path(__file__).with_name("stage_probe.py"), evidence / "stage_probe.py")
        shutil.copyfile(Path(__file__).with_name("role_probe.py"), evidence / "role_probe.py")
        shutil.copyfile(Path(__file__).with_name("test_role_probe.py"), evidence / "test_role_probe.py")
        shutil.copyfile(Path(__file__).with_name("test_stage_probe.py"), evidence / "test_stage_probe.py")
        product = evidence / "product"
        product.mkdir()
        for filename in ("linux_stage_executor.py", "linux_stage_volume.py", "linux_supervisor.py", "task_runner.py", "runner_manifest.py"):
            shutil.copyfile(REPOSITORY / "runner/scripts" / filename, product / filename)
        snapshots = [evidence / "runtime_preflight.py", evidence / "test_runtime_preflight.py", evidence / "stage_probe.py", evidence / "role_probe.py",
                     evidence / "supervisor_probe.py", evidence / "test_role_probe.py",
                     evidence / "test_stage_probe.py", *sorted(product.iterdir())]
        if RECOVERY:
            for filename in ("stage_recovery_probe.py", "test_stage_recovery_probe.py"):
                shutil.copyfile(Path(__file__).with_name(filename), evidence / filename)
                snapshots.append(evidence / filename)
        (evidence / "source-hashes.json").write_text(json.dumps(
            {str(path.relative_to(evidence)): file_sha256(path) for path in snapshots}, indent=2) + "\n")
    group = Path("/sys/fs/cgroup") / Path(Path("/proc/self/cgroup").read_text().strip().split("::", 1)[1]).relative_to("/")
    require(re.fullmatch((r"wjc-task10-java-supervisor-r" if JAVA_SUPERVISOR else r"wjc-task10-recovery-r" if RECOVERY else r"wjc-task10-supervisor-container-r" if SUPERVISOR_CONTAINER else r"wjc-task10-supervisor-build-r" if SUPERVISOR_IMAGE else r"wjc-task10-supervisor-r" if SUPERVISOR else r"wjc-task10-stage-r" if STAGE else r"wjc-task10-build-r" if BUILD else r"wjc-task10-env-r") + r"[0-9]+[.]service", group.name), "not in the dedicated service")
    limit_values = limits(group)
    unit = command(["systemctl", "show", group.name, "-p", "Id", "-p", "InvocationID", "-p", "RuntimeMaxUSec", "-p", "TimeoutStopUSec",
                    "-p", "Delegate", "-p", "MemoryMax", "-p", "MemorySwapMax", "-p", "CPUQuotaPerSecUSec",
                    "-p", "TasksMax", "-p", "PrivateNetwork", "-p", "ProtectSystem", "-p", "NoNewPrivileges"])
    require(re.search(r"^RuntimeMaxUSec=9min\s*30s$", unit, re.MULTILINE) and "TimeoutStopUSec=30s" in unit
            and "Delegate=yes" in unit, "watchdog or delegation missing")
    require(("PrivateNetwork=no" if BUILD and not OFFLINE_BUILD else "PrivateNetwork=yes") in unit
            and "NoNewPrivileges=yes" in unit, "outer network mode or NNP differs")
    filesystem = json.loads(command(["findmnt", "-J", "-T", "/tmp", "-o", "TARGET,FSTYPE,OPTIONS,SIZE", "-b"]))["filesystems"][0]
    require(filesystem["fstype"] == "tmpfs" and int(filesystem["size"]) <= STORAGE_MAX, "temporary storage is unbounded")
    if STAGE:
        require(int(filesystem["size"]) <= stage_tmpfs_limit_bytes(), "stage temporary filesystem exceeds its mode-specific budget")
        if SUPERVISOR_CONTAINER:
            task_volume_count = 2 if JAVA_SUPERVISOR else 1
            total_storage_budget = stage_tmpfs_limit_bytes() + (3 * 64 + task_volume_count * 32) * 1024 ** 2
            require(total_storage_budget <= STORAGE_MAX, "container supervisor temporary storage exceeds approved total")
            record("supervisor-container-experimental-budget", memoryLimitBytes=MEMORY_MAX,
                   rootTmpfsLimitBytes=stage_tmpfs_limit_bytes(), containerShmCount=3, containerShmBytes=64 * 1024 ** 2,
                   taskVolumeBytes=32 * 1024 ** 2, taskVolumeCount=task_volume_count, totalTemporaryStorageBudgetBytes=total_storage_budget,
                   approvedTemporaryStorageMaxBytes=STORAGE_MAX, newArchiveWrites=False,
                   scope="dedicated container-supervisor experiment only; not production resource defaults")
        archive = REPOSITORY / "target/task10-runtime/BUILD-R05/executor-image.tar"
        require(file_sha256(archive) == STAGE_IMAGE_SHA256, "executor image archive hash changed")
        record("stage-archive-verified", sha256=STAGE_IMAGE_SHA256, bytes=archive.stat().st_size,
               scope="existing archive will be bound read-only; no image build or shared-daemon import")
    if SUPERVISOR_CONTAINER:
        archive = REPOSITORY / "target/task10-runtime/SUPERVISOR-BUILD-R04/supervisor-image.tar"
        require(archive.stat().st_size == 120221696 and file_sha256(archive) == SUPERVISOR_CONTAINER_ARCHIVE_SHA256,
                "supervisor image archive identity changed")
        require({name: file_sha256(evidence / "product" / name) for name in SUPERVISOR_SOURCES}
                == SUPERVISOR_CONTAINER_SOURCE_HASHES, "supervisor image and product snapshot sources differ")
        record("supervisor-container-archive-verified", sha256=SUPERVISOR_CONTAINER_ARCHIVE_SHA256,
               bytes=archive.stat().st_size, imageId=SUPERVISOR_CONTAINER_IMAGE_ID,
               sourceSha256=SUPERVISOR_CONTAINER_SOURCE_HASHES,
               scope="locked dedicated container experiment; not Java integration or production approval")
    record("outer-verified", limits=limit_values, unit=unit, temporaryFilesystem=filesystem,
           entryArguments=sys.argv, kernel=os.uname().release, python=sys.version,
           namespaces=namespaces(), scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
           gitHead=command(["git", "-c", "safe.directory=" + str(REPOSITORY), "-C", str(REPOSITORY), "rev-parse", "HEAD"]))
    parser_tests = Path(__file__).with_name("test_runtime_preflight.py")
    record("preflight-validator-tests", result=json.loads(command(["/usr/bin/python3", "-B", str(parser_tests)])),
           testSha256=hashlib.sha256(parser_tests.read_bytes()).hexdigest())
    if STAGE:
        stage_tests = Path(__file__).with_name("test_stage_probe.py")
        record("stage-validator-tests", result=json.loads(command(["/usr/bin/python3", "-B", str(stage_tests)])),
               testSha256=file_sha256(stage_tests), scope="observation validator contracts, not container security evidence")
        role_tests = Path(__file__).with_name("test_role_probe.py")
        record("role-fixture-tests", result=json.loads(command(["/usr/bin/python3", "-B", str(role_tests)])),
               testSha256=file_sha256(role_tests), scope="static role fixture contracts, not container security evidence")
    if RECOVERY:
        recovery_tests = evidence / "test_stage_recovery_probe.py"
        record("recovery-proxy-contract-tests", result=json.loads(command(["/usr/bin/python3", "-B", str(recovery_tests)])),
               testSha256=file_sha256(recovery_tests), scope="bounded proxy and evidence contracts only; not real daemon recovery evidence")
    lab = Path(tempfile.mkdtemp(prefix="wjc-task10-", dir="/tmp"))
    (lab / "owner").write_text(group.name, encoding="utf-8")
    (lab / "report-path").write_text(str(evidence), encoding="utf-8")
    client = ["docker", "--config", str(lab / "empty-client"), "--host", "unix:///var/run/docker.sock"]
    image = json.loads(command(client + ["image", "inspect", "alpine:3.22.2"]))[0]
    image_id = image["Id"]
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", image_id) and image["Os"] == "linux", "invalid local seed image")
    (lab / "image-id").write_text(image_id, encoding="ascii")
    (lab / "image-metadata.json").write_text(json.dumps({field: image[field] for field in ("RootFS", "Config", "Architecture", "Os")}), encoding="utf-8")
    shared_identity = command(client + ["info", "--format", "{{.ID}}"])
    (lab / "shared-id").write_text(shared_identity, encoding="ascii")
    record("seed-selected", imageId=image_id, repoDigests=image.get("RepoDigests", []),
           sharedDaemonFingerprint=hashlib.sha256(shared_identity.encode()).hexdigest())
    command(client + ["image", "save", "--output", str(lab / "seed.tar"), image_id], timeout=60)
    record("seed-exported", bytes=(lab / "seed.tar").stat().st_size)
    if BUILD:
        require(int(filesystem["size"]) <= 1408 * 1024 ** 2, "build temporary filesystem exceeds 1408MiB")
        output = REPOSITORY / "target/task10-runtime" / evidence.name
        output.mkdir(exist_ok=False)
        (lab / "output-path").write_text(str(output))
    arguments = ["unshare", "--mount", "--cgroup", "--ipc", "--uts"]
    if not BUILD or OFFLINE_BUILD:
        arguments.append("--net")
    arguments.extend(["/usr/bin/python3", "-B", str(Path(__file__).resolve()),
                      phase_mode("namespace"), str(lab)])
    os.execve("/usr/bin/unshare", arguments, ENVIRONMENT)


def namespace(lab):
    global REPORT
    require(lab.parent == Path("/tmp") and lab.name.startswith("wjc-task10-") and not lab.is_symlink(), "invalid lab root")
    REPORT = Path((lab / "report-path").read_text())
    command(["mount", "--make-rprivate", "/"])
    root = lab / "root"
    root.mkdir()
    command(["mount", "--bind", str(root), str(root)])
    for directory in ("etc/docker", "var/lib/wjc", "run/wjc", "tmp", "proc", "sys/fs/cgroup", "dev/pts", "lab", ".old-root"):
        (root / directory).mkdir(parents=True, exist_ok=True)
    command(["mount", "-t", "cgroup2", "-o", "nsdelegate", "cgroup2", str(root / "sys/fs/cgroup")])
    cgroup = root / "sys/fs/cgroup"
    require(Path("/proc/self/cgroup").read_text().strip() == "0::/", "cgroup namespace not rooted at service")
    require(not any((cgroup / name).exists() for name in ("system.slice", "user.slice", "docker")), "host cgroups visible")
    require((cgroup / "cgroup.procs").read_text().split() == [str(os.getpid())], "unexpected process at delegation root")
    (cgroup / "manager").mkdir()
    (cgroup / "manager/cgroup.procs").write_text(str(os.getpid()))
    controllers = (cgroup / "cgroup.controllers").read_text().split()
    require(set(("cpu", "memory", "pids")).issubset(controllers), "required delegated controllers unavailable")
    (cgroup / "cgroup.subtree_control").write_text("+cpu +memory +pids")
    (cgroup / "workloads").mkdir()
    (cgroup / "workloads/cgroup.subtree_control").write_text("+cpu +memory +pids")
    record("delegated-root-verified", limits=limits(cgroup), controllers=controllers, namespaces=namespaces())
    bind(Path("/usr"), root / "usr")
    for directory in ("bin", "sbin", "lib", "lib64"):
        if (Path("/usr") / directory).exists():
            (root / directory).symlink_to("usr/" + directory)
    (root / "etc/passwd").write_text("root:x:0:0:root:/:/bin/sh\nnobody:x:65532:65532:nobody:/:/bin/sh\n")
    (root / "etc/group").write_text("root:x:0:\ndocker:x:65532:\n")
    (root / "etc/nsswitch.conf").write_text("passwd: files\ngroup: files\nhosts: files\n")
    (root / "etc/hosts").write_text("127.0.0.1 localhost\n")
    (root / "etc/resolv.conf").touch()
    (root / "etc/docker/daemon.json").write_text(json.dumps({"features": {"containerd-snapshotter": False}}))
    shutil.copyfile("/etc/os-release", root / "etc/os-release")
    if Path("/etc/ld.so.cache").exists():
        shutil.copyfile("/etc/ld.so.cache", root / "etc/ld.so.cache")
    for device in ("null", "zero", "random", "urandom"):
        bind(Path("/dev") / device, root / "dev" / device, readonly=False)
    os.chmod(root / "tmp", 0o1777)
    (root / "dev/fd").symlink_to("/proc/self/fd")
    bind(lab / "seed.tar", root / "lab/seed.tar")
    bind(REPORT, root / "lab/evidence", readonly=False)
    if JAVA_SUPERVISOR:
        lock = json.loads((REPORT / "java-bundle-lock.json").read_text())
        bundle = Path(lock["bundleDirectory"])
        java_bundle_location(bundle)
        validate_java_bundle(bundle, lock["manifestSha256"])
        require(JAVA_EXECUTABLE.is_file() and JAVA_EXECUTABLE.resolve() == JAVA_EXECUTABLE, "locked JDK executable is unavailable")
        bind(bundle, root / "lab/java-bundle")
        bind(Path("/etc/java-21-openjdk"), root / "etc/java-21-openjdk")
        bind(Path("/etc/ssl/certs/java/cacerts"), root / "etc/ssl/certs/java/cacerts")
        (root / "lab/java-tmp").mkdir(mode=0o700)
        record("java-private-runtime-bound", javaExecutable=str(JAVA_EXECUTABLE), bundleManifestSha256=lock["manifestSha256"],
               configBindings=["/etc/java-21-openjdk", "/etc/ssl/certs/java/cacerts"],
               jnaTemporaryDirectory="/lab/java-tmp", hostHomeBound=False, mavenCacheBound=False)
    if STAGE:
        bind(REPOSITORY / "target/task10-runtime/BUILD-R05/executor-image.tar", root / "lab/executor-image.tar")
        if SUPERVISOR_CONTAINER:
            bind(REPOSITORY / "target/task10-runtime/SUPERVISOR-BUILD-R04/supervisor-image.tar",
                 root / "lab/supervisor-image.tar")
        bind(REPORT / "product", root / "lab/product")
        bind(REPORT / "stage_probe.py", root / "lab/stage_probe.py")
        bind(REPORT / "role_probe.py", root / "lab/role_probe.py")
        bind(REPORT / "supervisor_probe.py", root / "lab/supervisor_probe.py")
        if RECOVERY:
            for filename in ("stage_recovery_probe.py", "test_stage_recovery_probe.py"):
                bind(REPORT / filename, root / "lab" / filename)
    if BUILD:
        bind(Path((lab / "output-path").read_text()), root / "lab/output", readonly=False)
        build_source = REPORT / "supervisor-build" if SUPERVISOR_IMAGE else REPOSITORY / "runner/linux-executor"
        bind(build_source / "Dockerfile", root / "lab/Dockerfile")
        bind(build_source / ".dockerignore", root / "lab/dockerignore")
        if SUPERVISOR_IMAGE:
            bind(build_source / "sources", root / "lab/supervisor-sources")
        if not OFFLINE_BUILD:
            shutil.copyfile("/etc/resolv.conf", root / "etc/resolv.conf")
        if REPLAY_BUILD:
            prior_round = "SUPERVISOR-BUILD-R01" if SUPERVISOR_IMAGE else "BUILD-R01"
            prior_report = ACCEPTANCE / prior_round
            prior_archive = REPOSITORY / "target/task10-runtime" / prior_round / "packages.tar"
            archive_record = next(json.loads(line) for line in (prior_report / "steps.jsonl").read_text().splitlines()
                                  if json.loads(line)["event"] == "packages-archived")
            require(file_sha256(prior_archive) == archive_record["sha256"], "prior package archive hash changed")
            manifest = json.loads((prior_report / "packages.json").read_text())
            restore_packages(prior_archive, manifest, root / "lab/build")
            shutil.copyfile(build_source / "Dockerfile", root / "lab/build/Dockerfile")
            shutil.copyfile(build_source / ".dockerignore", root / "lab/build/.dockerignore")
            (REPORT / "packages.json").write_text(json.dumps(manifest, indent=2) + "\n")
            record("packages-replayed", sourceRound=prior_round, archiveSha256=archive_record["sha256"], packages=len(manifest))
    for filename in ("image-id", "shared-id", "image-metadata.json"):
        shutil.copyfile(lab / filename, root / "lab" / filename)
    shutil.copyfile(Path(__file__), root / "lab/driver.py")
    record("private-root-created", root=str(root), namespaces=namespaces())
    os.execve("/usr/bin/unshare", ["unshare", "--mount", "--pid", "--fork", "--kill-child",
                                  "/usr/bin/python3", "-B", str(Path(__file__).resolve()),
                                  phase_mode("pivot"), str(root)], ENVIRONMENT)


def pivot(root):
    global REPORT
    require(os.getpid() == 1, "private PID namespace not active before pivot")
    require(root.parent.parent == Path("/tmp") and root.parent.name.startswith("wjc-task10-")
            and root.name == "root" and not root.is_symlink(), "invalid private root")
    os.chdir(root)
    command(["pivot_root", ".", ".old-root"])
    os.chroot(".")
    os.chdir("/")
    REPORT = Path("/lab/evidence")
    require(Path("/.old-root").is_dir(), "old root mount absent after pivot")
    command(["mount", "-t", "proc", "proc", "/proc"])
    record("pivot-observation", rootEntries=sorted(entry.name for entry in Path("/").iterdir()),
           mounts=Path("/proc/self/mountinfo").read_text(), namespaces=namespaces())
    command(["umount", "--lazy", "/.old-root"])
    os.rmdir("/.old-root")
    os.execve("/usr/bin/python3", ["python3", "-B", "/lab/driver.py", phase_mode("daemon")], ENVIRONMENT)


def daemon():
    global REPORT
    REPORT = Path("/lab/evidence")
    signal.signal(signal.SIGTERM, lambda signal_number, frame: sys.exit(128 + signal_number))
    require(os.getpid() == 1, "private PID namespace not active")
    require(os.readlink("/proc/self") == "1", "private proc filesystem not mounted")
    require(not Path("/var/run/docker.sock").exists() and not Path("/run/containerd/containerd.sock").exists(), "shared socket visible")
    require(not Path("/root").exists() and not Path("/home").exists() and not Path("/mnt").exists(), "host private tree visible")
    require(Path("/proc/self/cgroup").read_text().strip() == "0::/manager", "manager outside delegated cgroup")
    route_lines = Path("/proc/net/route").read_text().splitlines()
    interface_names = [line.split(":", 1)[0].strip() for line in Path("/proc/net/dev").read_text().splitlines() if ":" in line]
    record("network-observation", routeLines=[line if not line.strip() or line.split()[0] == "Iface"
                                             else line.split()[0] + " <redacted-route-values>" for line in route_lines],
           interfaces=interface_names, namespaces=namespaces())
    if not BUILD or OFFLINE_BUILD:
        validate_network(Path("/proc/net/route").read_text(), Path("/proc/net/dev").read_text(),
                         Path("/proc/net/ipv6_route").read_text())
    else:
        record("trusted-build-network", scope="official APK fetching only; no untrusted sources or attack execution")
    limits(Path("/sys/fs/cgroup"))
    record("private-domain-verified", namespaces=namespaces(), cgroup=Path("/proc/self/cgroup").read_text().strip())
    arguments = ["dockerd", "--config-file=/etc/docker/daemon.json", "--host=unix:///run/wjc/docker.sock",
                 "--data-root=/var/lib/wjc/docker", "--exec-root=/run/wjc/exec", "--pidfile=/run/wjc/dockerd.pid",
                 "--storage-driver=vfs", "--exec-opt=native.cgroupdriver=cgroupfs", "--cgroup-parent=/workloads",
                 "--default-cgroupns-mode=private", "--bridge=none", "--iptables=false", "--ip6tables=false",
                 "--ip-forward=false", "--ip-masq=false", "--userland-proxy=false", "--log-driver=none"]
    command(["dockerd", "--validate", "--config-file=/etc/docker/daemon.json"])
    client = ["docker", "--config", "/run/wjc/client", "--host", "unix:///run/wjc/docker.sock"]
    log = Path("/lab/daemon.log")
    process = None
    container = None
    restart_offline = False
    try:
        with log.open("wb") as output:
            process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                       env=ENVIRONMENT, close_fds=True)
        deadline = time.monotonic() + 30
        while not Path("/run/wjc/docker.sock").exists():
            require(process.poll() is None and time.monotonic() < deadline, "dedicated dockerd failed to start")
            time.sleep(0.1)
        info = json.loads(command(client + ["info", "--format", "{{json .}}"], timeout=15))
        require(info["ID"] != Path("/lab/shared-id").read_text(), "shared daemon selected")
        require(info["DockerRootDir"] == "/var/lib/wjc/docker" and info["CgroupDriver"] == "cgroupfs", "wrong runtime configuration")
        require(info["Containers"] == 0, "dedicated runtime is not empty")
        record("dedicated-runtime-ready", version=info["ServerVersion"], driver=info["Driver"],
               root=info["DockerRootDir"], cgroupDriver=info["CgroupDriver"], daemonPid=process.pid)
        loaded = command(client + ["image", "load", "--input", "/lab/seed.tar"], timeout=60)
        image_ids = set(command(client + ["image", "ls", "--all", "--quiet", "--no-trunc"]).splitlines())
        require(len(image_ids) == 1, "seed import did not produce exactly one image")
        imported = json.loads(command(client + ["image", "inspect", next(iter(image_ids))]))[0]
        image_id = verify_imported_image(json.loads(Path("/lab/image-metadata.json").read_text()), imported)
        record("seed-import-verified", sourceId=Path("/lab/image-id").read_text(), importedId=image_id,
               rootFilesystem=imported["RootFS"], loaderResult=loaded)
        if BUILD:
            build_toolchain_image(client, image_id)
            restart_offline = not OFFLINE_BUILD
            return
        if STAGE:
            require(file_sha256(Path("/lab/executor-image.tar")) == STAGE_IMAGE_SHA256,
                    "bound executor image archive hash changed")
            loaded = command(client + ["image", "load", "--input", "/lab/executor-image.tar"], timeout=120)
            record_image_load_resources("executor")
            executor_image = json.loads(command(client + ["image", "inspect", STAGE_IMAGE_ID]))[0]
            require(executor_image["Id"] == STAGE_IMAGE_ID and executor_image["Os"] == "linux",
                    "executor image identity differs")
            (REPORT / "executor-image-inspect.json").write_text(json.dumps(executor_image, indent=2) + "\n")
            record("stage-executor-image-loaded", imageId=STAGE_IMAGE_ID, archiveSha256=STAGE_IMAGE_SHA256,
                   command=client + ["image", "load", "--input", "/lab/executor-image.tar"], result=loaded)
            if SUPERVISOR_CONTAINER:
                require(file_sha256(Path("/lab/supervisor-image.tar")) == SUPERVISOR_CONTAINER_ARCHIVE_SHA256,
                        "bound supervisor archive identity changed")
                loaded = command(client + ["image", "load", "--input", "/lab/supervisor-image.tar"], timeout=120)
                record_image_load_resources("supervisor")
                supervisor_image = json.loads(command(client + ["image", "inspect", SUPERVISOR_CONTAINER_IMAGE_ID]))[0]
                require(supervisor_image["Id"] == SUPERVISOR_CONTAINER_IMAGE_ID and supervisor_image["Os"] == "linux"
                        and supervisor_image["Config"]["Entrypoint"] == ["python3", "-B", "/opt/web-judge/linux_supervisor.py"],
                        "supervisor container image or entrypoint differs")
                (REPORT / "supervisor-image-inspect.json").write_text(json.dumps(supervisor_image, indent=2) + "\n")
                record("supervisor-container-image-loaded", imageId=SUPERVISOR_CONTAINER_IMAGE_ID,
                       archiveSha256=SUPERVISOR_CONTAINER_ARCHIVE_SHA256,
                       command=client + ["image", "load", "--input", "/lab/supervisor-image.tar"], result=loaded)
            if JAVA_SUPERVISOR:
                run_java_supervisor_probe(client)
            else:
                from stage_probe import run_probe
                run_probe(client, STAGE_IMAGE_ID, REPORT, record, supervisor=SUPERVISOR,
                          supervisor_image=SUPERVISOR_CONTAINER_IMAGE_ID if SUPERVISOR_CONTAINER else None, recovery=RECOVERY)
            return
        container = command(client + ["run", "-d", "--name", "wjc-task10-benign", "--network", "none", "--read-only",
                                      "--user", "65532:65532", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                                      "--memory", "64m", "--memory-swap", "64m", "--cpus", "0.5", "--pids-limit", "32",
                                      "--tmpfs", "/scratch:rw,noexec,nosuid,nodev,size=8m", image_id,
                                      "/bin/sh", "-c", "touch /scratch/allowed && sleep 60"])
        require(re.fullmatch(r"[a-f0-9]{64}", container), "invalid dedicated container ID")
        inspection = json.loads(command(client + ["inspect", container]))[0]
        require(inspection["State"]["Running"], "benign workload failed")
        workload_pid = inspection["State"]["Pid"]
        workload_group = Path("/proc") / str(workload_pid) / "cgroup"
        workload_relative = workload_group.read_text().strip().split("::", 1)[1]
        require(workload_relative.startswith("/workloads/") and ".." not in workload_relative, "workload escaped outer delegated tree")
        workload_path = Path("/sys/fs/cgroup") / workload_relative.removeprefix("/")
        values = {name: (workload_path / name).read_text().strip()
                  for name in ("memory.max", "memory.swap.max", "pids.max", "cpu.max")}
        require(values["memory.max"] == str(64 * 1024 ** 2) and values["memory.swap.max"] == "0", "workload memory limit missing")
        require(values["pids.max"] == "32" and values["cpu.max"] == "50000 100000", "workload PID or CPU limit missing")
        status = dict(line.split(":", 1) for line in (Path("/proc") / str(workload_pid) / "status").read_text().splitlines() if ":" in line)
        workload_pid_namespace = os.readlink("/proc/" + str(workload_pid) + "/ns/pid")
        workload_mount_namespace = os.readlink("/proc/" + str(workload_pid) + "/ns/mnt")
        require(workload_pid_namespace != os.readlink("/proc/self/ns/pid") and status["NSpid"].split()[-1] == "1", "workload PID namespace is shared")
        require(set(status["Uid"].split()) == {"65532"} and int(status["CapEff"], 16) == 0, "workload identity or capabilities unsafe")
        require(status["NoNewPrivs"].strip() == "1" and status["Seccomp"].strip() == "2", "workload NNP or seccomp missing")
        record("workload-limits-verified", cgroup=workload_relative, limits=values, pidNamespace=workload_pid_namespace,
               mountNamespace=workload_mount_namespace,
               uid=status["Uid"].strip(), noNewPrivileges=status["NoNewPrivs"].strip(), seccomp=status["Seccomp"].strip())
        workload_root = Path("/proc") / str(workload_pid) / "root"
        record("init-filesystem-observation",
               scratchExists=(workload_root / "scratch").exists(),
               markerExists=(workload_root / "scratch/allowed").exists(),
               mounts=(Path("/proc") / str(workload_pid) / "mountinfo").read_text(),
               osRelease=(workload_root / "etc/os-release").read_text())
        for observation in (["id"], ["ls", "-ld", "/", "/scratch"],
                            ["ls", "-la", "/scratch"], ["cat", "/etc/os-release"],
                            ["readlink", "/proc/self/ns/mnt"], ["readlink", "/proc/self/ns/pid"]):
            observed = subprocess.run(client + ["exec", container] + observation,
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      text=True, errors="replace", timeout=10, env=ENVIRONMENT, close_fds=True)
            record("exec-filesystem-observation", command=observation, exitCode=observed.returncode,
                   stdout=observed.stdout[:8000], stderr=observed.stderr[:2000])
        exec_namespaces = [command(client + ["exec", container, "readlink", "/proc/self/ns/" + name])
                           for name in ("mnt", "pid")]
        require(exec_namespaces == [workload_mount_namespace, workload_pid_namespace],
                "exec did not enter workload mount and PID namespaces")
        checks = command(client + ["exec", container, "/bin/sh", "-c",
                                   "test -f /scratch/allowed && test ! -e /run/wjc/docker.sock && test ! -e /lab/evidence && test ! -e /mnt/c && test ! -w / && echo BENIGN_ENVIRONMENT_OK"])
        require(checks == "BENIGN_ENVIRONMENT_OK", "benign filesystem or PID namespace check failed")
        record("benign-workload-verified", imageId=image_id, containerId=container, cgroup=workload_relative,
               workloadLimits=values, outerLimits=limits(Path("/sys/fs/cgroup")),
               pidNamespace=workload_pid_namespace, namespacePids=status["NSpid"].split(),
               uid=status["Uid"].strip(), capabilities=status["CapEff"].strip(), seccomp=status["Seccomp"].strip(),
               noNewPrivileges=status["NoNewPrivs"].strip())
        command(client + ["rm", "-f", container])
        container = None
        require(json.loads(command(client + ["info", "--format", "{{json .Containers}}"] )) == 0, "test container remains")
        record("preflight-success", scope="benign dedicated runtime preflight only, not TASK-10 isolation acceptance")
    finally:
        if container is not None and process is not None and process.poll() is None:
            try:
                command(client + ["rm", "-f", container], timeout=10)
            except Exception as exception:
                record("cleanup-container-error", error=str(exception))
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if log.exists():
            with log.open("rb") as source:
                filename = "fetch-daemon-log-prefix.txt" if BUILD and not OFFLINE_BUILD else "daemon-log-prefix.txt"
                (REPORT / filename).write_bytes(source.read(64000))
        record("daemon-stopped", exitCode=None if process is None else process.returncode, offlineBuild=OFFLINE_BUILD)
        if restart_offline:
            require(process.returncode == 0, "fetch daemon did not stop cleanly")
            deadline = time.monotonic() + 10
            while True:
                remaining = []
                for entry in Path("/proc").iterdir():
                    if not entry.name.isdecimal():
                        continue
                    try:
                        name = (entry / "comm").read_text().strip()
                        status_text = (entry / "status").read_text()
                    except FileNotFoundError:
                        continue
                    if (name == "dockerd" or name.startswith("containerd")) and not re.search(r"^State:\s+Z", status_text, re.MULTILINE):
                        remaining.append(entry.name)
                if not remaining:
                    break
                require(time.monotonic() < deadline, "online runtime processes remain before offline build")
                time.sleep(0.1)
            record("online-runtime-stopped-before-offline-build", remainingRuntimeProcesses=remaining)
            os.execve("/usr/bin/unshare", ["unshare", "--net", "/usr/bin/python3", "-B",
                                          "/lab/driver.py", "daemon-supervisor-image-build-offline" if SUPERVISOR_IMAGE
                                          else "daemon-build-offline"], ENVIRONMENT)


def file_sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def restore_packages(archive_path, manifest, context):
    require(isinstance(manifest, list) and 0 < len(manifest) <= 100, "invalid package manifest")
    expected = {}
    for entry in manifest:
        name = entry.get("file", "")
        require(re.fullmatch(r"[A-Za-z0-9+_.-]+[.]apk", name) and name not in expected,
                "invalid or duplicate package name")
        require(re.fullmatch(r"[a-f0-9]{64}", entry.get("sha256", "")) and type(entry.get("bytes")) is int
                and 0 < entry["bytes"] <= 120 * 1024 ** 2, "invalid package manifest values")
        expected[name] = entry
    require(sum(entry["bytes"] for entry in expected.values()) <= 120 * 1024 ** 2, "package manifest exceeds budget")
    require(archive_path.stat().st_size <= 128 * 1024 ** 2, "package archive exceeds budget")
    packages = context / "packages"
    packages.mkdir(parents=True, exist_ok=False)
    seen = set()
    with tarfile.open(archive_path, "r:") as archive:
        for member in archive:
            require(member.name not in seen, "duplicate archive entry")
            seen.add(member.name)
            require(len(seen) <= len(expected) + 2, "unexpected archive entries")
            if member.name.rstrip("/") == "packages" and member.isdir():
                continue
            require(member.isfile() and member.name.startswith("packages/"), "unsafe archive entry")
            name = member.name.removeprefix("packages/")
            if name == "SHA256SUMS":
                require(member.size <= 32768, "oversized archived lock")
                continue
            require(name in expected and member.size == expected[name]["bytes"], "package not in locked archive manifest")
            target = packages / name
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, length=65536)
            require(file_sha256(target) == expected[name]["sha256"], "archived package hash changed")
    require({entry.name for entry in packages.iterdir()} == set(expected), "locked archive is missing packages")
    lock = "".join(expected[name]["sha256"] + "  ./" + name + "\n" for name in sorted(expected))
    (packages / "SHA256SUMS").write_text(lock, encoding="ascii")



def supervisor_image_smoke(client, image):
    require(image["Config"]["Entrypoint"] == ["python3", "-B", "/opt/web-judge/linux_supervisor.py"]
            and image["Config"]["User"] == "root", "supervisor image entrypoint or experimental identity changed")
    common = client + ["run", "--rm", "--network", "none", "--read-only", "--log-driver", "none",
                       "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                       "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5", "--pids-limit", "32"]
    program = """import hashlib, importlib, json, pathlib, shutil, subprocess, sys
root = pathlib.Path('/opt/web-judge')
names = %r
assert {path.name for path in root.iterdir()} == set(names) | {'SHA256SUMS'}
actual = ''.join(hashlib.sha256((root / name).read_bytes()).hexdigest() + '  ./' + name + chr(10) for name in sorted(names))
assert actual == (root / 'SHA256SUMS').read_text()
for name in names:
    assert (root / name).stat().st_mode & 0o222 == 0
    importlib.import_module(name.removesuffix('.py'))
assert root.stat().st_mode & 0o222 == 0
assert all(shutil.which(name) is None for name in ('dockerd', 'gcc', 'g++', 'pip', 'pip3'))
import runner_manifest
print(json.dumps({'python': sys.version, 'dockerCli': subprocess.check_output(['docker', '--version'], text=True).strip(),
                  'sourceSha256': {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names},
                  'capabilities': runner_manifest.CAPABILITIES}))
""" % (SUPERVISOR_SOURCES,)
    smoke_arguments = common + ["--entrypoint", "python3", image["Id"], "-B", "-c", program]
    smoke = json.loads(command(smoke_arguments, timeout=30))
    expected_hashes = {name: file_sha256(Path("/lab/supervisor-sources") / name) for name in SUPERVISOR_SOURCES}
    require(smoke["sourceSha256"] == expected_hashes, "supervisor image source identity changed")
    capabilities_arguments = common + [image["Id"], "--capabilities"]
    capabilities = json.loads(command(capabilities_arguments, timeout=30))
    require(capabilities == smoke["capabilities"], "supervisor CLI capabilities differ from shipped contract")
    reject_arguments = common + [image["Id"]]
    rejected = subprocess.run(reject_arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, errors="replace", timeout=30,
                              env=ENVIRONMENT, close_fds=True)
    require(rejected.returncode == 30 and not rejected.stdout
            and rejected.stderr.strip() == "supervisor configuration or execution rejected",
            "supervisor default invocation did not fail closed")
    record("supervisor-toolchain-smoke-verified", command=smoke_arguments, result=smoke,
           capabilitiesCommand=capabilities_arguments, capabilities=capabilities,
           rejectedCommand=reject_arguments, rejectedExitCode=rejected.returncode,
           rejectedStderr=rejected.stderr,
           scope="toolchain and immutable source identity only; no socket, Java integration or isolation acceptance")


def export_toolchain_image(client, image, seed_id, filename):
    require(filename == "supervisor-image.tar", "unsupported supervisor archive name")
    require(json.loads(command(client + ["info", "--format", "{{json .Containers}}"])) == 0,
            "build containers remain before export")
    output = Path("/lab/output") / filename
    command(["prlimit", "--fsize=402653184:402653184"] + client +
            ["image", "save", "--output", str(output), image["Id"]], timeout=120)
    require(output.stat().st_size <= 384 * 1024 ** 2, "supervisor image export exceeded budget")
    (REPORT / "supervisor-image-inspect.json").write_text(json.dumps(image, indent=2) + "\n")
    record("trusted-supervisor-build-success", imageId=image["Id"], imageBytes=image["Size"],
           archiveName=filename, archiveBytes=output.stat().st_size, archiveSha256=file_sha256(output),
           sourceSeedId=seed_id, sourceSha256={name: file_sha256(Path("/lab/supervisor-sources") / name)
                                             for name in SUPERVISOR_SOURCES},
           scope="supervisor toolchain build only; deployment identity, resources, Java and isolation not accepted")


def package_fetch_arguments(client, seed_id):
    arguments = client + ["run", "--rm", "--name", "wjc-task10-package-fetch", "--network", "host",
                         "--read-only", "--user", "65532:65532", "--cap-drop", "ALL",
                         "--security-opt", "no-new-privileges", "--memory", "256m", "--memory-swap", "256m",
                         "--cpus", "0.5", "--pids-limit", "32", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m",
                         "--mount", "type=bind,source=/lab/build/packages,target=/packages", seed_id,
                         "apk", "--no-cache", "--repositories-file", "/dev/null", "--repository",
                         "https://dl-cdn.alpinelinux.org/alpine/v3.22/main"]
    if SUPERVISOR_IMAGE:
        arguments.extend(["--repository", "https://dl-cdn.alpinelinux.org/alpine/v3.22/community"])
    return arguments + ["fetch", "--recursive", "--output", "/packages"] + (
        ["python3", "docker-cli"] if SUPERVISOR_IMAGE else ["g++"])


def build_toolchain_image(client, seed_id):
    require(BUILD, "image building requires explicit trusted mode")
    context = Path("/lab/build")
    packages = context / "packages"
    if not OFFLINE_BUILD:
        packages.mkdir(parents=True)
        packages.chmod(0o777)
        shutil.copyfile("/lab/Dockerfile", context / "Dockerfile")
        shutil.copyfile("/lab/dockerignore", context / ".dockerignore")
        fetch_arguments = package_fetch_arguments(client, seed_id)
        record("official-package-fetch-start", command=fetch_arguments,
               scope="trusted official tooling only; host networking is not approved for untrusted programs")
        fetched = command(fetch_arguments, timeout=120)
        package_files = sorted(packages.iterdir())
        require(0 < len(package_files) <= 100, "unexpected package count")
        require(all(path.is_file() and not path.is_symlink() and path.suffix == ".apk" for path in package_files),
                "unexpected downloaded package entry")
        require(sum(path.stat().st_size for path in package_files) <= 120 * 1024 ** 2, "packages exceed download budget")
        package_manifest = [{"file": path.name, "bytes": path.stat().st_size, "sha256": file_sha256(path)}
                            for path in package_files]
        (REPORT / "packages.json").write_text(json.dumps(package_manifest, indent=2) + "\n")
        record("official-packages-fetched", result=fetched, packages=package_manifest)
        lock = "".join(entry["sha256"] + "  ./" + entry["file"] + "\n" for entry in package_manifest)
        (packages / "SHA256SUMS").write_text(lock, encoding="ascii")
        (REPORT / "SHA256SUMS").write_text(lock, encoding="ascii")
        package_archive = Path("/lab/output/packages.tar")
        command(["prlimit", "--fsize=134217728:134217728", "tar", "-cf", str(package_archive),
                 "-C", str(context), "packages"], timeout=30)
        record("packages-archived", bytes=package_archive.stat().st_size, sha256=file_sha256(package_archive))
        return
    if SUPERVISOR_IMAGE:
        copy_supervisor_sources(Path("/lab/supervisor-sources"), context / "sources")
    image_tag = "wjc-task10-supervisor:local" if SUPERVISOR_IMAGE else "wjc-task10-executor:local"
    arguments = client + ["build", "--network", "none", "--pull=false", "--no-cache", "--build-arg",
                          "BASE_IMAGE=" + seed_id, "--tag", image_tag, str(context)]
    built = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           text=True, errors="replace", timeout=180, close_fds=True,
                           env={**ENVIRONMENT, "DOCKER_BUILDKIT": "0"})
    record("offline-image-build", command=arguments, exitCode=built.returncode,
           stdout=built.stdout[-24000:], stderr=built.stderr[-6000:],
           temporaryDiskUsedBytes=shutil.disk_usage("/").used,
           outerMemoryCurrent=Path("/sys/fs/cgroup/memory.current").read_text().strip(),
           outerMemoryPeak=Path("/sys/fs/cgroup/memory.peak").read_text().strip(),
           dockerfileSha256=file_sha256(context / "Dockerfile"))
    require(built.returncode == 0, "offline toolchain image build failed")
    image = json.loads(command(client + ["image", "inspect", image_tag]))[0]
    image_id = image["Id"]
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", image_id), "invalid compiler image ID")
    if SUPERVISOR_IMAGE:
        supervisor_image_smoke(client, image)
        export_toolchain_image(client, image, seed_id, "supervisor-image.tar")
        return
    program = Path("/lab/hello.cpp")
    program.write_text('#include <iostream>\nint main() { std::cout << "TASK10_CXX17_OK\\n"; }\n')
    smoke_arguments = client + ["run", "--rm", "--name", "wjc-task10-compiler-smoke", "--network", "none",
                                "--read-only", "--user", "65532:65532", "--cap-drop", "ALL",
                                "--security-opt", "no-new-privileges", "--memory", "256m", "--memory-swap", "256m",
                                "--cpus", "0.5", "--pids-limit", "32", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m",
                                "--tmpfs", "/scratch:rw,exec,nosuid,nodev,size=32m,uid=65532,gid=65532,mode=0700", "--mount",
                                "type=bind,source=/lab/hello.cpp,target=/input.cpp,readonly", image_id,
                                "/bin/sh", "-c", "g++ /input.cpp -std=c++17 -O2 -o /scratch/hello && /scratch/hello"]
    smoke = command(smoke_arguments, timeout=60)
    require(smoke == "TASK10_CXX17_OK", "C++17 compiler smoke failed")
    record("compiler-smoke-verified", command=smoke_arguments, output=smoke,
           scope="trusted fixed source compile/run only; not role isolation acceptance")
    require(json.loads(command(client + ["info", "--format", "{{json .Containers}}"] )) == 0,
            "build containers remain before export")
    output = Path("/lab/output/executor-image.tar")
    command(["prlimit", "--fsize=402653184:402653184"] + client +
            ["image", "save", "--output", str(output), image_id], timeout=120)
    require(output.stat().st_size <= 384 * 1024 ** 2, "compiler image export exceeded budget")
    record("trusted-build-success", imageId=image_id, imageBytes=image["Size"],
           archiveBytes=output.stat().st_size, archiveSha256=file_sha256(output),
           sourceSeedId=seed_id, compilerSourceSha256=file_sha256(program),
           scope="image build and trusted smoke only; no control-plane security acceptance")



if __name__ == "__main__":
    try:
        if sys.argv[1] == "java-stage-admission-child":
            require(len(sys.argv) == 3, "stage admission child requires exactly one verified attempt")
            run_java_stage_admission_rejections(sys.argv[2])
            sys.exit(0)
        mode = configure_mode(sys.argv[1])
        if mode == "outer":
            outer(Path(sys.argv[2]))
        elif mode == "namespace":
            namespace(Path(sys.argv[2]))
        elif mode == "daemon":
            daemon()
        elif mode == "pivot":
            pivot(Path(sys.argv[2]))
        else:
            raise RuntimeError("unsupported preflight mode")
    except Exception as exception:
        if REPORT is not None:
            record("preflight-failure", error=str(exception))
        raise
