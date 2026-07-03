# Linux Sandbox Runbook

This runbook covers the `linux-prod` provider for production judging. The
service must not serve untrusted C++ tasks in `linux-prod` unless the capability
probe and smoke checks pass on the real deployment host.

## Scope

- Provider: `judge.sandbox.production.provider=linux-container`
- Isolation: Linux container engine, not host `ProcessBuilder`
- Required controls: `--network none`, non-root container user, cgroup memory/CPU/pids limits, read-only root filesystem, tmpfs for writable runtime paths, `no-new-privileges`, seccomp/AppArmor or an equivalent enforced LSM policy
- Task execution: the web process starts a container and mounts only the judge work directory at `/work`. The in-container runner command is `/opt/judge-runner/run-task /work/sandbox-task.json`.

Windows note: Windows development machines may run this probe through Docker
Desktop's Linux engine, but production Windows hosting should use the Windows
Hyper-V provider or a remote Linux worker. Do not treat Windows process
execution or Job Object-only isolation as Linux production evidence.

## Host prerequisites

Install one supported container runtime on the production Linux host:

- Docker Engine 24+ or Podman with Docker-compatible CLI behavior.
- Linux kernel cgroup v2, or cgroup v1 with memory and pids controllers enabled.
- Seccomp enabled. AppArmor is recommended on Ubuntu/Debian; SELinux can be used as an equivalent policy when documented and verified.
- A dedicated non-root container UID/GID, for example `65532:65532`.
- A runner image that contains the task runner from `runner/task-runner-spec.md`. Until Task 11 is complete, `LinuxContainerRunner` can probe the container boundary, but real judging will require the image command `/opt/judge-runner/run-task`.

Do not run the web service as root. The container runtime socket must be
restricted to the service account or to a remote worker boundary.

## Spring profile

Use `linux-prod` only after account authentication and sandbox capability smoke
have passed.

```yaml
spring:
  profiles:
    active: linux-prod

judge:
  auth:
    account-auth-enabled: true
    accounts:
      - user-id: admin-1
        username: admin
        password-hash: "$2a$..."
        admin: true
        enabled: true
  execution:
    profile: linux-prod
    require-sandbox: true
    max-cases-per-task: 100000
    max-concurrent-tasks: 1
    max-concurrent-cases-per-task: 4
  sandbox:
    enabled: true
    production:
      provider: linux-container
      isolation: container
      security-profile: seccomp:/etc/cpp-judge/seccomp.json,apparmor:cpp-judge
      capability-probe-required: true
      capability-probe-passed: false
      linux-container:
        runtime-command: docker
        image: cpp-judge-runner:latest
        container-user: "65532:65532"
        work-mount: /work
        runner-command: /opt/judge-runner/run-task
        task-spec-file: sandbox-task.json
        event-file: events.jsonl
        pids-limit: 64
        cpus: 1.0
        command-timeout: 30s
```

Keep `capability-probe-passed=false` until the exact host, runtime, image, and
security profile pass the smoke checks below. The startup validator intentionally
fails closed when probe evidence is missing.

## Capability probe

The application probe performs these checks:

```bash
docker version --format '{{.Server.Os}}|{{.Server.Version}}'
docker image inspect cpp-judge-runner:latest --format '{{.Os}}|{{.Architecture}}'
docker run --rm \
  --network none \
  --user 65532:65532 \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --tmpfs /run:rw,noexec,nosuid,size=16m \
  --memory 67108864 \
  --memory-swap 67108864 \
  --cpus 1 \
  --pids-limit 64 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --security-opt seccomp=/etc/cpp-judge/seccomp.json \
  --security-opt apparmor=cpp-judge \
  cpp-judge-runner:latest \
  /bin/sh -lc 'id -u; cat /proc/net/dev; cat /proc/self/status; test -e /sys/fs/cgroup/cgroup.controllers -o -e /sys/fs/cgroup/memory.max -o -e /sys/fs/cgroup/memory/memory.limit_in_bytes'
```

Expected evidence:

- Runtime server OS is `linux`.
- Image OS is `linux`.
- Network interfaces inside the container are loopback-only.
- `id -u` is not `0`.
- cgroup files are visible and memory/pids limits are applied by the runtime.
- `/proc/self/status` shows seccomp mode not `0` or an enforced AppArmor/SELinux equivalent.
- The command line contains `--cap-drop ALL`, `--read-only`, `--tmpfs`, `--pids-limit`, and `--memory`.

If any item fails, do not set `capability-probe-passed=true`.

## Smoke checks

Run the unit-level capability test first:

```bash
./mvnw -Dtest=LinuxContainerRunnerTest test
```

On a workstation without a Linux container runtime or without the runner image,
the live probe test may be skipped. The skip reason is valid for local
development only; it is not production release evidence.

Run these checks on the deployment host with the real runner image:

### Network disabled

```bash
docker run --rm --network none --user 65532:65532 cpp-judge-runner:latest \
  /bin/sh -lc 'awk -F: "NR>2 {gsub(/ /,\"\",\$1); print \$1}" /proc/net/dev'
```

Expected output: `lo` only. A task attempting outbound network access must fail.

### Pids limit

```bash
timeout 10s docker run --rm --network none --pids-limit 32 --cap-drop ALL \
  --security-opt no-new-privileges cpp-judge-runner:latest \
  /bin/sh -lc 'i=0; while :; do sleep 30 & i=$((i+1)); echo "$i"; done'
```

Expected result: process creation fails before the host is stressed. The
container exits or is killed by `timeout`; no residual container remains.

### Memory limit

Use an image that contains `python3` or build an equivalent tiny C program in
the runner image:

```bash
docker run --rm --network none --memory 64m --memory-swap 64m --pids-limit 64 \
  --cap-drop ALL --security-opt no-new-privileges cpp-judge-runner:latest \
  python3 -c 'a=[]; [a.append(bytearray(1024 * 1024)) for _ in range(256)]'
```

Expected result: the process is killed by the container memory limit or exits
with allocation failure. The host must remain healthy.

### Host path isolation

```bash
mkdir -p /srv/cpp-judge/smoke-work
echo secret-canary > /srv/cpp-judge/outside-canary
docker run --rm --network none --read-only --user 65532:65532 \
  --mount type=bind,source=/srv/cpp-judge/smoke-work,target=/work \
  cpp-judge-runner:latest \
  /bin/sh -lc 'test ! -e /outside-canary && test -d /work'
```

Expected result: the container sees `/work` and cannot see the host canary.
Do not mount the storage base, Docker socket, host root, compiler cache, or any
user-controlled absolute path.

### Timeout cleanup

```bash
cid=$(docker run -d --network none --pids-limit 32 --memory 64m --memory-swap 64m \
  --cap-drop ALL --security-opt no-new-privileges cpp-judge-runner:latest sleep 300)
docker rm -f "$cid"
docker ps --filter "id=$cid" --format '{{.ID}}'
```

Expected output after cleanup: empty.

## Large-case operation

For 100000+ cases, the host must have enough disk throughput for workdir files
and enough memory for the web process event aggregation. The application must
consume `events.jsonl` incrementally and keep WebSocket payloads bounded. Do not
configure one Java future, DOM node, or result object per case.

Recommended starting point:

- `judge.execution.max-cases-per-task=100000`
- `judge.execution.batch-size=100`
- `judge.execution.max-concurrent-tasks=1`
- `judge.execution.max-concurrent-cases-per-task=4`
- `judge.sandbox.production.linux-container.pids-limit=64`
- Place `judge.sandbox.base-directory` on a dedicated filesystem with quota and monitoring.

Run the Linux high-volume smoke before serving 100000+ case tasks:

```bash
bash scripts/smoke/high-volume-smoke.sh --cases 100000
```

Expected output includes `HIGH_VOLUME_SMOKE` lines for 100, 10000, and the
requested case count. Treat the run as passing only when `payloadBytes` stays
below the configured WebSocket payload budget, `schedulerTasks=1`, `pollCount`
is batched rather than equal to the case count, sample counts stay within
configuration, and throughput is printed for the deployment record. Continue to
monitor heap, workdir size, event file size, and residual container cleanup on
the real host.

## Failure modes

- `container runtime unavailable`: Docker/Podman CLI missing, daemon down, or
  service account cannot access the runtime.
- `container runtime is not Linux`: Docker Desktop or daemon is using Windows
  containers; switch to Linux engine or use `worker-prod`.
- `linux runner image unavailable`: image missing on host or wrong tag.
- `network must be disabled`: probe saw non-loopback interfaces; check runtime
  flags and default networks.
- `non-root user is required`: image or command overrode `--user`.
- `cgroup resource limits are required`: runtime is not applying memory/pids
  controls; check kernel controllers and rootless runtime limits.
- `security profile is required`: seccomp/AppArmor/SELinux is missing or
  unconfined.

Treat all of the above as release blockers for `linux-prod`.
