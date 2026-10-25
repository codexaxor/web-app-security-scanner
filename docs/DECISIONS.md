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

## D-079 M8.1 errata (review clarifications; D-076/D-078 text unchanged) (2026-10-25, accepted)
Context: the /review-pr round surfaced small inaccuracies/clarifications in D-076/D-078 wording. Those entries are
not edited (log-integrity rule); this entry is the authority for the corrections.
Decision (errata):
- Real test names: the re-baseline proof is `RebaselineReconcileIT` (+ `ReconcilerRebaselineTest` was folded into
  it — the pure `Reconciler` is branch-independent, so re-baseline is an app-layer reason-code concern); the
  rotation-UI proof is `rotate.spec.ts`; branch-name parity is the shared `scanner-image/branch-vectors.json`
  read by `BranchNameValidationTest` (Java) and `branch_vectors_test.py` (in-image shell).
- API field names: the branch picker returns `isDefault` / `isProtected` (JSON), not `default`/`protected`.
- A2 wording narrowed: safety is "the branch reaches git only as a single `--branch=<name>` argv element — never
  through a shell, never interpolated into a URL or a file path; log lines reference scan/repository ids, not the
  branch name" (a structurally-valid shell-metacharacter branch is accepted and harmless; GitLab existence is the
  authoritative gate).
- Branch-name length is counted in Unicode CODE POINTS (N-1), matching busybox `${#}` under `LC_ALL=C.UTF-8` in
  the scanner image; proven by the shared vector set including `ü×255`/`×256` and astral cases.
Consequences: documentation accuracy only; no behavior change.

## D-078 Connection token rotation in the UI (2026-10-25, accepted)
Context: `gitlab_connection` tokens expire; D-063-era SPEC already shows a 14-day expiry banner and the backend
`PUT /api/v1/gitlab/connections/{id}` (`GitLabController.java:49` → `GitLabConnectionService.update` →
`ConnectionTokenWriter.rotate`) rotates a connection's token in-place (same target), re-verifying scopes/expiry.
M8 shipped no UI for it, so an expiring token could only be rotated by an API call.
Decision: each connection row in the Connect GitLab screen gains a **Rotate token** action opening a dialog with a
single `type=password`, `autocomplete=off` token field. Submit calls the existing PUT; on success the row shows the
new scopes/expiry. The token lives only in the dialog's local component state, is dropped on submit/close, is never
echoed back by the server, never logged, never written to any browser storage (D-067), and never placed in a URL.
A target mismatch (`CONNECTION_TARGET_MISMATCH`) or GitLab error maps to the existing error taxonomy and is shown
inline. Proven by `ConnectionRotateUiE2E` (Playwright: rotate sends the PUT; token never visible after submit).
Consequences: expiring tokens are rotated entirely in the UI; no new backend endpoint; no new dependency.

## D-077 Migration numbering: V19 = M8.1 scan branch; earmarks move (2026-10-25, accepted)
Context: D-074 reserved **V19** for the IaC severity override and **V20** for the level-2 taxonomy. M8.1 needs an
additive migration now.
Decision: **V19 = M8.1 scan branch** (`repository.scan_branch`, `scan.branch`). The earmarks move down one:
the IaC severity override → **V20**, the level-2 taxonomy → **V21**. This supersedes the "V20 / V19 reserved"
numbering mentioned in D-074 (D-074's text is left intact per the no-edit rule; this entry is the authority).
Consequences: no change to any applied migration; M8.5's two migrations become V20/V21.

## D-076 Branch to scan per repository, with re-baseline on switch (2026-10-25, accepted)
Context: SecScan always clones the repository's GitLab default branch (`ScanJobProcessor.buildSpec` →
`repo.getDefaultBranch()` → `SCAN_BRANCH` → `run-scan.sh` `git clone --single-branch --depth 1`). Sajal must scan
`prime-focus-health/orionpilot/notification` on branch `dev` (its `main` is a 2-commit skeleton; real code is on
`dev`). The container-facing plumbing already carries a branch argv-safely (docker-java API, no shell; token via a
one-time file, D-014), but there is no per-repository choice, no per-scan snapshot, no validation and no
branch-aware lifecycle.
Decision:
 1. `repository.scan_branch` (nullable; **null = follow the GitLab default branch**). `scan.branch` = the branch
    actually cloned, **snapshotted at enqueue and never re-read later**. Migration **V19** (additive; backfills
    existing `scan.branch` from `repository.default_branch` where known; never edits V1–V18).
 2. Setting it: `PATCH /api/v1/repositories/{id}` accepts `scanBranch` (string or null). The server validates
    (a) the ref-name with git's own `check-ref-format --branch` rules re-implemented in Java (**amendment A1**:
    not a narrow regex — any character git allows is allowed, incl. `+ # , _ @` except `@{`, and unicode letters;
    PLUS reject a leading `-`, ASCII control chars / NUL / newline, and length > 255), and (b) **existence in
    GitLab** for that project (exact match against the branches API, URL-encoded lookup) — the authoritative check,
    so every branch GitLab lists, and only those, can be chosen. Missing/invalid → `400 BRANCH_NOT_FOUND`; repo
    outside workspace → `404`; inactive repo → `409`. `null` resets to default.
 3. Branch picker: `GET /api/v1/repositories/{id}/branches?search=&page=&size=` (size ≤ 100; `search` ≤ 100 chars
    else 400, URL-encoded to GitLab) → `[{name, default, protected, lastCommitAt}]` via GitLab
    `GET /projects/:id/repository/branches` (read_api, paginated). Branch names are external data, rendered as
    **text only** (D-067). Tokens never logged; GitLab errors mapped to the existing taxonomy. **Amendment A3:** the
    picker is a searchable combobox that pages beyond 100 branches (server-side `search` + pagination).
 4. Clone: the scanner clones `scan.branch` with `--single-branch --depth 1` and the branch as a **separate argv
    element** via `--branch=<name>` (**amendment A2**: never a shell string, never interpolated into a URL / log /
    file path). `run-scan.sh` re-validates the branch with the same rules (`secscan-lib.sh`) and refuses otherwise
    (non-zero exit + scan warning). Container hardening is unchanged except this one validated argument (same
    read-only rootfs, `--cap-drop=ALL`, no-new-privileges, CPU/mem/PID limits, non-root user, tmpfs, network).
 5. **Re-baseline on branch change** (domain rules 3/4/5 preserved): identity is branch-independent — the
    fingerprint already excludes branch and line, so sub-issues stay loaded repo-wide. When a **scanner's** branch
    differs from the branch at which that same scanner last successfully closed findings, reconcile runs that
    scanner in *rebaseline mode*:
    - fingerprints present on the new branch: normal reconcile (new → open, seen → update);
    - open findings ABSENT on the new branch → **solved** with lifecycle reason `REASON_BRANCH_CHANGED` (not a
      "fix");
    - solved findings that reappear → **reopened** with `REASON_BRANCH_CHANGED` (approved: in rebaseline mode ALL
      switch-caused closes AND reopens use `REASON_BRANCH_CHANGED`, regardless of the prior close reason);
    - **ignored stays ignored** (rule 3) — an ignored finding absent on the new branch is NOT solved; it is held.
      This is the OD-19 "absent-but-ignored" family and is resolved with OD-19 in M8.5 (deferred register);
    - a failed/absent scanner closes NOTHING for that scanner; its rebaseline is **deferred to the next scan where
      it succeeds**. Nothing is persisted specially — the per-scanner "last successful branch" is **derived from
      existing scan/scan_task history** (`scan_task.status='ok' AND closes_findings`, newest reconciled), so a
      failed scanner simply does not advance its last-success branch and still sees the difference next time;
    - partial scans follow the same per-scanner rule.
    `REASON_BRANCH_CHANGED` is a **lifecycle** reason (a new constant in `issue/TriageEvent.java` alongside
    `SEEN`/`REOPENED`/`SOLVED_NOT_DETECTED`/`UNIGNORED`), **NOT** a `core.triage.ReasonCode` auto-ignore code — so
    CLAUDE.md hard rule 5's list is unchanged; hard rule 4 (every status change writes a `triage_event`) is
    honored. `triage_event.reason_code` is free text (no CHECK), so no constraint migration is needed.
    Feed "Solved · 7d" and any future "fixed"/MTTR/fix-rate metric (M9) **EXCLUDE** `REASON_BRANCH_CHANGED` closes
    (deferred register). History renders the event in plain words: "Closed — branch changed (<old> → <new>)", with
    the fallback "Closed — branch changed (now scanning <new>)" when the old branch is not cheaply recoverable.
 6. Changing `scanBranch` while a scan for that repo is queued/running is allowed; the in-flight scan keeps its
    snapshotted branch; the next scan uses the new one. The existing `uq_scan_active_per_repository` (one active
    scan per repo) is unchanged. **Amendment A4:** `scanBranch` may change any number of times, in any order
    (main → dev → staging → main, incl. back to null); each switch is honoured by the NEXT scan via re-baseline,
    and a same-branch rescan adds zero triage events.
 7. **Amendment A5 (branch disappeared):** if the chosen branch was deleted in GitLab, the next scan fails cleanly
    **before cloning** — a worker-side pre-clone existence check (GitLab branches API) writes `fail_reason` /
    `scan_warning` "branch not found: <name>", runs no reconcile (closes NOTHING), and the repository is flagged
    `branchMissing` so the UI shows "branch missing — choose another".
 8. **Amendment A6 (default follow):** with `scanBranch = null`, each scan uses GitLab's CURRENT default branch at
    enqueue (refreshed via `GET /projects/:id`); if it differs from the stored value, `repository.default_branch`
    is updated (no status change; INFO log with repo id + old/new), and the change is treated like a switch
    (re-baseline), not mass "solved". On a GitLab failure at enqueue, the stored default is used and a
    `scan_warning` is raised.
Proven by `BranchNameValidationTest`, `RepositoryScanBranchIT`, `RepositoryBranchesIT`, `RebaselineReconcileIT` +
`ReconcilerRebaselineTest`, `FeedSummaryIT`, `BranchMissingIT`, `BranchCloneScanIT`, and
`scanner-image/run_scan_branch_test.py`.
Consequences: per-repository branch selection with an audited, branch-aware lifecycle; one additive migration; no
hardening/CSP/dependency change; the absent-but-ignored edge stays with OD-19 for M8.5.

## D-075 M8 "Done when" verified on 2 repos (1 real + 1 fixture); 3-real-repo walkthrough → M8.5 (OD-21) (2026-10-25, accepted)
Context: SPEC M8 "Done when" is "Walkthrough on 3 real repos matches API numbers". Sajal's real-data walkthrough
(2026-10-22..25) was run against **core-keycloak** (a real GitLab repo) and **secscan-test-secrets** (a synthetic
fixture), not three real repos. Every acceptance invariant was confirmed on that data (SQL = API = UI; the
sub_issue status counts as oracle through ignore / EPSS On+Off / manual scan / unignore — see `docs/reports/M8.md`
"Done-when check").
Decision (OD-21 = option B — accept the deviation, finish M8, carry the shortfall): finish M8 on the two-repo
walkthrough; onboarding and verifying **ib-api-v2** and a **third real repo** against the same invariants is the
**first task of M8.5**. The M8 row in SPEC gets a note pointing here.
Consequences: M8 ships with its UI and read models proven on real + fixture data; the literal "3 real repos" bar
is met early in M8.5, not in M8. No code or behavior changes from this decision.

## D-074 Finding taxonomy (level-2 categories + optional OWASP tags) is M8.5, not M8 (OD-20) (2026-10-25, accepted)
Context: S10f shipped plain-English **level-1** type names everywhere (`ui/src/lib/labels.ts`: "Vulnerable
dependency", "Hardcoded secret", "Insecure code", "Infrastructure misconfiguration", "End-of-life runtime").
A richer **level-2** taxonomy (categories derived from CWE / secret type / IaC platform / ecosystem, plus optional
OWASP Top 10 2025 tags) was discussed during the walkthrough.
Decision (OD-20 = option A): the level-2 taxonomy is a **milestone M8.5** item with its own SPEC section and its
own migration (**V20**; note V19 stays reserved for the IaC override already planned). Level-1 names are done and
remain as shipped in S10f.
Consequences: no taxonomy columns/migration land in M8; M8.5 owns the schema and SPEC text.

## D-073 Duplicate-ignored-findings fix (OD-19) scheduled for M8.5 (2026-10-25, accepted)
Context: `docs/reports/M8-S10b-duplicate-ignored-sast.md` root-caused OD-19 — absent-but-ignored findings
accumulate forever after a fingerprint/snippet-hash shift, because reconcile's solve step is gated on
`status='open'`, so a vanished *ignored* fingerprint is never closed while the new one is re-ignored. Open findings
do not accumulate. The proposed fix touches reconcile/the D-063 resolver (domain rule 3), so it needed a human
decision and was not built in M8.
Decision: schedule OD-19 for **M8.5**. The fix = (1) solve an *ignored* finding absent from a scanner-complete
scan when its hold is re-derivable, routed through the D-063 resolver and writing a `triage_event`; (2) a one-off
maintenance pass (no migration) to retire rows already accumulated; (3) optionally reduce SAST fingerprint churn
with a structural match key. Not a security issue (the live finding is tracked correctly; surplus rows are stale
ignored clutter).
Consequences: tracked as an M8.5 work item with a known root cause; no M8 reconcile change.

## D-072 M8 test tooling, dev-only, never in the runtime image (OD-18) (2026-10-22, accepted)
Context: M8 needs unit/component tests and browser E2E, but the SPA runtime image must stay minimal and the
host is missing 3 Chromium shared libs (libxcomposite1/libxdamage1/libxrandr1; installing needs sudo).
Decision: dev-only test tooling — **vitest 4.1.12** (+ **jsdom 28.1.0** DOM env, **@testing-library/react 16.3.3**)
for unit/component tests; **@playwright/test 1.64.1** for E2E, run **inside the pinned Playwright image**
`mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf`
(approved — avoids host sudo/libs). The E2E oracle reads the e2e Postgres directly via **pg 8.23.1** (dev-only,
required by the harness "direct SQL count" assertion). None of these ship in `ui/Dockerfile` (`.dockerignore`
excludes `node_modules`/`e2e`). The Playwright container runs as **pwuser** (non-root), no docker socket, joined
only to the `secscan-e2e` network, ui mounted read-only except `e2e/artifacts`.
Consequences: fast local tests + faithful browser E2E with zero host changes; one more image to re-pin on bumps.

## D-071 Walkthrough stays Sajal's manual job; the build makes it checkable (OD-17) (2026-10-22, accepted)
Context: SPEC M8 "Done when" is a 3-real-repo walkthrough matching API numbers; that needs real GitLab + VPN.
Decision: Claude does NOT run the walkthrough. Claude's job is to make it trivially checkable — every on-screen
count is proven equal to the API and to direct SQL by named ITs + Playwright oracle, and the final report gives
the exact command list Sajal runs (rebuild api/worker/ui on the default project, per-screen checks for
core-keycloak + ib-api-v2 + a 3rd repo + secscan-test-secrets). No `/finish-milestone` until that passes.
Consequences: the milestone's acceptance is a short manual confirmation, not a from-scratch manual exploration.

## D-070 Repository settings: PATCH with immediate re-score for sensitivity (OD-15) (2026-10-22, accepted)
Context: M8 needs per-repo settings (sensitivity, include-dev-deps, active) from the Repositories screen.
Decision: `PATCH /api/v1/repositories/{id}` `{sensitivity?, includeDevDeps?, active?}`, workspace-scoped (repo
outside workspace → 404). `sensitivity` ∈ {low,normal,high} else 400; a change triggers an **immediate per-repo
re-score through the existing D-049 path** (`ReScoreService.rescoreRepository`, score-only — 0 `triage_event`,
no status/fingerprint change; sensitivity is already a scorer input, verified P3). `includeDevDeps` is saved and
takes effect at the **next scan** (no API-time triage — the D-063 carve-out is NOT widened). Activation
(change 1): `active=false` sets `inactive_reason='DEACTIVATED_BY_USER'` and blocks new scans (POST scan on an
inactive repo → 409, no job row); `active=true` succeeds **only** when `inactive_reason='DEACTIVATED_BY_USER'`
AND `connection_id IS NOT NULL` (clears the reason), else 409 ("re-select the project via a connection"). The
settings-change audit row is **deferred to M10**; M8 logs field names at INFO (no values). No migration
(`inactive_reason` is free text in an existing column).
Consequences: full per-repo tuning with correct scoring/lifecycle guarantees and no new table.

## D-069 M8 cut list — what the UI does and does not include (OD-16) (2026-10-22, accepted)
Context: M8 scope must be bounded against Aikido's full feature surface.
Decision: **IN** — delete ignore rule (+ "create new" prefilled from the old rule); side-panel ignore with all 5
scopes (subissue, group, path-prefix this repo, CVE this repo|global, rule this repo|global), reason required,
confirm dialog for global scopes; unignore = delete the holding manual rule; `.secscan.yml` rule → show the 409
"edit .secscan.yml" message; auto-ignored → reason code + how to clear, NO unignore button; per-sub-issue
history; scan button with status polling to terminal then refetch; Dashboard current counts + scan health +
recent scans; next/prev pagination. **OUT** — edit rule in place (no PUT), group-level timeline, trends over
time, export, snooze, teams, reachability, a detail page, live logs. All OUT items go to the deferred register.
Consequences: a focused, shippable UI; the OUT list is tracked, not forgotten.

## D-068 M8 server read models, all workspace-scoped, counts in SQL (OD-14) (2026-10-22, accepted)
Context: the UI must never compute counts in the browser; every screen number is a server read model.
Decision: additive read models (no migration) in dedicated query services: repository rollups (open-by-severity,
ignored count, last scan, last-scan warning count); `GET /scans/{id}/warnings` + workspace-wide `GET /scans`;
feed `GET /issue-groups` gains `view=focus|all`, severity-band filter, `repositoryPath` per row and the
documented sort **`severity_score desc NULLS LAST, first_seen_at desc, id`**; `GET /issue-groups/summary` (band
counts + last-7-day New/Solved/Auto-ignored as distinct sub-issues "findings", honouring type+repo filters
only — change 3); `GET /sub-issues/{id}/events`; `SubIssueView` gains rule fields (`ignoreRuleId/ruleScope/
ruleReason/ruleCreatedBy`, null for auto causes); `GET /dashboard/summary`; and the insert-refresh fix so
`createdAt` is non-null on POST scan / POST ignore-rule. Feed/summary/dashboard take `includeInactive` (default
false) and exclude inactive repos' rows by default (change 2). Semantics: Focus = groups status open; All =
open + ignored (solved excluded, only as counts); an all-ignored group has null score → shown "Ignored", no
band, never in band totals. Bands on `severity_score`: Critical 90–100 / High 70–89 / Medium 40–69 / Low 1–39.
Consequences: the browser renders server numbers only; zero new tables/migrations.

## D-067 M8 security baseline (OD-13) (2026-10-22, accepted)
Context: a security product's own UI must not become an attack surface.
Decision: (a) Basic credentials live in JS memory only — never localStorage/sessionStorage/IndexedDB/cookies/
URL/logs; a refresh re-signs-in; sign-out and any 401 clear credentials AND `queryClient.clear()`. (b) The
server keeps the JSON 401 with **no** WWW-Authenticate, no CORS, same-origin only (already in `SecurityConfig`).
(c) nginx (ui image) sends, ENFORCED with `always` and re-included into every location (change 4):
`Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"`,
`X-Content-Type-Options nosniff`, `Referrer-Policy no-referrer`, and `Cache-Control no-store` on index.html; any
Vite relaxation would STOP-and-report (handled: the module-preload polyfill is disabled so every script stays
external `'self'`). (d) All scanned/feed data renders as TEXT ONLY — no `dangerouslySetInnerHTML`, no HTML/
Markdown/`innerHTML`; outbound links (e.g. a CVE page) are built by our code from a regex-validated id, never
from data. (e) A guard script (`ui/scripts/guard-forbidden.mjs`, in the UI gate) FAILS on
`dangerouslySetInnerHTML`, `innerHTML`, `localStorage`, `sessionStorage`, `indexedDB`, `document.cookie`,
`eval(`, `new Function(` anywhere in `ui/src`.
Consequences: a locked-down SPA; error handler normalizes bodies without leaking stack traces (change 9).

## D-066 M8 runtime UI dependencies — minimal, no frameworks (OD-12) (2026-10-22, accepted)
Context: keep the SPA small and auditable.
Decision: the ONLY runtime deps are **react-router 8.5.0** (declarative mode: `BrowserRouter`/`Routes`, imported
from `react-router`; no framework mode, no SSR, no `react-router-dom`) and **@tanstack/react-query 5.104.5**. No
chart lib, no table lib, no component/CSS framework, no state lib. Charts are plain CSS/SVG; tables are plain
HTML with server-side pagination.
Consequences: a tiny dependency surface; charts/tables are hand-built.

## D-065 M8 frontend toolchain, pinned (OD-11) (2026-10-22, accepted)
Context: M8 builds the real UI; the toolchain must be current, pinned and reproducible.
Decision: **react 19.2.9**, **react-dom 19.2.9**, **vite 8.1.6**, **@vitejs/plugin-react 6.1.2** (peers vite ^8;
its 3 extra peers are optional), **typescript 6.0.3** (NOT 7 — no stable programmatic API until 7.1, and
typescript-eslint is incompatible). Exact versions pinned in `ui/package.json` + committed `package-lock.json`.
The lockfile is generated with **npm 12** (the host's npm 10.9.4 crashes on vite 8's devtools optional-peer graph
with an arborist `edgesOut` bug); `npm ci` — the gate command — succeeds with the bundled npm 10. `ui/Dockerfile`
build stage is pinned by digest to `node:22.23.4-alpine@sha256:ce8d…ffadc` (node v22.23.4 ≥ 22.22, required by
react-router 8.5.0 and vite 8; change 6). The nginx RUNTIME stage is likewise pinned by digest (SF-5):
`nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10` (tag resolved
2026-10-25). The build step is `npm ci` ONLY (no `|| npm install` fallback) so an out-of-sync lockfile fails the
build instead of silently drifting.
Consequences: a modern, reproducible build; re-pin node/nginx/npm notes on any bump.

## D-064 Default-dir/minified path exclusion does not apply to secret findings (2026-10-21, accepted)
Context: M7 review. The SPEC default exclusions (`node_modules/ vendor/ dist/ build/ target/ bin/ obj/` and
`*.min.js`/`*.min.css`) are noise-reduction for SCA/SAST/IaC, but a leaked CREDENTIAL in one of those paths (a key
checked into `dist/`, a token in a minified bundle) is still a real exposure that must not be auto-hidden.
Decision: the SPEC default dirs + minified-file rules are NOT applied to `type=secret` findings. An EXPLICIT
`.secscan.yml exclude.paths` entry still applies to every type (the repo owner's audited, revocable choice).
Implemented via `PathExclusion.isExcluded(path, extraPrefixes, applyDefaults)` with `applyDefaults=!secret` in
`TriageApplier`. Proven by `PathExclusionTest` + `SecretPathExclusionIT` (a key in `dist/app.js` stays OPEN; the
same key under an excluded path is ignored EXCLUDED_PATH).
Consequences: secrets in build/vendor/minified output are never silently suppressed by defaults; a repo can still
exclude a path explicitly, and that stays audited.

## D-063 One desired-ignore-state resolver with a fixed precedence (2026-10-21, accepted)
Context: M7 review. Ignore state was computed in two places (reconcile and the API) with the precedence expressed
both as a hand-ordered if/else chain and as the `ReasonCode` enum order — two sources of truth that could drift,
and the API path could not keep a finding ignored when another cause still applied.
Decision: a SINGLE resolver (`TriageResolver`) computes `(status, reason_code, rule_id)` from ALL causes with ONE
precedence — `ReasonCode` declaration order, applied once via `ReasonAssignment.highest` (no duplicate ordering
logic). It is used by reconcile (`TriageApplier`), the API (`TriageService`) and the EPSS sweep
(`WorkspaceSettingsService`). A manual/`.secscan.yml` `IGNORE_RULE` outranks every auto cause; among matching
rules the earliest `(created_at, id)` is the holder. A new rule over an auto-ignored finding quietly re-points
`ignored_by_rule_id` (no event); `removeRule` re-resolves and keeps a finding ignored when another rule or an
API-visible auto cause (path exclusion, secret placeholder, LOW_EPSS) still applies (quiet, no event), else
unignores to the last-succeeded-scan status (never solves on API evidence). **API-time triage (manual
ignore/unignore, EPSS sweep) is an audited carve-out of CLAUDE.md domain rule 3 "status changes only through
reconcile" (D-063)** — it still writes a `triage_event` for every status change and uses this same resolver.
The scan-time-only auto causes (config `exclude.paths`, dev/test scope, duplicate SAST rule) that the API cannot
recompute are persisted every reconcile in `sub_issue.auto_ignore_reason` (SF1, V18) — written even when an
IGNORE_RULE holds the finding, never a status change, no event — and the API resolution consults it, so removing
a rule over e.g. a TEST_SCOPE finding keeps it ignored under TEST_SCOPE with no event. CLAUDE.md domain rule 3
references this. Proven by `IgnoreScopeIT`, `UnignoreIT`, `TriagePrecedenceIT`, `TriageStabilityIT`,
`RuleRemovalKeepsScanTimeCauseIT`.
Consequences: identical ignore state however reached; one precedence authority (`ReasonCode` + `ReasonAssignment`);
one additive nullable column (`auto_ignore_reason`, V18).

## D-062 SAST ignores match on (rule_id, file_path), snippet-independent (2026-10-21, accepted)
Context: an M7 manual ignore of a SAST finding must survive a snippet re-fingerprint (D-053: a substantive edit
to the matched lines yields a new fingerprint, so the old sub-issue solves and a new one opens). Keying the
ignore on the full fingerprint would lose the ignore across such a churn.
Decision: a SAST ignore (`scope=subissue` or `scope=rule` resolving to a SAST finding) matches on
`(rule_id, file_path)`, NOT on the fingerprint — snippet-independent. So a re-fingerprinted finding for the same
rule+file stays ignored. The SAST fingerprint itself is UNCHANGED (`rule_id | file_path | normalized_snippet_hash`,
D-053 intact); the match key is a triage concern only, never a fingerprint input. Implemented in
`core.triage.IgnoreMatcher`; proven by `SastIgnoreChurnIT`.
Consequences: SAST ignores are stable across cosmetic/substantive snippet edits; no fingerprint change.

## D-061 Duplicate SAST rule auto-triage via a curated winner map (2026-10-21, accepted)
Context: a custom Spring/.NET rule and a stock opengrep-rules rule can both flag the same weakness (the M6 CSRF
case), yielding two sub-issues for one finding. We will not merge across rules or change any fingerprint.
Decision: both rules keep firing. A CURATED map `{stock rule id -> winning custom rule id}`
(`core.triage.DuplicateRuleMap`, seeded with the CSRF pair) auto-ignores the LOSER as `DUPLICATE_RULE` when the
winner is present for the same `file_path` in the SAME scan. No cross-rule merge, no fingerprint change. The
stock rule id is seeded from REAL Opengrep output (the M6 `sast-custom` fixture / manual check), never guessed;
if it cannot be confirmed from existing output it is captured in the manual check before seeding. Cleared via its
cause at reconcile (winner no longer present → the loser is unignored with a triage_event). Proven by
`DuplicateRuleIT`.
Consequences: one sub-issue surfaces per duplicated weakness; the map is maintained as rules evolve.

## D-060 EPSS toggle: a LOW_EPSS status path plus scoring-only tiers (2026-10-21, accepted)
Context: SPEC makes EPSS adjustments opt-in (off by default); M5 stored EPSS but applied nothing (D-047).
Decision: an `epssAutoIgnoreEnabled` flag lives in `workspace.settings` (jsonb), default OFF. When ON:
- `EPSS < 1% AND not on KEV` → auto-ignore `LOW_EPSS` — a real STATUS change at reconcile / on-toggle, with a
  `triage_event` (NOT the score-only re-score path);
- `EPSS < 5%` → −10 and `5–10%` → −5 are SCORING-ONLY (the normal `Scoring` + `ReScoreService`, no status change,
  no `triage_event`). The two tiers never double-apply (strict bands: <1% is LOW_EPSS, [1%,5%) is −10, [5%,10%)
  is −5, ≥10% no adjustment).
Re-evaluated on the next scan and on toggle (toggle OFF clears LOW_EPSS via its cause + re-scores). A KEV-listed
low-EPSS CVE is never auto-ignored. EPSS stays a descriptive, NON-fingerprint input (D-051). `core.triage.EpssPolicy`
holds the boundaries; `core.scoring.Scoring`/`ScoreInput` gain `epss` + `epssAdjustmentsEnabled` (additive).
Proven by `EpssPolicyTest`, `ScoringTest`, `EpssToggleIT`.
Consequences: EPSS noise reduction is available and audited; scoring tiers and the status path are kept separate.
Amendment (review S1, 2026-10-22): the scoring tiers are STRICT bands that defer to the status path —
`scoreDelta(epss, onKev)` returns 0 for a non-KEV finding below 1% (the LOW_EPSS status auto-ignore owns it, so
the score is not also docked), while a KEV finding below 1% (never status-ignored) keeps the −10 tier. Bands:
non-KEV <1% → 0; `[1%,5%)` and KEV `<5%` → −10; `[5%,10%)` → −5; ≥10% → 0 (`0.0099→0`, `0.01→−10`, `0.05→−5`,
`0.10→0`). The SPEC "no PoC" clause on the LOW_EPSS auto-ignore is DEFERRED until a PoC feed exists (open item
N5). Proven by `EpssPolicyTest` + `ScoringTest`.

## D-059 Auto-triage reason codes; dev/test scope read from DECLARED scope (2026-10-21, accepted)
Context: M4 (D-035 Q2) left npm devDependencies / Gradle test configs / Maven test scope un-excluded because OSV
exposes no dependency-group marker. M7 adds the auto-triage layer and finally wires `repository.include_dev_deps`
(a no-op until now).
Decision: reason codes — `EXCLUDED_PATH` (SPEC default globs `node_modules/ vendor/ dist/ build/ target/ bin/
obj/` + minified files, plus `.secscan.yml` paths); `SECRET_PLACEHOLDER` (`changeme` / `example` / `*.example`);
`DEV_DEPENDENCY` / `TEST_SCOPE` from the DECLARED scope read by our OWN inventory/resolver (Maven test scope,
Gradle test configurations, npm devDependencies); `IGNORE_RULE` (manual + `.secscan.yml` CVE); `LOW_EPSS` (D-060);
`DUPLICATE_RULE` (D-061). The scope marker is a TRIAGE ATTRIBUTE ONLY — never a fingerprint input.
- **Precedence (review change #3):** `effective_include_dev = repository.include_dev_deps OR .secscan.yml
  include_dev_dependencies`. The file may only INCREASE visibility (turn inclusion ON), never force-hide a dep
  below the SecScan repository setting. Dev/test auto-ignore applies ONLY when `effective_include_dev == false`.
- Where scope CANNOT be determined (e.g. an OSV transitive with no declared scope) the finding STAYS VISIBLE —
  we mark only what is confidently dev/test.
Every auto-ignore writes a `triage_event` (actor=system). This extends CLAUDE.md hard rule 5's list with
`DUPLICATE_RULE`. Pure logic in `core.triage` (`PathExclusion`, `SecretPlaceholder`, `ReasonAssignment`).
Consequences: dev/test noise is suppressed only on a declared, auditable basis; `repository.include_dev_deps` is
finally functional; unclassifiable findings are never silently hidden.
Amendment (review B1, 2026-10-22): `SECRET_PLACEHOLDER` is decided on the FILE PATH ONLY — never the masked
preview (`****` is the D-027 mask for any short/real secret, and a real token can begin chan/exam/plac, so a
preview can never mark a placeholder). Placeholder paths are narrowed to `*.example`/`*.sample`/`*.template`
suffixes and `example/`/`examples/` dirs. VALUE-based detection (e.g. the literal `changeme`) is DEFERRED; if ever
added it MUST be computed IN-CONTAINER with only its boolean verdict leaving — the raw secret must never reach the
app. Proven by `SecretPlaceholderTest` + `SecretPlaceholderRegressionIT`.
Amendment (review S4, 2026-10-22): declared scope is keyed by (manifest path, coordinate), NOT a flat coordinate
map — a finding is auto-ignored dev/test only when ITS OWN manifest declares it so. The in-container inventory
drops any coordinate declared PRODUCTION in any manifest of the repo (repo-wide prod set), and for npm reads
`package-lock.json` per-package `"dev"` flags (transitives); a package that is `dev:false` anywhere stays visible.
Proven by `scope_inventory_test.py` + `DevTestScopeIT.scopeIsKeyedPerManifestNotFlat`.

## D-058 Manual ignore model: ignore_rule (V17) applied through reconcile (2026-10-21, accepted)
Context: SPEC "Manual ignore scopes" (sub-issue, group, path prefix per repo, CVE global/per-repo, rule id
global/per-repo), each with a reason; D-029 noted bulk/manual ignore arrives in M7.
Decision: V17 (additive) creates `ignore_rule` (`id, workspace_id, scope in {subissue,group,path,cve,rule},
repository_id NULL=global, match_value, reason NOT NULL, created_by, created_at`) plus nullable
`sub_issue.ignored_by_rule_id` and `sub_issue.ignored_at`. `ignored_by_rule_id` is `ON DELETE SET NULL` (review
change #2) so deleting a rule never deletes the finding. Apply/remove runs through reconcile: a match →
`ignored` + `triage_event`; unignore → the finding returns to the RECONCILE-COMPUTED status (open/solved) +
`triage_event`. An active rule keeps a finding ignored when seen again (hard rule 6). `sub_issue.ignore_reason`
stores the reason CODE; the human reason comes from `ignore_rule.reason` via `ignored_by_rule_id`. `workspace_id`
on the table and every query. Unignore status is computed by `TriageStatusResolver` keyed on the latest scan
where the finding's SCANNER SUCCEEDED (a failed/absent scanner closes nothing → returns to OPEN, review
change #4). Manual unignore applies to MANUAL ignores; auto-ignores clear via their cause (review change #10).
Consequences: full manual triage with an audit trail; one new table + two nullable columns.

## D-057 `.secscan.yml` honored but AUDITED, parsed in-container, with a revoke lifecycle (2026-10-21, accepted)
Context: a carve-out of D-037 (in-repo suppressors ignored). The SPEC `.secscan.yml` lets a repo owner exclude
paths, ignore specific CVEs (with a reason) and opt into dev dependencies — but for a security product every
suppression must be surfaced and audited, never a silent drop.
Decision: `.secscan.yml` is optional, owner-authored, needs no repo change (D-030). It is read IN THE SCANNER
CONTAINER (review change #9 — never copied to `/out`, which would reintroduce the M6 symlink-exfil risk), parsed
to a bounded JSON that leaves via `/out`, and the WORKER applies it at triage step 9:
- `exclude.paths` → `EXCLUDED_PATH`; `ignore.cves{reason}` → `IGNORE_RULE` (repo reason shown, materialized as an
  idempotent system `ignore_rule` row, `created_by='secscan.yml'`, `scope=cve`, repo-scoped);
  `include_dev_dependencies` feeds the D-059 precedence. Every suppression sets `ignored` + writes a
  `triage_event` (actor=system) and is visible in the Ignored view.
- **Parser hardening (review change #1 — the M6 symlink→token-leak class):** `scanner-image/secscan_config.py`,
  `python3 -I`, stdlib + **PyYAML `yaml.safe_load` ONLY** (never `yaml.load`/full loader), no network, data-only
  (no import/exec of repo content). Symlink-safe + realpath-confined to the checkout (a symlinked `.secscan.yml`,
  or one resolving outside the clone, is refused with a warning). WHITELIST + BOUND what leaves via `/out`: only
  `exclude.paths`, `ignore.cves{reason}`, `include_dev_dependencies`, with caps on count and string length;
  unknown/oversized keys dropped with a warning; raw file bytes never echoed to `/out`.
- **Revoke lifecycle (review change #2):** each scan reconciles the `.secscan.yml` system rules to the CURRENT
  file — add new, REVOKE any whose cve/path is gone (clear-then-delete in the same tx, or `ON DELETE SET NULL`),
  and re-evaluate (unignore) the affected findings with a `triage_event`.
- **PyYAML** is pinned reproducibly in the scanner image (recorded here); `safe_load`-only is a guarded invariant.
Proven by `SecscanYmlIT` (honored + surfaced-not-dropped, and removal re-evaluates) and `SecscanConfigParseTest`
(host, python: safe_load, symlink refusal, whitelist/caps).
Consequences: one audited carve-out of D-037; one new pinned image dependency (PyYAML); the config can only add
visibility or add an audited, revocable suppression — never weaken a scan silently.
Amendment (review S3, 2026-10-22): a config that is PRESENT but could not be safely read/parsed (symlink,
resolves-outside-clone, YAML parse error, non-map) now reports `status="error"` (`secscan_config.py`), distinct
from a genuinely absent config. On a frozen config the worker FREEZES the prior config-derived triage — it does
NOT revoke the materialized CVE rules and does NOT unignore the config-derived `EXCLUDED_PATH` findings — and
raises a visible `SECSCAN_CONFIG_INVALID` scan warning; every other triage source still runs. So a transient
refusal or a typo never silently un-suppresses every `.secscan.yml` ignore. Proven by `SecscanYmlIT`
(`anInvalidConfigFreezesPriorIgnoresAndWritesNoTriageEventFromThatSource`).

## D-056 Opengrep needs a dedicated exec-able tmpfs — a bounded hardening carve-out (2026-10-20, accepted)
Context: Opengrep 1.31.0 (musllinux) is a PyInstaller single-file binary that, on every run, unpacks its own
interpreter + `opengrep-core` under `$HOME` and execs the core. The scanner's `/work` tmpfs is `noexec` (so no
cloned repo code can run, D-004), which blocks that unpack+exec. This is a change to scanner hardening and must
be justified, not silent (hard rule #7).
Avoidance investigated and ruled out: (a) pre-unpacking the runtime onto the read-only image layer does NOT
work — Opengrep re-unpacks and re-execs into a writable+exec dir at runtime even with a pre-warmed read-only
`$HOME` (verified: it fails with "couldn't launch child (exec): Permission denied" when the writable dir is
`noexec`); (b) the release ships no standalone musl `opengrep-core`, only glibc `opengrep-core_linux_*` which
does not run on Alpine; (c) there is no `opengrep` pip package to install onto the read-only layer. So a
dedicated writable+exec directory at runtime is genuinely required for the pinned release.
Decision: add ONE dedicated EXEC-able tmpfs at **`/opt/sast-exec`** (`ScannerContainerConfig.TOOL_EXEC_MOUNT`),
mounted `rw,exec,nosuid,nodev,mode=1777,size=512m`, SEPARATE from `/work` and `/out`. `run-scan.sh` points ONLY
Opengrep's `HOME`/`TMPDIR`/`XDG_CACHE_HOME` there. Properties (the carve-out is minimal and does not weaken the
repo-code guarantee):
- `/work` (the clone) and `/out` remain **`noexec`**; only `/opt/sast-exec` is exec-able.
- It holds ONLY Opengrep's own unpacked PyInstaller runtime, copies of OUR rule configs, and Opengrep's run log.
  **No scanned-repo file is ever copied onto it or executed from it**, and the scanned repo cannot write to it
  (only Opengrep does). The one repo-derived artefact is Opengrep's own debug log (it may name scanned paths /
  matched lines); it never leaves the throwaway container (only `/out` leaves).
- The scanned repository stays on `noexec` `/work` and is never executed — "never execute scanned code" (D-004)
  is unchanged.
- All other container controls are unchanged: `--cap-drop=ALL`, `--no-new-privileges`, read-only root
  filesystem, 2 CPU / 4 GB / 512 PID limits, 30-min hard timeout, and NO Docker socket mount.
Consequences: one bounded, auditable writable+exec mount for one trusted pinned tool. Revisit at an Opengrep
release that ships a standalone musl core or a pip distribution — then run the core from the read-only image
layer and drop this mount.

## D-055 EOL via a static detector + endoflife.date API v1, with a never-false-solve guard (2026-10-20, accepted)
Context: M6 adds end-of-life detection for runtimes/frameworks. The SPEC uses our own detector + endoflife.date
data; we must never run a build (D-004) and never false-solve a finding.
Decision: a **static** detector (`scanner-image/eol_detect.py`, `python3 -I`, stdlib, reads files as data —
Spring Boot, Java, .NET TargetFramework, Node `.nvmrc`/`engines`, Dockerfile base images, Keycloak) emits
`{product, cycle, filePath}` candidates and reaches no network; its unit tests run on the host
(`eolDetectTest`, D-039 pattern). The worker joins candidates against the **`eol_cycle`** intel cache to decide
which are issues.
- **endoflife.date v1** (`https://endoflife.date/api/v1`, MIT): the weekly `EolSyncService` (api profile) does a
  **per-product UPSERT** of `/products/<slug>/` release cycles into `eol_cycle` — it **NEVER wipes** (a failed
  or empty product fetch keeps that product's existing rows and fails loud; other products still sync). One new
  api-side egress host `endoflife.date` (beside KEV/EPSS; D-033). TLS always on. The v1 schema is camelCase
  (`name`=cycle, `isEol`+`eolFrom`, `isLts`, `isMaintained`, `latest.{name,date}`, `releaseDate`); `eol_cycle`
  column types mirror it. Confirmed slugs: `spring-boot`, `dotnet`, `nodejs`, `keycloak`, `alpine-linux`.
  **There is no `java` product in v1** — a bare Java language level uses the slug **`eclipse-temurin`** (the
  configurable default; a Dockerfile `FROM <vendor-jdk>:N` maps to the named distro slug). `/eol/sync` is added
  to `AdminIntelController` (OD-9 endpoint list extended for M10 role-gating).
- **Cycle normalization (review BUG 2, `core.eol.EolCycles`):** products key cycles differently — .NET as bare
  majors (`8`), Spring Boot / Alpine as major.minor (`2.1`, `3.21`). The detector's cycle is normalized to the
  feed's OWN published cycle key per product, GENERICALLY (no per-product rules): match the detected version
  against the product's published cycles and take the most specific (longest) one whose numeric components are
  a prefix of the detected version's (component-wise, so `2.1` never matches `2.10`). So `8.0`→`8`, `2.1`→`2.1`,
  `3.21`→`3.21`; the NORMALIZED cycle is the frozen fingerprint input (Spring Boot/Alpine are unchanged). No
  published cycle matches → the coverage gap below.
- **BLOCKER 2 / review BUG 1 — never false-solve, PER (product, cycle):** `EolEnricher` keeps only covered,
  issue-worthy INCOMING detections (past-EOL / ending-soon within 90 days, `core.eol.EolStatus`); a detection
  with no matching published cycle is dropped with an `EOL_COVERAGE_GAP` warning. Solvability is NOT scanner-wide:
  `scan_task.closes_findings` stays per-scanner (= the EOL scanner succeeded — unchanged behaviour for every
  type), and whether an EXISTING open EOL finding may be solved is decided **per fingerprint** in
  `ReconcileService.eolCoverageGuard` — an open EOL sub-issue whose stored `(product, cycle)` has no `eol_cycle`
  coverage is FROZEN (left untouched, never solved), exactly like a superseded secret under a replaced key
  (D-029). So one uncovered product (e.g. an unsynced .NET) never blocks solving a fully-covered product
  (Spring Boot, Alpine) in the same scan, and a covered finding that genuinely disappears still solves. An EOL
  finding is solved only when the scanner succeeded AND its `(product, cycle)` is covered AND it is now absent.
  Proven by `EolSolvabilityIT` (`aRescanWithEmptyEolCacheDoesNotSolveAnOpenEolFinding` and
  `aCoveredProductSolvesEvenWhenAnUncoveredProductIsInTheSameScan`).
- **Fingerprint / overload:** EOL fingerprint is `product | cycle | file_path` (`core.Fingerprints.eol`, NO line
  number; `cycle` is the NORMALIZED cycle). `product` reuses the `package` column and `cycle` the
  `installed_version` column (documented in `ReconcileService.fingerprintOf`), so no new business column is
  added. `eol_cycle` is the one new table (V16), a global cache with no `workspace_id` (D-050). The EOL base
  (past-EOL 75 / ending-soon 45) is wired in Slice 4.
Consequences: offline static EOL detection with faithful, conservative issue classification; one new table, one
new api egress host, and the detector reaches no network. Java EOL tracks a single configurable JDK distro slug
(revisit if per-vendor JDK tracking is needed).

## D-053 Opengrep (SAST) pinned 1.31.0 (musllinux), with baked rule packs and ignored in-repo suppressors (2026-10-20, accepted)
Context: M6 adds SAST. SPEC/D-005 chose Opengrep (LGPL-2.1 Semgrep fork); we pin every scanner by version +
checksum (D-034/D-036).
Decision: pin Opengrep **1.31.0** (stable, released 2026-10-15; NOT the 2.0.0 alpha line). The base image is
Alpine (musl), so we bake the **musllinux** per-arch binaries — the `manylinux` (glibc) builds do NOT run on
Alpine (they fail with exit 127, confirmed). Checksums verified before use:
`opengrep_musllinux_x86 = 02c9fba481611aa0664bc79ccf7ce59e9e334b76af8921c80b2f738d17fabf9b`,
`opengrep_musllinux_aarch64 = b7cbedab0b6c9953ea27ce3baa006ad8b6fa1c50ee7433638b63f7fc731519d9`. Version recorded
in `scan.tool_versions.opengrep`. (The glibc `manylinux` checksums from the plan are NOT pinned — those builds
fail on Alpine.)
- **Provenance (Cosign, SHOULD-FIX 3):** the Dockerfile ALSO verifies the release's keyless Sigstore signature
  before pinning — it downloads the asset's `.sig` + `.cert` and runs `cosign verify-blob` with
  `--certificate-identity-regexp 'https://github.com/opengrep/opengrep/.*'` and
  `--certificate-oidc-issuer 'https://token.actions.githubusercontent.com'`, so the binary must have been signed
  by Opengrep's own GitHub Actions identity. Cosign itself is pinned (v3.1.3, per-arch SHA-256) and is build-time
  only (removed, never in the runtime image). Verified OK against v1.31.0's musllinux assets.
- **Offline:** `--disable-version-check`. Opengrep has **no `--metrics` flag** (Semgrep's telemetry was removed),
  so there is nothing else to disable; the container also runs with the network disabled for fixture ITs.
- **In-repo suppressors ignored (D-037):** rules come ONLY via `--config` (no in-repo rule auto-load);
  `--disable-nosem` ignores inline `nosemgrep`/`nosem`/`noopengrep`; `--x-ignore-semgrepignore-files` ignores
  every in-repo `.semgrepignore`. Proven by `OpengrepConfigInjectionIT`.
- **Rule packs (frozen by commit SHA so every `check_id`, a fingerprint input, is stable):** (a)
  AikidoSec/opengrep-rules @`7ac79affecf709eb7263a243b518a417cd7e0ab2` (MIT); (b) select language packs
  (java, csharp, javascript, typescript) from opengrep/opengrep-rules @`f1d2b562b414783763fd02a6ed2736eaed622efa`
  — **Commons Clause** (restricts resale/SaaS): INTERNAL USE OK, **FLAGGED** for the open "SAST rule licensing
  before any SaaS" SPEC item and the D-005 note (does NOT block internal M6); (c) our custom Spring/.NET rules
  (MIT, `scanner-image/opengrep-rules-custom/`). NEVER `semgrep/semgrep-rules` (restrictive Semgrep Rules
  License). Opengrep runs from `/etc/secscan` with a relative `--config opengrep-rules`, so `check_id`s are the
  stable `opengrep-rules.<pack>.<id>` (never an absolute path); the parser relativizes the absolute checkout
  file paths.
- **Base score:** SAST base from the rule severity label — `extra.metadata.severity` (CRITICAL/HIGH/MEDIUM/LOW)
  if present, else `extra.severity` ERROR→HIGH(75) / WARNING→MEDIUM(50) / INFO→LOW(25); CRITICAL(90) only via
  metadata. Recorded as `base_sast` in `score_breakdown`.
- **Exec-tmpfs carve-out (full rationale in D-056):** Opengrep unpacks its (trusted, pinned) core and execs it
  from HOME/TMPDIR; `/work` is tmpfs `noexec` (so no cloned repo code can ever execute, D-004). run-scan.sh
  points ONLY Opengrep's HOME/TMPDIR/XDG_CACHE_HOME at a separate exec-able tmpfs `/opt/sast-exec`
  (`ScannerContainerConfig.TOOL_EXEC_MOUNT`, `nosuid,nodev,mode=0700` owned by the scan uid:gid, `size=512m`).
  The scanned repo is never placed on an exec mount, so "never execute scanned code" is unchanged.
- **Snippet sanitize (S1):** Opengrep's `extra.lines`/`extra.metavars` carry matched SOURCE LINES (including
  secrets our own rules match), so run-scan.sh computes the normalized-snippet SHA-256 INSIDE the container and
  strips `extra.lines`/`extra.metavars` before writing `/out` (mirrors D-023/D-027); only the hash leaves the
  container. The parser reads that hash — it never sees the raw line.
- **Snippet basis — DECIDED: whole matched region, normalized (not the metavar sub-capture).** The hash is
  computed over the full set of SOURCE LINES Opengrep reports as the match (`extra.lines`), whitespace-normalized
  (`awk 'NF>0{$1=$1;print}'` — blank lines dropped, runs of whitespace collapsed, leading/trailing trimmed),
  NOT over the per-metavar capture or a byte offset. Reasoning: (a) the whole-region text is stable across the
  cosmetic edits (reindentation, reflowed whitespace) that would otherwise churn the fingerprint, whereas a
  byte offset moves on any edit above the match and a metavar capture is often just an identifier that collides
  across unrelated matches; (b) it needs no AST/column data we would have to carry out of the container. Known
  caveat: a *substantive* edit to the matched lines (not just whitespace) yields a new fingerprint, so the old
  finding solves and a new one opens — a one-time re-fingerprint, acceptable because triage history for a
  changed snippet is genuinely a new finding. Ignore-churn minimization (carrying an ignore across such a
  re-fingerprint) is out of scope for M6 and deferred to M7. This basis is frozen with the other fingerprints.
- **Fingerprint / overload:** SAST fingerprint is `rule_id | file_path | normalized_snippet_hash` (`core.Fingerprints.sast`;
  snippet normalized + hashed in the container, NO line number). No new business column is added: `rule_id`, `file_path`,
  `snippet_hash` already exist. (IaC reuses the `package` column for the resource name and EOL reuses
  `package`/`installed_version` for product/cycle — the overload is documented in `ReconcileService.fingerprintOf`
  and D-054/D-055.)
Consequences: a maintained, reproducible SAST engine; rule-pack licensing stays flagged for SaaS; a small,
isolated exec-able tmpfs is the only hardening carve-out and does not weaken the repo-code guarantee.

## D-054 IaC = Checkov 3.3.29 (static frameworks only), with skip recovery and ignored in-repo config (2026-10-20, accepted)
Context: M6 adds IaC. We chose Checkov (not KICS) for graph-based cross-resource analysis (Apache-2.0,
Palo Alto/Bridgecrew). We pin every scanner.
Decision: pin **Checkov 3.3.29** (the current line; the 3.2.x line ended at 3.2.533 on 2026-06-04, so "latest
3.2.x" is superseded — the rationale, licence and engine are unchanged). Checkov is Python, so "pin" = install
with pip into an isolated venv constrained by `scanner-image/checkov-constraints.txt` — the full transitive
closure pinned at exact versions (captured from a clean Alpine 3.21 venv) — so the install is reproducible.
Checkov installs cleanly on Alpine musl (musllinux wheels; no build deps). Version in
`scan.tool_versions.checkov`. Rejected KICS (no cross-resource graph, smaller TF ruleset, a remediated Mar-2026
GitHub-Action supply-chain incident).
- **BLOCKER 1 (D-004 — never execute scanned code):** only STATIC-parse frameworks are enabled —
  `dockerfile, kubernetes, terraform, cloudformation`. **Helm and Kustomize are NOT run** (Checkov renders them
  by shelling out to the `helm`/`kustomize` binaries, which we do not install). The Helm-chart-rendering gap is
  shown, not hidden (D-030): run-scan.sh raises an `IAC_HELM_NOT_RENDERED` warning when a `Chart.yaml` is
  present. **Checkov 3.3.29 has NO `docker_compose` framework** (the planned framework list named it, but the
  tool does not offer it — valid values are dockerfile/kubernetes/terraform/cloudformation/helm/kustomize/…),
  so docker-compose misconfig coverage is a recorded gap too: run-scan.sh raises an
  `IAC_DOCKER_COMPOSE_UNSUPPORTED` warning when a `docker-compose.{yml,yaml}`/`compose.{yml,yaml}` is present
  (D-030 — gap shown, not hidden; deferred register).
- **Offline (SHOULD-FIX 3):** `--skip-download` (baked in `checkov.yaml`) + env `CKV_SKIP_PACKAGE_UPDATE_CHECK=True`
  suppress every network call; the config-injection ITs run the container with the network disabled to prove it.
- **In-repo config ignored (D-037):** passing our `--config-file` does NOT stop Checkov reading and honoring an
  in-repo `.checkov.yaml`/`.checkov.yml` (verified: its `skip-check` silently suppresses findings even with our
  `--config-file`). So run-scan.sh **deletes** every in-repo Checkov config from the EPHEMERAL in-container clone
  before scanning (the clone dies with the container; the real repo is untouched, D-030). The deletion is
  CLONE-SCOPED (rooted at `$CHECKOUT`), covers BOTH `.checkov.yaml` and `.checkov.yml` at ANY depth (nested
  modules), and is SYMLINK-SAFE: `find` runs in `-P` mode (never follows a symlink) and deletes the symlink
  ENTRY too (`-type l`), so Checkov can never follow a symlinked `.checkov.yaml` out of the clone and a symlink
  target outside the clone is never touched. Inline `#checkov:skip=` CANNOT be disabled by a flag, but a skipped
  check is still reported in `results.skipped_checks[]` — the parser **recovers** those and promotes them back
  to findings. (`quiet` is NOT set in `checkov.yaml`: it would strip `skipped_checks` and defeat the recovery.)
  Proven by `CheckovConfigInjectionIT` — `inlineSkipIsRecoveredAndInRepoCheckovConfigIsIgnored` (root inline +
  root config) and `nestedAndSymlinkedInRepoConfigsAreIgnored` (a nested `.checkov.yaml` and a symlinked one
  both ignored, findings still fire).
- **JSON shape:** multi-framework output is a JSON **array** of per-`check_type` report objects; a single
  matching framework is one object; an empty scan is a summary-only object (no `results`). The parser handles
  all three. Fields used: `check_id` (→ rule_id), `file_path` (leading `/` stripped → repo-relative), `resource`
  (→ the IaC resource name, stored in the `package` column, **normalized** — see below), `file_line_range`
  (display only, never a fingerprint input).
- **Resource normalization (frozen fingerprint input):** Checkov reports file-based frameworks (Dockerfile) as
  `"<leading-slash path>.<stage>"` — a stageless Dockerfile yields e.g. `"/iac/docker/Dockerfile."` (leading
  `/`, trailing `.` for the empty stage), unlike the already-clean Terraform resources (`aws_s3_bucket.foo`).
  Since `resource` is a FROZEN fingerprint input, `ScannerOutputParser.normalizeIacResource` makes it stable and
  repo-relative: collapse `//`, strip a leading `/`, strip a single trailing `.`. A stageless Dockerfile's
  resource therefore becomes its repo-relative path (the chosen stable synthetic resource; a named stage keeps
  `.<stage>`), while Terraform / Kubernetes / CloudFormation resources are unchanged. Deterministic, so a clean
  rescan is 0-triage.
- **Base score (NIT 7):** OSS Checkov checks carry no `severity` (null), so **all IaC findings score base 50
  (MEDIUM)** in M6 (`base_iac`). A maintained per-`check_id` severity-override table is deferred (deferred
  register).
- **Fingerprint / overload:** IaC fingerprint is `rule_id | file_path | resource_name` (`core.Fingerprints.iac`,
  NO line number). `resource_name` reuses the existing `package` column (documented in
  `ReconcileService.fingerprintOf`), so no new business column is added.
Consequences: a reproducible, offline, static IaC engine; Helm and docker-compose coverage are explicit gaps;
no schema change for IaC.

## D-052 SCA group fix-time is one upgrade (max category), not the sum of its sub-issues (2026-10-19, accepted)
Context: an SCA issue group is one vulnerable package; several CVEs on it are remediated by a SINGLE dependency
upgrade. Summing each CVE's fix-time (as M5 first did) multiplied one upgrade by the number of CVEs, badly
over-stating remediation effort (e.g. a package with a minor and a major CVE reported 75 min for what is one
bump).
Decision: for `type = sca`, the group `fix_time_minutes` is the LARGEST single fix-time category among the open
sub-issues — a major bump (60) if any open vuln needs one, else patch/minor (15) — never the sum. Every other
type (one fix per finding) keeps the sum of its open sub-issues' fix times. Implemented in
`GroupRollupService.fixTimeMinutes`; proven by `ScoringReconcileIT.scaGroupFixTimeIsTheSingleWorstUpgradeNotTheSum`.
Consequences: SCA fix-time reflects the single upgrade that clears the group. Revisit if a package ever needs
multiple non-coalescable upgrades.

## D-051 Persist cve_aliases on sub_issue + scan_finding for KEV matching and DB-only re-score (2026-10-19, accepted)
Context: KEV membership is "ANY CVE alias is in kev_entry" (D-046), and the re-score job (D-049) recomputes
`in_kev` from the database alone (no rescan), so the CVE aliases must be persisted, not just the single preferred
`vuln_id`.
Decision: add `cve_aliases jsonb` to `sub_issue` (and `scan_finding`, which reconcile reads to build the
sub-issue). The OSV parser fills it with every `CVE-…` in the alias group's ids+aliases. The scorer matches KEV
(and exposes the max EPSS) over these aliases plus the preferred id when it is a CVE. `cve_aliases` is
descriptive scoring input and is **NEVER a fingerprint input** — the SCA fingerprint stays `package | vulnId |
manifestPath` (D-035), so persisting aliases cannot cause a mass solve/reopen (proven by `ReScoreIT`: a re-score
changes the score with 0 triage_event rows and an unchanged fingerprint).
Consequences: one extra descriptive column on two tables; faithful "any CVE alias" KEV matching that also works
in the rescan-free re-score path.

## D-050 KEV/EPSS/fix-time are global reference/config tables, no workspace_id (2026-10-19, accepted)
Context: domain rule 1 puts `workspace_id` on every business table, but the SPEC data model already lists
`epss_score`/`kev_entry` as CVE/product-keyed intel caches alongside the advisory cache, i.e. shared public
reference data, not per-tenant business data; `fix_time_config` is global tuning.
Decision: `kev_entry`, `epss_score` and `fix_time_config` carry **no** `workspace_id` — the justified exception
to domain rule 1 for global caches. They are keyed by CVE / category and joined to workspace-scoped `sub_issue`
rows; the scoring query's scoping lives on the `sub_issue` side. The intel sync and the re-score sweep are
system maintenance jobs that run across workspaces; the re-score iterates each workspace and loads that
workspace's open sub-issues, so every sub-issue/repository/group query remains workspace-scoped by the row's own
`workspace_id`.
Consequences: no per-workspace duplication of ~1.3k KEV + ~350k EPSS rows. Revisit when multi-tenant intel
isolation or per-workspace fix-time tuning is needed (then add `workspace_id` + a default/global fallback row).

## D-049 Re-score job recomputes OPEN sub-issues after a KEV/EPSS refresh, without rescanning (2026-10-19, accepted)
Context: KEV/EPSS refresh daily; stored scores must reflect new intel without waiting for the next scan.
Decision: an api-side `ReScoreService.rescoreAll()` recomputes `score`/`score_breakdown`/`in_kev`/`epss` on OPEN
sub-issues via the same `SubIssueScorer` that scan-time reconcile uses, then rolls affected groups up. It writes
**no `triage_event`** and never changes status or the fingerprint (scoring is derived, domain rule 2). Triggered
after each feed load (scheduler) and via `POST /api/v1/admin/intel/rescore`.
Consequences: fresh scores between scans with zero risk of a scoring change mass-solving/reopening (proven by
`ReScoreIT`). It is a cross-workspace maintenance sweep (see D-050).

## D-048 CWE ids from OSV; a seeded CWE→phrase lookup drives the group subtitle (2026-10-19, accepted)
Context: the group subtitle is a plain-language CWE phrase of the worst sub-issue (SPEC "Grouping").
Decision: CWE ids come from OSV `vulnerabilities[].database_specific.cwe_ids` (array; may be empty). A maintained
seeded lookup (`core/src/main/resources/cwe-phrases.properties`, implemented as a core resource rather than a DB
table so it is unit-testable without Spring) maps a CWE id to a phrase, covering the fixtures' CWEs with
headroom (~150 entries). Within one finding's multiple cwe_ids the **first** id in OSV array order is used (CWE
"worst" is undefined — change #4); an empty/unknown first id falls back to the advisory summary (persisted as
`sub_issue.advisory_summary`, V14). Only sca/sast/iac groups derive the subtitle this way; secret/eol keep their
SPEC title and never break on empty cwe.
Consequences: readable subtitles; a maintainable dictionary; one descriptive column (`advisory_summary`).

## D-047 EPSS synced daily, stored and displayed, adjustments OFF in M5 (2026-10-19, accepted)
Context: EPSS tiers can reduce noise but are opt-in (SPEC: off by default; an M7 toggle).
Decision: a daily `EpssSyncJob` (api profile) loads the FIRST bulk CSV
(https://epss.cyentia.com/epss_scores-current.csv.gz) into `epss_score` (cve, epss, percentile) via a bulk
staged swap (temp table + upsert/delete, never ~350k row-by-row upserts — change #3). The value is stored and
exposed on the sub-issue API, but EPSS score adjustments stay OFF (the scorer never reads EPSS). Attribution is
required: EPSS data © FIRST.org — surfaced as `SubIssueView.epssAttribution` wherever an EPSS value appears.
Consequences: EPSS visible now, tunable in M7; a new api-side egress host (see D-033).

## D-046 KEV synced daily; any CVE alias on KEV adds +5 (2026-10-19, accepted)
Context: CISA KEV is the "known exploited" signal (SPEC: +5, always on).
Decision: a daily `KevSyncJob` (api profile) loads the CISA KEV JSON feed
(https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json) into `kev_entry` keyed on
CVE (full refresh). A sub-issue whose vuln id or **any** CVE alias (D-051) is in `kev_entry` gets +5 and an
`on_kev` line in `score_breakdown`. KEV is US-gov public domain. The done-when is proven offline with a seeded
`kev_entry` (`ScoringReconcileIT`); the live feed is validated by `@Tag("live-intel")` tests excluded from the
default build, and the fixture∩live-KEV intersection is reported from that run.
Consequences: an exploited-in-the-wild finding always scores at least High-ish; a new api-side egress host
(see D-033).

## D-045 Secret base score by Betterleaks rule id (2026-10-19, accepted)
Context: secrets have no CVSS; the base must come from the kind of secret (SPEC: private keys/cloud creds 90,
other tokens 75, generic passwords 60).
Decision: map the Betterleaks rule id → base: private-key & cloud-credential rules 90, generic-password rules
60, all other tokens 75. Live rules today: `private-key`→90, `github-pat`→75. An **unmapped** rule scores 75 and
is logged (by `SubIssueScorer`; the pure `Scoring` exposes `isMappedSecretRule`). Recorded in `score_breakdown`
(`base_secret`).
Consequences: secret scoring without a CVSS calculator; new rules default safely and are flagged for mapping.

## D-044 CVSS base for SCA = max_severity × 10, else advisory label; cvss/severity_label split (2026-10-19, accepted)
Context: SCA base is the highest CVSS base × 10, with an advisory-severity fallback when there is no vector (SPEC
"Base score"). OSV's `groups[].max_severity` already folds in CVSS v4 and tracks the higher of v3/v4.
Decision: base = `max_severity × 10` (the numeric OSV value, persisted as `tool_severity`), clamped 1–100; when
`max_severity` is "" fall back to `database_specific.severity`: CRITICAL 95 / HIGH 80 / MODERATE 55 / LOW 25. No
CVSS calculator is built. The base source (vector vs label) is recorded in `score_breakdown`
(`base_cvss`/`base_label`). **cvss/severity_label split (change #2):** the `cvss` column stores the chosen CVSS
**vector string only** (v4 preferred over v3, null when none); a separate `severity_label` column stores the
advisory label. The scorer/re-score recompute the base from these persisted inputs (`tool_severity` number,
else `severity_label`) and never read anything scoring-related back out of the `score_breakdown` jsonb.
Consequences: a faithful, explainable base with no calculator; two descriptive columns (`cvss`,
`severity_label`). The single scoring function (`core` `Scoring.score`) is called by both scan-time reconcile
and the re-score job.

## D-043 Full NuGet transitive resolution deferred; lockfile-less .csproj surfaces an honest gap (2026-10-18, accepted)
Context: OSV-Scanner v2.7.0 does transitive dependency resolution only for Maven `pom.xml` and Python
`requirements.txt`. NuGet is lockfile-only: a bare `.csproj` yields DIRECT `<PackageReference>` versions only,
under any flag (D-035). The Gradle BOM-fetch approach (D-042) does not transfer to NuGet, and no real .NET repo
is onboarded yet to validate a NuGet resolver against.
Decision: defer full NuGet transitive resolution. Build NO deps.dev NuGet resolver, NO synthetic
`packages.lock.json`, NO `.csproj` transitive expansion. Instead make the lockfile-less case HONEST (D-030): a
`.csproj` with no sibling `packages.lock.json` raises a new `NUGET_NO_LOCKFILE_TRANSITIVE_GAP` warning (free-text
code, no migration) stating that transitive NuGet deps are not resolved without a lockfile. This is ADDITIONAL
to the existing `MISSING_NUGET_LOCKFILE` warning (both coexist, same gating); the D-035 dedup keys on
`MISSING_NUGET_LOCKFILE` only and is unchanged. A `.csproj` WITH a lockfile is unchanged — OSV reads it for full
transitives and neither NuGet warning fires.
Consequences: lockfile-less .NET repos still get their direct-dependency findings plus a visible, named coverage
gap instead of a silent partial result; full NuGet transitive resolution is revisited when a real .NET repo is
onboarded. Resolve-it-ourselves (as in D-042 for Gradle) remains an option then.

## D-042 Gradle resolver fetches BOM POMs from Maven Central to pin every version (2026-10-18, accepted)
Context: D-041 imported the governing BOM into the synthetic pom's `<dependencyManagement>` and emitted
BOM-managed dependencies version-less, expecting OSV's `pomxml` transitive enricher to inherit the versions.
Measured on ib-api-v2 (@3be435d5) this failed: OSV does NOT inherit versions from a `<dependencyManagement>`
import and filters version-less dependencies as unscannable — only 44/151 (~29%) resolved, far below the ≥95%
target. All inputs are static in `build.gradle`; we must not run a build (D-004) and must not guess (D-035).
Decision: the resolver itself fetches the governing BOM POMs from Maven Central and writes a concrete
`<version>` on EVERY dependency it reports, so OSV has nothing version-less to filter. It builds the managed-
version map by reading each BOM's `<dependencyManagement>` and resolving `<properties>` interpolation, the
`<parent>` chain (fetched too) and nested `<scope>import</scope><type>pom</type>` sub-BOMs (fetched + merged
recursively — `spring-boot-dependencies` imports ~24). BOM coordinates are those D-041 already detects (Boot
plugin version, `platform()`/`enforcedPlatform()`, `mavenBom "..."`, `${prop}` from ext/set/extra/properties).
A dependency unresolved after full expansion → `GRADLE_UNPARSEABLE_CONSTRUCT` (never guessed/dropped); a BOM
that cannot be fetched → `GRADLE_BOM_FETCH_FAILED`, scan continues. Network: read-only HTTPS GET of `.pom` from
ONE host only — Maven Central (`repo.maven.apache.org`, already allowed by D-033) or the `SECSCAN_MAVEN_BASE_URL`
mirror — TLS verification ALWAYS on (never disabled), cross-host redirects blocked, bounded (per-POM timeout
15s, total cap 200/run, parent depth 8, import depth 6, 10 MB cap, in-run cache). `python3 -I` preserved;
fetched POMs are parsed as data, never imported/executed; no build is run.
Consequences: a new runtime egress need for the Gradle resolver step (Maven Central — within the D-033
allowlist); a Boot+Cloud build fetched 60 POMs in ~2.7s and emitted concrete versions on every dependency
(`spring-boot-starter-web`→`2.6.5`, `spring-cloud-starter-openfeign`→`3.1.4`). Supersedes the version-less
emission (and BOM-as-`<type>pom>` trigger) of D-041; D-041's BOM detection is retained.

## D-041 Gradle synthetic pom imports BOMs for Spring Boot/Cloud BOM-managed deps (2026-10-18, superseded by D-042)
Context: M4b Gradle repos (Spring Boot/Cloud) declare most dependencies with no inline version (BOM-managed), so
the D-038 synthetic pom listed only inline-versioned deps and OSV resolved almost nothing for them.
Decision: detect the governing BOMs — Spring Boot Gradle plugin version → `spring-boot-dependencies`,
`platform()`/`enforcedPlatform()`, `mavenBom "..."` (with `${prop}` from `ext{}`/`set()`/`extra[]`/
`gradle.properties`) — import them into the synthetic pom's `<dependencyManagement>` as
`<scope>import</scope><type>pom</type>`, emit BOM-managed deps version-less, and (because OSV's resolver only
engages with a concrete-versioned dep) inject the first BOM as a `<type>pom>` trigger. Broaden ext interpolation
to all declarations; surface a write failure as a warning.
Consequences: the BOM DETECTION and ext interpolation are retained, but the version-less emission + trigger did
not actually resolve versions in OSV (measured 44/151 on ib-api-v2) and are **superseded by D-042**, which
fetches the BOMs and pins every version explicitly.

## D-040 Secrets fixture uses a fake GitHub PAT, not an AWS key (2026-10-17, accepted)
Context: Betterleaks v1.7.3 reworked `aws-access-token` into a composite rule (an access key ID must be paired
with a validatable secret access key) and does not fire offline on a lone fake AWS key; the old
`fixtures/secrets-aws` key also paired with AWS's documented EXAMPLE secret, which is allowlisted. So the M4
secrets fixture no longer proves detection under the new engine.
Decision: the secrets fixture plants an obviously-fake GitHub personal access token (`ghp_…`), detected under
the stable, specific `github-pat` rule (identical id in both engines). `fixtures/secrets-aws` is renamed to
`fixtures/secrets-token`; this also updates the M2 done-when fixture (`ScanPipelineIT`) and the sample dir
`app/src/test/resources/samples/gitleaks` → `betterleaks`. The real open secret in `secscan-test-secrets` is an
RSA private key (`config/deploy-key.pem`, `private-key`), which Betterleaks detects offline unchanged (same rule
id, matched as the whole PEM block) — it is NOT affected by this fixture change and is not modified.
Consequences: a reliably-detected synthetic secret under the new engine; no real project secret changes.

## D-039 Gradle/NuGet static resolver is a Python 3 script in the scanner image (2026-10-17, accepted)
Context: the M4b static Gradle resolver (and NuGet transitive resolution) must run where the clone exists and
where OSV runs — inside the scanner container — so it cannot live in `core` as unit-testable Java. The existing
in-container parsing precedent is POSIX sh + xmllint (`secscan-lib.sh`), but ≥95% accuracy on Gradle Groovy/
Kotlin DSL plus TOML (`libs.versions.toml`) is not viable in busybox sh.
Decision: implement the resolver as a Python 3 script shipped in the scanner image (slices 1–2). Binding
constraints: stdlib only (TOML via `tomllib`, Python ≥ 3.11 — confirmed: Alpine 3.21 ships **python3 3.12.15**
with `tomllib`); invoke as `python3 -I`; read scanned-repo files as DATA only, never import/exec/eval from the
scanned tree (D-004); pin the `python3` apk version and record it when it is added; resolver unit tests run
under `./gradlew build` (wired via a Gradle task) or, if impractical, `GradleResolverIT` is the gate. Python is
NOT added to the image in slice 0 (no resolver yet) — only when slice 1 lands.
Consequences: a maintainable, testable resolver; one new image dependency (python3) arriving in slice 1.
Slice 1 (2026-10-18): the Gradle resolver landed as `scanner-image/gradle_resolver.py`, invoked `python3 -I`;
the `python3` apk is pinned to **`python3=3.12.15-r0`** (Alpine 3.21, ships `tomllib`). Its unit tests
(`gradle_resolver_test.py`) run on the host under `./gradlew build` via the `gradleResolverTest` task (the
primary gate); the live-osv `ScaLiveScanIT` is the behavioural proof. NuGet transitive resolution (slice 2) is
not yet done.

## D-038 Gradle synthetic-pom path mapping anchors SCA fingerprints to the build file (2026-10-17, accepted)
Context: the M4b Gradle resolver emits a synthetic `pom.xml` for OSV to resolve transitively, written OUTSIDE
the clone so a scanned repo cannot plant one. OSV then reports the synthetic pom's `/work` path, which the
parser's `UNRESOLVED_PATH` guard (D-035) would drop, and which must never enter a fingerprint anyway.
Decision: synthetic poms live at `/work/synthetic/<n>/pom.xml` (outside the clone); the scanner manifest maps
each to its originating `build.gradle` repo-relative path; the parser rewrites OSV's `source.path` to that
build-file path BEFORE fingerprinting, so a Gradle SCA finding's fingerprint anchors to the real `build.gradle`.
Anything that does not map (a stray `/work` path) still hits the `UNRESOLVED_PATH` guard and is dropped.
Consequences: stable, meaningful Gradle SCA identities; the path-escape guard remains in force. (Implemented in
slice 1.)
Multi-module robustness note (2026-10-19): a multi-module project (≥2 lockfile-less build files ⇒ ≥2 synthetic
poms) produced 0 SCA issues — OSV reported findings under `/work/synthetic/0` and `/work/synthetic/1` but the
map did not carry them, so every finding hit `UNRESOLVED_PATH`. Root cause: the map was carried on the
resolver's **stdout**, and when that stdout did not survive (a killed/OOM'd resolver after some poms were
already written to disk), `run-scan.sh` fell back to an empty map while the pom files remained for OSV to scan —
orphaning every Gradle finding. A single synthetic pom was cheaper and did not trip it. Fix: the resolver writes
a `buildfile` sidecar beside each synthetic pom, and `run-scan.sh` builds the map by walking `$SYNTH` on disk
(each `pom.xml` paired with its sidecar). The map is thus keyed on the pom's real path — exactly what OSV scans
and reports — and can never be lost while the poms exist. The parser also normalizes both sides of the key
(collapse `//`, strip a trailing `/`) as defense in depth. Guarded by a live-osv multi-module regression
(`fixtures/sca-gradle-multimodule`, each module's finding anchored to its own `build.gradle`, `UNRESOLVED_PATH`
count 0) and a 2-entry parser unit test. Separately, OSV's own stdout/stderr is now echoed to the (scrubbed)
container log excerpt on an SCA failure so it is debuggable. No schema change.

## D-037 Scanned-repo suppressions are ignored for every engine (2026-10-17, accepted; resolves OD-8 and the repo-config should-fix)
Context: a scanned repository could otherwise hide its own findings via in-repo scanner config, ignore files or
inline allow comments — unacceptable for a security product, where only SecScan's own audited triage may
suppress.
Decision: all in-repo suppressors are ignored for every current and future engine. A pinned config is baked
into the scanner image and passed with `--config`; every in-repo config file, ignore file and inline allow
comment is ignored. Only SecScan's own audited ignore rules suppress a finding (reason code + `triage_event`).
Implemented in slice 0 for both current engines (verified against the real tools):
- Betterleaks: `--config /etc/secscan/betterleaks.toml` (precedence #1, so an in-repo `.betterleaks.toml`/
  `.gitleaks.toml` is ignored; `[extend] useDefault = true` keeps the full default rule set), plus
  `--ignore-gitleaks-allow` (ignore inline `gitleaks:allow`/`betterleaks:allow`) and `--gitleaks-ignore-path`
  pointed at a baked EMPTY file (so the repo's `.gitleaksignore`/`.betterleaksignore` is never read).
- OSV-Scanner: `--config /etc/secscan/osv-scanner.toml` overrides any in-repo `osv-scanner.toml`
  (`[[IgnoredVulns]]`/`[[PackageOverrides]]`) — verified against v2.7.0: an in-repo `[[IgnoredVulns]]` drops a
  finding, and adding `--config` brings it back.
Proven by `BetterleaksConfigInjectionIT` (secrets, default build) and `OsvConfigInjectionIT` (SCA, `live-osv`).
Consequences: a repo cannot weaken its own scan; the pinned configs may only keep detection strong, never relax
it.

## D-036 Secrets engine Gitleaks 8.21.2 -> Betterleaks v1.7.3 (2026-10-17, accepted; resolves OD-7)
Context: Gitleaks' name/repo control changed hands; the original author started Betterleaks
(github.com/betterleaks/betterleaks, MIT) as its successor. Our rule is to pin every scanner by version +
checksum (D-023/D-034). Done now (not later) because the secret group key is the rule id and the only stored
secret is the test fixture, so the swap re-fingerprints nothing of value (D-029).
Decision: adopt Betterleaks **v1.7.3** (no v2 exists yet). The release ships per-arch tarballs verified against
the published `checksums.txt` before use:
`linux_x64  (amd64) = 9d2ed6fc387bc1c3f95557d5539077b1ce422f0db5ec38db5f14a141ad2947bf`,
`linux_arm64        = 0541cd2980964a6fb8ea768e8b9507c91a3c06229193462f33a3ecd9cb146b99`.
The version is recorded in `scan.tool_versions` (key `betterleaks`). Validation stays OFF (no `--validation`
flag → no live provider calls; `BetterleaksCommandTest` guards against it being added). In-repo suppressors are
ignored (D-037). The scanner image tag stays `secscan-scanner:8.21.2` — a HISTORICAL identifier, not the engine
version (D-034). Revisit at Betterleaks v2.
Probed facts (v1.7.3, recorded not trusted): directory scan is `betterleaks dir <path>`; JSON report is an
array of objects (Gitleaks-compatible fields `RuleID`/`File`/`StartLine`/`Secret`) or the literal `null` when
clean (run-scan.sh tolerates both); exit codes with `--exit-code 2` are 0 = clean, 2 = leaks found, 1 (and any
other) = error. AWS detection changed: `aws-access-token` is now a COMPOSITE rule (an access key ID paired with
a validatable secret access key) and does not fire offline on a lone fake key, so the secrets fixture moved from
a fake AWS key to a fake GitHub token (`github-pat`), whose rule id is identical across both engines (no id-map
needed; see the M4b report).
Consequences: a maintained, MIT secrets engine; one new pinned tool version + two new checksums. Any bump is new
checksums + a new decision.

## D-035 .NET SCA uses OSV-Scanner's native .csproj/CPM extraction, not a custom input file (2026-10-16, accepted; supersedes the .NET fallback mechanism of D-031)
Context: D-031 (and the SPEC) assumed OSV-Scanner could not read `.csproj` and that we would parse direct
`<PackageReference>` versions ourselves and feed OSV a custom `osv-scanner.json` (`--lockfile osv-scanner:…`,
plan change #4). Probing the pinned **OSV-Scanner v2.7.0** showed it source-scans `.csproj` AND NuGet Central
Package Management (`Directory.Packages.props`) natively: a bare `App.csproj` with a vulnerable
`PackageReference` yields `source.path = <the .csproj>` and the expected GHSA/CVE.
Decision: rely on OSV's native `.csproj`/CPM extraction (direct `PackageReference` versions, already mapped to
the real `.csproj` path). We do NOT write a custom input file (change #4 dropped — it would duplicate OSV's
native extraction, giving two findings per package, unless we also disabled OSV's plugin). We still raise a
`MISSING_NUGET_LOCKFILE` warning per `.csproj` that has no sibling `packages.lock.json` (the coverage gap is
real: without the lockfile only direct deps are seen, no transitive). Plan change #6 is kept but enforced on our
side: our inventory step reads each `.csproj` with `xmllint --nonet` (never `--noent`) and raises an
`UNRESOLVED_VERSION` warning (path + package) for versions that are MSBuild properties (`$(…)`), ranges, `*`
wildcards or `VersionOverride`; the parser also drops OSV packages whose version is such a non-concrete string
(OSV passes them through literally, e.g. `Serilog@$(SerilogVersion)`, matching nothing). Transitive NuGet
resolution without a lockfile remains an M4b item.
Consequences: less code and no double-counting; .NET direct-dep SCA works out of the box. `UNRESOLVED_VERSION`
visibility depends on our inventory parse, not OSV.
NuGet .csproj/lockfile dedup (2026-10-16, from the manual real-GitLab check): when a `.csproj` and a
`packages.lock.json` sit in the same directory, OSV v2.7.0 reports the same package from BOTH sources, so the
parser emitted two SCA findings (two sub-issues) for one vulnerability. Rule (in `ScannerOutputParser.parseOsv`):
a `.csproj`-sourced finding is kept ONLY when that `.csproj` (repo-relative path) has a `MISSING_NUGET_LOCKFILE`
warning in the same scan — i.e. the inventory step found no sibling `packages.lock.json` on disk. Otherwise a
lockfile exists and is authoritative (resolved versions, includes transitives), so the `.csproj` copy is
dropped. The rule keys on the filesystem inventory (the warning), NOT on OSV's results: OSV may omit a clean
lockfile from its JSON, and a `.csproj` beside it must still be dropped or its declared-but-not-resolved version
would be a false positive. Proven by `ScannerOutputParserTest` —
`nugetCsprojFindingIsKeptWhenItHasAMissingLockfileWarning`, `nugetCsprojFindingIsDroppedWhenNoMissingLockfileWarning`,
`nugetCsprojFindingIsDroppedWhenAPackagesLockfileSitsBesideIt`.
Vuln-id choice is FROZEN from M4 on (2026-10-16): the preferred id is the numerically lowest CVE by (year,
sequence) across the alias group, else the lowest-sorted GHSA, else the first id (`ScannerOutputParser.pickVulnId`).
Because this id feeds the SCA fingerprint (`package | vulnId | manifestPath`), changing the rule would
re-fingerprint every stored SCA sub-issue (mass solve + reopen), so it must not change once SCA findings are
stored. The parser also drops any OSV result whose source path is not safely repo-relative (still absolute or
containing a `..` segment) and raises an `UNRESOLVED_PATH` warning, so such a path can never enter a fingerprint.
Known gap (M4, for the report): npm `devDependencies` and Gradle test configurations are NOT excluded from SCA
— OSV-Scanner v2.7.0's JSON exposes no dependency-group ("dev") marker to filter on (Q2). Deferred to M7's
`DEV_DEPENDENCY` auto-triage, which will ignore such findings with a visible reason code.

## D-034 OSV-Scanner pinned to v2.7.0 (checksum + flags) (2026-10-16, accepted)
Context: M4's single SCA engine is OSV-Scanner (D-006), added to the scanner image; our rule is to pin every
scanner by version + checksum (as Gitleaks is, D-023).
Decision: pin OSV-Scanner **v2.7.0** (https://github.com/google/osv-scanner/releases/tag/v2.7.0). The release
ships raw per-arch binaries verified against `osv-scanner_SHA256SUMS` before use:
`linux_amd64 = 160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b`,
`linux_arm64 = 4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7`. Run
`osv-scanner scan source -r --format json` over the checkout; default deps.dev online data source (no
`--offline`, no `--data-source=native`, per D-006). The OSV-Scanner version is recorded in `scan.tool_versions`.
Exit codes: `0`/`1` = success (0 = no vulns, 1 = vulns found); `128` = no packages (scan_task `ok` **plus** a
`NO_MANIFESTS` warning, and the SCA scanner is NOT solvable for that scan — closes nothing, domain rule 3 /
D-030); all other codes = failure (closes no SCA issue, scan partial). The scanner image tag stays
`secscan-scanner:8.21.2` — an identifier matching `ScannerProperties.image`, tracked for the M10 weekly rebuild.
Consequences: a reproducible SCA engine; a new runtime egress need to deps.dev/osv.dev (D-033). Any bump is new
checksums + a new decision.
Maven transitive resolution (verified v2.7.0): `osv-scanner scan source -r` resolves Maven transitive
dependencies **by default** via the **deps.dev** data source — no `--allow-unsafe-plugins`/native flag, and it
does **not** follow `<repositories>`/`<pluginRepositories>` or other URLs declared in the scanned pom (verified:
a bogus `<repositories>` URL was ignored while an undeclared transitive dep was still resolved). Parent/
effective-POM lookups, when needed, go to **Maven Central** (a fixed host), not to artifact-declared URLs. We
deliberately do **not** pass `--data-source=native` (which would fetch from Maven Central/registries via the
`transitivedependency/pomxml` plugin). Egress implication for D-033: allow `api.deps.dev`, `api.osv.dev`, and
`repo.maven.apache.org`.

## D-033 Scanner egress stays on the default bridge network for M4 (2026-10-16, accepted; revisit before any deployment or M10, whichever comes first)
Context: SPEC "Securing the scanner" wants scanner egress limited to an allowlist (GitLab, api.osv.dev,
api.deps.dev, …). The scanner has used the default Docker bridge (full egress) since M2; M4 adds the first real
egress need (OSV → osv.dev / deps.dev).
Decision: keep the default bridge network for M4. An enforced egress allowlist (an egress-proxy sidecar on an
internal network with `HTTPS_PROXY` + a git proxy, allowlisting only GitLab, `api.osv.dev`, `api.deps.dev` and
`repo.maven.apache.org` — the last for OSV's Maven effective-POM/parent lookups, D-034) is deferred. This is not a
hardening regression — the bridge predates M4 — and every other container control stays in force (non-root,
read-only rootfs, `--cap-drop=ALL`, `no-new-privileges`, CPU/memory/PID limits, hard timeout, no Docker socket).
Revisit when: before any deployment or M10, whichever comes first.
Consequences: a compromised scanner could reach arbitrary hosts until the allowlist lands; accepted for local
single-tenant mode.
M5 egress note (2026-10-19): the api process gained two new outbound hosts for the daily intel sync —
`www.cisa.gov` (KEV, D-046) and `epss.cyentia.com` (EPSS, D-047) — over HTTPS with TLS verification always on.
These are api-side (not scanner) egress; fold them into the pre-deploy allowlist item alongside the scanner's
GitLab/osv.dev/deps.dev/Maven-Central hosts.

## D-032 Public package registries assumed; private-registry detection is M4b (2026-10-16, accepted; resolves OD-3)
Context: OD-3 — private registries (Nexus, Artifactory, GitLab package registry) — had to be settled before M4.
Decision: M4 assumes public registries (osv.dev/deps.dev online, D-006) and stores no registry credentials.
Detecting and handling private registries is deferred to M4b.
Consequences: packages served only from a private registry may be unresolved in M4; no registry credentials are
held anywhere.
Implemented (M4b slice 3, 2026-10-18): the resolver DETECTS private feeds and WARNS (`PRIVATE_REGISTRY`,
free-text code, no migration) — Gradle `repositories {}`/`maven { url }` in build.gradle(.kts) and
settings.gradle(.kts), and NuGet `nuget.config <packageSources>`. A non-public host is reported HOST ONLY
(everything up to the last `@` and the port are dropped and only a plain DNS hostname is emitted — not
`urlsplit().hostname`, which reports part of a credential containing an unencoded `#`/`?`/`/` as the host;
M4b review fix); `<packageSourceCredentials>` and any
credential/token/property value are never read or emitted, and nothing credential-bearing reaches `/out`. The
resolver NEVER fetches a private host (it stays Maven-Central-only, D-042), so a scanned repo cannot steer the
fetcher at an arbitrary host (SSRF-shaped risk). Public hosts (Maven Central, google, gradlePluginPortal,
api.nuget.org) never warn. This completes OD-3.

## D-031 Gradle/.NET without lockfiles in M4: warn + partial; static Gradle resolver is M4b (2026-10-16, accepted; partly resolves OD-1/OD-2)
Context: OD-1 (Gradle without a lockfile) and OD-2 (.NET without `packages.lock.json`) had to be settled before
M4, and we never run a build (D-004, D-030).
Decision: M4 raises a per-build-file "missing lockfile" scan warning and produces partial results. .NET falls
back to the **direct** `<PackageReference>` versions in `.csproj` (and `Directory.Packages.props`), written
outside the clone in OSV-Scanner's documented custom format and fed to it via `--lockfile osv-scanner:<path>`,
only for projects that have no `packages.lock.json` in the same folder; versions that are MSBuild properties,
ranges, wildcards or `VersionOverride` are skipped with an `UNRESOLVED_VERSION` warning (never guessed). Gradle
without a lockfile is warning-only (a `build.gradle`-only scan finds little because Spring Boot BOM-managed
versions are absent). A static Gradle resolver (and NuGet transitive resolution if needed) is a separate
milestone **M4b**, designed later. Never run gradle/dotnet/npm/mvn (D-004). Python is out of M4 (not an MVP
ecosystem).
Consequences: OD-1/OD-2 are partly resolved now (warn + .NET direct fallback) and finished in M4b; Gradle SCA
coverage is limited until then.

## D-030 Scanned repositories are never modified; coverage gaps are shown, not hidden (2026-10-16, accepted)
Context: SCA needs dependency versions, but running a build (gradle/dotnet/npm/mvn) on an untrusted repo is code
execution (D-004) and would also mean writing files (lockfiles, CI jobs) into repositories we hold only
read-only access to.
Decision: SecScan never modifies a scanned repository and never requires a change to it (no committed lockfiles,
CI jobs or config files as a precondition to scan). Dependency resolution is therefore static only (with D-004).
Where a repository's layout limits what can be resolved, the gap is surfaced as a scan-health warning (per build
file), never silently dropped.
Consequences: SCA completeness depends on what the repo already commits; partial coverage is explicit and
visible. Teams can improve coverage by committing lockfiles but are never forced to.

## D-029 The secret fingerprint key must never change once secrets are stored (2026-10-14, accepted)
Context: a secret sub-issue's fingerprint is derived from `secret_hash = HMAC-SHA256(SECSCAN_FINGERPRINT_KEY,
container SHA-256)` (D-027). Changing the key re-fingerprints every secret, so on the next scan the old
sub-issues would all look "missing" (solved) and the same secrets would reappear as new findings — a mass
false-solve plus duplicates.
Decision: each secret sub-issue stores a short `key_id` = first 8 hex chars of
`HMAC-SHA256(key, "secscan-key-id")` (non-reversible, reveals nothing about the key). During reconcile the
secret sub-issues are **partitioned by `key_id`** (M3 review refinement): those under the current key id
reconcile normally (solve/reopen), while those under any other key id are **frozen** — never solved, never
reopened, no `triage_event` — so the whole secret scanner is not stopped, only the superseded rows. A single
error names the count of frozen rows and the stale key id(s) (all non-reversible), never a secret. Operators
must clean up the old-key rows manually; bulk ignore arrives in M7. The key must be backed up and rotated with
care; rotation re-fingerprints all secrets and is a later, explicit feature. `key_id` is never returned by the
API.
Consequences: a changed key degrades gracefully — old secrets are frozen (kept, not mass-solved) while secrets
under the new key keep reconciling normally — instead of corrupting the feed or freezing all secret
reconciliation; operators get a loud, counted signal and a manual cleanup step. One extra stored column per
sub-issue.

## D-028 Repository lifecycle events in a dedicated table, not triage_event (2026-10-14, accepted)
Context: D-020's connection reassignment/deactivation currently only logs; M3 must persist it. The options were
a new table or `triage_event` with a nullable `sub_issue_id`.
Decision: a dedicated `repository_event` table (`workspace_id`, `repository_id`, `event_type` in
REASSIGNED/DETACHED/DEACTIVATED/CONFIRMED/REATTACHED, `from_connection_id`, `to_connection_id`, `reason_code`,
`detail`, `at`). `triage_event` stays specifically the issue-status audit trail (domain rule 4: from/to status,
reason code, actor, scan) and is not diluted with events that have no issue, status or sub-issue. The
`from`/`to` connection ids are plain uuids, not FKs, because the "from" connection is typically deleted at the
moment the event is written. A `RepositoryEventRecorder` writes these at the D-020 sites (connection removal,
and the connection guard's confirm/deactivate), within the same transaction as the state change.
Consequences: both audit trails stay purpose-specific and queryable; one new table.
Note (M3 review, accepted for now, revisit in M10): `triage_event.sub_issue_id`/`group_id` are `ON DELETE
CASCADE`, so hard-deleting a sub-issue/group would also delete its audit trail; and the issue-model FKs are
single-column (`id` only), so the database does not enforce that a sub-issue/triage_event shares its subject's
`workspace_id` (the server always writes a consistent `workspace_id`). Both are safe in M3 — nothing deletes
issue rows (M7 ignore only flips status) and the deployment is single-workspace — and are left as-is to avoid a
schema change now. Revisit before the first hard delete (repository deletion / tenant teardown) or multi-tenant
auth: switch the audit FKs to `ON DELETE RESTRICT`/`SET NULL` and add composite `(workspace_id, id)` FKs.

## D-027 Keyed secret fingerprint (HMAC-SHA256) instead of a bare SHA-256 (2026-10-14, accepted)
Context: the scanner container computes a SHA-256 of each secret value (the raw value never leaves the
container). Storing that bare digest would let anyone with a copy of the database confirm a guessed secret by
hashing candidates offline — the digest is unsalted and the input space for many secret types is small.
Decision: the worker stores `secret_hash = HMAC-SHA256(SECSCAN_FINGERPRINT_KEY, the container's SHA-256)`, hex.
The key is a 32-byte base64 value in `SECSCAN_FINGERPRINT_KEY`; an invalid key fails startup (like
`SECSCAN_ENCRYPTION_KEY`), and a missing key makes storing a secret finding fail with a clear error rather than
ever persisting the bare digest. The masked preview is computed in the container (first 4 chars only when the
secret is ≥ 12 chars, else `****`).
Consequences: a stolen database alone cannot confirm guessed secrets; the fingerprint stays stable and
comparable for M3 reconcile. The key must be backed up with the database — changing it re-fingerprints every
secret, so secrets seen before the change reopen as new on the next scan. Two keys (encryption + fingerprint)
to manage.

## D-026 Docker socket mounted into the worker only (2026-10-13, accepted)
Context: the worker launches scanner containers against the host Docker daemon, which requires the daemon
socket; mounting it grants host-root-equivalent power to whatever holds it.
Decision: the Docker socket is mounted into the **worker only**, never into a scanner container (CLAUDE.md).
The worker never passes the socket, a token env var, or the socket path to a scanner. This is accepted for
local, single-tenant mode; before any deployment we revisit with a rootless/again-sandboxed launcher or a
remote build service (tracked with OD-5, worker hosting).
Consequences: simple local operation; a compromise of the worker process is host-root-equivalent until we
harden the launch path for deployment.

## D-025 Fixture clone path and deferred secret-scan coverage (2026-10-13, accepted)
Context: M2's done-when must be an automated test, but there is no production GitLab token/VPN in this
environment (as in M1/M1b).
Decision: the automated `ScanPipelineIT` clones a synthetic local repository over `file://` (mounted read-only
at `/src`) with `network=none`, exercising clone → scan → sanitize → parse → persist → container-removed
offline. The token-via-one-time-file + `https` clone path is verified in the manual real-GitLab run, folded in
with the M1b check deferred by D-021. Full git-history secret scanning (spec: full clone for the first secrets
scan of a repo) is deferred — M2 scans the checked-out tree with `gitleaks dir` — and tracked in Known gaps.
Consequences: a self-contained, offline done-when; the token/https and full-history paths are covered manually
in M2 and automated later.

## D-024 Raw scan_finding store ahead of the issue model (2026-10-13, accepted)
Context: M2 must "store a finding", but the dedup/issue model (`issue_group`/`sub_issue`, fingerprints,
reconcile) is M3.
Decision: M2 persists one `scan_finding` row per normalized finding per scan (pipeline step 7's `RawFinding`),
with no dedup or status. M3's reconcile consumes these into `issue_group`/`sub_issue` with stable fingerprints.
Secret findings store only a SHA-256 hash and a 4-char preview (never the value).
Consequences: M2 is self-contained and testable before the issue model exists; `scan_finding` remains the
normalizer's output that reconcile reads.

## D-022 docker-java for the scanner launcher (2026-10-13, accepted)
Context: the worker must create/start/wait/log/remove hardened containers on the host daemon from Java.
Decision: use `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5`, pinned to
**3.4.2** to match Spring Boot 3.3.x's managed `httpclient5` (5.3.x); docker-java 3.5+ requires httpclient5
5.4+. TLS to the daemon is never disabled.
Consequences: a new runtime dependency (worker only); the version is coupled to the Spring Boot BOM's
httpclient5 and is revisited when Boot is upgraded.

## D-023 Scanner image: Alpine (digest-pinned) + Gitleaks (checksum-pinned); in-container secret sanitize (2026-10-13, accepted)
Context: M2 needs a disposable scanner container with git + a secrets scanner, and secret findings must never
store or leak the raw value (CLAUDE.md security rules).
Decision: `scanner-image/` builds from `alpine:3.21` pinned by digest, adds git + jq, and installs Gitleaks
8.21.2 verified against its published SHA-256 (per-arch). `run-scan.sh` clones one commit into tmpfs `/work`,
runs `gitleaks dir --no-banner --report-format json` (exit 0 or 1 = success; any other code = scanner failure),
then computes the SHA-256 and 4-char preview of each secret **inside the container** and writes only those,
with the repo-relative file path and start line, to `/out/secrets.json` — the raw value never leaves the
container. A manifest `/out/result.json` carries the cloned commit, tool versions and per-scanner status. The
token, when present, arrives only as a read-only file and is sent as a basic-auth header via a tmpfs git config,
never in the URL/env/argv/logs (D-014). apk package versions are not individually pinned (follow the pinned
Alpine branch) — see Known gaps.
Consequences: reproducible scanner builds; the strongest secret-handling posture (raw secret dies with the
container); OSV-Scanner/Opengrep/Checkov are added to this same image in M4/M6.

## D-020 Multiple connections, project-mode targets, and reassignment on removal (2026-10-12, accepted)
Context: M1b lets a workspace connect a whole group and/or several single projects; removing a connection must
not lose repositories (scan history hangs off them from M2) and must keep a clone path resolvable.
Decision: `gitlab_connection` gains `target_type` (`group`|`project`), `gitlab_project_id`, `project_full_path`
and `last_verified_at` (V5); the one-per-workspace unique is dropped and replaced by partial unique indexes
per group and per project. A connection is tested/created via `POST /connections` and its token rotated via
`PUT /connections/{id}` (same target only; a different target means a new connection); the old singular
`/connection` endpoints are removed. Project mode resolves `GET /projects/:url-encoded-path`, reusing the same
allowed-hosts check, no-redirect client, scope/expiry checks and pasted-URL support as group mode. Removing a
connection never deletes repository rows: each is reassigned — to a project-mode connection for the same
`gitlab_project_id`, else the group connection whose `group_full_path` is the longest namespace prefix of the
repo path, newest-verified breaking a tie (offline, no GitLab call at delete time) — or, if none, detached
(`connection_id` nullable) and deactivated with `inactive_reason = NO_ACCESSIBLE_CONNECTION`. A reassignment is
provisional (`reassigned_at`): the next use of the new connection confirms it, or, on a 401/403/404,
deactivates the repo. First-wins dedup applies only while a repository is owned and active; a detached or
inactive repository is reattached to the connection that next selects it (clearing the inactive/provisional
state). The tie-break prefers the most-recently-verified connection and treats a null `last_verified_at` as
oldest (existing rows are backfilled to `created_at` in V5). A concurrent duplicate `create` is caught via the
partial unique index and returned as `CONNECTION_EXISTS`, not a 500. Reassignments/deactivations are logged (a
persistent `repository_event` row arrives with M3 — D-028, not `triage_event`).
Consequences: additive, reversible connection management; a repo can briefly point at a connection that cannot
reach it, resolved on first use; delete works even with the VPN down or a token expired.

## D-019 Repository dedup and connection ownership (2026-10-12, accepted)
Context: Multiple connections (M1b) could surface the same GitLab project; a repository must exist once per
workspace, and we must know which connection to use to clone it.
Decision: A repository is unique per workspace on `gitlab_project_id` (already enforced by
`uq_repository_workspace_project`). `repository.connection_id` records the connection used to clone it. When a
connection is removed, its repositories are reassigned to another connection that exposes the same project, or
deactivated if none does. Changing an existing connection's group is rejected for now (M1); the reassign/
deactivate behavior is implemented in M1b.
Consequences: no duplicate repositories; clone credentials always resolve to a real connection.

## D-018 Outbound GitLab host allowlist (2026-10-12, accepted)
Context: The server makes outbound calls to a user-supplied GitLab base URL — an SSRF risk if unconstrained.
Decision: `SECSCAN_GITLAB_ALLOWED_HOSTS` (comma-separated, empty = deny all) lists the hosts the app may call.
Every outbound GitLab call requires `https` and an allowlisted host before the request is made; loopback hosts
may use `http` (for a local/mock GitLab) but must still be allowlisted. A disallowed host or scheme fails with a
clear `HOST_NOT_ALLOWED` error. A pasted group URL for a non-allowlisted host is rejected the same way.
Consequences: the base URL cannot be pointed at arbitrary internal services; operators opt hosts in explicitly.

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

- M6 deferred register (recorded 2026-10-20, for M7+):
  - **SAST rule-pack SaaS licensing** (D-053): opengrep/opengrep-rules carries a Commons Clause (no resale/SaaS).
    Internal use is fine; recheck before any multi-tenant/SaaS deployment (the open SPEC item + D-005).
  - **Opengrep bump re-fingerprint risk** (NIT 9): the SAST fingerprint hashes the normalized `extra.lines`
    snippet; a future Opengrep version that changes how `extra.lines` is emitted could re-fingerprint stored
    SAST sub-issues (mass solve+reopen). Verify the snippet output is unchanged (or time a deliberate
    re-fingerprint) before bumping Opengrep.
  - **IaC severity override table** (D-054, NIT 7): all IaC findings score base 50 in M6 (OSS Checkov has no
    severity). A maintained per-`check_id` severity override is deferred.
  - **Checkov Helm + docker-compose coverage** (D-054, D-030): Helm charts are not rendered (needs the helm
    binary, D-004 — an `IAC_HELM_NOT_RENDERED` warning is raised when a `Chart.yaml` is present); Checkov 3.3.29
    has no `docker_compose` framework (an `IAC_DOCKER_COMPOSE_UNSUPPORTED` warning is raised when a compose file
    is present). Both gaps are shown as scan-health warnings, not hidden. Revisit if/when a safe static path exists.
  - **By design (NIT 11):** two identical SAST snippets in the same file share one fingerprint
    (`rule_id | file_path | normalized_snippet_hash`) and collapse to a single sub-issue (first-wins dedup);
    EOL findings require the `eol_cycle` cache to be populated at scan time (an unsynced/uncovered product is
    not reported, and an existing open EOL finding for an uncovered `(product,cycle)` is frozen — never
    false-solved — per fingerprint, D-055 / review BUG 1).
  - **SAST test-path is an adjustment, not an exclusion:** the −15 is itemized in `score_breakdown`; dev/test
    scope EXCLUSION (`DEV_DEPENDENCY`/`TEST_SCOPE` auto-triage) remains M7.
- OD-1 Gradle repos without lockfiles — PARTLY RESOLVED by D-031 (warn-only in M4); finished in M4b
- OD-2 .NET repos without `packages.lock.json` — PARTLY RESOLVED by D-031 (`.csproj` direct fallback in M4);
  finished in M4b
- OD-3 Private package registries — RESOLVED by D-032 (public assumed in M4; private-registry detection → M4b)
- OD-4 GitLab instance, version and groups — RESOLVED by D-013 (version read at runtime)
- OD-5 Hosting for the worker — needed before M9
- OD-6 Product name
- OD-9 Admin intel endpoints are unauthenticated-by-role (deferred to M10). `AdminIntelController`
  (`POST /api/v1/admin/intel/{kev,epss,eol}/sync`, `/rescore`) is callable by any authenticated user: there is no
  role/authority gating yet because roles are an M10 concern (D-010). The endpoints are read-mostly maintenance
  (re-fetch a public feed, recompute derived scores; no secret exposure, no status/fingerprint change — D-049).
  Add role gating when M10 introduces authorities. (M5 review N5.)
- M8 deferred register (recorded 2026-10-22, for M9+):
  - **OD-16 OUT (not built in M8):** edit ignore rule in place (no PUT — you delete and "create new" prefilled);
    group-level timeline; trends over time; export; snooze; teams; reachability; a per-issue detail page; live
    scan logs. Revisit per product priority.
  - **Repository settings audit row → M10** (D-070): a settings change (sensitivity/include-dev-deps/active)
    writes no audit row in M8; M10's audit log adds it. M8 logs field names at INFO (no values).
  - **M3 gap "issues of an inactive repository stay open"** — resolved by OD-15/D-070 DISPLAY rules: an inactive
    repo keeps its statuses (reconcile never runs) but its groups/sub-issues are excluded from Feed/Dashboard
    totals by default, shown behind an "Inactive" filter + a visible "N inactive repos, M open findings" line.
  - **OD-19 → M8.5 (D-073; investigation in `docs/reports/M8-S10b-duplicate-ignored-sast.md`):**
    duplicate *ignored* sub-issues accumulate when a finding's fingerprint changes while it is ignored. Root
    cause: the SAST fingerprint includes the normalized-snippet hash (scanner-computed), which shifts when the
    Opengrep binary/rule bundle or the normalization algorithm changes (as it did on 2026-10-20, M6 `/review-pr`);
    reconcile's solve step is gated on `status='open'` (`Reconciler.java:95`), so the vanished old fingerprint is
    not closed ("ignored stays ignored") while the new one is re-ignored → two ignored rows. **Open findings do
    NOT accumulate** (missing open → solved). Fix (scheduled M8.5, touches reconcile/resolver = domain rule 3):
    (1) solve an *ignored* finding that is absent from a scanner-complete scan when its hold is
    re-derivable (auto cause or rule/path/CVE-scope), routed through the D-063 resolver, writing a `triage_event`;
    (2) a one-off maintenance pass (no migration) to retire the rows already accumulated; (3) optionally reduce
    SAST fingerprint churn with a structural match key. Not a security issue (the live finding is tracked
    correctly; surplus rows are stale ignored clutter).
  - **Feed "New · 7d" over-counts across a branch switch → M8.5 (D-076):** "New · 7d" counts sub-issues by
    `created_at`, so findings first seen on the PREVIOUS branch still count as "new" after a switch (Sajal's
    walkthrough: `notification` showed 108 SEEN where 88 were immediately closed by BRANCH_CHANGED). Options:
    count only findings on the currently-tracked branch, or annotate "(N closed by branch change)". Cosmetic
    (the open count is correct — 20 open on `dev`); scheduled with the other branch-switch display polish in M8.5.
  - **Switch to a branch with no manifests → SCA never re-baselines → M8.5 (D-1, D-076):** a branch with no
    dependency manifests makes a successful SCA scan non-authoritative (`closes_findings=false`, D-034), so the
    old branch's SCA findings are never closed by the switch (they are not re-baselined and not solved). Resolve
    with OD-19 in M8.5 (route the ignored/undetected-but-held close through the D-063 resolver). Open SCA findings
    on such a switch simply persist until a branch with manifests is scanned.
  - **M9 commit poller must snapshot `effectiveBranch()` (D-2, D-076):** when M9 adds commit polling / scheduled
    scans, the enqueue path MUST resolve `repository.effectiveBranch()` (the configured `scan_branch`, else the
    current GitLab default refreshed per A6) and snapshot it onto `scan.branch`, exactly like `ScanService
    .startManual` — so scheduled scans honour the chosen branch and re-baseline correctly.
  - **Absent-but-ignored on a branch switch → M8.5 (OD-19 family, D-076):** when `scanBranch` switches, an
    *ignored* finding that is absent on the new branch stays ignored (rule 3), not solved — the same
    absent-but-ignored shape as OD-19. Resolve it together with OD-19 in M8.5 (route the ignored-and-absent close
    through the D-063 resolver with a `triage_event`). Open findings are unaffected (they rebaseline correctly).
  - **"Fixed"/MTTR/fix-rate metrics must exclude `REASON_BRANCH_CHANGED` → M9 (D-076):** Feed "Solved · 7d"
    already excludes branch-change closes; any future fix-rate / mean-time-to-remediate / "fixed in last N days"
    metric (M9 scheduling dashboards or later) MUST likewise exclude `triage_event.reason_code =
    'BRANCH_CHANGED'`, or a branch switch would inflate the fix numbers.
  - **OD-20 Finding taxonomy → M8.5 (D-074):** level-2 categories (from CWE / secret type / IaC platform /
    ecosystem) + optional OWASP Top 10 2025 tags. New SPEC section + migration **V20** (V19 stays reserved for the
    IaC override). Level-1 plain names already shipped in S10f (`ui/src/lib/labels.ts`).
  - **OD-21 3-real-repo walkthrough → M8.5 (D-075):** M8 "Done when" was met on core-keycloak (real) +
    secscan-test-secrets (fixture); onboarding + verifying **ib-api-v2** and a **3rd real repo** is the first M8.5
    task.
  - **Scanner image tag reused with new content → per-digest tag (M10):** M8.1 rebuilt `secscan-scanner:8.21.2`
    with changed `run-scan.sh`/`secscan-lib.sh` under the SAME tag (the content hash tracks drift, but the tag is
    not immutable). The M10 weekly-rebuild / hardening pass should pin the scanner image by digest or bump the
    tag per content change. Not changed now (out of M8.1 scope).
  - **IaC Checkov `check_name` ingest → later:** ingest Checkov's human-readable `check_name` so IaC titles are
    human, not raw rule ids.
  - **Languages refresh on each scan → later:** re-derive a repository's detected languages on every scan (today
    they are not refreshed per scan).
  - **Side panel less repetitive → later:** group affected locations more compactly in the side panel.
  - **Theme (light/dark) preference persistence → M10 (user settings):** the S10e light theme is not yet persisted
    per user; it lands with M10 user settings.
  - **RESOLVED by SF-1 — the "Medium 65" stale panel header was a UI CACHE bug, not backend staleness.** The
    side-panel header reads the group via `GET /issue-groups/{id}` (`["issue-group", id]`), but that query family
    was NOT in any mutation's invalidation list, so after an API-time ignore the header kept its cached (open-era)
    score while the sub-issue rows updated. The backend recompute is correct: `GroupRollupService` nulls
    `severity_score` for an all-ignored group through every API-time path (manual triage, EPSS sweep) and the UI's
    `SeverityPill` renders null as a neutral "—". Fix: SF-1 adds `issue-group` + `sub-issue-events` to all four
    mutation kinds in `queryClient.ts`; SF-2 additionally makes the score-only rollup a column-scoped guarded
    UPDATE so a concurrent re-score can never resurrect a score onto a just-ignored group. Proven by
    `AllIgnoredGroupRollupIT`, `GroupScoreRollupRaceIT`, the `queryClient.test.ts` invalidation table and an
    e2e (panel header flips to "Ignored" without reload). The earlier "report evidence only / investigate"
    framing in `docs/reports/M8.md` was superseded; see M8.md's corrected "Follow-up fixes" section.
  - **D-1 nginx master process runs as root → M10 (hardening):** the `ui` runtime image (`nginx:1.27-alpine`)
    starts its master as root (workers drop to `nginx`). Switch to `nginxinc/nginx-unprivileged` (or an explicit
    non-root user + high port) as part of the M10 container-hardening pass. Not changed in M8 (out of scope; the
    image is already digest-pinned, read-only-friendly and only serves static files + proxies `/api`).
  - **D-2 sensitivity re-score skips ignored findings; unignore keeps the old score until the next scan/re-score
    → M8.5:** `ReScoreService.rescoreWorkspace`/`rescoreRepository` re-score only OPEN sub-issues (B1 guard), so a
    finding that is unignored by removing a rule keeps whatever score it had when it was ignored until the next
    scan reconcile or a re-score trigger (e.g. a sensitivity change or intel refresh) recomputes it. Acceptable
    for M8 (score is display/ordering, not status); M8.5 can re-score a just-unignored finding inline.

## D-021 M1b real-GitLab check deferred to M2 (2026-10-13, accepted; DONE 2026-10-14)
Context: M1b logic is covered by WireMock + Testcontainers tests; M1 already proved real GitLab connectivity.
Decision: Merge M1b without its manual real-GitLab check; run it as part of the M2 manual run.
Consequences: M2's report must include the M1b project-connection check (group + project, dedup, reassignment).
DONE (2026-10-14): Manual run completed against gitlab.celloscope.net (19.4.1-ee) over VPN — M2 real scan and
the M1b group/project/dedup/reassignment check both passed. Recorded in `docs/reports/M2.md`
("Manual verification (2026-10-14)"). Also confirmed the D-025 manual path (token one-time-file + `https`
clone): scan completed, exit 0, no token in logs, no leftover container.
