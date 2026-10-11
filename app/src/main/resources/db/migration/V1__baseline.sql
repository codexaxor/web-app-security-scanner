-- M0 baseline: the two tables every tenant-ready install needs.
-- Every business table carries workspace_id from day one (CLAUDE.md domain rule 1).

create table workspace (
    id         uuid        primary key,
    name       text        not null,
    settings   jsonb       not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table app_user (
    id            uuid        primary key,
    workspace_id  uuid        not null references workspace (id),
    email         text        not null,
    password_hash text        not null,
    role          text        not null check (role in ('admin', 'member', 'viewer')),
    created_at    timestamptz not null default now(),
    constraint uq_app_user_workspace_email unique (workspace_id, email)
);

create index idx_app_user_workspace on app_user (workspace_id);
