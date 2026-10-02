#!/usr/bin/env bash
set -euo pipefail

source_root=${1:?source root required}
evidence_root=${2:?evidence root required}
test -d "$source_root/.git"
test ! -e "$evidence_root"
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
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
snapshot=$(mktemp -d /tmp/wjc-task00.XXXXXX)
tested_commit=$(git -c "safe.directory=$source_root" -C "$source_root" rev-parse HEAD)
git -c "safe.directory=$source_root" -C "$source_root" archive "$tested_commit" | tar -x -C "$snapshot"
printf "%s\n" "$tested_commit" > "$evidence_root/tested-head.txt"
systemctl show "${cgroup_path##*/}" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p PrivateNetwork -p ProtectSystem -p ProtectHome -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths > "$evidence_root/unit-properties.txt"
{
    cat /etc/os-release
    java -version 2>&1
    mvn -version
    python3 --version
    g++ --version | head -n 1
    printf "python-command="
    command -v python || true
    printf "memory.max="
    cat "$cgroup_root/memory.max"
    printf "pids.max="
    cat "$cgroup_root/pids.max"
    printf "cpu.max="
    cat "$cgroup_root/cpu.max"
    findmnt -n -o TARGET,FSTYPE,OPTIONS -T /tmp
} > "$evidence_root/environment.txt"
cd "$snapshot"
set +e
timeout --signal=TERM --kill-after=10s 540s mvn -o -B -Dstyle.color=never test > "$snapshot/maven.raw.log" 2>&1
maven_exit=$?
set -e
python3 - "$snapshot" "$evidence_root" "$maven_exit" <<'PY'
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

def redact(value):
    value = re.sub(r"\x1b\[[0-9;]*m", "", value or "")
    value = re.sub(r"(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", "<redacted-id>", value)
    value = re.sub(r"(?i)((?:password|passwd|token|sessionId|session-id|accessCode|authorization|secret)\s*[:=]\s*)[^\s,;]+", r"\1<redacted>", value)
    return re.sub(r"://[^/\s:@]+:[^/\s@]+@", "://<redacted>@", value)[:2000]

snapshot = pathlib.Path(sys.argv[1])
evidence = pathlib.Path(sys.argv[2])
reports = sorted((snapshot / "target/surefire-reports").glob("TEST-*.xml"))
totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
suites = []
for report in reports:
    suite = ET.parse(report).getroot()
    counts = {key: int(suite.get(key, "0")) for key in totals}
    for key, count in counts.items():
        totals[key] += count
    outcomes = []
    for case in suite.findall("testcase"):
        for outcome in ("failure", "error", "skipped"):
            element = case.find(outcome)
            if element is not None:
                outcomes.append({"test": redact(case.get("name")), "outcome": outcome, "type": element.get("type"), "message": redact(element.get("message")), "detail": redact("\n".join((element.text or "").splitlines()[:6]))})
    suites.append({"name": suite.get("name"), **counts, "nonpassing": outcomes})
totals["passed"] = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
result = {"command": "mvn -o -B -Dstyle.color=never test", "exit_code": int(sys.argv[3]), "report_count": len(reports), "evidence_complete": bool(reports) and totals["tests"] > 0, "totals": totals, "suites": suites}
(evidence / "baseline-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
raw_log = (snapshot / "maven.raw.log").read_text(encoding="utf-8", errors="replace")
lines = []
for line in raw_log.splitlines():
    line = re.sub(r"\x1b\[[0-9;]*m", "", line)
    if re.match(r"^\[(?:INFO|ERROR|WARNING)\] (?:Tests run:|BUILD |Total time:|Finished at:|The POM for |Failed to execute goal|Cannot access |.*has not been downloaded from it before)", line):
        lines.append(redact(line))
(evidence / "build-summary.log").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps({"exit_code": result["exit_code"], "report_count": len(reports), **totals}))
print("\n".join(lines[-8:]))
if result["exit_code"] == 0 and not result["evidence_complete"]:
    sys.exit(2)
PY
exit "$maven_exit"
