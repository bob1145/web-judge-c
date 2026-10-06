import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import uuid
from pathlib import Path


ENVIRONMENT = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "HOME": "/nonexistent", "LANG": "C.UTF-8"}
VOLUME_BYTES = 32 * 1024 ** 2
SOURCE = r'''#include <cerrno>
#include <fcntl.h>
#include <iostream>
#include <unistd.h>
int main() {
    if (getuid() != 65532 || getgid() != 65532) return 11;
    int scratch = open("/scratch/probe.txt", O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (scratch < 0) return 12;
    if (write(scratch, "ok", 2) != 2 || close(scratch) != 0) return 13;
    errno = 0;
    int input = open("/input/program", O_WRONLY | O_APPEND);
    if (input >= 0 || (errno != EROFS && errno != EACCES)) return 14;
    for (const char* path : {"/lab", "/run/wjc/docker.sock", "/input/source.cpp"}) {
        errno = 0;
        if (access(path, F_OK) == 0 || errno != ENOENT) return 15;
    }
    std::cout << "TASK10_SEPARATE_STAGE_OK\n";
}
'''


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def file_sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_volume(volume, name, labels):
    require(volume.get("Name") == name and volume.get("Driver") == "local", "unexpected stage volume identity")
    require(volume.get("Labels") == labels, "stage volume ownership differs")
    options = volume.get("Options", {})
    require(options.get("type") == "tmpfs" and options.get("device") == "tmpfs", "stage volume is not tmpfs")
    require(set(options.get("o", "").split(",")) == {"size=33554432", "uid=0", "gid=0", "mode=0755", "nodev", "nosuid"},
            "stage volume options differ")
    mountpoint = Path(volume.get("Mountpoint", ""))
    require(mountpoint == Path("/var/lib/wjc/docker/volumes") / name / "_data", "stage volume mountpoint differs")
    return mountpoint


def validate_stage_inspection(inspection, identity, attempt, volume, memory_limit_bytes=256 * 1024 ** 2):
    require(inspection.get("Id") == identity, "stage inspection identity differs")
    labels = inspection.get("Config", {}).get("Labels", {})
    require(labels.get("io.webjudge.attempt") == attempt and labels.get("io.webjudge.managed") == "stage",
            "stage inspection ownership differs")
    require(inspection["Config"].get("User") == "65532:65532", "stage UID differs")
    host = inspection.get("HostConfig", {})
    require(host.get("ReadonlyRootfs") is True and host.get("NetworkMode") == "none", "stage root or network differs")
    require(host.get("Memory") == memory_limit_bytes and host.get("MemorySwap") == memory_limit_bytes,
            "stage memory limits differ")
    require(host.get("PidsLimit") == 32 and host.get("NanoCpus") == 500000000, "stage CPU or PID limit differs")
    require(host.get("CapDrop") == ["ALL"] and "no-new-privileges" in host.get("SecurityOpt", []),
            "stage capabilities or NNP differ")
    configured_mounts = host.get("Mounts", [])
    require(len(configured_mounts) == 2 and {mount.get("Target") for mount in configured_mounts} == {"/input", "/scratch"}
            and all(mount.get("VolumeOptions", {}).get("NoCopy") is True for mount in configured_mounts),
            "stage volume copy-up must be disabled")
    mounts = inspection.get("Mounts", [])
    require(len(mounts) == 2 and {mount.get("Destination") for mount in mounts} == {"/input", "/scratch"},
            "stage has unexpected mounts")
    for mount in mounts:
        require(mount.get("Type") == "volume" and mount.get("Name") == volume, "stage mounted unexpected volume")
        require(mount.get("RW") is (mount["Destination"] == "/scratch"), "stage mount writability differs")


def finish_resource_cleanup(domain_stop_required, cleanup, record, attempt):
    if domain_stop_required:
        record("stage-resource-cleanup-skipped-for-domain-stop", attempt=attempt, requiresDomainStop=True,
               resourcesVerifiedRemoved=False,
               scope="recovery did not complete; skip stage, holder and volume deletion and terminate the dedicated runtime domain")
        return False
    cleanup()
    return True


def run_probe(client, image_id, report, record, supervisor=False, supervisor_image=None, recovery=False):
    require(client[-2:] == ["--host", "unix:///run/wjc/docker.sock"], "stage probe requires the dedicated socket")
    require(not recovery or (not supervisor and supervisor_image is None), "recovery must use its independent stage-only mode")
    sys.path.insert(0, "/lab/product")
    from linux_stage_executor import LinuxStageConfig, LinuxStageExecutor
    from task_runner import run_process

    hashes = json.loads((report / "source-hashes.json").read_text())
    for filename in ("linux_stage_executor.py", "linux_stage_volume.py", "linux_supervisor.py", "task_runner.py", "runner_manifest.py"):
        require(file_sha256(Path("/lab/product") / filename) == hashes["product/" + filename],
                "product source snapshot hash differs")
    require(file_sha256(Path("/lab/stage_probe.py")) == hashes["stage_probe.py"], "probe source snapshot hash differs")
    require(file_sha256(Path("/lab/role_probe.py")) == hashes["role_probe.py"], "role probe source snapshot hash differs")
    require(file_sha256(Path("/lab/supervisor_probe.py")) == hashes["supervisor_probe.py"], "supervisor probe source snapshot hash differs")
    if recovery:
        for filename in ("stage_recovery_probe.py", "test_stage_recovery_probe.py"):
            require(file_sha256(Path("/lab") / filename) == hashes[filename], "recovery probe snapshot hash differs")
    attempt = str(uuid.uuid4())
    volume_name = "wjc-task10-stage-" + attempt
    holder_name = "wjc-task10-holder-" + attempt
    volume_labels = {"io.webjudge.attempt": attempt, "io.webjudge.managed": "stage-volume"}
    holder_labels = {"io.webjudge.attempt": attempt, "io.webjudge.managed": "stage-volume-holder"}
    control = Path("/lab/stage-control")
    control.mkdir(mode=0o700)
    journal = control / "journal"
    journal.mkdir(mode=0o700)
    evidence_journal = report / "stage-journal"
    evidence_journal.mkdir()
    source = control / "benign.cpp"
    source.write_text(SOURCE, encoding="utf-8")
    (report / "benign.cpp").write_text(SOURCE, encoding="utf-8")
    artifact = control / "program"
    cancel = control / "cancel.requested"
    active = True
    observed = []
    stage_results = []
    journal_hashes = {}
    volume_intent = False
    holder_intent = False
    domain_stop_required = False

    def persist_journals():
        for path in journal.glob("*.json"):
            require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 128 * 1024,
                    "unexpected stage journal entry")
            digest = file_sha256(path)
            if journal_hashes.get(path.name) != digest:
                payload = json.loads(path.read_text())
                temporary = evidence_journal / (path.name + ".tmp")
                shutil.copyfile(path, temporary)
                os.replace(temporary, evidence_journal / path.name)
                record("stage-journal-observation", journal=payload, sha256=digest)
                journal_hashes[path.name] = digest

    def audit(event, arguments):
        if active and event == "subprocess.Popen":
            persist_journals()
            record("stage-subprocess", executable=str(arguments[0]), command=[str(value) for value in arguments[1]])

    sys.addaudithook(audit)

    def invoke(arguments, timeout=20, check=True):
        result = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, env=ENVIRONMENT, close_fds=True)
        require(len(result.stdout) <= 256 * 1024 and len(result.stderr) <= 256 * 1024, "probe command output exceeded limit")
        record("stage-command-result", command=arguments, exitCode=result.returncode,
               stdout=result.stdout.decode("utf-8", errors="replace"), stderr=result.stderr.decode("utf-8", errors="replace"))
        require(not check or result.returncode == 0, "stage probe command failed")
        return result

    def inspect_container(identity):
        payload = json.loads(invoke(client + ["container", "inspect", identity]).stdout)
        require(isinstance(payload, list) and len(payload) == 1, "unexpected container inspection")
        return payload[0]

    def owned_container(identity, labels, absent_allowed=False):
        result = invoke(client + ["container", "inspect", identity], check=False)
        if result.returncode != 0:
            error = result.stderr.decode("utf-8", errors="replace").strip()
            require(absent_allowed and error in ("Error: No such container: " + identity,
                    "Error response from daemon: No such container: " + identity,
                    "Error: No such object: " + identity), "cannot establish probe container ownership")
            return None
        payload = json.loads(result.stdout)
        require(isinstance(payload, list) and len(payload) == 1, "unexpected cleanup inspection")
        container = payload[0]
        require(all(container.get("Config", {}).get("Labels", {}).get(key) == value for key, value in labels.items()),
                "probe container ownership differs")
        require(re.fullmatch(r"[a-f0-9]{64}", container.get("Id", "")), "invalid probe container ID")
        return container

    def attached_process(**arguments):
        command = arguments["command"]
        require(command[-5:-1] == ["container", "start", "--attach", "--interactive"], "unexpected product attach command")
        identity = command[-1]
        before = inspect_container(identity)
        phase = "compile" if before["Config"].get("Cmd", [None])[0] == "g++" else "execute"
        memory_limit = (256 if len(observed) < 2 or phase == "compile" else 128) * 1024 ** 2
        validate_stage_inspection(before, identity, attempt, volume_name, memory_limit)
        index = len(observed) + 1
        (report / ("stage-" + str(index) + "-before.json")).write_text(json.dumps(before, indent=2) + "\n")
        outcome = run_process(**arguments)
        after = inspect_container(identity)
        validate_stage_inspection(after, identity, attempt, volume_name, memory_limit)
        (report / ("stage-" + str(index) + "-after.json")).write_text(json.dumps(after, indent=2) + "\n")
        require(after["State"]["Running"] is False, "benign product stage remains running")
        observed.append(identity)
        stage_results.append({"containerId": identity, "phase": phase,
                              "role": before["Config"]["Labels"]["io.webjudge.role"],
                              "status": outcome.status, "exitCode": outcome.exit_code,
                              "stdout": outcome.stdout.decode("utf-8", errors="replace"),
                              "stderr": outcome.stderr.decode("utf-8", errors="replace")})
        record("stage-process-outcome", containerId=identity, status=outcome.status, exitCode=outcome.exit_code,
               stdout=outcome.stdout.decode("utf-8", errors="replace"), stderr=outcome.stderr.decode("utf-8", errors="replace"),
               elapsedMs=outcome.elapsed_ms, peakMemoryKb=outcome.peak_memory_kb,
               memoryMeasurementScope="attach CLI only; not a workload memory measurement")
        return outcome

    try:
        require(invoke(client + ["container", "ls", "--all", "--quiet"]).stdout.strip() == b"", "runtime is not empty before stage probe")
        require(invoke(client + ["volume", "ls", "--quiet"]).stdout.strip() == b"", "runtime has volumes before stage probe")
        volume_command = client + ["volume", "create", "--driver", "local", "--opt", "type=tmpfs", "--opt", "device=tmpfs",
                                   "--opt", "o=size=33554432,uid=0,gid=0,mode=0755,nodev,nosuid"]
        for key, value in volume_labels.items():
            volume_command.extend(["--label", key + "=" + value])
        volume_intent = True
        invoke(volume_command + [volume_name])
        volume = json.loads(invoke(client + ["volume", "inspect", volume_name]).stdout)[0]
        mountpoint = validate_volume(volume, volume_name, volume_labels)
        (report / "volume-inspect.json").write_text(json.dumps(volume, indent=2) + "\n")
        holder_command = client + ["container", "create", "--name", holder_name, "--network", "none", "--ipc", "private", "--read-only",
                                   "--user", "65532:65532", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                                   "--memory", "32m", "--memory-swap", "32m", "--cpus", "0.1", "--pids-limit", "8",
                                   "--log-driver", "none", "--mount", "type=volume,source=" + volume_name + ",target=/stage-root,volume-nocopy"]
        for key, value in holder_labels.items():
            holder_command.extend(["--label", key + "=" + value])
        holder_intent = True
        invoke(holder_command + [image_id, "/bin/sleep", "570"])
        holder = owned_container(holder_name, holder_labels)
        invoke(client + ["container", "start", holder["Id"]])
        holder = owned_container(holder_name, holder_labels)
        require(holder["State"]["Running"] is True, "volume holder is not running")
        (report / "holder-inspect.json").write_text(json.dumps(holder, indent=2) + "\n")
        filesystem = json.loads(invoke(["findmnt", "-J", "-T", str(mountpoint), "-o", "TARGET,FSTYPE,OPTIONS,SIZE", "-b"]).stdout)["filesystems"][0]
        require(filesystem["target"] == str(mountpoint) and filesystem["fstype"] == "tmpfs"
                and int(filesystem["size"]) == VOLUME_BYTES, "stage volume is not actually bounded tmpfs")
        require({"nodev", "nosuid"}.issubset(set(filesystem["options"].split(","))), "stage tmpfs safety options missing")
        require("noexec" not in filesystem["options"].split(","), "stage executable inputs cannot use a noexec volume")
        metadata = mountpoint.stat()
        require(metadata.st_uid == 0 and metadata.st_gid == 0 and stat.S_IMODE(metadata.st_mode) == 0o755,
                "stage tmpfs root permissions differ")
        record("stage-volume-mount-verified", attempt=attempt, volume=volume_name, filesystem=filesystem,
               scope="volume is executable for read-only program inputs; no scratch noexec claim")
        config = LinuxStageConfig(runtime_command="/usr/bin/docker", runtime_endpoint="unix:///run/wjc/docker.sock",
                                  executor_image=image_id, stage_volume=volume_name, stage_root=mountpoint,
                                  uid=65532, gid=65532, pids_limit=32, cpus=0.5, input_limit_bytes=128 * 1024,
                                  artifact_limit_bytes=4 * 1024 ** 2, command_timeout_seconds=20,
                                  volume_limit_bytes=VOLUME_BYTES, volume_holder_id=holder["Id"])
        executor = LinuxStageExecutor(config, attempt, journal, attached_process)
        stage_limits = {"stdin": b"", "timeout_seconds": 60, "memory_limit_bytes": 256 * 1024 ** 2,
                        "output_limit_bytes": 64 * 1024, "cancel_path": cancel}
        compiled = executor.compile("USER", source, artifact, **stage_limits)
        require(compiled.status == "SUCCESS" and compiled.exit_code == 0 and artifact.is_file(), "separate compilation failed")
        require(len(observed) == 1, "compile did not use exactly one product stage")
        require(owned_container(observed[0], {"io.webjudge.attempt": attempt}, absent_allowed=True) is None,
                "compiler container remains before executable handoff")
        require(list(mountpoint.iterdir()) == [], "compiler stage files remain after product cleanup")
        compiled_journals = [json.loads(path.read_text()) for path in journal.glob("*.json")]
        require(len(compiled_journals) == 1 and compiled_journals[0]["state"] == "CLEANED", "compile journal did not reach CLEANED")
        record("stage-artifact-handoff-verified", compilerContainerId=observed[0], artifactBytes=artifact.stat().st_size,
               artifactSha256=file_sha256(artifact), compilerRemoved=True,
               implementation="LinuxStageExecutor.compile exports the regular file only after container removal")
        executed = executor.execute("USER", artifact, [], {}, **{**stage_limits, "timeout_seconds": 10})
        require(executed.status == "SUCCESS" and executed.exit_code == 0
                and executed.stdout == b"TASK10_SEPARATE_STAGE_OK\n", "separate execution or benign boundary assertions failed")
        require(len(observed) == 2 and observed[0] != observed[1], "compile and execute did not use distinct containers")
        require(list(mountpoint.iterdir()) == [], "product stage files remain after execution")
        for identity in observed:
            require(owned_container(identity, {"io.webjudge.attempt": attempt}, absent_allowed=True) is None,
                    "product stage container remains")
        record("stage-benign-success", attempt=attempt, stageContainerIds=observed, output=executed.stdout.decode(),
               outerMemoryPeakBytes=int(Path("/sys/fs/cgroup/memory.peak").read_text()),
               scope="real product backend compile/export/separate execute and benign boundary assertions only; not complete TASK-10 isolation acceptance")
        if supervisor:
            from supervisor_probe import run_supervisor_probe
            run_supervisor_probe(config, attempt, control, report, client, invoke, record, supervisor_image=supervisor_image)
            require(len(observed) == 2, "supervisor CLI was incorrectly represented as an observed in-process stage")
            require(list(mountpoint.iterdir()) == [], "supervisor stage files remain")
            record("stage-supervisor-round-success", attempt=attempt, directlyObservedBaselineStages=observed,
                   supervisorImage=supervisor_image, supervisorMode="container" if supervisor_image else "host-cli",
                   outerMemoryPeakBytes=int(Path("/sys/fs/cgroup/memory.peak").read_text()),
                   scope="two directly observed baseline stages plus six CLI stages evidenced by product journals; no CLI per-stage inspect or stdout claim")
            return
        from role_probe import run_role_probe
        run_role_probe(executor, control, attempt, report, record, stage_results)
        require(len(observed) == 10 and len(set(observed)) == 10, "role probe did not use ten distinct product stages")
        require(list(mountpoint.iterdir()) == [], "role probe stage files remain")
        for identity in observed:
            require(owned_container(identity, {"io.webjudge.attempt": attempt}, absent_allowed=True) is None,
                    "role probe container remains")
        record("stage-role-round-success", attempt=attempt, stageContainerIds=observed,
               outerMemoryPeakBytes=int(Path("/sys/fs/cgroup/memory.peak").read_text()),
               scope="LEGACY_TEXT real TaskRunner plus separate SPJ backend probe; not full TASK-10 lifecycle acceptance")
        if recovery:
            domain_stop_required = True
            from stage_recovery_probe import run_recovery_probe
            run_recovery_probe(config, attempt, control, report, client, invoke, record)
            require(len(observed) == 10 and list(mountpoint.iterdir()) == [],
                    "recovery probe started an extra observed workload or retained task-volume content after successful teardown")
            record("stage-recovery-round-success", attempt=attempt, directlyObservedStages=observed,
                   outerMemoryPeakBytes=int(Path("/sys/fs/cgroup/memory.peak").read_text()),
                   scope="ten normal stages plus one late create without start; unknown journal remains; cleanup is experimental, not product recovery")
            domain_stop_required = False
    finally:
        try:
            def cleanup_owned_resources():
                stage_ids = invoke(client + ["container", "ls", "--all", "--quiet", "--no-trunc", "--filter",
                                            "label=io.webjudge.attempt=" + attempt, "--filter", "label=io.webjudge.managed=stage"]).stdout.decode().split()
                for identity in stage_ids:
                    container = owned_container(identity, {"io.webjudge.attempt": attempt, "io.webjudge.managed": "stage"})
                    invoke(client + ["container", "rm", "--force", container["Id"]])
                if holder_intent:
                    holder = owned_container(holder_name, holder_labels, absent_allowed=True)
                    if holder is not None:
                        invoke(client + ["container", "rm", "--force", holder["Id"]])
                if volume_intent:
                    existing = invoke(client + ["volume", "ls", "--quiet", "--filter", "name=" + volume_name]).stdout.decode().split()
                    require(set(existing).issubset({volume_name}), "volume cleanup lookup was not exact")
                    if volume_name in existing:
                        volume = json.loads(invoke(client + ["volume", "inspect", volume_name]).stdout)[0]
                        validate_volume(volume, volume_name, volume_labels)
                        invoke(client + ["volume", "rm", volume_name])
                require(invoke(client + ["container", "ls", "--all", "--quiet"]).stdout.strip() == b"", "dedicated containers remain")
                require(invoke(client + ["volume", "ls", "--quiet"]).stdout.strip() == b"", "dedicated volumes remain")
                record("stage-resources-cleaned", attempt=attempt, remainingContainers=[], remainingVolumes=[])

            finish_resource_cleanup(domain_stop_required, cleanup_owned_resources, record, attempt)
        finally:
            persist_journals()
            active = False
