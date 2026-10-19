package com.company.secscan.scan.worker;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the hardened Docker {@link HostConfig}, env and labels for a scanner container (pure, no Docker client,
 * so the hardening is unit-testable). Enforces the spec's container rules: non-root (the caller sets the user),
 * read-only root filesystem with tmpfs {@code /work}, {@code --cap-drop=ALL}, {@code no-new-privileges},
 * CPU/memory/PID limits, and NO Docker socket mount. Egress is the default bridge for real scans and disabled
 * ({@code none}) for offline fixture runs.
 */
final class ScannerContainerConfig {

    static final String WORK_MOUNT = "/work";
    static final String OUT_MOUNT = "/out";
    static final String TOKEN_MOUNT = "/run/secrets/gitlab-token";
    static final String SOURCE_MOUNT = "/src";
    /**
     * A small EXEC-able tmpfs for the SAST engine's own runtime: Opengrep (M6) unpacks its trusted core binary
     * to HOME/TMPDIR and execs it, which the {@code noexec} {@code /work} forbids. run-scan.sh points only
     * Opengrep's HOME/TMPDIR/XDG_CACHE_HOME here. It is isolated from {@code /work}, so the cloned repository is
     * NEVER placed on an exec-able mount — the "never execute scanned code" guarantee (D-004) is unchanged; only
     * our own pinned tool runs here.
     */
    static final String TOOL_EXEC_MOUNT = "/opt/sast-exec";

    private ScannerContainerConfig() {
    }

    static HostConfig buildHostConfig(ScannerLaunchSpec s) {
        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(s.outDir().toString(), new Volume(OUT_MOUNT)));
        if (s.tokenFile() != null) {
            // The token file is mounted read-only; it is the only way the token reaches the container (D-014).
            binds.add(new Bind(s.tokenFile().toString(), new Volume(TOKEN_MOUNT), AccessMode.ro));
        }
        if (s.localSource() != null) {
            // A local git repo to clone over file:///src (offline/local repositories), mounted read-only.
            binds.add(new Bind(s.localSource().toString(), new Volume(SOURCE_MOUNT), AccessMode.ro));
        }
        // /work is tmpfs: writable scratch that dies with the container, mounted noexec/nosuid/nodev so nothing
        // in the cloned repo can be executed (we never run repository code). /opt/sast-exec is a separate, small
        // EXEC-able tmpfs ONLY for the SAST engine's own core (Opengrep execs its unpacked core; D-053). The repo
        // is never placed there, so "never execute scanned code" (D-004) holds; it is nosuid/nodev and capped.
        String workOpts = "rw,noexec,nosuid,nodev,mode=1777,size=" + s.tmpfsSize();
        String toolExecOpts = "rw,exec,nosuid,nodev,mode=1777,size=256m";
        return HostConfig.newHostConfig()
                .withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withPidsLimit(s.pidsLimit())
                .withMemory(s.memoryBytes())
                // memorySwap == memory disables swap entirely, so the scanner cannot evade the memory limit by
                // swapping; the memory cap is a hard ceiling.
                .withMemorySwap(s.memoryBytes())
                .withNanoCPUs((long) (s.cpus() * 1_000_000_000L))
                .withTmpFs(Map.of(WORK_MOUNT, workOpts, TOOL_EXEC_MOUNT, toolExecOpts))
                .withBinds(binds)
                .withNetworkMode(s.networkDisabled() ? "none" : "bridge");
    }

    /** Non-secret inputs only. The token is NEVER an env var — it arrives as a mounted file (D-014). */
    static List<String> buildEnv(ScannerLaunchSpec s) {
        List<String> env = new ArrayList<>();
        env.add("SCAN_CLONE_URL=" + s.cloneUrl());
        if (s.branch() != null && !s.branch().isBlank()) {
            env.add("SCAN_BRANCH=" + s.branch());
        }
        env.add("SCAN_CLONE_DEPTH=" + s.cloneDepth());
        return env;
    }

    static Map<String, String> labels(String scanId) {
        return Map.of("secscan.component", "scanner", "secscan.scan", scanId);
    }
}
