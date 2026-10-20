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
     * A small EXEC-able tmpfs for the SAST engine's own runtime (D-056). Opengrep (M6) is a PyInstaller
     * single-file binary that, at every run, unpacks its own interpreter + {@code opengrep-core} under
     * {@code $HOME} and execs the core; the {@code noexec} {@code /work} forbids that, and pre-unpacking onto the
     * read-only image layer does not work (it re-unpacks + execs at runtime). No pip/standalone-musl-core path
     * exists for the pinned release, so a dedicated writable+exec mount is genuinely required. run-scan.sh points
     * ONLY Opengrep's HOME/TMPDIR/XDG_CACHE_HOME here; it holds Opengrep's own unpacked runtime + copies of OUR
     * rule configs + Opengrep's run log. The scanned repository stays on {@code noexec} {@code /work}: no repo
     * file is copied onto this mount or executed from it, and the scanned repo cannot write to it (only Opengrep
     * does) — so "never execute scanned code" (D-004) is unchanged.
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
        // EXEC-able tmpfs ONLY for the SAST engine's own unpacked runtime (Opengrep PyInstaller self-extract +
        // core exec; D-056). The repo is never placed there, so "never execute scanned code" (D-004) holds; it
        // is nosuid/nodev and size-capped (512m — Opengrep's unpacked runtime is ~240 MiB).
        String workOpts = "rw,noexec,nosuid,nodev,mode=1777,size=" + s.tmpfsSize();
        // The exec tmpfs is owned by the scan uid:gid and mode 0700 (not world-writable) — the scan user is the
        // only writer, and nothing else in the container can read/write Opengrep's unpacked runtime. /work stays
        // mode=1777 (shared scratch). Falls back to 1777 if the uid:gid is not a clean numeric pair.
        String toolExecOpts = execMountOpts(s.runAsUser());
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

    /** The SAST exec tmpfs options: owned by the scan uid:gid and mode 0700 when {@code runAsUser} is a clean
     * numeric {@code uid:gid} pair, else a safe world-writable fallback. nosuid/nodev and size-capped always. */
    static String execMountOpts(String runAsUser) {
        String base = "rw,exec,nosuid,nodev,size=512m";
        if (runAsUser != null) {
            String[] parts = runAsUser.split(":");
            if (parts.length == 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
                return base + ",mode=0700,uid=" + parts[0] + ",gid=" + parts[1];
            }
        }
        return base + ",mode=1777";
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
