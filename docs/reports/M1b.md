# Milestone report: M1b Project-mode & multiple GitLab connections

Branch: `feature/m1b-project-connections` · Commit: `4f2d66c` (+ this docs commit) · Date: 2026-10-12 ·
Model(s) used: Claude Opus 4.8

M1b is not a numbered row in the SPEC backlog; its scope comes from the user's brief and the two decisions M1
already logged (D-016 "multi-connection is a later additive change", D-019 "reassign/deactivate implemented in
M1b"). The "Done when" below is the acceptance set agreed in the approved plan.

## Done-when check

There is no production token in this environment, so the real-GitLab leg is a manual step (as in M1); the
automated proxies run the full flow against a mocked GitLab (WireMock + Testcontainers Postgres 16).

| Criterion | Proven by (test) | Result |
| --- | --- | --- |
| A project-mode connection resolves one project (path or URL), verifies scopes, lists & selects it | `ProjectConnectionIT` — create/test, pasted URL, list-single, select-single, not-a-member (7) | PASS |
| Project-mode errors map clearly (not found / missing scopes / expired) | `ProjectConnectionIT.projectNotFoundIsReported`/`missingScopeIsReported`/`expiredTokenIsReported`; `GitLabClientTest.missingProjectMapsToProjectNotFound`/`forbiddenProjectResolveMapsToMissingScopes` | PASS |
| A group + a project connection coexist in one workspace; duplicates rejected | `MultiConnectionIT` (group+project coexist, list; duplicate project rejected) | PASS |
| A project reachable via two connections is stored once; first connection keeps ownership | `DedupAcrossConnectionsIT.sameProjectSelectedViaTwoConnectionsIsStoredOnceAndKeepsFirstOwner` | PASS |
| Removing a connection reassigns its repos to a connection that still exposes them, else deactivates; rows kept (D-020) | `ConnectionRemovalIT` — reassign-to-group, deactivate-when-none, provisional-confirm, provisional-deactivate, missing-connection (5) | PASS |
| Token rotation on an existing connection (same target) keeps selected repositories; different target rejected | `GitLabConnectionIT.updateRotatesTokenAndKeepsSelectedRepositories`/`updatingToADifferentTargetIsRejected`/`creatingTheSameGroupTwiceIsRejected` | PASS |
| V5 applies to an empty DB; multiple connections allowed; target check enforced | `BaselineMigrationIT.workspaceSupportsManyConnectionsOfDifferentTargets`/`checkConstraintRejectsAGroupRowWithoutGroupColumns` | PASS |
| Token stored only as ciphertext; never logged | `ProjectConnectionIT.createResolvesAndStoresTheProject` (ciphertext ≠ token), `GitLabConnectionIT.createEncryptsTokenAndStoresConnection`; no `log.*` statement references a token (verified) | PASS |
| `./gradlew build` and `npm run build` green | Evidence below | PASS |
| Real GitLab group **and** a single project connect over VPN; repos persist | **Manual — NOT YET RUN** (needs token + VPN); see Known gaps | PENDING |

## What was built

- `app/.../gitlab/GitlabConnection.java` — target-aware entity: `targetType` (`group`/`project`), nullable
  `gitlabGroupId`/`groupFullPath`, `gitlabProjectId`/`projectFullPath`, `lastVerifiedAt`; `forGroup`/`forProject`
  factories, `updateToken`, `targetId()`/`targetPath()`.
- `app/.../gitlab/GitlabConnectionRepository.java` — `findByWorkspaceId` (list), `findByIdAndWorkspaceId`,
  `findByWorkspaceIdAndGitlabGroupId`, `findByWorkspaceIdAndGitlabProjectId`.
- `app/.../gitlab/GitLabClient.java` — `resolveProject` (`GET /projects/:url-encoded-path`); 404 →
  `PROJECT_NOT_FOUND` with a project-specific message. Same no-redirect JDK client / host policy as M1.
- `app/.../gitlab/GitLabProjectPath.java` — path-or-URL normalization for project paths (strips host with the
  allowlist check and `/-/…` suffix; requires at least one namespace segment; no `groups/` stripping).
- `app/.../gitlab/GitLabConnectionService.java` — `test`/`create`/`update`(PUT)/`list`/`require`/`delete`, keyed
  on the resolved target. `create` rejects a duplicate target (`CONNECTION_EXISTS`); `update` rotates the token
  only when the path resolves to the same target (`CONNECTION_TARGET_MISMATCH` otherwise). `delete` reassigns or
  detaches+deactivates each owned repository (never deletes rows), then removes the connection row.
- `app/.../gitlab/GitLabError.java` — adds `PROJECT_NOT_FOUND`, `CONNECTION_EXISTS`, `CONNECTION_TARGET_MISMATCH`,
  `CONNECTION_NOT_FOUND`; removes M1's single-connection `CONNECTION_GROUP_MISMATCH`.
- `app/.../gitlab/api/{ConnectionRequest,UpdateConnectionRequest,ConnectionTestResult,ConnectionView,ConnectionRemovalResult}.java`
  — `targetType` + project fields; connection id in the view; removal counts. None carry the token.
- `app/.../gitlab/GitLabController.java` — `/api/v1/gitlab/connections`: `GET` (list, paginated), `POST`
  (create), `POST /test`, `PUT /{id}`, `DELETE /{id}`, `GET /{id}/projects`. Old `/connection` endpoints removed.
- `app/.../repository/Repository.java` — `inactiveReason`, `reassignedAt`, nullable `connectionId`;
  `activate`/`deactivate(reason)`/`detachAndDeactivate(reason)`/`reassignTo`/`confirmConnection`/`isProvisional`.
- `app/.../repository/RepositoryService.java` — per-connection `listProjects`/`select` (group projects, or the
  single target project); dedup keeps the first connection's ownership; on each use, confirms or (on
  401/403/404) deactivates provisionally reassigned repos.
- `app/.../repository/RepositoryConnectionGuard.java` — `confirm`/`deactivate` in `REQUIRES_NEW` transactions so
  a provisional deactivation commits even when the triggering use then fails and rolls back.
- `app/.../repository/RepositoryController.java` + `api/{RepositoryView,SelectRepositoriesRequest}.java` —
  `select` takes `connectionId`; the view shows the owning connection and any inactive reason.
- `app/.../api/ApiExceptionHandler.java` — HTTP mapping for the new error codes (404 / 409).
- `ui/src/{api.ts,App.tsx}`, `ui/src/components/{ConnectGitLab,SelectRepositories,SignInGate}.tsx` — connections
  list + add (group/project) + inline-confirm remove with affected-repo count; per-connection project selection;
  selected-repositories table with connection and inactive reason.

## Migrations

`app/src/main/resources/db/migration/V5__multiple_connections_and_targets.sql` (V1–V4 untouched):

```sql
-- M1b: allow many GitLab connections per workspace, each targeting either a whole group or a single project.
-- A repository still exists once per workspace (V4's uq_repository_workspace_project); connection_id records
-- the connection used to reach it. Removing a connection never deletes repository rows (scan history hangs off
-- them from M2) — its repositories are reassigned to another connection that still exposes them, or deactivated.

-- A workspace may now hold several connections, so the one-per-workspace constraint is dropped.
alter table gitlab_connection
    drop constraint uq_gitlab_connection_workspace;

alter table gitlab_connection
    add column target_type       text        not null default 'group'
                                   check (target_type in ('group', 'project')),
    add column gitlab_project_id bigint,
    add column project_full_path text,
    -- Set on every successful test-then-save / token rotation; used to break reassignment ties.
    add column last_verified_at  timestamptz;

-- Group columns are only required for group-mode connections (project-mode rows leave them null).
alter table gitlab_connection
    alter column group_full_path drop not null,
    alter column gitlab_group_id drop not null;

-- Exactly one target is populated, matching the mode.
alter table gitlab_connection
    add constraint ck_gitlab_connection_target check (
        (target_type = 'group'
            and gitlab_group_id is not null and group_full_path is not null
            and gitlab_project_id is null and project_full_path is null)
        or (target_type = 'project'
            and gitlab_project_id is not null and project_full_path is not null
            and gitlab_group_id is null and group_full_path is null)
    );

-- At most one connection per workspace to the same group, and one per workspace to the same project
-- (a workspace may still hold one group connection plus several single-project connections).
create unique index uq_gitlab_connection_group
    on gitlab_connection (workspace_id, gitlab_group_id)
    where target_type = 'group';
create unique index uq_gitlab_connection_project
    on gitlab_connection (workspace_id, gitlab_project_id)
    where target_type = 'project';

-- Why a repository is inactive. Null while active; 'NO_ACCESSIBLE_CONNECTION' when no connection can reach it.
-- reassigned_at marks a provisional reassignment: cleared on the next successful use, or turned into a
-- deactivation if that use returns 401/403/404.
alter table repository
    add column inactive_reason text,
    add column reassigned_at   timestamptz;

-- A repository outlives its connection: when the last connection that could reach it is removed with no
-- replacement, the row is kept (scan history) but detached (connection_id null) and deactivated.
alter table repository
    alter column connection_id drop not null;
```

Applies on an empty DB (Flyway runs from scratch in every IT; `BaselineMigrationIT` asserts the new columns,
the target check constraint, and that a group + a project connection coexist for one workspace).

## API changes

All under `/api/v1`, HTTP Basic (D-010). List endpoints paginate server-side. **The singular M1 `/connection`
and `/projects` endpoints are removed (no aliases).**

| Method | Path | Example request / response |
| --- | --- | --- |
| POST | `/api/v1/gitlab/connections/test` | `{ "baseUrl":"…", "token":"…", "targetType":"project", "path":"ns/app" }` → `{ "ok":true, "targetType":"project", "gitlabVersion":"17.x", "projectId":102, "projectFullPath":"ns/app", "tokenScopes":[…], "tokenExpiresAt":"2027-06-30", "expiringSoon":false }`; failure → HTTP 200 `{ "ok":false, "errorCode":"PROJECT_NOT_FOUND", "message":"…" }` |
| POST | `/api/v1/gitlab/connections` | Same body/response as test; on success persists (token encrypted). Duplicate target → `ok:false, CONNECTION_EXISTS`; key missing → 409 `ENCRYPTION_NOT_CONFIGURED` |
| PUT | `/api/v1/gitlab/connections/{id}` | `{ "baseUrl":"…", "token":"…", "path":"…" }` → rotates the token for the same target (keeps repos); different target → `ok:false, CONNECTION_TARGET_MISMATCH`; unknown id → 404 `CONNECTION_NOT_FOUND` |
| GET | `/api/v1/gitlab/connections?page=&size=` | → `PagedResponse<ConnectionView>` (id, targetType, baseUrl, group/project id+path, scopes, expiry) |
| DELETE | `/api/v1/gitlab/connections/{id}` | → `{ "reassigned": n, "deactivated": m }`; repository rows are kept |
| GET | `/api/v1/gitlab/connections/{id}/projects?page=&size=` | → `PagedResponse<ProjectView>` (the group's projects, or the single target project) |
| POST | `/api/v1/repositories` | `{ "connectionId":"…", "gitlabProjectIds":[101,104] }` → `List<RepositoryView>` (now incl. connection id/type/label + inactiveReason) |
| GET | `/api/v1/repositories?page=&size=` | → `PagedResponse<RepositoryView>` of selected repos with their connection and inactive reason |

## Deviations from SPEC.md

- SPEC's `gitlab_connection` is "one per GitLab instance/group"; M1b makes it many-per-workspace with a
  `target_type` of group or project, plus `gitlab_project_id`/`project_full_path`/`last_verified_at`
  (**D-020**; anticipated by D-016).
- `repository.connection_id` is now **nullable**: a repository outlives its connection, and an orphaned repo
  (no connection can reach it) is detached and deactivated rather than deleted (**D-020** / D-019).
- CLAUDE.md domain rule 4 ("every status change writes a `triage_event`") is about issue/sub-issue status. A
  repository's active→inactive / reassignment is **not** an issue status change and the `triage_event` table
  does not exist until M3; these are recorded via SLF4J for now (noted in D-020). No issue statuses exist yet.
- Nothing from M2+ was built (no scanning).

## Decisions made (also added to DECISIONS.md)

- **D-020** Multiple connections, project-mode targets, and reassignment on removal: the schema change, the
  `/connections` REST surface (create vs. PUT-rotate), the offline reassignment order (same project id → longest
  group-namespace prefix → newest-verified tie), detach+deactivate when none, provisional reassignment resolved
  on next use, and SLF4J audit until M3's `triage_event`.

## Evidence

- `./gradlew test --rerun-tasks` (local): **88 tests run, 0 failed, 0 skipped** (core 7; app 81).
  - core: `TokenCipherTest` 7.
  - app: `GitLabClientTest` 17, `GitLabConnectionIT` 10, `ProjectConnectionIT` 7, `GitLabProjectPathTest` 7,
    `GitLabGroupPathTest` 6, `MultiConnectionIT` 2, `ConnectionRemovalIT` 5, `DedupAcrossConnectionsIT` 1,
    `RepositorySelectionIT` 5, `BaselineMigrationIT` 5, `GitLabHostPolicyTest` 5, `ApiSecurityIT` 2,
    `AdminBootstrapIT` 3, `AdminBootstrapTest` 4, `TokenCipherHolderTest` 2.
- `cd ui && npm run build`: green (tsc + vite, 34 modules).
- No scan milestone, so no findings-per-fixture table.

## Files the reviewer must read in full

- `app/.../gitlab/GitLabConnectionService.java` — target resolution, create vs. PUT-rotate, and the
  reassign/detach logic on `delete` (offline target selection, provisional flag).
- `app/.../repository/RepositoryService.java` + `RepositoryConnectionGuard.java` — per-connection list/select,
  dedup first-wins ownership, and the `REQUIRES_NEW` confirm/deactivate on use.
- `app/.../gitlab/GitLabProjectPath.java` — project path/URL normalization and host rejection.
- `app/.../gitlab/GitLabClient.java` — `resolveProject` (token only in header, no redirects, host policy).
- `app/.../api/ApiExceptionHandler.java` — error→status mapping; no token in bodies.
- `app/src/main/resources/db/migration/V5__multiple_connections_and_targets.sql`.

## Known gaps / TODOs

- **Real-GitLab Done-when unverified** — needs the token + VPN: connect a group **and** a single project, select
  from each, confirm both persist with the right `connection_id`, and confirm the ciphertext is not `glpat…`.
- A concurrent duplicate-target `create` relies on the partial unique index as a backstop; the race loser
  surfaces as a `DataIntegrityViolationException` (HTTP 500) rather than a clean `CONNECTION_EXISTS`. Harmless at
  single-user local scale; tighten if it ever matters.
- Carried from M1: GitLab I/O still runs inside `@Transactional` in `create`/`update`/`delete` (short network
  calls hold a DB connection). `listProjects` is intentionally **not** transactional so a provisional
  deactivation can commit. To be addressed with the M2 transaction-boundary cleanup.
- Reassignment/deactivation are logged, not persisted as events; a durable record waits for M3's `triage_event`.
- Single workspace (default) throughout; multi-tenant auth is M10.
