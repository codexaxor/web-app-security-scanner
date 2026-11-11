package com.company.secscan.scan.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Review S1: the startup reap runs BEFORE the stale-lease sweep and the first claim of every poll tick. */
class ScanPollerTest {

    private final ScanClaimer claimer = mock(ScanClaimer.class);
    private final ScanJobProcessor processor = mock(ScanJobProcessor.class);
    private final ScannerEnvironmentCheck environment = mock(ScannerEnvironmentCheck.class);
    private final ScanReaper reaper = mock(ScanReaper.class);
    private final ScanPoller poller = new ScanPoller(claimer, processor, environment, new ScannerProperties(), reaper);

    @AfterEach
    void tearDown() {
        poller.shutdown();
    }

    @Test
    void reapsBeforeRequeueingOrClaiming() {
        when(environment.isReady()).thenReturn(true);
        when(claimer.claimBatch(anyInt())).thenReturn(List.of());

        poller.poll();

        InOrder order = inOrder(reaper, claimer);
        order.verify(reaper).reapBeforeFirstClaim();
        order.verify(claimer).sweepStaleLeases(any(Duration.class));
        order.verify(claimer).claimBatch(anyInt());
    }

    @Test
    void anUnreadyEnvironmentNeitherReapsNorClaims() {
        when(environment.isReady()).thenReturn(false);

        poller.poll();

        verify(reaper, never()).reapBeforeFirstClaim();
        verify(claimer, never()).claimBatch(anyInt());
    }
}
