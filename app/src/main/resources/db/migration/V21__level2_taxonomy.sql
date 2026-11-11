-- M8.5 (D-083, D-074): a level-2 category per issue group, derived from stored data by
-- core.taxonomy.Level2Taxonomy, plus an optional OWASP Top 10:2025 category NAME (names only, CC BY-SA). Both
-- are DESCRIPTIVE (never a fingerprint input, never a status). Additive + nullable: reconcile sets them for
-- new/updated groups, and an idempotent api-startup backfill (TaxonomyBackfillRunner) fills existing rows, after
-- which the feed can filter by level2_category.
alter table issue_group add column level2_category text;
alter table issue_group add column owasp_category  text;

-- Feed filter support (the filter is workspace-scoped in the query; this index covers it).
create index idx_issue_group_level2 on issue_group (workspace_id, level2_category);
