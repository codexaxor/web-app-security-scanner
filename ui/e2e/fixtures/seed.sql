-- E2E seed (NOT a Flyway migration). Applied after the api has booted (so Flyway V1..V18 have run) by
-- run-e2e.mjs via `psql`. Idempotent: wrapped in a transaction and safe to re-apply to a fresh e2e volume.
--
-- Grows per slice. Workspace 1 is the seeded default tenant (Workspace.DEFAULT_ID) that the UI shows; a
-- SECOND workspace (change 7) carries a COMPLETE dataset whose values would visibly change every count if it
-- leaked — it must NEVER appear in the UI or any count, proving workspace isolation.
--
-- S0: no business rows yet (the onboarding + header smoke needs none). S2 adds the full fixture set.
begin;
-- placeholder — see S2 for the full seed.
commit;
