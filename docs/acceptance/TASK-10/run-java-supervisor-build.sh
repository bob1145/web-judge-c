#!/usr/bin/env bash
set -euo pipefail

test "$#" = 2
source_root=$1
evidence_root=$2
test "$(realpath "$source_root")" = /mnt/c/Users/29901/Documents/dp/web-judge-c
test "$(dirname "$evidence_root")" = "$source_root/docs/acceptance/TASK-10"
round=$(basename "$evidence_root")
[[ "$round" =~ ^JVM-BUILD-R[0-9]{2}$ ]]
test ! -e "$evidence_root"
output_root="$source_root/target/task10-runtime/$round"
test ! -e "$output_root"
test "$(realpath "$(dirname "$evidence_root")")" = "$source_root/docs/acceptance/TASK-10"
test "$(realpath "$(dirname "$output_root")")" = "$source_root/target/task10-runtime"
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
test "$(findmnt -b -n -o SIZE -T /tmp)" = 2147483648
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
unit=$(basename "$cgroup_path")
[[ "$unit" =~ ^wjc-task10-jvm-build-r[0-9]+\.service$ ]]
cgroup_root="/sys/fs/cgroup$cgroup_path"
test "$(cat "$cgroup_root/memory.max")" = 4294967296
test "$(cat "$cgroup_root/memory.swap.max")" = 0
test "$(cat "$cgroup_root/pids.max")" = 512
test "$(cat "$cgroup_root/cpu.max")" = "200000 100000"
test "$(awk '$1 == "NoNewPrivs:" {print $2}' /proc/self/status)" = 1
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
test -z "$(ip route show)"
findmnt -n -o VFS-OPTIONS -T "$source_root" | tr , '\n' | grep -qx ro
unit_properties=$(systemctl show "$unit" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p TimeoutStopUSec -p PrivateNetwork -p ProtectSystem -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths)
grep -qx 'RuntimeMaxUSec=9min 30s' <<< "$unit_properties"
grep -qx 'TimeoutStopUSec=30s' <<< "$unit_properties"
grep -qx 'PrivateNetwork=yes' <<< "$unit_properties"
mkdir "$evidence_root" "$output_root"
printf '%s\n' "$unit_properties" > "$evidence_root/unit-properties.txt"
snapshot=$(mktemp -d /tmp/wjc-java-build.XXXXXX)
git -c "safe.directory=$source_root" -C "$source_root" rev-parse HEAD > "$evidence_root/tested-head.txt"
git -c "safe.directory=$source_root" -C "$source_root" ls-files -z --cached --others --exclude-standard -- pom.xml src runner .mvn > "$snapshot/source-files.txt"
python3 - "$source_root" "$snapshot" "$evidence_root" <<'PY'
import hashlib
import json
import pathlib
import shutil
import sys

source, snapshot, evidence = map(pathlib.Path, sys.argv[1:])
hashes = {}
for raw in sorted(set((snapshot / 'source-files.txt').read_bytes().split(b'\0')) - {b''}):
    relative = pathlib.PurePosixPath(raw.decode('utf-8'))
    original = source / relative
    if relative.is_absolute() or '..' in relative.parts or not original.is_file() or original.resolve() != original:
        raise RuntimeError('unsafe frozen source path')
    target = snapshot / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, target)
    hashes[str(relative)] = hashlib.sha256(target.read_bytes()).hexdigest()
(evidence / 'source-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
(evidence / 'source-files.txt').write_text('\n'.join(hashes) + '\n')
diagnostics = snapshot / 'src/test/java/com/example/demo/acceptance'
diagnostics.mkdir(parents=True, exist_ok=True)
for name in ('JavaSupervisorProbe.java', 'JavaSupervisorBundleClasspathTest.java'):
    original = source / 'docs/acceptance/TASK-10' / name
    if original.resolve() != original or not original.is_file() or (diagnostics / name).exists():
        raise RuntimeError('unsafe or conflicting diagnostic source')
    shutil.copyfile(original, diagnostics / name)
    shutil.copyfile(original, evidence / name)
shutil.copyfile(source / 'docs/acceptance/TASK-10/run-java-supervisor-build.sh', evidence / 'run-java-supervisor-build.sh')
PY
cd "$snapshot"
set +e
(ulimit -f 32768; timeout --signal=TERM --kill-after=10s 480s mvn -o -B -Dstyle.color=never -Dtest=JavaSupervisorBundleClasspathTest test > "$snapshot/maven.raw.log" 2>&1)
maven_exit=$?
set -e
printf '%s\n' "$maven_exit" > "$evidence_root/maven-exit.txt"
python3 - "$snapshot" "$evidence_root" "$output_root" "$maven_exit" <<'PY'
import hashlib
import json
import pathlib
import shutil
import sys
import tarfile
import xml.etree.ElementTree as ET

snapshot, evidence, output = map(pathlib.Path, sys.argv[1:4])
exit_code = int(sys.argv[4])
def require(condition, message):
    if not condition:
        raise RuntimeError(message)
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
report = snapshot / 'target/surefire-reports/TEST-com.example.demo.acceptance.JavaSupervisorBundleClasspathTest.xml'
counts = {}
nonpassing = []
if report.is_file() and report.stat().st_size <= 2 * 1024 ** 2:
    suite = ET.parse(report).getroot()
    counts = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    for testcase in suite.findall('testcase'):
        for outcome in ('failure', 'error', 'skipped'):
            if testcase.find(outcome) is not None:
                nonpassing.append({'test': testcase.get('name', ''), 'outcome': outcome})
summary = {'mavenExitCode': exit_code, 'counts': counts, 'productAcceptance': False,
           'nonpassing': nonpassing,
           'scope': 'trusted offline compilation and bundle capture only; no runtime execution'}
diagnostics = []
if exit_code != 0 or counts != {'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}:
    import re
    with (snapshot / 'maven.raw.log').open('rb') as stream:
        log_excerpt = stream.read(4 * 1024 ** 2).decode('utf-8', errors='replace')
    for raw in log_excerpt.splitlines():
        line = re.sub(r'\x1b\[[0-9;]*m', '', raw)
        if '[ERROR]' not in line or any(term in line.lower() for term in
                ('classpath', 'class.path', 'system propert', 'settings.xml', 'user.home', 'java.home', 'environment')):
            continue
        line = line.replace(str(snapshot), '<snapshot>').replace('/root/.m2/repository', '<maven-cache>')
        line = re.sub(r'[A-Za-z][A-Za-z0-9+.-]*://\S+', '<redacted-url>', line)
        line = re.sub(r'(?i)\b(password|token|secret|authorization|access.?key)\s*[:=]\s*\S+', r'\1=<redacted>', line)
        line = re.sub(r'\b[^\s@]+@[^\s@]+\b', '<redacted-email>', line)
        line = re.sub(r'/(?:root|home|mnt)/[^\s,:;]+', '<redacted-host-path>', line)
        diagnostics.append(''.join(character for character in line if character.isprintable())[:400])
        if len(diagnostics) == 20:
            break
summary['boundedFailureDiagnostics'] = diagnostics
(evidence / 'build-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
require(exit_code == 0 and counts == {'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}, 'offline compilation or classpath capture failed; raw XML and log remain private')
bundle = output / 'bundle'
bundle.mkdir()
(bundle / 'classes').mkdir()
(bundle / 'lib').mkdir()
classes = snapshot / 'target/classes'
require(classes.is_dir(), 'compiled product classes missing')
for original in sorted(classes.rglob('*.class')):
    require(original.resolve() == original and original.is_file(), 'unsafe compiled class')
    target = bundle / 'classes' / original.relative_to(classes)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, target)
probe_root = snapshot / 'target/test-classes/com/example/demo/acceptance'
probe_classes = list(probe_root.glob('JavaSupervisorProbe*.class'))
require(any(path.name == 'JavaSupervisorProbe.class' for path in probe_classes), 'compiled probe missing')
for original in probe_classes:
    target = bundle / 'classes/com/example/demo/acceptance' / original.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, target)
classpath = snapshot / 'target/java-supervisor-classpath.txt'
require(classpath.stat().st_size <= 128 * 1024, 'classpath capture exceeds bound')
classpath_text = classpath.read_text()
classpath_entries = classpath_text.split(':')
classpath_shape = {'entryCount': len(classpath_entries), 'startsWithSeparator': classpath_text.startswith(':'),
                   'endsWithSeparator': classpath_text.endswith(':'),
                   'emptyEntryCount': sum(entry == '' for entry in classpath_entries),
                   'firstEmptyEntryIndexes': [index for index, entry in enumerate(classpath_entries) if entry == ''][:16]}
dependencies = []
seen = set()
classpath_normalization = {'policy': 'omit only the sole terminal empty token; reject all other empty tokens',
                           'soleTrailingEmptyOmitted': False, 'omittedIndexZeroBased': None,
                           'implicitWorkingDirectoryIncluded': False,
                           'executionClasspath': '/lab/java-bundle/classes:/lab/java-bundle/lib/*'}
def reject_classpath_entry(index, raw, original, reason):
    import re
    observation = {'indexZeroBased': index, 'isLastEntry': index == len(classpath_entries) - 1,
                   'rawEmpty': raw == '', 'rawCharacterCount': len(raw), 'reason': reason,
                   'absolute': original.is_absolute(), 'file': original.is_file(),
                   'directory': original.is_dir(), 'symlink': original.is_symlink(),
                   'canonical': original.resolve() == original, 'pathScope': 'redacted-outside-approved-roots',
                   'dependencyJarsCopied': len(dependencies)}
    for scope, root in (('snapshot', snapshot), ('maven-cache', pathlib.Path('/root/.m2/repository'))):
        if original.is_absolute() and original.is_relative_to(root):
            relative = original.relative_to(root)
            if '..' not in relative.parts and original.resolve().is_relative_to(root):
                safe_relative = relative.as_posix()
                observation['pathScope'] = scope
                observation['relativePath'] = (safe_relative[:512] if re.fullmatch(r'[A-Za-z0-9_./+$-]+', safe_relative)
                                               else '<redacted-unexpected-path-characters>')
                observation['relativePathTruncated'] = len(safe_relative) > 512
            break
    summary.update({'bundlePackagingComplete': False, 'classpathShape': classpath_shape,
                    'classpathNormalization': classpath_normalization,
                    'classpathEntryRejected': observation})
    (evidence / 'build-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (evidence / 'classpath-rejection.json').write_text(json.dumps(
        {'classpathShape': classpath_shape, 'rejectedEntry': observation}, indent=2) + '\n')
    raise RuntimeError(reason + '; see bounded classpath-rejection.json')
for index, raw in enumerate(classpath_entries):
    if raw == '' and len(classpath_entries) > 1 and index == len(classpath_entries) - 1 and classpath_shape['emptyEntryCount'] == 1:
        classpath_normalization['soleTrailingEmptyOmitted'] = True
        classpath_normalization['omittedIndexZeroBased'] = index
        continue
    original = pathlib.Path(raw)
    if original in (classes, snapshot / 'target/test-classes'):
        continue
    if not (original.is_absolute() and original.is_file() and original.suffix == '.jar' and original.resolve() == original):
        reject_classpath_entry(index, raw, original, 'classpath contains an unexpected entry')
    if not original.is_relative_to(pathlib.Path('/root/.m2/repository')):
        reject_classpath_entry(index, raw, original, 'dependency outside trusted offline cache')
    if original in seen:
        continue
    seen.add(original)
    target = bundle / 'lib' / original.name
    if target.exists():
        reject_classpath_entry(index, raw, original, 'dependency filename collision')
    shutil.copyfile(original, target)
    dependencies.append({'coordinatePath': str(original.relative_to('/root/.m2/repository')), 'file': 'lib/' + original.name, 'sha256': digest(target)})
files = {str(path.relative_to(bundle)): {'sha256': digest(path), 'bytes': path.stat().st_size}
         for path in sorted(bundle.rglob('*')) if path.is_file()}
total = sum(item['bytes'] for item in files.values())
require(total <= 256 * 1024 ** 2 and len(files) <= 10000, 'bundle exceeds explicit size or entry budget')
manifest = {'version': 1, 'mainClass': 'com.example.demo.acceptance.JavaSupervisorProbe',
            'probeSourceSha256': digest(evidence / 'JavaSupervisorProbe.java'),
            'sourceSnapshotSha256': digest(evidence / 'source-hashes.json'),
            'classpathScope': 'resolved Surefire dependency superset, including test jars; not claimed minimal',
            'classpathNormalization': classpath_normalization,
            'dependencies': dependencies, 'files': files, 'totalBytes': total}
manifest_path = bundle / 'bundle-manifest.json'
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
archive = output / 'java-supervisor-bundle.tar'
with tarfile.open(archive, 'w', format=tarfile.PAX_FORMAT) as packaged:
    for path in sorted(bundle.rglob('*')):
        if path.is_file():
            packaged.add(path, arcname=str(path.relative_to(bundle)), recursive=False)
summary.update({'bundlePackagingComplete': True, 'classpathShape': classpath_shape,
                'classpathNormalization': classpath_normalization,
                'bundleDirectory': str(bundle), 'bundleManifestSha256': digest(manifest_path),
                'bundleFileCount': len(files), 'bundleBytes': total,
                'archiveBytes': archive.stat().st_size, 'archiveSha256': digest(archive),
                'probeSourceSha256': manifest['probeSourceSha256'], 'sourceSnapshotSha256': manifest['sourceSnapshotSha256']})
(evidence / 'build-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
shutil.copyfile(manifest_path, evidence / 'bundle-manifest.json')
print(json.dumps(summary))
PY
exit "$maven_exit"
