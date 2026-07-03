#!/usr/bin/env bash
set -euo pipefail

IMAGE="cpp-judge-runner-linux:latest"
MEMORY="256m"
PIDS_LIMIT="64"
SECCOMP_PROFILE="default"
APPARMOR_PROFILE="docker-default"
SKIP_MAVEN=0
REQUIRE_DOCKER=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)
      IMAGE="${2:?--image requires a value}"
      shift 2
      ;;
    --memory)
      MEMORY="${2:?--memory requires a value}"
      shift 2
      ;;
    --pids-limit)
      PIDS_LIMIT="${2:?--pids-limit requires a value}"
      shift 2
      ;;
    --seccomp)
      SECCOMP_PROFILE="${2:?--seccomp requires a value}"
      shift 2
      ;;
    --apparmor)
      APPARMOR_PROFILE="${2:?--apparmor requires a value}"
      shift 2
      ;;
    --skip-maven)
      SKIP_MAVEN=1
      shift
      ;;
    --require-docker)
      REQUIRE_DOCKER=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--image IMAGE] [--memory 256m] [--pids-limit 64] [--seccomp PROFILE] [--apparmor PROFILE] [--skip-maven] [--require-docker] [--dry-run]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "Linux capability smoke for linux-prod"
echo "Checks: LinuxContainerRunnerTest, --network none, cgroup resource limits, --pids-limit, --read-only, seccomp, AppArmor, non-root, default access code rejection, wildcard origin rejection."

run_step() {
  echo "Step: $*"
  if (( DRY_RUN )); then
    printf '+'
    printf ' %q' "$@"
    printf '\n'
    return 0
  fi
  "$@"
}

if (( SKIP_MAVEN == 0 )); then
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
  run_step "${MVN[@]}" "-Dtest=LinuxContainerRunnerTest,ProductionSecurityStartupValidatorTest" test
fi

if ! command -v docker >/dev/null 2>&1; then
  if (( REQUIRE_DOCKER )); then
    echo "Docker CLI unavailable; cannot collect live Linux container evidence." >&2
    exit 1
  fi
  echo "Docker CLI unavailable; unit-test or --dry-run smoke is not production release evidence." >&2
  exit 0
fi

WORKDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

run_step docker run --rm \
  --network none \
  --memory "$MEMORY" \
  --cpus 1 \
  --pids-limit "$PIDS_LIMIT" \
  --read-only \
  --user 1000:1000 \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --mount "type=bind,source=$WORKDIR,target=/work" \
  --security-opt "seccomp=$SECCOMP_PROFILE" \
  --security-opt "apparmor=$APPARMOR_PROFILE" \
  "$IMAGE" \
  /opt/judge-runner/probe
