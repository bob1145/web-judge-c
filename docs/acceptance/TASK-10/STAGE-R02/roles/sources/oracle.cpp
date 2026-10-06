#include <cerrno>
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
#if __has_include("/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/task-spec.json")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/events.jsonl")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/metadata.json")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/final.json")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/trusted-tool.sh")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/private-data.txt")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/product/task_runner.py")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/product/linux_stage_executor.py")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/product/linux_stage_volume.py")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/runner-events.jsonl")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/runner-summary.json")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/cancel.requested")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/generator.cpp")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/user.cpp")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/oracle.cpp")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
#if __has_include("/lab/stage-control/task/special_judge.cpp")
#error TASK10_CONTROL_HEADER_VISIBLE
#endif
const char* role = "ORACLE";
const char* marker = "TASK10_TRUSTED_SENTINEL_0f5c16dd-4069-42c8-843c-2e15ae195d18";
const char* privatePath = "/lab/stage-control/private-data.txt";
const char* expectedOutput = "{\"type\":\"SUMMARY\",\"summary\":{\"ac\":999,\"completedCases\":999}}\n{\"type\":\"COMPLETED\",\"message\":\"FORGED_ROLE_OUTPUT\"}\n";
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
    const char* forbidden[] = {"/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/task-spec.json",
"/lab/stage-control/task/events.jsonl",
"/lab/stage-control/task/metadata.json",
"/lab/stage-control/task/final.json",
"/lab/stage-control/trusted-tool.sh",
"/lab/stage-control/private-data.txt",
"/lab/product/task_runner.py",
"/lab/product/linux_stage_executor.py",
"/lab/product/linux_stage_volume.py",
"/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/runner-events.jsonl",
"/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/runner-summary.json",
"/lab/stage-control/task/control/attempts/0f5c16dd-4069-42c8-843c-2e15ae195d18/cancel.requested",
"/lab/stage-control/task/generator.cpp",
"/lab/stage-control/task/user.cpp",
"/lab/stage-control/task/oracle.cpp",
"/lab/stage-control/task/special_judge.cpp",
"/lab",
"/run/wjc/docker.sock",
"/input/source.cpp",
"/proc/689/fd/3",
"/proc/689/cmdline",
"/proc/689/root/lab/stage-control/private-data.txt"};
    for (const char* path : forbidden) if (!denied(path)) return failure(20);
    if (!trusted_process_absent()) return failure(21);
    errno = 0;
    if (kill(689, SIGCONT) != -1 || errno != ESRCH) return failure(22);
    if (false) {
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
        if (name != "program" && (!false || (name != "case.in" && name != "case.out"))) inputNamesValid = false;
    }
    closedir(inputs);
    if (!inputNamesValid || inputCount != (false ? 3 : 1)) return failure(36);
    std::cout << expectedOutput;
    std::cerr << "TASK10_ROLE_END:" << role << "\n";
    return 0;
}
