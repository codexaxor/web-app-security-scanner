package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S4 gate (D-059): a declared dev/test-scoped vulnerable dependency is auto-ignored DEV_DEPENDENCY/TEST_SCOPE
 * when the effective include-dev-deps is false, shown when it is true, and an UNCLASSIFIABLE dependency (no
 * declared scope) always stays visible. Seed-based; the in-container inventory is proven by scope_inventory_test.py.
 */
class DevTestScopeIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired SubIssueRepository subIssues;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;

    // (manifest, coordinate) -> declared scope, as our inventory would emit (review S4: keyed per manifest).
    private static final List<DeclaredScope> SCOPES = List.of(
            new DeclaredScope("pom.xml", "junit:junit", "test"),         // Maven test scope
            new DeclaredScope("package.json", "mocha", "dev"));          // npm devDependency

    @Test
    void testScopedDepIsAutoIgnoredWhenDevDepsOff() {
        UUID repo = seedRepo(false);
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2020-0001", "junit:junit", "Maven", "pom.xml");
        seedSca(scan, repo, "CVE-2020-0002", "mocha", "npm", "package.json");
        seedSca(scan, repo, "CVE-2020-0003", "org.apache.commons:commons-text", "Maven", "pom.xml"); // unknown scope

        reconcile.reconcile(scan, WORKSPACE, new TriageContext(SecscanConfig.absent(), SCOPES, false));

        assertThat(byPkg(repo, "junit:junit").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(byPkg(repo, "junit:junit").getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(byPkg(repo, "mocha").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(byPkg(repo, "mocha").getIgnoreReason()).isEqualTo(ReasonCode.DEV_DEPENDENCY.code());
        // Unclassifiable dependency stays visible.
        assertThat(byPkg(repo, "org.apache.commons:commons-text").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void devTestScopedDepsAreShownWhenDevDepsOn() {
        UUID repo = seedRepo(true); // repository.include_dev_deps = true -> effective true
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2020-0001", "junit:junit", "Maven", "pom.xml");
        seedSca(scan, repo, "CVE-2020-0002", "mocha", "npm", "package.json");

        reconcile.reconcile(scan, WORKSPACE, new TriageContext(SecscanConfig.absent(), SCOPES, true));

        assertThat(byPkg(repo, "junit:junit").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(byPkg(repo, "mocha").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void secscanYmlIncludeDevDependenciesOverridesTheRepoSettingToShow() {
        // repository.include_dev_deps=false but .secscan.yml include_dev_dependencies=true -> effective TRUE
        // (the file may only INCREASE visibility, review change #3). The worker computes this OR in
        // ScanStateService; here the context carries the already-effective flag.
        UUID repo = seedRepo(false);
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2020-0001", "junit:junit", "Maven", "pom.xml");

        reconcile.reconcile(scan, WORKSPACE, new TriageContext(
                new SecscanConfig(true, List.of(), Map.of(), true), SCOPES, true));

        assertThat(byPkg(repo, "junit:junit").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void scopeIsKeyedPerManifestNotFlat() {
        // Review S4: junit:junit is test-scoped only in moduleA/pom.xml. A finding for the SAME coordinate in a
        // DIFFERENT manifest (moduleB/pom.xml, where it is a compile dep) must stay OPEN — the scope is keyed by
        // (manifest, coordinate), never a flat coordinate map. com.x:prod in B has no scope entry -> OPEN too.
        UUID repo = seedRepo(false);
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2020-1001", "junit:junit", "Maven", "moduleA/pom.xml"); // test here -> ignored
        seedSca(scan, repo, "CVE-2020-1002", "junit:junit", "Maven", "moduleB/pom.xml"); // not declared here -> open
        seedSca(scan, repo, "CVE-2020-1003", "com.x:prod", "Maven", "moduleB/pom.xml");  // prod -> open

        List<DeclaredScope> scopes = List.of(new DeclaredScope("moduleA/pom.xml", "junit:junit", "test"));
        reconcile.reconcile(scan, WORKSPACE, new TriageContext(SecscanConfig.absent(), scopes, false));

        SubIssue inA = byVuln(repo, "CVE-2020-1001");
        assertThat(inA.getStatus()).as("test-scoped in its own manifest").isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(inA.getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(byVuln(repo, "CVE-2020-1002").getStatus())
                .as("same coordinate, a different manifest -> not auto-ignored").isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(byVuln(repo, "CVE-2020-1003").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    private SubIssue byPkg(UUID repo, String pkg) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> pkg.equals(s.getPackageName())).findFirst().orElseThrow();
    }

    private SubIssue byVuln(UUID repo, String vulnId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> vulnId.equals(s.getVulnId())).findFirst().orElseThrow();
    }

    private UUID seedRepo(boolean includeDevDeps) {
        long seq = SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        Repository repo = new Repository(WORKSPACE, conn.getId(), 1000L + seq, "svc/app-" + seq, "main",
                List.of("Java"));
        repo.setIncludeDevDeps(includeDevDeps);
        return repositories.save(repo).getId();
    }

    private UUID seedScan(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 5L, null);
        task.setClosesFindings(true);
        tasks.save(task);
        return scan.getId();
    }

    private void seedSca(UUID scanId, UUID repoId, String cve, String pkg, String ecosystem, String manifest) {
        ScanFinding f = ScanFinding.sca(WORKSPACE, scanId, repoId, cve, pkg, ecosystem, "1.0.0", "1.0.1", manifest,
                "7.0");
        f.setOsvEnrichment(null, "HIGH", List.of("CWE-20"), List.of(cve), "Example");
        scanFindings.save(f);
    }
}
