package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scoring.ScoreLine;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** The read-only issue API: filters, pagination, 404, and that a secret's hash/key id never leave the server. */
class IssueApiIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    IssueService service;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    ObjectMapper objectMapper;

    private Repository seedRepository(long projectId, String path) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + projectId, projectId, "ct",
                "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, path, "main",
                List.of("Java")));
    }

    private IssueGroup seedGroup(UUID repoId, String type, String groupKey, String title) {
        return groups.save(new IssueGroup(WORKSPACE, repoId, type, groupKey, title, null));
    }

    private SubIssue seedSecretSub(IssueGroup group, UUID repoId, String fingerprint) {
        SubIssue s = new SubIssue(WORKSPACE, group.getId(), repoId, "secret", "secret", fingerprint, null);
        s.setRuleId("aws-access-token");
        s.setFilePath("config/app.properties");
        s.setSecretHash("DEADBEEFkeyedHMAChashValue");
        s.setSecretPreview("AKIA");
        s.setKeyId("ab12cd34");
        return subIssues.save(s);
    }

    @Test
    void listGroupsFiltersByRepositoryTypeAndStatus() {
        Repository repoA = seedRepository(401L, "svc/a");
        Repository repoB = seedRepository(402L, "svc/b");
        seedGroup(repoA.getId(), "secret", "secret|aws-access-token", "Exposed aws-access-token");
        IssueGroup solvedGroup = seedGroup(repoA.getId(), "sast", "sast|rule-x", "Rule X");
        solvedGroup.setStatus(IssueGroup.STATUS_SOLVED);
        groups.save(solvedGroup);
        seedGroup(repoB.getId(), "secret", "secret|aws-access-token", "Exposed aws-access-token");

        // Filter by repository.
        assertThat(service.listGroups(repoA.getId(), null, null, 0, 50).content()).hasSize(2);
        // Filter by type.
        assertThat(service.listGroups(repoA.getId(), "secret", null, 0, 50).content()).hasSize(1);
        // Filter by status.
        assertThat(service.listGroups(repoA.getId(), null, "solved", 0, 50).content()).hasSize(1);
        // No filter: both repos' groups (3 total).
        assertThat(service.listGroups(null, null, null, 0, 50).totalElements()).isEqualTo(3);
    }

    @Test
    void listGroupsPaginatesServerSide() {
        Repository repo = seedRepository(403L, "svc/page");
        for (int i = 0; i < 5; i++) {
            seedGroup(repo.getId(), "secret", "secret|rule-" + i, "Exposed rule-" + i);
        }
        var firstPage = service.listGroups(repo.getId(), null, null, 0, 2);
        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
    }

    @Test
    void listSubIssuesReturns404ForAnUnknownGroup() {
        assertThatThrownBy(() -> service.listSubIssues(UUID.randomUUID(), 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such issue group");
    }

    @Test
    void subIssueApiExposesTheScoringFieldsIncludingKevAndEpssAttribution() throws Exception {
        Repository repo = seedRepository(405L, "svc/scored");
        IssueGroup group = seedGroup(repo.getId(), "sca", "sca|Maven/org.apache.commons:commons-text",
                "org.apache.commons:commons-text");
        SubIssue s = new SubIssue(WORKSPACE, group.getId(), repo.getId(), "sca", "sca", "fp-sca-scored", null);
        s.setVulnId("CVE-2022-42889");
        s.setPackageName("org.apache.commons:commons-text");
        s.setInstalledVersion("1.9");
        s.setFixedVersion("1.10.0");
        s.setToolSeverity("9.8");
        s.setCvss("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        s.setSeverityLabel("CRITICAL");
        s.setCwe(List.of("CWE-94"));
        s.setCveAliases(List.of("CVE-2022-42889"));
        s.setScore(100);
        s.setInKev(true);
        s.setEpss(new BigDecimal("0.94412"));
        s.setScoreBreakdown(List.of(new ScoreLine("base_cvss", "CVSS base 9.8 × 10", 98),
                new ScoreLine("on_kev", "Listed in the CISA KEV catalog", 5),
                new ScoreLine("clamp", "Clamped to the 1–100 range (was 103)", -3)));
        subIssues.save(s);

        SubIssueView v = service.listSubIssues(group.getId(), 0, 50).content().get(0);
        assertThat(v.score()).isEqualTo(100);
        assertThat(v.band()).isEqualTo("critical");
        assertThat(v.inKev()).isTrue();
        assertThat(v.cvss()).startsWith("CVSS:3.1/");
        assertThat(v.cwe()).containsExactly("CWE-94");
        assertThat(v.cwePhrase()).isEqualTo("Code injection can lead to arbitrary code execution");
        assertThat(v.epss()).isEqualByComparingTo("0.94412");
        assertThat(v.epssAttribution()).contains("FIRST.org");
        assertThat(v.scoreBreakdown()).anyMatch(l -> l.code().equals("on_kev") && l.delta() == 5);

        String json = objectMapper.writeValueAsString(v);
        assertThat(json).contains("\"score\":100").contains("\"inKev\":true").contains("on_kev")
                .contains("FIRST.org");
    }

    @Test
    void subIssueApiExposesThePreviewButNeverTheHashOrKeyId() throws Exception {
        Repository repo = seedRepository(404L, "svc/secret");
        IssueGroup group = seedGroup(repo.getId(), "secret", "secret|aws-access-token", "Exposed aws-access-token");
        seedSecretSub(group, repo.getId(), "fp-secret-1");

        List<SubIssueView> views = service.listSubIssues(group.getId(), 0, 50).content();
        assertThat(views).singleElement().satisfies(v -> assertThat(v.secretPreview()).isEqualTo("AKIA"));

        // The serialized JSON must not carry the stored HMAC hash or the key id (plan addition 6).
        String json = objectMapper.writeValueAsString(views.get(0));
        assertThat(json)
                .contains("AKIA")
                .doesNotContain("DEADBEEFkeyedHMAChashValue")
                .doesNotContain("ab12cd34")
                .doesNotContain("secretHash")
                .doesNotContain("keyId")
                .doesNotContain("fingerprint");
    }
}
