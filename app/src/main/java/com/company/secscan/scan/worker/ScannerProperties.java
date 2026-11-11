package com.company.secscan.scan.worker;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ScannerProperties.class);

    private String image = "secscan-scanner:8.21.2";
    private String workRoot = "/tmp/secscan/scans";
    /**
     * Pass {@code --taint-intrafile} to Opengrep (intra-file inter-procedural taint; D-104, M8.15 S4). Default
     * on. It changes no SAST fingerprint input (rule id / file path / normalized-snippet hash), so turning it on
     * does not churn existing findings — it only adds taint-tracked findings. TaintRescanStabilityIT flips this
     * OFF then ON on the same commit and proves the matched findings keep identical fingerprints (0 triage_events).
     */
    private boolean taintIntrafile = true;
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
     * Ownership tag stamped on every scanner container (label {@code secscan.instance}) and used by
     * {@link ScanReaper}: a worker reaps ONLY leftovers carrying its own instance id, never another stack's
     * (dev / smoke / a Gradle IT run). It is also the name of this worker's sub-tree of the work root
     * ({@code <workRoot>/<instanceId>/<scanId>}), so it must be one plain path segment. Stable across restarts;
     * one worker per instance id (D-102).
     * Sourced from {@code SECSCAN_INSTANCE_ID} (see application-worker.yml), default {@code "default"}.
     */
    private String instanceId = "default";
    /**
     * How often {@link ScanReaper} sweeps for leftovers (default every 10 minutes; configurable). The startup sweep
     * is not delayed: it runs synchronously before the first claim (review S1).
     */
    private long reapIntervalMs = 600_000;
    /**
     * TEST-ONLY: allow cloning a repository over {@code file:///src} from a local path. Default false — in
     * production the worker clones only over https to an allowlisted host (D-018). The offline fixture ITs set
     * this true; it must never be enabled in a real deployment.
     */
    private boolean allowLocalFileClone = false;
    /**
     * TEST-ONLY: give a local {@code file://} clone network access (normally disabled, so offline fixtures
     * prove isolation). The one {@code @Tag("live-osv")} end-to-end test sets this so OSV-Scanner can reach
     * deps.dev; it must never be enabled in a real deployment. Remote https clones always have network.
     */
    private boolean localCloneNetwork = false;

    /**
     * Loudly warn at worker startup if either test-only clone escape hatch is enabled — neither must ever be on
     * in a real deployment (both weaken the clone-scheme/egress posture, D-018/D-033).
     */
    @PostConstruct
    void warnOnTestOnlyFlags() {
        if (allowLocalFileClone) {
            log.warn("secscan.scanner.allow-local-file-clone is TRUE: the worker will clone repositories over "
                    + "file:// from a local path. This is TEST-ONLY and must never be enabled in a deployment "
                    + "(production clones only over https to an allowlisted host, D-018).");
        }
        if (localCloneNetwork) {
            log.warn("secscan.scanner.local-clone-network is TRUE: local file:// clones run WITH network access. "
                    + "This is TEST-ONLY (for the live-osv end-to-end test) and must never be enabled in a "
                    + "deployment.");
        }
    }

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

    public boolean isTaintIntrafile() {
        return taintIntrafile;
    }

    public void setTaintIntrafile(boolean taintIntrafile) {
        this.taintIntrafile = taintIntrafile;
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

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public long getReapIntervalMs() {
        return reapIntervalMs;
    }

    public void setReapIntervalMs(long reapIntervalMs) {
        this.reapIntervalMs = reapIntervalMs;
    }

    public boolean isAllowLocalFileClone() {
        return allowLocalFileClone;
    }

    public void setAllowLocalFileClone(boolean allowLocalFileClone) {
        this.allowLocalFileClone = allowLocalFileClone;
    }

    public boolean isLocalCloneNetwork() {
        return localCloneNetwork;
    }

    public void setLocalCloneNetwork(boolean localCloneNetwork) {
        this.localCloneNetwork = localCloneNetwork;
    }
}
