-- M8.1 (D-076, D-077): branch to scan per repository.
-- repository.scan_branch: the branch to clone; NULL = follow the GitLab default branch.
-- scan.branch: the branch actually cloned, snapshotted at enqueue and never re-read.
-- triage_event.reason_code is already free text (V8), so BRANCH_CHANGED needs no constraint change.

alter table repository add column scan_branch    text;
alter table repository add column branch_missing boolean not null default false;
alter table scan        add column branch         text;

-- Backfill existing scans from the repository's known default branch so historical rows carry a branch.
update scan s
   set branch = r.default_branch
  from repository r
 where s.repository_id = r.id
   and s.branch is null
   and r.default_branch is not null;
