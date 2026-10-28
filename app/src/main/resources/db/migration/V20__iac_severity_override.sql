-- M8.5 (D-083 OD-27, D-087): a maintained per-check_id severity override for IaC findings. OSS Checkov checks
-- carry no severity, so every IaC finding scores base MEDIUM (D-054); this lets a specific check_id be scored
-- CRITICAL/HIGH/MEDIUM/LOW instead. SCORING-ONLY: it changes the base at scan time and on re-score (D-049), never
-- a status change and never a triage_event; the check_id is NOT a fingerprint input (it already is the IaC
-- rule_id and is unchanged).
--
-- Global reference/config, so NO workspace_id (D-050 family, alongside kev_entry / epss_score / fix_time_config /
-- eol_cycle). Joined to workspace-scoped sub_issue rows at score time; CLAUDE.md domain-rule-1 exception list is
-- extended for IacSeverityOverrideRepository.findById.
--
-- Ships EMPTY: a row is added only for a check_id confirmed in real Checkov output (never guessed; D-061 pattern),
-- by a later migration. There is no UI to manage these yet (known gap).
create table iac_severity_override (
    check_id   text        primary key,
    severity   text        not null check (severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    note       text,
    created_at timestamptz not null default now()
);
