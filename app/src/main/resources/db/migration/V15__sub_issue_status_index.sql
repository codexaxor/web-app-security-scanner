-- M5 review (SF5). Additive only. The re-score job and the group rollup both query OPEN sub-issues scoped by
-- workspace (ReScoreService loads findByWorkspaceIdAndStatus; GroupRollupService re-reads a group's rows), and
-- the column-scoped score UPDATE filters on status (B1). This composite index serves those workspace+status
-- lookups. Named explicitly and created additively (never edits an applied migration).
create index if not exists idx_sub_issue_workspace_status on sub_issue (workspace_id, status);
