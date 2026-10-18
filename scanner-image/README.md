# scanner-image

The disposable scanner container (git + Betterleaks for secrets + OSV-Scanner for SCA). One container per scan,
destroyed afterwards; only `/out` and the container logs leave it.

## Build

```
docker build -t secscan-scanner:8.21.2 scanner-image/
```

The tag `8.21.2` is a HISTORICAL identifier (it began as the Gitleaks version) — it is NOT the engine version.
The secrets engine is now Betterleaks 1.7.3 (D-036); the tag stays 8.21.2 so it keeps matching
`ScannerProperties.image` and the M10 rebuild tracker (D-034). The build is also wired into the Gradle test run
(the `buildScannerImage` task builds this image before the worker ITs if the tag is missing or `scanner-image/`
changed).

## Runtime contract

The worker's launcher runs this image with these mounts and env (see `ScannerLauncher`):

| Path / env | Purpose |
| --- | --- |
| `/work` | tmpfs scratch (clone + raw Betterleaks/OSV output); dies with the container |
| `/out` | bind mount; sanitized `result.json` + `secrets.json` are written here |
| `/run/secrets/gitlab-token` | optional read-only one-time token file (absent for `file://` clones) |
| `SCAN_CLONE_URL` | repo to clone (`https://…` or `file:///src`) |
| `SCAN_BRANCH` | branch to clone (default branch) |
| `SCAN_CLONE_DEPTH` | clone depth (default `1`) |

The token is never put in the clone URL, an env var, or the command line, and no secret value is ever written
to `/out` or the logs: `run-scan.sh` computes a SHA-256 + masked preview inside the container and emits only
those. The preview reveals the first 4 characters only when the secret is at least 12 characters long;
shorter secrets are fully masked as `****` (see `secret_preview` in `secscan-lib.sh`). The SHA-256 emitted
here is further keyed (HMAC-SHA256) by the worker before it is stored, so the bare digest is never persisted.

Betterleaks runs with `--exit-code 2`, so a clean tree (exit 0) and leaks found (exit 2) are both successful
runs, while any other exit code (1 = error) — or a missing/invalid JSON report — is a scanner failure
(`status: "failed"`, process exit 20). A clean tree yields the JSON literal `null`, which is treated as "no
findings". `betterleaks dir` does not follow symlinks. In-repo suppressors are ignored (D-037): a pinned
`--config /etc/secscan/betterleaks.toml` (precedence over any in-repo `.betterleaks.toml`/`.gitleaks.toml`),
`--ignore-gitleaks-allow` (inline allow comments), and `--gitleaks-ignore-path` pointed at a baked empty file
(the repo's `.gitleaksignore`/`.betterleaksignore`). Validation is OFF (we never pass `--validation`), so no
live provider calls are made. OSV-Scanner is likewise passed a pinned `--config /etc/secscan/osv-scanner.toml`
so an in-repo `osv-scanner.toml` cannot suppress a vulnerability.

## Static Gradle resolution (D-038 / D-039)

OSV-Scanner reads `gradle.lockfile`, not `build.gradle`, so a Gradle project with no lockfile resolves nothing.
`gradle_resolver.py` (Python 3, stdlib only — pinned `python3=3.12.15-r0`, which ships `tomllib`) parses each
lockfile-less `build.gradle`/`build.gradle.kts` **as data** and emits a synthetic Maven `pom.xml` under
`/work/synthetic/<n>/` (outside the clone, so a scanned repo cannot plant one). OSV then scans both the
checkout and `/work/synthetic`, resolving the synthetic poms **transitively** (deps.dev). It is invoked
`python3 -I` so the scanned tree and CWD are off `sys.path` and a planted `json.py`/`tomllib.py` can never be
imported (D-004); no build is ever executed.

**BOM version expansion (D-042; supersedes D-041).** Spring Boot/Cloud Gradle builds declare most dependencies
with no inline version (managed by a BOM). OSV's pomxml enricher does **not** inherit versions from a
`<dependencyManagement>` BOM import — it filters version-less deps — so the resolver **fetches the governing
BOM POMs from Maven Central itself** and writes a concrete `<version>` on every dependency it reports. It builds
the managed-version map by reading each BOM's `<dependencyManagement>` and resolving `<properties>`
interpolation, the `<parent>` chain (fetched too) and nested `<scope>import</scope><type>pom</type>` entries
(fetched and merged recursively — `spring-boot-dependencies` imports ~24 sub-BOMs). BOM coordinates come from
the Spring Boot plugin version (`id 'org.springframework.boot' version 'X'`, Kotlin/`plugins`/legacy
`spring-boot-gradle-plugin:X`) → `spring-boot-dependencies:X`; `platform(...)`/`enforcedPlatform(...)`; and
`mavenBom "..."` — with `${prop}` resolved from `ext{}` (incl. `set('p','v')`), `extra[...]` and
`gradle.properties`. `<dependencyManagement>` is kept in the synthetic pom too (harmless). A dependency with an
EXPLICIT non-concrete version (range/dynamic/unresolved `$`) is skipped+warned; a version-less dependency with
no managing BOM is skipped+warned (`GRADLE_UNPARSEABLE_CONSTRUCT`); a BOM that cannot be fetched warns
`GRADLE_BOM_FETCH_FAILED` and the scan continues.

**Network (D-042).** The resolver step performs read-only HTTPS GETs of `.pom` files from ONE host only — Maven
Central (`repo.maven.apache.org`, already allowed by D-033) or the mirror in `SECSCAN_MAVEN_BASE_URL` — with TLS
verification always ON (never disabled) and cross-host redirects blocked. Fetches are bounded: per-POM timeout
15s, total cap 200 POMs/run, parent depth 8, import depth 6, 10 MB response cap, and an in-run cache. No other
host is contacted by the resolver; the rest of the scan keeps its existing network posture.

It resolves **every non-`buildSrc` dependency configuration**
(production and test) and lets OSV decide what to report — OSV is scope-agnostic (it reports a Maven
`<scope>test</scope>` dependency too), so this matches how M4 scans real poms; a package in a production config
is always kept even if it also appears in a test config (keep wins). The principled dev/test exclusion switch
is M7. Only concrete versions are emitted; a missing/dynamic/range/unresolved version — and a construct the
static reader cannot follow (`buildSrc`, `apply from:`, `resolutionStrategy`, an unresolved version-catalog
alias) — is skipped and raises a `GRADLE_UNPARSEABLE_CONSTRUCT` scan warning, so the gap is visible, never
silent (D-030).

Because Gradle uses Maven coordinates, **Gradle findings surface in OSV's `Maven` ecosystem**. The manifest's
`syntheticPoms` array maps each synthetic pom's `/work` path to its originating `build.gradle` (repo-relative);
the parser rewrites OSV's `source.path` back to the build file before fingerprinting, so a Gradle SCA finding
anchors to the real build file. An OSV source path that is neither under the checkout nor in this map is
dropped (`UNRESOLVED_PATH`). The resolver is best-effort: any failure falls back to an empty map and the scan
still runs. Its unit tests (`gradle_resolver_test.py`) run on the host under `./gradlew build`
(`gradleResolverTest`), not inside the image.

## Output format (`/out`)

`result.json`:

```json
{ "schemaVersion": 1, "cloneFailed": false, "commit": "<sha>",
  "toolVersions": { "git": "2.x", "betterleaks": "1.7.3", "osv-scanner": "2.7.0" },
  "warnings": [ { "code": "MISSING_GRADLE_LOCKFILE", "message": "…", "filePath": "build.gradle" } ],
  "syntheticPoms": [ { "syntheticPath": "/work/synthetic/0/pom.xml", "buildFile": "build.gradle" } ],
  "scanners": [ { "name": "secret", "status": "ok", "exitCode": 2, "durationMs": 1234,
                  "findingsFile": "secrets.json" } ] }
```

`secrets.json`:

```json
[ { "ruleId": "github-pat", "file": "config/application.properties", "startLine": 4,
    "secretHash": "<sha256-hex>", "preview": "ghp_" } ]
```
