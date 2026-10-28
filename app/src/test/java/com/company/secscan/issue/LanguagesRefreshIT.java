package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.scan.Scan;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerTaskResult;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.scan.worker.ScanStateService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.5 (D-081): a successful scan refreshes {@code repository.languages} from the content-detected list, and an
 * empty result (detection failed or found none) never clears the stored languages.
 */
class LanguagesRefreshIT extends TriageSeedSupport {

    @Autowired ScanStateService scanState;

    private ParsedScan parsed(List<String> languages) {
        ScannerTaskResult sca = new ScannerTaskResult("sca", "ok", 0, 5L, "osv.json", false);
        return new ParsedScan(false, "commit-lang", Map.of(), List.of(sca), List.of(), List.of(),
                SecscanConfig.absent(), List.of(), ParsedScan.SCOPE_STATUS_OK, List.of(), languages);
    }

    private void recordScan(UUID repo, List<String> languages) {
        Scan scan = Scan.manual(WORKSPACE, repo);
        scan.markRunning();
        scan = scans.save(scan);
        scanState.recordSuccess(WORKSPACE, scan.getId(), UUID.randomUUID(), repo, parsed(languages), null,
                Scan.STATUS_COMPLETED);
    }

    @Test
    void successfulScanRefreshesLanguages() {
        UUID repo = seedRepo(); // seeded with ["Java"]
        recordScan(repo, List.of("Kotlin", "Go"));
        assertThat(repositories.findByIdAndWorkspaceId(repo, WORKSPACE).orElseThrow().getLanguages())
                .containsExactly("Kotlin", "Go");
    }

    @Test
    void emptyDetectionDoesNotClearExistingLanguages() {
        UUID repo = seedRepo();
        recordScan(repo, List.of("Kotlin"));
        recordScan(repo, List.of()); // detection failed/none → must NOT clear
        assertThat(repositories.findByIdAndWorkspaceId(repo, WORKSPACE).orElseThrow().getLanguages())
                .containsExactly("Kotlin");
    }
}
