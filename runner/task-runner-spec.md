# Task Runner Specification

## Purpose

The task runner is the stable execution contract inside platform sandbox
providers. Container runners and remote workers may differ in how they isolate
the process, but they must produce the same event stream, summary shape, and
exit codes.

## Input Contract (v2)

The host SandboxTaskSpec remains a domain object with native, absolute host paths.
Adapters create a separate SandboxExecutionManifest after validating every source
and planned control path. The execution root is a provider argument/current working
directory, never a JSON field. Linux and Windows default to /work and C:\work;
adapters build provider paths as strings, not by parsing them with the host OS Path API.

Required fields (no missing-field fallback in the executable artifact):

- protocolVersion: integer 2.
- judgeId: task identifier; attemptId: fresh canonical UUID assigned by the host.
- sourcePaths: relative, forward-slash paths for GENERATOR, USER, and exactly one
  of ORACLE/SPECIAL_JUDGE, matching the comparison mode. Sources must exist under
  this task root and cannot name control/workspace/artifact paths.
- testCases: positive signed-32-bit integer.
- comparison: mode DECIMAL_ABSOLUTE and decimal-string precision for ordinary
  requests; mode SPECIAL_JUDGE and precision "0" for SPJ.
- caseTimeLimitMillis, maxTaskRuntimeMillis: positive signed-64-bit milliseconds;
  the host rejects sub-millisecond durations rather than truncating them.
- memoryLimitBytes, maxOutputBytesPerCase: positive signed-64-bit bytes. The
  output allowance is per execution, stdout plus stderr, including generators.
- stopOnFirstNonAc: explicit boolean.
- retention: positive completedMillis, failedMillis, cancelledMillis. This
  remains a host cleanup policy, not a Python deletion job.

The wire model has no host workDir, userId, profile, command, or execution-root override.
The Python receiver rejects duplicate, missing and unknown fields, absolute paths,
parent/dot components, drive/UNC syntax, backslashes, Windows device names, and
symlink/junction aliases. The host also checks that each native source is inside
this task, not merely inside the common storage base. Validation precedes all
runner directory/output creation. Rejection exits 30 with a fixed stderr message;
it does not create events or summaries in an untrusted location.

The manifest is control/attempts/{attemptId}/task-spec.json under the execution root.
Each attempt uses a new directory; an existing output stream or workspace is rejected,
never truncated. These checks do not replace the UID/ACL and mount isolation in
TASK-10, and do not prove safety against concurrent same-identity filesystem mutation.

### Comparison staging

TASK-09 transports decimal strings from the policy snapshot to the manifest without
additional binary conversion. JudgeRequest is still a double at this stage; API
precision already rounded there is **not** recovered. Lossless API parsing and
numeric bounds belong to TASK-15; the common Java/Python algorithm to TASK-16.

The current Python artifact advertises only SPECIAL_JUDGE and LEGACY_TEXT (the old
trimmed-text comparator, precision exactly zero). LEGACY_TEXT is explicitly selected
only by diagnostic fixtures; JudgeService never silently selects it. Ordinary requests
ask for DECIMAL_ABSOLUTE, which this artifact rejects until TASK-16 implements and
validates that capability. This intermediate upgrade is not a release-ready ordinary
container judging pipeline.

### Capability negotiation

Before writing a local manifest, adapters invoke the configured artifact with
--capabilities in a resource-constrained probe container. Its JSON response is:

~~~json
{"versions":[2],"comparisonModes":["LEGACY_TEXT","SPECIAL_JUDGE"],"features":["relative-sources","attempt-files","explicit-limits","retention"]}
~~~

SandboxCapabilities exposes this object as protocol. Missing/unknown versions,
unsupported requested comparison modes, or missing required features fail closed.
Version values must be integers; string/fractional versions are not coerced to 2.
These features describe protocol interpretation, not proof of isolation, online output
limits, process-tree cleanup or deadline enforcement.

### Remote Worker v2

HTTP paths and Bearer/HMAC signing inputs are unchanged. The host first calls
GET /api/v1/capabilities and requires the v2 protocol object plus feature
inline-sources-base64. Only then does POST /api/v1/runs send:

~~~json
{"manifest":{"protocolVersion":2,"...":"remaining v2 fields"},"sourceContentsBase64":{"user.cpp":"...","generator.cpp":"...","oracle.cpp":"..."}}
~~~

The worker must validate the complete manifest and source-key set before creating
its independently assigned task root or staging bytes. Base64 preserves source bytes;
host paths and ownership credentials are not transmitted. Bundle keys are not arbitrary
filesystem paths, and the worker must not accept a client-selected execution root.
The client transfers actual files; no external worker implementation, staging or
server-side isolation is supplied or certified by the local TLS protocol fixtures.

Accepted handles must echo integer protocolVersion=2, the same judgeId and attemptId,
provider=remote-worker, and a nonempty runId. Mismatched acknowledgements are unavailable,
not accepted runs. Worker cleanup after a lost/invalid acknowledgement remains a
server/lifecycle obligation for TASK-12/26.

## Event Contract

Events are JSON Lines in `control/attempts/{attemptId}/runner-events.jsonl`. Each line maps to
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

Attempt-local `runner-summary.json` maps to `JudgeSummary`:

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

The runner writes attempt-local `runner-summary.json.tmp` first and then atomically replaces
`runner-summary.json`. Host `events.jsonl` and `summary.json` are never runner output targets. Partial summaries must be recoverable for cancellation and task
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
| `21` | `BUDGET_EXCEEDED` | `maxTaskRuntimeMillis` was exceeded. |
| `30` | `SECURITY_VIOLATION` | Task paths violated the mounted workdir boundary. |
| `40` | `SYSTEM_ERROR` | Runner failed before it could produce a normal verdict. |

## Cancellation

The provider requests cancellation by creating:

```text
executionRoot/control/attempts/{attemptId}/cancel.requested
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
  the v2 manifest/bundle, capability and run-handle contracts described above.

## Test Evidence

Required commands:

```bash
python3 -B -m unittest discover -s runner/tests
./mvnw "-Dtest=SandboxExecutionManifestTest,TaskRunnerArtifactContractTest,TaskRunnerPythonSuiteTest" test
```

The Java contract test covers compile success, compile failure, `AC`, `WA`,
`TLE`, `MLE`, `OUTPUT_LIMIT_EXCEEDED`, security violation, cancellation,
bounded compiler output, stable exit codes, JSONL event parsing, and atomic
summary writes.
