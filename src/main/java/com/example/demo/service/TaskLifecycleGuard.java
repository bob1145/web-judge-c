package com.example.demo.service;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class TaskLifecycleGuard {
    private static final String LOCK_NAME = ".task-lifecycle.lock";
    private static final ReentrantLock LOCAL_LOCK = new ReentrantLock();
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private TaskLifecycleGuard() {
    }

    public static Lease acquire(Path storageRoot, boolean allowRootCreation) throws IOException {
        return acquire(storageRoot, allowRootCreation, false);
    }

    public static Lease tryAcquireExisting(Path storageRoot) throws IOException {
        return acquire(storageRoot, false, true);
    }

    private static Lease acquire(Path storageRoot, boolean allowRootCreation, boolean existingOnly)
            throws IOException {
        requireNormalizedAbsolute(storageRoot);
        if (LOCAL_LOCK.isHeldByCurrentThread()) {
            throw new IOException("Nested task lifecycle leases are not supported");
        }
        if (existingOnly) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted acquiring task lifecycle lease");
            }
            if (!LOCAL_LOCK.tryLock()) {
                throw new IOException("Task lifecycle lock is already held");
            }
        } else {
            try {
                LOCAL_LOCK.lockInterruptibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted acquiring task lifecycle lease", interrupted);
            }
        }
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            validateDirectoryChain(storageRoot, allowRootCreation);
            Identity rootIdentity = directoryIdentity(storageRoot);
            Path lockPath = storageRoot.resolve(LOCK_NAME);
            boolean created = false;
            if (existingOnly) {
                channel = openExistingLock(lockPath, rootIdentity.owner());
            } else {
                try {
                    if (posix(storageRoot)) {
                        channel = FileChannel.open(lockPath,
                                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                                PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
                    } else {
                        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    }
                    created = true;
                } catch (FileAlreadyExistsException exists) {
                    channel = openExistingLock(lockPath, rootIdentity.owner());
                }
            }
            Identity lockIdentity = lockIdentity(lockPath, rootIdentity.owner());
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException overlap) {
                throw new IOException("Task lifecycle lock is already held", overlap);
            }
            if (fileLock == null) {
                throw new IOException("Task lifecycle lock is already held");
            }
            Lease lease = new Lease(storageRoot, rootIdentity, lockIdentity, channel, fileLock);
            lease.validateIdentity();
            if (created) {
                channel.force(true);
                if (posix(storageRoot)) {
                    try (FileChannel directory = FileChannel.open(storageRoot, StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                }
            }
            return lease;
        } catch (IOException | RuntimeException | Error failure) {
            release(fileLock, channel, failure);
            LOCAL_LOCK.unlock();
            throw failure;
        }
    }

    private static FileChannel openExistingLock(Path lockPath, UserPrincipal rootOwner) throws IOException {
        Identity before = lockIdentity(lockPath, rootOwner);
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            requireSame(before, lockIdentity(lockPath, rootOwner));
            return channel;
        } catch (IOException | RuntimeException | Error failure) {
            release(null, channel, failure);
            throw failure;
        }
    }

    private static void requireNormalizedAbsolute(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IOException("Task lifecycle paths must be absolute and normalized");
        }
    }

    private static void validateDirectoryChain(Path directory, boolean allowCreation) throws IOException {
        Path current = directory.getRoot();
        requireDirectory(current);
        for (Path component : directory) {
            Path parent = current;
            current = current.resolve(component);
            try {
                requireDirectory(current);
            } catch (NoSuchFileException absent) {
                if (!allowCreation) {
                    throw absent;
                }
                try {
                    if (posix(parent)) {
                        Files.createDirectory(current, PRIVATE_DIRECTORY);
                    } else {
                        Files.createDirectory(current);
                    }
                } catch (FileAlreadyExistsException concurrentCreation) {
                    requireDirectory(current);
                }
                requireDirectory(current);
            }
        }
        if (!directory.toRealPath().equals(directory)) {
            throw new IOException("Task lifecycle directory is not canonical");
        }
    }

    private static BasicFileAttributes requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Task lifecycle path is not an identifiable directory: " + directory);
        }
        fileKey(directory, attributes);
        return attributes;
    }

    private static Identity directoryIdentity(Path directory) throws IOException {
        BasicFileAttributes attributes = requireDirectory(directory);
        if (posix(directory)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("Task lifecycle root permits untrusted writes");
            }
        }
        return new Identity(fileKey(directory, attributes), Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS));
    }

    private static Identity lockIdentity(Path lockPath, UserPrincipal rootOwner) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = Files.getOwner(lockPath, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() != 0
                || !rootOwner.equals(owner)) {
            throw new IOException("Invalid task lifecycle lock identity");
        }
        Object key = fileKey(lockPath, attributes);
        if (posix(lockPath) && !Files.getPosixFilePermissions(lockPath, LinkOption.NOFOLLOW_LINKS)
                .equals(PRIVATE_FILE)) {
            throw new IOException("Task lifecycle lock must have private permissions");
        }
        if (Files.getFileStore(lockPath).supportsFileAttributeView("unix")) {
            Object links = Files.getAttribute(lockPath, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number count) || count.longValue() != 1) {
                throw new IOException("Task lifecycle lock must have one link");
            }
        }
        return new Identity(key, owner);
    }

    private static Object fileKey(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes.fileKey() != null) {
            return attributes.fileKey();
        }
        if (!Platform.isWindows() || !path.getFileSystem().equals(FileSystems.getDefault())) {
            throw new IOException("Task lifecycle filesystem identity is unavailable: " + path);
        }
        Kernel32 kernel = Kernel32.INSTANCE;
        WinNT.HANDLE handle = kernel.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
                null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("Cannot open Windows task lifecycle path " + path
                    + ": error " + kernel.GetLastError());
        }
        try {
            WinBase.FILE_ATTRIBUTE_TAG_INFO tag = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            WinBase.FILE_ID_INFO identity = new WinBase.FILE_ID_INFO();
            if (!kernel.GetFileInformationByHandleEx(handle, WinBase.FileAttributeTagInfo,
                    tag.getPointer(), new WinDef.DWORD(tag.size()))
                    || !kernel.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo,
                    identity.getPointer(), new WinDef.DWORD(identity.size()))) {
                throw new IOException("Cannot identify Windows task lifecycle path " + path
                        + ": error " + kernel.GetLastError());
            }
            tag.read();
            identity.read();
            if ((tag.FileAttributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                    || ((tag.FileAttributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0)
                    != attributes.isDirectory()) {
                throw new IOException("Invalid Windows task lifecycle path: " + path);
            }
            if (attributes.isRegularFile()) {
                WinBase.FILE_STANDARD_INFO standard = new WinBase.FILE_STANDARD_INFO();
                if (!kernel.GetFileInformationByHandleEx(handle, WinBase.FileStandardInfo,
                        standard.getPointer(), new WinDef.DWORD(standard.size()))) {
                    throw new IOException("Cannot inspect Windows task lifecycle lock " + path
                            + ": error " + kernel.GetLastError());
                }
                standard.read();
                if (standard.NumberOfLinks != 1) {
                    throw new IOException("Task lifecycle lock must have one link");
                }
            }
            return new WindowsFileKey(identity.VolumeSerialNumber, HexFormat.of().formatHex(
                    identity.FileId.getPointer().getByteArray(0, identity.FileId.size())));
        } finally {
            if (!kernel.CloseHandle(handle)) {
                throw new IOException("Cannot close Windows task lifecycle handle: error "
                        + kernel.GetLastError());
            }
        }
    }

    private record WindowsFileKey(long volumeSerialNumber, String fileId) {
    }

    private static boolean posix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private static void requireSame(Identity expected, Identity actual) throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException("Task lifecycle filesystem identity changed");
        }
    }

    private static void release(FileLock fileLock, FileChannel channel, Throwable failure) {
        if (fileLock != null) {
            try {
                fileLock.release();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private record Identity(Object fileKey, UserPrincipal owner) {
    }

    public static final class Lease implements AutoCloseable {
        private final Path root;
        private final Identity rootIdentity;
        private final Identity lockIdentity;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final Thread ownerThread = Thread.currentThread();
        private boolean closed;

        private Lease(Path root, Identity rootIdentity, Identity lockIdentity,
                      FileChannel channel, FileLock fileLock) {
            this.root = root;
            this.rootIdentity = rootIdentity;
            this.lockIdentity = lockIdentity;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        public void requireTaskDirectory(Path directChild) throws IOException {
            requireOpenOwner();
            requireNormalizedAbsolute(directChild);
            if (!root.equals(directChild.getParent())) {
                throw new IOException("Task directory must be a direct storage child");
            }
            validateIdentity();
            requireDirectory(directChild);
            if (!directChild.toRealPath().equals(directChild)) {
                throw new IOException("Task directory is not canonical");
            }
            validateIdentity();
        }

        private void requireOpenOwner() throws IOException {
            if (closed || Thread.currentThread() != ownerThread || !LOCAL_LOCK.isHeldByCurrentThread()) {
                throw new IOException("Task lifecycle lease is closed or owned by another thread");
            }
        }

        private void validateIdentity() throws IOException {
            requireOpenOwner();
            if (!fileLock.isValid() || !channel.isOpen()) {
                throw new IOException("Task lifecycle lock is no longer held");
            }
            validateDirectoryChain(root, false);
            requireSame(rootIdentity, directoryIdentity(root));
            requireSame(lockIdentity, lockIdentity(root.resolve(LOCK_NAME), rootIdentity.owner()));
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            requireOpenOwner();
            IOException failure = null;
            try {
                validateIdentity();
            } catch (IOException invalid) {
                failure = invalid;
            } finally {
                try {
                    fileLock.release();
                } catch (IOException invalid) {
                    failure = append(failure, invalid);
                } finally {
                    try {
                        channel.close();
                    } catch (IOException invalid) {
                        failure = append(failure, invalid);
                    } finally {
                        closed = true;
                        LOCAL_LOCK.unlock();
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static IOException append(IOException failure, IOException additional) {
            if (failure == null) {
                return additional;
            }
            failure.addSuppressed(additional);
            return failure;
        }
    }
}
