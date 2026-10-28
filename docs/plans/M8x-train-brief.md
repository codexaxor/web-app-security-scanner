/start-milestone M8.5

This is a "milestone train": M8.5 → M8.6 → M8.7 are planned together and approved once, then built back to
back, each in its own fresh session, each on its own branch stacked on the previous one. Phase A now
covers ALL THREE milestones. Write no production code in this session until the plan is approved.

=== STEP 0: make the brief durable (do this first) ===
1. From develop (expected c9fe9fa, clean tree) create feature/m8.5-correctness-taxonomy.
2. Save this entire message VERBATIM to docs/plans/M8x-train-brief.md and commit it
   ("docs(plans): M8.x train brief"). This file is the source of truth for the whole train. Whenever your
   context is compacted, a session starts, or you are unsure, RE-READ: docs/plans/M8x-train-brief.md,
   docs/plans/M8x-train-state.md, the current milestone work log, CLAUDE.md, and the SPEC/DECISIONS sections
   named below — before taking any action.
3. Create docs/plans/M8x-train-state.md with the template in section "STATE FILE" and commit it.

=== SOURCES (binding) ===
CLAUDE.md, docs/WORKFLOW.md, docs/SPEC.md sections "M8.5 Correctness + taxonomy", "M8.6 UI uplift",
"M8.7 Multiple sources", and DECISIONS D-063, D-067, D-073, D-074, D-076, D-077, D-079, D-080, D-081,
D-082, D-083, D-084, D-085. Where this brief is MORE detailed than SPEC (the M8.6/M8.7 "Done when"
below), this brief is the detailed Done-when the SPEC sections point to ("detailed Done when: architect").
If the brief conflicts with CLAUDE.md or an accepted decision: HARD STOP and ask.

=== TRAIN RULES (recorded as D-086 in M8.5 slice S0) ===
D-086 M8.x train execution model (2026-10-27, accepted by Sajal):
- One combined Phase A plan for M8.5, M8.6, M8.7; the architect approves it once.
- CLAUDE.md "don't start the next milestone in the same session" is KEPT: each milestone runs in its own
  fresh session, started by the human with PROMPT 3. Continuity lives on disk (brief + state file + work logs).
- Branches are stacked: feature/m8.6-ui-uplift branches from the HEAD of feature/m8.5-correctness-taxonomy;
  feature/m8.7-multi-source from the HEAD of feature/m8.6-ui-uplift. (Deviation from WORKFLOW "branch from
  develop", accepted for this train.)
- Review happens after all three are built: /review-pr per milestone in fresh sessions. A fix to an earlier
  branch is committed on that branch and then MERGED FORWARD (git merge, never rebase/force-push) into the
  later branches. Merges into develop/main happen in order m8.5 → m8.6 → m8.7, by the human.
- Real-data walkthroughs are deferred to the end of the train (Sajal skips manual testing during the build).
  Each report lists them under "Pending manual walkthrough" with exact steps. ib-api-v2 is onboarded only
  then (deviation from D-083: no ib-api-v2 baseline on the m8.1 build).

=== BASELINE (already captured, m8.1 build, 2026-10-27, after Docker data-root moved to /home/docker-data) ===
sub_issue counts by repo/status: core-keycloak open 59; secscan-test-secrets open 35 / ignored 8 / solved 5;
notification open 20 / solved 88. No separate baseline slice. Copy into the M8.5 work log.

=== PHASE A — what the plan must contain (then STOP) ===
For EACH milestone (M8.5, M8.6, M8.7):
a. Slices in order, each with: goal, files touched, tests added (class + method names), which Done-when
   bullets it proves, gates it runs (G1–G4), est. size (S/M/L).
b. Every migration's full SQL (V20, V21 in M8.5; V22 in M8.7) + backfill strategy + proof it applies to an
   empty DB and to a DB with existing data.
c. Every API change: method, path, request/response fields (records), workspace scoping, pagination.
d. A table "Done-when bullet → named test or pending-manual-walkthrough step" covering EVERY bullet.
e. Explicit answers to the CHECKLIST below.
f. Risks + how each is contained; anything you think is under-specified, with your proposed default.
Plus for the train: disk preflight (free space on /home/docker-data and /), the stacked-branch plan, and
the order of slices across milestones.
STOP after presenting the plan. Do not write production code until the approval message arrives.

=== CHECKLIST (answer in the plan; enforce in every slice) ===
1. No network I/O inside @Transactional (GitLab/GitHub/registry calls happen before or after the tx).
2. No whole-entity save of an entity loaded outside the write tx — column-scoped updates.
3. Every new repository/query method is workspace-scoped (…ByWorkspaceIdAnd…); list the new ones.
4. Status changes only via reconcile or the D-063 audited carve-outs; every status change writes a
   triage_event; a failed or non-authoritative scanner closes nothing; ignored stays ignored except the
   D-083 OD-19 path.
5. Fingerprints never include line numbers; no fingerprint input changes in this train (provider/host
   are repository identity, NOT finding fingerprint inputs).
6. V1–V19 frozen; new migrations only V20, V21 (M8.5), V22 (M8.7).
7. Tokens: entered only in the UI, AES-GCM at rest, never logged, never in URLs/env; scanner receives
   credentials only via the one-time file (D-014). Redirects never followed with credentials. TLS always verified.
8. UI: text-only rendering; no dangerouslySetInnerHTML; links only per D-067 + D-084 link builder;
   no new runtime npm dependency without a D-entry (D-066) — prefer inline SVG icons and plain CSS.
9. Never execute scanned code (D-004); never modify scanned repos (D-030).
10. Every list endpoint paginated server-side.

=== BUILD LOOP (Phase B — applies to every slice of every milestone) ===
For each slice:
 1. Re-read the state file; confirm current milestone/slice and last green commit.
 2. Write down the slice goal and the Done-when bullets it serves (in the work log).
 3. Tests first for new behavior (failing for the right reason), then the minimal implementation.
 4. Run targeted tests, then the gates:
    G1 ./gradlew build   (every slice)
    G2 cd ui && npm ci && npm run build && npm run typecheck && npm test && npm run guard   (slices touching ui/)
    G3 cd ui && npm run e2e   (UI slices that change a user flow, and at milestone finish)
    G4 git status clean except intended files; no secrets/tokens/real company code staged; grep the diff for
       new repository methods without workspace scoping, @Transactional methods calling clients, and
       dangerouslySetInnerHTML / href built from data.
    If scanner-image/ changes: rebuild the scanner image (./gradlew :app:buildScannerImage) and run its
    in-image tests.
 5. Self-review the slice diff against the CHECKLIST; fix before committing.
 6. Commit (Conventional Commits, scope core/app/scanner/ui/db/docs). Push the feature branch (backup).
 7. Update the work log (slice, commit, gate results with test counts) and the state file (next action).
Failure policy:
 - A failing gate gets at most 3 fix attempts. Each attempt: diagnose root cause first, write it in the
   work log, then change code. After the 3rd failed attempt → HARD STOP.
 - Never weaken, skip, @Disabled, or delete a test to pass. If a test seems wrong, HARD STOP and explain.
 - Suspected flake: re-run once. Passes → record as flake in the work log with evidence. Fails twice → real.
 - Under-specified detail: if the choice is reversible, local, and touches no domain/security rule,
   migration shape or public API contract → pick the conservative option, record it as "D-draft" in the
   work log and the report, continue. Otherwise → HARD STOP.
Disk:
 - Before G3 and before any scanner-image build: check free space on /home/docker-data and /. Below 8 GB on
   either → HARD STOP. After e2e: docker image prune -f (never volumes; never docker compose down -v on the
   default project).
HARD STOP = commit nothing half-done to the branch tip; leave WIP uncommitted or on a wip/ commit clearly
marked; write the diagnosis + options into the state file and the work log; report to the human; stop.
Context hygiene:
 - Keep the work log terse: per slice ≤ 15 lines.
 - If context gets long, write the state file first, then continue; after compaction, re-read per STEP 0.

=== MILESTONE FINISH (end of each milestone) ===
 1. ./gradlew clean build --rerun-tasks green; G2 green; G3 run twice green.
 2. Write docs/reports/M8.x.md from the milestone report template in docs/ (as /finish-milestone does): Done-when
    table (bullet → test or "pending manual walkthrough" with exact steps), migrations, API changes,
    decisions (incl. D-drafts), deviations (incl. D-086 items), evidence (test counts), files the reviewer
    must read in full, known gaps.
 3. Update README/CLAUDE.md commands if anything changed. Update SPEC only where behaviour differs from it
    (and say so in the report).
 4. Commit, push the branch, set the state file to "milestone done; next: start M8.(x+1) with PROMPT 3"
    (or "train done" after M8.7), and STOP the session. Do not start the next milestone in this session.

=== STATE FILE (docs/plans/M8x-train-state.md template) ===
# M8.x train state
- Train plan approved: no / yes (date)
- Current milestone: M8.5 | M8.6 | M8.7 | done
- Current slice: <id + name>
- Branch / last green commit: <branch> @ <sha>
- Last gate results: G1 <tests> | G2 <vitest/guard> | G3 <e2e passed ×runs>
- HARD STOP: none | <reason + options>
- D-drafts so far: <list>
- Next action: <one line>

=== M8.5 Correctness + taxonomy ===
Scope + Done when: SPEC section "M8.5 Correctness + taxonomy" and D-083, unchanged, except the D-086
deviations above. Slice order: S0 D-086 + work log + baseline → OD-19 reconcile + one-off retire pass
(dry-run count first, idempotent, writes triage_events, no migration) → V20 IaC per-check severity override
(scoring only) → V21 level-2 taxonomy + backfill + API fields + feed filter (mapping as a core resource,
unit-tested; OWASP 2025 category NAMES only) → ingest: Checkov check_name for IaC titles, languages refresh
on every successful scan → Feed "New · 7d" = first seen on the currently tracked branch, re-score on
unignore, D-074 errata entry → A5 ITs (transient 5xx/timeout → proceed; branch_missing cleared after a good
clone) + housekeeping (API Dockerfile `|| true` removed safely, dev compose postgres digest-pinned, e2e
artifacts dir not world-writable) → finish.
The plan must show: where reconcile's solve step is gated on status='open' today (file + method), how
"hold is re-derivable" and "scanner authoritative" are decided, the reason_code per case (BRANCH_CHANGED
for switch-caused closes), the exact SQL for "first seen on the tracked branch", and the level-2 value set
per type.

=== M8.6 UI uplift — detailed Done when ===
Scope: D-081 (2), D-084, D-082 (last-scan age). No change to reconcile, status, scoring or fingerprints.
API changes are additive only (new fields/endpoints), workspace-scoped, paginated.
1. Shell: left sidebar (D-084/OD-25) with Dashboard, Feed, Repositories, Ignored, Settings, Connect;
   active state; theme switch and sign-out kept; collapses to an icon rail/drawer below ~1024 px;
   keyboard navigable; icons as inline SVG (no icon library unless a D-entry is added).
2. Design pass: tokens extended (spacing scale, radii, type scale, elevation, focus ring); severity count
   chips (Critical/High/Medium/Low colours, zero = muted); status chips (New, To Do, Ignored, Solved);
   tables with sticky header, hover row, clickable-row affordance; empty states, loading skeletons, error
   banners. Light and dark both meet WCAG AA contrast for text (vitest/Playwright check on key screens).
3. Repositories list: full-width table — repository (name, muted group path, branch tag), languages,
   severity chips, ignored count, last scan (status chip + relative age), row ⋮ menu (Scan, Settings).
   Search by name/path, filters (show inactive, has open issues, last scan failed), sort (severity desc
   default, name, last scan), "N active repositories" count. Search/sort/filter server-side if the list is
   paginated. Whole row clickable → /repositories/:id (⋮ menu does not navigate).
4. Repository page /repositories/:id with URL-addressable tabs:
   - Header: name, path, branch, last scan status + age, Scan button, ⋮.
   - Coverage banner built only from stored scan data (latest scan warnings, scanner statuses, resolver
     coverage): e.g. "Dependencies resolved without a lockfile", "N dependencies could not be resolved",
     "Secret scanner failed in the last scan — its findings were not updated". Never suggests changing the
     repository (D-030). No banner when there is nothing to say.
   - Issues tab: repo-scoped issue table — type icon + level-2 category, title + subtitle, severity chip,
     location (primary manifest/file + "+N more"), fix time, status (New/To Do), ⋮ (open, ignore); search,
     type filter, category filter, Focus/All; server-side pagination.
   - Scans tab: GET /api/v1/repositories/{id}/scans with status, branch, commit (short SHA), duration,
     warnings count → warnings drawer.
   - Settings tab: the current repo settings (sensitivity, dev deps, active, branch to scan with the
     existing picker) inline; same API (PATCH) and validation.
5. Issue side panel (Feed and repo Issues tab): opens from a row; URL carries the group id (reload and
   shared link reopen it); next/prev through the current list; Esc closes. Sections:
   - Header: score + band as a gauge, title, subtitle, type + category chip, status, first seen.
   - Summary ("TL;DR") and "How do I fix it?": deterministic text built ONLY from stored fields
     (counts, CWE phrases, package, installed/fixed versions, manifest paths, secret type, rule id), no AI.
     Implemented as pure functions with unit tests covering SCA single/multi-vuln, SCA no fixed version,
     SAST, secret, IaC, EOL.
   - Advisory text: new SubIssueView field exposing sub_issue.advisory_summary (rendered as text).
   - Sub-issues grouped by location (manifest/file) with counts; each row keeps today's data (CVE/GHSA
     link, KEV, EPSS + attribution, installed→fixed, line, ignore actions, history).
   - "Why this score": the score_breakdown lines in plain words.
6. Secret context: masked preview only (≤4 chars, as stored); file + line; fix guidance per secret
   level-2 category (static text map, generic fallback: revoke/rotate first, then remove and move to a
   secret store; note that removal does not remove it from git history); links "Open file at scanned
   commit" and "View commit" from ONE link builder per D-084: inputs = stored connection host, stored
   project path, scan.commit_sha validated as 40 hex, repo-relative path URL-encoded per segment, integer
   line. GitLab shapes: https://<host>/<project>/-/blob/<sha>/<path>#L<line> and
   https://<host>/<project>/-/commit/<sha>. Opened with rel="noopener noreferrer". Unit tests reject:
   non-hex/short SHA, "..", absolute paths, javascript:/data: schemes, a host different from the stored
   one, and encode spaces/#/?. guard-forbidden still passes.
7. Feed, Dashboard, Ignored, Settings and Connect pages restyled to the new system; behaviour unchanged.
8. Last-scan age shown on the list and repo page (D-082).
9. Tests: vitest for text generators, link builder, chips/format; E2E: repositories search/sort → click
   row → repo page tabs (Issues/Scans/Settings) → open panel → next/prev → reload keeps the panel → secret
   panel link hrefs asserted → dark mode smoke; existing E2E specs still pass (update selectors, never
   weaken assertions); security-headers/CSP test unchanged and green.
10. Pending manual walkthrough (end of train): list → repo page → panel on core-keycloak, notification,
    secscan-test-secrets; secret links open the right line in GitLab.

=== M8.7 Multiple sources — detailed Done when ===
Scope: D-080, D-085, D-081 (3). One workspace, one feed, many providers.
1. V22: generalise the GitLab connection into a provider-neutral source connection (provider gitlab|github,
   host, target kind, credential kind none|token, encrypted token, scopes, expiry, last_verified_at) and
   repository identity = workspace + provider + host + provider's repository id. Backfill all existing rows
   as provider=gitlab on their current host; repository ids, findings, groups, triage events, ignore
   rules unchanged. Proven by a migration IT on a seeded pre-V22 DB (counts and ids identical before/after)
   and on an empty DB. Prefer additive columns + new unique index over table renames; if a rename is
   proposed, justify it in the plan.
2. Provider adapter interface (contract + per-provider implementation): verify credential (scopes,
   expiry), list accessible repositories (paginated, search), get repository (default branch, visibility),
   list branches (search, paging), check branch exists, clone URL; one error mapping
   (401/403/404/429/5xx/timeout); rate limits (Retry-After / reset headers) → transient, never "solved",
   never deactivation.
3. GitLab adapter: today's behaviour unchanged for gitlab.celloscope.net; gitlab.com and any other
   self-managed host work in the same workspace. Identity-collision IT: the same numeric project id on two
   GitLab hosts yields two distinct repositories.
4. GitHub adapter (github.com only; GitHub Enterprise Server later): fine-grained PAT with read-only
   Contents + Metadata, entered only in the UI; targets = a user/org (all repos the token sees) or a single
   repository; clone over HTTPS with the token delivered via the D-014 one-time file, never in the URL/env/logs.
5. Public repositories: "Add public repository" by URL for github.com, gitlab.com, or an allowed
   self-managed host; strict URL parsing (https only, host allowlisted, owner/name charset validated);
   anonymous metadata + branches via API; anonymous clone; stored as credential kind "none". Private or
   deleted later → existing branch_missing/inactive semantics; rate-limit → transient message.
6. Host policy per connection: https only; host allowlist (keep SECSCAN_GITLAB_ALLOWED_HOSTS working;
   propose in the plan how github.com/gitlab.com/public hosts are allowed — deny-by-default for
   self-managed hosts); redirects never followed with credentials; TLS always verified. Remove D-085's
   "do not add a second GitLab host" restriction only when this ships (new D-entry).
7. Scanner/worker: clone path provider-neutral; branch handling unchanged (D-076/D-079 vectors still pass
   in-image); run-scan.sh accepts only https clone URLs to allowed hosts.
8. Branch picker, default-branch refresh (A6) and branch-missing (A5) work for every provider.
9. UI: "Connect GitLab" becomes "Sources": add GitLab (self-managed or gitlab.com), GitHub, or a public
   repository; connection cards with provider icon, host, target, expiry warning, Rotate; Select
   repositories per connection; provider icon on repositories list, repo page and feed rows; provider
   filter on the list and feed.
10. Finding fingerprints unchanged (provider/host are repository identity only).
11. Tests: WireMock GitHub API fixtures (repos, branches, pagination, 401/403/404, 429 with Retry-After,
    redirect not followed, token absent from logs/errors); IT: one workspace with self-managed GitLab +
    gitlab.com + private GitHub + public GitHub repos → one feed lists all four, each scan clones with the
    right credential kind; E2E with seeded data for the Sources page and provider icons/filters.
12. Pending manual walkthrough (end of train, needs Sajal's tokens in the UI): gitlab.com mirror of
    notification vs the celloscope original on the same commit → same finding set; one private GitHub
    repository; one public GitHub repository; ib-api-v2 onboarded and walked through (D-083).

=== REPORT BACK NOW (Phase A) ===
The full plan per "PHASE A", the CHECKLIST answers, disk preflight numbers, the brief + state file commit
hashes — then STOP.

## Approved plan changes (2026-10-27)

Train plan approved with these changes. First append this whole message verbatim to
docs/plans/M8x-train-brief.md under a heading "Approved plan changes (2026-10-27)", record the approval
date in docs/plans/M8x-train-state.md, commit ("docs(plans): train plan approval + changes"). These changes
are binding for M8.5, M8.6 and M8.7 and must be re-read by every later session.

M8.5
1. S1 OD-19 retire pass: endpoint acts ONLY on the authenticated user's workspace (no cross-workspace
   sweep — admin endpoints are ungated, OD-9). dryRun defaults to true and returns the candidate list
   (sub_issue ids, rule, file, reason). Candidate = status 'ignored' AND hold re-derivable (auto cause, or a
   rule/path/cve-scope rule; sub-issue-scope manual ignores are NOT touched) AND the repository's latest scan
   on its effective branch completed with that finding's scanner succeeded and authoritative AND the
   fingerprint is absent from that scan. Each retire writes triage_event ignored→solved, actor system,
   reason SOLVED_NOT_DETECTED, scan_id = that latest scan; BRANCH_CHANGED only when the absence is caused by
   a branch switch (reconcile path). Idempotent. Add IT: subIssueScopeManualIgnoreIsNeverRetired.
2. S3 taxonomy backfill: NOT an endpoint. Run automatically on api startup after Flyway, idempotent, only
   rows with level2_category null, per workspace, DB-only, no status change, no triage_event. Reconcile sets
   the category for new/updated groups. TaxonomyBackfillIT asserts null count 0 after startup.
3. S4 (your question a = yes): reuse advisory_summary for the Checkov check_name. IaC group title =
   check_name (fallback check_id); IaC subtitle must not equal the title — use the check_id. Existing IaC
   groups' titles are refreshed on their next successful scan (title is descriptive). Add
   IaCTitleIT.existingGroupTitleRefreshedOnNextScan.
4. S3 (your question b = yes): coarse IaC platform heuristic with fallback "Infrastructure"; record as a D-draft.
5. S2 V20: global table accepted (D-050 family) with the CLAUDE.md rule-1 exception + DECISIONS entry; seed
   only check_ids confirmed in real Checkov fixture output, else ship empty. Report lists "no UI to manage
   IaC overrides yet" under known gaps.
6. S5 languages: lang_detect counts file SIZES by extension only — never reads file contents; skips .git,
   node_modules, vendor, build, dist, target, .gradle; never follows symlinks (realpath-confined to the
   checkout); display names match GitLab's (Java, Kotlin, HTML, CSS, JavaScript, TypeScript, Python,
   FreeMarker, Shell, Dockerfile, C#, …); keep languages ≥1% share; bounded runtime. A detection failure
   never fails the scan (scan warning only) and never clears existing languages. In-image tests cover
   symlink-out, vendored dir skipped, empty repo.
7. OWASP Top 10 2025 names: use only the official final 2025 list. If it cannot be verified as final from
   owasp.org during S3, leave owasp_category null everywhere and record it — never guess names.
8. New·7d SQL accepted as proposed (row-level New/To Do status unchanged).

M8.6
9. Accepted as planned. SubIssueView host may be null (detached repo) → no source link rendered. The
   sourceLink builder covers GitLab shapes in M8.6 (incl. a self-managed host with a non-default port).
10. U4: write the exact scan-warning-code → banner-text map into the work log and unit-test it.

M8.7 (V22 design changes — binding)
11. Connection uniqueness: replace uq_gitlab_connection_group / uq_gitlab_connection_project with indexes
    that include provider and host (workspace_id, provider, host, <group id | project id>).
12. Connection targets: relax ck_gitlab_connection_target and extend target_type to cover GitHub targets
    (owner = user/org, repo = single repository) and public repositories; add the columns they need (e.g.
    owner login, repo full name, provider repo id). Public repositories: credential_kind 'none'; state in
    V0 whether a public repo gets its own connection row or an anonymous per-host connection, and why.
13. repository: provider_repo_id NOT NULL; gitlab_project_id becomes nullable (GitHub rows); GitHub
    identity = GitHub's numeric repository id (stable across renames), full name kept as display path.
14. Host backfill: host keeps an explicit non-default port (host:port). It must not fail when a workspace
    has repositories but no connections: run \d repository first; derive host from a stored repository
    URL column if one exists, else the single configured SECSCAN_GITLAB_ALLOWED_HOSTS value, else HARD STOP
    with the counts. MigrationV22IT adds: detachedRepoWithNoConnections, gitlabGroupIdCollisionAcrossHosts,
    githubConnectionRowAccepted, idsAndFindingsUnchangedAfterBackfill.
15. V0 writes the final V22 SQL into the work log, and V1 must have MigrationV22IT green before any other
    M8.7 slice starts.
16. sourceLink gains GitHub shapes (https://github.com/<owner>/<repo>/blob/<sha>/<path>#L<line> and
    /commit/<sha>) with the same rejection tests.
17. Built-in SaaS hosts (github.com, api.github.com, gitlab.com) are allowed by default but can be switched
    off by configuration (egress control, D-033); document the property.

Then run Phase B for M8.5 only, following the brief (BUILD LOOP, failure policy, HARD STOP, MILESTONE
FINISH). When M8.5 is finished, set the state to "next: M8.6 via PROMPT 3" and STOP this session.

Report back: approval commit hash; the M8.5 report path + commit; final gate counts (core/app tests,
vitest, guard, e2e ×2); every D-draft; the OD-19 dry-run result shape; pending manual walkthrough steps.
