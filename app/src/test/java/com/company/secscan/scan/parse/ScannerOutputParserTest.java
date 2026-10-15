package com.company.secscan.scan.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.finding.RawFinding;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the saved scanner samples and proves the sanitized contract: a secret finding surfaces only its
 * SHA-256 hash and 4-char preview, and a (hypothetically) leaking {@code secrets.json} can never bring a raw
 * value through the parser.
 */
class ScannerOutputParserTest {

    private final ScannerOutputParser parser = new ScannerOutputParser();

    @Test
    void parsesSecretFindingFromSamples(@TempDir Path outDir) throws Exception {
        copyResource("/samples/scanner/result.json", outDir.resolve("result.json"));
        copyResource("/samples/gitleaks/secrets.json", outDir.resolve("secrets.json"));

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.cloneFailed()).isFalse();
        assertThat(parsed.commit()).isEqualTo("ab49a433ddc6c8b5e243ef26187b7eb30583a4c5");
        assertThat(parsed.toolVersions()).containsEntry("gitleaks", "8.21.2");
        assertThat(parsed.tasks()).singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("secret");
                    assertThat(t.succeeded()).isTrue();
                    assertThat(t.exitCode()).isEqualTo(2);
                });
        assertThat(parsed.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(RawFinding.TYPE_SECRET);
            assertThat(f.ruleId()).isEqualTo("aws-access-token");
            assertThat(f.filePath()).isEqualTo("config/application.properties");
            assertThat(f.line()).isEqualTo(5);
            assertThat(f.secretHash())
                    .isEqualTo("91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d");
            assertThat(f.secretPreview()).isEqualTo("AKIA");
            assertThat(f.installedVersion()).isNull();
        });
    }

    @Test
    void aRawValueInSecretsJsonNeverSurfaces(@TempDir Path outDir) throws Exception {
        copyResource("/samples/scanner/result.json", outDir.resolve("result.json"));
        // A deliberately malformed findings file that smuggles raw fields the parser must never read.
        String raw = "AKIA3K7HB9QO2PX5TL4D";
        Files.writeString(outDir.resolve("secrets.json"), "[{"
                + "\"ruleId\":\"aws-access-token\",\"file\":\"config/application.properties\",\"startLine\":5,"
                + "\"secretHash\":\"hash\",\"preview\":\"AKIA\","
                + "\"Secret\":\"" + raw + "\",\"Match\":\"key=" + raw + "\",\"Line\":\"full line " + raw + "\""
                + "}]");

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.findings()).singleElement()
                .satisfies(f -> assertThat(f.toString()).doesNotContain(raw));
    }

    @Test
    void aLeakyPreviewIsClampedToTheMask(@TempDir Path outDir) throws Exception {
        copyResource("/samples/scanner/result.json", outDir.resolve("result.json"));
        // A preview the (old/buggy) container might emit for a multi-line PEM key: first 4 chars of every line.
        String leaky = "----\\nMIIE\\nX3N1\\nhkiG";
        Files.writeString(outDir.resolve("secrets.json"), "[{"
                + "\"ruleId\":\"private-key\",\"file\":\"config/id_rsa\",\"startLine\":1,"
                + "\"secretHash\":\"hash\",\"preview\":\"" + leaky + "\"}]");

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.findings()).singleElement()
                .satisfies(f -> assertThat(f.secretPreview()).isEqualTo("****"));
    }

    @Test
    void safePreviewClampsTooLongOrWhitespaceButKeepsValidPreviews() {
        // At most 4 chars, no whitespace/newline → kept.
        assertThat(ScannerOutputParser.safePreview("AKIA")).isEqualTo("AKIA");
        assertThat(ScannerOutputParser.safePreview("ab")).isEqualTo("ab");
        assertThat(ScannerOutputParser.safePreview(null)).isNull();
        // Longer than 4 → masked.
        assertThat(ScannerOutputParser.safePreview("AKIAX")).isEqualTo("****");
        // Contains a newline / CR / space → masked (never leak a line fragment).
        assertThat(ScannerOutputParser.safePreview("--\n-")).isEqualTo("****");
        assertThat(ScannerOutputParser.safePreview("a b")).isEqualTo("****");
        assertThat(ScannerOutputParser.safePreview("a\r")).isEqualTo("****");
    }

    @Test
    void cloneFailureYieldsNoFindings(@TempDir Path outDir) throws Exception {
        Files.writeString(outDir.resolve("result.json"),
                "{\"schemaVersion\":1,\"cloneFailed\":true,\"error\":\"clone failed (exit 128)\",\"scanners\":[]}");

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.cloneFailed()).isTrue();
        assertThat(parsed.findings()).isEmpty();
        assertThat(parsed.tasks()).isEmpty();
    }

    // --- SCA (OSV-Scanner) parsing ---

    @Test
    void mavenSampleYieldsExpectedCvesWithRepoRelativePaths(@TempDir Path outDir) throws Exception {
        var findings = parser.parseOsv(sample("/samples/osv/maven.json", outDir), "/work/checkout");

        assertThat(findings).hasSize(2);
        // Paths are repo-relative — a fingerprint must never contain a /work path (plan change #3).
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.type()).isEqualTo(RawFinding.TYPE_SCA);
            assertThat(f.ecosystem()).isEqualTo("Maven");
            assertThat(f.filePath()).isEqualTo("pom.xml").doesNotContain("/work");
        });
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.apache.commons:commons-text");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-42889");
            assertThat(f.installedVersion()).isEqualTo("1.9");
        });
        assertThat(findings).anySatisfy(f ->
                assertThat(f.packageName()).isEqualTo("org.apache.commons:commons-lang3"));
    }

    @Test
    void anAliasGroupBecomesOneFindingWithTheLowestSortedCve(@TempDir Path outDir) throws Exception {
        var findings = parser.parseOsv(sample("/samples/osv/alias-group.json", outDir), "/work/checkout");

        assertThat(findings).singleElement().satisfies(f -> {
            // ids [GHSA-…], aliases [CVE-2026-9999, CVE-2024-1111, GHSA-…] → the lowest-sorted CVE wins.
            assertThat(f.vulnId()).isEqualTo("CVE-2024-1111");
            assertThat(f.packageName()).isEqualTo("com.example:widget");
            assertThat(f.filePath()).isEqualTo("sub/pom.xml").doesNotContain("/work");
        });
    }

    @Test
    void npmSampleYieldsOneFindingPerAliasGroup(@TempDir Path outDir) throws Exception {
        var findings = parser.parseOsv(sample("/samples/osv/npm.json", outDir), "/work/checkout");

        assertThat(findings).hasSize(8); // one per vulnerability alias group on lodash@4.17.4
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("lodash");
            assertThat(f.ecosystem()).isEqualTo("npm");
            assertThat(f.filePath()).isEqualTo("package-lock.json");
        });
        assertThat(findings).extracting(RawFinding::vulnId).contains("CVE-2019-10744");
    }

    @Test
    void nugetSampleYieldsTheVulnerablePackageOnly(@TempDir Path outDir) throws Exception {
        // Newtonsoft.Json@12.0.1 is vulnerable; Serilog@$(SerilogVersion) has no vulns (and a non-concrete
        // version) and must not surface.
        var findings = parser.parseOsv(sample("/samples/osv/nuget.json", outDir), "/work/checkout");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.packageName()).isEqualTo("Newtonsoft.Json");
            assertThat(f.ecosystem()).isEqualTo("NuGet");
            assertThat(f.vulnId()).isEqualTo("CVE-2024-21907");
        });
    }

    @Test
    void emptyOsvReportYieldsNoFindings(@TempDir Path outDir) throws Exception {
        assertThat(parser.parseOsv(sample("/samples/osv/empty.json", outDir), "/work/checkout")).isEmpty();
    }

    @Test
    void pickVulnIdPrefersLowestCveThenGhsaThenFirstId() {
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of("GHSA-x"),
                java.util.List.of("CVE-2026-9999", "CVE-2024-1111", "GHSA-x"))).isEqualTo("CVE-2024-1111");
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of("GHSA-bbbb", "GHSA-aaaa"),
                java.util.List.of())).isEqualTo("GHSA-aaaa");
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of("OSV-2021-1"),
                java.util.List.of())).isEqualTo("OSV-2021-1");
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of(), java.util.List.of())).isNull();
    }

    @Test
    void isConcreteVersionRejectsPropertiesRangesAndWildcards() {
        assertThat(ScannerOutputParser.isConcreteVersion("12.0.1")).isTrue();
        assertThat(ScannerOutputParser.isConcreteVersion("1.2.3-beta.2")).isTrue();
        assertThat(ScannerOutputParser.isConcreteVersion("$(SerilogVersion)")).isFalse();
        assertThat(ScannerOutputParser.isConcreteVersion("[1.0,2.0)")).isFalse();
        assertThat(ScannerOutputParser.isConcreteVersion("1.*")).isFalse();
        assertThat(ScannerOutputParser.isConcreteVersion("")).isFalse();
        assertThat(ScannerOutputParser.isConcreteVersion(null)).isFalse();
    }

    private Path sample(String resource, Path outDir) throws Exception {
        Path target = outDir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
        copyResource(resource, target);
        return target;
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("sample resource %s", resource).isNotNull();
            Files.copy(in, target);
        }
    }
}
