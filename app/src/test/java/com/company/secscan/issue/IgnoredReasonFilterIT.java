package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.issue.api.IgnoredItemView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.8 item 9: the Ignored view can be filtered to one reason code (the Workspace-settings LOW_EPSS link) and
 * the count stays EXACT — totalElements is the server-side SQL count of the filtered rows, never a client guess.
 */
class IgnoredReasonFilterIT extends TriageSeedSupport {

    @Autowired
    TriageService triage;
    @Autowired
    ReconcileService reconcile;

    @Test
    void filtersTheIgnoredViewByReasonCodeWithExactServerSideCounts() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        seedSca(scan, repo, "CVE-2021-44228", "org.apache.logging.log4j:log4j-core", "pom.xml");
        reconcile.reconcile(scan, WORKSPACE);

        SubIssue low = byVuln(repo, "CVE-2022-42889");
        low.ignore("LOW_EPSS", null);
        subIssues.save(low);
        SubIssue dev = byVuln(repo, "CVE-2021-44228");
        dev.ignore("DEV_DEPENDENCY", null);
        subIssues.save(dev);

        var all = triage.listIgnored(0, 50, null);
        var lowEpss = triage.listIgnored(0, 50, "LOW_EPSS");

        assertThat(all.totalElements()).isEqualTo(2);
        assertThat(lowEpss.totalElements()).isEqualTo(1); // exact server-side count
        assertThat(lowEpss.content()).singleElement()
                .satisfies((IgnoredItemView v) -> assertThat(v.reasonCode()).isEqualTo("LOW_EPSS"));
    }
}
