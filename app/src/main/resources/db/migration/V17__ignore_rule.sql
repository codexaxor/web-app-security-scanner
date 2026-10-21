-- M7: the manual-ignore model (D-058). Users triage findings by creating ignore_rule rows (SPEC "Manual ignore
-- scopes"): this sub-issue, this group, a path prefix (per repo), a CVE (global or per repo), or a rule id
-- (global or per repo). .secscan.yml CVE ignores are materialized as idempotent SYSTEM rules (created_by =
-- 'secscan.yml', D-057). Apply/remove runs through reconcile and writes a triage_event for every status change
-- (domain rules 2-4).
--
-- Additive only; V1-V16 untouched. Every business table carries workspace_id and every query filters by it
-- (domain rule 1). ignore_rule is per-tenant business data, so it carries workspace_id (unlike the D-050 global
-- intel caches).
create table ignore_rule (
    id            uuid        primary key,
    workspace_id  uuid        not null references workspace (id),
    scope         text        not null check (scope in ('subissue', 'group', 'path', 'cve', 'rule')),
    -- NULL = a global rule (applies across every repository in the workspace); otherwise scoped to one repo.
    repository_id uuid        references repository (id),
    -- The value matched per scope: subissue = fingerprint (SAST matches on rule_id|file_path, D-062);
    -- group = group_key; path = a repo-relative path prefix; cve = a CVE id; rule = a rule id.
    match_value   text        not null,
    -- Human reason (SPEC: every ignore requires one). For a .secscan.yml CVE rule this is the repo-authored
    -- reason; for a manual rule the user's reason. NOT NULL.
    reason        text        not null,
    -- 'secscan.yml' for the materialized in-repo-config rules (D-057), else the acting user (an id/email; a
    -- literal 'system' is used until M10 roles). Never a secret.
    created_by    text        not null,
    created_at    timestamptz not null default now()
);

create index idx_ignore_rule_workspace on ignore_rule (workspace_id);
create index idx_ignore_rule_lookup on ignore_rule (workspace_id, scope, match_value);
create index idx_ignore_rule_repository on ignore_rule (repository_id);
-- Idempotent re-materialization of .secscan.yml rules each scan (D-057 revoke lifecycle): a repo-scoped system
-- rule is unique per (workspace, repository, scope, match_value) so the worker can upsert without duplicating.
create unique index uq_ignore_rule_repo_scoped
    on ignore_rule (workspace_id, repository_id, scope, match_value)
    where repository_id is not null;
create unique index uq_ignore_rule_global
    on ignore_rule (workspace_id, scope, match_value)
    where repository_id is null;

-- Which rule (if any) currently holds a sub_issue ignored, and when it was ignored. ON DELETE SET NULL (D-058,
-- review change #2): deleting a rule must never delete the finding — the unignore path clears the status and
-- writes a triage_event. ignore_reason (V8) now carries the reason CODE (EXCLUDED_PATH / IGNORE_RULE / ...);
-- the human reason comes from ignore_rule.reason via ignored_by_rule_id. Both columns nullable, no backfill.
alter table sub_issue add column ignored_by_rule_id uuid references ignore_rule (id) on delete set null;
alter table sub_issue add column ignored_at timestamptz;
