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
    void m6FingerprintsAreLineFreeAndPathFormInsensitive() {
        // The SAST/IaC/EOL fingerprint functions take NO line number, so identity cannot depend on it (domain
        // rule 2 — the FROZEN M6 fingerprints). Path form is normalized so a backslash / ./ prefix never churns.
        assertThat(Fingerprints.sast("rule", "src/A.java", "snip"))
                .isEqualTo(Fingerprints.sast("rule", ".\\src\\A.java", "snip"));
        assertThat(Fingerprints.iac("ckv.1", "k8s/pod.yaml", "Pod.default.demo"))
                .isEqualTo(Fingerprints.iac("ckv.1", "./k8s/pod.yaml", "Pod.default.demo"));
        assertThat(Fingerprints.eol("spring-boot", "2.1", "pom.xml"))
                .isEqualTo(Fingerprints.eol("spring-boot", "2.1", "./pom.xml"));
        // Every field still changes the identity.
        assertThat(Fingerprints.sast("rule", "src/A.java", "s1"))
                .isNotEqualTo(Fingerprints.sast("rule", "src/A.java", "s2"));
        assertThat(Fingerprints.iac("ckv.1", "k8s/pod.yaml", "res1"))
                .isNotEqualTo(Fingerprints.iac("ckv.1", "k8s/pod.yaml", "res2"));
        assertThat(Fingerprints.eol("spring-boot", "2.1", "pom.xml"))
                .isNotEqualTo(Fingerprints.eol("spring-boot", "2.2", "pom.xml"));
    }

    @Test
    void groupKeyPrefixesTheTypeSoDiscriminatorsDoNotCollideAcrossTypes() {
        assertThat(Fingerprints.groupKey("secret", "aws-access-token")).isEqualTo("secret|aws-access-token");
        assertThat(Fingerprints.groupKey("sast", "rule-x"))
                .isNotEqualTo(Fingerprints.groupKey("iac", "rule-x"));
    }
}
