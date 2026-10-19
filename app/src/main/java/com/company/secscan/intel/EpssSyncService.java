package com.company.secscan.intel;

import com.company.secscan.intel.IntelRows.EpssRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the FIRST EPSS bulk scores into {@code epss_score} (D-047). The feed is a full daily snapshot of
 * ~350k rows, so this does a bulk staged swap (change #3), never row-by-row upserts: batch-insert into a
 * session-temp table, then one upsert into {@code epss_score} and one delete of CVEs no longer present — unless
 * the empty/short-feed guard (SF2) rejects the parsed set, in which case the existing scores are kept untouched.
 * A global cache (D-050); no workspace scope. EPSS is stored and displayed but NOT applied to scores in M5.
 * Loaded daily by {@code EpssSyncService} via {@code IntelSyncScheduler} (api profile).
 */
@Profile("api")
@Service
public class EpssSyncService {

    private static final Logger log = LoggerFactory.getLogger(EpssSyncService.class);
    private static final int BATCH = 5000;

    private final IntelFetcher fetcher;
    private final JdbcTemplate jdbc;
    private final IntelProperties props;
    private final TransactionTemplate tx;

    public EpssSyncService(IntelFetcher fetcher, JdbcTemplate jdbc, IntelProperties props,
                           PlatformTransactionManager txManager) {
        this.fetcher = fetcher;
        this.jdbc = jdbc;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Fetches the live feed and loads it. */
    public int refresh() {
        List<EpssRow> rows = fetcher.fetchEpss();
        int n = load(rows);
        log.info("EPSS sync: {} rows now loaded", n);
        return n;
    }

    /**
     * Staged bulk load of already-fetched rows. Wrapped in an explicit transaction (not {@code @Transactional},
     * which {@link #refresh()}'s self-invocation would bypass) so the session-temp staging table (created
     * {@code on commit drop}) and the swap share one connection and commit atomically. Aborts the swap (keeps the
     * existing scores) when the parsed set fails the empty/short-feed guard (SF2). Returns the row count now in
     * the table.
     */
    public int load(List<EpssRow> rows) {
        return tx.execute(status -> {
            Long currentBox = jdbc.queryForObject("select count(*) from epss_score", Long.class);
            long current = currentBox == null ? 0 : currentBox;
            if (!IntelLoadGuard.shouldReplace(log, "EPSS", rows.size(), current, props.getMinEpssRows(),
                    props.getMinRetainedFraction())) {
                return (int) current; // keep the existing scores; never delete-then-load-nothing
            }
            return jdbc.execute((Connection con) -> {
            try (Statement s = con.createStatement()) {
                s.execute("create temporary table epss_stage (cve text primary key, epss numeric, "
                        + "percentile numeric) on commit drop");
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "insert into epss_stage (cve, epss, percentile) values (?, ?, ?) "
                            + "on conflict (cve) do nothing")) {
                int i = 0;
                for (EpssRow r : rows) {
                    ps.setString(1, r.cve());
                    ps.setBigDecimal(2, r.epss());
                    ps.setBigDecimal(3, r.percentile());
                    ps.addBatch();
                    if (++i % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            try (Statement s = con.createStatement()) {
                // Upsert the snapshot, then drop rows that fell out of it — the "swap".
                s.execute("insert into epss_score (cve, epss, percentile, synced_at) "
                        + "select cve, epss, percentile, now() from epss_stage "
                        + "on conflict (cve) do update set epss = excluded.epss, "
                        + "percentile = excluded.percentile, synced_at = now()");
                s.execute("delete from epss_score e where not exists "
                        + "(select 1 from epss_stage s where s.cve = e.cve)");
            }
            return rows.size();
            });
        });
    }
}
