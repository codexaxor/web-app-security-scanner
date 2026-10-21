# M8 (UI) — Phase B work log

Branch `feature/m8-ui` from `develop` @ `a113fc1`. Autonomous build loop S0→S9.
No `/finish-milestone` (Sajal's real-GitLab walkthrough comes first). No migration, no scanner/hardening change.

Gate per slice: G1 `./gradlew build`; G2 `cd ui && npm ci && npm run build && npm run typecheck && npm test && guard`;
G3 `npm run e2e` (S0, S3–S9; S1/S2 run the existing-screens smoke); G4 clean git, no secrets staged.

## Approved-change tracker (plan changes 1–10)

| # | Change | Slice | Commit | Closed by (named test) |
|---|---|---|---|---|
| 1 | Repo activation rules + 409s | S1 | S1 | `RepositorySettingsIT` (round trip; detached→409 unchanged; scan-on-inactive→409 no scan) |
| 2 | includeInactive default false (feed/summary/dashboard) | S2 | S2 | `FeedSearchIT`, `DashboardSummaryIT` (inactive excluded + line) |
| 3 | 7-day counts: type+repo filters only, New=created_at<7d | S2 | S2 | `FeedSummaryIT` (window boundary; view-independent) |
| 4 | nginx headers `always` + all locations | S0 | S0 | `e2e security.spec.ts` (5 cases: /, deep link, asset, 404, /api) |
| 5 | rescoreRepository same lock/guard + concurrency test | S1 | S1 | `SensitivityRescoreIT`, `SensitivityRescoreConcurrencyIT` |
| 6 | pin node + playwright by digest; pw non-root, no socket | S0 | S0 | `ui/Dockerfile` digest; `e2e/run-e2e.mjs` (pwuser, no socket, ro mount) |
| 7 | workspace-2 complete seed dataset | S0/S2 | S2 | `e2e/fixtures/seed.sql` (complete ws2 + ws1; oracle in S4+) |
| 8 | path-scope ignore matches IgnoreMatcher format | S6 | _pending_ | _pending_ |
| 9 | error handler: no stack traces; 401 entrypoint unchanged | S1 | S1 | `ApiErrorBodyIT` (400/404/409 shape, no trace; 401 no WWW-Authenticate) |
| 10 | per-run e2e pg pw; ScanCreatedAtIT→R1/R4; post-signin route restore | S0/S1/S3 | S0,S1 | `ScanCreatedAtIT` (R1/R4); route restore in S3 |

## Slices

### S0 — Toolchain + tooling + e2e harness skeleton ✓
Upgraded the 3 existing screens to the pinned toolchain (react 19.2.9, vite 8.1.6, @vitejs/plugin-react 6.1.2,
ts 6.0.3); added react-router 8.5.0 + @tanstack/react-query 5.104.5 (runtime) and vitest 4.1.12 / jsdom 28.1.0 /
@testing-library/react 16.3.3 / @playwright/test 1.64.1 / pg 8.23.1 (dev). Bumped `ui/Dockerfile` to
`node:22.23.4-alpine@sha256:ce8d…` and copied the security-headers snippet. CSP-safe Vite build
(`modulePreload.polyfill=false` → single external script). nginx headers on document locations only (Spring
Security already sets nosniff on /api). Guard script, e2e harness (compose, seed scaffold, Playwright-in-Docker
as pwuser, oracle, per-run secrets), D-065..D-072 + deferred register.

- Commits: _pending (this commit)_
- G1 `./gradlew build` → BUILD SUCCESSFUL (backend unchanged). G2 → npm ci + build + typecheck + 2 vitest +
  guard all green. G3 `npm run e2e` → 7 Playwright passed (5 security headers + 2 onboarding regression), stack
  torn down with `-v`, nothing left running. G4 clean.
- Problems hit + fix: (a) npm 10.9.4 `edgesOut` arborist crash on vite 8's devtools optional-peer graph →
  generate the lockfile with npm 12; `npm ci` (the gate) works under npm 10. (b) initially omitted
  `@playwright/test` from deps → added. (c) Playwright-in-Docker uid: pwuser(1001) vs host-owned artifacts →
  run the local pw binary directly (no npx/npm cache) + `chmod 0777 artifacts`. (d) outputDir was the mount
  root (can't rmdir) and clashed with the html report → both moved to distinct subdirs. (e) nginx /api double
  `nosniff` (Spring Security + nginx) → scope headers to document locations only.

### S1 — Backend reads part 1 + settings + error-body/createdAt fixes ✓
OD-14 items 1 (repository rollups via `RepositorySummaryService`, computed in SQL), 2 (`GET /scans`,
`GET /scans/{id}/warnings`), 8 (`@Generated(INSERT)` on `scan.created_at` + `ignore_rule.created_at`;
`TriageService` now `saveAndFlush`es a new rule so its createdAt is read back). OD-15/D-070
`PATCH /repositories/{id}` with activation rules (change 1) and an immediate per-repo re-score for sensitivity
(`ReScoreService.rescoreRepository`, change 5). Error-body normalization (change 9):
`ResponseStatusException`/`MethodArgumentNotValidException` → `{error,message}`, no stack trace; the JSON 401
entrypoint is unchanged.

- New: `RepositorySummaryService`, `RepositorySettingsRequest`, `ScanWarningView`; PATCH + `/scans` + warnings
  endpoints; `ReScoreService.rescoreRepository`; 3 native rollup queries on `RepositoryRepository`.
- Tests: `RepositorySettingsIT`, `SensitivityRescoreIT`, `SensitivityRescoreConcurrencyIT`, `ScanWarningsApiIT`,
  `ScanCreatedAtIT`, `RepositorySummaryIT` (= direct SQL + workspace isolation), `ApiErrorBodyIT`.
- Problem: `createRule` returned a null createdAt (plain `save` defers the INSERT so `@Generated(INSERT)` had
  nothing to read) — fixed to `saveAndFlush` (the proven `ScanEnqueuer` pattern).

### S2 — Backend reads part 2 (feed, summary, events, panel rule fields, dashboard) ✓
OD-14 item 3: `GET /issue-groups` gains `view=focus|all`, severity `band`, `repositoryPath` per row, the
documented sort (`severity_score desc NULLS LAST, first_seen_at desc, id desc`) and `includeInactive`
(default false, change 2). Item 4: `GET /issue-groups/summary` — band group counts + last-7-day
New/Solved/Auto-ignored as distinct findings, honouring type+repo only, not band/view (change 3). Item 5:
`GET /sub-issues/{id}/events`. Item 6: `SubIssueView` gains `ignoreRuleId/ruleScope/ruleReason/ruleCreatedBy`
(null for auto causes/open). Item 7: `GET /dashboard/summary` (group + finding bands, type breakdown, scan
health, inactive line). All counts in SQL (JPQL aggregates), workspace-scoped, inactive excluded by default.

- The old M3 `IssueService.listGroups(repo,type,status,…)` raw-status filter is replaced by the feed
  semantics (solved never shown); `IssueApiIT` updated to the new contract (not weakened — a documented
  behaviour change, OD-14).
- change 7: `ui/e2e/fixtures/seed.sql` now carries the complete fixture set — 64 bulk groups (forces
  pagination > 50), every type/band, every reason code, KEV+EPSS/secret(4-char)/XSS-probe rows, manual+global+
  secscan.yml rules, scans completed/partial/failed with warnings, triage_events in/out of 7 days, an inactive
  repo with an open finding, and a COMPLETE workspace 2 that must never leak. The UI oracle asserts against it
  from S4.
- Tests: `FeedSearchIT`, `FeedSummaryIT`, `SubIssuePanelIT`, `DashboardSummaryIT` (all = direct SQL + workspace
  isolation), updated `IssueApiIT`. Gate: `./gradlew clean build --rerun-tasks`; e2e smoke (seed applies,
  api rebuilt).
