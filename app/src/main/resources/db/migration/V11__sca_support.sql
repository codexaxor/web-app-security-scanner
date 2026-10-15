-- M4 SCA support. Three additive changes (V1-V10 are immutable once applied):
--
-- 1. scan_warning: scan-health warnings surfaced per scan (SPEC data model; D-030/D-031) — a coverage gap we
--    show, never hide. code is a machine code (MISSING_GRADLE_LOCKFILE, MISSING_NUGET_LOCKFILE,
--    UNRESOLVED_VERSION, NO_MANIFESTS); file_path is the repo-relative build file, or null when repo-wide.
-- 2. scan_finding.ecosystem: the SCA ecosystem (Maven/npm/NuGet/…) so the issue group key is
--    repo + ecosystem + package (SPEC "Grouping", plan change #7) and the same package name in two ecosystems
--    forms two groups.
-- 3. scan_task.closes_findings: whether a SUCCESSFUL scanner is authoritative to close its missing findings
--    this scan. False for an SCA task that found no packages (OSV exit 128): the scan is ok but must not
--    mass-solve existing SCA issues (D-034, plan change #1). A failed/timed-out task closes nothing regardless.

create table scan_warning (
    id            uuid        primary key default gen_random_uuid(),
    workspace_id  uuid        not null references workspace (id),
    repository_id uuid        not null references repository (id),
    scan_id       uuid        not null references scan (id) on delete cascade,
    code          text        not null,
    message       text        not null,
    file_path     text,
    created_at    timestamptz not null default now()
);

create index idx_scan_warning_scan on scan_warning (scan_id);
create index idx_scan_warning_repository on scan_warning (repository_id);

alter table scan_finding add column ecosystem text;

alter table scan_task add column closes_findings boolean not null default true;
