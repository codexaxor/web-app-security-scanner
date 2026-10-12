# scanner-image

The disposable scanner container (M2: git + Gitleaks). One container per scan, destroyed afterwards; only
`/out` and the container logs leave it.

## Build

```
docker build -t secscan-scanner:8.21.2 scanner-image/
```

The pinned tag matches the Gitleaks version. The build is also wired into the Gradle test run (the
`buildScannerImage` task builds this image before `ScanPipelineIT` if the tag is missing).

## Runtime contract

The worker's launcher runs this image with these mounts and env (see `ScannerLauncher`):

| Path / env | Purpose |
| --- | --- |
| `/work` | tmpfs scratch (clone + raw Gitleaks output); dies with the container |
| `/out` | bind mount; sanitized `result.json` + `secrets.json` are written here |
| `/run/secrets/gitlab-token` | optional read-only one-time token file (absent for `file://` clones) |
| `SCAN_CLONE_URL` | repo to clone (`https://…` or `file:///src`) |
| `SCAN_BRANCH` | branch to clone (default branch) |
| `SCAN_CLONE_DEPTH` | clone depth (default `1`) |

The token is never put in the clone URL, an env var, or the command line, and no secret value is ever written
to `/out` or the logs: `run-scan.sh` computes a SHA-256 + 4-char preview inside the container and emits only
those.

## Output format (`/out`)

`result.json`:

```json
{ "schemaVersion": 1, "cloneFailed": false, "commit": "<sha>",
  "toolVersions": { "git": "2.x", "gitleaks": "8.21.2" },
  "scanners": [ { "name": "secret", "status": "ok", "exitCode": 1, "durationMs": 1234,
                  "findingsFile": "secrets.json" } ] }
```

`secrets.json`:

```json
[ { "ruleId": "aws-access-token", "file": "config/application.properties", "startLine": 3,
    "secretHash": "<sha256-hex>", "preview": "AKIA" } ]
```
