-- M5 scoring. Additive only (V1-V11 are immutable once applied): new columns on sub_issue/scan_finding and
-- three global intel/config tables. Nothing here edits or drops existing structure.
--
-- Derived-scoring columns on sub_issue (SPEC data model; D-044..D-049). score/score_breakdown/in_kev/epss are
-- recomputed by the scorer (scan-time in reconcile, and the api re-score job) and MUST NEVER feed a fingerprint
-- (domain rule 2). cvss/severity_label/cwe are descriptive, persisted from OSV so the re-score path can
-- recompute the base with no rescan (D-044 split: cvss = the CVSS vector string only, null when none;
-- severity_label = OSV database_specific.severity, the no-CVSS base source). cve_aliases persists the group's
-- CVE aliases so KEV "any CVE alias" matching (D-046) works at scan time AND at DB-only re-score time (D-051);
-- it is descriptive only and is NOT a fingerprint input.
alter table sub_issue add column score            int;
alter table sub_issue add column score_breakdown  jsonb;
alter table sub_issue add column cvss             text;
alter table sub_issue add column severity_label   text;
alter table sub_issue add column cwe              jsonb;
alter table sub_issue add column epss             numeric;
alter table sub_issue add column in_kev           boolean not null default false;
alter table sub_issue add column cve_aliases       jsonb;

-- scan_finding is the raw per-scan store reconcile reads to build sub_issues (D-024), so it carries the same
-- OSV-derived descriptive fields. tool_severity already holds OSV groups[].max_severity (the numeric base).
alter table scan_finding add column cvss           text;
alter table scan_finding add column severity_label text;
alter table scan_finding add column cwe            jsonb;
alter table scan_finding add column cve_aliases    jsonb;

-- Global intel/config caches (D-050): public CVE-keyed intel and global config, joined to workspace-scoped
-- sub_issue rows by CVE/category. They carry NO workspace_id (the justified exception to domain rule 1); the
-- SPEC data model already lists epss_score/kev_entry as cve/product-keyed caches, not per-tenant business data.
-- Revisit when multi-tenant intel isolation or per-workspace fix-time is needed.

-- CISA KEV catalog (D-046): a CVE present here adds +5 and an on_kev line to any sub-issue whose vuln id or any
-- cve_alias matches. Loaded daily by KevSyncService (via IntelSyncScheduler, api profile). US-gov public domain.
create table kev_entry (
    cve        text        primary key,
    vuln_name  text,
    date_added date,
    synced_at  timestamptz not null default now()
);

-- FIRST EPSS (D-047): exploitation probability + percentile per CVE. Loaded daily by EpssSyncService (api profile).
-- Stored and exposed, but EPSS score adjustments stay OFF in M5 (an M7 toggle). Data (c) FIRST.org.
create table epss_score (
    cve        text        primary key,
    epss       numeric     not null,
    percentile numeric,
    synced_at  timestamptz not null default now()
);

-- Tunable fix-time minutes per remediation category (SPEC "Fix time"). Seeded in V13. SCA uses PATCH_MINOR vs
-- MAJOR (derived from installed->fixed semver); secret uses ROTATION; SAST/IaC/EOL categories are seeded but
-- unused until M6.
create table fix_time_config (
    category text primary key,
    minutes  int  not null
);
