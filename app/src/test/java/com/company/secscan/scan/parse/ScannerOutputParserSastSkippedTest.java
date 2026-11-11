package com.company.secscan.scan.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8.15 review S3: the SAST skipped-file classification parsed from REAL Opengrep 1.31.0 output
 * ({@code samples/opengrep/skipped/}: a Timeout, a full {@code Syntax error}, an {@code Other syntax error}, a
 * size-limit skip, an unreadable file, and a {@code PartialParsing} file). The sanitized sample is exactly what
 * run-scan.sh's {@code sast_skipped_files} produces from the raw sample ({@code sastSkippedFilesInImageTest}
 * asserts that in the built image).
 */
class ScannerOutputParserSastSkippedTest {

    private final ScannerOutputParser parser = new ScannerOutputParser();

    private static void manifest(Path outDir, String sastStatus) throws Exception {
        Files.writeString(outDir.resolve("result.json"),
                "{\"schemaVersion\":1,\"cloneFailed\":false,\"commit\":\"c1\",\"checkoutPath\":\"/work/checkout\","
                        + "\"toolVersions\":{},\"scanners\":[{\"name\":\"sast\",\"status\":\"" + sastStatus + "\","
                        + "\"exitCode\":" + ("ok".equals(sastStatus) ? 0 : 2) + ",\"durationMs\":5,"
                        + "\"findingsFile\":\"opengrep.json\"}]}");
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("sample resource %s", resource).isNotNull();
            Files.copy(in, target);
        }
    }

    @Test
    void realSampleFreezesTimeoutFullParseErrorSizeAndUnreadableButOnlyCountsPartialParsing(@TempDir Path outDir)
            throws Exception {
        manifest(outDir, "ok");
        copyResource("/samples/opengrep/skipped/opengrep-skipped.json", outDir.resolve("opengrep.json"));

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.sastSkippedFiles())
                .extracting(SastSkippedFile::path, SastSkippedFile::errorTypes)
                .containsExactly(
                        tuple("src/Garbage.java", java.util.List.of("Syntax error")),
                        tuple("src/Garbage.scala", java.util.List.of("Other syntax error")),
                        tuple("src/Huge.java", java.util.List.of("exceeded_size_limit")),
                        tuple("src/noperm.py", java.util.List.of("insufficient_permissions")),
                        tuple("src/slow.py", java.util.List.of("Timeout")));
        assertThat(parsed.sastSkippedFiles()).extracting(SastSkippedFile::path)
                .as("a PartialParsing file is analyzed → not frozen").doesNotContain("src/partial.py");
        assertThat(parsed.sastPartiallyParsedFiles()).isEqualTo(1);
    }

    @Test
    void aFailedSastTaskCarriesNoSkippedFilesSoNoWarningCanFire(@TempDir Path outDir) throws Exception {
        // review N5: the parser reads skipped files only from a SUCCEEDED sast task.
        manifest(outDir, "failed");
        copyResource("/samples/opengrep/skipped/opengrep-skipped.json", outDir.resolve("opengrep.json"));

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.sastSkippedFiles()).isEmpty();
        assertThat(parsed.sastPartiallyParsedFiles()).isZero();
    }

    @Test
    void errorTypesAreReducedToTheirNameEvenFromAStringifiedPayload(@TempDir Path outDir) throws Exception {
        // Defense in depth (review S1): even if a report carried a raw/stringified type, only the leading name
        // survives — never a container path or a line range — and paths outside the checkout are dropped.
        manifest(outDir, "ok");
        Files.writeString(outDir.resolve("opengrep.json"),
                "{\"results\":[],\"errors\":[],\"partiallyParsedFiles\":0,\"skippedFiles\":["
                        + "{\"path\":\"src/a.py\",\"errorTypes\":["
                        + "\"[\\\"PartialParsing\\\",[{\\\"path\\\":\\\"/work/checkout/src/a.py\\\","
                        + "\\\"start\\\":{\\\"line\\\":6}}]]\",\"Timeout\\n/work/checkout/src/a.py:3\",\"\",\"%%%\"]},"
                        + "{\"path\":\"/etc/passwd\",\"errorTypes\":[\"Timeout\"]},"
                        + "{\"path\":\"../escape.py\",\"errorTypes\":[\"Timeout\"]}]}");

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.sastSkippedFiles()).singleElement().satisfies(sf -> {
            assertThat(sf.path()).isEqualTo("src/a.py");
            assertThat(sf.errorTypes()).containsExactly("PartialParsing", "Timeout");
        });
    }

    @Test
    void normalizeSastErrorTypeKeepsOnlyTheLeadingName() {
        assertThat(ScannerOutputParser.normalizeSastErrorType("Timeout")).isEqualTo("Timeout");
        assertThat(ScannerOutputParser.normalizeSastErrorType("Other syntax error")).isEqualTo("Other syntax error");
        assertThat(ScannerOutputParser.normalizeSastErrorType("exceeded_size_limit"))
                .isEqualTo("exceeded_size_limit");
        assertThat(ScannerOutputParser.normalizeSastErrorType(
                "[\"PartialParsing\",[{\"path\":\"/work/checkout/x.py\",\"start\":{\"line\":6}}]]"))
                .isEqualTo("PartialParsing");
        assertThat(ScannerOutputParser.normalizeSastErrorType("A".repeat(60))).hasSize(48);
    }
}
