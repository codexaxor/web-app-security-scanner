package com.company.secscan.scan.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.finding.RawFinding;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        copyResource("/samples/betterleaks/secrets.json", outDir.resolve("secrets.json"));

        ParsedScan parsed = parser.parse(outDir);

        assertThat(parsed.cloneFailed()).isFalse();
        assertThat(parsed.commit()).isEqualTo("ab49a433ddc6c8b5e243ef26187b7eb30583a4c5");
        assertThat(parsed.toolVersions()).containsEntry("betterleaks", "1.7.3");
        assertThat(parsed.tasks()).singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("secret");
                    assertThat(t.succeeded()).isTrue();
                    assertThat(t.exitCode()).isEqualTo(2);
                });
        assertThat(parsed.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(RawFinding.TYPE_SECRET);
            assertThat(f.ruleId()).isEqualTo("github-pat");
            assertThat(f.filePath()).isEqualTo("config/application.properties");
            assertThat(f.line()).isEqualTo(4);
            assertThat(f.secretHash())
                    .isEqualTo("e810c94de3e7621be6d97a3d5bd08eadaf66803e591fc7b11afef3e9aa9017b2");
            assertThat(f.secretPreview()).isEqualTo("ghp_");
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
    void mavenSampleCarriesCvssVectorLabelCweAndAliases(@TempDir Path outDir) throws Exception {
        var findings = parser.parseOsv(sample("/samples/osv/maven.json", outDir), "/work/checkout");

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.vulnId()).isEqualTo("CVE-2022-42889");
            assertThat(f.toolSeverity()).isEqualTo("9.8"); // OSV max_severity (the base number)
            assertThat(f.cvss()).isEqualTo("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
            assertThat(f.severityLabel()).isEqualTo("CRITICAL");
            assertThat(f.cwe()).containsExactly("CWE-94");
            assertThat(f.cveAliases()).containsExactly("CVE-2022-42889");
            assertThat(f.advisorySummary()).isEqualTo("Arbitrary code execution in Apache Commons Text");
        });
    }

    @Test
    void noCvssFindingKeepsLabelAndCweWithNullVector(@TempDir Path outDir) throws Exception {
        // REAL capture (SF6): lodash 4.17.4 / GHSA-4xc9-xhrj-v574 — a genuine no-CVSS advisory (empty severity).
        var findings = parser.parseOsv(sample("/samples/osv/no-cvss.json", outDir), "/work/checkout");

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("lodash");
            assertThat(f.vulnId()).isEqualTo("CVE-2018-16487");
            assertThat(f.toolSeverity()).isEmpty();      // max_severity ""
            assertThat(f.cvss()).isNull();               // no severity vector
            assertThat(f.severityLabel()).isEqualTo("HIGH");
            assertThat(f.cwe()).containsExactly("CWE-400");
            assertThat(f.cveAliases()).containsExactly("CVE-2018-16487");
            assertThat(f.advisorySummary()).contains("lodash");
        });
    }

    @Test
    void realV4OnlyAdvisoryChoosesTheV4Vector(@TempDir Path outDir) throws Exception {
        // REAL capture (SF6): logback-core 1.2.11 / GHSA-25qh-j22f-pwp8 from the sca-gradle-multimodule fixture —
        // a real v4-only advisory (CVSS:4.0 vector only), so the chosen cvss vector is the v4 one (D-044). OSV
        // reports it under the module's synthetic pom (/work/synthetic/0/pom.xml), mapped to service/build.gradle
        // exactly as the real multimodule scan does (D-038).
        Map<String, String> syntheticPoms = Map.of("/work/synthetic/0/pom.xml", "service/build.gradle");
        var findings = parser.parseOsv(sample("/samples/osv/no-cvss.json", outDir), "/work/checkout",
                java.util.Set.of(), new java.util.ArrayList<>(), syntheticPoms);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("ch.qos.logback:logback-core");
            assertThat(f.vulnId()).isEqualTo("CVE-2025-11226");
            assertThat(f.toolSeverity()).isEqualTo("5.9");   // OSV max_severity carried from the real capture
            assertThat(f.cvss()).startsWith("CVSS:4.0/");
            assertThat(f.severityLabel()).isEqualTo("MODERATE");
            assertThat(f.cwe()).containsExactly("CWE-20");
            assertThat(f.cveAliases()).containsExactly("CVE-2025-11226");
            assertThat(f.filePath()).isEqualTo("service/build.gradle"); // mapped off the /work synthetic path
        });
    }

    @Test
    void v4VectorIsPreferredOverV3AndEmptyCweIsEmptyList(@TempDir Path outDir) throws Exception {
        // SYNTHETIC edge sample (SF6): no real fixture advisory carries BOTH v3 and v4 vectors AND empty cwe_ids,
        // so these two parser edges (v4 preferred over v3; empty cwe_ids -> empty list -> summary subtitle) are
        // proved against a clearly-labelled synthetic file.
        var findings = parser.parseOsv(sample("/samples/osv/synthetic-vector-edge.json", outDir), "/work/checkout");

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("com.example:synthetic-both-vectors");
            assertThat(f.cvss()).startsWith("CVSS:4.0/");  // v4 preferred over v3 (D-044)
            assertThat(f.cwe()).isEmpty();                 // empty cwe_ids -> subtitle falls back to summary
            assertThat(f.advisorySummary()).isNotBlank();
            assertThat(f.cveAliases()).containsExactly("CVE-2024-0001");
        });
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
    void nugetCsprojFindingIsKeptWhenItHasAMissingLockfileWarning(@TempDir Path outDir) throws Exception {
        // Newtonsoft.Json@12.0.1 is vulnerable; Serilog@$(SerilogVersion) has no vulns (and a non-concrete
        // version) and must not surface. App.csproj has no lockfile beside it (MISSING_NUGET_LOCKFILE), so its
        // finding is authoritative and kept.
        var findings = parser.parseOsv(sample("/samples/osv/nuget.json", outDir), "/work/checkout",
                java.util.Set.of("App.csproj"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.packageName()).isEqualTo("Newtonsoft.Json");
            assertThat(f.ecosystem()).isEqualTo("NuGet");
            assertThat(f.vulnId()).isEqualTo("CVE-2024-21907");
        });
    }

    @Test
    void nugetCsprojFindingIsDroppedWhenNoMissingLockfileWarning(@TempDir Path outDir) throws Exception {
        // Same App.csproj with a vulnerable declared version, but NO MISSING_NUGET_LOCKFILE warning: a
        // packages.lock.json exists on disk (authoritative) and is clean enough that OSV left it out of the
        // results. The .csproj's declared-but-not-resolved version must not surface as a false positive.
        var findings = parser.parseOsv(sample("/samples/osv/nuget.json", outDir), "/work/checkout",
                java.util.Set.of());

        assertThat(findings).isEmpty();
    }

    @Test
    void nugetCsprojFindingIsDroppedWhenAPackagesLockfileSitsBesideIt(@TempDir Path outDir) throws Exception {
        // OSV reports Newtonsoft.Json from BOTH App.csproj and packages.lock.json in the repo root (the real
        // v2.7.0 behaviour that caused duplicate sub-issues). App.csproj has a lockfile beside it (no
        // MISSING_NUGET_LOCKFILE warning), so its copy is dropped and the surviving finding's path is the
        // lockfile. other/Other.csproj has no lockfile (warning raised) and is kept as-is (D-035).
        var findings = parser.parseOsv(sample("/samples/osv/nuget-csproj-and-lockfile.json", outDir),
                "/work/checkout", java.util.Set.of("other/Other.csproj"));

        assertThat(findings).filteredOn(f -> f.packageName().equals("Newtonsoft.Json"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.vulnId()).isEqualTo("CVE-2024-21907");
                    assertThat(f.filePath()).isEqualTo("packages.lock.json");
                });
        // A .csproj with no lockfile in its directory still surfaces its vulnerable package.
        assertThat(findings).filteredOn(f -> f.packageName().equals("System.Text.Encodings.Web"))
                .singleElement()
                .satisfies(f -> assertThat(f.filePath()).isEqualTo("other/Other.csproj"));
    }

    @Test
    void emptyOsvReportYieldsNoFindings(@TempDir Path outDir) throws Exception {
        assertThat(parser.parseOsv(sample("/samples/osv/empty.json", outDir), "/work/checkout")).isEmpty();
    }

    @Test
    void anOsvSourcePathOutsideTheCheckoutYieldsNoFindingButAnUnresolvedPathWarning(@TempDir Path outDir)
            throws Exception {
        // The result would otherwise be a finding (concrete version, has a vuln group); it is dropped only
        // because its source path (/work/outside/pom.xml) is not under the checkout — it must never reach a
        // fingerprint. The warning names the OSV path, never file contents.
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        var findings = parser.parseOsv(sample("/samples/osv/path-escape.json", outDir), "/work/checkout",
                java.util.Set.of(), warnings);

        assertThat(findings).isEmpty();
        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("UNRESOLVED_PATH");
            assertThat(w.message()).contains("/work/outside/pom.xml");
            assertThat(w.filePath()).isNull();
        });
    }

    @Test
    void syntheticGradlePomPathIsRewrittenToTheBuildFileBeforeFingerprinting(@TempDir Path outDir)
            throws Exception {
        // D-038: OSV reports the synthetic pom's /work path; the manifest maps it to the originating
        // build.gradle (repo-relative). The mapped finding must anchor to that build file (never the /work
        // path). The unmapped /work/synthetic/9/pom.xml is NOT in the map, so it is dropped (UNRESOLVED_PATH).
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        Map<String, String> syntheticPoms = Map.of("/work/synthetic/0/pom.xml", "app/build.gradle");
        var findings = parser.parseOsv(sample("/samples/osv/gradle-synthetic.json", outDir), "/work/checkout",
                java.util.Set.of(), warnings, syntheticPoms);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(RawFinding.TYPE_SCA);
            // Gradle findings surface in OSV's Maven ecosystem (Maven coordinates, Maven Central).
            assertThat(f.ecosystem()).isEqualTo("Maven");
            assertThat(f.packageName()).isEqualTo("org.apache.commons:commons-text");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-42889");
            // The fingerprint anchors to the build file, never the synthetic /work path.
            assertThat(f.filePath()).isEqualTo("app/build.gradle").doesNotContain("/work").doesNotContain("synthetic");
        });
        // The unmapped synthetic pom escaped the checkout and had no mapping -> dropped with a warning.
        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("UNRESOLVED_PATH");
            assertThat(w.message()).contains("/work/synthetic/9/pom.xml");
        });
    }

    @Test
    void multipleSyntheticPomsAreEachRewrittenToTheirOwnBuildFile(@TempDir Path outDir) throws Exception {
        // M4b multi-module regression (D-038): TWO synthetic poms (/work/synthetic/0 and /1) map to TWO distinct
        // build files. Both must be rewritten and anchored to their OWN build file; none may be dropped as
        // UNRESOLVED_PATH. A single-entry map cannot catch a lookup that only works for n == 0.
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        Map<String, String> syntheticPoms = Map.of(
                "/work/synthetic/0/pom.xml", "service/build.gradle",
                "/work/synthetic/1/pom.xml", "gateway/build.gradle");
        var findings = parser.parseOsv(sample("/samples/osv/gradle-synthetic-multi.json", outDir), "/work/checkout",
                java.util.Set.of(), warnings, syntheticPoms);

        assertThat(findings).hasSize(2);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-42252");
            assertThat(f.filePath()).isEqualTo("service/build.gradle");
        });
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.yaml:snakeyaml");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-1471");
            assertThat(f.filePath()).isEqualTo("gateway/build.gradle");
        });
        assertThat(findings).allSatisfy(f ->
                assertThat(f.filePath()).doesNotContain("/work").doesNotContain("synthetic"));
        // Both poms mapped, so nothing was dropped.
        assertThat(warnings).isEmpty();
    }

    @Test
    void syntheticPomKeyMatchesDespiteCosmeticSlashDifferences(@TempDir Path outDir) throws Exception {
        // Defense in depth: a doubled slash in OSV's source.path must not orphan a synthetic pom. The map key is
        // clean (as parse() stores it, normalized); OSV's path here has doubled slashes. The lookup normalizes
        // the OSV path to the clean key, so the finding still anchors to the build file rather than being dropped
        // as UNRESOLVED_PATH.
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        Map<String, String> syntheticPoms = Map.of("/work/synthetic/0/pom.xml", "app/build.gradle");
        var findings = parser.parseOsv(sample("/samples/osv/gradle-synthetic-slashes.json", outDir),
                "/work/checkout", java.util.Set.of(), warnings, syntheticPoms);

        assertThat(findings).singleElement().satisfies(f ->
                assertThat(f.filePath()).isEqualTo("app/build.gradle").doesNotContain("/work"));
        assertThat(warnings).isEmpty();
    }

    @Test
    void multipleOsvResultsSharingOneSyntheticPathAreAllRewritten(@TempDir Path outDir) throws Exception {
        // OSV emits SEPARATE result entries for the same source path (a direct block and a transitive block), so
        // one synthetic pom appears in two results. The map lookup must be non-consuming and apply to EVERY
        // result on that path -- the second (transitive) block carries the CVEs. Before the fix the second block
        // was dropped as UNRESOLVED_PATH, leaving 0 SCA. Both blocks must anchor to the build file; none dropped.
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        Map<String, String> syntheticPoms = Map.of("/work/synthetic/0/pom.xml", "service/build.gradle");
        Path osvJson = sample("/samples/osv/gradle-synthetic-dup.json", outDir);
        // Guard the regression shape: the sample must actually have the SAME synthetic path in >= 2 results, or
        // it cannot reproduce the bug (a single-result-per-path sample would pass trivially).
        long sharedPathResults = java.util.regex.Pattern.compile("/work/synthetic/0/pom\\.xml")
                .matcher(Files.readString(osvJson)).results().count();
        assertThat(sharedPathResults).isGreaterThanOrEqualTo(2);
        var findings = parser.parseOsv(osvJson, "/work/checkout", java.util.Set.of(), warnings, syntheticPoms);

        // Both the direct and the transitive block were rewritten and kept.
        assertThat(findings).extracting(RawFinding::packageName).contains(
                "org.springframework.boot:spring-boot-starter-web",
                "org.apache.tomcat.embed:tomcat-embed-core");
        // The transitive-block finding (tomcat) is anchored to the build file, not the /work path.
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-42252");
            assertThat(f.filePath()).isEqualTo("service/build.gradle");
        });
        assertThat(findings).allSatisfy(f ->
                assertThat(f.filePath()).isEqualTo("service/build.gradle"));
        // A second legitimate result on an already-mapped path is NOT an unresolved path.
        assertThat(warnings).isEmpty();
    }

    @Test
    void realOsvMultiModuleOutputAnchorsEveryFindingToItsOwnBuildFile(@TempDir Path outDir) throws Exception {
        // REAL osv-scanner v2.7.0 output (scanner image, fixtures/sca-gradle-multimodule; free-text vuln fields
        // trimmed). It has the shape the hand-written samples above only model: /work/synthetic/1/pom.xml is
        // emitted as TWO results (a direct block with spring-boot-starter-web, then the transitive block).
        java.util.List<ScanWarning> warnings = new java.util.ArrayList<>();
        Map<String, String> syntheticPoms = Map.of(
                "/work/synthetic/0/pom.xml", "gateway/build.gradle",
                "/work/synthetic/1/pom.xml", "service/build.gradle");
        var findings = parser.parseOsv(sample("/samples/osv/gradle-synthetic-multimodule-real.json", outDir),
                "/work/checkout", java.util.Set.of(), warnings, syntheticPoms);

        assertThat(warnings).isEmpty(); // no UNRESOLVED_PATH: both blocks on the shared path were rewritten
        assertThat(findings).isNotEmpty().allSatisfy(f ->
                assertThat(f.filePath()).isIn("gateway/build.gradle", "service/build.gradle"));
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.yaml:snakeyaml");
            assertThat(f.vulnId()).isEqualTo("CVE-2022-1471");
            assertThat(f.filePath()).isEqualTo("gateway/build.gradle");
        });
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
            assertThat(f.filePath()).isEqualTo("service/build.gradle");
        });
        // The direct block on the shared path is kept too.
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.packageName()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
            assertThat(f.filePath()).isEqualTo("service/build.gradle");
        });
    }

    @Test
    void normalizeSyntheticKeyCollapsesSlashesAndTrailingSlash() {
        assertThat(ScannerOutputParser.normalizeSyntheticKey("/work/synthetic/0/pom.xml"))
                .isEqualTo("/work/synthetic/0/pom.xml");
        assertThat(ScannerOutputParser.normalizeSyntheticKey("/work//synthetic///0/pom.xml"))
                .isEqualTo("/work/synthetic/0/pom.xml");
        assertThat(ScannerOutputParser.normalizeSyntheticKey("/work/synthetic/0/")).isEqualTo("/work/synthetic/0");
        assertThat(ScannerOutputParser.normalizeSyntheticKey("C:\\work\\synthetic\\0\\pom.xml"))
                .isEqualTo("C:/work/synthetic/0/pom.xml");
        assertThat(ScannerOutputParser.normalizeSyntheticKey(null)).isNull();
    }

    @Test
    void isPathOutsideCheckoutFlagsAbsoluteAndDotDotPaths() {
        assertThat(ScannerOutputParser.isPathOutsideCheckout("pom.xml")).isFalse();
        assertThat(ScannerOutputParser.isPathOutsideCheckout("sub/pom.xml")).isFalse();
        assertThat(ScannerOutputParser.isPathOutsideCheckout(null)).isFalse();
        assertThat(ScannerOutputParser.isPathOutsideCheckout("/work/outside/pom.xml")).isTrue();
        assertThat(ScannerOutputParser.isPathOutsideCheckout("../escape/pom.xml")).isTrue();
        assertThat(ScannerOutputParser.isPathOutsideCheckout("sub/../../escape/pom.xml")).isTrue();
    }

    @Test
    void pickVulnIdPrefersLowestCveThenGhsaThenFirstId() {
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of("GHSA-x"),
                java.util.List.of("CVE-2026-9999", "CVE-2024-1111", "GHSA-x"))).isEqualTo("CVE-2024-1111");
        // "Lowest CVE" is numeric by (year, sequence), not lexicographic: CVE-2024-9999 < CVE-2024-10000.
        assertThat(ScannerOutputParser.pickVulnId(java.util.List.of(),
                java.util.List.of("CVE-2024-10000", "CVE-2024-9999"))).isEqualTo("CVE-2024-9999");
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
