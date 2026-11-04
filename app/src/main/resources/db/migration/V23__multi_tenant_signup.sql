-- M8.10 (D-093, D-094, D-097, D-098): self sign-up with operator approval.
-- Pending sign-ups are NOT app_user rows: they live in signup_request (pre-tenant, D-098). app_user keeps
-- workspace_id NOT NULL. The workspace + app_user are created at APPROVAL, in one transaction.

-- ---------------------------------------------------------------- app_user: status + operator
alter table app_user
    add column status            text        not null default 'active',
    add column is_operator       boolean     not null default false,
    add column status_changed_at timestamptz,
    add column status_changed_by uuid        references app_user (id);

alter table app_user add constraint ck_app_user_status
    check (status in ('active', 'disabled'));
-- The operator can never be disabled (it would lock the platform out).
alter table app_user add constraint ck_app_user_operator_active
    check (not is_operator or status = 'active');
-- At most one operator (D-094: the platform operator). Never a second one silently.
create unique index uq_app_user_single_operator on app_user ((true)) where is_operator;

-- Backfill: existing users are active (column default). Promote the bootstrap admin = earliest real (email)
-- admin in Default — ONLY if no operator exists (approval item 4). Demo users (no '@') never qualify.
update app_user set is_operator = true
 where not exists (select 1 from app_user o where o.is_operator)
   and id = (select u.id from app_user u
              where u.workspace_id = '00000000-0000-0000-0000-000000000001'
                and u.role = 'admin' and u.email like '%@%' and u.status = 'active'
              order by u.created_at, u.id
              limit 1);

-- ---------------------------------------------------------------- signup_request (pre-tenant, D-098)
create table signup_request (
    id                       uuid        primary key,
    email                    text        not null,
    password_hash            text,                       -- bcrypt; cleared once decided
    requested_workspace_name text        not null,
    status                   text        not null default 'pending',
    created_at               timestamptz not null default now(),
    decided_at               timestamptz,
    decided_by               uuid        references app_user (id),
    workspace_id             uuid        references workspace (id),   -- the workspace created on approval
    constraint ck_signup_request_status   check (status in ('pending', 'approved', 'rejected')),
    constraint ck_signup_request_email    check (email = lower(email) and position('@' in email) > 1),
    constraint ck_signup_request_ws_name  check (length(btrim(requested_workspace_name)) between 1 and 80),
    constraint ck_signup_request_decision check (
           (status = 'pending'  and password_hash is not null and decided_at is null
                                and decided_by is null and workspace_id is null)
        or (status = 'rejected' and password_hash is null and decided_at is not null
                                and decided_by is not null and workspace_id is null)
        or (status = 'approved' and password_hash is null and decided_at is not null
                                and decided_by is not null and workspace_id is not null))
);
-- One PENDING request per email; a rejected email may sign up again (approval item 1).
create unique index uq_signup_request_pending_email on signup_request (lower(email)) where status = 'pending';
create index idx_signup_request_status_created on signup_request (status, created_at);

-- ---------------------------------------------------------------- fair use (D-097)
-- Fast "does this workspace already have a running job?" for the claimer. V6 constrains
-- scan_job.state to queued|running|done|failed, so this is exactly the running set.
create index idx_scan_job_workspace_running on scan_job (workspace_id) where state = 'running';
