-- M6: the end-of-life (EOL) intel cache. A GLOBAL reference table with NO workspace_id — the justified D-050
-- exception, exactly as kev_entry / epss_score: public reference data (endoflife.date) keyed by product+cycle,
-- joined to workspace-scoped sub_issue rows (which carry the scoping). Column types mirror the endoflife.date
-- API v1 release object (D-055): camelCase fields name/releaseDate/isEol/eolFrom/isLts/isMaintained/latest.*.
-- Loaded weekly by EolSyncService (api profile), per-product upsert (never a full wipe); read by the worker's
-- EolEnricher (is this detected cycle actually EOL / ending soon?) and by the scorer (base_eol, Slice 4).
--
-- Additive only; V1-V15 untouched. The sub_issue/issue_group/scan_finding type CHECK constraints already allow
-- 'eol' (added in V6/V8), and the EOL fingerprint (product | cycle | file_path) reuses existing columns
-- (package = product, installed_version = cycle), so no business-table change is needed for M6's new types.
create table eol_cycle (
    product       text    not null,           -- endoflife.date slug (spring-boot, dotnet, nodejs, eclipse-temurin, alpine-linux, keycloak, …)
    cycle         text    not null,           -- the v1 release "name" (e.g. "2.7", "21", "6.0", "3.14")
    is_eol        boolean not null,           -- v1 isEol
    eol_from      date,                        -- v1 eolFrom (nullable)
    latest        text,                        -- v1 latest.name (the current release in the cycle)
    latest_date   date,                        -- v1 latest.date
    release_date  date,                        -- v1 releaseDate
    is_lts        boolean not null default false,  -- v1 isLts
    is_maintained boolean not null default false,  -- v1 isMaintained
    synced_at     timestamptz not null default now(),
    primary key (product, cycle)
);
