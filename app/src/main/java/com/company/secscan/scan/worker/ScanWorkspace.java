package com.company.secscan.scan.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A per-scan working directory on the host work root (point 3 / D-022):
 * {@code <workRoot>/<instanceId>/<scanId>} with mode {@code 0700} (D-102: each worker instance owns its own
 * sub-tree, so stacks that share one work root never see — or reap — each other's dirs), holding the {@code out} bind-mount target and the optional one-time token file ({@code 0600}).
 * Created before the container runs and deleted in a {@code finally} (so the token file never outlives the
 * scan). Because the worker shares the work root with the Docker daemon at the same absolute path, these are
 * also the host paths the container binds.
 */
public final class ScanWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScanWorkspace.class);

    private final Path root;
    private final Path outDir;
    private Path tokenFile;

    private ScanWorkspace(Path root, Path outDir) {
        this.root = root;
        this.outDir = outDir;
    }

    /**
     * This instance's sub-tree of the work root: {@code <workRoot>/<instanceId>}. The instance id must be one
     * plain path segment (letters, digits, {@code . _ -}; not {@code .} or {@code ..}) so it can never point
     * outside the work root.
     */
    public static Path instanceRoot(Path workRoot, String instanceId) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9._-]{1,64}")
                || instanceId.equals(".") || instanceId.equals("..")) {
            throw new IllegalArgumentException(
                    "secscan.scanner.instance-id must be 1-64 chars of [A-Za-z0-9._-] and not '.' or '..'");
        }
        return workRoot.toAbsolutePath().normalize().resolve(instanceId);
    }

    /** Creates a fresh workspace; an existing dir for this scan is NOT replaced ({@link WorkspaceBusyException}). */
    public static ScanWorkspace create(Path workRoot, String instanceId, String scanId) {
        return create(workRoot, instanceId, scanId, false);
    }

    /**
     * Creates {@code <workRoot>/<instanceId>/<scanId>}. If that dir already exists it is either a leftover of an
     * earlier, dead run of this scan (a crash before the reaper got to it) or the dir of a run that is still live.
     * With {@code replaceLeftover} (the caller has established, under {@link ActiveScans}' lock, that no other run
     * holds this scan) the leftover — token file included — is deleted and the workspace re-created; otherwise
     * {@link WorkspaceBusyException} is thrown and the existing dir is left untouched (review S1).
     */
    public static ScanWorkspace create(Path workRoot, String instanceId, String scanId, boolean replaceLeftover) {
        try {
            Path instanceRoot = instanceRoot(workRoot, instanceId);
            if (!Files.isDirectory(instanceRoot)) {
                Files.createDirectories(workRoot);
                try {
                    Files.createDirectory(instanceRoot,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // created concurrently by another scan of this instance (maxParallel > 1) — fine
                }
            }
            Path root = instanceRoot.resolve(scanId);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!replaceLeftover) {
                    throw new WorkspaceBusyException();
                }
                try {
                    deleteTree(root);
                } catch (IOException e) {
                    // Surfaced as "already exists": the caller treats it as transient, never a permanent failure.
                    FileAlreadyExistsException busy = new FileAlreadyExistsException(scanId, null,
                            "leftover scan workspace could not be removed");
                    busy.initCause(e);
                    throw busy;
                }
            }
            Files.createDirectory(root,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path outDir = Files.createDirectory(root.resolve("out"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return new ScanWorkspace(root, outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create scan workspace for " + scanId, e);
        }
    }

    /** Writes the one-time token file ({@code 0600}) and returns its path. */
    public Path writeToken(String token) {
        try {
            Path file = root.resolve("token");
            Files.createFile(file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, token, StandardCharsets.UTF_8);
            this.tokenFile = file;
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write token file", e);
        }
    }

    public Path root() {
        return root;
    }

    public Path outDir() {
        return outDir;
    }

    public Path tokenFile() {
        return tokenFile;
    }

    /** The uid:gid that owns this workspace — the scanner container runs as this so it can write {@code /out}. */
    public String ownerUserSpec() {
        try {
            Object uid = Files.getAttribute(root, "unix:uid");
            Object gid = Files.getAttribute(root, "unix:gid");
            return uid + ":" + gid;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workspace owner", e);
        }
    }

    public boolean ownedByRoot() {
        return ownerUserSpec().startsWith("0:");
    }

    /**
     * Thrown when the scan's workspace dir already exists and may belong to a live run. Retryable — never a
     * permanent configuration failure.
     */
    public static final class WorkspaceBusyException extends RuntimeException {
        WorkspaceBusyException() {
            super("scan workspace already exists (another run of this scan may be in progress)");
        }
    }

    /** Deletes a tree deepest-first without following symlinks (a symlink is removed as a link). */
    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void close() {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {} while cleaning up scan workspace", p);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up scan workspace {}", root);
        }
    }
}
