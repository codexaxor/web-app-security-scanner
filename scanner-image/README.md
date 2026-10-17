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

## Output format (`/out`)

`result.json`:

```json
{ "schemaVersion": 1, "cloneFailed": false, "commit": "<sha>",
  "toolVersions": { "git": "2.x", "betterleaks": "1.7.3", "osv-scanner": "2.7.0" },
  "scanners": [ { "name": "secret", "status": "ok", "exitCode": 2, "durationMs": 1234,
                  "findingsFile": "secrets.json" } ] }
```

`secrets.json`:

```json
[ { "ruleId": "github-pat", "file": "config/application.properties", "startLine": 4,
    "secretHash": "<sha256-hex>", "preview": "ghp_" } ]
```
