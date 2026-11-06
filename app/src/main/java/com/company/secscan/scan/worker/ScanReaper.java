package com.company.secscan.scan.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reaps the scan leftovers a crashed/restarted worker can leave behind (the M8.13 fact-check gap): a scanner
 * container, and the per-scan host workspace dir (including its one-time token file), that nothing else removes.
 * The stale-lease sweep ({@link ScanClaimer#sweepStaleLeases}) only requeues the DB job; this reaper removes the
 * physical remains. It NEVER changes job/scan/finding state (a failed scanner still closes nothing) and logs
 * counts only — never a path (which could name a token file) and never token content.
 *
 * <p>Runs once synchronously before the worker's first claim ({@link #reapBeforeFirstClaim}) and then every
 * {@code secscan.scanner.reap-interval-ms} (default 10 min). "Live" = a scan
 * id in this worker's {@link ActiveScans} set; everything else is a leftover. It only ever touches:
 * <ul>
 *   <li>containers carrying BOTH scanner labels for THIS instance ({@code secscan.component=scanner} AND
 *       {@code secscan.instance=<own id>}) — a different instance's, or an unlabelled pre-change, container is
 *       never returned by the daemon-side filter, so it is never removed (its count is only logged); and</li>
 *   <li>direct children of THIS instance's sub-tree {@code <workRoot>/<instanceId>} whose name is a scan id
 *       (UUID), never following a symlink and never leaving that sub-tree.</li>
 * </ul>
 * One worker per instance id is assumed (D-102).
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "secscan.scanner.polling-enabled", havingValue = "true", matchIfMissing = true)
public class ScanReaper {

    private static final Logger log = LoggerFactory.getLogger(ScanReaper.class);

    private final DockerClient docker;
    private final ScannerProperties props;
    private final ActiveScans activeScans;

    public ScanReaper(DockerClient docker, ScannerProperties props, ActiveScans activeScans) {
        this.docker = docker;
        this.props = props;
        this.activeScans = activeScans;
    }

    /** The outcome of one reap pass (counts only; returned for tests and logging). */
    public record ReapResult(int containersRemoved, int dirsRemoved, int dirsFailed) {
    }

    /** Counts from one dir sweep. */
    private record DirSweep(int removed, int failed) {
    }

    private volatile boolean startupReapDone;
    private volatile boolean legacyReported;

    /**
     * The periodic sweep. Its first tick is one interval after startup: the STARTUP sweep is not scheduled but run
     * synchronously by {@link ScanPoller} through {@link #reapBeforeFirstClaim()}, so it is guaranteed to finish
     * before this worker claims its first job (review S1).
     */
    @Scheduled(fixedDelayString = "${secscan.scanner.reap-interval-ms:600000}",
            initialDelayString = "${secscan.scanner.reap-interval-ms:600000}")
    public void reapScheduled() {
        reap();
    }

    /**
     * Runs the startup sweep exactly once, synchronously; every later call returns immediately. {@link ScanPoller}
     * calls this before each claim, so no job is claimed until crash leftovers from a previous run are gone.
     */
    public synchronized void reapBeforeFirstClaim() {
        if (!startupReapDone) {
            reap();
            startupReapDone = true;
        }
    }

    /** Reap leftover containers and dirs. Never throws — a reaper failure must not stop the worker. */
    public ReapResult reap() {
        int containers = 0;
        DirSweep dirs = new DirSweep(0, 0);
        try {
            containers = reapContainers();
        } catch (RuntimeException e) {
            log.warn("Scan reaper: container sweep failed ({}); leaving containers for the next tick",
                    e.getClass().getSimpleName());
        }
        try {
            dirs = reapDirs();
        } catch (RuntimeException | IOException e) {
            log.warn("Scan reaper: workspace-dir sweep failed ({}); leaving dirs for the next tick",
                    e.getClass().getSimpleName());
        }
        if (containers > 0 || dirs.removed() > 0) {
            log.info("Scan reaper removed {} leftover scanner container(s) and {} leftover workspace dir(s)",
                    containers, dirs.removed());
        }
        if (dirs.failed() > 0) {
            log.warn("Scan reaper could not fully remove {} leftover workspace dir(s); retrying next tick",
                    dirs.failed());
        }
        return new ReapResult(containers, dirs.removed(), dirs.failed());
    }

    private int reapContainers() {
        String instanceId = props.getInstanceId();
        // Removal list: filter on BOTH labels daemon-side, so we can ONLY ever see THIS instance's scanner
        // containers. A different instance's container, or an unlabelled pre-change container, is never returned
        // here and so is never a removal candidate.
        List<Container> mine = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("secscan.component", "scanner", "secscan.instance", instanceId))
                .exec();
        int removed = 0;
        for (Container c : mine) {
            String scanId = c.getLabels() == null ? null : c.getLabels().get("secscan.scan");
            if (scanId == null) {
                continue; // no scan label (defensive) — never touch
            }
            try {
                // Under ActiveScans' lock: skipped if live, and the scan cannot become live mid-removal.
                if (activeScans.runIfInactive(scanId,
                        () -> docker.removeContainerCmd(c.getId()).withForce(true).exec())) {
                    removed++;
                }
            } catch (RuntimeException e) {
                log.warn("Scan reaper: could not remove a leftover container ({})", e.getClass().getSimpleName());
            }
        }
        // Observability only: count — never remove — scanner containers with no instance label (created before
        // this change). They are left for an operator to clean up.
        long legacy = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("secscan.component", "scanner"))
                .exec().stream()
                .filter(c -> c.getLabels() == null || !c.getLabels().containsKey("secscan.instance"))
                .count();
        if (legacy > 0) {
            // Review N3: INFO once (the first sweep after startup), DEBUG afterwards — not every 10 minutes.
            if (!legacyReported) {
                log.info("Scan reaper left {} scanner container(s) with no instance label untouched "
                        + "(created before instance-id labelling; remove them manually)", legacy);
            } else {
                log.debug("Scan reaper: {} scanner container(s) with no instance label still present", legacy);
            }
        }
        legacyReported = true;
        return removed;
    }

    private DirSweep reapDirs() throws IOException {
        // Only THIS instance's sub-tree (D-102): another instance's dirs, and old-layout scan dirs sitting directly
        // under the work root, are never listed and so never touched.
        Path instanceRoot = ScanWorkspace.instanceRoot(Path.of(props.getWorkRoot()), props.getInstanceId());
        if (!Files.isDirectory(instanceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new DirSweep(0, 0);
        }
        // The fully resolved instance root (the configured work root may itself sit behind a symlink).
        Path realInstanceRoot = instanceRoot.toRealPath();
        int removed = 0;
        int failed = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(instanceRoot)) {
            Iterator<Path> it = children.iterator();
            while (true) {
                Path child;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    child = it.next();
                } catch (DirectoryIteratorException e) {
                    // The listing itself broke; what was reaped so far stands, the rest waits for the next tick.
                    failed++;
                    break;
                }
                // Review S2: one bad entry (unreadable sub-dir, vanished file, ...) is counted and skipped; it must
                // never stop the sweep of the remaining leftovers (which may hold token files).
                try {
                    if (shouldReapDir(realInstanceRoot, child)) {
                        // Under ActiveScans' lock: re-checked live, and the scan cannot become live mid-deletion.
                        boolean[] gone = {false};
                        boolean ran = activeScans.runIfInactive(child.getFileName().toString(),
                                () -> gone[0] = deleteRecursively(child));
                        if (gone[0]) {
                            removed++;
                        } else if (ran) {
                            failed++;
                        }
                    }
                } catch (RuntimeException | IOException e) {
                    failed++;
                }
            }
        }
        return new DirSweep(removed, failed);
    }

    /**
     * A listed entry is reapable iff: its name is a scan id (UUID); the scan is not live; it is a real directory,
     * NOT a symlink (checked without following links, so a link named like a scan id is never followed); and its
     * fully resolved real path is a direct child of the resolved instance root (review N2). The last check is what
     * stops a deletion from leaving the instance sub-tree if the instance dir (or an ancestor below the work root)
     * was swapped for a symlink after the listing began.
     */
    private boolean shouldReapDir(Path realInstanceRoot, Path child) throws IOException {
        String name = child.getFileName().toString();
        if (!isScanDirName(name) || activeScans.isActive(name)) {
            return false;
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return false;   // a symlink (to anything) or a plain file
        }
        Path real = child.toRealPath();
        return realInstanceRoot.equals(real.getParent()) && real.getFileName().toString().equals(name);
    }

    private static boolean isScanDirName(String name) {
        try {
            UUID.fromString(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Delete a scan-dir subtree, deepest first, carrying on past entries it cannot read or delete so that as much
     * as possible (in particular a token file) goes even when part of the tree is stuck. Symlinks are never
     * followed ({@link Files#walkFileTree} without FOLLOW_LINKS): a link is removed as a link and its target is
     * left untouched. Returns true if the root is gone afterwards.
     */
    private static boolean deleteRecursively(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    tryDelete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Unreadable sub-dir: try removing it as-is (works if empty), then move on.
                    tryDelete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    tryDelete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // fall through to the existence check
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
    }

    private static void tryDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // counted by the caller via the root's existence; never logged with a path
        }
    }
}
