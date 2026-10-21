package com.company.secscan.issue;

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
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared seeding for the M7 triage ITs (a repo, a completed+reconciled scan, SCA/SAST findings). */
abstract class TriageSeedSupport extends AbstractScanWorkerIT {

    static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired SubIssueRepository subIssues;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;

    UUID seedRepo() {
        long seq = SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 1000L + seq, "svc/app-" + seq, "main",
                List.of("Java"))).getId();
    }

    /** A completed scan with OK, closes-findings SCA + SAST tasks (both authoritative to solve). */
    UUID seedScan(UUID repoId) {
        return seedScan(repoId, true, true);
    }

    UUID seedScan(UUID repoId, boolean scaCloses, boolean sastCloses) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        addTask(scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, scaCloses);
        addTask(scan.getId(), ScanTask.SCANNER_SAST, sastCloses ? ScanTask.STATUS_OK : ScanTask.STATUS_FAILED,
                sastCloses);
        return scan.getId();
    }

    private void addTask(UUID scanId, String scanner, String status, boolean closes) {
        ScanTask task = new ScanTask(WORKSPACE, scanId, scanner, status, 0, 5L, null);
        task.setClosesFindings(closes);
        tasks.save(task);
    }

    void seedSca(UUID scanId, UUID repoId, String cve, String pkg, String manifest) {
        ScanFinding f = ScanFinding.sca(WORKSPACE, scanId, repoId, cve, pkg, "Maven", "1.9", "1.10.0", manifest,
                "9.8");
        f.setOsvEnrichment("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "CRITICAL", List.of("CWE-94"),
                List.of(cve), "RCE");
        scanFindings.save(f);
    }

    void seedSast(UUID scanId, UUID repoId, String ruleId, String filePath, String snippetHash) {
        scanFindings.save(ScanFinding.sast(WORKSPACE, scanId, repoId, ruleId, filePath, 10, snippetHash, "HIGH",
                "ERROR", List.of("CWE-352")));
    }

    SubIssue byFile(UUID repo, String filePath) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> filePath.equals(s.getFilePath())).findFirst().orElseThrow();
    }

    SubIssue byVuln(UUID repo, String vulnId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> vulnId.equals(s.getVulnId())).findFirst().orElseThrow();
    }
}
