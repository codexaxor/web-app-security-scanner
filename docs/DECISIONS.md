# Decisions log

Newest first. One entry per decision. Claude Code adds entries for every new dependency, tool, or choice the spec
did not cover. Never delete an entry; supersede it with a new one.

Format:

```
## D-<nnn> <title> (<YYYY-MM-DD>, <status: accepted | superseded by D-xxx | open>)
Context: why we had to decide.
Decision: what we chose.
Consequences: what this implies / what we gave up.
```

---

## D-042 Gradle resolver fetches BOM POMs from Maven Central to pin every version (2026-10-18, accepted)
Context: D-041 imported the governing BOM into the synthetic pom's `<dependencyManagement>` and emitted
BOM-managed dependencies version-less, expecting OSV's `pomxml` transitive enricher to inherit the versions.
Measured on ib-api-v2 (@3be435d5) this failed: OSV does NOT inherit versions from a `<dependencyManagement>`
import and filters version-less dependencies as unscannable — only 44/151 (~29%) resolved, far below the ≥95%
target. All inputs are static in `build.gradle`; we must not run a build (D-004) and must not guess (D-035).
Decision: the resolver itself fetches the governing BOM POMs from Maven Central and writes a concrete
`<version>` on EVERY dependency it reports, so OSV has nothing version-less to filter. It builds the managed-
version map by reading each BOM's `<dependencyManagement>` and resolving `<properties>` interpolation, the
`<parent>` chain (fetched too) and nested `<scope>import</scope><type>pom</type>` sub-BOMs (fetched + merged
recursively — `spring-boot-dependencies` imports ~24). BOM coordinates are those D-041 already detects (Boot
plugin version, `platform()`/`enforcedPlatform()`, `mavenBom "..."`, `${prop}` from ext/set/extra/properties).
A dependency unresolved after full expansion → `GRADLE_UNPARSEABLE_CONSTRUCT` (never guessed/dropped); a BOM
that cannot be fetched → `GRADLE_BOM_FETCH_FAILED`, scan continues. Network: read-only HTTPS GET of `.pom` from
ONE host only — Maven Central (`repo.maven.apache.org`, already allowed by D-033) or the `SECSCAN_MAVEN_BASE_URL`
mirror — TLS verification ALWAYS on (never disabled), cross-host redirects blocked, bounded (per-POM timeout
15s, total cap 200/run, parent depth 8, import depth 6, 10 MB cap, in-run cache). `python3 -I` preserved;
fetched POMs are parsed as data, never imported/executed; no build is run.
Consequences: a new runtime egress need for the Gradle resolver step (Maven Central — within the D-033
allowlist); a Boot+Cloud build fetched 60 POMs in ~2.7s and emitted concrete versions on every dependency
(`spring-boot-starter-web`→`2.6.5`, `spring-cloud-starter-openfeign`→`3.1.4`). Supersedes the version-less
emission (and BOM-as-`<type>pom>` trigger) of D-041; D-041's BOM detection is retained.

## D-041 Gradle synthetic pom imports BOMs for Spring Boot/Cloud BOM-managed deps (2026-10-18, superseded by D-042)
Context: M4b Gradle repos (Spring Boot/Cloud) declare most dependencies with no inline version (BOM-managed), so
the D-038 synthetic pom listed only inline-versioned deps and OSV resolved almost nothing for them.
Decision: detect the governing BOMs — Spring Boot Gradle plugin version → `spring-boot-dependencies`,
`platform()`/`enforcedPlatform()`, `mavenBom "..."` (with `${prop}` from `ext{}`/`set()`/`extra[]`/
`gradle.properties`) — import them into the synthetic pom's `<dependencyManagement>` as
`<scope>import</scope><type>pom</type>`, emit BOM-managed deps version-less, and (because OSV's resolver only
engages with a concrete-versioned dep) inject the first BOM as a `<type>pom>` trigger. Broaden ext interpolation
to all declarations; surface a write failure as a warning.
Consequences: the BOM DETECTION and ext interpolation are retained, but the version-less emission + trigger did
not actually resolve versions in OSV (measured 44/151 on ib-api-v2) and are **superseded by D-042**, which
fetches the BOMs and pins every version explicitly.

## D-040 Secrets fixture uses a fake GitHub PAT, not an AWS key (2026-10-17, accepted)
Context: Betterleaks v1.7.3 reworked `aws-access-token` into a composite rule (an access key ID must be paired
with a validatable secret access key) and does not fire offline on a lone fake AWS key; the old
`fixtures/secrets-aws` key also paired with AWS's documented EXAMPLE secret, which is allowlisted. So the M4
secrets fixture no longer proves detection under the new engine.
Decision: the secrets fixture plants an obviously-fake GitHub personal access token (`ghp_…`), detected under
the stable, specific `github-pat` rule (identical id in both engines). `fixtures/secrets-aws` is renamed to
`fixtures/secrets-token`; this also updates the M2 done-when fixture (`ScanPipelineIT`) and the sample dir
`app/src/test/resources/samples/gitleaks` → `betterleaks`. The real open secret in `secscan-test-secrets` is an
RSA private key (`config/deploy-key.pem`, `private-key`), which Betterleaks detects offline unchanged (same rule
id, matched as the whole PEM block) — it is NOT affected by this fixture change and is not modified.
Consequences: a reliably-detected synthetic secret under the new engine; no real project secret changes.

## D-039 Gradle/NuGet static resolver is a Python 3 script in the scanner image (2026-10-17, accepted)
Context: the M4b static Gradle resolver (and NuGet transitive resolution) must run where the clone exists and
where OSV runs — inside the scanner container — so it cannot live in `core` as unit-testable Java. The existing
in-container parsing precedent is POSIX sh + xmllint (`secscan-lib.sh`), but ≥95% accuracy on Gradle Groovy/
Kotlin DSL plus TOML (`libs.versions.toml`) is not viable in busybox sh.
Decision: implement the resolver as a Python 3 script shipped in the scanner image (slices 1–2). Binding
constraints: stdlib only (TOML via `tomllib`, Python ≥ 3.11 — confirmed: Alpine 3.21 ships **python3 3.12.15**
with `tomllib`); invoke as `python3 -I`; read scanned-repo files as DATA only, never import/exec/eval from the
scanned tree (D-004); pin the `python3` apk version and record it when it is added; resolver unit tests run
under `./gradlew build` (wired via a Gradle task) or, if impractical, `GradleResolverIT` is the gate. Python is
NOT added to the image in slice 0 (no resolver yet) — only when slice 1 lands.
Consequences: a maintainable, testable resolver; one new image dependency (python3) arriving in slice 1.
Slice 1 (2026-10-18): the Gradle resolver landed as `scanner-image/gradle_resolver.py`, invoked `python3 -I`;
the `python3` apk is pinned to **`python3=3.12.15-r0`** (Alpine 3.21, ships `tomllib`). Its unit tests
(`gradle_resolver_test.py`) run on the host under `./gradlew build` via the `gradleResolverTest` task (the
primary gate); the live-osv `ScaLiveScanIT` is the behavioural proof. NuGet transitive resolution (slice 2) is
not yet done.

## D-038 Gradle synthetic-pom path mapping anchors SCA fingerprints to the build file (2026-10-17, accepted)
Context: the M4b Gradle resolver emits a synthetic `pom.xml` for OSV to resolve transitively, written OUTSIDE
the clone so a scanned repo cannot plant one. OSV then reports the synthetic pom's `/work` path, which the
parser's `UNRESOLVED_PATH` guard (D-035) would drop, and which must never enter a fingerprint anyway.
Decision: synthetic poms live at `/work/synthetic/<n>/pom.xml` (outside the clone); the scanner manifest maps
each to its originating `build.gradle` repo-relative path; the parser rewrites OSV's `source.path` to that
build-file path BEFORE fingerprinting, so a Gradle SCA finding's fingerprint anchors to the real `build.gradle`.
Anything that does not map (a stray `/work` path) still hits the `UNRESOLVED_PATH` guard and is dropped.
Consequences: stable, meaningful Gradle SCA identities; the path-escape guard remains in force. (Implemented in
slice 1.)

## D-037 Scanned-repo suppressions are ignored for every engine (2026-10-17, accepted; resolves OD-8 and the repo-config should-fix)
Context: a scanned repository could otherwise hide its own findings via in-repo scanner config, ignore files or
inline allow comments — unacceptable for a security product, where only SecScan's own audited triage may
suppress.
Decision: all in-repo suppressors are ignored for every current and future engine. A pinned config is baked
into the scanner image and passed with `--config`; every in-repo config file, ignore file and inline allow
comment is ignored. Only SecScan's own audited ignore rules suppress a finding (reason code + `triage_event`).
Implemented in slice 0 for both current engines (verified against the real tools):
- Betterleaks: `--config /etc/secscan/betterleaks.toml` (precedence #1, so an in-repo `.betterleaks.toml`/
  `.gitleaks.toml` is ignored; `[extend] useDefault = true` keeps the full default rule set), plus
  `--ignore-gitleaks-allow` (ignore inline `gitleaks:allow`/`betterleaks:allow`) and `--gitleaks-ignore-path`
  pointed at a baked EMPTY file (so the repo's `.gitleaksignore`/`.betterleaksignore` is never read).
- OSV-Scanner: `--config /etc/secscan/osv-scanner.toml` overrides any in-repo `osv-scanner.toml`
  (`[[IgnoredVulns]]`/`[[PackageOverrides]]`) — verified against v2.7.0: an in-repo `[[IgnoredVulns]]` drops a
  finding, and adding `--config` brings it back.
Proven by `BetterleaksConfigInjectionIT` (secrets, default build) and `OsvConfigInjectionIT` (SCA, `live-osv`).
Consequences: a repo cannot weaken its own scan; the pinned configs may only keep detection strong, never relax
it.

## D-036 Secrets engine Gitleaks 8.21.2 -> Betterleaks v1.7.3 (2026-10-17, accepted; resolves OD-7)
Context: Gitleaks' name/repo control changed hands; the original author started Betterleaks
(github.com/betterleaks/betterleaks, MIT) as its successor. Our rule is to pin every scanner by version +
checksum (D-023/D-034). Done now (not later) because the secret group key is the rule id and the only stored
secret is the test fixture, so the swap re-fingerprints nothing of value (D-029).
Decision: adopt Betterleaks **v1.7.3** (no v2 exists yet). The release ships per-arch tarballs verified against
the published `checksums.txt` before use:
`linux_x64  (amd64) = 9d2ed6fc387bc1c3f95557d5539077b1ce422f0db5ec38db5f14a141ad2947bf`,
`linux_arm64        = 0541cd2980964a6fb8ea768e8b9507c91a3c06229193462f33a3ecd9cb146b99`.
The version is recorded in `scan.tool_versions` (key `betterleaks`). Validation stays OFF (no `--validation`
flag → no live provider calls; `BetterleaksCommandTest` guards against it being added). In-repo suppressors are
ignored (D-037). The scanner image tag stays `secscan-scanner:8.21.2` — a HISTORICAL identifier, not the engine
version (D-034). Revisit at Betterleaks v2.
Probed facts (v1.7.3, recorded not trusted): directory scan is `betterleaks dir <path>`; JSON report is an
array of objects (Gitleaks-compatible fields `RuleID`/`File`/`StartLine`/`Secret`) or the literal `null` when
clean (run-scan.sh tolerates both); exit codes with `--exit-code 2` are 0 = clean, 2 = leaks found, 1 (and any
other) = error. AWS detection changed: `aws-access-token` is now a COMPOSITE rule (an access key ID paired with
a validatable secret access key) and does not fire offline on a lone fake key, so the secrets fixture moved from
a fake AWS key to a fake GitHub token (`github-pat`), whose rule id is identical across both engines (no id-map
needed; see the M4b report).
Consequences: a maintained, MIT secrets engine; one new pinned tool version + two new checksums. Any bump is new
checksums + a new decision.

## D-035 .NET SCA uses OSV-Scanner's native .csproj/CPM extraction, not a custom input file (2026-10-16, accepted; supersedes the .NET fallback mechanism of D-031)
Context: D-031 (and the SPEC) assumed OSV-Scanner could not read `.csproj` and that we would parse direct
`<PackageReference>` versions ourselves and feed OSV a custom `osv-scanner.json` (`--lockfile osv-scanner:…`,
plan change #4). Probing the pinned **OSV-Scanner v2.7.0** showed it source-scans `.csproj` AND NuGet Central
Package Management (`Directory.Packages.props`) natively: a bare `App.csproj` with a vulnerable
`PackageReference` yields `source.path = <the .csproj>` and the expected GHSA/CVE.
Decision: rely on OSV's native `.csproj`/CPM extraction (direct `PackageReference` versions, already mapped to
the real `.csproj` path). We do NOT write a custom input file (change #4 dropped — it would duplicate OSV's
native extraction, giving two findings per package, unless we also disabled OSV's plugin). We still raise a
`MISSING_NUGET_LOCKFILE` warning per `.csproj` that has no sibling `packages.lock.json` (the coverage gap is
real: without the lockfile only direct deps are seen, no transitive). Plan change #6 is kept but enforced on our
side: our inventory step reads each `.csproj` with `xmllint --nonet` (never `--noent`) and raises an
`UNRESOLVED_VERSION` warning (path + package) for versions that are MSBuild properties (`$(…)`), ranges, `*`
wildcards or `VersionOverride`; the parser also drops OSV packages whose version is such a non-concrete string
(OSV passes them through literally, e.g. `Serilog@$(SerilogVersion)`, matching nothing). Transitive NuGet
resolution without a lockfile remains an M4b item.
Consequences: less code and no double-counting; .NET direct-dep SCA works out of the box. `UNRESOLVED_VERSION`
visibility depends on our inventory parse, not OSV.
NuGet .csproj/lockfile dedup (2026-10-16, from the manual real-GitLab check): when a `.csproj` and a
`packages.lock.json` sit in the same directory, OSV v2.7.0 reports the same package from BOTH sources, so the
parser emitted two SCA findings (two sub-issues) for one vulnerability. Rule (in `ScannerOutputParser.parseOsv`):
a `.csproj`-sourced finding is kept ONLY when that `.csproj` (repo-relative path) has a `MISSING_NUGET_LOCKFILE`
warning in the same scan — i.e. the inventory step found no sibling `packages.lock.json` on disk. Otherwise a
lockfile exists and is authoritative (resolved versions, includes transitives), so the `.csproj` copy is
dropped. The rule keys on the filesystem inventory (the warning), NOT on OSV's results: OSV may omit a clean
lockfile from its JSON, and a `.csproj` beside it must still be dropped or its declared-but-not-resolved version
would be a false positive. Proven by `ScannerOutputParserTest` —
`nugetCsprojFindingIsKeptWhenItHasAMissingLockfileWarning`, `nugetCsprojFindingIsDroppedWhenNoMissingLockfileWarning`,
`nugetCsprojFindingIsDroppedWhenAPackagesLockfileSitsBesideIt`.
Vuln-id choice is FROZEN from M4 on (2026-10-16): the preferred id is the numerically lowest CVE by (year,
sequence) across the alias group, else the lowest-sorted GHSA, else the first id (`ScannerOutputParser.pickVulnId`).
Because this id feeds the SCA fingerprint (`package | vulnId | manifestPath`), changing the rule would
re-fingerprint every stored SCA sub-issue (mass solve + reopen), so it must not change once SCA findings are
stored. The parser also drops any OSV result whose source path is not safely repo-relative (still absolute or
containing a `..` segment) and raises an `UNRESOLVED_PATH` warning, so such a path can never enter a fingerprint.
Known gap (M4, for the report): npm `devDependencies` and Gradle test configurations are NOT excluded from SCA
— OSV-Scanner v2.7.0's JSON exposes no dependency-group ("dev") marker to filter on (Q2). Deferred to M7's
`DEV_DEPENDENCY` auto-triage, which will ignore such findings with a visible reason code.

## D-034 OSV-Scanner pinned to v2.7.0 (checksum + flags) (2026-10-16, accepted)
Context: M4's single SCA engine is OSV-Scanner (D-006), added to the scanner image; our rule is to pin every
scanner by version + checksum (as Gitleaks is, D-023).
Decision: pin OSV-Scanner **v2.7.0** (https://github.com/google/osv-scanner/releases/tag/v2.7.0). The release
ships raw per-arch binaries verified against `osv-scanner_SHA256SUMS` before use:
`linux_amd64 = 160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b`,
`linux_arm64 = 4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7`. Run
`osv-scanner scan source -r --format json` over the checkout; default deps.dev online data source (no
`--offline`, no `--data-source=native`, per D-006). The OSV-Scanner version is recorded in `scan.tool_versions`.
Exit codes: `0`/`1` = success (0 = no vulns, 1 = vulns found); `128` = no packages (scan_task `ok` **plus** a
`NO_MANIFESTS` warning, and the SCA scanner is NOT solvable for that scan — closes nothing, domain rule 3 /
D-030); all other codes = failure (closes no SCA issue, scan partial). The scanner image tag stays
`secscan-scanner:8.21.2` — an identifier matching `ScannerProperties.image`, tracked for the M10 weekly rebuild.
Consequences: a reproducible SCA engine; a new runtime egress need to deps.dev/osv.dev (D-033). Any bump is new
checksums + a new decision.
Maven transitive resolution (verified v2.7.0): `osv-scanner scan source -r` resolves Maven transitive
dependencies **by default** via the **deps.dev** data source — no `--allow-unsafe-plugins`/native flag, and it
does **not** follow `<repositories>`/`<pluginRepositories>` or other URLs declared in the scanned pom (verified:
a bogus `<repositories>` URL was ignored while an undeclared transitive dep was still resolved). Parent/
effective-POM lookups, when needed, go to **Maven Central** (a fixed host), not to artifact-declared URLs. We
deliberately do **not** pass `--data-source=native` (which would fetch from Maven Central/registries via the
`transitivedependency/pomxml` plugin). Egress implication for D-033: allow `api.deps.dev`, `api.osv.dev`, and
`repo.maven.apache.org`.

## D-033 Scanner egress stays on the default bridge network for M4 (2026-10-16, accepted; revisit before any deployment or M10, whichever comes first)
Context: SPEC "Securing the scanner" wants scanner egress limited to an allowlist (GitLab, api.osv.dev,
api.deps.dev, …). The scanner has used the default Docker bridge (full egress) since M2; M4 adds the first real
egress need (OSV → osv.dev / deps.dev).
Decision: keep the default bridge network for M4. An enforced egress allowlist (an egress-proxy sidecar on an
internal network with `HTTPS_PROXY` + a git proxy, allowlisting only GitLab, `api.osv.dev`, `api.deps.dev` and
`repo.maven.apache.org` — the last for OSV's Maven effective-POM/parent lookups, D-034) is deferred. This is not a
hardening regression — the bridge predates M4 — and every other container control stays in force (non-root,
read-only rootfs, `--cap-drop=ALL`, `no-new-privileges`, CPU/memory/PID limits, hard timeout, no Docker socket).
Revisit when: before any deployment or M10, whichever comes first.
Consequences: a compromised scanner could reach arbitrary hosts until the allowlist lands; accepted for local
single-tenant mode.

## D-032 Public package registries assumed; private-registry detection is M4b (2026-10-16, accepted; resolves OD-3)
Context: OD-3 — private registries (Nexus, Artifactory, GitLab package registry) — had to be settled before M4.
Decision: M4 assumes public registries (osv.dev/deps.dev online, D-006) and stores no registry credentials.
Detecting and handling private registries is deferred to M4b.
Consequences: packages served only from a private registry may be unresolved in M4; no registry credentials are
held anywhere.

## D-031 Gradle/.NET without lockfiles in M4: warn + partial; static Gradle resolver is M4b (2026-10-16, accepted; partly resolves OD-1/OD-2)
Context: OD-1 (Gradle without a lockfile) and OD-2 (.NET without `packages.lock.json`) had to be settled before
M4, and we never run a build (D-004, D-030).
Decision: M4 raises a per-build-file "missing lockfile" scan warning and produces partial results. .NET falls
back to the **direct** `<PackageReference>` versions in `.csproj` (and `Directory.Packages.props`), written
outside the clone in OSV-Scanner's documented custom format and fed to it via `--lockfile osv-scanner:<path>`,
only for projects that have no `packages.lock.json` in the same folder; versions that are MSBuild properties,
ranges, wildcards or `VersionOverride` are skipped with an `UNRESOLVED_VERSION` warning (never guessed). Gradle
without a lockfile is warning-only (a `build.gradle`-only scan finds little because Spring Boot BOM-managed
versions are absent). A static Gradle resolver (and NuGet transitive resolution if needed) is a separate
milestone **M4b**, designed later. Never run gradle/dotnet/npm/mvn (D-004). Python is out of M4 (not an MVP
ecosystem).
Consequences: OD-1/OD-2 are partly resolved now (warn + .NET direct fallback) and finished in M4b; Gradle SCA
coverage is limited until then.

## D-030 Scanned repositories are never modified; coverage gaps are shown, not hidden (2026-10-16, accepted)
Context: SCA needs dependency versions, but running a build (gradle/dotnet/npm/mvn) on an untrusted repo is code
execution (D-004) and would also mean writing files (lockfiles, CI jobs) into repositories we hold only
read-only access to.
Decision: SecScan never modifies a scanned repository and never requires a change to it (no committed lockfiles,
CI jobs or config files as a precondition to scan). Dependency resolution is therefore static only (with D-004).
Where a repository's layout limits what can be resolved, the gap is surfaced as a scan-health warning (per build
file), never silently dropped.
Consequences: SCA completeness depends on what the repo already commits; partial coverage is explicit and
visible. Teams can improve coverage by committing lockfiles but are never forced to.

## D-029 The secret fingerprint key must never change once secrets are stored (2026-10-14, accepted)
Context: a secret sub-issue's fingerprint is derived from `secret_hash = HMAC-SHA256(SECSCAN_FINGERPRINT_KEY,
container SHA-256)` (D-027). Changing the key re-fingerprints every secret, so on the next scan the old
sub-issues would all look "missing" (solved) and the same secrets would reappear as new findings — a mass
false-solve plus duplicates.
Decision: each secret sub-issue stores a short `key_id` = first 8 hex chars of
`HMAC-SHA256(key, "secscan-key-id")` (non-reversible, reveals nothing about the key). During reconcile the
secret sub-issues are **partitioned by `key_id`** (M3 review refinement): those under the current key id
reconcile normally (solve/reopen), while those under any other key id are **frozen** — never solved, never
reopened, no `triage_event` — so the whole secret scanner is not stopped, only the superseded rows. A single
error names the count of frozen rows and the stale key id(s) (all non-reversible), never a secret. Operators
must clean up the old-key rows manually; bulk ignore arrives in M7. The key must be backed up and rotated with
care; rotation re-fingerprints all secrets and is a later, explicit feature. `key_id` is never returned by the
API.
Consequences: a changed key degrades gracefully — old secrets are frozen (kept, not mass-solved) while secrets
under the new key keep reconciling normally — instead of corrupting the feed or freezing all secret
reconciliation; operators get a loud, counted signal and a manual cleanup step. One extra stored column per
sub-issue.

## D-028 Repository lifecycle events in a dedicated table, not triage_event (2026-10-14, accepted)
Context: D-020's connection reassignment/deactivation currently only logs; M3 must persist it. The options were
a new table or `triage_event` with a nullable `sub_issue_id`.
Decision: a dedicated `repository_event` table (`workspace_id`, `repository_id`, `event_type` in
REASSIGNED/DETACHED/DEACTIVATED/CONFIRMED/REATTACHED, `from_connection_id`, `to_connection_id`, `reason_code`,
`detail`, `at`). `triage_event` stays specifically the issue-status audit trail (domain rule 4: from/to status,
reason code, actor, scan) and is not diluted with events that have no issue, status or sub-issue. The
`from`/`to` connection ids are plain uuids, not FKs, because the "from" connection is typically deleted at the
moment the event is written. A `RepositoryEventRecorder` writes these at the D-020 sites (connection removal,
and the connection guard's confirm/deactivate), within the same transaction as the state change.
Consequences: both audit trails stay purpose-specific and queryable; one new table.
Note (M3 review, accepted for now, revisit in M10): `triage_event.sub_issue_id`/`group_id` are `ON DELETE
CASCADE`, so hard-deleting a sub-issue/group would also delete its audit trail; and the issue-model FKs are
single-column (`id` only), so the database does not enforce that a sub-issue/triage_event shares its subject's
`workspace_id` (the server always writes a consistent `workspace_id`). Both are safe in M3 — nothing deletes
issue rows (M7 ignore only flips status) and the deployment is single-workspace — and are left as-is to avoid a
schema change now. Revisit before the first hard delete (repository deletion / tenant teardown) or multi-tenant
auth: switch the audit FKs to `ON DELETE RESTRICT`/`SET NULL` and add composite `(workspace_id, id)` FKs.

## D-027 Keyed secret fingerprint (HMAC-SHA256) instead of a bare SHA-256 (2026-10-14, accepted)
Context: the scanner container computes a SHA-256 of each secret value (the raw value never leaves the
container). Storing that bare digest would let anyone with a copy of the database confirm a guessed secret by
hashing candidates offline — the digest is unsalted and the input space for many secret types is small.
Decision: the worker stores `secret_hash = HMAC-SHA256(SECSCAN_FINGERPRINT_KEY, the container's SHA-256)`, hex.
The key is a 32-byte base64 value in `SECSCAN_FINGERPRINT_KEY`; an invalid key fails startup (like
`SECSCAN_ENCRYPTION_KEY`), and a missing key makes storing a secret finding fail with a clear error rather than
ever persisting the bare digest. The masked preview is computed in the container (first 4 chars only when the
secret is ≥ 12 chars, else `****`).
Consequences: a stolen database alone cannot confirm guessed secrets; the fingerprint stays stable and
comparable for M3 reconcile. The key must be backed up with the database — changing it re-fingerprints every
secret, so secrets seen before the change reopen as new on the next scan. Two keys (encryption + fingerprint)
to manage.

## D-026 Docker socket mounted into the worker only (2026-10-13, accepted)
Context: the worker launches scanner containers against the host Docker daemon, which requires the daemon
socket; mounting it grants host-root-equivalent power to whatever holds it.
Decision: the Docker socket is mounted into the **worker only**, never into a scanner container (CLAUDE.md).
The worker never passes the socket, a token env var, or the socket path to a scanner. This is accepted for
local, single-tenant mode; before any deployment we revisit with a rootless/again-sandboxed launcher or a
remote build service (tracked with OD-5, worker hosting).
Consequences: simple local operation; a compromise of the worker process is host-root-equivalent until we
harden the launch path for deployment.

## D-025 Fixture clone path and deferred secret-scan coverage (2026-10-13, accepted)
Context: M2's done-when must be an automated test, but there is no production GitLab token/VPN in this
environment (as in M1/M1b).
Decision: the automated `ScanPipelineIT` clones a synthetic local repository over `file://` (mounted read-only
at `/src`) with `network=none`, exercising clone → scan → sanitize → parse → persist → container-removed
offline. The token-via-one-time-file + `https` clone path is verified in the manual real-GitLab run, folded in
with the M1b check deferred by D-021. Full git-history secret scanning (spec: full clone for the first secrets
scan of a repo) is deferred — M2 scans the checked-out tree with `gitleaks dir` — and tracked in Known gaps.
Consequences: a self-contained, offline done-when; the token/https and full-history paths are covered manually
in M2 and automated later.

## D-024 Raw scan_finding store ahead of the issue model (2026-10-13, accepted)
Context: M2 must "store a finding", but the dedup/issue model (`issue_group`/`sub_issue`, fingerprints,
reconcile) is M3.
Decision: M2 persists one `scan_finding` row per normalized finding per scan (pipeline step 7's `RawFinding`),
with no dedup or status. M3's reconcile consumes these into `issue_group`/`sub_issue` with stable fingerprints.
Secret findings store only a SHA-256 hash and a 4-char preview (never the value).
Consequences: M2 is self-contained and testable before the issue model exists; `scan_finding` remains the
normalizer's output that reconcile reads.

## D-022 docker-java for the scanner launcher (2026-10-13, accepted)
Context: the worker must create/start/wait/log/remove hardened containers on the host daemon from Java.
Decision: use `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5`, pinned to
**3.4.2** to match Spring Boot 3.3.x's managed `httpclient5` (5.3.x); docker-java 3.5+ requires httpclient5
5.4+. TLS to the daemon is never disabled.
Consequences: a new runtime dependency (worker only); the version is coupled to the Spring Boot BOM's
httpclient5 and is revisited when Boot is upgraded.

## D-023 Scanner image: Alpine (digest-pinned) + Gitleaks (checksum-pinned); in-container secret sanitize (2026-10-13, accepted)
Context: M2 needs a disposable scanner container with git + a secrets scanner, and secret findings must never
store or leak the raw value (CLAUDE.md security rules).
Decision: `scanner-image/` builds from `alpine:3.21` pinned by digest, adds git + jq, and installs Gitleaks
8.21.2 verified against its published SHA-256 (per-arch). `run-scan.sh` clones one commit into tmpfs `/work`,
runs `gitleaks dir --no-banner --report-format json` (exit 0 or 1 = success; any other code = scanner failure),
then computes the SHA-256 and 4-char preview of each secret **inside the container** and writes only those,
with the repo-relative file path and start line, to `/out/secrets.json` — the raw value never leaves the
container. A manifest `/out/result.json` carries the cloned commit, tool versions and per-scanner status. The
token, when present, arrives only as a read-only file and is sent as a basic-auth header via a tmpfs git config,
never in the URL/env/argv/logs (D-014). apk package versions are not individually pinned (follow the pinned
Alpine branch) — see Known gaps.
Consequences: reproducible scanner builds; the strongest secret-handling posture (raw secret dies with the
container); OSV-Scanner/Opengrep/Checkov are added to this same image in M4/M6.

## D-020 Multiple connections, project-mode targets, and reassignment on removal (2026-10-12, accepted)
Context: M1b lets a workspace connect a whole group and/or several single projects; removing a connection must
not lose repositories (scan history hangs off them from M2) and must keep a clone path resolvable.
Decision: `gitlab_connection` gains `target_type` (`group`|`project`), `gitlab_project_id`, `project_full_path`
and `last_verified_at` (V5); the one-per-workspace unique is dropped and replaced by partial unique indexes
per group and per project. A connection is tested/created via `POST /connections` and its token rotated via
`PUT /connections/{id}` (same target only; a different target means a new connection); the old singular
`/connection` endpoints are removed. Project mode resolves `GET /projects/:url-encoded-path`, reusing the same
allowed-hosts check, no-redirect client, scope/expiry checks and pasted-URL support as group mode. Removing a
connection never deletes repository rows: each is reassigned — to a project-mode connection for the same
`gitlab_project_id`, else the group connection whose `group_full_path` is the longest namespace prefix of the
repo path, newest-verified breaking a tie (offline, no GitLab call at delete time) — or, if none, detached
(`connection_id` nullable) and deactivated with `inactive_reason = NO_ACCESSIBLE_CONNECTION`. A reassignment is
provisional (`reassigned_at`): the next use of the new connection confirms it, or, on a 401/403/404,
deactivates the repo. First-wins dedup applies only while a repository is owned and active; a detached or
inactive repository is reattached to the connection that next selects it (clearing the inactive/provisional
state). The tie-break prefers the most-recently-verified connection and treats a null `last_verified_at` as
oldest (existing rows are backfilled to `created_at` in V5). A concurrent duplicate `create` is caught via the
partial unique index and returned as `CONNECTION_EXISTS`, not a 500. Reassignments/deactivations are logged (a
persistent `repository_event` row arrives with M3 — D-028, not `triage_event`).
Consequences: additive, reversible connection management; a repo can briefly point at a connection that cannot
reach it, resolved on first use; delete works even with the VPN down or a token expired.

## D-019 Repository dedup and connection ownership (2026-10-12, accepted)
Context: Multiple connections (M1b) could surface the same GitLab project; a repository must exist once per
workspace, and we must know which connection to use to clone it.
Decision: A repository is unique per workspace on `gitlab_project_id` (already enforced by
`uq_repository_workspace_project`). `repository.connection_id` records the connection used to clone it. When a
connection is removed, its repositories are reassigned to another connection that exposes the same project, or
deactivated if none does. Changing an existing connection's group is rejected for now (M1); the reassign/
deactivate behavior is implemented in M1b.
Consequences: no duplicate repositories; clone credentials always resolve to a real connection.

## D-018 Outbound GitLab host allowlist (2026-10-12, accepted)
Context: The server makes outbound calls to a user-supplied GitLab base URL — an SSRF risk if unconstrained.
Decision: `SECSCAN_GITLAB_ALLOWED_HOSTS` (comma-separated, empty = deny all) lists the hosts the app may call.
Every outbound GitLab call requires `https` and an allowlisted host before the request is made; loopback hosts
may use `http` (for a local/mock GitLab) but must still be allowlisted. A disallowed host or scheme fails with a
clear `HOST_NOT_ALLOWED` error. A pasted group URL for a non-allowlisted host is rejected the same way.
Consequences: the base URL cannot be pointed at arbitrary internal services; operators opt hosts in explicitly.

## D-017 Encryption key handling (2026-10-12, accepted)
Context: GitLab tokens are encrypted with `SECSCAN_ENCRYPTION_KEY`; we must fail safe, never store plaintext.
Decision: If the key is present it must base64-decode to exactly 32 bytes, validated at startup — an invalid
key fails startup with a clear message. If absent, the app still starts and saving/using a connection returns a
clear "encryption key not configured" error (never a plaintext fallback). Changing the key makes stored tokens
unreadable, so you must reconnect; key rotation is a later feature.
Consequences: safe-by-default; a documented reconnect step if the key changes.

## D-016 GitLab group resolution and single connection (2026-10-12, accepted)
Context: Project listing needs a group; paths can be renamed.
Decision: Resolve the group with `GET /groups/:url-encoded-path` and store its numeric `gitlab_group_id` plus
`full_path`; use the id for all later calls. One connection per workspace in M1, with a clear error when the
group is not found or not visible. The data model stays open to more connections/groups later (no UI for that
now). This extends the spec's `gitlab_connection` columns with `group_full_path` and `gitlab_group_id`.
Consequences: stable against group renames; multi-connection is a later, additive change.

## D-015 GitLab client = Spring RestClient; WireMock for tests (2026-10-12, accepted)
Decision: Use Spring `RestClient` (sync) for GitLab REST v4 with explicit connect/read timeouts; test it with
WireMock (dep `org.wiremock:wiremock-standalone`). TLS verification is never disabled.
Consequences: a new test dependency; real-GitLab verification is a manual step.

## D-014 Token decrypted in-memory in api and worker (2026-10-12, accepted)
Context: The api needs the token for the connection test, project listing and commit polling; a worker-only
decrypt would force a job round-trip onto synchronous UI actions.
Decision: Decrypt the token in-memory in whichever process needs it — the api (test/listing/polling) and the
worker (scanner clone hand-off via a one-time file). Never logged, never in env vars or URLs. The scanner
container only ever sees the token via a one-time file. This reclarifies the earlier "decrypted only in the
worker" wording in CLAUDE.md.
Consequences: simpler synchronous UX; the "worker-only" rule now means "the scanner container, not the api."

## D-013 GitLab instance (resolves OD-4) (2026-10-12, accepted)
Context: We need the target GitLab to build M1 against.
Decision: Self-managed GitLab at `https://gitlab.celloscope.net`, reached over VPN, with a valid public TLS
certificate and reachable from inside Docker containers (no custom CA or DNS). TLS verification is never
disabled. The GitLab version is read from `/version` at connect time rather than pinned.
Consequences: OD-4 is resolved except the version (runtime). No token yet, so M1 is built/tested against a
mocked GitLab (WireMock); the real-GitLab connect is a manual check.

## D-012 Local mode: no PRs or CI until we deploy (2026-10-11, accepted)
Context: everything runs locally; nothing is deployed.
Decision: the local `./gradlew build` is the gate; milestones are reviewed (`/review-pr`) and merged locally by a
human with `--no-ff`; GitHub is a backup remote.
Consequences: no automated gate — the review step must not be skipped; re-enable CI and PRs when we deploy.

## D-011 Bootstrap admin from env, seed workspace via migration (2026-10-11, accepted)
Context: We need a usable login in single-tenant mode without putting any password in git.
Decision: `V2__default_workspace.sql` seeds one workspace (fixed UUID, name "Default") — real data every
table needs in single-tenant mode. The admin user is NOT seeded by migration; on startup (api profile), if
`app_user` is empty and `SECSCAN_BOOTSTRAP_ADMIN_EMAIL` / `SECSCAN_BOOTSTRAP_ADMIN_PASSWORD` are set, create
one admin (bcrypt) in the default workspace. Log the email, never the password.
Consequences: no secrets in migrations; first run is reproducible; roles still deferred to M10.

## D-010 Minimal Spring Security in M0 (2026-10-11, accepted)
Context: The app must boot and be reachable, but real auth/roles are M10 and M1 must not be blocked.
Decision: `/actuator/health` public; `/api/**` requires HTTP Basic against `app_user` (bcrypt). No roles yet.
No Spring default generated password or default login page.
Consequences: M1 can call `/api/**` with Basic auth; role-based authorization added in M10.

## D-009 Build toolchain: Gradle wrapper 8.10.2, Java 21, version catalog (2026-10-11, accepted)
Context: System Gradle is 6.9.4 (too old for Spring Boot 3); default JDK on the host is 25, but we target Java 21.
Decision: Commit a Gradle wrapper pinned to 8.10.2; pin a Java 21 toolchain in Gradle; keep dependency versions
in `gradle/libs.versions.toml`; one Docker image serves both profiles, selected by `SPRING_PROFILES_ACTIVE`;
the worker does not mount the Docker socket in M0 (that arrives in M2).
Consequences: reproducible builds regardless of host JDK; `./gradlew` is the only supported build entrypoint.

## D-008 Branching model: main / develop / feature (2026-10-11, accepted, superseded in part by D-012 (no PRs))
Context: Work is done by an AI agent; we need reviewable, reversible units.
Decision: `main` (stable, tagged per milestone), `develop` (integration), `feature/m<N>-<slug>` per milestone.
No direct commits to `main`/`develop`. See `docs/WORKFLOW.md`.
Consequences: every milestone is one PR, easy to review and revert.

## D-007 Change detection by polling, not webhooks (2026-10-11, accepted)
Context: Creating GitLab project hooks needs the write `api` scope.
Decision: Poll the default branch SHA every 15 min with a read-only token.
Consequences: up to 15 min delay; no write access needed.

## D-006 OSV-Scanner online mode (2026-10-11, accepted)
Context: Offline mode disables Maven transitive resolution.
Decision: Use online osv.dev / deps.dev; cache advisories locally.
Consequences: scanner needs egress to api.osv.dev and api.deps.dev.

## D-005 Opengrep for SAST (2026-10-11, accepted)
Context: Semgrep CE lacks cross-file taint; Opengrep is the LGPL-2.1 community fork (Aikido co-founded it).
Decision: Opengrep with pinned rule bundles + our custom rules.
Consequences: rule-pack licences must be rechecked before any SaaS use.

## D-004 Never execute scanned repositories' code (2026-10-11, accepted)
Context: Running builds on untrusted repos is a code-execution risk.
Decision: Parse files only. Gradle/.NET without lockfiles get a warning and partial results.
Consequences: incomplete SCA for repos without lockfiles until open decision OD-1/OD-2 is resolved.

## D-003 Postgres as job queue (2026-10-11, accepted)
Decision: `scan_job` table with `FOR UPDATE SKIP LOCKED`; no Redis/RabbitMQ.

## D-002 Two profiles, one codebase (2026-10-11, accepted)
Decision: Spring Boot `api` and `worker` profiles from the same app module.

## D-001 Tenant-ready schema (2026-10-11, accepted)
Decision: `workspace_id` on every business table from day one.

---

## Open decisions

- OD-1 Gradle repos without lockfiles — PARTLY RESOLVED by D-031 (warn-only in M4); finished in M4b
- OD-2 .NET repos without `packages.lock.json` — PARTLY RESOLVED by D-031 (`.csproj` direct fallback in M4);
  finished in M4b
- OD-3 Private package registries — RESOLVED by D-032 (public assumed in M4; private-registry detection → M4b)
- OD-4 GitLab instance, version and groups — RESOLVED by D-013 (version read at runtime)
- OD-5 Hosting for the worker — needed before M9
- OD-6 Product name

## D-021 M1b real-GitLab check deferred to M2 (2026-10-13, accepted; DONE 2026-10-14)
Context: M1b logic is covered by WireMock + Testcontainers tests; M1 already proved real GitLab connectivity.
Decision: Merge M1b without its manual real-GitLab check; run it as part of the M2 manual run.
Consequences: M2's report must include the M1b project-connection check (group + project, dedup, reassignment).
DONE (2026-10-14): Manual run completed against gitlab.celloscope.net (19.4.1-ee) over VPN — M2 real scan and
the M1b group/project/dedup/reassignment check both passed. Recorded in `docs/reports/M2.md`
("Manual verification (2026-10-14)"). Also confirmed the D-025 manual path (token one-time-file + `https`
clone): scan completed, exit 0, no token in logs, no leftover container.
