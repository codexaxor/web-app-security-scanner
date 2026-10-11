-- M1: one GitLab connection per workspace, and the repositories selected from its group.
-- Tokens are stored only as AES-256-GCM ciphertext (never plaintext).

create table gitlab_connection (
    id                        uuid        primary key,
    workspace_id              uuid        not null references workspace (id),
    base_url                  text        not null,
    group_full_path           text        not null,
    gitlab_group_id           bigint      not null,
    token_ciphertext          text        not null,
    token_scopes              text,
    token_expires_at          date,
    webhook_secret_ciphertext text,
    created_at                timestamptz not null default now(),
    -- Single active connection per workspace in M1 (model stays open to more later).
    constraint uq_gitlab_connection_workspace unique (workspace_id)
);

create table repository (
    id                  uuid        primary key,
    workspace_id        uuid        not null references workspace (id),
    connection_id       uuid        not null references gitlab_connection (id),
    gitlab_project_id   bigint      not null,
    path_with_namespace text        not null,
    default_branch      text,
    active              boolean     not null default true,
    sensitivity         text        not null default 'normal'
                          check (sensitivity in ('low', 'normal', 'high')),
    include_dev_deps    boolean     not null default false,
    languages           jsonb       not null default '[]'::jsonb,
    last_scanned_sha    text,
    created_at          timestamptz not null default now(),
    constraint uq_repository_workspace_project unique (workspace_id, gitlab_project_id)
);

create index idx_gitlab_connection_workspace on gitlab_connection (workspace_id);
create index idx_repository_workspace on repository (workspace_id);
create index idx_repository_connection on repository (connection_id);
