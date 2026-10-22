# M8 (UI) — Phase B work log

Branch `feature/m8-ui` from `develop` @ `a113fc1`. Autonomous build loop S0→S9.
No `/finish-milestone` (Sajal's real-GitLab walkthrough comes first). No migration, no scanner/hardening change.

Gate per slice: G1 `./gradlew build`; G2 `cd ui && npm ci && npm run build && npm run typecheck && npm test && guard`;
G3 `npm run e2e` (S0, S3–S9; S1/S2 run the existing-screens smoke); G4 clean git, no secrets staged.

## Approved-change tracker (plan changes 1–10)

| # | Change | Slice | Commit | Closed by (named test) |
|---|---|---|---|---|
| 1 | Repo activation rules + 409s | S1 | af829d7 | `RepositorySettingsIT` (round trip; detached→409 unchanged; scan-on-inactive→409 no scan) |
| 2 | includeInactive default false (feed/summary/dashboard) | S2 | fffe5de | `FeedSearchIT`, `DashboardSummaryIT` (inactive excluded + line) |
| 3 | 7-day counts: type+repo filters only, New=created_at<7d | S2 | fffe5de | `FeedSummaryIT` (window boundary; view-independent) |
| 4 | nginx headers `always` + all locations | S0 | 95ffccd | `e2e security.spec.ts` (5 cases: /, deep link, asset, 404, /api) |
| 5 | rescoreRepository same lock/guard + concurrency test | S1 | af829d7 | `SensitivityRescoreIT`, `SensitivityRescoreConcurrencyIT` |
| 6 | pin node + playwright by digest; pw non-root, no socket | S0 | 95ffccd | `ui/Dockerfile` digest; `e2e/run-e2e.mjs` (pwuser, no socket, ro mount) |
| 7 | workspace-2 complete seed dataset | S0/S2 | fffe5de,1e1222a | `e2e/fixtures/seed.sql` (complete ws2 + ws1; oracle in S4+) |
| 8 | path-scope ignore matches IgnoreMatcher format | S6 | 8135e03 | `e2e panel.spec` (path prefix hint; ignore leaves Focus) |
| 9 | error handler: no stack traces; 401 entrypoint unchanged | S1 | af829d7 | `ApiErrorBodyIT` (400/404/409 shape, no trace; 401 no WWW-Authenticate) |
| 10 | per-run e2e pg pw; ScanCreatedAtIT→R1/R4; post-signin route restore | S0/S1/S3 | 95ffccd,af829d7,a93bd97 | `ScanCreatedAtIT` (R1/R4); `e2e onboarding.spec` (route restored after re-auth) |

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

### S3 — UI shell ✓
react-router v8 routes (/dashboard, /feed, /feed/:groupId, /repositories, /ignored, /settings, /connect,
/select; index + wildcard → /dashboard). QueryClient with a documented query-key + invalidation map
(`src/queryClient.ts`): any triage/settings/scan/EPSS mutation invalidates the panel, feed rows + summary,
ignored, dashboard and repositories families. In-memory auth (`src/auth.tsx`): a 401 anywhere (QueryCache/
MutationCache onError) clears credentials AND `queryClient.clear()` and drops to the gate in place — the URL is
untouched so re-auth restores the route (change 10). ErrorBanner renders the server `{error,message}` body.
api.ts extended with every M8 endpoint + types; the feed query string is built from a validated params object,
never free data (OD-13d). External CSS (`styles.css`, light/dark tokens) keeps `style-src 'self'`; `vite-env.d.ts`
adds the Vite client types. Page bodies are stubs filled in S4–S8.

- Tests: `e2e onboarding.spec` (shell nav, Connect screen reachable, wrong-password, sign-out clears data +
  route restore). Gate: typecheck/build/vitest/guard green; e2e below.

### S4 — Repositories screen ✓
Full table: per-repo open-by-severity badges, ignored count, last-scan status + relative time, a warnings chip
(opens a drawer), an Inactive badge, and a visible "N inactive repos, M open findings (not scanned)" line from
the dashboard read model. Settings dialog (sensitivity / include-dev-deps / active) with Save and "Save and
rescan"; the scan button posts and then polls GET /scans/{id} to a terminal status before refetching counts.
Reusable SeverityBadges + ScanButton + RepoSettingsDialog + WarningsDrawer; lib/format for time/bands.

- Tests: `e2e repositories.spec` — rollups UI = API = SQL (via helpers/oracle), inactive line + badge + disabled
  scan, Start Scan enqueues (SQL scan count +1), polling-to-terminal returns the button to idle (status
  intercepted running→completed), warnings drawer lists a scan's warnings.
- Problems hit: (a) a new "Select repositories" link made `name: "Repositories"` ambiguous → `exact: true` on
  the nav-link selectors; (b) the Start-Scan test mutated a repo's last-scan (newest → 0 warnings), which the
  warnings test depended on → enqueue on web-portal so core-keycloak's warning row is untouched.

### S5 — Feed ✓
Feed page: summary bar (Grouped issues with a segmented severity bar + band counts; New / Solved / Auto-ignored
in the last 7 days), a Focus/All toggle, and type / severity / repository / include-inactive filters — all bound
to URL query params (shareable, back-button friendly). Server-side pagination (next/prev), default sort from the
server (severity desc NULLS LAST). Rows show type, title + subtitle, severity, repository path, fix time, and the
New(<7d)/To Do/Ignored status derived from the group's first_seen_at. A row opens /feed/:groupId (a minimal side
panel for now; S6 completes it).

- Tests: `e2e feed.spec` — summary counts UI = API = SQL (totalGroups, bands, 7-day New/Solved/Auto-ignored),
  Focus vs All (ignored groups appear only in All), >50 server-side pagination for core-keycloak (64 groups →
  50 + 14), default severity-desc sort, type filter, New/To-Do at the 7-day boundary (fresh bulk group = New,
  30-day inactive group = To Do under Include inactive).

### S6 — Issue side panel ✓
Full panel: per sub-issue the CVE/GHSA link (built from a regex-validated id, never from data), KEV badge, EPSS
value + "© FIRST.org" attribution whenever EPSS shows, installed→fixed, file/line, CWE phrase, the secret
preview as given (4 chars), the itemized score breakdown, and a per-sub-issue history. Ignore at all 5 scopes
(subissue, group, path-prefix this repo, CVE this repo|global, rule this repo|global) with a required reason and
a confirm for global scopes; the path match_value is a repo-relative PREFIX per core.triage.IgnoreMatcher
(change 8), with that hint shown. Unignore deletes the holding manual rule; a .secscan.yml rule shows the
edit-in-repo message and offers no unignore; an auto-ignored finding shows its cause and no unignore. Every
triage mutation invalidates the panel/feed/summary/ignored/dashboard/repositories families.

- Additive backend: `SubIssueView` gains `repositoryId` (the panel needs it for repo-scoped ignores); no
  migration. Seed gains a .secscan.yml-held finding.
- Tests: `e2e panel.spec` — CVE link + KEV + EPSS attribution + installed→fixed; reason-required + global
  confirm gating; ignore (subissue) removes the group from Focus (API oracle); .secscan.yml message + no
  unignore; auto-cause message + no unignore; unignore restores the finding to open; history lists events.

> **HARD STOP (S6 gate) — disk full.** The root filesystem `/` (57G, holds `/var/lib/docker`) is 100% full from
> repeated e2e image rebuilds; Testcontainers/e2e can no longer start a container. (/home, where the repo lives,
> has 127G — the P10 check looked there and missed that Docker is on root.) S6 CODE is complete and the UI gate
> (typecheck/build/vitest/guard) + targeted backend tests (SubIssuePanelIT, IssueApiIT) passed; the full
> `./gradlew build` and the S6 e2e failed ONLY with ExceptionInInitializerError / "postgres exited 1" — Docker
> out of space, not a code defect. Reclaimable without touching the dev DB: ~2.6G Docker build cache
> (`docker builder prune`) + ~4.4G unused images (`docker image prune -a`); the `postgres-data` named volume and
> the running default stack are preserved. Awaiting approval to prune (plan P10 gates `docker builder prune`).

### S7 — Ignored view + Settings ✓
Ignored page: a findings table (reason code, holding rule's reason + scope, creator, ignored time) and a rules
table with Delete and "Create new…" (prefilled from the old rule — no edit-in-place, D-069). Deleting a rule
restores the findings it held. Settings page: the EPSS auto-ignore toggle (GET/PUT /workspace/settings); turning
it on/off re-evaluates LOW_EPSS and the effect shows in the Feed/Ignored counts after refetch (the mutation
invalidates those families).

- Seed gains a low-EPSS (0.5%, not KEV) open finding and a manual rule + held finding dedicated to the
  delete-restores test (kept independent of the panel's rule to avoid cross-spec coupling).
- Tests: `e2e ignored.spec` (rows = GET /ignored; reason/creator/non-blank time; delete rule restores the held
  finding; "Create new" prefilled) and `e2e settings.spec` (EPSS toggle moves Feed focus −1 / Ignored +1 and
  round-trips). Gate: typecheck/build/vitest/guard green; e2e below.

  Problem (settings.spec, debugged against a throwaway `secscan-dbg` stack): (1) a Playwright controlled-
  checkbox + async mutation needs click()+poll, not check(); (2) the e2e seed's SAST rows had a null rule_id →
  `DuplicateRuleMap.shouldIgnore` NPE'd during the toggle sweep (fixed: SAST/IaC rows get a rule_id — real SAST
  always does); (3) the re-score re-enriches `sub_issue.epss` from `epss_score`, so the low-EPSS fixture's CVE
  must be in that table (added), and the api profile's scheduled intel-sync/re-score was disabled in the e2e so
  it can't clobber the seed; (4) the toggle-off sweep clears EVERY LOW_EPSS ignore (including the seed's standalone
  one), so the test now asserts the SPECIFIC low-EPSS finding moves between Focus and Ignored, not aggregate
  totals. (Observation, not fixed — out of M8/UI scope: `DuplicateRuleMap.shouldIgnore` NPEs on a null SAST
  rule_id; unreachable with real data since rule_id is part of the SAST fingerprint.)

### S8 — Dashboard ✓
Dashboard page: band-count cards for issue groups and findings (with a segmented severity bar), the issue-type
breakdown as plain CSS bars, scan health (repos whose last scan is partial/failed or raised warnings), the
inactive-repository line, and a recent-scans table (GET /scans + a repo-id→path map). All numbers come from
GET /dashboard/summary; nothing is computed in the browser.

- Tests: `e2e dashboard.spec` — totals/band counts UI = API = SQL, workspace isolation (the other workspace's
  critical finding is in a direct cross-workspace SQL count but never in the dashboard), scan-health count, type
  breakdown = API. Gate: typecheck/build/vitest/guard green; e2e below.

### S9 — Finish ✓ (no /finish-milestone — Sajal's real-GitLab walkthrough comes first)
- `./gradlew clean build --rerun-tasks` → BUILD SUCCESSFUL (app 305 + core 116 tests).
- UI gate: `npm ci && npm run build && npm run typecheck && npm test (2 vitest) && npm run guard` all green.
- E2E suite run **twice back-to-back**: 31 passed, all green, exit 0 both times — no flakes. Global security
  assertions (0 console errors, 0 CSP violations, 0 non-origin requests, empty storage) clean on every test.
- `npm audit` (ui): **0 vulnerabilities** (runtime and dev).
- Docs: README + CLAUDE.md Commands (UI gate + `npm run e2e`); SPEC M8 build note (OD-16 cuts); DECISIONS
  D-065..D-072 + the M8 deferred register; this work log. No migration, no scanner/hardening change.

Final test counts: app **305**, core **116**, vitest **2**, Playwright e2e **31** (×2). No `/finish-milestone`.

## S10 — fix + UX pass (Phase B continued)

No migration, no scanner change, NO new dependencies (React/react-router/react-query/plain CSS only); CSP
unchanged (`style-src 'self'`, styling only in CSS files). Data semantics unchanged — every oracle assertion
(UI=API=SQL) preserved; only two non-oracle e2e steps adjusted for the redesign.

### S10a — relative-time units bug ✓ (`a10e401`)
`lib/format.relativeTime` divided by the right unit but labelled it with the next-smaller unit (8.7h → "9m",
3.4d → "3h"). Replaced with one shared formatter on `Intl.RelativeTimeFormat` (built-in, no dep): <60s "just
now"; minutes; hours; days; >30d an absolute date; null/invalid "—"; future reads "in …". Added `exactTime()`
and a `RelativeTime` component that shows the exact local timestamp (locale + timezone) as a title tooltip
everywhere a relative time appears. vitest: 8 cases covering 59s/60s, 59m/60m, 23h/24h, 29d/30d, the two
reported values, future and null/invalid. e2e: 8h40m → "9 hours ago" and 3d10h → "3 days ago" asserted on
Repositories, Dashboard and Ignored.

### S10b — duplicate ignored SAST sub-issues (investigation only) ✓ (`d750052`)
Read-only against the dev DB; no data changed. Report: `docs/reports/M8-S10b-duplicate-ignored-sast.md`. Root
cause: the SAST fingerprint includes the scanner-computed normalized-snippet hash, which shifted on 2026-10-20
(Opengrep re-pin + Java→shell `SnippetNormalizer`, commits 12dbe23 / 05d9121); `Reconciler.java:95` solves only
`status='open'` findings, so the vanished OLD fingerprint stayed ignored ("ignored stays ignored") while the new
one was re-ignored → two ignored rows. Open findings do NOT accumulate (missing open → solved; empirically 2
ignored duplicate clusters, 0 open). Fix proposed as **OD-19** (touches reconcile/domain rule 3 — not
implemented). The line-number UI aid landed in S10d.

### S10c — design system foundation ✓ (`2949713`)
`src/styles/{tokens,base,components}.css` (imported via `styles.css`): severity colors used for severity only;
separate status roles (new=blue, todo=grey, ignored=muted, solved=green); surface/border/text levels; focus
ring; 4/8/12/16/24/32 spacing; 12–32 type scale; radii; one font stack; AA contrast in light and dark. Shared
components: SeverityPill, StatusChip, TypeBadge, RepoName, Button, Card, PageHeader, EmptyState, InfoTip,
SeverityBar, Legend, Toggle, RelativeTime, ScanStatusChip, accessible Dialog (role=dialog, aria-modal, Esc,
focus-trap, focus restore) and ConfirmDialog. Human-label maps in `src/lib/labels.ts`. Shell restyled (wordmark,
nav active state, user + Sign out).

### S10d — screens rebuilt + backend read-model additions ✓ (`35c714e`, fixes in `30ac253`)
All 8 screens rebuilt on the system (see commit body). Severity colors now identical Feed↔panel (orange-98
mismatch fixed). Backend (no migration, ITs added): `IssueGroupView.findingCount` (batch count, no N+1;
FeedSearchIT), `IgnoredItemView.line` (SastIgnoreChurnIT), `GET /issue-groups/{id}` for the panel header. Two
fixes after the first e2e run: dropped the sticky table header (it overlapped the first row and intercepted a
click) and showed a scan's real status via `ScanStatusChip` ("Completed"/"Failed"/"Partial") instead of the
issue `StatusChip` ("Solved").

### S10 gates
- G1 `./gradlew build` → BUILD SUCCESSFUL (app + core; +2 ITs). G2 UI gate (typecheck/build/2+8 vitest/guard)
  green. `npm audit`: **0 vulnerabilities** (no new deps).
- G3 `npm run e2e`: **54 Playwright tests** (31 prior + 7 `s10.spec` + 16 `screens.spec`), run twice
  back-to-back, all green, no flakes. Screenshots at 1440×900 and 1280×800 in `ui/e2e/artifacts/screens/`
  (gitignored).
- Oracle assertions unchanged; two non-oracle steps adjusted (Show-inactive toggle; delete-confirm). No
  migration, no scanner change, no new deps, CSP unchanged, numbers unchanged.

## S10e — UX fixes from the live review + light theme

From Sajal's incognito review on real data. No migration, no scanner change, no new deps, CSP unchanged, all
existing oracle assertions unchanged.

1. **Finding counts (`ecab860`):** `GET /issue-groups` rows carry `openCount`/`ignoredCount`/`solvedCount` (one
   SQL `group by status`, no N+1) instead of a single total. Feed Focus shows `openCount`, All shows
   "N open · M ignored", so the row count matches the Dashboard. `FeedSearchIT` asserts a mixed group's exact
   per-status counts; e2e oracle: each Feed row's shown openCount == API openCount == SQL open sub-issues of
   that group, and Σ Focus openCount == Dashboard findings total.
2. **Dashboard type bars** render again — the fill span needed `display:block`. e2e: each fill has computed
   width > 0 and widths are ordered by count.
3. **Repositories open-by-severity** is one labeled non-wrapping line "C 1 · H 2 · M 2 · L 0" (zeros muted);
   full path muted on its own line under the repo name (ellipsis). (`data-sev` count span unchanged for the
   oracle.)
4. **Side panel** no horizontal clipping: the location header keeps the path on its own line with the
   SeverityPill right-aligned; long tokens (incl. the EPSS URL) wrap; `overflow-x:hidden`. e2e: panel
   scrollWidth <= clientWidth at 1280 and 1440.
5. **Typography:** advisory/description subtitles in the body font; monospace only for rule/CVE ids, paths and
   versions.
6. **App header:** Sign out never clipped (nav shrinks with `min-width:0`, `topbar-user` is `flex:none` +
   `margin-left:auto`, topbar `min-height`). e2e: the Sign out button is fully inside the viewport at 1280.
7. **Small text:** Ignored paths wrap after "/" (`<wbr>` per segment); dashboard warning chip "repo · N
   warnings" (a `.chip` gap stops the flex layout trimming the separator); scan-health sentence
   "N need attention — N failed/partial · M with warnings" / "… — all scans completed · M with warnings"
   (the `scan-health-count` element still carries the total for the oracle).
8. **Feed summary** band legend is a 2×2 grid (no orphaned "0 low").
9. Screenshots re-taken (16 at 1440/1280 + 8 explicit light/dark captures of the 4 key screens at 1440).
10. **Light theme (`ecab860`):** a Light/Dark/System control held in memory only (no web storage — the guard is
   unchanged; a refresh resets to System). It sets `data-theme` on `<html>`; the token CSS already selects by
   `[data-theme]` and `@media (prefers-color-scheme)`. AA contrast verified per theme (light and dark) in e2e;
   toggling switches theme with no reload and storage stays empty.

### S10e gates
- G1 `./gradlew build` → BUILD SUCCESSFUL (app + core). G2 UI gate green (typecheck/build/10 vitest/guard, 44
  files). `npm audit`: 0 vulnerabilities.
- G3 `npm run e2e`: **70 Playwright tests** (54 S10 + 8 `s10e.spec` + 8 themed screenshots), run twice
  back-to-back, all green, no flakes (one race fix after the first run — wait for the Feed rows before reading
  their counts). Screenshots in `ui/e2e/artifacts/screens/` (gitignored).
- No migration, no scanner change, no new deps, CSP unchanged, oracle assertions unchanged.
