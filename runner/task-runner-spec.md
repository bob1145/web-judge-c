# Task Runner Specification

## Purpose

The task runner is the stable execution contract inside platform sandbox
providers. Container runners and remote workers may differ in how they isolate
the process, but they must produce the same event stream, summary shape, and
exit codes.

## Input Contract

Input is one JSON file containing `SandboxTaskSpec`.

Required fields:

- `judgeId`: stable task id.
- `userId`: task owner id.
- `profile`: execution profile snapshot.
- `workDir`: absolute mounted work directory.
- `sourcePaths`: role-to-source map. Supported roles are `GENERATOR`, `USER`,
  `ORACLE`, and `SPECIAL_JUDGE`.
- `testCases`: positive case count.
- `caseTimeLimit`: seconds number or ISO-8601 duration.
- `maxTaskRuntime`: seconds number or ISO-8601 duration.
- `memoryLimitBytes`: per-process memory budget.
- `maxOutputBytesPerCase`: per-process stdout/stderr/output budget.
- `retention`: retained for web-service cleanup policy; the runner does not
  decide retention.

Security invariant:

```text
task spec path and every source path must resolve inside workDir
```

Violation produces `SECURITY_VIOLATION` and exit code `30`.

## Event Contract

Events are JSON Lines in `events.jsonl`. Each line maps to
`com.example.demo.dto.SandboxTaskEvent`.

Required event fields:

- `judgeId`
- `type`
- `occurredAt`
- `message`

Optional event fields:

- `caseNumber`
- `status`
- `result`
- `summary`

Event types used by this runner:

- `COMPILE_STARTED`
- `COMPILE_FINISHED`
- `RUN_STARTED`
- `RUN_FINISHED`
- `SUMMARY`
- `COMPLETED`
- `CANCELLED`
- `BUDGET_EXCEEDED`
- `SECURITY_VIOLATION`
- `SYSTEM_ERROR`

Compile failure uses `COMPILE_FINISHED` with status `COMPILE_ERROR`, followed
by terminal `SYSTEM_ERROR`. Compiler output in event messages is bounded to
4096 bytes.

## Summary Contract

`summary.json` maps to `JudgeSummary`:

- `totalCases`
- `completedCases`
- `ac`
- `wa`
- `tle`
- `mle`
- `re`
- `systemError`
- `outputLimitExceeded`
- `firstFailedCase`
- `failureSamples`
- `slowSamples`
- `stoppedReason`

The runner writes `summary.json.tmp` first and then atomically replaces
`summary.json`. Partial summaries must be recoverable for cancellation and task
budget expiry.

## Verdict Mapping

| Runner condition | Case status | Summary counter |
| --- | --- | --- |
| User output equals oracle output | `AC` | `ac` |
| User output differs from oracle output | `WA` | `wa` |
| User process timeout | `TLE` | `tle` |
| User process memory monitor kills process | `MLE` | `mle` |
| User process exits non-zero | `RE` | `re` |
| stdout/stderr/output exceeds `maxOutputBytesPerCase` | `OUTPUT_LIMIT_EXCEEDED` | `outputLimitExceeded` |
| Generator/oracle/SPJ infrastructure failure | `System Error` | `systemError` |

## Exit Codes

| Code | Symbol | Meaning |
| ---: | --- | --- |
| `0` | `COMPLETED` | Runner completed normally and wrote summary. |
| `10` | `COMPILE_ERROR` | Compilation failed or timed out. |
| `20` | `CANCELLED` | `cancel.requested` was observed; child process tree was killed. |
| `21` | `BUDGET_EXCEEDED` | `maxTaskRuntime` was exceeded. |
| `30` | `SECURITY_VIOLATION` | Task paths violated the mounted workdir boundary. |
| `40` | `SYSTEM_ERROR` | Runner failed before it could produce a normal verdict. |

## Cancellation

The provider requests cancellation by creating:

```text
workDir/cancel.requested
```

The runner must kill the current process tree, write partial summary, emit
`CANCELLED`, and exit `20`.

## Platform Boundary

This spec is not a full sandbox. It is the contract for the process that runs
inside a sandbox.

- Linux provider: Docker/Podman command line must supply no network, read-only
  root filesystem, tmpfs, cgroup memory/CPU/pids limits, non-root user, and
  seccomp/AppArmor.
- Windows provider: Hyper-V container isolation must be active. The Windows
  image should provide a native `run-task.exe` entrypoint that applies Job Object
  process-tree limits, then emits the same event/summary contract.
- Remote worker provider: the worker service must authenticate requests and
  expose capability, run submission, event polling, and cancel endpoints using
  the same DTOs.

## Test Evidence

Required commands:

```bash
python -m unittest discover -s runner/tests
./mvnw -Dtest=TaskRunnerArtifactContractTest test
```

The Java contract test covers compile success, compile failure, `AC`, `WA`,
`TLE`, `MLE`, `OUTPUT_LIMIT_EXCEEDED`, security violation, cancellation,
bounded compiler output, stable exit codes, JSONL event parsing, and atomic
summary writes.
