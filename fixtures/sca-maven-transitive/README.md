# Fixture: sca-maven-transitive

Synthetic Maven project whose ONLY vulnerable package is **transitive**: the direct dependency
`commons-text:1.12.0` is clean, but it pulls `commons-lang3` (not declared in this pom) which carries
**CVE-2025-48924**. Used by `ScaLiveScanIT` (`@Tag("live-osv")`) to prove OSV-Scanner resolves Maven
transitive dependencies in default mode (deps.dev data source), with no unsafe flag and without contacting
repositories declared in the pom.
