package com.company.secscan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

/**
 * Logs which process started. The api and worker processes are the same jar with a different active profile
 * (D-009); these markers make the split visible in the logs until each profile grows real behavior.
 */
@Configuration
public class ProfileStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(ProfileStartupLogger.class);

    @Configuration
    @Profile("api")
    static class ApiMarker {
        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            log.info("SecScan started in API mode (REST, scheduler, intel sync, re-score).");
        }
    }

    @Configuration
    @Profile("worker")
    static class WorkerMarker {
        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            log.info("SecScan started in WORKER mode (claims scan jobs, launches scanner containers).");
        }
    }
}
