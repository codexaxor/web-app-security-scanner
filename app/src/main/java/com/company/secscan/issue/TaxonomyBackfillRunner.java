package com.company.secscan.issue;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the level-2 taxonomy backfill ({@link TaxonomyBackfiller}) once at api startup, after Flyway has applied
 * V21 (D-083, approved plan item 2). Idempotent, DB-only; the worker profile does not run it.
 */
@Component
@Profile("api")
@Order(0)
public class TaxonomyBackfillRunner implements ApplicationRunner {

    private final TaxonomyBackfiller backfiller;

    public TaxonomyBackfillRunner(TaxonomyBackfiller backfiller) {
        this.backfiller = backfiller;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfiller.backfill();
    }
}
