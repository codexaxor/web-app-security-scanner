package com.company.secscan.scan.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A per-scan working directory on the host work root (point 3 / D-022): {@code <workRoot>/<scanId>} with mode
 * {@code 0700}, holding the {@code out} bind-mount target and the optional one-time token file ({@code 0600}).
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

    public static ScanWorkspace create(Path workRoot, String scanId) {
        try {
            Files.createDirectories(workRoot);
            Path root = workRoot.resolve(scanId);
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
