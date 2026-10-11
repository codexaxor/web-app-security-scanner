# SecScan — instructions for Claude Code

SecScan connects to our GitLab, scans selected repositories (Java/Spring Boot, .NET, JavaScript/TypeScript) for
vulnerable dependencies, secrets, SAST issues, IaC misconfigurations and end-of-life runtimes, and shows one
deduplicated, prioritized issue feed. It is modelled on Aikido's repository scanning.

Read these before any work:
- `docs/SPEC.md` — what to build (read the section for your milestone)
- `docs/WORKFLOW.md` — branches, commits, local merges
- `docs/DECISIONS.md` — decisions already made; do not undo them

If the spec, this file and a decision disagree, stop and ask.

## Architecture (short)

- One Spring Boot codebase, two profiles:
  - `api`: REST for the UI, scheduler, GitLab commit polling, KEV/EPSS/EOL sync, re-score jobs.
  - `worker`: claims `scan_job` rows, launches one disposable scanner container per scan, parses, enriches,
    scores and reconciles findings.
- PostgreSQL is both the database and the job queue (`SELECT … FOR UPDATE SKIP LOCKED`). No Redis, no broker.
- Scanner = separate Docker image (`scanner-image/`) with git, OSV-Scanner, Gitleaks, Opengrep, Checkov. Writes
  JSON/SARIF to `/out`, destroyed after every scan. Only `/out` and logs leave it.
- React + Vite + TypeScript UI in `ui/`.

## Repository layout

```
core/            domain logic: fingerprints, scoring, triage, reconcile — plain Java, no Spring
app/             Spring Boot app (profiles: api, worker), entities, REST, jobs
scanner-image/   Dockerfile + run-scan.sh (pinned tool versions and checksums)
ui/              React app
fixtures/        tiny synthetic vulnerable repos per ecosystem, for integration tests
docs/            SPEC, WORKFLOW, DECISIONS, reports/, reference/ (Aikido screenshots)
```

## Stack and conventions

- Java 21, Spring Boot 3, Gradle (Kotlin DSL) multi-module. Commit `gradle.lockfile`s for our own build.
- Base package `com.company.secscan`.
- PostgreSQL 16, Flyway in `app/src/main/resources/db/migration`, named `V{n}__{description}.sql`.
  Never edit a merged migration; add a new one.
- Entities in `app`; pure logic in `core`, unit-testable without Spring.
- REST under `/api/v1`. DTOs are records. Paginate every list endpoint server-side.
- Tests: JUnit 5, AssertJ, Testcontainers for Postgres. Scanner integration tests run against `fixtures/`.
- Logging: SLF4J. Never log tokens, secret values or file contents.

## Commands

```
docker compose up --build          # full stack: postgres + api + worker + ui
docker compose up -d postgres      # just the DB, for the dev loop below
./gradlew build
./gradlew :app:bootRun --args='--spring.profiles.active=api'
./gradlew :app:bootRun --args='--spring.profiles.active=worker'
docker build -t secscan-scanner scanner-image/
cd ui && npm ci && npm run dev
```

Update this section whenever a command changes.

## Domain rules — never break

1. Every business table has `workspace_id`, and every query filters by it.
2. `issue_group` (what users triage) vs `sub_issue` (one finding, stable fingerprint = SHA-256 of fields joined
   with `|`):
   - SCA: package + vuln id (CVE, else GHSA/OSV) + manifest path
   - SAST: rule id + file path + hash of normalized matched snippet — NEVER the line number
   - Secret: rule id + file path + hash of the secret value
   - IaC: rule id + file path + resource name
   - EOL: product + detected cycle + file path
3. Status changes only through reconcile after a scan. Seen → open (reopen if solved). Missing from a scan where
   its scanner succeeded → solved. Scanner failed/timed out → its findings untouched. Ignored stays ignored.
4. Every status change writes a `triage_event` (actor, reason code, scan id).
5. Every auto-ignore has a reason code: `DEV_DEPENDENCY`, `TEST_SCOPE`, `EXCLUDED_PATH`, `SECRET_PLACEHOLDER`,
   `LOW_EPSS`, `IGNORE_RULE`.
6. Severity is 0–100 (Critical 90–100, High 70–89, Medium 40–69, Low 1–39); every adjustment goes in
   `score_breakdown`.

## Security rules — never break

- Never execute code from scanned repositories (no `mvn`, `gradle`, `dotnet restore`, `npm install` on them).
- Scanner containers: non-root, read-only root FS, `--cap-drop=ALL`, `no-new-privileges`, CPU/memory/PID limits,
  hard timeout. Never mount the Docker socket into the scanner container.
- GitLab tokens: AES-256-GCM at rest. Decrypted in-memory only where needed — the api (connection test, project
  listing, commit polling) and the worker — never logged, and never in env vars or URLs. The scanner container
  only ever receives the token via a one-time file, never an env var (D-014).
- Secret findings: SHA-256 hash + masked preview (first 4 chars). Never store the value.
- GitLab access is read-only (`read_api`, `read_repository`) unless `docs/DECISIONS.md` says otherwise.
- Never commit real tokens, real company code or `.env` files. Fixtures are synthetic; fake keys must be
  obviously fake (e.g. AWS's documented example key).

## Git rules — never break

- Never commit to, merge into, or push `main` or `develop`. Work on `feature/m<N>-<slug>` (or `fix/…`, `docs/…`)
  branched from `develop`. Details in `docs/WORKFLOW.md`.
- Small commits in Conventional Commits format: `feat(core): add SCA fingerprint`, `test(app): …`, `fix(…)`.
- Push the feature branch as a backup (`git push -u origin HEAD`).
- Never force-push or rewrite history.
- Merging is done by the human locally after review; Claude Code never merges.

## How to work

1. Start a milestone with `/start-milestone M<N>`: read spec, write a plan, wait for approval.
2. Implement in small slices; each slice ends with `./gradlew build` green and a commit.
3. Every new behavior has a test. `core` logic (fingerprints, reconcile, scoring, triage) needs unit tests.
4. Never weaken, skip or delete a test to make it pass. If a test seems wrong, stop and say so.
5. Scanner output parsing: save a real sample under `app/src/test/resources/samples/<tool>/` and test against it.
6. New dependency, service or tool → one-line entry in `docs/DECISIONS.md`.
7. Anything the spec doesn't cover → ask, or record your choice in `docs/DECISIONS.md` and flag it in the report.
8. `/finish-milestone M<N>`: build, report, commit, push the feature branch. No PR.
9. Don't start the next milestone in the same session.

## Definition of done

- Local `./gradlew build` passes (including Testcontainers); `npm run build` passes if `ui/` changed.
- Migrations apply to an empty database.
- The milestone's "Done when" is proven by a named test or a documented manual check.
- `docs/reports/M<N>.md` written; README/CLAUDE.md/SPEC updated if behavior or commands changed.
