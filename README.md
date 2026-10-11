# SecScan — Web App Security Scanner

Self-hosted code security scanner for our GitLab repositories, modelled on Aikido's repository scanning.
It finds vulnerable open-source dependencies, leaked secrets, insecure code patterns (SAST), infrastructure-as-code
misconfigurations and end-of-life runtimes, then shows one deduplicated, prioritized issue feed.

> Status: **planning complete, M0 (skeleton) next.** See [docs/SPEC.md](docs/SPEC.md) for the milestone plan.

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

1. Connect a GitLab group with a **read-only** group access token (`read_api`, `read_repository`).
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

## Quick start (available after M0)

Prerequisites: JDK 21, Docker, Node 20+.

```bash
cp .env.example .env            # set SECSCAN_ENCRYPTION_KEY: openssl rand -base64 32
docker compose up -d postgres
./gradlew build
docker build -t secscan-scanner scanner-image/
./gradlew :app:bootRun --args='--spring.profiles.active=api'
./gradlew :app:bootRun --args='--spring.profiles.active=worker'
cd ui && npm ci && npm run dev
```

## Contributing

- Branches: `main` (stable, tagged per milestone) ← `develop` (integration) ← `feature/m<N>-<slug>`.
  Never commit to `main` or `develop` directly. Details: [docs/WORKFLOW.md](docs/WORKFLOW.md).
- Commits: Conventional Commits (`feat(core): …`).
- Every PR: milestone report in `docs/reports/`, green CI, [review checklist](docs/REVIEW_CHECKLIST.md).
- Decisions: [docs/DECISIONS.md](docs/DECISIONS.md).

## Working with Claude Code

This repo is built milestone by milestone with Claude Code. Rules for the agent are in [CLAUDE.md](CLAUDE.md).

| Command | Does |
| --- | --- |
| `/start-milestone M<N>` | Creates the feature branch, reads the spec, proposes a plan, waits for approval |
| `/finish-milestone M<N>` | Builds, self-reviews, writes `docs/reports/M<N>.md`, pushes, opens PR to `develop` |
| `/review-pr M<N>` | Independent review of the branch against spec and checklist (run with the strongest model) |

## Documentation

- [docs/SPEC.md](docs/SPEC.md) — full MVP specification and milestones
- [docs/WORKFLOW.md](docs/WORKFLOW.md) — branching, commits, PRs, GitHub settings
- [docs/DECISIONS.md](docs/DECISIONS.md) — decision log and open decisions
- [docs/REVIEW_CHECKLIST.md](docs/REVIEW_CHECKLIST.md) — what every PR is checked against
- [docs/MILESTONE_REPORT_TEMPLATE.md](docs/MILESTONE_REPORT_TEMPLATE.md) — report format
- [docs/reference/](docs/reference/) — UI reference screenshots

## Security

This tool holds read access to source code and a list of its weaknesses. Never commit tokens or `.env`.
Report problems in this project privately to the maintainers, not as public issues.
