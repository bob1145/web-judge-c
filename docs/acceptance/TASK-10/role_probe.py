import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path


ROLES = ("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE")
FORGED_OUTPUT = '{"type":"SUMMARY","summary":{"ac":999,"completedCases":999}}\n{"type":"COMPLETED","message":"FORGED_ROLE_OUTPUT"}\n'
ENVIRONMENT = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "HOME": "/nonexistent", "LANG": "C.UTF-8"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def file_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def manifest_fixture(attempt):
    return {"protocolVersion": 2, "judgeId": "task10-real-roles", "attemptId": attempt,
            "comparison": {"mode": "LEGACY_TEXT", "precision": "0"},
            "sourcePaths": {"GENERATOR": "generator.cpp", "USER": "user.cpp", "ORACLE": "oracle.cpp"},
            "testCases": 1, "caseTimeLimitMillis": 10000, "maxTaskRuntimeMillis": 120000,
            "memoryLimitBytes": 128 * 1024 ** 2, "maxOutputBytesPerCase": 4096, "stopOnFirstNonAc": False,
            "retention": {"completedMillis": 60000, "failedMillis": 60000, "cancelledMillis": 60000}}


def role_source(role, forbidden_paths, include_paths, sentinel_pid, sentinel_fd, marker, private_path):
    require(role in ROLES and type(sentinel_pid) is int and sentinel_pid > 1, "invalid role source identity")
    require(type(sentinel_fd) is int and sentinel_fd >= 0, "invalid sentinel FD")
    guards = "\n".join("#if __has_include(" + json.dumps(str(path)) + ")\n#error TASK10_CONTROL_HEADER_VISIBLE\n#endif"
                       for path in include_paths)
    paths = ",\n".join(json.dumps(str(path)) for path in forbidden_paths)
    template = r'''#include <cerrno>
#include <csignal>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <initializer_list>
#include <iterator>
#include <string>
#include <unistd.h>
@GUARDS@
const char* role = @ROLE@;
const char* marker = @MARKER@;
const char* privatePath = @PRIVATE@;
const char* expectedOutput = @OUTPUT@;
int failure(int code) {
    std::cerr << "TASK10_ROLE_FAILURE:" << role << ":" << code << "\n";
    return code;
}
bool denied(const char* path) {
    errno = 0;
    int descriptor = open(path, O_RDONLY | O_NONBLOCK);
    if (descriptor >= 0) { close(descriptor); return false; }
    if (errno != ENOENT && errno != EACCES && errno != EPERM) return false;
    errno = 0;
    descriptor = open(path, O_WRONLY | O_APPEND | O_CREAT | O_NONBLOCK, 0600);
    if (descriptor >= 0) { close(descriptor); return false; }
    return errno == ENOENT || errno == EACCES || errno == EPERM || errno == EROFS;
}
bool trusted_process_absent() {
    DIR* processes = opendir("/proc");
    if (!processes) return false;
    int processCount = 0;
    bool safe = true;
    while (dirent* entry = readdir(processes)) {
        std::string number(entry->d_name);
        if (number.empty() || number.find_first_not_of("0123456789") != std::string::npos) continue;
        if (++processCount > 512) { safe = false; break; }
        std::string base = "/proc/" + number;
        std::ifstream command(base + "/cmdline", std::ios::binary);
        char commandBytes[8192] = {};
        command.read(commandBytes, sizeof(commandBytes));
        std::string commandText(commandBytes, static_cast<std::size_t>(command.gcount()));
        if (commandText.find(marker) != std::string::npos || commandText.find("/lab/") != std::string::npos) { safe = false; break; }
        DIR* descriptors = opendir((base + "/fd").c_str());
        if (!descriptors) continue;
        int descriptorCount = 0;
        while (dirent* descriptorEntry = readdir(descriptors)) {
            std::string name(descriptorEntry->d_name);
            if (name == "." || name == "..") continue;
            if (++descriptorCount > 64) { safe = false; break; }
            char target[4096] = {};
            ssize_t count = readlink((base + "/fd/" + name).c_str(), target, sizeof(target));
            if (count < 0) continue;
            if (count == sizeof(target)) { safe = false; break; }
            std::string link(target, static_cast<std::size_t>(count));
            if (link.find(privatePath) != std::string::npos || link.find(marker) != std::string::npos
                    || link.find("/lab/") != std::string::npos) {
                safe = false; break;
            }
        }
        closedir(descriptors);
        if (!safe) break;
    }
    closedir(processes);
    return safe;
}
int main(int argc, char** argv) {
    std::cerr << "TASK10_ROLE_BEGIN:" << role << "\n";
    if (getuid() != 65532 || getgid() != 65532) return failure(11);
    int scratch = open("/scratch/role-probe.txt", O_CREAT | O_EXCL | O_WRONLY, 0600);
    if (scratch < 0) return failure(12);
    if (write(scratch, "ok", 2) != 2 || close(scratch) != 0) return failure(13);
    errno = 0;
    int input = open("/input/program", O_WRONLY | O_APPEND);
    if (input >= 0) { close(input); return failure(14); }
    if (errno != EROFS && errno != EACCES) return failure(15);
    const char* forbidden[] = {@PATHS@};
    for (const char* path : forbidden) if (!denied(path)) return failure(20);
    if (!trusted_process_absent()) return failure(21);
    errno = 0;
    if (kill(@PID@, SIGCONT) != -1 || errno != ESRCH) return failure(22);
    if (@SPJ@) {
        if (argc != 3 || std::strcmp(argv[1], "/input/case.in") || std::strcmp(argv[2], "/input/case.out")) return failure(30);
        for (const char* path : {"/input/case.in", "/input/case.out"}) {
            std::ifstream file(path, std::ios::binary);
            if (!file) return failure(31);
            std::string content((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
            if (content != expectedOutput) return failure(32);
        }
    } else {
        if (argc != 1) return failure(33);
        for (const char* path : {"/input/case.in", "/input/case.out"}) {
            errno = 0;
            if (access(path, F_OK) == 0 || errno != ENOENT) return failure(34);
        }
    }
    DIR* inputs = opendir("/input");
    if (!inputs) return failure(35);
    int inputCount = 0;
    bool inputNamesValid = true;
    while (dirent* entry = readdir(inputs)) {
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        ++inputCount;
        if (name != "program" && (!@SPJ@ || (name != "case.in" && name != "case.out"))) inputNamesValid = false;
    }
    closedir(inputs);
    if (!inputNamesValid || inputCount != (@SPJ@ ? 3 : 1)) return failure(36);
    std::cout << expectedOutput;
    std::cerr << "TASK10_ROLE_END:" << role << "\n";
    return 0;
}
'''
    replacements = {"@GUARDS@": guards, "@ROLE@": json.dumps(role), "@MARKER@": json.dumps(marker),
                    "@PRIVATE@": json.dumps(str(private_path)), "@OUTPUT@": json.dumps(FORGED_OUTPUT),
                    "@PATHS@": paths, "@PID@": str(sentinel_pid), "@SPJ@": "true" if role == "SPECIAL_JUDGE" else "false"}
    for key, value in replacements.items():
        template = template.replace(key, value)
    return template


def run_role_probe(executor, control, attempt, report, record, stage_results):
    from task_runner import TaskRunner

    evidence = report / "roles"
    evidence.mkdir()
    (evidence / "sources").mkdir()
    root = control / "task"
    root.mkdir(mode=0o700)
    attempt_dir = root / "control/attempts" / attempt
    attempt_dir.mkdir(parents=True, mode=0o700)
    spec_path = attempt_dir / "task-spec.json"
    spec_path.write_text(json.dumps(manifest_fixture(attempt), indent=2) + "\n")
    event_path = attempt_dir / "runner-events.jsonl"
    summary_path = attempt_dir / "runner-summary.json"
    cancel_path = attempt_dir / "cancel.requested"
    audit_path = root / "events.jsonl"
    audit_path.write_text("".join(json.dumps({"role": role, "state": "CONTROL_SENTINEL_UNCHANGED"}) + "\n" for role in ROLES))
    fixed_files = {
        root / "metadata.json": '{"owner":"trusted-supervisor","state":"fixed"}\n',
        root / "final.json": '{"trustedFinal":true,"ac":0}\n',
        control / "trusted-tool.sh": '#!/bin/sh\nprintf "TRUSTED_TOOL_SENTINEL\\n"\n',
        control / "private-data.txt": 'TASK10_PRIVATE_DATA_SENTINEL\n',
    }
    for path, content in fixed_files.items():
        path.write_text(content, encoding="utf-8")
    private_path = control / "private-data.txt"
    (control / "trusted-tool.sh").chmod(0o555)
    private_path.chmod(0o600)
    immutable = [spec_path, audit_path, *fixed_files, Path("/lab/product/task_runner.py"),
                 Path("/lab/product/linux_stage_executor.py"), Path("/lab/product/linux_stage_volume.py")]
    before_hashes = {str(path): file_hash(path) for path in immutable}
    (evidence / "control-hashes-before.json").write_text(json.dumps(before_hashes, indent=2) + "\n")
    record("role-control-fixtures-created", attempt=attempt, hashes=before_hashes,
           auditRows=4, scope="synthetic control and private fixtures inside this dedicated runtime only")
    source_paths = {role: root / (role.lower() + ".cpp") for role in ROLES}
    marker = "TASK10_TRUSTED_SENTINEL_" + attempt
    sentinel = None
    initial_stage_count = len(stage_results)
    try:
        with private_path.open("rb") as sentinel_file:
            sentinel = subprocess.Popen([marker, "570"], executable="/bin/sleep", stdin=subprocess.DEVNULL,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=ENVIRONMENT,
                                        pass_fds=(sentinel_file.fileno(),), close_fds=True, start_new_session=True)
            require(sentinel.pid > 1 and sentinel.poll() is None, "trusted sentinel did not start")
            sentinel_fd = sentinel_file.fileno()
            descriptor_path = Path("/proc") / str(sentinel.pid) / "fd" / str(sentinel_fd)
            require(os.readlink(descriptor_path) == str(private_path), "trusted sentinel did not inherit the private descriptor")
            cmdline_path = Path("/proc") / str(sentinel.pid) / "cmdline"
            cmdline = cmdline_path.read_bytes()
            require(marker.encode() in cmdline, "trusted sentinel marker is absent")
            sentinel_identity = {"pid": sentinel.pid, "fd": sentinel_fd, "fdTarget": os.readlink(descriptor_path),
                                 "cmdlineSha256": hashlib.sha256(cmdline).hexdigest()}
            (evidence / "sentinel-before.json").write_text(json.dumps(sentinel_identity, indent=2) + "\n")
            sentinel_proc = Path("/proc") / str(sentinel.pid)
            (evidence / "sentinel-proc-status-before.txt").write_text((sentinel_proc / "status").read_text())
            (evidence / "sentinel-proc-stat-before.txt").write_text((sentinel_proc / "stat").read_text())
            (evidence / "sentinel-proc-fd-before.json").write_text(json.dumps(
                {path.name: os.readlink(path) for path in (sentinel_proc / "fd").iterdir()}, indent=2) + "\n")
            (evidence / "sentinel-proc-namespaces.json").write_text(json.dumps(
                {name: os.readlink(sentinel_proc / "ns" / name) for name in ("mnt", "pid", "net", "ipc", "uts", "cgroup")},
                indent=2) + "\n")
            record("role-trusted-sentinel-started", **sentinel_identity)
            forbidden = [*immutable, event_path, summary_path, cancel_path, *source_paths.values(),
                         "/lab", "/run/wjc/docker.sock", "/input/source.cpp", descriptor_path, cmdline_path,
                         Path("/proc") / str(sentinel.pid) / "root" / str(private_path).lstrip("/")]
            for role, path in source_paths.items():
                content = role_source(role, forbidden, immutable + [event_path, summary_path, cancel_path]
                                      + list(source_paths.values()), sentinel.pid,
                                      sentinel_fd, marker, private_path)
                path.write_text(content, encoding="utf-8")
                (evidence / "sources" / path.name).write_text(content, encoding="utf-8")
            source_hashes = {role: file_hash(path) for role, path in source_paths.items()}
            (evidence / "source-hashes.json").write_text(json.dumps(source_hashes, indent=2) + "\n")
            runner = TaskRunner(spec_path, root, stage_executor=executor)
            runner_exit = runner.run()
            record("role-task-runner-exit", exitCode=runner_exit, attempt=attempt,
                   scope="actual TaskRunner LEGACY_TEXT one-case GENERATOR/USER/ORACLE path")
            require(runner_exit == 0, "actual role TaskRunner did not complete successfully")
            require(sentinel.poll() is None, "trusted sentinel was stopped by a role")
            summary = json.loads(summary_path.read_text())
            require(summary["totalCases"] == 1 and summary["completedCases"] == 1 and summary["ac"] == 1,
                    "forged role output changed trusted summary counts")
            require(all(summary[key] == 0 for key in ("wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")),
                    "role boundary assertions did not produce a clean AC")
            events = [json.loads(line) for line in event_path.read_text().splitlines()]
            require([event["type"] for event in events] == ["COMPILE_STARTED", "COMPILE_FINISHED", "RUN_STARTED",
                    "RUN_FINISHED", "SUMMARY", "COMPLETED"], "forged output changed trusted event sequence")
            require(events[-2]["summary"] == summary and all(event["judgeId"] == "task10-real-roles" for event in events),
                    "trusted event summary or task identity differs")
            require(all(event.get("message") != "FORGED_ROLE_OUTPUT" for event in events), "forged completed event was accepted")
            require(not cancel_path.exists(), "role created a cancellation control file")
            summary_before_spj = file_hash(summary_path)
            event_before_spj = file_hash(event_path)
            spj_artifact = control / "special-judge-program"
            stage_limits = {"stdin": b"", "timeout_seconds": 60, "memory_limit_bytes": 256 * 1024 ** 2,
                            "output_limit_bytes": 64 * 1024, "cancel_path": cancel_path}
            compiled = executor.compile("SPECIAL_JUDGE", source_paths["SPECIAL_JUDGE"], spj_artifact, **stage_limits)
            require(compiled.status == "SUCCESS" and compiled.exit_code == 0, "SPJ control-path compiler probe failed")
            executed = executor.execute("SPECIAL_JUDGE", spj_artifact, ["/input/case.in", "/input/case.out"],
                                        {"case.in": root / "1.in", "case.out": root / "1.out"},
                                        **{**stage_limits, "timeout_seconds": 10, "memory_limit_bytes": 128 * 1024 ** 2})
            require(executed.status == "SUCCESS" and executed.exit_code == 0 and executed.stdout.decode() == FORGED_OUTPUT,
                    "separate SPJ boundary probe failed")
            require(file_hash(summary_path) == summary_before_spj and file_hash(event_path) == event_before_spj,
                    "SPJ changed finalized TaskRunner control output")
            role_results = stage_results[initial_stage_count:]
            expected = [(role, "compile") for role in ROLES[:3]] + [(role, "execute") for role in ROLES[:3]]
            expected += [("SPECIAL_JUDGE", "compile"), ("SPECIAL_JUDGE", "execute")]
            require([(result["role"], result["phase"]) for result in role_results] == expected,
                    "actual four-role compile/execute stage sequence differs")
            for result in role_results:
                require(result["status"] == "SUCCESS" and result["exitCode"] == 0, "role stage was not successful")
                if result["phase"] == "execute":
                    require(result["stdout"] == FORGED_OUTPUT, "role data output differs")
                    require(result["stderr"] == "TASK10_ROLE_BEGIN:" + result["role"] + "\nTASK10_ROLE_END:" + result["role"] + "\n",
                            "role boundary check completion marker is missing")
            after_hashes = {str(path): file_hash(path) for path in immutable}
            require(before_hashes == after_hashes and not cancel_path.exists(), "a role modified synthetic control files")
            source_hashes_after = {role: file_hash(path) for role, path in source_paths.items()}
            require(source_hashes_after == source_hashes, "a role modified original role sources")
            (evidence / "source-hashes-after.json").write_text(json.dumps(source_hashes_after, indent=2) + "\n")
            require(sentinel.poll() is None and os.readlink(descriptor_path) == str(private_path)
                    and cmdline_path.read_bytes() == cmdline, "trusted sentinel identity or FD changed")
            (evidence / "control-hashes-after.json").write_text(json.dumps(after_hashes, indent=2) + "\n")
            (evidence / "sentinel-after.json").write_text(json.dumps(sentinel_identity, indent=2) + "\n")
            (evidence / "sentinel-proc-status-after.txt").write_text((sentinel_proc / "status").read_text())
            (evidence / "stage-results.json").write_text(json.dumps(role_results, indent=2) + "\n")
            record("role-control-boundaries-verified", attempt=attempt, roles=list(ROLES), taskRunnerExit=runner_exit,
                   summary=summary, controlHashesUnchanged=True, trustedSentinelAlive=True,
                   compileIncludeProbes="all four source compilations succeeded with __has_include rejection guards",
                   signalProbe="fixed SIGCONT to trusted-domain sentinel numeric PID returned ESRCH in every role",
                   spjScope="separate actual backend compile/execute with current case input/output only; not TaskRunner SPJ end-to-end",
                   scope="bounded control files/process/FD observations only; no restart, crash, exhaustive syscall or resource-attack acceptance")
    finally:
        if sentinel is not None:
            was_alive = sentinel.poll() is None
            if was_alive:
                sentinel.terminate()
                try:
                    sentinel.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    sentinel.kill()
                    sentinel.wait(timeout=5)
            record("role-trusted-sentinel-cleaned", pid=sentinel.pid, aliveBeforeCleanup=was_alive, exitCode=sentinel.returncode)
        for path in [*immutable, event_path, summary_path, root / "1.in", root / "1.out", root / "1.ans"]:
            if path.is_file() and not path.is_symlink():
                require(path.stat().st_size <= 128 * 1024, "role evidence file exceeded bound")
                shutil.copyfile(path, evidence / path.name)
        final_hashes = {str(path): file_hash(path) if path.is_file() else None for path in immutable}
        (evidence / "control-hashes-final.json").write_text(json.dumps(final_hashes, indent=2) + "\n")
        (evidence / "stage-results-final.json").write_text(json.dumps(stage_results[initial_stage_count:], indent=2) + "\n")
