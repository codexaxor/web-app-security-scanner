# Fixture: sca-gradle-bom

Synthetic Spring Boot + Spring Cloud Gradle project with **no lockfile** and **no inline dependency versions** —
every dependency is BOM-managed. Used by `ScaLiveScanIT` (`@Tag("live-osv")`) to prove the **D-041** BOM-import
path end to end, which is what the ib-api-v2 accuracy gate exercises.

The static resolver (`scanner-image/gradle_resolver.py`):

- imports `org.springframework.boot:spring-boot-dependencies:2.6.5` (from the Boot plugin version) and
  `org.springframework.cloud:spring-cloud-dependencies:2021.0.4` (from `mavenBom "...:${springCloudVersion}"`,
  resolved from `ext { set('springCloudVersion', '2021.0.4') }`) into the synthetic pom's
  `<dependencyManagement>` as `<scope>import</scope><type>pom</type>`;
- emits `spring-boot-starter-web` and `spring-cloud-starter-openfeign` **version-less** (managed by the BOMs);
- since there is **no** inline-versioned dependency to trigger OSV's transitive resolver, injects the Boot BOM
  as a `<type>pom</type>` dependency purely as the trigger (a BOM pom contributes no packages of its own).

OSV then resolves the full transitive graph — e.g. `spring-boot-starter-web` → `tomcat-embed-core` (9.0.60,
known CVEs) — with every finding anchored to `build.gradle` (D-038). Gradle coordinates are Maven coordinates,
so findings appear in OSV's **Maven** ecosystem. No build is ever executed (D-004); all values are static.
