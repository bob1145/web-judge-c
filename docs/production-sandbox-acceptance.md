# Production Sandbox Acceptance

This document is the final release gate for the production sandbox work. It maps
each requirement to concrete tests, smoke scripts, and runbooks.

Release status: BLOCKED until live platform smoke passes on the deployment target

The application-side regression, high-volume path, documentation gate, and local
script prechecks pass in this workspace. Production traffic must still remain
closed because Docker CLI unavailable on this Windows workstation, so no live
Windows Hyper-V container or live Linux container capability evidence was
collected here. WSL precheck is not production release evidence; it only proves
that the Linux shell scripts are portable from a Windows operator machine.

## Required Release Commands

Run these before opening production traffic:

```powershell
.\mvnw.cmd test
.\mvnw.cmd "-Dtest=ProductionHighVolumeIntegrationTest" test
powershell -ExecutionPolicy Bypass -File scripts/smoke/windows-capability-smoke.ps1 -RequireDocker
powershell -ExecutionPolicy Bypass -File scripts/smoke/high-volume-smoke.ps1 -Cases 100000
```

```bash
bash scripts/smoke/linux-capability-smoke.sh --require-docker
bash scripts/smoke/high-volume-smoke.sh --cases 100000
```

On Windows with WSL available, the operator may run this additional portability
precheck:

```powershell
wsl.exe -- bash -lc "cd /mnt/c/path/to/cpp && bash scripts/smoke/linux-capability-smoke.sh --dry-run --skip-maven"
```

That WSL command does not replace the live Linux host smoke.

## Fresh Evidence In This Workspace

- Full regression: `.\mvnw.cmd test` passed with 167 tests run, 0 failures, 0 errors, 4 skipped.
- High-volume integration: `.\mvnw.cmd "-Dtest=ProductionHighVolumeIntegrationTest" test` passed with 100, 10000, and 100000 case evidence; the 100000 case run reported `schedulerTasks=1`, `peakInFlightSchedulerTasks=1`, `pollCount=196`, `payloadBytes=1069`, `websocketMessages=4`, `failureSamples=5`, and `slowSamples=7`.
- Windows capability precheck: `powershell -ExecutionPolicy Bypass -File scripts/smoke/windows-capability-smoke.ps1 -WhatIf` exited 0, but it is local precheck evidence only because Docker CLI was unavailable.
- Linux syntax precheck: `bash -n scripts/smoke/linux-capability-smoke.sh` exited 0.
- WSL precheck: `wsl.exe -- bash -lc "cd /mnt/c/tmp/codex-production-sandbox-tasks && bash scripts/smoke/linux-capability-smoke.sh --dry-run --skip-maven"` exited 0 and printed a Docker command containing `--network none`, `--pids-limit`, `--read-only`, non-root user, seccomp, and AppArmor.
- High-volume Windows smoke: `powershell -ExecutionPolicy Bypass -File scripts/smoke/high-volume-smoke.ps1 -Cases 100000` passed with 2 tests run, 0 failures, 0 errors, 0 skipped.
- High-volume Linux/WSL smoke: `bash scripts/smoke/high-volume-smoke.sh --cases 100000` passed with 2 tests run, 0 failures, 0 errors, 0 skipped.
- `git diff --check` exited 0; Windows reported CRLF conversion warnings only.

## Completion Matrix

| Requirement | Status | Evidence | Release Gate |
| --- | --- | --- | --- |
| Requirement 1: cross-platform production sandbox boundary | Code and documentation evidence present; live platform proof pending | `ProductionSecurityStartupValidatorTest`, `WindowsHyperVContainerRunnerTest`, `LinuxContainerRunnerTest`, `RemoteWorkerRunnerTest`, `ProductionRunbookDocumentationTest`, `docs/security-boundary.md` | Live Windows or Linux capability smoke must pass on the deployment target. |
| Requirement 2: unified SandboxRunner contract | Accepted | `SandboxRunnerContractTest`, `JudgeSandboxOrchestrationTest`, `SandboxEventIngestorTest`, `RemoteWorkerRunnerTest` | No additional blocker beyond full regression. |
| Requirement 3: Windows production sandbox | Code and documentation evidence present; live Hyper-V proof pending | `WindowsHyperVContainerRunnerTest`, `TaskRunnerArtifactContractTest`, `ProductionCleanupTest`, `scripts/smoke/windows-capability-smoke.ps1`, `docs/windows-sandbox-runbook.md` | Run `scripts/smoke/windows-capability-smoke.ps1 -RequireDocker` on the Windows deployment host and verify Hyper-V isolation. |
| Requirement 4: Linux production sandbox | Code and documentation evidence present; live container proof pending | `LinuxContainerRunnerTest`, `TaskRunnerArtifactContractTest`, `ProductionCleanupTest`, `scripts/smoke/linux-capability-smoke.sh`, `docs/linux-sandbox-runbook.md` | Run `scripts/smoke/linux-capability-smoke.sh --require-docker` on the Linux deployment host and verify cgroup, pids, memory, CPU, network, non-root, seccomp, and AppArmor. |
| Requirement 5: high-volume task execution | Accepted in application path | `ProductionHighVolumeIntegrationTest`, `HighVolumeJudgeIntegrationTest`, `SandboxEventIngestorTest`, `ProgressPublisherTest`, `scripts/smoke/high-volume-smoke.ps1`, `scripts/smoke/high-volume-smoke.sh` | Re-run the 100000 case smoke on the final host size before traffic. |
| Requirement 6: resource budgets and quotas | Accepted | `TaskPolicyResolverTest`, `QuotaAndAuthorizationTest`, `JudgeSchedulerTest`, `TaskRunnerArtifactContractTest` | Tune production quotas before launch. |
| Requirement 7: strong authentication, authorization, and session safety | Accepted for production gate | `ProductionAuthenticationTest`, `QuotaAndAuthorizationTest`, `AuditAndAdminTest`, `ProductionSecurityStartupValidatorTest`, `SecurityModeStartupValidatorTest` | default credentials rejected; wildcard origin rejected; external identity or configured accounts required. |
| Requirement 8: file, mount, and download safety | Accepted | `JudgeFileServiceProductionTest`, `JudgeFileServiceTest`, `FileTaskStoreTest`, `ProductionCleanupTest` | Verify host storage paths before launch. |
| Requirement 9: observability, audit, and operational control | Accepted | `AuditAndAdminTest`, `SandboxEventIngestorTest`, `ProductionCleanupTest`, `ProgressPublisherTest` | Configure log retention and alerting before launch. |
| Requirement 10: stress, fault injection, and release validation | Gate partially complete; live platform proof pending | `ProductionHighVolumeIntegrationTest`, `ProductionRunbookDocumentationTest`, `WindowsHyperVContainerRunnerTest`, `LinuxContainerRunnerTest`, high-volume smoke scripts, capability smoke scripts | Do not open traffic until live platform capability smoke and 100000 case smoke pass on the actual host. |

## Security Gate Assertions

- direct runner is rejected in production profiles by startup validation.
- default credentials rejected in production profiles.
- wildcard origin rejected in production profiles.
- Windows Job Object-only is documented and tested as not sufficient for a production boundary.
- Linux direct process execution is documented and tested as not production safe.
- Remote worker production mode requires authenticated communication.
- Task details, downloads, cancellation, and admin queue views require owner or admin authorization.
- File access and cleanup reject storage-base escapes, symlink escapes, junction escapes, and reparse-point escapes where supported by the host.
- High-volume progress omits full 100000+ result lists and uses bounded summaries, samples, and WebSocket messages.

## Release Decision

This branch is ready for target-host release validation, not for opening public
traffic by itself. The next release owner must attach live smoke output from the
actual Windows Hyper-V host, Linux container host, or authenticated remote worker
host. If any live capability smoke fails, keep `judge.sandbox.production.capability-probe-passed=false`,
remove public traffic, and follow `docs/production-sandbox-runbook.md`.
