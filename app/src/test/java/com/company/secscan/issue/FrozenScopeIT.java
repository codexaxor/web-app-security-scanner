package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanWarning;
import com.company.secscan.scan.ScanWarningRepository;
import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerTaskResult;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.scan.worker.ScanStateService;
import com.company.secscan.triage.ReasonCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SF2/SF3/SF4: when the scope inventory fails (globally, or because an unreadable config also freezes dev/test),
 * prior TEST_SCOPE ignores STAY ignored with no event, and the worker writes a visible SCOPE_INVENTORY_FAILED
 * scan warning. A frozen (unreadable) config writes SECSCAN_CONFIG_INVALID.
 */
class FrozenScopeIT extends TriageSeedSupport {

    @Autowired ReconcileService reconcile;
    @Autowired ScanStateService scanState;
    @Autowired ScanWarningRepository warnings;
    @Autowired TriageEventRepository events;

    @Test
    void priorTestScopeIgnoreStaysWhenScopeInventoryFailedAndNoEvent() {
        UUID repo = seedRepo();
        List<DeclaredScope> scopes = List.of(new DeclaredScope("pom.xml", "junit:junit", "test"));

        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, "CVE-FS-1", "junit:junit", "pom.xml");
        reconcile.reconcile(scan1, WORKSPACE, new TriageContext(SecscanConfig.absent(), scopes, false));
        assertThat(byVuln(repo, "CVE-FS-1").getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        long before = events.count();

        // Scan 2: the scope inventory FAILED (empty scopes, scopeInventoryFailed=true) → freeze, keep ignored.
        UUID scan2 = seedScan(repo);
        seedSca(scan2, repo, "CVE-FS-1", "junit:junit", "pom.xml");
        reconcile.reconcile(scan2, WORKSPACE,
                new TriageContext(SecscanConfig.absent(), List.of(), false, false, true));

        SubIssue after = byVuln(repo, "CVE-FS-1");
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(after.getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(events.count()).as("a frozen scope writes no new event").isEqualTo(before);
    }

    @Test
    void scopeInventoryFailureWritesAVisibleScanWarning() {
        UUID repo = seedRepo();
        Scan scan = scans.save(running(repo));
        ParsedScan parsed = empty(repo, SecscanConfig.absent(), "error"); // scopeInventoryStatus = error
        scanState.recordSuccess(WORKSPACE, scan.getId(), UUID.randomUUID(), repo, parsed, null,
                Scan.STATUS_COMPLETED);
        assertThat(warnings.findByScanId(scan.getId())).extracting(ScanWarning::getCode)
                .contains("SCOPE_INVENTORY_FAILED");
    }

    @Test
    void invalidConfigWritesAVisibleScanWarning() {
        UUID repo = seedRepo();
        Scan scan = scans.save(running(repo));
        SecscanConfig frozen = new SecscanConfig(true, List.of(), Map.of(), false, "error");
        ParsedScan parsed = empty(repo, frozen, ParsedScan.SCOPE_STATUS_OK);
        scanState.recordSuccess(WORKSPACE, scan.getId(), UUID.randomUUID(), repo, parsed, null,
                Scan.STATUS_COMPLETED);
        assertThat(warnings.findByScanId(scan.getId())).extracting(ScanWarning::getCode)
                .contains("SECSCAN_CONFIG_INVALID");
    }

    private Scan running(UUID repo) {
        Scan s = Scan.manual(WORKSPACE, repo);
        s.markRunning();
        return s;
    }

    private ParsedScan empty(UUID repo, SecscanConfig config, String scopeStatus) {
        ScannerTaskResult sca = new ScannerTaskResult("sca", "ok", 0, 5L, "osv.json", false);
        return new ParsedScan(false, "commit-fs", Map.of(), List.of(sca), List.of(), List.of(), config,
                List.of(), scopeStatus, List.of(), List.of());
    }
}
