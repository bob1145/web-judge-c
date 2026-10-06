#!/usr/bin/env bash
set -euo pipefail

evidence_root=${1:?evidence root required}
[[ "${evidence_root##*/}" =~ ^STAGE-FLOCK-ABI-R[0-9]{2}$ ]]
test ! -e "$evidence_root"
test "$(uname -m)" = x86_64
cgroup_path=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
cgroup_root="/sys/fs/cgroup$cgroup_path"
test "$(cat "$cgroup_root/memory.max")" = 4294967296
test "$(cat "$cgroup_root/memory.swap.max")" = 0
test "$(cat "$cgroup_root/pids.max")" = 512
test "$(cat "$cgroup_root/cpu.max")" = "200000 100000"
test "$(findmnt -n -o FSTYPE -T /tmp)" = tmpfs
test "$(findmnt -n -o FSTYPE -T /proc/self/fd)" = proc
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
test -z "$(ip route show)"
mkdir "$evidence_root"
sha256sum "$0" > "$evidence_root/harness-hash.sha256"
systemctl show "${cgroup_path##*/}" -p Id -p InvocationID -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p TasksMax -p RuntimeMaxUSec -p PrivateNetwork -p ProtectSystem -p NoNewPrivileges -p TemporaryFileSystem -p ReadOnlyPaths -p ReadWritePaths > "$evidence_root/unit-properties.txt"
{
    uname -m
    getconf GNU_LIBC_VERSION
    java -version 2>&1
    gcc --version | head -n 1
    findmnt -n -o TARGET,FSTYPE,OPTIONS -T /proc/self/fd
} > "$evidence_root/environment.txt"
gcc -D_GNU_SOURCE -include fcntl.h -include sys/file.h -include errno.h -dM -E -x c /dev/null | grep -E '^#define (O_RDONLY|O_DIRECTORY|__O_DIRECTORY|O_NOFOLLOW|__O_NOFOLLOW|O_CLOEXEC|__O_CLOEXEC|LOCK_EX|LOCK_NB|EWOULDBLOCK|EAGAIN|EINTR|F_GETFD|FD_CLOEXEC) ' > "$evidence_root/header-macros.txt"
sha256sum /usr/include/x86_64-linux-gnu/bits/fcntl-linux.h /usr/include/x86_64-linux-gnu/sys/file.h /usr/include/asm-generic/errno-base.h > "$evidence_root/header-hashes.sha256"
probe_root=$(mktemp -d /tmp/wjc-flock-abi.XXXXXX)
cat > "$evidence_root/probe.c" <<'C'
#define _GNU_SOURCE
#include <fcntl.h>
#include <sys/file.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    int descriptor = open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (descriptor < 0) return 1;
    int descriptor_flags = fcntl(descriptor, F_GETFD);
    printf("intBytes=%zu pointerBytes=%zu\n", sizeof(int), sizeof(void *));
    printf("O_RDONLY=%d O_DIRECTORY=%d O_NOFOLLOW=%d O_CLOEXEC=%d\n", O_RDONLY, O_DIRECTORY, O_NOFOLLOW, O_CLOEXEC);
    printf("LOCK_EX=%d LOCK_NB=%d EAGAIN=%d EWOULDBLOCK=%d EINTR=%d\n", LOCK_EX, LOCK_NB, EAGAIN, EWOULDBLOCK, EINTR);
    printf("F_GETFD=%d FD_CLOEXEC=%d observedDescriptorFlags=%d\n", F_GETFD, FD_CLOEXEC, descriptor_flags);
    if (close(descriptor) != 0) return 2;
    return descriptor_flags == FD_CLOEXEC ? 0 : 3;
}
C
gcc -Wall -Wextra -Werror "$evidence_root/probe.c" -o "$probe_root/probe"
"$probe_root/probe" > "$evidence_root/abi-values.txt"
cat "$evidence_root/abi-values.txt"
