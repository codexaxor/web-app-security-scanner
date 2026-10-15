package com.company.secscan.scan.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Worker-side scanner configuration ({@code secscan.scanner.*}), with safe defaults so the worker runs without
 * extra config in local dev. Resource limits match the spec's hardening budget (2 CPU / 4 GB / 512 PIDs,
 * 30-minute hard timeout).
 *
 * <p>{@code workRoot} must be a host directory mounted into the worker at the SAME absolute path: the worker
 * talks to the host Docker daemon, so the per-scan token file and {@code /out} bind are host paths (point 3 /
 * D-022).
 */
@ConfigurationProperties(prefix = "secscan.scanner")
public class ScannerProperties {

    private String image = "secscan-scanner:8.21.2";
    private String workRoot = "/tmp/secscan/scans";
    private String tmpfsSize = "2g";
    private DataSize memory = DataSize.ofGigabytes(4);
    private double cpus = 2.0;
    private long pidsLimit = 512;
    private Duration timeout = Duration.ofMinutes(30);
    private int cloneDepth = 1;
    /** Max scanner containers this worker runs at once (point 7). */
    private int maxParallel = 2;
    /** A lease older than the container timeout plus this margin is reclaimed and the job requeued (point 7). */
    private Duration leaseMargin = Duration.ofMinutes(5);
    /** The scheduled claim loop; disabled in tests that drive the pipeline directly. */
    private boolean pollingEnabled = true;
    private long pollIntervalMs = 5000;
    /**
     * TEST-ONLY: allow cloning a repository over {@code file:///src} from a local path. Default false — in
     * production the worker clones only over https to an allowlisted host (D-018). The offline fixture ITs set
     * this true; it must never be enabled in a real deployment.
     */
    private boolean allowLocalFileClone = false;

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getWorkRoot() {
        return workRoot;
    }

    public void setWorkRoot(String workRoot) {
        this.workRoot = workRoot;
    }

    public String getTmpfsSize() {
        return tmpfsSize;
    }

    public void setTmpfsSize(String tmpfsSize) {
        this.tmpfsSize = tmpfsSize;
    }

    public DataSize getMemory() {
        return memory;
    }

    public void setMemory(DataSize memory) {
        this.memory = memory;
    }

    public double getCpus() {
        return cpus;
    }

    public void setCpus(double cpus) {
        this.cpus = cpus;
    }

    public long getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(long pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getCloneDepth() {
        return cloneDepth;
    }

    public void setCloneDepth(int cloneDepth) {
        this.cloneDepth = cloneDepth;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public Duration getLeaseMargin() {
        return leaseMargin;
    }

    public void setLeaseMargin(Duration leaseMargin) {
        this.leaseMargin = leaseMargin;
    }

    public boolean isPollingEnabled() {
        return pollingEnabled;
    }

    public void setPollingEnabled(boolean pollingEnabled) {
        this.pollingEnabled = pollingEnabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public boolean isAllowLocalFileClone() {
        return allowLocalFileClone;
    }

    public void setAllowLocalFileClone(boolean allowLocalFileClone) {
        this.allowLocalFileClone = allowLocalFileClone;
    }
}
