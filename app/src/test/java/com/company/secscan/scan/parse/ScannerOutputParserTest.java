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
                    assertThat(t.exitCode()).isEqualTo(1);
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
    void cloneFailureYieldsNoFindings(@TempDir Path outDir) throws Exception {
        Files.writeString(outDir.resolve("result.json"),
                "{\"schemaVersion\":1,\"cloneFailed\":true,\"error\":\"clone failed (exit 128)\",\"scanners\":[]}");

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.cloneFailed()).isTrue();
        assertThat(parsed.findings()).isEmpty();
        assertThat(parsed.tasks()).isEmpty();
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("sample resource %s", resource).isNotNull();
            Files.copy(in, target);
        }
    }
}
