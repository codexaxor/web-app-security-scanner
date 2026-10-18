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

// Spring Boot manages the JUnit platform launcher versions; keep AssertJ from the BOM. The one end-to-end SCA
// test that needs live network (osv.dev/deps.dev) is tagged "live-osv" and excluded by default; run it with
// `./gradlew :app:test -PliveOsv --tests "*ScaLiveScanIT"`.
tasks.withType<Test> {
    useJUnitPlatform {
        if (!project.hasProperty("liveOsv")) {
            excludeTags("live-osv")
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

tasks.named("check") {
    dependsOn(gradleResolverTest)
}
