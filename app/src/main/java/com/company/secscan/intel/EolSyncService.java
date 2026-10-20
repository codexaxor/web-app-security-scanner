package com.company.secscan.intel;

import com.company.secscan.intel.IntelRows.EolRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads endoflife.date release cycles into {@code eol_cycle} for the products we detect (D-055). Unlike the KEV
 * full-refresh, this is a PER-PRODUCT UPSERT that NEVER wipes the table (BLOCKER 6): a failed or empty fetch for
 * one product keeps that product's existing rows and fails loud (logs an error) while other products still
 * sync. So a transient endoflife.date outage can never blank the cache and false-classify an EOL finding.
 * api profile; run weekly by {@code EolSyncScheduler}.
 */
@Profile("api")
@Service
public class EolSyncService {

    private static final Logger log = LoggerFactory.getLogger(EolSyncService.class);

    private final IntelFetcher fetcher;
    private final EolCycleRepository repository;
    private final IntelProperties props;
    private final TransactionTemplate tx;

    public EolSyncService(IntelFetcher fetcher, EolCycleRepository repository, IntelProperties props,
                          PlatformTransactionManager txManager) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Fetches and upserts every configured product; returns the total number of cycle rows upserted. */
    public int refresh() {
        int total = 0;
        for (String product : props.getEolProducts()) {
            try {
                List<EolRow> rows = fetcher.fetchEol(props.getEolBaseUrl(), product);
                if (rows.isEmpty()) {
                    // Never treat "no rows" as "wipe this product"; keep whatever is already cached.
                    log.warn("EOL sync: product {} returned no cycles; keeping existing rows", product);
                    continue;
                }
                total += upsert(rows);
                log.info("EOL sync: product {} upserted {} cycle(s)", product, rows.size());
            } catch (RuntimeException e) {
                // Fail loud, keep existing rows for this product, carry on with the rest (BLOCKER 6).
                log.error("EOL sync failed for product {}; keeping its existing rows", product, e);
            }
        }
        return total;
    }

    /** Upserts one product's cycle rows in a single transaction (merge into existing rows; never delete). */
    int upsert(List<EolRow> rows) {
        return tx.execute(status -> {
            for (EolRow r : rows) {
                repository.findByProductAndCycle(r.product(), r.cycle())
                        .ifPresentOrElse(
                                existing -> existing.update(r.eol(), r.eolFrom(), r.latest(), r.latestDate(),
                                        r.releaseDate(), r.lts(), r.maintained()),
                                () -> repository.save(new EolCycle(r.product(), r.cycle(), r.eol(), r.eolFrom(),
                                        r.latest(), r.latestDate(), r.releaseDate(), r.lts(), r.maintained())));
            }
            return rows.size();
        });
    }
}
