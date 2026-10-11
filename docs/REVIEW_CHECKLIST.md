# PR review checklist

Use for every milestone PR. A reviewer (human or a strong model in a fresh session) checks the diff, not the
summary.

## Scope
- [ ] Only the milestone's scope from `docs/SPEC.md`; nothing from later milestones
- [ ] Every deviation listed in the report and in `docs/DECISIONS.md`

## Domain rules (CLAUDE.md)
- [ ] `workspace_id` on new tables and in every query
- [ ] Fingerprints match the spec exactly (no line numbers for SAST)
- [ ] Status changes only via reconcile; failed scanner closes nothing
- [ ] Every status change writes `triage_event`
- [ ] Auto-ignores carry a reason code; scores carry `score_breakdown`

## Security
- [ ] No token, secret value or file content in logs, URLs, env vars, exceptions
- [ ] Scanner container flags: non-root, read-only, cap-drop ALL, no-new-privileges, limits, timeout
- [ ] No code from scanned repos executed
- [ ] No real secrets or company code committed; fixtures obviously fake

## Tests
- [ ] "Done when" is a named, passing test (or documented manual check)
- [ ] No test weakened, skipped or deleted without explanation
- [ ] Parser tests use real saved scanner output
- [ ] Testcontainers, not H2

## Database
- [ ] Merged migrations untouched; new ones apply on an empty DB
- [ ] Indexes on foreign keys and on `(repository_id, fingerprint)`

## Hygiene
- [ ] Conventional commits; branch `feature/m<N>-…` → `develop`
- [ ] README / CLAUDE.md commands still accurate
