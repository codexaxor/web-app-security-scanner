package com.company.secscan.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.taxonomy.Level2Taxonomy.Category;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit-proves the level-2 taxonomy derivation per type (D-083/D-074) and the confident OWASP 2025 mapping. */
class Level2TaxonomyTest {

    private final Level2Taxonomy tax = Level2Taxonomy.getInstance();

    private Category sca(String manifest) {
        return tax.of(RawFinding.TYPE_SCA, manifest, null, null, null);
    }

    @Test
    void scaEcosystemFromManifestName() {
        assertThat(sca("pom.xml").level2()).isEqualTo("Maven");
        assertThat(sca("app/build.gradle.kts").level2()).isEqualTo("Gradle");
        assertThat(sca("ui/package-lock.json").level2()).isEqualTo("npm");
        assertThat(sca("src/Api.csproj").level2()).isEqualTo("NuGet");
        assertThat(sca("packages.lock.json").level2()).isEqualTo("NuGet");
        assertThat(sca("requirements.txt").level2()).isEqualTo("PyPI");
        assertThat(sca("go.mod").level2()).isEqualTo("Go");
        assertThat(sca("unknown.lock").level2()).isEqualTo("Dependency");
        // SCA always maps to the supply-chain OWASP category (A03:2025).
        assertThat(sca("pom.xml").owasp()).isEqualTo("Software Supply Chain Failures");
    }

    @Test
    void secretKindFromRuleId() {
        assertThat(tax.of(RawFinding.TYPE_SECRET, "k.pem", "private-key", null, null).level2())
                .isEqualTo("Private key");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "aws-access-token", null, null).level2())
                .isEqualTo("Cloud credential");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "github-pat", null, null).level2()).isEqualTo("API token");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "db-password", null, null).level2()).isEqualTo("Password");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "mystery", null, null).level2()).isEqualTo("Secret");
        // No confident single OWASP category for a hardcoded secret → null (never guessed).
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "private-key", null, null).owasp()).isNull();
    }

    @Test
    void sastWeaknessClassAndOwaspFromFirstCwe() {
        Category csrf = tax.of(RawFinding.TYPE_SAST, "Sec.java", "rule", List.of("CWE-352"), null);
        assertThat(csrf.level2()).isEqualTo("Cross-site request forgery");
        assertThat(csrf.owasp()).isEqualTo("Broken Access Control"); // CSRF folded into BAC in 2025
        Category sqli = tax.of(RawFinding.TYPE_SAST, "Dao.java", "rule", List.of("CWE-89", "CWE-20"), null);
        assertThat(sqli.level2()).isEqualTo("SQL injection");
        assertThat(sqli.owasp()).isEqualTo("Injection");
        // Unmapped / empty CWE → fallback class, null OWASP.
        Category none = tax.of(RawFinding.TYPE_SAST, "X.java", "rule", List.of(), null);
        assertThat(none.level2()).isEqualTo("Code weakness");
        assertThat(none.owasp()).isNull();
        assertThat(tax.of(RawFinding.TYPE_SAST, "X.java", "rule", List.of("CWE-99999"), null).owasp()).isNull();
    }

    @Test
    void sastFirstCweExtractedFromTheRealOpengrepCweString() {
        // B-3: the real Opengrep sample (app/src/test/resources/samples/opengrep/real-csrf-duplicate.json) reports
        // a CWE as the full "CWE-<n>: <name>" string, not a bare id. firstCwe must extract the leading CWE id so
        // the mapping still resolves. Using the EXACT string from that sample.
        Category csrf = tax.of(RawFinding.TYPE_SAST, "Sec.java", "rule",
                List.of("CWE-352: Cross-Site Request Forgery (CSRF)"), null);
        assertThat(csrf.level2()).isEqualTo("Cross-site request forgery");
        assertThat(csrf.owasp()).isEqualTo("Broken Access Control");
        // Case-insensitive and whitespace-tolerant, trailing name ignored.
        Category sqli = tax.of(RawFinding.TYPE_SAST, "Dao.java", "rule",
                List.of("  cwe-89: SQL Injection"), null);
        assertThat(sqli.level2()).isEqualTo("SQL injection");
        assertThat(sqli.owasp()).isEqualTo("Injection");
        // A string with no CWE id → fallback class, null OWASP.
        Category none = tax.of(RawFinding.TYPE_SAST, "X.java", "rule", List.of("no cwe here"), null);
        assertThat(none.level2()).isEqualTo("Code weakness");
        assertThat(none.owasp()).isNull();
    }

    @Test
    void secretKindMatchesOnTokenBoundariesNotSubstrings() {
        // N-3: "monkey" must not read as a "key", "passwordless" must not read as a "password".
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "monkey-config", null, null).level2()).isEqualTo("Secret");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "passwordless-login", null, null).level2())
                .isEqualTo("Secret");
        // Real kebab/snake rule ids still classify on their tokens.
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "generic-api-key", null, null).level2())
                .isEqualTo("API token");
        assertThat(tax.of(RawFinding.TYPE_SECRET, "a", "ssh-private-key", null, null).level2())
                .isEqualTo("Private key");
    }

    @Test
    void iacPlatformHeuristicAndMisconfigOwasp() {
        assertThat(tax.of(RawFinding.TYPE_IAC, "docker/Dockerfile", "CKV_DOCKER_2", null, null).level2())
                .isEqualTo("Dockerfile");
        assertThat(tax.of(RawFinding.TYPE_IAC, "infra/main.tf", "CKV_AWS_24", null, null).level2())
                .isEqualTo("Terraform");
        assertThat(tax.of(RawFinding.TYPE_IAC, "k8s/deploy.yaml", "CKV_K8S_21", null, null).level2())
                .isEqualTo("Kubernetes");
        assertThat(tax.of(RawFinding.TYPE_IAC, "stack.yaml", "CKV_AWS_99", null, null).level2())
                .isEqualTo("Infrastructure"); // coarse fallback (D-draft-1)
        assertThat(tax.of(RawFinding.TYPE_IAC, "infra/main.tf", "CKV_AWS_24", null, null).owasp())
                .isEqualTo("Security Misconfiguration");
    }

    @Test
    void eolRuntimeAndSupplyChainOwasp() {
        assertThat(tax.of(RawFinding.TYPE_EOL, "pom.xml", null, null, "spring-boot").level2())
                .isEqualTo("Spring Boot");
        assertThat(tax.of(RawFinding.TYPE_EOL, "Dockerfile", null, null, "nodejs").level2()).isEqualTo("Node.js");
        assertThat(tax.of(RawFinding.TYPE_EOL, "x", null, null, "keycloak").level2()).isEqualTo("Keycloak");
        assertThat(tax.of(RawFinding.TYPE_EOL, "x", null, null, "whatever").level2()).isEqualTo("Runtime");
        assertThat(tax.of(RawFinding.TYPE_EOL, "x", null, null, "dotnet").owasp())
                .isEqualTo("Software Supply Chain Failures");
    }

    @Test
    void level2IsNeverNullForAnyType() {
        for (String type : List.of(RawFinding.TYPE_SCA, RawFinding.TYPE_SAST, RawFinding.TYPE_SECRET,
                RawFinding.TYPE_IAC, RawFinding.TYPE_EOL)) {
            assertThat(tax.of(type, null, null, null, null).level2()).isNotBlank();
        }
    }

    @Test
    void resourcesLoadWithHeadroom() {
        assertThat(tax.cweClassSize()).isGreaterThan(30);
        assertThat(tax.cweOwaspSize()).isGreaterThan(30);
    }
}
