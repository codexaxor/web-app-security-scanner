package com.company.secscan.intel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling in the api process (the first api-side @Scheduled jobs are the daily KEV/EPSS sync, M5) and
 * binds {@link IntelProperties}. Nothing here loads in the worker, whose own scheduling lives in
 * {@code WorkerScanConfig}. Multi-instance safety (ShedLock) is M9 — single-instance is assumed now.
 */
@Configuration
@Profile("api")
@EnableScheduling
@EnableConfigurationProperties(IntelProperties.class)
public class ApiSchedulingConfig {
}
