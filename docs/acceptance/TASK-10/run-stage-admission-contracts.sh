#!/usr/bin/env bash
set -euo pipefail

test "$#" = 2 -o "$#" = 3
source_root=$1
evidence_root=$2
mode=${3:-runner}
case "$mode" in runner|validator) ;; *) exit 2 ;; esac
test "$(realpath "$source_root")" = /mnt/c/Users/29901/Documents/dp/web-judge-c
test "$(dirname "$evidence_root")" = "$source_root/docs/acceptance/TASK-10"
[[ "$(basename "$evidence_root")" =~ ^STAGE-ADMISSION-R[0-9]{2}$ ]]
test ! -e "$evidence_root"
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
test "$(findmnt -b -n -o SIZE -T /tmp)" = 2147483648
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
cgroup_root="/sys/fs/cgroup$cgroup_path"
unit=$(basename "$cgroup_path")
[[ "$unit" =~ ^wjc-task10-stage-admission-r[0-9]+[.]service$ ]]
test "$(cat "$cgroup_root/memory.max")" = 4294967296
test "$(cat "$cgroup_root/memory.swap.max")" = 0
test "$(cat "$cgroup_root/pids.max")" = 512
test "$(cat "$cgroup_root/cpu.max")" = "200000 100000"
test "$(awk '$1 == "NoNewPrivs:" {print $2}' /proc/self/status)" = 1
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
test -z "$(ip route show)"
findmnt -n -o VFS-OPTIONS -T "$source_root" | tr , '\n' | grep -qx ro
unit_properties=$(systemctl show "$unit" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p TimeoutStopUSec -p PrivateNetwork -p ProtectSystem -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths)
grep -qx 'RuntimeMaxUSec=3min' <<< "$unit_properties"
grep -qx 'TimeoutStopUSec=30s' <<< "$unit_properties"
grep -qx 'PrivateNetwork=yes' <<< "$unit_properties"
mkdir "$evidence_root"
printf '%s\n' "$unit_properties" > "$evidence_root/unit-properties.txt"
snapshot=$(mktemp -d /tmp/wjc-stage-admission.XXXXXX)
git -c "safe.directory=$source_root" -C "$source_root" rev-parse HEAD > "$evidence_root/tested-head.txt"
git -c "safe.directory=$source_root" -C "$source_root" ls-files -z --cached --others --exclude-standard -- runner > "$snapshot/source-files.txt"
python3 - "$source_root" "$snapshot" "$evidence_root" "$mode" <<'PY'
import hashlib
import json
import pathlib
import shutil
import sys

source, snapshot, evidence = map(pathlib.Path, sys.argv[1:4])
hashes = {}
entries = set((snapshot / 'source-files.txt').read_bytes().split(b'\0')) - {b''}
if sys.argv[4] == 'validator':
    entries.update(b'docs/acceptance/TASK-10/' + name for name in (b'runtime_preflight.py', b'test_runtime_preflight.py', b'supervisor_probe.py'))
for raw in sorted(entries):
    relative = pathlib.PurePosixPath(raw.decode('utf-8'))
    original = source / relative
    if relative.is_absolute() or '..' in relative.parts or not original.is_file() or original.resolve() != original:
        raise RuntimeError('unsafe frozen source path')
    for base in (snapshot, evidence / 'sources'):
        target = base / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
    hashes[str(relative)] = hashlib.sha256((snapshot / relative).read_bytes()).hexdigest()
    if hashlib.sha256(original.read_bytes()).hexdigest() != hashes[str(relative)] or hashlib.sha256((evidence / 'sources' / relative).read_bytes()).hexdigest() != hashes[str(relative)]:
        raise RuntimeError('source changed during snapshot capture')
(evidence / 'source-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
shutil.copyfile(source / 'docs/acceptance/TASK-10/run-stage-admission-contracts.sh', evidence / 'run-stage-admission-contracts.sh')
PY
cd "$snapshot"
set +e
if [ "$mode" = validator ]; then
    (ulimit -f 32768; timeout --signal=TERM --kill-after=10s 120s python3 -B docs/acceptance/TASK-10/test_runtime_preflight.py > "$evidence_root/python-tests.log" 2>&1)
else
    (ulimit -f 32768; timeout --signal=TERM --kill-after=10s 120s python3 -B runner/tests/run_contract_suite.py "$evidence_root/summary.json" > "$evidence_root/python-tests.log" 2>&1)
fi
python_exit=$?
set -e
printf '%s\n' "$python_exit" > "$evidence_root/python-exit.txt"
python3 - "$evidence_root" "$python_exit" "$mode" <<'PY'
import json
import pathlib
import sys

folder = pathlib.Path(sys.argv[1])
path = folder / 'summary.json'
if sys.argv[3] == 'validator':
    lines = (folder / 'python-tests.log').read_text().splitlines()
    observed = json.loads(lines[-1]) if lines and lines[-1].startswith('{') else {'tests': None, 'summaryMissing': True}
    path.write_text(json.dumps({'command': ['python3', '-B', 'docs/acceptance/TASK-10/test_runtime_preflight.py'],
                                'exit_code': int(sys.argv[2]), **observed}, indent=2) + '\n')
summary = json.loads(path.read_text()) if path.is_file() else {'tests': None, 'summaryMissing': True}
print(json.dumps({'processExitCode': int(sys.argv[2]), **{key: value for key, value in summary.items() if key not in ('command', 'nonpassing')}}))
PY
exit "$python_exit"
