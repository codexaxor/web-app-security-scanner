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

- OD-1 Gradle repos without lockfiles — needed before M4
- OD-2 .NET repos without `packages.lock.json` — needed before M4
- OD-3 Private package registries — needed before M4
- OD-4 GitLab instance, version and groups — RESOLVED by D-013 (version read at runtime)
- OD-5 Hosting for the worker — needed before M9
- OD-6 Product name
