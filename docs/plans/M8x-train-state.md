# M8.x train state
- Train plan approved: yes (2026-10-27) — with binding changes appended to M8x-train-brief.md "Approved plan changes (2026-10-27)"
- Current milestone: done — TRAIN DONE (M8.5 → M8.6 → M8.7 all built & green). M8.6 pre-check gap fixed (d6d5269).
- Current slice: M8.7 DONE (V0–V9 complete + finish)
- Branch / last green commit: feature/m8.7-multi-source @ 72e0b14 (stacked on feature/m8.6-ui-uplift @ d6d5269)
- Last gate results: G1 clean build app 427 / core 179 / 0 failures | G2 vitest 87 + guard 67 | G3 e2e 92 ×2
- HARD STOP: none. Train done; next: /review-pr per milestone (fresh sessions), then human merges m8.5 → m8.6 → m8.7.
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
- Next action: **train done; next: /review-pr per milestone**. Run `/review-pr M8.5`, `/review-pr M8.6`,
  `/review-pr M8.7` in fresh sessions; a fix to an earlier branch is committed there and MERGED FORWARD (git
  merge, never rebase/force-push) into the later branches; the human then merges m8.5 → m8.6 → m8.7 into
  develop/main in order. Pending manual walkthroughs for the whole train are listed in docs/reports/M8.5.md,
  M8.6.md and M8.7.md (ib-api-v2 onboarded then, D-083); they run at the end of the train.
