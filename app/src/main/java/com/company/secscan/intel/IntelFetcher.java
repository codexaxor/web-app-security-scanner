package com.company.secscan.intel;

import com.company.secscan.intel.IntelRows.EolRow;
import com.company.secscan.intel.IntelRows.EpssRow;
import com.company.secscan.intel.IntelRows.KevFeed;
import com.company.secscan.intel.IntelRows.KevRow;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches and parses the CISA KEV JSON feed and the FIRST EPSS bulk CSV (D-046/D-047). HTTPS with TLS
 * verification always on (never disabled); bounded connect/read timeouts. Parsing only — no DB access. The
 * EPSS payload is gzip; its CSV carries a leading {@code #model_version,...} comment line before the header.
 */
@Profile("api")
@Component
public class IntelFetcher {

    private static final Logger log = LoggerFactory.getLogger(IntelFetcher.class);

    private final IntelProperties props;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public IntelFetcher(IntelProperties props) {
        this.props = props;
    }

    /** Downloads and parses the KEV catalog. */
    public KevFeed fetchKev() {
        requireHttpsOrLoopback(props.getKevUrl());
        byte[] body = client().get().uri(props.getKevUrl()).retrieve().body(byte[].class);
        if (body == null) {
            throw new IllegalStateException("Empty KEV response from " + props.getKevUrl());
        }
        try {
            KevEnvelope env = mapper.readValue(body, KevEnvelope.class);
            List<KevRow> rows = new ArrayList<>();
            if (env.vulnerabilities != null) {
                for (KevVuln v : env.vulnerabilities) {
                    if (v.cveID == null || v.cveID.isBlank()) {
                        continue;
                    }
                    rows.add(new KevRow(v.cveID.trim(), v.vulnerabilityName, parseDate(v.dateAdded)));
                }
            }
            return new KevFeed(env.catalogVersion, rows);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse KEV feed from " + props.getKevUrl(), e);
        }
    }

    /** Downloads, gunzips and parses the EPSS bulk CSV into rows (cve, epss, percentile). */
    public List<EpssRow> fetchEpss() {
        requireHttpsOrLoopback(props.getEpssUrl());
        byte[] gz = client().get().uri(props.getEpssUrl()).retrieve().body(byte[].class);
        if (gz == null) {
            throw new IllegalStateException("Empty EPSS response from " + props.getEpssUrl());
        }
        List<EpssRow> rows = new ArrayList<>();
        int malformed = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gz)), StandardCharsets.UTF_8))) {
            String line;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue; // skip the #model_version,... comment line(s)
                }
                if (!headerSeen) {
                    headerSeen = true; // the cve,epss,percentile header
                    continue;
                }
                String[] cols = line.split(",");
                if (cols.length < 2 || cols[0].isBlank()) {
                    malformed++;
                    continue;
                }
                try {
                    BigDecimal epss = new BigDecimal(cols[1].trim());
                    BigDecimal pct = cols.length >= 3 && !cols[2].isBlank() ? new BigDecimal(cols[2].trim()) : null;
                    rows.add(new EpssRow(cols[0].trim(), epss, pct));
                } catch (NumberFormatException ex) {
                    malformed++; // never log the row content (defensive); count only
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read EPSS feed from " + props.getEpssUrl(), e);
        }
        if (malformed > 0) {
            log.warn("EPSS feed: skipped {} malformed row(s) out of {} parsed", malformed, rows.size() + malformed);
        }
        return rows;
    }

    /**
     * Fetches one endoflife.date v1 product's release cycles (D-055): GET {@code <base>/products/<slug>/}, whose
     * {@code result.releases[]} carries the camelCase cycle facts. HTTPS + TLS always on. Returns one
     * {@link EolRow} per release; an empty/absent releases list yields an empty list (never throws for "no data").
     */
    public List<EolRow> fetchEol(String baseUrl, String slug) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = base + "/products/" + slug + "/";
        requireHttpsOrLoopback(url);
        byte[] body = client().get().uri(url).retrieve().body(byte[].class);
        if (body == null) {
            throw new IllegalStateException("Empty EOL response from " + url);
        }
        try {
            EolEnvelope env = mapper.readValue(body, EolEnvelope.class);
            List<EolRow> rows = new ArrayList<>();
            if (env.result != null && env.result.releases != null) {
                for (EolRelease r : env.result.releases) {
                    if (r.name == null || r.name.isBlank()) {
                        continue;
                    }
                    String latestName = r.latest == null ? null : r.latest.name;
                    LocalDate latestDate = r.latest == null ? null : parseDate(r.latest.date);
                    rows.add(new EolRow(slug, r.name.trim(), r.isEol, parseDate(r.eolFrom), latestName,
                            latestDate, parseDate(r.releaseDate), r.isLts, r.isMaintained));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse EOL feed from " + url, e);
        }
    }

    private RestClient client() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * A feed URL must be {@code https}, except that a loopback host may use {@code http} (the same carve-out the
     * GitLab clone-host check makes for the test WireMock, D-018). Plain-http egress to any real host would send
     * the request — and expose it to tampering/MITM that could poison the KEV/EPSS caches — in the clear, so it is
     * rejected. TLS verification itself is never disabled.
     */
    static void requireHttpsOrLoopback(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid intel feed URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && isLoopbackHost(uri.getHost())) {
            return; // loopback http carve-out (D-018), for the test WireMock
        }
        throw new IllegalStateException("Intel feed URL must use https (loopback http only): " + url);
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h) || "[::1]".equals(h)) {
            return true;
        }
        return h.startsWith("127.");
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class KevEnvelope {
        @JsonProperty("catalogVersion")
        public String catalogVersion;
        public List<KevVuln> vulnerabilities;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class KevVuln {
        public String cveID;
        public String vulnerabilityName;
        public String dateAdded;
    }

    // --- endoflife.date v1 bindings (D-055): {schema_version, generated_at, result:{releases:[...]}} ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EolEnvelope {
        public EolProduct result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EolProduct {
        public List<EolRelease> releases;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EolRelease {
        public String name;         // the cycle identifier
        public String releaseDate;
        public boolean isEol;
        public String eolFrom;
        public boolean isLts;
        public boolean isMaintained;
        public EolLatest latest;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EolLatest {
        public String name;
        public String date;
    }
}
