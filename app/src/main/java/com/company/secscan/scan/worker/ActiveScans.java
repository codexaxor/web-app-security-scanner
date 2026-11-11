package com.company.secscan.scan.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * This worker's in-memory registry of the scan ids it is currently processing ("live"). A scan id is registered
 * BEFORE its per-scan workspace dir or scanner container is created and released only AFTER both are gone (see
 * {@link ScanJobProcessor}), so there is never a moment when a dir or container exists for a scan that is not
 * registered here. {@link ScanReaper} treats exactly these scans as live and never reaps them; everything else
 * carrying this instance's scanner labels, or matching a scan-dir name under this instance's sub-tree, is a
 * crash/restart leftover.
 *
 * <p>Registrations are COUNTED per scan id (review N1): if the same scan is processed twice at once (a stale-lease
 * requeue of a job that is in fact still running here, then re-claimed), the first run to finish must not make
 * the other one look dead — the id stays live until every registration is released.
 *
 * <p>All methods share one monitor. {@link #runIfInactive} runs the reaper's deletion of one leftover under it, so
 * a scan cannot become live half-way through the reaper deleting its dir/container, and {@link #underLock} lets
 * the processor create its workspace atomically with respect to the reaper and to a concurrent run.
 *
 * <p>A freshly (re)started worker starts empty, so every pre-existing leftover is reaped on the first pass. This is
 * a process-local view; one worker per instance id is assumed (D-102).
 */
@Component
@Profile("worker")
public class ActiveScans {

    private final Map<String, Integer> active = new HashMap<>();

    public synchronized void markActive(String scanId) {
        active.merge(scanId, 1, Integer::sum);
    }

    /** Releases ONE registration; the id stays live while other registrations remain. */
    public synchronized void clear(String scanId) {
        active.computeIfPresent(scanId, (k, n) -> n > 1 ? n - 1 : null);
    }

    public synchronized boolean isActive(String scanId) {
        return active.containsKey(scanId);
    }

    /** How many runs currently hold this scan id (0 = not live). */
    public synchronized int holders(String scanId) {
        return active.getOrDefault(scanId, 0);
    }

    /**
     * Runs {@code action} only if the scan is not live, holding the registry monitor for its duration so no run
     * can register the id meanwhile. Returns whether it ran.
     */
    public synchronized boolean runIfInactive(String scanId, Runnable action) {
        if (active.containsKey(scanId)) {
            return false;
        }
        action.run();
        return true;
    }

    /** Runs {@code action} holding the registry monitor (no registration change or reaper deletion interleaves). */
    public synchronized <T> T underLock(Supplier<T> action) {
        return action.get();
    }
}
