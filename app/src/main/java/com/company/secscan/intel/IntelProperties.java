package com.company.secscan.intel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the daily intel feeds (D-046 KEV, D-047 EPSS). URLs default to the real public feeds and
 * are overridden to a local WireMock in tests so the default {@code ./gradlew build} never touches the network.
 * TLS verification is always on (never disabled) — there is no flag to turn it off.
 */
@ConfigurationProperties(prefix = "secscan.intel")
public class IntelProperties {

    /** CISA KEV catalog JSON feed (US-gov public domain). */
    private String kevUrl = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    /** FIRST EPSS current bulk scores, gzip CSV (data (c) FIRST.org). */
    private String epssUrl = "https://epss.cyentia.com/epss_scores-current.csv.gz";

    /** Whether the daily @Scheduled sync runs (api profile). Off in tests that drive sync explicitly. */
    private boolean scheduledSyncEnabled = true;

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofMinutes(5);

    public String getKevUrl() {
        return kevUrl;
    }

    public void setKevUrl(String kevUrl) {
        this.kevUrl = kevUrl;
    }

    public String getEpssUrl() {
        return epssUrl;
    }

    public void setEpssUrl(String epssUrl) {
        this.epssUrl = epssUrl;
    }

    public boolean isScheduledSyncEnabled() {
        return scheduledSyncEnabled;
    }

    public void setScheduledSyncEnabled(boolean scheduledSyncEnabled) {
        this.scheduledSyncEnabled = scheduledSyncEnabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
