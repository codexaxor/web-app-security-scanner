package com.company.secscan.intel;

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
        byte[] gz = client().get().uri(props.getEpssUrl()).retrieve().body(byte[].class);
        if (gz == null) {
            throw new IllegalStateException("Empty EPSS response from " + props.getEpssUrl());
        }
        List<EpssRow> rows = new ArrayList<>();
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
                    continue;
                }
                try {
                    BigDecimal epss = new BigDecimal(cols[1].trim());
                    BigDecimal pct = cols.length >= 3 && !cols[2].isBlank() ? new BigDecimal(cols[2].trim()) : null;
                    rows.add(new EpssRow(cols[0].trim(), epss, pct));
                } catch (NumberFormatException ex) {
                    log.debug("Skipping malformed EPSS row: {}", line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read EPSS feed from " + props.getEpssUrl(), e);
        }
        return rows;
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
}
