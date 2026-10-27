# M8.x train state
- Train plan approved: yes (2026-10-27) — with binding changes appended to M8x-train-brief.md "Approved plan changes (2026-10-27)"
- Current milestone: M8.7 (in progress); M8.6 DONE + pre-check gap fixed (category-filter E2E, commit d6d5269)
- Current slice: V1 DONE (V22 migration + MigrationV22IT green); next: V2 (provider adapter contract)
- Branch / last green commit: feature/m8.7-multi-source @ 41c52d7 (stacked on feature/m8.6-ui-uplift @ d6d5269)
- Last gate results: G1 app 383 / core 179 / 0 failures | G2 (M8.6 pre-check) vitest 79 + guard 64 | G3 e2e 90 ×2
- HARD STOP: none
- D-drafts so far (M8.7): (V1) repository identity columns filled on insert by a DB trigger as a compatibility
  shim; entities map/set them in V3/V4. In docs/reports/M8.7-worklog.md.
- Next action: continue M8.7 Phase B — V2 provider adapter contract, V3 GitLab adapter + entity/service
  generalization + collision IT, V4 GitHub adapter (WireMock), V5 public repos, V6 host policy (+ retire D-085
  restriction), V7 scanner/worker, V8 UI Sources + sourceLink GitHub shapes, V9 tests + finish. Per
  docs/plans/M8x-train-brief.md "M8.7" + approval items 11–17 and docs/reports/M8.7-worklog.md slice plan.
  Pending manual walkthroughs for the whole train run at the end. Not merged to develop (human merges in order).
