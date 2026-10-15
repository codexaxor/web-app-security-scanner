# SecScan — Web App Security Scanner

Self-hosted code security scanner for our GitLab repositories, modelled on Aikido's repository scanning.
It finds vulnerable open-source dependencies, leaked secrets, insecure code patterns (SAST), infrastructure-as-code
misconfigurations and end-of-life runtimes, then shows one deduplicated, prioritized issue feed.

> Status: **M0 (skeleton) and M1 (GitLab connection) done; M1b (project-mode & multiple connections) implemented.** See [docs/SPEC.md](docs/SPEC.md) for the milestone plan.

## What it scans

| Ecosystem | Dependencies | Secrets / SAST / IaC / EOL |
| --- | --- | --- |
| Java / Spring Boot | Maven `pom.xml` (full tree), Gradle with `gradle.lockfile` | Yes |
| .NET | `packages.lock.json`, `*.deps.json`, `packages.config` (`.csproj` direct deps as fallback) | Yes |
| JavaScript / TypeScript | npm, yarn, pnpm, bun lockfiles | Yes |

Engines: [OSV-Scanner](https://github.com/google/osv-scanner), [Gitleaks](https://github.com/gitleaks/gitleaks),
[Opengrep](https://github.com/opengrep/opengrep), [Checkov](https://github.com/bridgecrewio/checkov),
plus CISA KEV, FIRST EPSS and endoflife.date data.

## How it works

1. Connect one or more GitLab targets — a whole group or a single project — with a **read-only** access token
   (`read_api`, `read_repository`).
2. Pick repositories. They are scanned daily, on new commits (polled every 15 min), or on demand.
3. Each scan runs in a throwaway, hardened container; only findings are kept, never the code.
4. Findings are normalized, scored 0–100, auto-triaged with visible reasons, and grouped into issues that open,
   solve and reopen automatically.

Architecture, data model and rules: [docs/SPEC.md](docs/SPEC.md).

## Tech stack

Java 21 · Spring Boot 3 · Gradle · PostgreSQL 16 (data + job queue) · Docker · React + Vite + TypeScript

## Repository layout

```
core/            domain logic (fingerprints, scoring, triage, reconcile)
app/             Spring Boot app — profiles: api, worker
scanner-image/   scanner Docker image (pinned tools)
ui/              React frontend
fixtures/        synthetic vulnerable repos for tests
docs/            spec, workflow, decisions, milestone reports, UI reference
.claude/         Claude Code project commands
```

## Quick start

Prerequisites: JDK 21, Docker, Node 20+. Build with the committed Gradle wrapper (`./gradlew`); the host's
own Gradle/JDK version does not matter.

```bash
cp .env.example .env            # set SECSCAN_ENCRYPTION_KEY and SECSCAN_FINGERPRINT_KEY: openssl rand -base64 32
```

**Build the scanner image** (the worker launches it per scan; the tag must match `ScannerProperties.image`):

```bash
docker build -t secscan-scanner:8.21.2 scanner-image/
```

**Set up the scan work root** (where per-scan token files and `/out` live). The worker launches each scanner
container on the *host* Docker daemon, so the work root must be the **same absolute path** on the host and
inside the worker, and the worker must run as a user that can write it:

```bash
# In .env (see .env.example):
#   SECSCAN_SCAN_WORK_ROOT=/var/lib/secscan/scans
#   SECSCAN_UID=$(id -u)   SECSCAN_GID=$(id -g)        # the worker runs as this host user
#   DOCKER_GID=$(stat -c %g /var/run/docker.sock)      # so the worker can reach the Docker socket
sudo mkdir -p /var/lib/secscan/scans
sudo chown "$(id -u):$(id -g)" /var/lib/secscan/scans
```

The worker self-checks this on startup (work root writable, Docker reachable, scanner image present) and logs a
clear error and refuses to claim jobs until it is fixed.

**Run everything (postgres + api + worker + ui) in containers:**

```bash
docker compose up --build        # ui on :5173, api on :8080; worker is non-web (no port)
```

If a system Postgres already owns host port 5432, set `POSTGRES_HOST_PORT` in `.env` (e.g. `15432`) before
`docker compose up`; the app containers still reach Postgres at `postgres:5432` inside the compose network.

**Compose smoke test (isolated — never touches your dev database):**

```bash
scripts/compose-smoke.sh          # build + up + wait for api health + tear down
```

> ⚠️ Smoke/scratch compose runs **must** use a separate compose project — `docker compose -p secscan-smoke …` —
> which namespaces the volume (`secscan-smoke_postgres-data`) away from the default project. **Never run
> `docker compose down -v` on the default project**: it deletes the shared `postgres-data` volume and wipes your
> dev database. `scripts/compose-smoke.sh` enforces this (own project, ephemeral host ports, teardown scoped to
> `-p secscan-smoke`).

**Dev loop (hot reload, Postgres in a container, app and UI on the host):**

```bash
docker compose up -d postgres
./gradlew build                  # compiles + runs tests (Testcontainers needs Docker)
./gradlew :app:bootRun --args='--spring.profiles.active=api'
./gradlew :app:bootRun --args='--spring.profiles.active=worker'
cd ui && npm ci && npm run dev
```

The first admin is created on first `api` startup when `app_user` is empty and
`SECSCAN_BOOTSTRAP_ADMIN_EMAIL` / `SECSCAN_BOOTSTRAP_ADMIN_PASSWORD` are set (see `.env.example`).
`/actuator/health` is public; `/api/**` requires HTTP Basic. For the host dev loop the worker runs as your
own user, so the work root just needs to exist and be writable by you.

## Contributing

- Branches: `main` (stable, tagged per milestone) ← `develop` (integration) ← `feature/m<N>-<slug>`, merged
  locally by a human after review. No pull requests or CI for now — the local `./gradlew build` is the gate and
  GitHub is only a backup. Details: [docs/WORKFLOW.md](docs/WORKFLOW.md).
- Commits: Conventional Commits (`feat(core): …`).
- Every milestone: report in `docs/reports/`, local `./gradlew build` green, [review checklist](docs/REVIEW_CHECKLIST.md).
- Decisions: [docs/DECISIONS.md](docs/DECISIONS.md).

## Working with Claude Code

This repo is built milestone by milestone with Claude Code. Rules for the agent are in [CLAUDE.md](CLAUDE.md).

| Command | Does |
| --- | --- |
| `/start-milestone M<N>` | Creates the feature branch, reads the spec, proposes a plan, waits for approval |
| `/finish-milestone M<N>` | Builds, self-reviews, writes `docs/reports/M<N>.md`, commits and pushes the feature branch |
| `/review-pr M<N>` | Independent review before local merge (run with the strongest model) |
| `/handover` | Read-only: builds the architect context bundle (outside the repo) for the claude.ai project knowledge |

## Documentation

- [docs/SPEC.md](docs/SPEC.md) — full MVP specification and milestones
- [docs/WORKFLOW.md](docs/WORKFLOW.md) — branching, commits, local merges
- [docs/DECISIONS.md](docs/DECISIONS.md) — decision log and open decisions
- [docs/REVIEW_CHECKLIST.md](docs/REVIEW_CHECKLIST.md) — what every milestone is checked against before a local merge
- [docs/MILESTONE_REPORT_TEMPLATE.md](docs/MILESTONE_REPORT_TEMPLATE.md) — report format
- [docs/reference/](docs/reference/) — UI reference screenshots

## Security

This tool holds read access to source code and a list of its weaknesses. Never commit tokens or `.env`.
Report problems in this project privately to the maintainers, not as public issues.

GitLab tokens are encrypted at rest with `SECSCAN_ENCRYPTION_KEY` (AES-256-GCM). The key must base64-decode to
32 bytes (`openssl rand -base64 32`); an invalid key fails startup, and saving a connection without a key
returns a clear error (tokens are never stored unencrypted). **Changing `SECSCAN_ENCRYPTION_KEY` makes already
stored tokens unreadable — you must reconnect GitLab.** Key rotation is a later feature.

Secret findings are stored as a **keyed** hash: `secret_hash = HMAC-SHA256(SECSCAN_FINGERPRINT_KEY, the
scanner's SHA-256 of the value)`, plus a masked preview — never the value and never the bare SHA-256 (D-027).
Keying the digest with a server-held key means a copy of the database alone cannot confirm a guessed secret.
`SECSCAN_FINGERPRINT_KEY` must base64-decode to 32 bytes (`openssl rand -base64 32`); an invalid key fails
startup, and the worker returns a clear error if it must store a secret finding while the key is unset.
**Changing it changes all future secret fingerprints**, so secrets seen before the change reopen as new on the
next scan.

Outbound GitLab calls are restricted to the hosts in `SECSCAN_GITLAB_ALLOWED_HOSTS` (comma-separated; empty =
deny all) and must use `https` (loopback hosts may use `http` for a local or mock GitLab) — an SSRF guard
(D-018). Set it to your GitLab host, e.g. `gitlab.celloscope.net`.
