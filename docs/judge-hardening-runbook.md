# Judge Hardening Runbook

This runbook covers the supported local and intranet operating modes for the C++ judge after the high-volume hardening work.

## Modes

### trusted-local

Use this mode only on a developer workstation or another machine that is not exposed to untrusted users.

```yaml
judge:
  execution:
    profile: trusted-local
    max-cases-per-task: 10000
    large-mode-threshold: 5000
    max-concurrent-tasks: 1
    max-concurrent-cases-per-task: 4
    batch-size: 100
    require-sandbox: false
app:
  websocket:
    allowed-origins: "http://localhost:*,https://localhost:*"
```

Startup command:

```powershell
mvn -s C:\Users\ywy\.m2\settings.xml "-Dmaven.repo.local=C:\Users\ywy\.m2\repository" spring-boot:run
```

Expected logs:

```text
HIGH RISK trusted-local configuration: default access code
HIGH RISK trusted-local configuration: sandbox is disabled
```

### local-large

Use this mode for local 100000-case validation. Keep it local unless a separate secure deployment review has been completed.

```yaml
spring:
  profiles:
    active: local-large
judge:
  execution:
    profile: local-large
    max-cases-per-task: 100000
    large-mode-threshold: 5000
    task-queue-capacity: 3
    max-concurrent-tasks: 1
    max-concurrent-cases-per-task: 4
    batch-size: 100
    max-task-runtime: 2h
    max-output-bytes-per-case: 16777216
    require-sandbox: false
```

Startup command:

```powershell
mvn -s C:\Users\ywy\.m2\settings.xml "-Dmaven.repo.local=C:\Users\ywy\.m2\repository" spring-boot:run "-Dspring-boot.run.profiles=local-large"
```

Expected logs:

```text
HIGH RISK trusted-local configuration is not expected in local-large.
Judge task queued for execution
```

### intranet-large

Use this mode only on a controlled intranet with a non-default access code, restricted WebSocket origins, and sandbox enforcement.

```yaml
spring:
  profiles:
    active: intranet-large
app:
  websocket:
    allowed-origins: "https://judge.intranet.example"
judge:
  auth:
    access-code: "${JUDGE_ACCESS_CODE}"
  execution:
    profile: intranet-large
    max-cases-per-task: 100000
    large-mode-threshold: 5000
    task-queue-capacity: 20
    max-concurrent-tasks: 2
    max-concurrent-cases-per-task: 4
    batch-size: 100
    max-task-runtime: 2h
    max-output-bytes-per-case: 16777216
    require-sandbox: true
  sandbox:
    enabled: true
```

Startup command:

```powershell
$env:JUDGE_ACCESS_CODE = "replace-with-a-long-random-secret"
mvn -s C:\Users\ywy\.m2\settings.xml "-Dmaven.repo.local=C:\Users\ywy\.m2\repository" spring-boot:run "-Dspring-boot.run.profiles=intranet-large"
```

Expected logs:

```text
Started Demo18Application
No HIGH RISK trusted-local configuration warnings
Sandbox process runner selected for judge execution
```

## Output Limit Tuning

`judge.execution.max-output-bytes-per-case` controls the maximum captured bytes
for one test case's generated input, stdout, and stderr. The default profile
keeps this at `1048576` bytes. The large-case profiles in `application.yml`
raise it to `16777216` bytes.

Override it per deployment when cases are larger:

```powershell
$env:JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE = "16777216"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local-large"
```

```bash
export JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE=16777216
./mvnw spring-boot:run -Dspring-boot.run.profiles=linux-prod
```

Do not set this to an unbounded value. Worst-case temporary disk pressure is
roughly `testCases * max-output-bytes-per-case * generated artifacts per case`.
For public or intranet deployments, pair a larger cap with a sandbox, cleanup,
disk quota, and monitoring.

## Validation

Run automated checks:

```powershell
mvn -s C:\Users\ywy\.m2\settings.xml "-Dmaven.repo.local=C:\Users\ywy\.m2\repository" "-Dtest=HighVolumeJudgeIntegrationTest" test
mvn -s C:\Users\ywy\.m2\settings.xml "-Dmaven.repo.local=C:\Users\ywy\.m2\repository" test
```

Expected automated coverage:

- 10-case HTTP AC flow preserves complete small-task `results`.
- 1000-case synthetic flow verifies batching, throttled progress publishing, summary counters, and bounded failure samples.
- 100000-case synthetic flow passes through policy resolution, scheduler, batch runner, aggregator, task store, and progress publisher. It asserts peak in-flight futures, final payload size, failure sample count, and final summary counters.

curl validation:

```powershell
curl -i -X POST http://localhost:1234/judge ^
  -H "Content-Type: application/json" ^
  -H "X-Session-ID: <session-id>" ^
  --data "@request.json"

curl -i -X POST http://localhost:1234/judge/start/<judge-id> -H "X-Session-ID: <session-id>"
curl -i http://localhost:1234/judge/status/<judge-id> -H "X-Session-ID: <session-id>"
```

Browser validation:

Use browser validation for UI behavior that curl cannot cover.

- Open `http://localhost:1234`.
- Sign in with the configured access code.
- Submit a 10-case sample and confirm the result grid, detail modal, and single-case download still work.
- Submit a large-mode sample and confirm the page renders summary counters and failure samples instead of creating one DOM card per test case.

## Risk

Public exposure is unsupported without a separate hardening project that adds strong authentication, a mandatory sandbox, audit logging, tenant isolation, rate limiting, and host-level process isolation. The current supported modes are local trusted use and controlled intranet use.

High-volume mode increases CPU, process, disk, and queue pressure. Keep `max-concurrent-tasks`, `max-concurrent-cases-per-task`, `batch-size`, `max-task-runtime`, and cleanup retention explicit for each deployment.

## Rollback

Rollback options:

- Switch from `local-large` or `intranet-large` back to `trusted-local` and reduce `judge.execution.max-cases-per-task`.
- Reduce `judge.execution.task-queue-capacity` to stop new large queues from accumulating.
- Temporarily set a shorter `judge.execution.max-task-runtime` to drain risky workloads.
- Revert the deployment to the last known good Git commit and restart the service.
- Preserve the judge storage directory until any active incident review finishes; cleanup can remove expired terminal tasks after the review.
