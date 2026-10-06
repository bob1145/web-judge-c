import hashlib
import json
import os
import re
import shutil
import stat
import uuid
from pathlib import Path


ROLE_ORDER = ("GENERATOR", "USER", "ORACLE")
SOURCES = {
    "generator.cpp": '#include <iostream>\nint main() { std::cout << "42\\n"; }\n',
    "user.cpp": '#include <iostream>\nint main() { int value; if (!(std::cin >> value)) return 2; std::cout << value << "\\n"; }\n',
    "oracle.cpp": '#include <iostream>\nint main() { int value; if (!(std::cin >> value)) return 2; std::cout << value << "\\n"; }\n',
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def file_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def supervisor_config(config, attempt):
    return {"configVersion": 1, "attemptId": attempt,
            "runtime_command": config.runtime_command, "runtime_endpoint": config.runtime_endpoint,
            "executor_image": config.executor_image, "stage_volume": config.stage_volume,
            "stage_root": str(config.stage_root), "uid": config.uid, "gid": config.gid,
            "pids_limit": config.pids_limit, "cpus": config.cpus,
            "input_limit_bytes": config.input_limit_bytes, "artifact_limit_bytes": config.artifact_limit_bytes,
            "command_timeout_seconds": config.command_timeout_seconds,
            "volume_limit_bytes": config.volume_limit_bytes, "volume_holder_id": config.volume_holder_id}


def verify_journals(directory, attempt, volume):
    paths = sorted(directory.glob("*.json"), key=lambda path: path.stat().st_mtime_ns)
    require(len(paths) == 6, "supervisor must produce exactly six stage journals")
    timestamps = [path.stat().st_mtime_ns for path in paths]
    require(all(previous < following for previous, following in zip(timestamps, timestamps[1:])),
            "journal completion order cannot be inferred unambiguously")
    results = []
    for path in paths:
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 128 * 1024,
                "unsafe supervisor stage journal")
        journal = json.loads(path.read_text())
        stage = journal["stage"]
        require(str(uuid.UUID(stage)) == stage and path.name == stage + ".json", "journal stage identity differs")
        require(journal.get("state") == "CLEANED" and journal.get("attempt") == attempt
                and journal.get("volume") == volume and journal.get("name") == "wjc-stage-" + stage,
                "supervisor journal ownership or cleanup state differs")
        identity = journal.get("containerId", "")
        require(re.fullmatch(r"[a-f0-9]{64}", identity) is not None, "supervisor journal lacks a complete container ID")
        require(journal.get("labels") == {"io.webjudge.attempt": attempt, "io.webjudge.stage": stage,
                "io.webjudge.role": journal["role"], "io.webjudge.managed": "stage"}, "supervisor stage labels differ")
        require(journal.get("outcome") == {"status": "SUCCESS", "exitCode": 0, "oomKilled": False},
                "supervisor stage did not complete successfully")
        results.append({"filename": path.name, "mtimeNs": path.stat().st_mtime_ns,
                        "sha256": file_hash(path), "journal": journal})
    expected = [(role, "compile") for role in ROLE_ORDER] + [(role, "execute") for role in ROLE_ORDER]
    require([(entry["journal"]["role"], entry["journal"]["phase"]) for entry in results] == expected,
            "supervisor journal role/phase completion sequence differs")
    require(len({entry["journal"]["containerId"] for entry in results}) == 6, "supervisor stages reused a container identity")
    return results


def supervisor_container_command(client, image_id, root, config, attempt, case, arguments):
    require(client[-2:] == ["--host", "unix:///run/wjc/docker.sock"], "supervisor requires the dedicated socket")
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", image_id) is not None, "supervisor image must be immutable")
    require(str(uuid.UUID(attempt)) == attempt and case in {"missing-field", "wrong-expected-owner",
            "config-group-world-writable", "control-parent-group-writable", "valid"}, "invalid supervisor experiment identity")
    require(root == Path("/lab/stage-control/supervisor-task"), "supervisor task mount must be the new experiment root")
    require(config.runtime_command == "/usr/bin/docker" and config.runtime_endpoint == "unix:///run/wjc/docker.sock",
            "supervisor runtime command or socket differs")
    require(config.stage_volume == "wjc-task10-stage-" + attempt
            and config.stage_root == Path("/var/lib/wjc/docker/volumes") / config.stage_volume / "_data",
            "supervisor stage mount must match the dedicated volume mountpoint")
    attempt_dir = root / "control/attempts" / attempt
    require(len(arguments) == 7 and arguments[:5] == [str(attempt_dir / "task-spec.json"), "--work-root", str(root),
            "--stage-config", str(attempt_dir / "supervisor-config.json")]
            and arguments[5] == "--config-owner-uid" and arguments[6] in {"0", "1"},
            "unexpected supervisor experiment arguments")
    name = "wjc-task10-supervisor-" + attempt + "-" + case
    labels = {"io.webjudge.attempt": attempt, "io.webjudge.managed": "supervisor-probe", "io.webjudge.probe": case}
    command = client + ["container", "create", "--name", name, "--network", "none", "--ipc", "private",
                        "--cgroupns", "private", "--read-only", "--user", "0:0", "--cap-drop", "ALL",
                        "--security-opt", "no-new-privileges", "--memory", "256m", "--memory-swap", "256m",
                        "--pids-limit", "32", "--cpus", "0.5", "--log-driver", "none",
                        "--mount", "type=bind,source=" + str(root) + ",target=" + str(root),
                        "--mount", "type=volume,source=" + config.stage_volume + ",target=" + str(config.stage_root) + ",volume-nocopy",
                        "--mount", "type=bind,source=/run/wjc/docker.sock,target=/run/wjc/docker.sock"]
    for key, value in labels.items():
        command.extend(["--label", key + "=" + value])
    return command + [image_id, *arguments], name, labels


def validate_supervisor_container(inspection, identity, name, labels, image_id, root, config, arguments):
    require(re.fullmatch(r"[a-f0-9]{64}", identity) is not None and inspection.get("Id") == identity,
            "supervisor container identity differs")
    require(inspection.get("Name") == "/" + name and inspection.get("Image") == image_id,
            "supervisor container name or image differs")
    settings = inspection.get("Config", {})
    require(settings.get("Labels") == labels and settings.get("User") == "0:0"
            and settings.get("Entrypoint") == ["python3", "-B", "/opt/web-judge/linux_supervisor.py"]
            and settings.get("Cmd") == arguments, "supervisor ownership, identity or entrypoint differs")
    host = inspection.get("HostConfig", {})
    require(host.get("Privileged") is False and host.get("ReadonlyRootfs") is True
            and host.get("NetworkMode") == "none" and host.get("PidMode") == ""
            and host.get("IpcMode") == "private" and host.get("CgroupnsMode") == "private",
            "supervisor root or namespace isolation differs")
    require(host.get("Memory") == 256 * 1024 ** 2 and host.get("MemorySwap") == 256 * 1024 ** 2
            and host.get("PidsLimit") == 32 and host.get("NanoCpus") == 500000000,
            "supervisor resource budget differs")
    require(host.get("CapDrop") == ["ALL"] and not host.get("CapAdd")
            and "no-new-privileges" in host.get("SecurityOpt", []) and host.get("LogConfig", {}).get("Type") == "none"
            and not host.get("Devices") and not host.get("Binds") and not host.get("Tmpfs"),
            "supervisor privileges, logging or implicit mounts differ")
    mounts = inspection.get("Mounts", [])
    expected = {str(root): ("bind", str(root)), str(config.stage_root): ("volume", str(config.stage_root)),
                "/run/wjc/docker.sock": ("bind", "/run/wjc/docker.sock")}
    require(len(mounts) == 3 and {entry.get("Destination") for entry in mounts} == set(expected),
            "supervisor has unexpected mounts")
    for mount in mounts:
        require((mount.get("Type"), mount.get("Source")) == expected[mount["Destination"]]
                and mount.get("RW") is True, "supervisor mount source or access differs")
        if mount["Type"] == "volume":
            require(mount.get("Name") == config.stage_volume, "supervisor mounted an unexpected volume")


def invoke_supervisor_container(client, image_id, root, config, attempt, case, arguments, timeout, evidence, invoke, record):
    create, name, labels = supervisor_container_command(client, image_id, root, config, attempt, case, arguments)
    evidence.mkdir(exist_ok=True)
    identity = None
    cleanup_required = False

    def inspect_owned(reference, absent_allowed=False):
        inspected = invoke(client + ["container", "inspect", reference], check=False)
        if inspected.returncode != 0:
            missing = {"Error: No such object: " + reference, "Error: No such container: " + reference,
                       "Error response from daemon: No such container: " + reference}
            require(absent_allowed and inspected.stderr.decode().strip() in missing,
                    "cannot establish supervisor container cleanup ownership")
            return None
        payload = json.loads(inspected.stdout)
        require(isinstance(payload, list) and len(payload) == 1, "unexpected supervisor container inspection")
        current = payload[0]
        current_id = current.get("Id", "")
        require(re.fullmatch(r"[a-f0-9]{64}", current_id) is not None
                and current.get("Name") == "/" + name and current.get("Config", {}).get("Labels") == labels
                and (identity is None or current_id == identity), "supervisor cleanup identity or ownership differs")
        return current

    try:
        record("supervisor-container-create-intent", case=case, command=create, name=name, labels=labels,
               scope="explicit root-only trusted experiment; no production identity or Java integration claim")
        cleanup_required = True
        created = invoke(create, timeout=30)
        returned_identity = created.stdout.decode().strip()
        require(re.fullmatch(r"[a-f0-9]{64}", returned_identity) is not None, "create did not return a full supervisor ID")
        identity = returned_identity
        before = inspect_owned(identity)
        (evidence / (case + "-before.json")).write_text(json.dumps(before, indent=2) + "\n")
        validate_supervisor_container(before, identity, name, labels, image_id, root, config, arguments)
        require(before.get("State", {}).get("Status") == "created", "supervisor unexpectedly ran before attach")
        attached_command = client + ["container", "start", "--attach", identity]
        attached = invoke(attached_command, timeout=timeout, check=False)
        after = inspect_owned(identity)
        (evidence / (case + "-after.json")).write_text(json.dumps(after, indent=2) + "\n")
        (evidence / (case + "-stdout.txt")).write_bytes(attached.stdout)
        (evidence / (case + "-stderr.txt")).write_bytes(attached.stderr)
        validate_supervisor_container(after, identity, name, labels, image_id, root, config, arguments)
        state = after.get("State", {})
        require(state.get("Running") is False and state.get("Status") == "exited" and state.get("OOMKilled") is False
                and state.get("ExitCode") == attached.returncode, "supervisor attach and terminal state differ")
        record("supervisor-container-exited", case=case, containerId=identity, command=attached_command,
               imageId=image_id, state=state, labels=labels, stdout=attached.stdout.decode("utf-8", errors="replace"),
               stderr=attached.stderr.decode("utf-8", errors="replace"))
        return attached
    finally:
        if cleanup_required:
            current = inspect_owned(identity if identity and re.fullmatch(r"[a-f0-9]{64}", identity) else name,
                                    absent_allowed=True)
            if current is not None:
                (evidence / (case + "-cleanup.json")).write_text(json.dumps(current, indent=2) + "\n")
                invoke(client + ["container", "rm", "--force", current["Id"]], timeout=30)
                require(inspect_owned(current["Id"], absent_allowed=True) is None, "supervisor remains after cleanup")
                record("supervisor-container-cleaned", case=case, containerId=current["Id"], labels=labels)


def run_supervisor_probe(config, attempt, control, report, client, invoke, record, supervisor_image=None):
    config_owner_uid = os.geteuid()
    require(config_owner_uid == 0, "this experiment requires its established trusted root supervisor identity")
    root = control / "supervisor-task"
    root.mkdir(mode=0o700)
    evidence = report / "supervisor"
    evidence.mkdir()
    (evidence / "sources").mkdir()
    (evidence / "stage-journal").mkdir()
    attempt_dir = root / "control/attempts" / attempt
    attempt_dir.mkdir(parents=True, mode=0o700)
    spec_path = attempt_dir / "task-spec.json"
    config_path = attempt_dir / "supervisor-config.json"
    event_path = attempt_dir / "runner-events.jsonl"
    summary_path = attempt_dir / "runner-summary.json"
    journal_root = attempt_dir / "stage-journal"
    spec = {"protocolVersion": 2, "judgeId": "task10-supervisor-cli", "attemptId": attempt,
            "comparison": {"mode": "LEGACY_TEXT", "precision": "0"},
            "sourcePaths": {role: role.lower() + ".cpp" for role in ROLE_ORDER},
            "testCases": 1, "caseTimeLimitMillis": 10000, "maxTaskRuntimeMillis": 120000,
            "memoryLimitBytes": 128 * 1024 ** 2, "maxOutputBytesPerCase": 4096,
            "stopOnFirstNonAc": False,
            "retention": {"completedMillis": 60000, "failedMillis": 60000, "cancelledMillis": 60000}}
    spec_path.write_text(json.dumps(spec, indent=2) + "\n")
    for filename, source in SOURCES.items():
        (root / filename).write_text(source, encoding="utf-8")
        (evidence / "sources" / filename).write_text(source, encoding="utf-8")
    audit_path = root / "events.jsonl"
    audit_path.write_text("".join(json.dumps({"role": role, "state": "CONTROL_SENTINEL_UNCHANGED"}) + "\n"
                                  for role in (*ROLE_ORDER, "SPECIAL_JUDGE")))
    metadata = root / "metadata.json"
    metadata.write_text('{"owner":"trusted-supervisor","state":"fixed"}\n')
    final_path = root / "final.json"
    final_path.write_text('{"trustedFinal":true,"ac":0}\n')
    immutable = [spec_path, audit_path, metadata, final_path, *(root / name for name in SOURCES),
                 *(Path("/lab/product") / name for name in ("linux_supervisor.py", "linux_stage_executor.py",
                                                           "linux_stage_volume.py", "task_runner.py", "runner_manifest.py"))]
    before_hashes = {str(path): file_hash(path) for path in immutable}
    (evidence / "hashes-before.json").write_text(json.dumps(before_hashes, indent=2) + "\n")
    complete_config = supervisor_config(config, attempt)
    require(len(complete_config) == 16, "supervisor fixture omitted explicit configuration fields")
    incomplete_config = {name: value for name, value in complete_config.items() if name != "runtime_endpoint"}
    config_path.write_text(json.dumps(complete_config, indent=2) + "\n")
    config_path.chmod(0o600)
    base_command = ["/usr/bin/python3", "-B", "/lab/product/linux_supervisor.py", str(spec_path),
                    "--work-root", str(root), "--stage-config", str(config_path)]
    command = base_command + ["--config-owner-uid", str(config_owner_uid)]
    trusted_paths = [root, root / "control", root / "control/attempts", attempt_dir, config_path]

    def launch(command, case, timeout):
        if supervisor_image is None:
            return invoke(command, timeout=timeout, check=False)
        require(command[:3] == ["/usr/bin/python3", "-B", "/lab/product/linux_supervisor.py"],
                "unexpected host CLI prefix for container experiment")
        return invoke_supervisor_container(client, supervisor_image, root, config, attempt, case,
                                           command[3:], timeout, evidence / "container-runs", invoke, record)

    def only_holder_remains():
        identities = invoke(client + ["container", "ls", "--all", "--quiet", "--no-trunc"]).stdout.decode().split()
        require(identities == [config.volume_holder_id], "supervisor left an unexpected container")
        holder = json.loads(invoke(client + ["container", "inspect", config.volume_holder_id]).stdout)[0]
        require(holder.get("Id") == config.volume_holder_id and holder.get("State", {}).get("Running") is True,
                "supervisor volume holder is not running")
        labels = holder.get("Config", {}).get("Labels", {})
        require(labels.get("io.webjudge.attempt") == attempt and labels.get("io.webjudge.managed") == "stage-volume-holder",
                "supervisor holder ownership changed")
        require(list(config.stage_root.iterdir()) == [], "supervisor left task-volume content")
        return identities

    def path_metadata(path):
        metadata = path.lstat()
        kind = "directory" if stat.S_ISDIR(metadata.st_mode) else "file" if stat.S_ISREG(metadata.st_mode) else "other"
        result = {"uid": metadata.st_uid, "gid": metadata.st_gid,
                  "mode": format(stat.S_IMODE(metadata.st_mode), "04o"), "kind": kind}
        if kind == "file":
            require(metadata.st_size <= 4 * 1024 ** 2, "configuration rejection evidence file exceeded its bound")
            result.update({"bytes": metadata.st_size, "sha256": file_hash(path)})
        return result

    def rejection_snapshot():
        paths = sorted(root.rglob("*"))
        require(len(paths) <= 256, "configuration rejection inventory exceeded its bound")
        return {"metadata": {str(path): path_metadata(path) for path in trusted_paths},
                "taskInventory": {str(path.relative_to(root)): path_metadata(path) for path in paths},
                "hashes": {str(path): file_hash(path) for path in [*immutable, config_path]},
                "containerIds": invoke(client + ["container", "ls", "--all", "--quiet", "--no-trunc"]).stdout.decode().split(),
                "taskVolumeEntries": sorted(path.name for path in config.stage_root.iterdir())}

    def reject_configuration(name, payload, expected_owner_uid, changed_mode=None):
        config_path.write_text(json.dumps(payload, indent=2) + "\n")
        original_mode = None
        target = None
        if changed_mode is not None:
            target, mode = changed_mode
            require(target in trusted_paths and target.is_relative_to(root) and not target.is_symlink(),
                    "permission experiment must stay within its newly created trusted paths")
            original_mode = stat.S_IMODE(target.stat().st_mode)
        try:
            if target is not None:
                target.chmod(mode)
            shutil.copyfile(config_path, evidence / (name + "-config.json"))
            before = rejection_snapshot()
            require(before["containerIds"] == [config.volume_holder_id] and before["taskVolumeEntries"] == [],
                    "configuration rejection did not start with only the holder")
            (evidence / (name + "-before.json")).write_text(json.dumps(before, indent=2) + "\n")
            rejected_command = base_command + ["--config-owner-uid", str(expected_owner_uid)]
            rejected = launch(rejected_command, name, timeout=30)
            (evidence / (name + "-cli-stdout.txt")).write_bytes(rejected.stdout)
            (evidence / (name + "-cli-stderr.txt")).write_bytes(rejected.stderr)
            after = rejection_snapshot()
            (evidence / (name + "-after.json")).write_text(json.dumps(after, indent=2) + "\n")
            record("supervisor-invalid-config-result", case=name,
                   command=(["python3", "-B", "/opt/web-judge/linux_supervisor.py", *rejected_command[3:]]
                            if supervisor_image else rejected_command),
                   commandScope="container process" if supervisor_image else "host process", exitCode=rejected.returncode,
                   stdout=rejected.stdout.decode("utf-8", errors="replace"), stderr=rejected.stderr.decode("utf-8", errors="replace"),
                   expectedConfigOwnerUid=expected_owner_uid, actualConfigOwnerUid=config_path.stat().st_uid,
                   before=before, after=after, scope="configuration integrity only; no confidentiality claim")
            require(rejected.returncode == 30 and rejected.stdout == b""
                    and rejected.stderr == b"supervisor configuration or execution rejected\n",
                    "invalid configuration metadata did not fail closed through the real CLI")
            require(not (root / "workspace").exists() and not event_path.exists() and not summary_path.exists()
                    and not journal_root.exists(), "invalid supervisor configuration produced execution side effects")
            require(before == after, "rejected supervisor changed configuration metadata, inventory, hashes or resources")
            require(before_hashes == {str(path): file_hash(path) for path in immutable}, "rejected supervisor changed trusted inputs")
            only_holder_remains()
            record("supervisor-invalid-config-fail-closed", case=name, workspaceExists=False, eventFileExists=False,
                   summaryFileExists=False, journalExists=False, taskVolumeEmpty=True, remainingContainerIds=[config.volume_holder_id],
                   scope="configuration integrity only; no confidentiality claim")
        finally:
            if target is not None:
                target.chmod(original_mode)
                require(stat.S_IMODE(target.stat().st_mode) == original_mode, "new experiment path mode was not restored")
                record("supervisor-config-mode-restored", case=name, path=str(target), metadata=path_metadata(target))

    initial_metadata = {str(path): path_metadata(path) for path in trusted_paths}
    require(all(details["uid"] == config_owner_uid and not int(details["mode"], 8) & 0o022
                for details in initial_metadata.values()), "configuration rejection baseline is not trusted")
    require(config.uid != config_owner_uid, "experimental workload shares the trusted configuration owner")
    (evidence / "initial-config-metadata.json").write_text(json.dumps(initial_metadata, indent=2) + "\n")
    try:
        only_holder_remains()
        reject_configuration("missing-field", incomplete_config, config_owner_uid)
        reject_configuration("wrong-expected-owner", complete_config, config_owner_uid + 1)
        reject_configuration("config-group-world-writable", complete_config, config_owner_uid, (config_path, 0o666))
        reject_configuration("control-parent-group-writable", complete_config, config_owner_uid, (root / "control", 0o775))
        config_path.write_text(json.dumps(complete_config, indent=2) + "\n")
        shutil.copyfile(config_path, evidence / "supervisor-config-valid.json")
        valid_metadata = {str(path): path_metadata(path) for path in trusted_paths}
        require(valid_metadata == initial_metadata, "configuration metadata was not restored before the valid CLI")
        (evidence / "valid-config-metadata.json").write_text(json.dumps(valid_metadata, indent=2) + "\n")
        record("supervisor-valid-config-metadata", configOwnerUid=config_owner_uid, paths=valid_metadata,
               scope="explicit experimental owner and integrity metadata; no production UID default or confidentiality claim")
        valid_config_hash = file_hash(config_path)
        completed = launch(command, "valid", timeout=180)
        (evidence / "valid-cli-stdout.txt").write_bytes(completed.stdout)
        (evidence / "valid-cli-stderr.txt").write_bytes(completed.stderr)
        record("supervisor-valid-config-result",
               command=(["python3", "-B", "/opt/web-judge/linux_supervisor.py", *command[3:]] if supervisor_image else command),
               commandScope="container process" if supervisor_image else "host process", exitCode=completed.returncode,
               stdout=completed.stdout.decode("utf-8", errors="replace"), stderr=completed.stderr.decode("utf-8", errors="replace"),
               configSha256=valid_config_hash,
               evidenceBoundary="CLI process streams only; no per-stage inspect or compiler/program stdout observation")
        require(completed.returncode == 0, "real supervisor CLI did not complete successfully")
        require(file_hash(config_path) == valid_config_hash, "supervisor configuration changed during execution")
        summary = json.loads(summary_path.read_text())
        require(summary["totalCases"] == 1 and summary["completedCases"] == 1 and summary["ac"] == 1,
                "supervisor did not produce the expected one-case AC")
        require(all(summary[key] == 0 for key in ("wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")),
                "supervisor summary contains a failure")
        events = [json.loads(line) for line in event_path.read_text().splitlines()]
        require([event["type"] for event in events] == ["COMPILE_STARTED", "COMPILE_FINISHED", "RUN_STARTED",
                "RUN_FINISHED", "SUMMARY", "COMPLETED"], "supervisor emitted an unexpected trusted event sequence")
        require(events[-2]["summary"] == summary and all(event["judgeId"] == "task10-supervisor-cli" for event in events),
                "supervisor trusted event identity or summary differs")
        require(all((root / name).read_bytes() == b"42\n" for name in ("1.in", "1.out", "1.ans")),
                "supervisor case artifacts differ from fixed input/output")
        journals = verify_journals(journal_root, attempt, config.stage_volume)
        (evidence / "journal-order.json").write_text(json.dumps(journals, indent=2) + "\n")
        after_hashes = {str(path): file_hash(path) for path in immutable}
        require(after_hashes == before_hashes, "supervisor changed host audit, fixed controls, sources or product tools")
        require(len(audit_path.read_text().splitlines()) == 4, "supervisor changed the four-line host audit")
        require(not (attempt_dir / "cancel.requested").exists(), "supervisor unexpectedly created cancellation state")
        remaining = only_holder_remains()
        (evidence / "hashes-after.json").write_text(json.dumps(after_hashes, indent=2) + "\n")
        record("supervisor-cli-verified", attempt=attempt, exitCode=completed.returncode, summary=summary,
               supervisorMode="container" if supervisor_image else "host-cli", supervisorImage=supervisor_image,
               stageContainerIds=[entry["journal"]["containerId"] for entry in journals],
               stageStates=[entry["journal"]["state"] for entry in journals], remainingContainerIds=remaining,
               journalOrderBasis="strictly increasing final journal mtime_ns from serialized real product stages",
               auditUnchanged=True, taskVolumeEmpty=True, executorImage=config.executor_image,
               evidenceBoundary="six actual product journal results; fixed-image and volume enforcement are performed by the unmodified product, not substituted observers",
               scope="real supervisor CLI configuration rejection and one-case success; no crash/restart lifecycle acceptance")
    finally:
        for path in [spec_path, config_path, audit_path, metadata, final_path, event_path, summary_path,
                     root / "1.in", root / "1.out", root / "1.ans"]:
            if path.is_file() and not path.is_symlink():
                require(path.stat().st_size <= 128 * 1024, "supervisor evidence file exceeds its bound")
                shutil.copyfile(path, evidence / path.name)
        if journal_root.is_dir():
            for path in journal_root.glob("*.json"):
                require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 128 * 1024,
                        "supervisor journal evidence is unsafe")
                shutil.copyfile(path, evidence / "stage-journal" / path.name)
        final_hashes = {str(path): file_hash(path) if path.is_file() else None for path in immutable}
        (evidence / "hashes-final.json").write_text(json.dumps(final_hashes, indent=2) + "\n")
