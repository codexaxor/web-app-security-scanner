# Milestone / change: <title>

<!-- See docs/WORKFLOW.md and docs/REVIEW_CHECKLIST.md. Feature branches target `develop`. -->

## What this does
-

## Milestone report
- Attached: `docs/reports/M<N>.md`  (required for a milestone PR)

## Checklist (see docs/REVIEW_CHECKLIST.md)
- [ ] Scope is this milestone only; deviations noted in the report and DECISIONS.md
- [ ] `workspace_id` on new tables and in every query
- [ ] Status changes only via reconcile; every change writes a `triage_event`
- [ ] No token, secret value or file content in logs/URLs/env/exceptions
- [ ] "Done when" proven by a named test (or documented manual check)
- [ ] No test weakened, skipped or deleted without explanation
- [ ] Migrations apply to an empty DB; merged migrations untouched
- [ ] `./gradlew build` green; `npm run build` green if `ui/` changed
