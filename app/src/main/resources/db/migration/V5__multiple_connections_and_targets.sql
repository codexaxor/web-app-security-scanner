-- M1b: allow many GitLab connections per workspace, each targeting either a whole group or a single project.
-- A repository still exists once per workspace (V4's uq_repository_workspace_project); connection_id records
-- the connection used to reach it. Removing a connection never deletes repository rows (scan history hangs off
-- them from M2) — its repositories are reassigned to another connection that still exposes them, or deactivated.

-- A workspace may now hold several connections, so the one-per-workspace constraint is dropped.
alter table gitlab_connection
    drop constraint uq_gitlab_connection_workspace;

alter table gitlab_connection
    add column target_type       text        not null default 'group'
                                   check (target_type in ('group', 'project')),
    add column gitlab_project_id bigint,
    add column project_full_path text,
    -- Set on every successful test-then-save / token rotation; used to break reassignment ties.
    add column last_verified_at  timestamptz;

-- Group columns are only required for group-mode connections (project-mode rows leave them null).
alter table gitlab_connection
    alter column group_full_path drop not null,
    alter column gitlab_group_id drop not null;

-- Existing (pre-M1b) connections predate last_verified_at; backfill their creation time so reassignment
-- tie-breaks have a value. A still-null last_verified_at is treated as oldest when breaking a tie.
update gitlab_connection set last_verified_at = created_at where last_verified_at is null;

-- Exactly one target is populated, matching the mode.
alter table gitlab_connection
    add constraint ck_gitlab_connection_target check (
        (target_type = 'group'
            and gitlab_group_id is not null and group_full_path is not null
            and gitlab_project_id is null and project_full_path is null)
        or (target_type = 'project'
            and gitlab_project_id is not null and project_full_path is not null
            and gitlab_group_id is null and group_full_path is null)
    );

-- At most one connection per workspace to the same group, and one per workspace to the same project
-- (a workspace may still hold one group connection plus several single-project connections).
create unique index uq_gitlab_connection_group
    on gitlab_connection (workspace_id, gitlab_group_id)
    where target_type = 'group';
create unique index uq_gitlab_connection_project
    on gitlab_connection (workspace_id, gitlab_project_id)
    where target_type = 'project';

-- Why a repository is inactive. Null while active; 'NO_ACCESSIBLE_CONNECTION' when no connection can reach it.
-- reassigned_at marks a provisional reassignment: cleared on the next successful use, or turned into a
-- deactivation if that use returns 401/403/404.
alter table repository
    add column inactive_reason text,
    add column reassigned_at   timestamptz;

-- A repository outlives its connection: when the last connection that could reach it is removed with no
-- replacement, the row is kept (scan history) but detached (connection_id null) and deactivated.
alter table repository
    alter column connection_id drop not null;
