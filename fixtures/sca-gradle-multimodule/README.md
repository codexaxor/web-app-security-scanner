# sca-gradle-multimodule

A synthetic **multi-module** Gradle project with **no lockfiles** anywhere, used by the live-OSV regression that
guards the D-038 synthetic-pom → build-file mapping for the `n ≥ 2` case.

```
settings.gradle         include 'service', 'gateway'
build.gradle            root (java plugin only; nothing resolvable)
service/build.gradle    Boot 2.6.5 BOM → spring-boot-starter-web  → tomcat-embed-core 9.0.60 (UNIQUE to service)
gateway/build.gradle    Boot 2.6.5 BOM → spring-boot-starter      → snakeyaml 1.29 (CVE-2022-1471); NO tomcat
```

Two lockfile-less build files produce **two** synthetic poms (`/work/synthetic/0`, `/work/synthetic/1`). Before
the M4b multi-module fix, OSV reported findings under both synthetic poms but the parser could not map the
second one back (the synthetic→build-file map was lost when the resolver's stdout did not survive), so every
Gradle finding was dropped as `UNRESOLVED_PATH` and the scan produced **0 SCA issues**. The fix derives the map
from a `buildfile` sidecar written beside each synthetic pom on disk, so each finding anchors to its own module's
`build.gradle`.

The fixture is intentionally tiny and declares **no inline versions**, so it also exercises BOM fetching
(D-042). It is only meaningful with live network (`live-osv`), because OSV resolves the transitive graph via
deps.dev. All coordinates are public OSS; there are no secrets.
