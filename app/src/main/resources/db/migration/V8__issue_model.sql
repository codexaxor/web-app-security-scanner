-- M3: the deduplicated issue model. M2 stored raw scan_finding rows per scan (D-024); reconcile turns those
-- into issue_group (what users triage) and sub_issue (one concrete finding with a stable fingerprint), tracking
-- the open/solved/reopened lifecycle and writing a triage_event for every status change (CLAUDE.md domain rules
-- 2-4, SPEC "Issue lifecycle"). Scoring columns (cvss/cwe/epss/score/...) are added by M5's own migration;
-- migrations are immutable once applied, so new behaviour is always a new migration (V8+), never an edit.
--
-- Every business table carries workspace_id and every query filters by it (domain rule 1).

-- Atomicity + stale guard (M3 plan additions 1 & 2): findings are stored and reconciled in one transaction,
-- which stamps reconciled_at. Reconcile also skips a scan when a newer scan of the same repository has already
-- been reconciled (out-of-order completion must not let an older view re-open/re-solve findings).
alter table scan add column reconciled_at timestamptz;

-- A Feed row: the group users triage. status/severity are derived from its sub-issues (M3 derives status;
-- severity_score and fix_time_minutes are populated by M5). group_key is "type|discriminator" (Fingerprints).
create table issue_group (
    id               uuid        primary key,
    workspace_id     uuid        not null references workspace (id),
    repository_id    uuid        not null references repository (id),
    type             text        not null check (type in ('sca', 'sast', 'secret', 'iac', 'eol')),
    group_key        text        not null,
    title            text        not null,
    subtitle         text,
    status           text        not null default 'open'
                       check (status in ('open', 'solved', 'ignored')),
    severity_score   int,
    fix_time_minutes int,
    first_seen_at    timestamptz not null default now(),
    created_at       timestamptz not null default now(),
    constraint uq_issue_group_key unique (workspace_id, repository_id, group_key)
);

create index idx_issue_group_workspace on issue_group (workspace_id);
create index idx_issue_group_repository on issue_group (repository_id);

-- One concrete finding. Its identity is fingerprint (SHA-256 of the type's fields, never the line number),
-- unique per repository. scanner records which scanner produced it so reconcile can honour per-scanner success
-- (a failed scanner never closes its findings). secret_hash is the keyed HMAC (D-027) and key_id the short
-- fingerprint-key id (D-029); neither is ever returned by the API. line is informational only.
create table sub_issue (
    id                 uuid        primary key,
    workspace_id       uuid        not null references workspace (id),
    group_id           uuid        not null references issue_group (id) on delete cascade,
    repository_id      uuid        not null references repository (id),
    type               text        not null check (type in ('sca', 'sast', 'secret', 'iac', 'eol')),
    scanner            text        not null,
    fingerprint        text        not null,
    vuln_id            text,
    rule_id            text,
    package            text,
    installed_version  text,
    fixed_version      text,
    file_path          text,
    line               int,
    snippet_hash       text,
    secret_hash        text,
    secret_preview     text,
    key_id             text,
    tool_severity      text,
    status             text        not null default 'open'
                         check (status in ('open', 'ignored', 'solved')),
    ignore_reason      text,
    first_seen_scan_id uuid        references scan (id),
    last_seen_scan_id  uuid        references scan (id),
    solved_at          timestamptz,
    created_at         timestamptz not null default now(),
    constraint uq_sub_issue_fingerprint unique (workspace_id, repository_id, fingerprint)
);

create index idx_sub_issue_workspace on sub_issue (workspace_id);
create index idx_sub_issue_group on sub_issue (group_id);
create index idx_sub_issue_repository on sub_issue (repository_id);

-- The audit trail: one row per status change (domain rule 4), actor system or user, with the reason code and
-- the scan that caused it. Either sub_issue_id or group_id identifies the subject (both nullable so the table
-- can hold group-level events later).
create table triage_event (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id),
    sub_issue_id uuid        references sub_issue (id) on delete cascade,
    group_id     uuid        references issue_group (id) on delete cascade,
    from_status  text,
    to_status    text,
    actor        text        not null check (actor in ('system', 'user')),
    reason_code  text,
    scan_id      uuid        references scan (id),
    at           timestamptz not null default now()
);

create index idx_triage_event_workspace on triage_event (workspace_id);
create index idx_triage_event_sub_issue on triage_event (sub_issue_id);
create index idx_triage_event_group on triage_event (group_id);

-- D-028: a dedicated table for repository lifecycle events (connection reassignment/deactivation on removal,
-- D-020), kept separate from triage_event (which is specifically the issue-status audit trail). from/to
-- connection ids are plain uuids, not FKs: the "from" connection is typically deleted at the moment the event
-- is written.
create table repository_event (
    id                 uuid        primary key,
    workspace_id       uuid        not null references workspace (id),
    repository_id      uuid        not null references repository (id),
    event_type         text        not null
                         check (event_type in ('REASSIGNED', 'DETACHED', 'DEACTIVATED', 'CONFIRMED',
                                               'REATTACHED')),
    from_connection_id uuid,
    to_connection_id   uuid,
    reason_code        text,
    detail             text,
    at                 timestamptz not null default now()
);

create index idx_repository_event_workspace on repository_event (workspace_id);
create index idx_repository_event_repository on repository_event (repository_id);
