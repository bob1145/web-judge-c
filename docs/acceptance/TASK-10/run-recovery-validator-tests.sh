#!/usr/bin/env bash
set -euo pipefail
test "$#" = 2
source_root=$1
evidence_root=$2
test "$(realpath "$source_root")" = /mnt/c/Users/29901/Documents/dp/web-judge-c
test "$(dirname "$evidence_root")" = "$source_root/docs/acceptance/TASK-10"
[[ "$(basename "$evidence_root")" =~ ^VALIDATOR-FAULT-R[0-9]{2}$ ]]
test ! -e "$evidence_root"
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
test "$(cat "/sys/fs/cgroup$cgroup_path/memory.max")" = 4294967296
test "$(cat "/sys/fs/cgroup$cgroup_path/memory.swap.max")" = 0
test "$(cat "/sys/fs/cgroup$cgroup_path/pids.max")" = 512
test "$(cat "/sys/fs/cgroup$cgroup_path/cpu.max")" = "200000 100000"
test "$(findmnt -b -n -o SIZE -T /tmp)" = 2147483648
test "$(awk '$1 == "NoNewPrivs:" {print $2}' /proc/self/status)" = 1
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
findmnt -n -o VFS-OPTIONS -T "$source_root" | tr , '\n' | grep -qx ro
mkdir "$evidence_root"
python3 -B - "$source_root" "$evidence_root" <<'PY'
import hashlib
import json
import pathlib
import subprocess
import sys
import unittest
source, evidence = map(pathlib.Path, sys.argv[1:])
acceptance = source / 'docs/acceptance/TASK-10'
sys.path.insert(0, str(acceptance))
hashes = {name: hashlib.sha256((acceptance / name).read_bytes()).hexdigest() for name in
          ('runtime_preflight.py', 'test_runtime_preflight.py', 'supervisor_probe.py', 'role_probe.py', 'stage_probe.py')}
(evidence / 'source-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
with (evidence / 'unittest.log').open('x') as stream:
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromName('test_runtime_preflight'))
summary = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
           'skipped': len(result.skipped), 'exitCode': 0 if result.wasSuccessful() else 1,
           'command': ['python3', '-B', '-m', 'unittest', 'test_runtime_preflight']}
entrypoint = subprocess.run([sys.executable, '-B', str(acceptance / 'test_runtime_preflight.py')],
                            capture_output=True, text=True, timeout=60)
if len(entrypoint.stdout.encode()) + len(entrypoint.stderr.encode()) > 256 * 1024:
    raise RuntimeError('validator entrypoint log exceeds bound')
(evidence / 'entrypoint.log').write_text(entrypoint.stdout + entrypoint.stderr)
summary['entrypointExitCode'] = entrypoint.returncode
summary['entrypointSummary'] = json.loads(entrypoint.stdout)
if entrypoint.returncode != 0 or summary['entrypointSummary'] != {key: summary[key] for key in ('tests', 'failures', 'errors', 'skipped')}:
    summary['exitCode'] = 1
(evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
sys.exit(summary['exitCode'])
PY
