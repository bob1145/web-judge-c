# Production Sandbox Runbook

This runbook is the release gate for running untrusted C++ judging in
production. Do not serve public or multi-user traffic until the selected
profile passes its capability smoke on the actual host or worker used for
deployment.

## TASK-10 development gate (2026-10-03)

The current Linux shared-workdir adapter is not production-capable. Linux
startup now checks the actual selected runner for trusted control-plane
isolation and the other required capabilities; configuration-only probe
approval cannot override a failed or unavailable result. Non-trusted-local
calls to the legacy Linux execution path are rejected before writing a
manifest or launching a task. The independent execution-unit implementation
and real isolation acceptance are still pending; this gate is not completion
of TASK-10 or release approval. Do not switch to trusted-local to bypass it.
Other providers still require their own real acceptance evidence.

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

Current status: disabled for untrusted production execution. The legacy Windows
adapter shares its control domain with workload processes and explicitly reports
`controlPlaneIsolated=false` and `productionSafe=false`. Hyper-V and a configured
`capability-probe-passed=true` cannot override this rejection. Startup consumes
the selected runner probe; missing, failed or contradictory results fail closed.
The configuration and validation checklist below describe target requirements,
not an accepted Windows implementation. Do not bypass by selecting trusted-local.

Use for a Windows deployment only when Windows containers run with Hyper-V
isolation.

```yaml
spring.profiles.active: windows-prod
judge.execution.profile: windows-prod
judge.execution.require-sandbox: true
judge.execution.max-cases-per-task: 100000
judge.execution.max-output-bytes-per-case: 16777216
judge.execution.max-detail-preview-bytes: 65536
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
judge.execution.max-detail-preview-bytes: 65536
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
- Untrusted execution units must not receive the whole task work directory:
  expose only stage-specific inputs and scratch, never host audit or control
  files. The legacy whole-workdir mount does not satisfy this requirement.
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
judge.execution.max-detail-preview-bytes: 65536
judge.sandbox.enabled: true
judge.sandbox.production.provider: remote-worker
judge.worker.endpoint: https://worker.example.internal
judge.worker.auth-token: ${JUDGE_WORKER_TOKEN}
```

Required evidence:

- Worker endpoint uses HTTPS with normal server-certificate and hostname validation,
  and the existing Bearer token and/or HMAC-SHA256 request-signature protocol.
- mTLS is not implemented in this release. Any nonempty mtls-certificate setting
  is rejected, including configurations that also provide Bearer/HMAC credentials;
  a certificate-path string or the removed X-Judge-Worker-Mtls header is not authentication.
- Configure judge.worker or judge.sandbox.production.worker consistently. Nonblank
  endpoint, auth-token, signing-secret and mtls-certificate values must agree if
  present in both locations; conflicting values fail startup rather than silently
  overriding another source. Both configuration locations support signing-secret.
- All remote-worker endpoints require HTTPS, including local development clients.
  Endpoints containing user-info, query strings or fragments are rejected without
  echoing the supplied value. Redirect responses are not followed; configure the
  intended final endpoint explicitly rather than forwarding credentials elsewhere.
- Worker capability probe reports `productionSafe=true` and an explicit v2 `protocol`
  object supporting the requested comparison, all required fields, and
  `inline-sources-base64`. The v2 runs body is a manifest plus source-byte bundle,
  not the host SandboxTaskSpec. A response must echo protocolVersion/judgeId/attemptId.
  See runner/task-runner-spec.md; client TLS fixtures are not external Worker acceptance.
- Worker host passes either the Windows Hyper-V or Linux container smoke,
  depending on its platform.
- Event polling/streaming, cancellation, stale run handling, and cleanup are
  verified.

TASK-08 uses ephemeral CA-signed certificates and real loopback TLS handshakes to
validate the Java client. Those protocol fixtures do not attest an external Worker:
server-side signature verification, replay protection, execution isolation and the
actual deployment endpoint still require TASK-26 evidence. Do not set production
capability flags or enable an unverified provider based only on these client tests.

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
    max-detail-preview-bytes: 65536
```

For one-off deployment overrides, use the Spring environment variable form:

```powershell
$env:JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE = "16777216"
$env:JUDGE_EXECUTION_MAX_DETAIL_PREVIEW_BYTES = "65536"
```

```bash
export JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE=16777216
export JUDGE_EXECUTION_MAX_DETAIL_PREVIEW_BYTES=65536
```

If a generator creates a single test case larger than this cap, the task should
finish as an output-limit failure instead of staying `RUNNING`. Increase this
only with disk quota, cleanup, and sandbox evidence in place.

`max-detail-preview-bytes` only controls how many bytes the browser details
modal reads per input/output file. Keep it much smaller than very large archives
and use failed-case downloads when the full data point is needed.

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

## TASK-09 protocol cutover (not production acceptance)

- Drain or terminate active runs in a maintenance window. Upgrade the host and
  actual runner images/Windows artifact/Worker as a matching set; no mixed v1/v2
  execution or rolling-upgrade compatibility is promised.
- Package both task_runner.py and runner_manifest.py in Python-based artifacts.
  Container entrypoints must implement --capabilities; old artifacts are rejected.
- Replace task-spec-file/event-file overrides with task-spec.json/runner-events.json.
  They are fixed basenames under a fresh control/attempts/{attemptId}, not task-root
  host events.jsonl. Never reinterpret old mixed event files as a trusted runner stream.
- Keep old completed tasks read-only. Do not move or replay their files as part of this
  change. New handles persist protocolVersion and attemptId alongside provider runId.
- The current Python artifact does not advertise DECIMAL_ABSOLUTE. Ordinary container
  requests fail closed until TASK-16; SPJ/LEGACY_TEXT protocol fixtures do not certify
  permission isolation, incremental reads, resource enforcement or real platforms.
- Rollback requires matching host/artifact/Worker versions and a reviewed maintenance
  backup. Keep v2 tasks read-only for older binaries; do not restore shared event files,
  URL credentials, weak authentication or unvalidated production providers.
