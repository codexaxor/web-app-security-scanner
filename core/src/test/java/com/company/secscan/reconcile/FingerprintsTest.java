package com.company.secscan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FingerprintsTest {

    @Test
    void normalizePathUsesForwardSlashesAndStripsLeadingDotSlashKeepingCase() {
        assertThat(Fingerprints.normalizePath("src\\Main\\App.java")).isEqualTo("src/Main/App.java");
        assertThat(Fingerprints.normalizePath("./config/app.properties")).isEqualTo("config/app.properties");
        assertThat(Fingerprints.normalizePath("././a/b")).isEqualTo("a/b");
        // Case is preserved; a mid-path "./" is not touched, only leading segments.
        assertThat(Fingerprints.normalizePath("Config/App.YML")).isEqualTo("Config/App.YML");
        assertThat(Fingerprints.normalizePath("a/./b")).isEqualTo("a/./b");
        assertThat(Fingerprints.normalizePath(null)).isNull();
    }

    @Test
    void sameSecretAtSamePathAndKeyYieldsTheSameFingerprintRegardlessOfPathForm() {
        String a = Fingerprints.secret("aws-access-token", "config/app.properties", "HMAC123");
        String b = Fingerprints.secret("aws-access-token", ".\\config\\app.properties", "HMAC123");
        assertThat(a).isEqualTo(b);
        // 64 lowercase hex chars (SHA-256).
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintChangesWithAnyField() {
        String base = Fingerprints.secret("aws-access-token", "config/app.properties", "HMAC123");
        assertThat(Fingerprints.secret("other-rule", "config/app.properties", "HMAC123")).isNotEqualTo(base);
        assertThat(Fingerprints.secret("aws-access-token", "config/other.properties", "HMAC123"))
                .isNotEqualTo(base);
        assertThat(Fingerprints.secret("aws-access-token", "config/app.properties", "HMAC999"))
                .isNotEqualTo(base);
    }

    @Test
    void eachTypeUsesItsOwnFieldSet() {
        assertThat(Fingerprints.sca("org.springframework:spring-web", "CVE-2024-1", "pom.xml"))
                .hasSize(64);
        assertThat(Fingerprints.sast("java.sqli", "src/A.java", "snip"))
                .hasSize(64);
        assertThat(Fingerprints.iac("ckv.docker.1", "Dockerfile", "web"))
                .hasSize(64);
        assertThat(Fingerprints.eol("spring-boot", "2.7", "pom.xml"))
                .hasSize(64);
        // Per domain rule 2 the fingerprint hashes only the type's fields (type is a separate column), so two
        // types given identical field values hash the same; distinctness in practice comes from the fields
        // (rule ids, hash shapes) differing. Within a type, distinct inputs differ:
        assertThat(Fingerprints.sca("pkg-a", "CVE-1", "pom.xml"))
                .isNotEqualTo(Fingerprints.sca("pkg-b", "CVE-1", "pom.xml"));
    }

    @Test
    void groupKeyPrefixesTheTypeSoDiscriminatorsDoNotCollideAcrossTypes() {
        assertThat(Fingerprints.groupKey("secret", "aws-access-token")).isEqualTo("secret|aws-access-token");
        assertThat(Fingerprints.groupKey("sast", "rule-x"))
                .isNotEqualTo(Fingerprints.groupKey("iac", "rule-x"));
    }
}
