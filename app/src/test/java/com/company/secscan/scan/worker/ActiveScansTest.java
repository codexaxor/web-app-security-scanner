package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The live-scan registry is reference-counted (review N1) and guards the reaper's deletions (review S1). */
class ActiveScansTest {

    private final ActiveScans scans = new ActiveScans();

    @Test
    void twoMarksOneClearStillActive() {
        scans.markActive("s1");
        scans.markActive("s1");   // a second, concurrent run of the same scan
        scans.clear("s1");        // the first run finishes

        assertThat(scans.isActive("s1")).isTrue();
        assertThat(scans.holders("s1")).isEqualTo(1);

        scans.clear("s1");
        assertThat(scans.isActive("s1")).isFalse();
        assertThat(scans.holders("s1")).isZero();
    }

    @Test
    void clearingAnUnknownIdIsANoOp() {
        scans.clear("never-marked");
        assertThat(scans.isActive("never-marked")).isFalse();
    }

    @Test
    void runIfInactiveSkipsALiveScanAndRunsForADeadOne() {
        scans.markActive("live");
        AtomicBoolean ranLive = new AtomicBoolean();
        AtomicBoolean ranDead = new AtomicBoolean();

        assertThat(scans.runIfInactive("live", () -> ranLive.set(true))).isFalse();
        assertThat(scans.runIfInactive("dead", () -> ranDead.set(true))).isTrue();

        assertThat(ranLive).isFalse();
        assertThat(ranDead).isTrue();
    }

    @Test
    void aScanCannotBecomeLiveWhileTheReaperIsDeletingIt() throws Exception {
        // The reaper's deletion holds the registry monitor; a run registering the same id must wait for it.
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean liveDuringDelete = new AtomicBoolean();
        Thread reaper = new Thread(() -> scans.runIfInactive("s", () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            liveDuringDelete.set(scans.isActive("s"));
        }));
        reaper.start();
        inside.await();
        Thread run = new Thread(() -> scans.markActive("s"));
        run.start();
        Thread.sleep(100);                 // give the run a chance to (wrongly) register mid-deletion
        assertThat(run.isAlive()).as("markActive blocks while the reaper holds the monitor").isTrue();
        release.countDown();
        reaper.join(5000);
        run.join(5000);

        assertThat(liveDuringDelete).isFalse();
        assertThat(scans.isActive("s")).isTrue();
    }
}
