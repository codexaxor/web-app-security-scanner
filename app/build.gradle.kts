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
    }
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

tasks.named("check") {
    dependsOn(gradleResolverTest, eolDetectTest, secscanConfigTest, scopeInventoryTest, langDetectTest,
        branchVectorsInImageTest, langDetectInImageTest)
}
