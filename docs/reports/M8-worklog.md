# M8 (UI) — Phase B work log

Branch `feature/m8-ui` from `develop` @ `a113fc1`. Autonomous build loop S0→S9.
No `/finish-milestone` (Sajal's real-GitLab walkthrough comes first). No migration, no scanner/hardening change.

Gate per slice: G1 `./gradlew build`; G2 `cd ui && npm ci && npm run build && npm run typecheck && npm test && guard`;
G3 `npm run e2e` (S0, S3–S9; S1/S2 run the existing-screens smoke); G4 clean git, no secrets staged.

## Approved-change tracker (plan changes 1–10)

| # | Change | Slice | Commit | Closed by (named test) |
|---|---|---|---|---|
| 1 | Repo activation rules + 409s | S1 | _pending_ | _pending_ |
| 2 | includeInactive default false (feed/summary/dashboard) | S2 | _pending_ | _pending_ |
| 3 | 7-day counts: type+repo filters only, New=created_at<7d | S2 | _pending_ | _pending_ |
| 4 | nginx headers `always` + all locations | S0 | S0 | `e2e security.spec.ts` (5 cases: /, deep link, asset, 404, /api) |
| 5 | rescoreRepository same lock/guard + concurrency test | S1 | _pending_ | _pending_ |
| 6 | pin node + playwright by digest; pw non-root, no socket | S0 | S0 | `ui/Dockerfile` digest; `e2e/run-e2e.mjs` (pwuser, no socket, ro mount) |
| 7 | workspace-2 complete seed dataset | S0/S2 | S0 (scaffold) | `e2e/fixtures/seed.sql` (full set in S2) |
| 8 | path-scope ignore matches IgnoreMatcher format | S6 | _pending_ | _pending_ |
| 9 | error handler: no stack traces; 401 entrypoint unchanged | S1 | _pending_ | _pending_ |
| 10 | per-run e2e pg pw; ScanCreatedAtIT→R1/R4; post-signin route restore | S0/S1/S3 | S0 (pg pw) | `e2e/run-e2e.mjs` (per-run secrets) |

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
