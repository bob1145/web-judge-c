#!/usr/bin/env bash
set -euo pipefail

test "$#" = 3
source_root=$1
evidence_root=$2
mode=$3
test "$(realpath "$source_root")" = /mnt/c/Users/29901/Documents/dp/web-judge-c
test "$(dirname "$evidence_root")" = "$source_root/docs/acceptance/TASK-10"
test "$(realpath "$(dirname "$evidence_root")")" = "$source_root/docs/acceptance/TASK-10"
round=$(basename "$evidence_root")
case "$mode" in
    targeted)
        [[ "$round" =~ ^CANCEL-CONTROL-R[0-9]{2}$ ]]
        selection=-Dtest=LinuxRuntimeJournalTest,LinuxSupervisorLauncherTest,LinuxAttemptDiscoveryTest,LinuxContainerRunnerTest
        ;;
    full)
        [[ "$round" =~ ^R[0-9]{2}$ ]]
        selection=-DfailIfNoTests=true
        ;;
    *) exit 2 ;;
esac
test ! -e "$evidence_root"
test "$(id -u)" = 0
test "$(id -g)" = 0
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
test "$(findmnt -b -n -o SIZE -T /tmp)" = 2147483648
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
unit=$(basename "$cgroup_path")
[[ "$unit" =~ ^wjc-task10-cancel-(control|regression)-r[0-9]+[.]service$ ]]
cgroup_root="/sys/fs/cgroup$cgroup_path"
test "$(cat "$cgroup_root/memory.max")" = 4294967296
test "$(cat "$cgroup_root/memory.swap.max")" = 0
test "$(cat "$cgroup_root/pids.max")" = 512
test "$(cat "$cgroup_root/cpu.max")" = "200000 100000"
test "$(awk '$1 == "NoNewPrivs:" {print $2}' /proc/self/status)" = 1
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
test -z "$(ip route show)"
findmnt -n -o VFS-OPTIONS -T "$source_root" | tr , '\n' | grep -qx ro
unit_properties=$(systemctl show "$unit" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p TimeoutStopUSec -p PrivateNetwork -p ProtectSystem -p ProtectHome -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths)
grep -qx 'RuntimeMaxUSec=10min' <<< "$unit_properties"
grep -qx 'TimeoutStopUSec=30s' <<< "$unit_properties"
grep -qx 'PrivateNetwork=yes' <<< "$unit_properties"
mkdir "$evidence_root"
printf '%s\n' "$unit_properties" > "$evidence_root/unit-properties.txt"
snapshot=$(mktemp -d /tmp/wjc-cancel-contracts.XXXXXX)
git -c "safe.directory=$source_root" -C "$source_root" rev-parse HEAD > "$evidence_root/tested-head.txt"
git -c "safe.directory=$source_root" -C "$source_root" ls-files -z --cached --others --exclude-standard -- pom.xml src runner .mvn > "$snapshot/source-files.txt"
if [ "$mode" = full ]; then
    git -c "safe.directory=$source_root" -C "$source_root" ls-files -z --cached --others --exclude-standard -- README.md scripts > "$snapshot/extra-source-files.txt"
fi
python3 - "$source_root" "$snapshot" "$evidence_root" "$mode" <<'PY'
import hashlib
import json
import pathlib
import shutil
import sys

source, snapshot, evidence = map(pathlib.Path, sys.argv[1:4])
entries = set((snapshot / 'source-files.txt').read_bytes().split(b'\0')) - {b''}
if sys.argv[4] == 'full':
    entries.update(set((snapshot / 'extra-source-files.txt').read_bytes().split(b'\0')) - {b''})
    entries.update(path.relative_to(source).as_posix().encode('utf-8') for path in (source / 'docs').glob('*.md'))
if not 1 <= len(entries) <= 10000:
    raise RuntimeError('source entry count outside budget')
hashes = {}
total_bytes = 0
for raw in sorted(entries):
    relative = pathlib.PurePosixPath(raw.decode('utf-8'))
    original = source / relative
    if relative.is_absolute() or '..' in relative.parts or not original.is_file() or original.resolve() != original:
        raise RuntimeError('unsafe frozen source path')
    total_bytes += original.stat().st_size
    if total_bytes > 256 * 1024 ** 2:
        raise RuntimeError('source snapshot exceeds size budget')
    for base in (snapshot, evidence / 'sources'):
        target = base / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
    digest = hashlib.sha256((snapshot / relative).read_bytes()).hexdigest()
    if hashlib.sha256(original.read_bytes()).hexdigest() != digest or hashlib.sha256((evidence / 'sources' / relative).read_bytes()).hexdigest() != digest:
        raise RuntimeError('source changed during snapshot capture')
    hashes[str(relative)] = digest
script = source / 'docs/acceptance/TASK-10/run-cancellation-contracts.sh'
shutil.copyfile(script, evidence / script.name)
(evidence / 'source-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
(evidence / 'snapshot.json').write_text(json.dumps({'sourceCount': len(hashes), 'sourceBytes': total_bytes,
    'scriptSha256': hashlib.sha256(script.read_bytes()).hexdigest(), 'sourceReadOnly': True,
    'mavenOffline': True, 'trustedFixtureUid': 0, 'trustedFixtureGid': 0}, indent=2) + '\n')
PY
cd "$snapshot"
set +e
(ulimit -f 32768; timeout --signal=TERM --kill-after=10s 480s mvn -o -B -Dstyle.color=never "$selection" test > "$snapshot/maven.raw.log" 2>&1)
maven_exit=$?
set -e
printf '%s\n' "$maven_exit" > "$evidence_root/maven-exit.txt"
python_exit=0
if [ "$mode" = full ]; then
    set +e
    (ulimit -f 32768; timeout --signal=TERM --kill-after=10s 60s python3 -B runner/tests/run_contract_suite.py "$evidence_root/python-summary.json" > "$evidence_root/python-tests.log" 2>&1)
    python_exit=$?
    set -e
    printf '%s\n' "$python_exit" > "$evidence_root/python-exit.txt"
fi
python3 - "$snapshot" "$evidence_root" "$maven_exit" "$python_exit" "$mode" "$selection" <<'PY'
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

snapshot, evidence = map(pathlib.Path, sys.argv[1:3])
maven_exit, python_exit = map(int, sys.argv[3:5])
mode, selection = sys.argv[5:7]
def redact(value):
    value = re.sub(r'\x1b\[[0-9;]*m', '', value).replace(str(snapshot), '<snapshot>').replace('/root/.m2/repository', '<offline-cache>')
    return re.sub(r'(?i)((?:authorization|cookie|password|secret|access[-_]?code|csrf|session[-_]?token)\s*[:=]\s*)[^\s,;]+', r'\1<redacted>', value)
reports = sorted((snapshot / 'target/surefire-reports').glob('TEST-*.xml'))
suites = []
report_bytes = 0
for report in reports:
    report_bytes += report.stat().st_size
    if len(reports) > 256 or report.stat().st_size > 4 * 1024 ** 2 or report_bytes > 32 * 1024 ** 2:
        raise RuntimeError('Surefire reports exceed evidence budget')
    root = ET.parse(report).getroot()
    counts = {key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    observed = {key: 0 for key in counts}
    nonpassing = []
    for testcase in root.findall('testcase'):
        observed['tests'] += 1
        for outcome, key in (('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')):
            entry = testcase.find(outcome)
            if entry is not None:
                observed[key] += 1
                nonpassing.append({'test': testcase.get('name', ''), 'outcome': outcome,
                    'type': entry.get('type', ''), 'message': redact(entry.get('message', ''))[:2048],
                    'detail': redact(entry.text or '')[:2048]})
    if observed != counts:
        raise RuntimeError('Surefire counts differ from actual testcases')
    suites.append({'name': root.get('name', ''), **counts, 'nonpassing': nonpassing,
        'reportSha256': hashlib.sha256(report.read_bytes()).hexdigest(), 'reportBytes': report.stat().st_size})
totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
totals['passed'] = totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']
expected = {'com.example.demo.service.sandbox.LinuxRuntimeJournalTest',
    'com.example.demo.service.sandbox.LinuxSupervisorLauncherTest',
    'com.example.demo.service.sandbox.LinuxAttemptDiscoveryTest', 'com.example.demo.LinuxContainerRunnerTest'}
reports_complete = ({suite['name'] for suite in suites} == expected if mode == 'targeted' else len(suites) >= 57)
reports_complete = reports_complete and all(suite['tests'] > 0 for suite in suites)
mandatory_skips = sum(suite['skipped'] for suite in suites if suite['name'] in expected - {'com.example.demo.LinuxContainerRunnerTest'})
summary = {'command': ['mvn', '-o', '-B', '-Dstyle.color=never', selection, 'test'],
    'exit_code': maven_exit, 'report_count': len(reports), 'reports_present': bool(reports),
    'reports_complete': reports_complete, 'mandatoryCancellationSkips': mandatory_skips,
    'totals': totals, 'suites': suites, 'mode': mode, 'productAcceptance': False,
    'scope': 'offline Java contracts only; not real supervisor or production acceptance'}
summary_name = 'summary.json' if mode == 'targeted' else 'baseline-summary.json'
(evidence / summary_name).write_text(json.dumps(summary, indent=2) + '\n')
diagnostics = []
with (snapshot / 'maven.raw.log').open('rb') as stream:
    excerpt = stream.read(4 * 1024 ** 2).decode('utf-8', errors='replace')
for line in excerpt.splitlines():
    if '[ERROR]' in line and not any(term in line.lower() for term in
            ('classpath', 'class.path', 'system propert', 'settings.xml', 'user.home', 'java.home', 'environment')):
        diagnostics.append(redact(line)[:1024])
        if len(diagnostics) == 100:
            break
(evidence / 'build-summary.log').write_text('\n'.join(diagnostics) + '\n')
python_summary = None
if mode == 'full':
    path = evidence / 'python-summary.json'
    python_summary = json.loads(path.read_text()) if path.is_file() else {'summaryMissing': True}
print(json.dumps({'javaExit': maven_exit, 'java': totals, 'reportsComplete': reports_complete,
    'mandatoryCancellationSkips': mandatory_skips, 'pythonExit': python_exit, 'python': python_summary}))
if not reports_complete or mandatory_skips or (mode == 'full' and (python_exit != 0 or python_summary.get('summaryMissing'))):
    raise SystemExit(2)
PY
if [ "$maven_exit" != 0 ]; then
    exit "$maven_exit"
fi
exit "$python_exit"
