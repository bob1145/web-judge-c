# Task Runner

The task runner is the artifact executed inside a Linux container, Windows
Hyper-V container image, or remote worker VM. It receives one
`sandbox-task.json`, compiles the submitted C++ sources, runs the requested
cases, and writes machine-readable output for the Spring service.

## Commands

Run the runner directly:

```bash
python runner/scripts/task_runner.py /work/sandbox-task.json
```

Run the runner artifact tests:

```bash
python -m unittest discover -s runner/tests
```

Run the Java contract tests that deserialize runner output through the Spring DTOs:

```bash
./mvnw -Dtest=TaskRunnerArtifactContractTest test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -Dtest=TaskRunnerArtifactContractTest test
```

## Runtime Inputs

The input file is a serialized `SandboxTaskSpec` with these required fields:

- `judgeId`, `userId`, `profile`
- `workDir`
- `sourcePaths.GENERATOR`
- `sourcePaths.USER`
- `sourcePaths.ORACLE` or `sourcePaths.SPECIAL_JUDGE`
- `testCases`
- `caseTimeLimit`
- `maxTaskRuntime`
- `memoryLimitBytes`
- `maxOutputBytesPerCase`

All source paths and the task spec must stay inside `workDir`. A path outside
`workDir` is treated as `SECURITY_VIOLATION`.

## Runtime Outputs

The runner writes these files under `workDir`:

- `events.jsonl`: one JSON `SandboxTaskEvent` per line.
- `summary.json`: final or partial `JudgeSummary`, written atomically through
  `summary.json.tmp` + replace.
- `bin/`: compiled executables.
- Optional per-case files for special judge input/output handoff.

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
   is compared against the user stdout after trimming surrounding whitespace.

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
