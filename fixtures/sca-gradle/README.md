# Fixture: sca-gradle

Synthetic Gradle project with **no lockfile**, used by `GradleResolverIT` (`@Tag("live-osv")`) to prove the
M4b static Gradle resolver (D-038 / D-039).

OSV-Scanner reads `gradle.lockfile`, not `build.gradle`, so without a lockfile it resolves nothing here. The
scanner's static resolver (`scanner-image/gradle_resolver.py`) parses `build.gradle` as data, emits a synthetic
Maven `pom.xml` outside the clone, and OSV resolves that pom **transitively**:

- `implementation "org.apache.commons:commons-text:${commonsTextVersion}"` (version `1.12.0` from
  `gradle.properties`) is itself clean, but pulls the transitive `commons-lang3`, which carries
  **CVE-2025-48924**. The finding anchors to `build.gradle` (never the synthetic `/work` path — D-038).
- `testImplementation 'commons-collections:commons-collections:3.2.1'` has its own CVE (CVE-2015-7501) but is
  **test scope**, so the resolver excludes it and it must NOT surface.

Gradle coordinates are Maven coordinates, so these findings appear in OSV's **Maven** ecosystem.

Everything is fake/synthetic; no build is ever executed (D-004).
