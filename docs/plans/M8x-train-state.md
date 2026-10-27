# M8.x train state
- Train plan approved: yes (2026-10-27) — with binding changes appended to M8x-train-brief.md "Approved plan changes (2026-10-27)"
- Current milestone: M8.7 (in progress); M8.6 DONE + pre-check gap fixed (category-filter E2E, commit d6d5269)
- Current slice: V5 DONE (host policy + D-089); M8.7 PAUSED after V5 — foundation complete, integration half remains
- Branch / last green commit: feature/m8.7-multi-source @ b624e81 (stacked on feature/m8.6-ui-uplift @ d6d5269)
- Last gate results: G1 app 411 / core 179 / 0 failures (V5) | G2 (M8.6 pre-check) vitest 79 + guard 64 | G3 e2e 90 ×2
- HARD STOP: none (everything committed is green). M8.7 is NOT finished — paused at a clean boundary (V0–V5 done).
- Done so far: V0 (SQL+decisions), V1 (V22 migration + MigrationV22IT, 7 ITs), V2 (SourceProvider contract +
  HttpErrors), V3 (GitLab adapter + provider-neutral identity + CrossHostIdentityIT), V4 (GitHub adapter +
  GitHubProviderIT, 11), V5 (host policy built-in-SaaS default-on + retire D-085 → D-089). Both provider adapters
  complete with full error/rate-limit/redirect/token-safety coverage; identity = (workspace, provider, host,
  provider_repo_id) live in the GitLab path.
- D-entries added: D-089 (host policy + D-085 retired). D-drafts: (V1) repository identity columns filled on
  insert by a DB trigger (compatibility shim); documented in M8.7-worklog.md.

### REMAINING for M8.7 (V6–V9) — precise handoff
Pick up on feature/m8.7-multi-source @ b624e81. Re-read M8x-train-brief.md "M8.7" + approval 11–17 and
docs/reports/M8.7-worklog.md first. The provider adapters (GitLabProvider/GitHubProvider via ProviderRegistry)
and the V22 schema are ready to wire in.
- V6a Entity: `Repository.gitlabProjectId` is still primitive `long`; GitHub rows need it NULL. Change it to
  `Long` and fix callers: RepositoryView field, ScanJobProcessor (lines ~222/291/298), ScanService (~100),
  RepositoryService (~300/373) all unbox for GitLab-only paths; GitLabConnectionService.pickReassignTarget
  (~205) match by providerRepoId (not `==` on Long). Add `Repository.github(ws, connId, host, githubRepoId,
  fullName, defaultBranch, langs)` (provider='github', providerRepoId=githubRepoId, gitlabProjectId=null).
- V6b Connection CREATE (provider-neutral): add GitlabConnection factories forGitHubOwner / forSingleRepo
  (github|public, credentialKind token|none). New `SourceConnectionService`: createGitHubOwner(login, token),
  createGitHubRepo(owner/name, token), createPublicRepo(url). Strict public-URL parser (https only, host
  allowlisted via GitLabHostPolicy, owner/name charset). Host-scoped dedup (reuse the V22 uq_* indexes). Encrypt
  token (TokenCipherHolder) or store none. ITs via GitHubWireMock + a public (anonymous) WireMock.
- V6c Selection (provider-neutral): generalise RepositoryService.select/listProjects to route via
  ProviderRegistry by connection.provider (GitLab keeps today's exact path incl. RepositoryConnectionGuard;
  GitHub/public list via the adapter and upsert through a generic `RepositoryUpserter.upsertSource`). ws-scoped.
- V7 Worker/scanner (SECURITY-CRITICAL — do carefully): ScanJobProcessor clone path provider-neutral — clone URL
  from `registry.require(repo.getProvider()).cloneUrl(...)`; token only for credentialKind=token, delivered via
  the D-014 one-time file (never URL/env/log); credentialKind=none → anonymous clone. Branch verify / default
  refresh / languages via the adapter (A5/A6/D-076). run-scan.sh: accept only https clone URLs to allowed hosts
  (keep file:// test-only flag). D-076/D-079 branch vectors still pass in-image. Rebuild the scanner image
  (`./gradlew :app:buildScannerImage`) + run its in-image tests. Keep container hardening unchanged.
- V8 UI "Sources": Connect→Sources (add GitLab self-managed/gitlab.com, GitHub, public repo); connection cards
  (provider icon, host, target, expiry, Rotate); Select repositories per connection; provider icon on list/repo/
  feed rows; provider filter; sourceLink GitHub shapes (approval 16: https://github.com/<o>/<r>/blob/<sha>/<path>
  #L<line> and /commit/<sha>) with the same rejection tests. G2 + G3.
- V9 Tests + finish: cross-provider IT (one workspace: self-managed GitLab + gitlab.com + private GitHub + public
  GitHub → one feed; each scan clones with the right credential kind; mirror parity), E2E Sources page +
  provider icons/filters; ./gradlew clean build --rerun-tasks; G2; G3 ×2; docs/reports/M8.7.md; set state to
  "train done; next: /review-pr per milestone".
- Public repositories modelling (decided, approval 12): a public repo is its OWN gitlab_connection row,
  credential_kind='none', target_type='repo', no token (see V0 decision in M8.7-worklog.md).
- HARD STOP: none
- D-drafts so far (M8.7): (V1) repository identity columns filled on insert by a DB trigger as a compatibility
  shim; entities map/set them in V3/V4. In docs/reports/M8.7-worklog.md.
- Next action: continue M8.7 Phase B — V2 provider adapter contract, V3 GitLab adapter + entity/service
  generalization + collision IT, V4 GitHub adapter (WireMock), V5 public repos, V6 host policy (+ retire D-085
  restriction), V7 scanner/worker, V8 UI Sources + sourceLink GitHub shapes, V9 tests + finish. Per
  docs/plans/M8x-train-brief.md "M8.7" + approval items 11–17 and docs/reports/M8.7-worklog.md slice plan.
  Pending manual walkthroughs for the whole train run at the end. Not merged to develop (human merges in order).
