#!/usr/bin/env bash
set -euo pipefail

source_root=${1:?source root required}
evidence_root=${2:?evidence root required}
test "$#" = 2
test -d "$source_root/.git"
test ! -e "$evidence_root"
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
test "$(findmnt -b -n -o SIZE -T /tmp)" = 2147483648
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
cgroup_root="/sys/fs/cgroup$cgroup_path"
test "$(cat "$cgroup_root/memory.max")" = 4294967296
test "$(cat "$cgroup_root/memory.swap.max")" = 0
test "$(cat "$cgroup_root/pids.max")" = 512
test "$(cat "$cgroup_root/cpu.max")" = "200000 100000"
test "$(awk '$1 == "NoNewPrivs:" {print $2}' /proc/self/status)" = 1
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
test -z "$(ip route show)"
findmnt -n -o VFS-OPTIONS -T "$source_root" | tr , '\n' | grep -qx ro
mkdir "$evidence_root"
snapshot=$(mktemp -d /tmp/wjc-scheduler-probe.XXXXXX)
git -c "safe.directory=$source_root" -C "$source_root" rev-parse HEAD > "$evidence_root/tested-head.txt"
printf 'worktree-with-isolated-diagnostic\n' > "$evidence_root/snapshot-mode.txt"
git -c "safe.directory=$source_root" -C "$source_root" ls-files -z --cached --others --exclude-standard -- pom.xml src runner .mvn > "$snapshot/source-files.txt"
tar -C "$source_root" --null -T "$snapshot/source-files.txt" -cf "$snapshot/source.tar"
tar -xf "$snapshot/source.tar" -C "$snapshot"
(cd "$snapshot"; find src runner .mvn -type f -print0 | sort -z | xargs -0 sha256sum; sha256sum pom.xml) > "$evidence_root/source-hashes.sha256"
probe_source="$source_root/docs/acceptance/TASK-10/SchedulerRejectionProbeTest.java"
cp "$probe_source" "$snapshot/src/test/java/com/example/demo/SchedulerRejectionProbeTest.java"
cp "$snapshot/src/test/java/com/example/demo/SchedulerRejectionProbeTest.java" "$evidence_root/SchedulerRejectionProbeTest.java"
(cd "$evidence_root"; sha256sum SchedulerRejectionProbeTest.java) > "$evidence_root/probe-hash.sha256"
sha256sum "$0" > "$evidence_root/harness-hash.sha256"
systemctl show "${cgroup_path##*/}" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p PrivateNetwork -p ProtectSystem -p ProtectHome -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths > "$evidence_root/unit-properties.txt"
cd "$snapshot"
set +e
(ulimit -f 32768; timeout --signal=TERM --kill-after=10s 180s mvn -o -B -Dstyle.color=never -Dtest=SchedulerRejectionProbeTest test > "$snapshot/maven.raw.log" 2>&1)
maven_exit=$?
set -e
printf '%s\n' "$maven_exit" > "$evidence_root/maven-exit.txt"
python3 - "$snapshot" "$evidence_root" "$maven_exit" <<'PY'
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

snapshot = pathlib.Path(sys.argv[1])
evidence = pathlib.Path(sys.argv[2])
exit_code = int(sys.argv[3])
report = snapshot / "target/surefire-reports/TEST-com.example.demo.SchedulerRejectionProbeTest.xml"
counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
outcomes = []
if report.is_file() and report.stat().st_size <= 2 * 1024 * 1024:
    suite = ET.parse(report).getroot()
    counts = {key: int(suite.get(key, "0")) for key in counts}
    for case in suite.findall("testcase")[:4]:
        for outcome in ("failure", "error", "skipped"):
            element = case.find(outcome)
            if element is not None:
                outcomes.append({"test": (case.get("name") or "")[:160],
                                 "outcome": outcome, "type": (element.get("type") or "")[:160]})
observation_file = snapshot / "target/scheduler-rejection-observation.json"
observation = {}
if observation_file.is_file() and observation_file.stat().st_size <= 4096:
    raw = json.loads(observation_file.read_text(encoding="utf-8"))
    for key in ("knownDefectObserved", "productAcceptance", "r07CausalityProven", "secondStatus", "secondMessage", "secondExecutions"):
        if key in raw:
            observation[key] = raw[key]
confirmed = (exit_code == 0 and counts == {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}
             and observation == {"knownDefectObserved": True, "productAcceptance": False,
                                 "r07CausalityProven": False, "secondStatus": "SYSTEM_ERROR",
                                 "secondMessage": "Judge executor rejected the task", "secondExecutions": 0})
result = {"purpose": "Known-defect reproduction only; not product acceptance or proof of the R07 execution trace",
          "command": ["mvn", "-o", "-B", "-Dstyle.color=never", "-Dtest=SchedulerRejectionProbeTest", "test"],
          "mavenExitCode": exit_code, "counts": counts, "nonpassing": outcomes,
          "observation": observation, "diagnosticConfirmed": confirmed, "productAcceptance": False}
(evidence / "diagnostic-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
if exit_code == 0 and not confirmed:
    sys.exit(1)
PY
exit "$maven_exit"
