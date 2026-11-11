-- V22: provider-neutral source connection + repository identity (M8.7, D-080/D-085).
-- Additive: gitlab_connection and repository keep their names and GitLab columns; new provider-neutral columns
-- are added, backfilled, then constrained. Repository identity moves to (workspace_id, provider, host,
-- provider_repo_id). No table/column rename; V1-V21 are untouched. (Approved plan changes items 11-15.)
--
-- Compatibility: to keep every existing GitLab insert path working unchanged (no entity/API churn in this
-- slice), gitlab_connection.host is a GENERATED column derived from base_url, provider/credential_kind carry
-- GitLab defaults, and repository identity columns are backfilled for existing rows and filled for future
-- inserts by a BEFORE INSERT trigger (host from the connection; provider/provider_repo_id default to GitLab).
-- GitHub/public inserts (later slices) set these columns explicitly; the trigger only fills NULLs.

-- ============================ gitlab_connection -> provider-neutral source connection ============================
alter table gitlab_connection
    add column provider         text not null default 'gitlab',
    add column credential_kind  text not null default 'token',
    add column owner_login      text,   -- GitHub user/org login (target_type='owner')
    add column repo_full_name   text,   -- 'owner/name' display for a single repo (target_type='repo')
    add column provider_repo_id text;   -- the provider's repo id for a single-repo target (GitHub numeric id as text)

-- host is always the authority (host[:port]) of base_url, with the scheme's default port (443/80) dropped and
-- any explicit non-default port kept (e.g. localhost:8080, h:8443). Generated, so no code ever sets it. The
-- authority is extracted for any scheme: a test-only file:// connection has an empty authority (host = '').
alter table gitlab_connection
    add column host text generated always as (
        case
            when base_url ~ '^https://' then regexp_replace(substring(base_url from '^[^/]*//([^/]*)'), ':443$', '')
            when base_url ~ '^http://'  then regexp_replace(substring(base_url from '^[^/]*//([^/]*)'), ':80$', '')
            else coalesce(substring(base_url from '^[^/]*//([^/]*)'), '')
        end
    ) stored;

alter table gitlab_connection
    add constraint ck_gitlab_connection_provider check (provider in ('gitlab', 'github')),
    add constraint ck_gitlab_connection_credential_kind check (credential_kind in ('none', 'token'));

-- A token exists iff the connection is token-credentialed (public repos carry no token).
alter table gitlab_connection alter column token_ciphertext drop not null;
alter table gitlab_connection
    add constraint ck_gitlab_connection_token check (
        (credential_kind = 'token' and token_ciphertext is not null)
     or (credential_kind = 'none'  and token_ciphertext is null)
    );

-- credential_kind 'none' only ever pairs with a by-URL single repo (public).
alter table gitlab_connection
    add constraint ck_gitlab_connection_public_is_repo check (
        credential_kind = 'token' or target_type = 'repo'
    );

-- Relax the target check to cover GitLab group/project, GitHub owner/repo, and public (by-URL) repositories.
-- V5 added target_type with an inline enum check (auto-named gitlab_connection_target_type_check) limited to
-- group/project; drop it and widen the allowed set.
alter table gitlab_connection drop constraint gitlab_connection_target_type_check;
alter table gitlab_connection
    add constraint ck_gitlab_connection_target_type check (target_type in ('group', 'project', 'owner', 'repo'));
alter table gitlab_connection drop constraint ck_gitlab_connection_target;
alter table gitlab_connection
    add constraint ck_gitlab_connection_target check (
        (target_type = 'group'
            and gitlab_group_id is not null and group_full_path is not null
            and gitlab_project_id is null and project_full_path is null)
        or (target_type = 'project'
            and gitlab_project_id is not null and project_full_path is not null
            and gitlab_group_id is null and group_full_path is null)
        or (target_type = 'owner'
            and owner_login is not null
            and gitlab_group_id is null and gitlab_project_id is null
            and group_full_path is null and project_full_path is null)
        or (target_type = 'repo'
            and provider_repo_id is not null and repo_full_name is not null
            and gitlab_group_id is null and gitlab_project_id is null
            and group_full_path is null and project_full_path is null)
    );

-- Uniqueness now includes provider + host (approval 11): the same numeric id on two hosts is two connections.
drop index uq_gitlab_connection_group;
drop index uq_gitlab_connection_project;
create unique index uq_gitlab_connection_group
    on gitlab_connection (workspace_id, provider, host, gitlab_group_id)   where target_type = 'group';
create unique index uq_gitlab_connection_project
    on gitlab_connection (workspace_id, provider, host, gitlab_project_id) where target_type = 'project';
create unique index uq_gitlab_connection_owner
    on gitlab_connection (workspace_id, provider, host, owner_login)       where target_type = 'owner';
create unique index uq_gitlab_connection_repo
    on gitlab_connection (workspace_id, provider, host, provider_repo_id)  where target_type = 'repo';

create index idx_gitlab_connection_provider_host on gitlab_connection (provider, host);

-- ============================ repository identity -> provider + host + provider_repo_id ============================
alter table repository
    add column provider         text,
    add column host             text,
    add column provider_repo_id text;

-- Backfill existing rows: all are GitLab; provider_repo_id is the GitLab project id as text.
update repository
   set provider = 'gitlab',
       provider_repo_id = gitlab_project_id::text
 where provider is null;

-- host: own connection -> single workspace host -> single global host (D-085: one host per workspace pre-M8.7).
update repository r set host = c.host
  from gitlab_connection c
 where c.id = r.connection_id and r.host is null;

update repository r set host = (
        select min(c.host) from gitlab_connection c
         where c.workspace_id = r.workspace_id
         group by c.workspace_id
        having count(distinct c.host) = 1)
 where r.host is null;

update repository r set host = (
        select min(c.host) from gitlab_connection c
        having count(distinct c.host) = 1)
 where r.host is null;

-- HARD STOP: never guess a host. Fail loudly (with the count) if any row is still unresolved.
do $$
declare n bigint;
begin
    select count(*) into n from repository where host is null;
    if n > 0 then
        raise exception 'V22 host backfill: % repository row(s) have no derivable host (no connection, and no '
            'single host in the workspace or globally). Resolve manually before migrating.', n;
    end if;
end $$;

-- Future inserts: fill identity columns when the caller left them NULL (every current GitLab insert path).
-- host mirrors the connection's host; provider/provider_repo_id default to GitLab identity. Explicit values
-- (GitHub/public inserts in later slices) are left untouched.
create or replace function repository_fill_identity() returns trigger as $$
begin
    if new.provider is null then
        new.provider := 'gitlab';
    end if;
    -- Derive provider_repo_id from the GitLab project id ONLY when that id is present (SF-2). A non-GitLab
    -- insert that supplies no provider_repo_id (and has no gitlab_project_id to fall back to) is NOT given a
    -- fabricated identity here — it leaves provider_repo_id NULL and is rejected by the NOT NULL constraint
    -- below, rather than silently stored with a bogus id. GitHub/public inserts always set it explicitly.
    if new.provider_repo_id is null and new.gitlab_project_id is not null then
        new.provider_repo_id := new.gitlab_project_id::text;
    end if;
    if new.host is null and new.connection_id is not null then
        new.host := (select c.host from gitlab_connection c where c.id = new.connection_id);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger repository_fill_identity_trg
    before insert on repository
    for each row execute function repository_fill_identity();

alter table repository
    alter column provider set not null,
    alter column host set not null,
    alter column provider_repo_id set not null,
    alter column gitlab_project_id drop not null;   -- null for GitHub rows; kept for GitLab display/back-compat

alter table repository
    add constraint ck_repository_provider check (provider in ('gitlab', 'github'));

-- Identity moves off the bare GitLab project id (D-080). gitlab_project_id stays for GitLab display only.
alter table repository drop constraint uq_repository_workspace_project;
create unique index uq_repository_identity
    on repository (workspace_id, provider, host, provider_repo_id);
