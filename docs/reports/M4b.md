# Milestone report: M4b Static Gradle/.NET resolution

Branch: `feature/m4b-static-resolution` · Commit: `b252ae3` · Date: 2026-10-19 · Model(s) used: Claude Opus 4.8

## Done-when check
| Criterion (from SPEC) | Proven by (test name or manual step) | Result |
| --- | --- | --- |
| A Gradle fixture without a lockfile yields its expected transitive CVEs | `ScaLiveScanIT.gradleTransitiveCveIsFoundAndAnchoredToTheBuildFile` (sca-gradle: commons-text 1.12.0 → transitive `commons-lang3` CVE-2025-48924, anchored to `build.gradle`) | PASS |
| …including BOM-managed (version-less) deps | `ScaLiveScanIT.bomManagedGradleDependencyIsResolvedAndAnchoredToTheBuildFile` (sca-gradle-bom: version-less Spring Boot starters → `tomcat-embed-core`, resolved via fetched BOMs) | PASS |
| …in a multi-module repo, each finding anchored to its own build file | `ScaLiveScanIT.multiModuleGradleFindingsEachAnchorToTheirOwnBuildFile` (sca-gradle-multimodule: `tomcat-embed-core`→`service/build.gradle`, `snakeyaml` CVE-2022-1471→`gateway/build.gradle`, `UNRESOLVED_PATH` count 0) | PASS |
| …within an accuracy bound vs a committed lockfile | Manual (architect, done): resolver output vs the real `./gradlew :dependencies` lockfile. **ib-api-v2** (single-module): direct deps 100% (22/22), 202 resolved — ≥95% of the 151-entry lockfile (was 44/151 under D-041). **ib-keycloak** (multi-module) full pipeline scan: 59 SCA findings, `UNRESOLVED_PATH` 0, anchored to `build.gradle` (54) and `verify/consumer-scratch/build.gradle` (5) | PASS (confirmed) |
| NuGet transitive resolution if needed (deferred, gap made honest) | `ScaWarningsIT` (lockfile-less `.csproj` emits both `MISSING_NUGET_LOCKFILE` and `NUGET_NO_LOCKFILE_TRANSITIVE_GAP`) + `nugetWithLockfileEmitsNeitherNugetWarning` | PASS |
| Private-registry detection (D-032) | `ScaWarningsIT.privateRegistryWarningsCarryHostOnlyAndNeverLeakCredentials` + 9 resolver unit tests (`PrivateRegistryTests`) | PASS |

The automated Done-when is proven offline + by the live-OSV Gradle tests. The *quantitative* accuracy bound against
the private ib-api-v2 / ib-keycloak lockfiles is **confirmed** by the architect's manual run (row above): ib-api-v2
22/22 direct and ≥95% of 151 overall; ib-keycloak 59 findings, 0 `UNRESOLVED_PATH`, every finding on its own build
file.

**Stale-worker lesson.** Multi-module anchoring on ib-keycloak was verified only after the worker JVM was rebuilt;
before that, the pipeline ran a worker binary older than the D-038 parser fix even though the scanner image was
current. Scanner-image content-hash parity is NOT worker-binary parity:
`docs/WORKFLOW.md` step 6 now requires rebuilding api + worker before any manual pipeline check after a
parser/`core`/`app` change.

## What was built
- `scanner-image/gradle_resolver.py` — Python 3 (stdlib only) static Gradle resolver: parses lockfile-less
  `build.gradle(.kts)` **as data** (`python3 -I`, no build execution; D-039), emits a synthetic Maven `pom.xml` per
  build file under `/work/synthetic/<n>/` with a `buildfile` sidecar, fetches BOM POMs from Maven Central to pin
  every version (D-042), and detects private registries (D-032).
- `scanner-image/gradle_resolver_test.py` — 53 resolver unit tests, run on the host under `./gradlew build`.
- `scanner-image/run-scan.sh` — runs the resolver; builds the synthetic→build-file map from the on-disk sidecars;
  scans `$CHECKOUT` + `$SYNTH` with OSV; emits NuGet gap + private-registry warnings; echoes OSV output to the log
  excerpt on an SCA failure.
- `scanner-image/Dockerfile` — adds pinned `python3=3.12.15-r0`; swaps Gitleaks→Betterleaks v1.7.3 (D-036) with a
  baked, pinned config + empty ignore file (D-037).
- `scanner-image/betterleaks.toml`, `betterleaks-empty.ignore`, `osv-scanner.toml` — pinned engine configs so no
  in-repo suppressor is honored (D-037).
- `app/.../scan/parse/ScannerOutputParser.java` — maps each OSV synthetic-pom `source.path` (every result, a
  non-consuming lookup; OSV emits a direct and a transitive block per pom) back to its `build.gradle` before the
  `UNRESOLVED_PATH` guard and fingerprinting (D-038); normalizes the key on both sides.
- Fixtures: `sca-gradle`, `sca-gradle-bom`, `sca-gradle-multimodule`, `sca-private-registry`, `sca-osv-ignore`,
  `secrets-token`, `secrets-suppressed` (synthetic; obviously-fake secrets). `secrets-aws` removed (D-040).

## Migrations
None. No schema change: all new scan warnings (`MISSING_GRADLE_LOCKFILE`, `GRADLE_UNPARSEABLE_CONSTRUCT`,
`GRADLE_BOM_FETCH_FAILED`, `NUGET_NO_LOCKFILE_TRANSITIVE_GAP`, `PRIVATE_REGISTRY`, `UNRESOLVED_PATH`) are free-text
values in the existing `scan_warning.code` column (no CHECK constraint).

## API changes
None.

| Method | Path | Example request / response |
| --- | --- | --- |
| — | — | no REST changes in M4b |

## Deviations from SPEC.md
- **Full NuGet transitive resolution deferred (D-043).** OSV v2.7.0 resolves transitive NuGet only from a
  `packages.lock.json`; rather than build a second resolver with no real .NET repo to validate against, the
  lockfile-less `.csproj` case is made *honest* with an explicit `NUGET_NO_LOCKFILE_TRANSITIVE_GAP` warning
  (in addition to `MISSING_NUGET_LOCKFILE`). The Gradle resolver fully covers the milestone's "static resolver"
  core.
- The quantitative accuracy bound is proven by a documented manual architect run against the private repos
  (CONFIRMED — see Done-when: ib-api-v2 22/22 direct, ≥95% of 151; ib-keycloak 59 findings, 0 `UNRESOLVED_PATH`),
  not an automated test, because the repos are not in this tree.

## Decisions made (also added to DECISIONS.md)
- D-036 — secrets engine Gitleaks 8.21.2 → Betterleaks v1.7.3.
- D-037 — in-repo suppressors ignored for every engine (pinned `--config` + ignore-allow + empty ignore path).
- D-038 — synthetic-pom `source.path` rewritten to the build file before fingerprinting; + multi-module robustness
  note (map derived from on-disk sidecars; non-consuming per-result lookup for OSV's direct/transitive blocks).
- D-039 — the resolver is a stdlib-only Python 3 script in the scanner image, run `python3 -I` (D-004), apk pinned.
- D-040 — secrets fixture uses a fake GitHub PAT, not an AWS key.
- D-041 — (superseded by D-042) import BOMs into the synthetic `<dependencyManagement>`.
- D-042 — the resolver fetches BOM POMs from Maven Central (Maven-Central-only egress, TLS always on, bounded) and
  writes a concrete `<version>` on every dependency.
- D-043 — full NuGet transitive resolution deferred; honest gap warning instead.
- D-032 — private-registry detection implemented (host-only `PRIVATE_REGISTRY` warnings; never fetched, never a
  credential), resolving OD-3.

## Evidence
- `./gradlew build` (local): **210 Java tests (app 179 + core 31), 0 failed, 0 skipped** + **53 Python resolver
  tests** (`gradleResolverTest`) after the review fixes (was 209 + 45). Live-OSV ITs excluded by default.
- Live-OSV Gradle ITs (`-PliveOsv`): `ScaLiveScanIT` **5 tests, 0 failed**.

| Fixture | Type | Expected | Found |
| --- | --- | --- | --- |
| sca-gradle (no lockfile) | SCA/Gradle | transitive `commons-lang3` CVE-2025-48924 @ `build.gradle` | yes (+ over-included test-scope `commons-collections`) |
| sca-gradle-bom (version-less, BOM) | SCA/Gradle | `tomcat-embed-core` @ `build.gradle` (BOM-pinned) | yes |
| sca-gradle-multimodule | SCA/Gradle | `tomcat-embed-core`@`service/build.gradle`, `snakeyaml` CVE-2022-1471@`gateway/build.gradle`, UNRESOLVED_PATH=0 | yes |
| sca-warnings | warnings | `MISSING_GRADLE_LOCKFILE`, `MISSING_NUGET_LOCKFILE` + `NUGET_NO_LOCKFILE_TRANSITIVE_GAP`, `UNRESOLVED_VERSION` | yes |
| sca-private-registry | warnings | `PRIVATE_REGISTRY` for nexus/artifactory/nuget.corp (host only, no credentials in `/out`) | yes |

## Files the reviewer must read in full
- `scanner-image/gradle_resolver.py` — the resolver (BOM fetch + network posture + private-registry detection;
  security-sensitive: `python3 -I`, Maven-Central-only egress, TLS always on, no build execution).
- `scanner-image/run-scan.sh` — resolver invocation, on-disk synthetic map, OSV scan, warning emission.
- `app/src/main/java/com/company/secscan/scan/parse/ScannerOutputParser.java` — `parseOsv` synthetic-pom rewrite +
  `UNRESOLVED_PATH` guard + per-result non-consuming mapping.
- `scanner-image/Dockerfile`, `betterleaks.toml`, `osv-scanner.toml` — engine swap + suppressor lockdown.
- `docs/DECISIONS.md` (D-032, D-036 … D-043).

## Review fixes (fresh `/review-pr M4b`)
- `gradle_resolver.py` `_host_of`: the host is taken after the LAST `@`, not from `urlsplit().hostname`, which
  reported part of a credential containing an unencoded `#`/`?`/`/` as the host (e.g.
  `https://glpat-abc?x@nexus.corp/r` → `glpat-abc`) into `PRIVATE_REGISTRY` / `/out`.
- `MavenFetcher`: every coordinate validated before it enters a URL (map-notation `platform()`, catalog and
  nested-import coordinates were not); redirects must stay on the host AND on https; a non-https
  `SECSCAN_MAVEN_BASE_URL` is refused; a 120 s total wall-clock budget (incl. slow-drip reads) keeps the fetch
  phase well under the 30 min scan timeout; `http.client.HTTPException` is caught per fetch.
- `resolve()` catch-all now emits a visible `GRADLE_UNPARSEABLE_CONSTRUCT` warning instead of a stderr-only
  partial result (D-030).
- Parser test against REAL osv-scanner v2.7.0 output (`samples/osv/gradle-synthetic-multimodule-real.json`,
  captured from the scanner image on `fixtures/sca-gradle-multimodule`; free-text vuln fields trimmed).

## Known gaps / TODOs
- Full NuGet transitive resolution (deferred, D-043) — revisit when a real .NET repo is onboarded.
- Principled dev/test-scope exclusion for Gradle is M7 (M4b over-includes non-`buildSrc` configs and lets OSV
  decide, matching how M4 scans real poms).
- Scanner egress remains on the default bridge (D-033); the resolver needs Maven-Central reachability — revisit the
  egress allowlist before deployment / M10.
- The accuracy bound is confirmed manually only (private repos); there is no automated regression guard against
  the real lockfiles — re-run the ib-api-v2 / ib-keycloak comparison when the resolver changes.
