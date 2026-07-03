#!/usr/bin/env bash
set -euo pipefail

CASES=100000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cases)
      CASES="${2:?--cases requires a value}"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--cases 100000]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if ! [[ "$CASES" =~ ^[0-9]+$ ]]; then
  echo "--cases must be an integer" >&2
  exit 2
fi

if (( CASES < 100 || CASES > 200000 )); then
  echo "--cases must be between 100 and 200000" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "Running high-volume smoke for ${CASES} cases"
echo "Expected test output includes HIGH_VOLUME_SMOKE with payloadBytes, schedulerTasks, pollCount, and throughputCasesPerSec."

if [[ -f "$ROOT/mvnw" ]] && ! LC_ALL=C grep -q $'\r' "$ROOT/mvnw"; then
  MVN=(sh "$ROOT/mvnw")
elif [[ -f "$ROOT/mvnw.cmd" ]] && command -v cmd.exe >/dev/null 2>&1; then
  if command -v cygpath >/dev/null 2>&1; then
    MVNW_CMD="$(cygpath -w "$ROOT/mvnw.cmd")"
  else
    MVNW_CMD="$ROOT/mvnw.cmd"
  fi
  MVN=(cmd.exe /c "$MVNW_CMD")
else
  MVN=(mvn)
fi

"${MVN[@]}" "-Dtest=ProductionHighVolumeIntegrationTest" "-DhighVolumeSmokeCases=${CASES}" test
