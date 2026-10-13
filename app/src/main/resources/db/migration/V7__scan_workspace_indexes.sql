-- M2 review fix: index workspace_id on the scan-engine child tables.
--
-- Every business table carries workspace_id and every query filters by it (CLAUDE.md domain rule 1), so each
-- of these columns wants an index. They were first added inside V6, but V6 had already been applied to a local
-- dev database, so editing it in place broke Flyway's checksum validation. Migrations are immutable once run
-- on ANY database (CLAUDE.md), so the indexes live here in a new migration instead.
--
-- scan already has idx_scan_workspace from V6; these cover scan_task, scan_job and scan_finding.
-- "if not exists" keeps this idempotent for any database where the indexes were created by the edited V6.

create index if not exists idx_scan_task_workspace on scan_task (workspace_id);
create index if not exists idx_scan_job_workspace on scan_job (workspace_id);
create index if not exists idx_scan_finding_workspace on scan_finding (workspace_id);
