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

// Spring Boot manages the JUnit platform launcher versions; keep AssertJ from the BOM.
tasks.withType<Test> {
    useJUnitPlatform()
}

// Builds the disposable scanner image (scanner-image/) that ScanPipelineIT launches, if the pinned tag is not
// already present. A no-op `docker image inspect` keeps repeat test runs fast; docker is required for tests
// anyway (Testcontainers). The tag must match ScannerProperties.image (D-023).
val buildScannerImage by tasks.registering(Exec::class) {
    group = "secscan"
    description = "Build the scanner Docker image if the pinned tag is missing."
    workingDir = rootDir
    commandLine(
        "sh", "-c",
        "docker image inspect secscan-scanner:8.21.2 >/dev/null 2>&1 || " +
            "docker build -t secscan-scanner:8.21.2 scanner-image/"
    )
}

tasks.named<Test>("test") {
    dependsOn(buildScannerImage)
}
