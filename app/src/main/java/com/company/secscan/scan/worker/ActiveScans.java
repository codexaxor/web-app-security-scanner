package com.company.secscan.scan.worker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * This worker's in-memory set of the scan ids it is currently processing ("live"). A scan id is added BEFORE its
 * per-scan workspace dir or scanner container is created and removed only AFTER both are gone (see
 * {@link ScanJobProcessor}), so there is never a moment when a dir or container exists for a scan that is not
 * registered here. {@link ScanReaper} treats exactly these scans as live and never reaps them; everything else
 * carrying this instance's scanner labels, or matching a scan-dir name under this instance's work root, is a
 * crash/restart leftover.
 *
 * <p>A freshly (re)started worker starts with an empty set, so every pre-existing leftover is reaped on the first
 * tick. This is a process-local view; one worker per instance id is assumed (D-102).
 */
@Component
@Profile("worker")
public class ActiveScans {

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public void markActive(String scanId) {
        active.add(scanId);
    }

    public void clear(String scanId) {
        active.remove(scanId);
    }

    public boolean isActive(String scanId) {
        return active.contains(scanId);
    }
}
