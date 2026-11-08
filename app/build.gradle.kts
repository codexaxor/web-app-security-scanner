import java.security.MessageDigest

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly(libs.postgresql)

    // Worker-only: launch hardened, disposable scanner containers against the host Docker daemon (D-022).
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.wiremock)
}

// Spring Boot manages the JUnit platform launcher versions; keep AssertJ from the BOM. Tests that need live
// network OR the heavier M6 scanner tools (real Opengrep/Checkov runs) are tagged and excluded by default so
// `./gradlew build` stays fast and offline:
//   - "live-osv"      (osv.dev/deps.dev):      `./gradlew :app:test -PliveOsv      --tests "*ScaLiveScanIT"`
//   - "live-intel"    (cisa.gov / epss):       `./gradlew :app:test -PliveIntel    --tests "*LiveIntel*"`
//   - "live-opengrep" (real Opengrep SAST):    `./gradlew :app:test -PliveOpengrep --tests "*Opengrep*"`
//   - "live-checkov"  (real Checkov IaC):      `./gradlew :app:test -PliveCheckov  --tests "*Checkov*"`
//   - "live-eol"      (endoflife.date / EOL container): `./gradlew :app:test -PliveEol --tests "*Eol*"`
//   - "live-reaper"   (real Docker daemon + scanner image): `./gradlew :app:test -PliveReaper --tests "*ScanReaperIT*"`
// A test needing several tools carries several tags (e.g. M6ScannerImageIT is live-opengrep AND live-checkov),
// so it runs only when ALL its flags are passed.
tasks.withType<Test> {
    useJUnitPlatform {
        if (!project.hasProperty("liveOsv")) {
            excludeTags("live-osv")
        }
        if (!project.hasProperty("liveIntel")) {
            excludeTags("live-intel")
        }
        if (!project.hasProperty("liveOpengrep")) {
            excludeTags("live-opengrep")
        }
        if (!project.hasProperty("liveCheckov")) {
            excludeTags("live-checkov")
        }
        if (!project.hasProperty("liveEol")) {
            excludeTags("live-eol")
        }
        if (!project.hasProperty("liveReaper")) {
            excludeTags("live-reaper")
        }
    }
}

// Content hash of scanner-image/, mirroring buildScannerImage's find-based formula (skip dotfiles and
// __pycache__). Declared as an input property on :app:test and every in-image test task so their up-to-date
// checks are tied to the scanner image's content: a change to ANY baked scanner file re-runs those tasks
// against the freshly rebuilt image instead of staying UP-TO-DATE / FROM-CACHE. Without it a scanner-image
// change rebuilds the image (buildScannerImage reruns) but leaves :app:test UP-TO-DATE, so the ITs never
// exercise the new image (the M8.15 S1 gate miss). It need not equal the docker label string — Gradle only
// compares it against this task's own previous value, so any stable content hash detects the change.
val scannerImageContentHash: Provider<String> = providers.provider {
    val base = rootProject.file("scanner-image")
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> "%02x".format(b) }
    val manifest = base.walkTopDown()
        .filter { it.isFile }
        .filterNot { f ->
            f.relativeTo(base).invariantSeparatorsPath.split('/')
                .any { seg -> seg.startsWith(".") || seg == "__pycache__" }
        }
        .map { f -> f.relativeTo(base).invariantSeparatorsPath + "\u0000" + sha256Hex(f.readBytes()) }
        .sorted()
        .joinToString("\n")
    sha256Hex(manifest.toByteArray(Charsets.UTF_8))
}

// Builds the disposable scanner image (scanner-image/) that the worker ITs launch. The tag must match
// ScannerProperties.image (D-023). Rebuilds when the image is missing OR when scanner-image/ changed: a
// content hash of scanner-image/ is stamped on the image as a label, and we rebuild whenever the current
// content hash differs from that label (so editing e.g. secscan-lib.sh triggers a rebuild, not just a new
// tag). `inputs.dir` lets Gradle skip the task entirely when nothing under scanner-image/ changed.
val buildScannerImage by tasks.registering(Exec::class) {
    group = "secscan"
    description = "Build the scanner Docker image when scanner-image/ changes or the pinned tag is missing."
    workingDir = rootDir
    inputs.dir(rootProject.file("scanner-image")).withPropertyName("scannerImage")
    commandLine(
        "sh", "-c",
        """
        set -eu
        hash="${'$'}(find scanner-image -type f -not -path '*/.*' -not -path '*/__pycache__/*' -exec sha256sum {} \; | sort | sha256sum | cut -d' ' -f1)"
        label="${'$'}(docker image inspect -f '{{ index .Config.Labels "secscan.scanner.content-hash" }}' secscan-scanner:8.21.2 2>/dev/null || true)"
        if [ "${'$'}label" = "${'$'}hash" ]; then
            echo "scanner image secscan-scanner:8.21.2 is up to date (content-hash ${'$'}hash)"
        else
            echo "building scanner image secscan-scanner:8.21.2 (content-hash ${'$'}hash)"
            docker build -t secscan-scanner:8.21.2 --label "secscan.scanner.content-hash=${'$'}hash" scanner-image/
        fi
        """.trimIndent()
    )
}

tasks.named<Test>("test") {
    dependsOn(buildScannerImage)
    // Re-run the suite (incl. the worker ITs that launch the scanner image) whenever the image content changes.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
}

// D-039 gate: the static Gradle resolver (scanner-image/gradle_resolver.py) is Python, so its unit tests run
// under the host python3 wired into the DEFAULT build. Offline, stdlib-only (unittest); a resolver regression
// fails `./gradlew build`. The behavioural end-to-end proof (real transitive CVEs via OSV) is the live-osv
// GradleResolverIT. NOTE: run WITHOUT `python3 -I` -- the test harness imports the resolver module from its
// own directory; `-I` is only for the container invocation against untrusted repos.
val gradleResolverTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the static Gradle resolver's Python unit tests (D-039)."
    val scannerDir = rootProject.file("scanner-image")
    workingDir = scannerDir
    inputs.file(scannerDir.resolve("gradle_resolver.py")).withPropertyName("resolver")
    inputs.file(scannerDir.resolve("gradle_resolver_test.py")).withPropertyName("resolverTest")
    // A marker output so Gradle can treat the task as up to date when neither script changed.
    val marker = layout.buildDirectory.file("gradle-resolver-test.ok")
    outputs.file(marker)
    // -B / PYTHONDONTWRITEBYTECODE: never write __pycache__ under scanner-image/ (it would churn the scanner
    // image's content hash and dirty the tree).
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-B", "gradle_resolver_test.py")
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-055 gate: the static EOL detector (scanner-image/eol_detect.py) is Python; its unit tests run under the
// host python3 wired into the DEFAULT build (offline, stdlib unittest), mirroring gradleResolverTest.
val eolDetectTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the static EOL detector's Python unit tests (D-055)."
    val scannerDir = rootProject.file("scanner-image")
    workingDir = scannerDir
    inputs.file(scannerDir.resolve("eol_detect.py")).withPropertyName("eolDetect")
    inputs.file(scannerDir.resolve("eol_detect_test.py")).withPropertyName("eolDetectTest")
    val marker = layout.buildDirectory.file("eol-detect-test.ok")
    outputs.file(marker)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-B", "eol_detect_test.py")
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-057 gate: the .secscan.yml reader (scanner-image/secscan_config.py) is Python; its unit tests run under the
// host python3 wired into the DEFAULT build (stdlib unittest; the whitelist/caps/confinement tests need no
// PyYAML, the YAML-parse tests self-skip when PyYAML is absent), mirroring eolDetectTest.
val secscanConfigTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the .secscan.yml reader's Python unit tests (D-057)."
    val scannerDir = rootProject.file("scanner-image")
    workingDir = scannerDir
    inputs.file(scannerDir.resolve("secscan_config.py")).withPropertyName("secscanConfig")
    inputs.file(scannerDir.resolve("secscan_config_test.py")).withPropertyName("secscanConfigTest")
    val marker = layout.buildDirectory.file("secscan-config-test.ok")
    outputs.file(marker)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-B", "secscan_config_test.py")
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-059 gate: the declared dev/test scope inventory (scanner-image/scope_inventory.py) is Python; host-run
// unit tests under the DEFAULT build (stdlib unittest), mirroring eolDetectTest.
val scopeInventoryTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the declared dev/test scope inventory's Python unit tests (D-059)."
    val scannerDir = rootProject.file("scanner-image")
    workingDir = scannerDir
    inputs.file(scannerDir.resolve("scope_inventory.py")).withPropertyName("scopeInventory")
    inputs.file(scannerDir.resolve("scope_inventory_test.py")).withPropertyName("scopeInventoryTest")
    val marker = layout.buildDirectory.file("scope-inventory-test.ok")
    outputs.file(marker)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-B", "scope_inventory_test.py")
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-081 gate (M8.5): the static language detector (scanner-image/lang_detect.py) is Python; host-run unit tests
// under the DEFAULT build (stdlib unittest; sizes files only, no network), mirroring eolDetectTest.
val langDetectTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the static language detector's Python unit tests (M8.5, D-081)."
    val scannerDir = rootProject.file("scanner-image")
    workingDir = scannerDir
    inputs.file(scannerDir.resolve("lang_detect.py")).withPropertyName("langDetect")
    inputs.file(scannerDir.resolve("lang_detect_test.py")).withPropertyName("langDetectTest")
    val marker = layout.buildDirectory.file("lang-detect-test.ok")
    outputs.file(marker)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-B", "lang_detect_test.py")
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-076 gate (SF-5): the scanner's branch-name validator (secscan-lib.sh valid_branch_name) must agree with the
// Java core.scan.BranchName rules on the SHARED scanner-image/branch-vectors.json, and run-scan.sh must refuse an
// invalid SCAN_BRANCH before git. Run INSIDE the built image (busybox sh + the shipped scripts), not host dash,
// under LC_ALL=C.UTF-8 so busybox counts code points (N-1). Runs as --user 0 so the test can write /out and
// /work for the run-scan.sh refusal check — this exercises run-scan.sh LOGIC only; the production runtime user
// (USER 10001) and the container hardening are unchanged and tested elsewhere.
val branchVectorsInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the branch-vector parity + run-scan.sh refusal test inside the scanner image (D-076/SF-5)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    inputs.file(scannerDir.resolve("secscan-lib.sh")).withPropertyName("secscanLib")
    inputs.file(scannerDir.resolve("run-scan.sh")).withPropertyName("runScan")
    inputs.file(scannerDir.resolve("branch-vectors.json")).withPropertyName("branchVectors")
    inputs.file(scannerDir.resolve("branch_vectors_test.py")).withPropertyName("branchVectorsTest")
    // Runs INSIDE the built image, so tie its up-to-date check to the whole image content, not just these files.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("branch-vectors-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0",
        "-e", "LC_ALL=C.UTF-8", "-e", "BRANCH_VECTORS=/host/branch-vectors.json",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/branch_vectors_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-081 gate (M8.5, review N-5): the language detector's filesystem-sensitive cases (symlink-out not followed,
// vendored dirs skipped, empty repo) run INSIDE the built scanner image against the SHIPPED lang_detect.py, so
// os.walk/islink/realpath behaviour is proven on the real container runtime, not only the host. Runs as
// --user 0 for a logic-only check (like branchVectorsInImageTest); the production USER 10001 is unchanged.
val langDetectInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the language detector's filesystem tests inside the scanner image (M8.5, D-081, N-5)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    inputs.file(scannerDir.resolve("lang_detect.py")).withPropertyName("langDetect")
    inputs.file(scannerDir.resolve("lang_detect_in_image_test.py")).withPropertyName("langDetectInImageTest")
    // Runs INSIDE the built image, so tie its up-to-date check to the whole image content, not just these files.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("lang-detect-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/lang_detect_in_image_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-104 gate (M8.15 S2): the SAST exclusion lists (deny-list + category-exclusion) are applied INSIDE the
// image, so verify them there against the SHIPPED baked rules — the category list regenerates byte-for-byte
// (diff gate), no excluded id is dead or belongs to an existing pack, and --exclude-rule actually removes
// every listed id from a synthetic trigger scan. Runs as --user 0 (logic-only, like the sibling in-image
// tests); production USER 10001 + hardening unchanged.
val opengrepExclusionsInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the SAST deny-list + category exclusions inside the scanner image (D-104, M8.15 S2)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    inputs.file(scannerDir.resolve("gen_category_exclusions.py")).withPropertyName("genCategoryExclusions")
    inputs.file(scannerDir.resolve("opengrep-denylist.txt")).withPropertyName("denylist")
    inputs.file(scannerDir.resolve("opengrep-excluded-by-category.txt")).withPropertyName("categoryExclusions")
    inputs.file(scannerDir.resolve("opengrep_exclusions_in_image_test.py")).withPropertyName("exclusionsTest")
    // Runs INSIDE the built image, so tie its up-to-date check to the whole image content, not just these files.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("opengrep-exclusions-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0",
        "-e", "HOST=/host",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/opengrep_exclusions_in_image_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-104 gate (M8.15 S3): Opengrep must load rules containing non-ASCII text, which it only does under a UTF-8
// locale. Proven inside the image: C.UTF-8 loads+fires a non-ASCII rule, a forced non-UTF-8 locale crashes on
// it, and run-scan.sh sets C.UTF-8 for the opengrep scan. --user 0 logic-only (like the sibling in-image tests).
val opengrepLocaleInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify Opengrep loads non-ASCII rules under C.UTF-8 inside the scanner image (D-104, M8.15 S3)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    inputs.file(scannerDir.resolve("run-scan.sh")).withPropertyName("runScan")
    inputs.file(scannerDir.resolve("opengrep_locale_in_image_test.py")).withPropertyName("localeTest")
    // Runs INSIDE the built image, so tie its up-to-date check to the whole image content, not just these files.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("opengrep-locale-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0",
        "-e", "HOST=/host",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/opengrep_locale_in_image_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-104 gate (M8.15 S6): recall + canary leak. Scans fixtures/sast-recall THE PRODUCTION WAY (real run-scan.sh
// over a file:// clone) inside the built image, asserting every expected (check_id, file) fires for the six new
// packs, negatives stay clean, no denied/category-excluded id appears, and the planted canary secret never
// reaches /out or the logs. --user 0 logic-only (like the sibling in-image tests); production hardening unchanged.
val sastRecallInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run SAST recall + canary-leak checks for the new packs inside the scanner image (D-104, M8.15 S6)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    val fixtureDir = rootProject.file("fixtures/sast-recall")
    inputs.file(scannerDir.resolve("sast_recall_in_image_test.py")).withPropertyName("recallTest")
    inputs.file(scannerDir.resolve("run-scan.sh")).withPropertyName("runScan")
    inputs.dir(fixtureDir).withPropertyName("recallFixture")
    // Runs INSIDE the built image, so tie its up-to-date check to the whole image content, not just these files.
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("sast-recall-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0", "--network", "none",
        "-e", "SCAN_CLONE_URL=file:///tmp/repo",
        "-e", "FIXTURE=/fixture",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "-v", fixtureDir.absolutePath + ":/fixture:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/sast_recall_in_image_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

// D-104 gate (M8.15 S7): the custom MIT rules (scorecard-driven) must fire on their positive fixture and stay
// silent on their negative, inside the built image, in the default build. --user 0 logic-only (like the sibling
// in-image tests); production hardening unchanged.
val customRulesInImageTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the custom MIT SAST rules fire on positives + stay silent on negatives (D-104, M8.15 S7)."
    dependsOn(buildScannerImage)
    val scannerDir = rootProject.file("scanner-image")
    val fixtureDir = rootProject.file("fixtures/sast-custom")
    inputs.file(scannerDir.resolve("custom_rules_in_image_test.py")).withPropertyName("customRulesTest")
    inputs.dir(scannerDir.resolve("opengrep-rules-custom")).withPropertyName("customRules")
    inputs.dir(fixtureDir).withPropertyName("customFixtures")
    inputs.property("scannerImageContentHash", scannerImageContentHash)
    val marker = layout.buildDirectory.file("custom-rules-in-image-test.ok")
    outputs.file(marker)
    commandLine(
        "docker", "run", "--rm", "--user", "0", "--network", "none",
        "-e", "FIXTURE=/fixture",
        "-v", scannerDir.absolutePath + ":/host:ro",
        "-v", fixtureDir.absolutePath + ":/fixture:ro",
        "--entrypoint", "python3", "secscan-scanner:8.21.2",
        "-I", "/host/custom_rules_in_image_test.py",
    )
    doLast { marker.get().asFile.writeText("ok\n") }
}

tasks.named("check") {
    dependsOn(gradleResolverTest, eolDetectTest, secscanConfigTest, scopeInventoryTest, langDetectTest,
        branchVectorsInImageTest, langDetectInImageTest, opengrepExclusionsInImageTest,
        opengrepLocaleInImageTest, sastRecallInImageTest, customRulesInImageTest)
}
