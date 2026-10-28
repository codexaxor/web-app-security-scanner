-- E2E seed (NOT a Flyway migration). Applied after the api boots (Flyway V1..V18 done) by run-e2e.mjs via psql
-- with ON_ERROR_STOP=1. Idempotent against a fresh e2e volume.
--
-- Workspace 1 = the seeded default tenant (Workspace.DEFAULT_ID) the UI shows. Workspace 2 carries a COMPLETE
-- dataset (change 7) whose values would visibly change every count if it leaked — it must NEVER appear in the UI
-- or any count. The UI queries hardcode DEFAULT_ID, so workspace 2 is the isolation oracle.
--
-- XSS probes are planted in title/subtitle/file_path/advisory_summary and an ignore reason to prove the UI
-- renders scanned data as text only (OD-13d).
begin;

-- ---------- intel (global reference tables, no workspace) ----------
insert into kev_entry (cve, vuln_name, date_added) values
  ('CVE-2022-42889', 'Apache Commons Text RCE', date '2023-01-01')
  on conflict (cve) do nothing;
insert into epss_score (cve, epss, percentile) values
  ('CVE-2022-42889', 0.94412, 0.99),
  ('CVE-2021-44228', 0.97500, 0.99),
  -- The low-EPSS fixture's CVE MUST be in this intel table: the re-score (scheduled, and on every EPSS toggle)
  -- re-enriches sub_issue.epss from here, so a CVE missing here would null the value and break the toggle.
  ('CVE-2024-00001', 0.00500, 0.10)
  on conflict (cve) do nothing;

-- ========================================================================================================
-- WORKSPACE 1 — visible
-- ========================================================================================================
-- The default workspace row already exists (Flyway V2). One connection (unique per workspace), four repos.
insert into gitlab_connection (id, workspace_id, base_url, group_full_path, gitlab_group_id, token_ciphertext,
                               token_scopes, token_expires_at)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
        'https://gitlab.example.com', 'acme', 42, 'fake-ciphertext', 'read_api,read_repository',
        now() + interval '10 days'); -- expiring soon (< 14d) → Connect screen shows amber "10 days left" (S10d)

insert into repository (id, workspace_id, connection_id, gitlab_project_id, path_with_namespace, default_branch,
                        active, sensitivity, include_dev_deps, languages, inactive_reason)
values
  ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 101, 'acme/core-keycloak', 'main', true, 'high', false,
   '["Java"]'::jsonb, null),
  ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 102, 'acme/ib-api-v2', 'main', true, 'normal', false,
   '["Java","TypeScript"]'::jsonb, null),
  ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 103, 'acme/web-portal', 'main', true, 'low', true,
   '["TypeScript"]'::jsonb, null),
  ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 104, 'acme/legacy-batch', 'main', false, 'normal', false,
   '["Java"]'::jsonb, 'DEACTIVATED_BY_USER');

-- Scans: completed (with warnings), partial, failed — on the first three repos.
insert into scan (id, workspace_id, repository_id, trigger, commit_sha, status, started_at, finished_at)
values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000001', 'manual', 'abc123', 'completed', now() - interval '2 hours',
   now() - interval '115 minutes'),
  ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000002', 'schedule', 'def456', 'partial', now() - interval '3 hours',
   now() - interval '175 minutes'),
  ('30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000003', 'commit', 'ghi789', 'failed', now() - interval '4 hours',
   now() - interval '235 minutes');

insert into scan_warning (workspace_id, repository_id, scan_id, code, message, file_path) values
  ('00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
   '30000000-0000-0000-0000-000000000001', 'MISSING_GRADLE_LOCKFILE', 'Missing Gradle lockfile', 'build.gradle'),
  ('00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
   '30000000-0000-0000-0000-000000000002', 'IAC_HELM_NOT_RENDERED', 'Helm chart not rendered', 'Chart.yaml');

-- 64 bulk OPEN groups in core-keycloak (forces server-side pagination > the default 50), round-robin over type
-- and band, plus one open sub-issue each so sub-issue band/type counts populate.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, subtitle, status,
                         severity_score, fix_time_minutes, first_seen_at, created_at)
select gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
       (array['sca','sast','secret','iac','eol'])[1 + (g % 5)],
       'bulk|' || g,
       'Bulk finding ' || g,
       'auto subtitle ' || g,
       'open',
       (array[95,75,50,20])[1 + (g % 4)],
       15,
       now() - (g || ' hours')::interval,
       now() - (g || ' hours')::interval
from generate_series(1, 64) g;

insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       status, score, created_at)
select gen_random_uuid(), ig.workspace_id, ig.id, ig.repository_id, ig.type, ig.type,
       'fp-' || ig.group_key,
       -- SAST/IaC findings always carry a rule id in real scans (it is part of the fingerprint); set one so the
       -- triage resolver's DUPLICATE_RULE check never sees a null rule id.
       case when ig.type in ('sast', 'iac') then 'bulk-rule-' || ig.group_key else null end,
       case when ig.type in ('sast', 'iac', 'secret') then 'src/Bulk' || ig.group_key || '.java' else null end,
       'open', ig.severity_score, ig.first_seen_at
from issue_group ig
where ig.workspace_id = '00000000-0000-0000-0000-000000000001' and ig.group_key like 'bulk|%';

-- M8.6 item 9: give some OPEN bulk groups (active repo → focus scope) a level-2 category so the Feed category
-- filter has focus-scope data to narrow. Value-only (no new rows, no status/count change), so every existing
-- count oracle — including core-keycloak's "64 groups" — is unaffected. Two categories keyed off the
-- round-robin type: sast bulk → "Injection" (13 groups), sca bulk → "Vulnerable dependency" (12). The other
-- open groups keep a null level2_category, so a category filter returns exactly its own subset.
update issue_group set level2_category = 'Injection'
 where workspace_id = '00000000-0000-0000-0000-000000000001' and group_key like 'bulk|%' and type = 'sast';
update issue_group set level2_category = 'Vulnerable dependency'
 where workspace_id = '00000000-0000-0000-0000-000000000001' and group_key like 'bulk|%' and type = 'sca';

-- A KEV + EPSS SCA finding (ib-api-v2): band Critical, attribution shown, KEV badge.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, subtitle, status,
                         severity_score, fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sca', 'sca|commons-text',
        'org.apache.commons:commons-text', 'Code injection can lead to arbitrary code execution', 'open', 100,
        60, now() - interval '1 day');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, package,
                       installed_version, fixed_version, file_path, tool_severity, status, score, cvss,
                       severity_label, cwe, epss, in_kev, created_at)
values ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'sca', 'sca',
        'fp-commons-text', 'CVE-2022-42889', 'org.apache.commons:commons-text', '1.9', '1.10.0', 'pom.xml',
        '9.8', 'open', 100, 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H', 'CRITICAL', '["CWE-94"]'::jsonb,
        0.94412, true, now() - interval '1 day');

-- A secret finding with a 4-char preview (never the value).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'secret', 'secret|aws-access-token', 'Exposed aws-access-token',
        'open', 90, 10, now() - interval '2 days');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       secret_hash, secret_preview, key_id, status, score, created_at)
values ('50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'secret', 'secret',
        'fp-aws', 'aws-access-token', 'config/app.properties', 'DEADBEEFkeyedHMAChash', 'AKIA', 'kid1', 'open',
        90, now() - interval '2 days');

-- M8.6 U8: a linkable secret finding on core-keycloak — a 40-hex commit + file path + line so the
-- source-host deep links (D-084) render and their hrefs can be asserted in e2e. Connection host is
-- gitlab.example.com; the file link must be /-/blob/<sha>/config/app.properties#L7 and the commit /-/commit/<sha>.
-- created_at is set OLDER than core-keycloak's existing scan (30000…0001) so this one does NOT become the
-- repo's "last scan" (which would hide that scan's warnings); the source link uses the SUB's last-seen scan.
insert into scan (id, workspace_id, repository_id, trigger, commit_sha, branch, status, started_at, finished_at,
                  created_at)
values ('30000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', 'manual',
        '1111111111111111111111111111111111111111', 'main', 'completed', now() - interval '5 hours',
        now() - interval '295 minutes', now() - interval '5 hours');
-- status 'solved' so it is counted in NO feed/focus/all/dashboard oracle (keeping core-keycloak's 64 bulk open
-- groups); the panel still opens it by id and renders the source links for the link-href assertions.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at, level2_category)
values ('40000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', 'secret', 'secret|linktest', 'Linkable secret', 'solved', 80,
        30, now() - interval '30 minutes', 'Cloud credential');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       line, secret_hash, secret_preview, key_id, status, score, solved_at,
                       first_seen_scan_id, last_seen_scan_id, created_at)
values ('50000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-0000000000aa', '20000000-0000-0000-0000-000000000001', 'secret', 'secret',
        'fp-linktest', 'aws-access-token', 'config/app.properties', 7, 'DEADBEEFhash', 'AKIA', 'kid-aa', 'solved',
        80, now() - interval '29 minutes',
        '30000000-0000-0000-0000-0000000000aa', '30000000-0000-0000-0000-0000000000aa', now());

-- XSS probes in title/subtitle/file_path/advisory_summary and (below) an ignore reason.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, subtitle, status,
                         severity_score, fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sast', 'sast|xss-probe',
        '<img src=x onerror=alert(1)>', 'javascript:alert(1)', 'open', 75, 30, now() - interval '3 hours');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       advisory_summary, status, score, created_at)
values ('50000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002', 'sast', 'sast',
        'fp-xss', 'xss-rule', '<img src=x onerror=alert(1)>.java', '"><script>alert(1)</script>', 'open', 75,
        now() - interval '3 hours');

-- Ignore rules: a manual rule, a GLOBAL rule (repository_id null), and a .secscan.yml rule (created_by).
insert into ignore_rule (id, workspace_id, scope, repository_id, match_value, reason, created_by) values
  ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'cve',
   '20000000-0000-0000-0000-000000000002', 'CVE-2021-45046', 'Not exploitable in our config', 'user'),
  ('60000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'rule',
   null, 'generic-password', 'Accepted org-wide <img src=x onerror=alert(1)>', 'user'),
  ('60000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'cve',
   '20000000-0000-0000-0000-000000000002', 'CVE-2025-22869', 'Only used in an offline build tool', 'secscan.yml'),
  -- A manual rule dedicated to the Ignored-view delete-restores test (kept independent of the panel's rule).
  ('60000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', 'cve',
   '20000000-0000-0000-0000-000000000002', 'CVE-2099-00001', 'Dedicated for the delete-restores test', 'user');

-- A finding held ignored by the dedicated manual rule above; deleting that rule restores it (Ignored view, R4).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sca', 'sca|deltest', 'Delete-restores fixture', 'ignored', null,
        null, now() - interval '6 days');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, status,
                       ignore_reason, ignored_by_rule_id, ignored_at, created_at)
values ('50000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000002', 'sca', 'sca',
        'fp-deltest', 'CVE-2099-00001', 'ignored', 'IGNORE_RULE',
        '60000000-0000-0000-0000-000000000005', now() - interval '6 days', now() - interval '6 days');

-- One IGNORED group per reason code (null score → shown "Ignored", no band), each with an ignored sub-issue
-- carrying that reason code; one of them is held by the manual rule above.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, first_seen_at)
select gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
       'sca', 'ign|' || code, 'Ignored (' || code || ')', 'ignored', now() - interval '5 days'
from (values ('DEV_DEPENDENCY'), ('TEST_SCOPE'), ('EXCLUDED_PATH'), ('SECRET_PLACEHOLDER'), ('LOW_EPSS'),
             ('IGNORE_RULE'), ('DUPLICATE_RULE')) as codes(code);

insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, status,
                       ignore_reason, ignored_by_rule_id, ignored_at, created_at)
select gen_random_uuid(), ig.workspace_id, ig.id, ig.repository_id, 'sca', 'sca',
       'fp-' || ig.group_key, 'ignored',
       substring(ig.group_key from 5),
       case when substring(ig.group_key from 5) = 'IGNORE_RULE'
            then '60000000-0000-0000-0000-000000000001'::uuid else null end,
       now() - interval '5 days', now() - interval '5 days'
from issue_group ig
where ig.workspace_id = '00000000-0000-0000-0000-000000000001' and ig.group_key like 'ign|%';

-- A low-EPSS open finding (EPSS 0.5%, not on KEV) in an active repo. With the EPSS toggle OFF it is a normal
-- open finding; turning the toggle ON auto-ignores it as LOW_EPSS (Settings → Feed/Ignored counts move, R5).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000003', 'sca', 'sca|low-epss', 'Low-EPSS dependency', 'open', 50, 15,
        now() - interval '2 hours');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, package,
                       status, score, epss, in_kev, created_at)
values ('50000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000003', 'sca', 'sca',
        'fp-low-epss', 'CVE-2024-00001', 'com.example:lib', 'open', 50, 0.005, false, now() - interval '2 hours');

-- An ignored finding held by the .secscan.yml rule (created_by='secscan.yml') → the panel shows the
-- "edit .secscan.yml" message and offers NO unignore button.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, first_seen_at)
values ('40000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sca', 'sca|secscanyml-held', 'Ignored via .secscan.yml',
        'ignored', now() - interval '4 days');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, status,
                       ignore_reason, ignored_by_rule_id, ignored_at, created_at)
values ('50000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000002', 'sca', 'sca',
        'fp-secscanyml', 'CVE-2025-22869', 'ignored', 'IGNORE_RULE',
        '60000000-0000-0000-0000-000000000003', now() - interval '4 days', now() - interval '4 days');

-- triage_events inside and outside the 7-day window (New uses sub.created_at; Solved/Auto-ignored use at).
-- Solved within 7d (the finding is backdated out of the New window via its own created_at above/below).
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, status, score,
                       created_at)
values ('50000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'sca', 'sca',
        'fp-solved-recent', 'solved', 70, now() - interval '10 days');
insert into triage_event (id, workspace_id, sub_issue_id, from_status, to_status, actor, reason_code, at) values
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000009',
   'open', 'solved', 'system', null, now() - interval '2 days'),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000009',
   'open', 'solved', 'system', null, now() - interval '20 days'),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000002',
   'open', 'ignored', 'system', 'LOW_EPSS', now() - interval '1 day');

-- An OPEN finding in the INACTIVE repo (excluded from totals by default; shown on the inactive line).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000004', 'sca', 'sca|inactive', 'Inactive repo finding', 'open', 88, 60,
        now() - interval '30 days');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, status, score,
                       created_at)
values (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000009',
        '20000000-0000-0000-0000-000000000004', 'sca', 'sca', 'fp-inactive', 'open', 88, now() - interval '30 days');

-- M8 S10a: a dedicated ACTIVE repo with scans at precise ages so the relative-time formatter can be asserted
-- in the real UI (8h40m → "9 hours ago", 3d10h → "3 days ago"). Scans are completed with no warnings (scan
-- health untouched); the one finding is ignored (not counted in open bands / type breakdown / focus feed).
insert into repository (id, workspace_id, connection_id, gitlab_project_id, path_with_namespace, default_branch,
                        active, sensitivity, include_dev_deps, languages, inactive_reason)
values ('20000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001', 110, 'acme/time-fixture', 'main', true, 'normal', false,
        '["Java"]'::jsonb, null);
insert into scan (id, workspace_id, repository_id, trigger, commit_sha, status, started_at, finished_at) values
  ('30000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000010', 'manual', 'tfa', 'completed',
   now() - interval '8 hours 45 minutes', now() - interval '8 hours 40 minutes'),
  ('30000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000010', 'schedule', 'tfb', 'completed',
   now() - interval '3 days 10 hours 6 minutes', now() - interval '3 days 10 hours');
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, first_seen_at)
values ('40000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000010', 'sca', 'sca|time-fixture', 'Time-fixture ignored', 'ignored',
        now() - interval '3 days 10 hours');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, status,
                       ignore_reason, ignored_at, created_at)
values ('50000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000010', 'sca', 'sca',
        'fp-time-fixture', 'CVE-2024-99999', 'ignored', 'DEV_DEPENDENCY',
        now() - interval '3 days 10 hours', now() - interval '3 days 10 hours');

-- M8 S10e: a group with a MIX of open and ignored sub-issues (2 open, 1 ignored) in an active repo, so the
-- Feed shows openCount in Focus ("2") and "2 open · 1 ignored" in All (finding-count semantics regression).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'secret', 'secret|mixed', 'Mixed open and ignored', 'open', 80,
        30, now() - interval '1 hour');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       status, score, ignore_reason, ignored_at, created_at) values
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000011',
   '20000000-0000-0000-0000-000000000002', 'secret', 'secret', 'fp-mix-o1', 'generic-api-key', 'src/A.java',
   'open', 80, null, null, now() - interval '1 hour'),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000011',
   '20000000-0000-0000-0000-000000000002', 'secret', 'secret', 'fp-mix-o2', 'generic-api-key', 'src/B.java',
   'open', 80, null, null, now() - interval '1 hour'),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000011',
   '20000000-0000-0000-0000-000000000002', 'secret', 'secret', 'fp-mix-ig', 'generic-api-key', 'src/C.java',
   'ignored', 80, 'DEV_DEPENDENCY', now() - interval '1 hour', now() - interval '1 hour');

-- ---------- M8 review-fixes fixtures (each spec owns its own group; no coupling to shared groups) ----------

-- SF-7: an SCA group with TWO different fixed versions across its OPEN sub-issues → the panel's fix line reads
-- "Several fixed versions — see each location".
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, subtitle, status,
                         severity_score, fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sca', 'sca|multi-fix', 'org.example:multi-fix',
        'Dependency with several fixes', 'open', 72, 60, now() - interval '2 hours');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, package,
                       installed_version, fixed_version, file_path, status, score, created_at) values
  ('50000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000002', 'sca', 'sca', 'fp-mf1',
   'CVE-2023-00001', 'org.example:multi-fix', '1.0.0', '1.2.3', 'pom.xml', 'open', 72, now() - interval '2 hours'),
  ('50000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000002', 'sca', 'sca', 'fp-mf2',
   'CVE-2023-00002', 'org.example:multi-fix', '1.0.0', '4.5.6', 'pom.xml', 'open', 60, now() - interval '2 hours');

-- N-10: a finding held ignored by the GLOBAL rule (...002, repository_id null, created_by user) → the panel's
-- unignore asks for confirmation before removing an all-repositories rule.
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, first_seen_at)
values ('40000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'secret', 'secret|global-held', 'Globally ignored secret',
        'ignored', now() - interval '5 days');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, rule_id, file_path,
                       status, ignore_reason, ignored_by_rule_id, ignored_at, created_at)
values ('50000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000002', 'secret', 'secret',
        'fp-global-held', 'generic-password', 'src/G.java', 'ignored', 'IGNORE_RULE',
        '60000000-0000-0000-0000-000000000002', now() - interval '5 days', now() - interval '5 days');

-- SF-1: a dedicated OPEN group with a single open sub-issue. "Ignore group…" in the panel leaves it all-ignored;
-- the header severity must switch to the neutral "Ignored" pill (no band) WITHOUT a reload (the query the panel
-- header reads, ["issue-group", id], is now invalidated by the triage mutation).
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', 'sca', 'sca|allignore', 'All-ignore fixture', 'open', 80, 30,
        now() - interval '2 hours');
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, package,
                       installed_version, fixed_version, status, score, created_at)
values ('50000000-0000-0000-0000-000000000015', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000014', '20000000-0000-0000-0000-000000000002', 'sca', 'sca',
        'fp-allignore', 'CVE-2023-00003', 'org.example:allignore', '1.0.0', '2.0.0', 'open', 80,
        now() - interval '2 hours');

-- SF-1 (M8.7 review): a GitHub source in the SAME workspace — a GitHub connection + repository + one OPEN High
-- finding — so the Repositories list shows a GitHub provider badge, the provider filter narrows list and feed,
-- and the GitHub repo is scannable. Severity 85 (High) so NO Critical-band oracle changes; feed/dashboard
-- oracles compare UI == API == SQL, so the +1 active repo / +1 open group is consistent everywhere.
insert into gitlab_connection (id, workspace_id, base_url, target_type, provider, credential_kind,
                               token_ciphertext, provider_repo_id, repo_full_name, token_scopes, token_expires_at)
values ('10000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        'https://github.com', 'repo', 'github', 'token', 'fake-ciphertext-gh', '900001', 'octo/widget',
        'metadata,contents', now() + interval '60 days');

insert into repository (id, workspace_id, connection_id, provider, host, provider_repo_id, gitlab_project_id,
                        path_with_namespace, default_branch, active, sensitivity, include_dev_deps, languages,
                        inactive_reason)
values ('20000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-0000000000a1', 'github', 'github.com', '900001', null, 'octo/widget', 'main',
        true, 'normal', false, '["TypeScript"]'::jsonb, null);

insert into scan (id, workspace_id, repository_id, trigger, commit_sha, branch, status, started_at, finished_at)
values ('30000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-0000000000a1', 'manual', 'ghsha01', 'main', 'completed',
        now() - interval '1 hour', now() - interval '58 minutes');

insert into issue_group (id, workspace_id, repository_id, type, group_key, title, subtitle, status,
                         severity_score, fix_time_minutes, first_seen_at)
values ('40000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-0000000000a1', 'sca', 'sca|gh-widget', 'GitHub widget dependency',
        'A vulnerable transitive dependency', 'open', 85, 60, now() - interval '1 hour');

insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, vuln_id, package,
                       installed_version, fixed_version, file_path, status, score, created_at)
values ('50000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-0000000000a1', '20000000-0000-0000-0000-0000000000a1', 'sca', 'sca',
        'fp-gh-widget', 'CVE-2024-30000', 'octo:widget-lib', '1.0.0', '1.0.1', 'package-lock.json', 'open', 85,
        now() - interval '1 hour');

-- ========================================================================================================
-- WORKSPACE 2 — the isolation oracle (must NEVER appear in the UI or any count)
-- ========================================================================================================
insert into workspace (id, name) values ('00000000-0000-0000-0000-000000000002', 'other-tenant');
insert into gitlab_connection (id, workspace_id, base_url, group_full_path, gitlab_group_id, token_ciphertext)
values ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
        'https://gitlab.other.com', 'other', 99, 'fake-ciphertext-2');
insert into repository (id, workspace_id, connection_id, gitlab_project_id, path_with_namespace, default_branch,
                        active, languages)
values ('20000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000002', 901, 'other/secret-leak', 'main', true, '["Java"]'::jsonb);
insert into scan (id, workspace_id, repository_id, trigger, commit_sha, status, finished_at) values
  ('30000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000002',
   '20000000-0000-0000-0000-000000000009', 'manual', 'zzz999', 'completed', now());
insert into scan_warning (workspace_id, repository_id, scan_id, code, message) values
  ('00000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000009',
   '30000000-0000-0000-0000-000000000009', 'OTHER_WS_WARNING', 'should never be shown');
insert into issue_group (id, workspace_id, repository_id, type, group_key, title, status, severity_score,
                         first_seen_at)
values ('40000000-0000-0000-0000-000000000099', '00000000-0000-0000-0000-000000000002',
        '20000000-0000-0000-0000-000000000009', 'sca', 'sca|other', 'OTHER WORKSPACE — do not show', 'open', 100,
        now());
insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint, status, score,
                       created_at)
values ('50000000-0000-0000-0000-000000000099', '00000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000099', '20000000-0000-0000-0000-000000000009', 'sca', 'sca',
        'fp-other', 'open', 100, now());
insert into ignore_rule (id, workspace_id, scope, repository_id, match_value, reason, created_by) values
  ('60000000-0000-0000-0000-000000000099', '00000000-0000-0000-0000-000000000002', 'cve',
   null, 'CVE-0000-0000', 'other workspace rule', 'user');
insert into triage_event (id, workspace_id, sub_issue_id, from_status, to_status, actor, at) values
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000099',
   'open', 'ignored', 'system', now());

-- M8.1 (D-076): give seeded scans a branch (so the Dashboard Branch column is populated) and put web-portal on a
-- non-default scan branch (so a repo row shows "· dev"). Additive updates only — no row counts change.
update scan set branch = 'main' where branch is null;
update repository set scan_branch = 'dev' where path_with_namespace = 'acme/web-portal';
update scan set branch = 'dev'
 where repository_id = (select id from repository where path_with_namespace = 'acme/web-portal');

-- M8.5 (D-083): "New · 7d" now counts only findings first seen on the repository's effective branch, so every
-- seeded sub-issue needs a first_seen_scan_id pointing at a scan of its repository on that branch (real
-- findings always have one). Additive — no row counts change; it only populates the previously-null column.
update sub_issue s set first_seen_scan_id = (
    select sc.id from scan sc
    join repository r on r.id = s.repository_id
    where sc.repository_id = s.repository_id
      and sc.branch = coalesce(r.scan_branch, r.default_branch)
    order by sc.finished_at desc nulls last
    limit 1)
 where first_seen_scan_id is null;

commit;
