package com.company.secscan.intel;

import com.company.secscan.intel.IntelRows.KevFeed;
import com.company.secscan.intel.IntelRows.KevRow;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the CISA KEV catalog into {@code kev_entry} (D-046). The catalog is a full snapshot (~1.3k rows), so
 * this is a full refresh inside one transaction: clear, then insert the fetched rows. A global cache (D-050),
 * so there is no workspace scope. Returns the row count loaded.
 */
@Profile("api")
@Service
public class KevSyncService {

    private static final Logger log = LoggerFactory.getLogger(KevSyncService.class);

    private final IntelFetcher fetcher;
    private final KevEntryRepository repository;
    private final TransactionTemplate tx;

    public KevSyncService(IntelFetcher fetcher, KevEntryRepository repository,
                          PlatformTransactionManager txManager) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Fetches the live feed and loads it. */
    public int refresh() {
        KevFeed feed = fetcher.fetchKev();
        int n = load(feed.rows());
        log.info("KEV sync loaded {} entries (catalog {})", n, feed.catalogVersion());
        return n;
    }

    /**
     * Full-refresh load of already-fetched rows (used by tests and {@link #refresh()}). Wrapped in an explicit
     * transaction — not {@code @Transactional}, which {@link #refresh()}'s self-invocation would bypass — so the
     * clear-then-insert is atomic (no window where the catalog is empty).
     */
    public int load(List<KevRow> rows) {
        List<KevEntry> entries = new ArrayList<>(rows.size());
        for (KevRow r : rows) {
            entries.add(new KevEntry(r.cve(), r.vulnName(), r.dateAdded()));
        }
        return tx.execute(status -> {
            repository.deleteAllInBatch();
            repository.saveAll(entries);
            return entries.size();
        });
    }
}
