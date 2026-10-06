# Task Runner

The task runner is the artifact executed inside a Linux container, Windows
Hyper-V container image, or remote worker VM. It receives one
v2 `control/attempts/{attemptId}/task-spec.json`, compiles the submitted C++ sources,
runs the requested cases, and writes attempt-local output for the Spring service.
This repository provides a Python artifact, not a validated Linux image, Windows
executable, or external Worker service.

The current shared-workdir Linux and Windows adapters are restricted to trusted-local
development. It reports controlPlaneIsolated=false and productionSafe=false:
outer container limits and v2 protocol support do not isolate this Python
supervisor from its children. Both container production profiles require an actual
runner probe, not a configured capabilityProbePassed flag. Separate trusted
supervision and isolated execution units remain TASK-10 work in progress;
do not enable production by downgrading the profile or overriding a probe flag.

## Commands

Run the runner directly:

```bash
python3 -B runner/scripts/task_runner.py --capabilities
python3 -B runner/scripts/task_runner.py --work-root /work /work/control/attempts/{attemptId}/task-spec.json
```

Run the runner artifact tests:

```bash
python3 -B -m unittest discover -s runner/tests
```

Run the Java contract tests that deserialize runner output through the Spring DTOs:

```bash
./mvnw -Dtest=TaskRunnerArtifactContractTest test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -Dtest=TaskRunnerArtifactContractTest test
```


Java contract tests require a working Python 3 interpreter. They probe `python3`
first, then `python` on `PATH`. Each version probe has a five-second deadline;
a timed-out probe is forcibly terminated with a one-second cleanup deadline.
Missing interpreters, Python 2, and failed probes fail tests rather than skip them.
To select an executable explicitly (including a path containing spaces), use:

```powershell
.\mvnw.cmd "-Djudge.test.python=C:/Python313/python.exe" -Dtest=TaskRunnerArtifactContractTest test
```

`judge.test.python` takes precedence over discovery and must be a single executable
path or name, not a shell command. An invalid explicit value fails without falling
back to `PATH`. This property affects Java tests only, not the production runner.
Run resource-intensive runner tests only within the approved isolated test environment.

## Runtime Inputs

The input is a SandboxExecutionManifest, not the host SandboxTaskSpec. See
runner/task-runner-spec.md for required v2 fields, strict path rules, capability
negotiation and the Worker manifest/source bundle. Source paths are task-relative;
the execution root is supplied by the trusted provider. Invalid manifests exit 30
before creating runner directories or output files.

The artifact currently advertises SPECIAL_JUDGE and explicit LEGACY_TEXT with zero
precision. Ordinary host requests use DECIMAL_ABSOLUTE and are rejected until the
TASK-16 comparator is implemented; no precision-dropping fallback is allowed.
The public double precision input remains a TASK-15 limitation.

## Runtime Outputs

- control/attempts/{attemptId}/runner-events.jsonl: SandboxTaskEvent JSONL.
- control/attempts/{attemptId}/runner-summary.json: atomic JudgeSummary projection.
- control/attempts/{attemptId}/cancel.requested: attempt-specific cancellation flag.
- workspace/{attemptId}/bin: compiled programs, with that attempt's workspace as cwd.
- Existing task-root per-case .in/.out/.ans files remain for download compatibility.

Host events.jsonl and summary.json are untouched. Replaying an attempt fails rather
than truncating its stream. Separate paths are not separate execution identities;
TASK-10 still needs real supervisor/submission permission isolation.

Example output fixtures:

- `runner/fixtures/example-events.jsonl`
- `runner/fixtures/example-summary.json`

## Exit Codes

| Code | Meaning |
| ---: | --- |
| `0` | Runner completed and wrote summary. Verdicts such as `WA`, `TLE`, `MLE`, `RE`, and `OUTPUT_LIMIT_EXCEEDED` are normal judged outcomes. |
| `10` | Compile error. Events include `COMPILE_STARTED`, `COMPILE_FINISHED`, and terminal `SYSTEM_ERROR`. |
| `20` | Cancellation requested through `cancel.requested`; partial summary is written. |
| `21` | Task runtime budget exceeded; partial summary is written. |
| `30` | Security violation, for example a task/source path outside the mounted work directory. |
| `40` | Runner system error. |

## Case Protocol

For each case:

1. The runner sends the case number to the generator on stdin.
2. The generator stdout becomes the test input.
3. The user executable receives the generated input on stdin.
4. If `SPECIAL_JUDGE` is present, the special judge executable receives the
   input and user-output file paths as arguments.
5. Otherwise the oracle executable receives the same input on stdin, and stdout
   is compared using the explicitly negotiated comparison mode. LEGACY_TEXT retains
   only the historical trimmed-text behavior; it does not implement DECIMAL_ABSOLUTE.

## Resource Controls

The local runner provides per-process timeout, cancellation, and a best-effort
process memory monitor. Its current buffered output check happens after process
collection; it is not a bounded streaming-output guarantee (TASK-12 remains
pending). Production security still depends on the provider boundary:

- Linux production requires trusted control-plane isolation in addition to
  cgroup memory/pids/CPU limits, no network, non-root user, and seccomp/AppArmor.
  The current shared-identity artifact does not satisfy that boundary.
- Windows production images should wrap this contract behind
  `C:\judge-runner\run-task.exe` or an equivalent native entrypoint that applies
  Job Object process-tree control before invoking the same event/summary
  protocol.
- Remote workers must expose the same task/event/summary contract over the
  authenticated worker API.

The runner does not claim to be a complete sandbox by itself.

## Linux executor image development evidence

`linux-executor/Dockerfile` now has an actual offline build and a fixed trusted
C++17 compile/run smoke result in `docs/acceptance/TASK-10/BUILD-R05`. The exported
image is only a compiler/execution toolchain: it does not contain the trusted
supervisor and is not a replacement for the legacy full runner image. It has not
been loaded into the shared daemon. Do not enable Linux production mode by
changing the old adapter image tag. See `docs/acceptance/TASK-10/build.md` for
exact inputs and failures.

## Experimental Linux stage supervisor

`scripts/linux_stage_executor.py` now compiles and executes each role in separate
containers, verifies ownership before removal, and exports bounded regular
artifacts only after the compiler container has been removed. Stage inputs are
read-only and only that stage's scratch directory is writable. Both stage mounts
disable volume copy-up explicitly, preserving the supervisor-prepared directory
ownership even before an executor container starts.
`scripts/linux_stage_volume.py` rejects unowned, unbounded, or incorrectly mounted
volumes; the trusted supervisor must see the daemon's exact volume Mountpoint.
The volume is executable for program inputs; scratch noexec is not claimed.
Stage journals use `journalVersion: 1` and a separate `creationState`. A create
request is durably marked UNKNOWN before transmission and becomes CONFIRMED
only after a complete container ID and matching ownership inspection. A timeout,
invalid reply or uncertain inspection never counts as cleanup success: the stage
directory and CREATE_UNKNOWN record are retained, even if a currently visible
container can be removed. Descriptor-relative bounded journal validation and a
nonblocking directory lock prevent the same attempt from starting another stage,
including through a fresh executor instance. This is conservative quarantine,
not a runtime creation barrier or automatic crash-recovery implementation.

`scripts/linux_supervisor.py` is a separate stage-only entrypoint. Execution
requires explicit task spec, work root, and a `supervisor-config.json` in the same
attempt directory, plus a separate mandatory `--config-owner-uid`. The expected
writer UID must not come from the configuration being validated. Descriptor-based
owner/mode checks reject group/other-writable control paths and configurations;
the workload UID must differ from both trusted identities. These checks protect
integrity, not confidentiality. Configuration contains `configVersion: 1`, the matching
`attemptId`, and every field of `LinuxStageConfig`; there are no implicit image,
socket, identity, or resource defaults. Invalid configuration fails closed and
never selects the local process backend. `--capabilities` reports only the static
protocol, not production security.

`docs/acceptance/TASK-10/STAGE-R01` contains real separate compilation, artifact
handoff, and execution evidence. `STAGE-R02` additionally covers all four roles
against bounded control-file/process/FD/signal probes and fake result output;
the LEGACY_TEXT path uses the actual TaskRunner, while SPJ is a separate backend
probe. `CONTRACT-R09` has 118 passing contract tests, including uncertain-create
and persistent journal rejection cases. `SUPERVISOR-R02` exercises the
actual CLI with four invalid configuration/permission cases and a successful
one-case run in the dedicated Linux domain. `SUPERVISOR-CONTAINER-R04` additionally
passes these checks inside the separate supervisor image with only three mounts
and no added capabilities. `RECOVERY-R02` verifies quarantine across a real delayed
create; its cleanup is performed by the experimental harness, not the product.
These do not establish Java integration, automatic crash recovery, production
identity mapping, or full production acceptance. The existing
Linux production gate remains closed. See `docs/acceptance/TASK-10/stages.md`.
