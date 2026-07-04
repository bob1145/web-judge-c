# Production Sandbox Runbook

This runbook is the release gate for running untrusted C++ judging in
production. Do not serve public or multi-user traffic until the selected
profile passes its capability smoke on the actual host or worker used for
deployment.

## Profiles

### trusted-local

Use only for local development on a trusted machine.

```yaml
spring.profiles.active: trusted-local
judge.execution.profile: trusted-local
judge.execution.require-sandbox: false
judge.sandbox.enabled: false
```

Release rule: never expose this profile to public users. `trusted-local` may
warn about a default access code, wildcard origin, disabled sandbox, and direct
runner behavior; those warnings are blockers outside local development.

### windows-prod

Use for a Windows deployment only when Windows containers run with Hyper-V
isolation.

```yaml
spring.profiles.active: windows-prod
judge.execution.profile: windows-prod
judge.execution.require-sandbox: true
judge.execution.max-cases-per-task: 100000
judge.execution.max-output-bytes-per-case: 16777216
judge.sandbox.enabled: true
judge.sandbox.production.provider: windows-container
judge.sandbox.production.isolation: hyper-v
judge.sandbox.production.security-profile: hyper-v;job-object
judge.sandbox.production.capability-probe-required: true
judge.sandbox.production.capability-probe-passed: false
```

Validation command:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/windows-capability-smoke.ps1
```

Required evidence:

- Windows Hyper-V isolation is used; process isolation is rejected.
- Network disabled means container inspect shows `--network none` or an
  approved equivalent.
- Resource limits include memory, CPU, output, and process-tree kill evidence.
- Path isolation mounts only the task work directory, not the storage base,
  Docker pipe, host root, compiler cache, or a user-controlled absolute path.
- Job Object support is only an in-container process-control layer; it is not a
  production security boundary by itself.
- Default access code and wildcard origin are rejected by startup validation.

### linux-prod

Use for a Linux deployment only when Linux containers enforce the kernel-level
boundary.

```yaml
spring.profiles.active: linux-prod
judge.execution.profile: linux-prod
judge.execution.require-sandbox: true
judge.execution.max-cases-per-task: 100000
judge.execution.max-output-bytes-per-case: 16777216
judge.sandbox.enabled: true
judge.sandbox.production.provider: linux-container
judge.sandbox.production.isolation: container
judge.sandbox.production.security-profile: seccomp;apparmor
judge.sandbox.production.capability-probe-required: true
judge.sandbox.production.capability-probe-passed: false
```

Validation command:

```bash
bash scripts/smoke/linux-capability-smoke.sh
```

Required evidence:

- Network disabled means Docker inspect or runtime output proves `--network none`.
- Resource limits include cgroup memory, CPU, and `--pids-limit`.
- The task runs as a non-root user with a read-only root filesystem.
- seccomp and AppArmor are enabled, or an explicitly documented equivalent is
  enabled by the host platform.
- Path isolation mounts only the task work directory at `/work`.
- Direct Linux process execution is rejected for production.
- Default access code and wildcard origin are rejected by startup validation.

### worker-prod

Use when the web service submits tasks to authenticated remote workers.

```yaml
spring.profiles.active: worker-prod
judge.execution.profile: worker-prod
judge.execution.require-sandbox: true
judge.execution.max-cases-per-task: 100000
judge.execution.max-output-bytes-per-case: 16777216
judge.sandbox.enabled: true
judge.sandbox.production.provider: remote-worker
judge.worker.endpoint: https://worker.example.internal
judge.worker.auth-token: ${JUDGE_WORKER_TOKEN}
```

Required evidence:

- Worker endpoint is authenticated with a token, mTLS, or a signed request
  scheme.
- Worker capability probe reports `productionSafe=true`.
- Worker host passes either the Windows Hyper-V or Linux container smoke,
  depending on its platform.
- Event polling/streaming, cancellation, stale run handling, and cleanup are
  verified.

## Release Validation

Run these before setting `capability-probe-passed=true` or opening traffic:

```powershell
.\mvnw.cmd test
powershell -ExecutionPolicy Bypass -File scripts/smoke/windows-capability-smoke.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/high-volume-smoke.ps1 -Cases 100000
```

```bash
bash scripts/smoke/linux-capability-smoke.sh
bash scripts/smoke/high-volume-smoke.sh --cases 100000
```

On a Windows operator machine with WSL, this is useful as a Linux script
portability precheck:

```powershell
wsl.exe -- bash -lc "cd /mnt/c/path/to/cpp && bash scripts/smoke/linux-capability-smoke.sh --dry-run"
wsl.exe -- bash -lc "cd /mnt/c/path/to/cpp && bash scripts/smoke/high-volume-smoke.sh --cases 100000"
```

WSL prechecks do not replace evidence from the actual Linux container host or
remote worker host.

## Large Output Tuning

The production profiles keep the per-case captured input/output cap explicit:

```yaml
judge:
  execution:
    max-output-bytes-per-case: 16777216
```

For one-off deployment overrides, use the Spring environment variable form:

```powershell
$env:JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE = "16777216"
```

```bash
export JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE=16777216
```

If a generator creates a single test case larger than this cap, the task should
finish as an output-limit failure instead of staying `RUNNING`. Increase this
only with disk quota, cleanup, and sandbox evidence in place.

## Failure Modes

Block release when any of these happens:

- default access code is accepted under a production profile.
- wildcard origin is accepted under a production profile.
- sandbox capability probe is missing, skipped without a deployment reason, or
  reports unsafe isolation.
- network disabled evidence is missing.
- resource limits are missing or zero.
- path isolation allows reads or writes outside the task directory.
- process-tree kill leaves compiler, generator, user, oracle, or SPJ child
  processes alive after timeout or cancellation.
- high-volume smoke shows per-case Java futures, WebSocket messages, DOM nodes,
  retained result lists, or payload sizes growing linearly with cases.

## Rollback

Release rollback is mandatory when any production smoke fails.

If a smoke fails after deployment:

1. Remove public traffic from the instance.
2. Set `judge.sandbox.production.capability-probe-passed=false`.
3. Disable new task creation by setting queue capacity to `0` or taking the web
   instance out of the load balancer.
4. Cancel running tasks and run cleanup reconciliation.
5. Revert to the last commit whose full regression and platform smoke passed.
6. Keep `trusted-local` available only for local diagnostics, never as a
   production fallback.

## Related Documents

- `docs/security-boundary.md`
- `docs/windows-sandbox-runbook.md`
- `docs/linux-sandbox-runbook.md`
- `scripts/smoke/windows-capability-smoke.ps1`
- `scripts/smoke/linux-capability-smoke.sh`
- `scripts/smoke/high-volume-smoke.ps1`
- `scripts/smoke/high-volume-smoke.sh`
