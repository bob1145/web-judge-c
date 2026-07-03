# Security Boundary

This project executes untrusted C++ code. The production security boundary must
be outside the Spring Boot process and outside ordinary host process controls.

## Production-Safe Boundaries

- Windows Hyper-V containers: acceptable for `windows-prod` only when the
  runtime proves Hyper-V isolation, `network none`, bounded memory/CPU/output,
  workdir-only mounts, and full cleanup.
- Linux containers: acceptable for `linux-prod` only when the runtime proves
  cgroup limits, `network none`, non-root execution, read-only root filesystem,
  seccomp, AppArmor or an equivalent host policy, pids limits, and workdir-only
  mounts.
- remote worker: acceptable for `worker-prod` only when the channel is
  authenticated, the worker reports production-safe capabilities, and the worker
  host itself passes the matching Windows or Linux capability smoke.

## Not Production-Safe

- Job Object-only is not a production boundary. It can help kill child
  processes inside a Windows container, but it cannot isolate hostile code from
  the host by itself.
- Linux direct process execution is not production safe. Fork, namespace,
  cgroup, filesystem, and network controls must be owned by the container or
  worker boundary, not by ad hoc process code in the web JVM.
- A disabled sandbox, direct runner, default access code, wildcard WebSocket
  origin, or unauthenticated remote worker is a release blocker.
- WSL smoke is not production release evidence. It is useful on a Windows
  operator machine to catch script and application portability issues, but it
  does not prove the Linux production host boundary.

## Required Controls

- Authentication: production uses user accounts or an external identity system;
  a shared access code is not enough.
- Authorization: users may only read, cancel, or download their own tasks unless
  they have an explicit admin role.
- Network: task containers or workers run with network none unless a future
  design explicitly proves a narrower egress policy.
- Filesystem: only the task work directory is mounted. Storage base, host root,
  Docker socket, compiler cache, and arbitrary user-supplied paths must not be
  mounted.
- Resources: memory, CPU, pids, output, runtime, queue depth, daily cases, and
  daily runtime are bounded.
- Cleanup: cancel, timeout, crash, stale startup reconciliation, and retention
  cleanup must remove residual containers/processes and refuse path escapes.
- Observability: audit logs and admin queue views must expose sanitized status,
  not secrets or full source code.
