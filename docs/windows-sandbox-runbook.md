# Windows Sandbox Runbook

This runbook covers the `windows-prod` provider for production judging on
Windows. The production boundary is a Windows container with Hyper-V isolation.
Host process execution, Windows process isolation, and Job Object-only sandboxing
are not sufficient production boundaries for untrusted C++ code.

## Scope

- Provider: `judge.sandbox.production.provider=windows-container`
- Isolation: `--isolation hyperv`
- Network: `--network none` or a verified equivalent that disables outbound task traffic
- Required in-container controls: Job Object process-tree control, full child
  process kill on timeout/cancel, bounded stdout/stderr/output files, stable
  machine-readable `events.jsonl`
- Task execution: the web process starts a Hyper-V container and mounts only the
  judge work directory at `C:\work`. The in-container runner command is
  `C:\judge-runner\run-task.exe C:\work\sandbox-task.json`.

Linux note: use `linux-prod` or `worker-prod` for Linux deployment. Windows
Hyper-V evidence does not prove the Linux container boundary, and Linux Docker
Desktop evidence does not prove Windows Hyper-V isolation.

## Host prerequisites

- Windows Server with Hyper-V enabled. Windows client is acceptable for local
  smoke only, not release evidence unless it is the actual deployment target.
- Docker/Mirantis Container Runtime configured for Windows containers.
- A Windows runner image whose OS version is compatible with the host.
- The runner image contains:
  - `C:\judge-runner\probe.cmd`
  - `C:\judge-runner\run-task.exe`
  - Job Object enforcement for the compiler, generator, user program, oracle,
    special judge, and all child processes.
- The web service account can start and remove containers, but must not expose
  the Docker named pipe to user code or mounted task directories.

## Spring profile

```yaml
spring:
  profiles:
    active: windows-prod

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
    profile: windows-prod
    require-sandbox: true
    max-cases-per-task: 100000
  sandbox:
    enabled: true
    production:
      provider: windows-container
      isolation: hyper-v
      security-profile: hyper-v;job-object
      capability-probe-required: true
      capability-probe-passed: false
      windows-container:
        runtime-command: docker
        image: cpp-judge-runner-windows:latest
        container-user: ContainerUser
        work-mount: "C:\\work"
        runner-command: "C:\\judge-runner\\run-task.exe"
        probe-command: "C:\\judge-runner\\probe.cmd"
        task-spec-file: sandbox-task.json
        event-file: events.jsonl
        cpus: 1.0
        probe-memory-bytes: 268435456
        command-timeout: 30s
```

Keep `capability-probe-passed=false` until the exact deployment host, runtime,
image, and probe command pass the checks below.

## Capability probe

The application probe checks runtime OS, image OS, Hyper-V isolation, network
mode, memory limit, runner-reported Job Object support, output-limit support,
and cleanup.

Equivalent manual commands:

```powershell
docker version --format "{{.Server.Os}}|{{.Server.Version}}"
docker image inspect cpp-judge-runner-windows:latest --format "{{.Os}}|{{.Architecture}}"

$cid = docker run -d `
  --name cpp-judge-win-probe `
  --isolation hyperv `
  --network none `
  --user ContainerUser `
  --memory 268435456 `
  --cpus 1 `
  cpp-judge-runner-windows:latest `
  C:\judge-runner\probe.cmd

docker inspect --format "{{.HostConfig.Isolation}}|{{.HostConfig.NetworkMode}}|{{.HostConfig.Memory}}|{{.State.ExitCode}}" $cid
docker logs $cid
docker rm -f $cid
```

Expected evidence:

- Runtime server OS is `windows`.
- Image OS is `windows`.
- Inspect output isolation is `hyperv`; `process` is a release blocker.
- Inspect output network mode is `none` or the documented equivalent.
- Inspect output memory is greater than `0`.
- Probe logs include:
  - `PROBE_JOB_OBJECT=enabled`
  - `PROBE_PROCESS_TREE_KILL=enabled`
  - `PROBE_OUTPUT_LIMIT=enabled`
- Cleanup removes the probe container.

## Smoke checks

Run the unit-level capability test first:

```powershell
.\mvnw.cmd -Dtest=WindowsHyperVContainerRunnerTest test
```

On a machine without Docker Windows containers or without the runner image, the
live probe test may be skipped. The skip reason is valid for local development
only; it is not production release evidence.

### Hyper-V isolation

```powershell
$cid = docker run -d --isolation hyperv --network none cpp-judge-runner-windows:latest ping -t 127.0.0.1
docker inspect --format "{{.HostConfig.Isolation}}" $cid
docker rm -f $cid
```

Expected output: `hyperv`.

### Network disabled

```powershell
$cid = docker run -d --isolation hyperv --network none cpp-judge-runner-windows:latest powershell -NoProfile -Command "Start-Sleep 30"
docker inspect --format "{{.HostConfig.NetworkMode}}" $cid
docker rm -f $cid
```

Expected output: `none`. A user program attempting outbound network access must
fail.

### Workdir-only mount

```powershell
New-Item -ItemType Directory -Force C:\cpp-judge-smoke\work | Out-Null
"secret-canary" | Set-Content C:\cpp-judge-smoke\outside-canary.txt
docker run --rm --isolation hyperv --network none `
  --mount type=bind,source=C:\cpp-judge-smoke\work,target=C:\work `
  cpp-judge-runner-windows:latest `
  powershell -NoProfile -Command "if (Test-Path C:\outside-canary.txt) { exit 1 }; if (!(Test-Path C:\work)) { exit 2 }"
```

Expected result: exit code `0`. Do not mount the storage base, Docker named
pipe, host root, compiler cache, or any user-controlled absolute path.

### Process-tree kill

The runner probe should perform this check internally. Manual equivalent:

```powershell
docker run --rm --isolation hyperv --network none cpp-judge-runner-windows:latest `
  C:\judge-runner\probe.cmd
```

Expected logs:

```text
PROBE_JOB_OBJECT=enabled
PROBE_PROCESS_TREE_KILL=enabled
```

If a timeout/cancel leaves child compiler or user-program processes alive inside
the container, the runner image fails production acceptance.

### Memory and output limits

Container memory must be enforced by Docker/Hyper-V:

```powershell
$cid = docker run -d --isolation hyperv --network none --memory 268435456 `
  cpp-judge-runner-windows:latest powershell -NoProfile -Command "Start-Sleep 30"
docker inspect --format "{{.HostConfig.Memory}}" $cid
docker rm -f $cid
```

Expected output: a non-zero memory limit.

Output limits are enforced by the in-container runner. The probe must report
`PROBE_OUTPUT_LIMIT=enabled`, and Task 11 must include runner artifact tests for
oversized stdout/stderr/output files.

### Cleanup

```powershell
$cid = docker run -d --isolation hyperv --network none cpp-judge-runner-windows:latest ping -t 127.0.0.1
docker rm -f $cid
docker ps --filter "id=$cid" --format "{{.ID}}"
```

Expected output after cleanup: empty.

## Large-case operation

For 100000+ cases, keep the web process out of per-case process management.
The Windows provider should start one task container per judgeId; the in-container
runner emits bounded events and summaries. Do not create one Java future, DOM
node, WebSocket payload, or retained result object per case.

Recommended starting point:

- `judge.execution.max-cases-per-task=100000`
- `judge.execution.max-concurrent-tasks=1`
- `judge.execution.max-concurrent-cases-per-task=4`
- `judge.sandbox.production.windows-container.cpus=1.0`
- Put `judge.sandbox.base-directory` on a dedicated disk with cleanup and quota.

High-volume smoke scripts are completed in Task 15. Until then, release evidence
for 100000+ Windows tasks requires manual monitoring of heap, task directory
size, event file size, container count, and process cleanup.

## Failure modes

- `container runtime unavailable`: Docker CLI missing, daemon down, service
  account lacks access, or Windows container mode is not enabled.
- `container runtime is not Windows`: Docker is using Linux containers; switch
  to Windows containers or use another provider.
- `windows runner image unavailable`: image missing, wrong tag, or OS version
  mismatch.
- `hyper-v isolation is required`: runtime reported `process`; block release.
- `network must be disabled`: inspect reported any network mode other than
  `none` or a documented equivalent.
- `memory limit is required`: inspect reported memory `0`.
- `job object process-tree control is required`: runner probe did not prove
  child-process kill.
- `container cleanup confirmation is required`: probe/start/cancel left residual
  containers.

Treat all of the above as release blockers for `windows-prod`.
