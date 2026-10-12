-- M2: the scan engine. One scan = one repository at one commit. PostgreSQL is also the job queue (D-003):
-- scan_job rows are claimed with SELECT ... FOR UPDATE SKIP LOCKED, no Redis/broker.
--
-- Every business table carries workspace_id and every query filters by it (CLAUDE.md domain rule 1); the
-- spec's data-model table lists "key columns" only, so adding workspace_id to the child tables does not
-- contradict it. Findings parsed from a scanner are stored as raw scan_finding rows now (D-024); M3 reconciles
-- them into issue_group/sub_issue with stable fingerprints.

create table scan (
    id            uuid        primary key,
    workspace_id  uuid        not null references workspace (id),
    repository_id uuid        not null references repository (id),
    trigger       text        not null check (trigger in ('schedule', 'commit', 'manual')),
    commit_sha    text,
    status        text        not null
                    check (status in ('queued', 'running', 'completed', 'partial', 'failed')),
    -- Tool + rule versions recorded per scan (CLAUDE.md security rules); used by M9 skip-unchanged.
    tool_versions jsonb       not null default '{}'::jsonb,
    started_at    timestamptz,
    finished_at   timestamptz,
    created_at    timestamptz not null default now()
);

-- At most one in-flight scan per repository: a new Start Scan returns the existing queued/running scan rather
-- than enqueuing a second. Enforced here so a concurrent double-submit cannot create two (service recovers).
create unique index uq_scan_active_per_repository
    on scan (repository_id)
    where status in ('queued', 'running');

create index idx_scan_workspace on scan (workspace_id);
create index idx_scan_repository on scan (repository_id);

-- One row per scanner that ran in a scan; tracks partial-failure (CLAUDE.md domain rule 3: a scanner that
-- failed/timed out never closes its findings). M2 runs only the secret scanner (Gitleaks).
create table scan_task (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id),
    scan_id      uuid        not null references scan (id) on delete cascade,
    scanner      text        not null,
    status       text        not null check (status in ('ok', 'failed', 'timed_out')),
    exit_code    int,
    duration_ms  bigint,
    -- Truncated and token-scrubbed before storage (never full tool output, never a secret value).
    log_excerpt  text,
    created_at   timestamptz not null default now()
);

create index idx_scan_task_scan on scan_task (scan_id);

-- The queue. state drives claiming; attempts/run_after drive retry with backoff; locked_by/locked_at are the
-- lease (a stale lease is requeued). Exactly one job per scan.
create table scan_job (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id),
    scan_id      uuid        not null references scan (id) on delete cascade,
    state        text        not null check (state in ('queued', 'running', 'done', 'failed')),
    attempts     int         not null default 0,
    locked_by    text,
    locked_at    timestamptz,
    run_after    timestamptz not null default now(),
    created_at   timestamptz not null default now(),
    constraint uq_scan_job_scan unique (scan_id)
);

-- The claim query filters on state + run_after; the lease sweep filters on state + locked_at.
create index idx_scan_job_claim on scan_job (state, run_after);
create index idx_scan_job_lease on scan_job (state, locked_at);

-- Raw normalized findings (D-024, pipeline step 7). One row per finding per scan; no dedup/reconcile yet
-- (that is M3). Secret findings store only a SHA-256 hash and a 4-char masked preview, never the value
-- (CLAUDE.md security rules).
create table scan_finding (
    id                uuid        primary key,
    workspace_id      uuid        not null references workspace (id),
    scan_id           uuid        not null references scan (id) on delete cascade,
    repository_id     uuid        not null references repository (id),
    type              text        not null check (type in ('sca', 'sast', 'secret', 'iac', 'eol')),
    scanner           text        not null,
    rule_id           text,
    vuln_id           text,
    package           text,
    installed_version text,
    fixed_version     text,
    file_path         text,
    line              int,
    snippet_hash      text,
    secret_hash       text,
    secret_preview    text,
    tool_severity     text,
    created_at        timestamptz not null default now()
);

create index idx_scan_finding_scan on scan_finding (scan_id);
create index idx_scan_finding_repository on scan_finding (repository_id);
