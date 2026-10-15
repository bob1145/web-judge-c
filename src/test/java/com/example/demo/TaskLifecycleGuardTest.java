package com.example.demo;

import com.example.demo.service.TaskLifecycleGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TaskLifecycleGuardTest {
    private static final String LOCK_NAME = ".task-lifecycle.lock";

    @TempDir
    Path tempDir;

    @Test
    void createsPrivateMissingRootAndStableEmptyLock() throws Exception {
        Path root = canonicalTemp().resolve("parent/storage");
        Object identity;
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, true)) {
            Path task = Files.createDirectory(root.resolve("task"));
            lease.requireTaskDirectory(task);
            assertThat(Files.size(root.resolve(LOCK_NAME))).isZero();
            identity = fileKey(root.resolve(LOCK_NAME));
            if (posix(root)) {
                assertThat(Files.getPosixFilePermissions(root))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
                assertThat(Files.getPosixFilePermissions(root.getParent()))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
                assertThat(Files.getPosixFilePermissions(root.resolve(LOCK_NAME)))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
        }
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
            lease.requireTaskDirectory(root.resolve("task"));
            assertThat(fileKey(root.resolve(LOCK_NAME))).isEqualTo(identity);
        }
        assertThat(root.resolve(LOCK_NAME)).exists();
    }

    @Test
    void missingReadOnlyRootDoesNotCreateAnyDirectory() throws Exception {
        Path parent = canonicalTemp().resolve("absent");
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(parent.resolve("storage"), false))
                .isInstanceOf(IOException.class);
        assertThat(parent).doesNotExist();
    }

    @Test
    void existingOnlyMissingRootDoesNotCreateAnyDirectory() throws Exception {
        Path parent = canonicalTemp().resolve("absent");
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(parent.resolve("storage")))
                .isInstanceOf(IOException.class);
        assertThat(parent).doesNotExist();
    }

    @Test
    void existingOnlyMissingLockDoesNotCreateIt() throws Exception {
        Path root = privateRoot();
        Object rootIdentity = fileKey(root);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(root.resolve(LOCK_NAME)).doesNotExist();
        assertThat(fileKey(root)).isEqualTo(rootIdentity);
        try (var contents = Files.list(root)) {
            assertThat(contents).isEmpty();
        }
    }

    @Test
    void existingOnlyReusesValidatedLockAndLeaseContract() throws Exception {
        Path root = privateRoot();
        Path lock = privateFile(root.resolve(LOCK_NAME));
        Path task = Files.createDirectory(root.resolve("task"));
        Object identity = fileKey(lock);
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.tryAcquireExisting(root)) {
            lease.requireTaskDirectory(task);
            assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root))
                    .isInstanceOf(IOException.class).hasMessageContaining("Nested");
            assertThatThrownBy(() -> lease.requireTaskDirectory(root.resolve("missing")))
                    .isInstanceOf(IOException.class);
            assertThat(fileKey(lock)).isEqualTo(identity);
            assertThat(Files.size(lock)).isZero();
        }
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.tryAcquireExisting(root)) {
            lease.requireTaskDirectory(task);
        }
    }

    @Test
    void existingOnlyRejectsContenderBeforeHoldingThreadReleases() throws Exception {
        Path root = privateRoot();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch contenderStarted = new CountDownLatch(1);
        try {
            try (TaskLifecycleGuard.Lease owner = TaskLifecycleGuard.acquire(root, false)) {
                Future<Boolean> contender = executor.submit(() -> {
                    contenderStarted.countDown();
                    assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root))
                            .isInstanceOf(IOException.class).hasMessageContaining("already held");
                    return true;
                });
                assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(contender.get(5, TimeUnit.SECONDS)).isTrue();
                owner.requireTaskDirectory(Files.createDirectory(root.resolve("task")));
            }
            assertThat(executor.submit(() -> {
                try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.tryAcquireExisting(root)) {
                    lease.requireTaskDirectory(root.resolve("task"));
                    return true;
                }
            }).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void existingOnlyPreservesExistingInterruptAndLeavesFilesystemUntouched() throws Exception {
        Path root = privateRoot();
        Path lock = privateFile(root.resolve(LOCK_NAME));
        Object identity = fileKey(lock);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root))
                            .isInstanceOf(IOException.class).hasMessageContaining("Interrupted");
                    return Thread.currentThread().isInterrupted();
                } finally {
                    Thread.interrupted();
                }
            }).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fileKey(lock)).isEqualTo(identity);
            try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.tryAcquireExisting(root)) {
                lease.requireTaskDirectory(Files.createDirectory(root.resolve("task")));
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsRelativeNullAndUnnormalizedRootsWithoutCreatingThem() throws Exception {
        Path root = canonicalTemp().resolve("missing");
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(null, true)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(Path.of("relative"), true))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root.resolve("../storage"), true))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(null)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(Path.of("relative")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root.resolve("../storage")))
                .isInstanceOf(IOException.class);
        assertThat(root).doesNotExist();
    }

    @Test
    void rootFileIsRejectedWithoutChangingContent() throws Exception {
        Path root = canonicalTemp().resolve("storage");
        Files.writeString(root, "retained");
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, true)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(Files.readString(root)).isEqualTo("retained");
    }

    @Test
    void rootAndAncestorLinksAreRejectedWithoutCreatingLockOrChild() throws Exception {
        requirePosix();
        Path actual = Files.createDirectory(canonicalTemp().resolve("actual"));
        Path link = Files.createSymbolicLink(canonicalTemp().resolve("link"), actual);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(link, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(link.resolve("storage"), true))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(link)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(link.resolve("storage")))
                .isInstanceOf(IOException.class);
        assertThat(actual.resolve(LOCK_NAME)).doesNotExist();
        assertThat(actual.resolve("storage")).doesNotExist();
    }

    @Test
    void writableRootIsRejectedBeforeLockCreation() throws Exception {
        requirePosix();
        Path root = privateRoot();
        for (String permissions : new String[]{"rwxrwx---", "rwx----w-"}) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString(permissions));
            assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
            assertThat(root.resolve(LOCK_NAME)).doesNotExist();
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void rejectsNonEmptyLockWithoutTruncatingIt() throws Exception {
        Path root = privateRoot();
        Path lock = privateFile(root.resolve(LOCK_NAME));
        Files.writeString(lock, "evidence");
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(Files.readString(lock)).isEqualTo("evidence");
    }

    @Test
    void rejectsDirectoryAtLockPath() throws Exception {
        Path root = privateRoot();
        Files.createDirectory(root.resolve(LOCK_NAME));
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(root.resolve(LOCK_NAME)).isDirectory();
    }

    @Test
    void rejectsSymbolicLockWithoutTouchingTarget() throws Exception {
        requirePosix();
        Path root = privateRoot();
        Path target = privateFile(canonicalTemp().resolve("target"));
        Files.writeString(target, "untouched");
        Files.createSymbolicLink(root.resolve(LOCK_NAME), target);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).isEqualTo("untouched");
    }

    @Test
    void rejectsHardLinkedLock() throws Exception {
        Path root = privateRoot();
        Path lock = privateFile(root.resolve(LOCK_NAME));
        Files.createLink(canonicalTemp().resolve("alias"), lock);
        assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
        assertThat(Files.size(lock)).isZero();
    }

    @Test
    void rejectsNonPrivateLockPermissionsWithoutRepairingThem() throws Exception {
        requirePosix();
        Path root = privateRoot();
        Path lock = privateFile(root.resolve(LOCK_NAME));
        for (String permissions : new String[]{"rw-r-----", "rw----r--", "rwx------", "r--------"}) {
            Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString(permissions));
            assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> TaskLifecycleGuard.tryAcquireExisting(root)).isInstanceOf(IOException.class);
            assertThat(Files.getPosixFilePermissions(lock))
                    .isEqualTo(PosixFilePermissions.fromString(permissions));
        }
    }

    @Test
    void rejectsMissingNonDirectoryAndEscapingTaskPaths() throws Exception {
        Path root = privateRoot();
        Path task = Files.createDirectory(root.resolve("task"));
        Path nested = Files.createDirectory(task.resolve("nested"));
        Path file = Files.writeString(root.resolve("file"), "data");
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
            lease.requireTaskDirectory(task);
            for (Path invalid : new Path[]{root, canonicalTemp(), nested, file, root.resolve("missing"),
                    root.resolve("../outside"), Path.of("task")}) {
                assertThatThrownBy(() -> lease.requireTaskDirectory(invalid)).isInstanceOf(IOException.class);
            }
            assertThatThrownBy(() -> lease.requireTaskDirectory(null)).isInstanceOf(IOException.class);
        }
        assertThat(root.resolve("missing")).doesNotExist();
    }

    @Test
    void rejectsTaskDirectoryLink() throws Exception {
        requirePosix();
        Path root = privateRoot();
        Path actual = Files.createDirectory(canonicalTemp().resolve("actual"));
        Path task = Files.createSymbolicLink(root.resolve("task"), actual);
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
            assertThatThrownBy(() -> lease.requireTaskDirectory(task)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsNestedLeaseWithoutInvalidatingOriginal() throws Exception {
        Path root = privateRoot();
        Path task = Files.createDirectory(root.resolve("task"));
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
            assertThatThrownBy(() -> TaskLifecycleGuard.acquire(root, false))
                    .isInstanceOf(IOException.class).hasMessageContaining("Nested");
            assertThatThrownBy(() -> TaskLifecycleGuard.acquire(canonicalTemp().resolve("other"), true))
                    .isInstanceOf(IOException.class).hasMessageContaining("Nested");
            lease.requireTaskDirectory(task);
        }
        assertThat(canonicalTemp().resolve("other")).doesNotExist();
    }

    @Test
    void closedLeaseIsIdempotentAndCannotValidateTasks() throws Exception {
        Path root = privateRoot();
        Path task = Files.createDirectory(root.resolve("task"));
        TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false);
        lease.close();
        lease.close();
        assertThatThrownBy(() -> lease.requireTaskDirectory(task)).isInstanceOf(IOException.class);
        try (TaskLifecycleGuard.Lease next = TaskLifecycleGuard.acquire(root, false)) {
            next.requireTaskDirectory(task);
        }
    }

    @Test
    void replacementLockMakesCloseFailButReleasesOriginalLocks() throws Exception {
        Path root = privateRoot();
        Path task = Files.createDirectory(root.resolve("task"));
        TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false);
        Path previous = root.resolve("previous-lock");
        try {
            Files.move(root.resolve(LOCK_NAME), previous);
            privateFile(root.resolve(LOCK_NAME));
            assertThatThrownBy(() -> lease.requireTaskDirectory(task)).isInstanceOf(IOException.class);
        } finally {
            assertThatThrownBy(lease::close).isInstanceOf(IOException.class);
        }
        try (FileChannel oldChannel = FileChannel.open(previous, StandardOpenOption.WRITE);
             FileLock oldLock = oldChannel.tryLock();
             TaskLifecycleGuard.Lease next = TaskLifecycleGuard.acquire(root, false)) {
            assertThat(oldLock).isNotNull();
            next.requireTaskDirectory(task);
        }
    }

    @Test
    void replacementRootMakesCloseFailAndStillAllowsLaterAcquisition() throws Exception {
        requirePosix();
        Path root = privateRoot();
        TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false);
        Path previous = canonicalTemp().resolve("previous-root");
        try {
            Files.move(root, previous);
            assertThat(privateRoot()).isEqualTo(root);
        } finally {
            assertThatThrownBy(lease::close).isInstanceOf(IOException.class);
        }
        try (FileChannel oldChannel = FileChannel.open(previous.resolve(LOCK_NAME), StandardOpenOption.WRITE);
             FileLock oldLock = oldChannel.tryLock();
             TaskLifecycleGuard.Lease next = TaskLifecycleGuard.acquire(root, false)) {
            assertThat(oldLock).isNotNull();
            Path task = Files.createDirectory(root.resolve("task"));
            next.requireTaskDirectory(task);
        }
    }

    @Test
    void lockMutationIsDetectedAndCloseStillReleasesAdmission() throws Exception {
        Path root = privateRoot();
        TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false);
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertThatThrownBy(() -> Files.writeString(root.resolve(LOCK_NAME), "changed"))
                        .isInstanceOf(IOException.class);
                assertThat(Files.size(root.resolve(LOCK_NAME))).isZero();
                lease.requireTaskDirectory(Files.createDirectory(root.resolve("retained-task")));
            } else {
                Files.writeString(root.resolve(LOCK_NAME), "changed");
                assertThatThrownBy(lease::close).isInstanceOf(IOException.class);
                Files.write(root.resolve(LOCK_NAME), new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            }
        } finally {
            lease.close();
        }
        try (TaskLifecycleGuard.Lease next = TaskLifecycleGuard.acquire(root, false)) {
            Path task = Files.createDirectory(root.resolve("task"));
            next.requireTaskDirectory(task);
        }
    }

    @Test
    void sameJvmContenderWaitsUntilOwnerReleasesLease() throws Exception {
        Path root = privateRoot();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        Future<Boolean> contender;
        try {
            try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
                contender = executor.submit(() -> {
                    entered.countDown();
                    try (TaskLifecycleGuard.Lease next = TaskLifecycleGuard.acquire(root, false)) {
                        return true;
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> contender.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }
            assertThat(contender.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedContenderPreservesInterruptAndDoesNotReleaseOwnerLock() throws Exception {
        Path root = privateRoot();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            started.countDown();
            try (TaskLifecycleGuard.Lease ignored = TaskLifecycleGuard.acquire(root, false)) {
                unexpected.set(new AssertionError("Interrupted contender acquired a lease"));
            } catch (IOException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        }, "task-lifecycle-interrupt-test");
        try (TaskLifecycleGuard.Lease owner = TaskLifecycleGuard.acquire(root, false)) {
            contender.start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            contender.interrupt();
            contender.join(5000);
            assertThat(contender.isAlive()).isFalse();
            assertThat(unexpected.get()).isNull();
            assertThat(interrupted.get()).isTrue();
            owner.requireTaskDirectory(Files.createDirectory(root.resolve("task")));
        } finally {
            contender.interrupt();
            contender.join(5000);
        }
    }

    @Test
    void anotherThreadCannotCloseOrUseOwnersLease() throws Exception {
        Path root = privateRoot();
        Path task = Files.createDirectory(root.resolve("task"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(root, false)) {
            executor.submit(() -> {
                assertThatThrownBy(lease::close).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> lease.requireTaskDirectory(task)).isInstanceOf(IOException.class);
            }).get(5, TimeUnit.SECONDS);
            lease.requireTaskDirectory(task);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void independentJavaProcessConflictsAndNormalExitReleasesLock() throws Exception {
        verifyIndependentProcess(false, false);
    }

    @Test
    void independentJavaProcessAbruptExitReleasesLock() throws Exception {
        verifyIndependentProcess(true, false);
    }

    @Test
    void existingOnlyConflictsWithIndependentProcessAndAcquiresAfterExit() throws Exception {
        verifyIndependentProcess(false, true);
    }

    @Test
    void existingOnlyAcquiresAfterIndependentProcessAbruptExit() throws Exception {
        verifyIndependentProcess(true, true);
    }

    private void verifyIndependentProcess(boolean abrupt, boolean existingOnly) throws Exception {
        Path root = privateRoot();
        Path log = canonicalTemp().resolve("holder.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx64m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=16m", "-cp",
                System.getProperty("java.class.path"), LockHolder.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            awaitHolder(process, log);
            long before = System.nanoTime();
            assertThatThrownBy(() -> {
                if (existingOnly) {
                    TaskLifecycleGuard.tryAcquireExisting(root);
                } else {
                    TaskLifecycleGuard.acquire(root, false);
                }
            })
                    .isInstanceOf(IOException.class).hasMessageContaining("already held");
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(2));
            if (abrupt) {
                process.destroyForcibly();
            } else {
                process.getOutputStream().close();
            }
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            if (!abrupt) {
                assertThat(process.exitValue()).isZero();
            }
            try (TaskLifecycleGuard.Lease lease = existingOnly
                    ? TaskLifecycleGuard.tryAcquireExisting(root) : TaskLifecycleGuard.acquire(root, false)) {
                lease.requireTaskDirectory(Files.createDirectory(root.resolve("task")));
            }
        } finally {
            process.getOutputStream().close();
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static void awaitHolder(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertThat(Files.size(log)).isLessThanOrEqualTo(8192);
            String output = Files.readString(log);
            if (output.contains("LOCKED")) {
                assertThat(process.isAlive()).isTrue();
                return;
            }
            assertThat(process.isAlive()).as("Lock holder exited: %s", output).isTrue();
            Thread.sleep(25);
        }
        throw new AssertionError("Independent lock holder did not signal readiness");
    }

    private Path canonicalTemp() throws IOException {
        return tempDir.toRealPath();
    }

    private Path privateRoot() throws IOException {
        Path root = canonicalTemp().resolve("storage");
        if (posix(canonicalTemp())) {
            return Files.createDirectory(root, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createDirectory(root);
    }

    private static Path privateFile(Path path) throws IOException {
        if (posix(path.getParent())) {
            return Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createFile(path);
    }

    private static Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static boolean posix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private void requirePosix() throws IOException {
        assumeTrue(posix(canonicalTemp()), "POSIX-specific filesystem contract");
    }

    public static final class LockHolder {
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected one trusted root path");
            }
            try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(Path.of(arguments[0]), false)) {
                System.out.println("LOCKED");
                System.out.flush();
                if (System.in.read() != -1) {
                    throw new IOException("Unexpected lock-holder input");
                }
            }
        }
    }
}
