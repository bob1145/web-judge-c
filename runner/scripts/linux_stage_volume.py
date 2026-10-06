import json
import os
import re
import stat
import uuid
from pathlib import Path

from linux_stage_executor import StageSecurityError, open_directory


def _require(condition):
    if not condition:
        raise StageSecurityError("stage volume verification failed")


def _options(value):
    _require(isinstance(value, str) and bool(value))
    result = {}
    for item in value.split(","):
        name, separator, option = item.partition("=")
        _require(bool(name) and name not in result)
        result[name] = option if separator else None
    return result


def _inspect(control, kind, identity):
    result = control(kind, "inspect", identity)
    _require(result.returncode == 0)
    payload = json.loads(result.stdout)
    _require(isinstance(payload, list) and len(payload) == 1 and isinstance(payload[0], dict))
    return payload[0]


def _mount_path(value):
    return re.sub(r"\\(040|011|012|134)", lambda match: chr(int(match.group(1), 8)), value)


def _verify_mount(root, volume_limit_bytes):
    matches = []
    for line in Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines():
        fields, separator, filesystem = line.partition(" - ")
        _require(bool(separator))
        fields = fields.split()
        filesystem = filesystem.split()
        _require(len(fields) >= 6 and len(filesystem) == 3)
        mountpoint = _mount_path(fields[4])
        if root == mountpoint or root.startswith(mountpoint.rstrip("/") + "/"):
            matches.append((mountpoint, fields, filesystem))
    _require(bool(matches))
    longest = max(len(entry[0]) for entry in matches)
    matches = [entry for entry in matches if len(entry[0]) == longest]
    _require(len(matches) == 1)
    mountpoint, fields, filesystem = matches[0]
    _require(mountpoint == root and filesystem[0] == "tmpfs" and _mount_path(fields[3]) == "/")
    mount_options = _options(fields[5])
    super_options = _options(filesystem[2])
    _require(all(name in mount_options and mount_options[name] is None for name in ("rw", "nosuid", "nodev")))
    _require("ro" not in mount_options and "ro" not in super_options and "rw" in super_options)
    size = super_options.get("size", "")
    _require(isinstance(size, str) and re.fullmatch(r"[1-9][0-9]*[kKmMgG]?", size) is not None)
    multiplier = {"k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}
    actual_size = int(size[:-1]) * multiplier[size[-1].lower()] if size[-1].isalpha() else int(size)
    _require(actual_size == volume_limit_bytes)


def verify_stage_volume(config, attempt_id, volume_limit_bytes, holder_id, control):
    """Verify an existing bounded volume without creating or cleaning resources.

    The trusted supervisor must share the daemon's mount namespace and access
    its exact Mountpoint as config.stage_root; arbitrary path mappings are not
    supported. A separately isolated trusted holder must keep the volume mounted
    throughout execution. This point-in-time check is not lifecycle recovery and
    does not replace the executor's private namespace and subpath restrictions.
    """
    try:
        _require(os.name == "posix")
        _require(type(volume_limit_bytes) is int and volume_limit_bytes > 0)
        _require(isinstance(attempt_id, str) and str(uuid.UUID(attempt_id)) == attempt_id)
        _require(isinstance(holder_id, str) and re.fullmatch(r"[0-9a-f]{64}", holder_id) is not None)
        root = str(config.stage_root)
        _require(config.stage_root.is_absolute() and root == os.path.normpath(root) and root != "/")
        volume = _inspect(control, "volume", config.stage_volume)
        _require(volume.get("Name") == config.stage_volume and volume.get("Driver") == "local"
                 and volume.get("Scope") == "local" and volume.get("Mountpoint") == root)
        labels = volume.get("Labels", {})
        _require(labels.get("io.webjudge.attempt") == attempt_id
                 and labels.get("io.webjudge.managed") == "stage-volume")
        options = volume.get("Options", {})
        _require(options.get("type") == "tmpfs" and options.get("device") == "tmpfs")
        mount_options = _options(options.get("o"))
        expected = {"size": str(volume_limit_bytes), "nodev": None, "nosuid": None,
                    "uid": "0", "gid": "0", "mode": "0755"}
        _require(mount_options == expected or mount_options == {**expected, "rw": None})
        holder = _inspect(control, "container", holder_id)
        _require(holder.get("Id") == holder_id and holder.get("State", {}).get("Running") is True)
        labels = holder.get("Config", {}).get("Labels", {})
        _require(labels.get("io.webjudge.attempt") == attempt_id
                 and labels.get("io.webjudge.managed") == "stage-volume-holder")
        host = holder.get("HostConfig", {})
        _require(host.get("Privileged") is False and host.get("NetworkMode") == "none"
                 and host.get("PidMode") == "" and host.get("IpcMode") == "private")
        mounts = holder.get("Mounts")
        _require(isinstance(mounts, list) and len(mounts) == 1)
        mount = mounts[0]
        _require(mount.get("Type") == "volume" and mount.get("Name") == config.stage_volume
                 and mount.get("Source") == root and mount.get("Destination") == "/stage-root"
                 and mount.get("RW") is True)
        descriptor = open_directory(config.stage_root)
        try:
            info = os.fstat(descriptor)
            _require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o755
                     and info.st_uid == 0 and info.st_gid == 0)
            _verify_mount(root, volume_limit_bytes)
            capacity = os.fstatvfs(descriptor)
            _require(capacity.f_frsize > 0 and capacity.f_blocks > 0
                     and capacity.f_frsize * capacity.f_blocks <= volume_limit_bytes)
        finally:
            os.close(descriptor)
    except Exception:
        raise StageSecurityError("stage volume verification failed") from None
