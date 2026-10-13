package com.company.secscan.scan.worker;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The worker's scheduled claim loop: each tick reclaims stale leases, then claims up to the free-slot count and
 * runs each scan on a bounded pool, so at most {@link ScannerProperties#getMaxParallel()} scanner containers
 * run at once (point 7). Disabled via {@code secscan.scanner.polling-enabled=false} in tests that drive the
 * pipeline directly. Multiple worker processes scale out naturally through {@code SKIP LOCKED}.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "secscan.scanner.polling-enabled", havingValue = "true", matchIfMissing = true)
public class ScanPoller {

    private static final Logger log = LoggerFactory.getLogger(ScanPoller.class);

    private final ScanClaimer claimer;
    private final ScanJobProcessor processor;
    private final ScannerEnvironmentCheck environment;
    private final ScannerProperties props;
    private final ExecutorService pool;
    private final AtomicInteger active = new AtomicInteger();

    public ScanPoller(ScanClaimer claimer, ScanJobProcessor processor, ScannerEnvironmentCheck environment,
                      ScannerProperties props) {
        this.claimer = claimer;
        this.processor = processor;
        this.environment = environment;
        this.props = props;
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.getMaxParallel()));
    }

    @Scheduled(fixedDelayString = "${secscan.scanner.poll-interval-ms:5000}",
            initialDelayString = "${secscan.scanner.poll-interval-ms:5000}")
    public void poll() {
        if (!environment.isReady()) {
            // The self-check (ScannerEnvironmentCheck) has logged why; don't claim jobs we can't run.
            return;
        }
        try {
            claimer.sweepStaleLeases(props.getTimeout().plus(props.getLeaseMargin()));
        } catch (RuntimeException e) {
            log.warn("Stale-lease sweep failed: {}", e.getMessage());
        }
        int free = props.getMaxParallel() - active.get();
        if (free <= 0) {
            return;
        }
        List<UUID> claimed;
        try {
            claimed = claimer.claimBatch(free);
        } catch (RuntimeException e) {
            log.warn("Claiming scan jobs failed: {}", e.getMessage());
            return;
        }
        for (UUID jobId : claimed) {
            active.incrementAndGet();
            pool.submit(() -> {
                try {
                    processor.process(jobId);
                } catch (RuntimeException e) {
                    log.error("Unhandled error processing scan job {}", jobId, e);
                } finally {
                    active.decrementAndGet();
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
