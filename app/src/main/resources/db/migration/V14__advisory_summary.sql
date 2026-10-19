-- M5 (slice 3): persist the OSV advisory summary so the issue-group subtitle can fall back to it when a
-- finding has empty/unknown cwe_ids (D-048: unknown CWEs fall back to the advisory summary). Descriptive only,
-- never a fingerprint input. Additive (V12/V13 are already published and immutable), so it is its own migration.
alter table sub_issue    add column advisory_summary text;
alter table scan_finding add column advisory_summary text;
