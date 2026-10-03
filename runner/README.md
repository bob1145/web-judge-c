# Task Runner

The task runner is the artifact executed inside a Linux container, Windows
Hyper-V container image, or remote worker VM. It receives one
v2 `control/attempts/{attemptId}/task-spec.json`, compiles the submitted C++ sources,
runs the requested cases, and writes attempt-local output for the Spring service.
This repository provides a Python artifact, not a validated Linux image, Windows
executable, or external Worker service.

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

The runner enforces per-process timeout, output limit, cancellation, and a
best-effort process memory monitor. Production security still depends on the
provider boundary:

- Linux production images must run this inside the `linux-container` provider
  with cgroup memory/pids/CPU limits, no network, non-root user, and
  seccomp/AppArmor.
- Windows production images should wrap this contract behind
  `C:\judge-runner\run-task.exe` or an equivalent native entrypoint that applies
  Job Object process-tree control before invoking the same event/summary
  protocol.
- Remote workers must expose the same task/event/summary contract over the
  authenticated worker API.

The runner does not claim to be a complete sandbox by itself.
