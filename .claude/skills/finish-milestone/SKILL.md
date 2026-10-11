---
name: finish-milestone
description: Verify, write the milestone report, push and open the PR
argument-hint: M<N>
disable-model-invocation: true
---

Finish milestone $ARGUMENTS.

1. Run `./gradlew build` (and `cd ui && npm run build` if `ui/` changed). If anything fails, fix it and stop here
   if you cannot. Never weaken or skip tests.
2. Check each "Done when" criterion for $ARGUMENTS in `docs/SPEC.md` against a named test or a manual step you
   actually ran.
3. Self-review the full diff `git diff develop...HEAD` against `docs/REVIEW_CHECKLIST.md`. Fix problems you find.
4. Write `docs/reports/$ARGUMENTS.md` from `docs/MILESTONE_REPORT_TEMPLATE.md`. Be factual: report failures and
   gaps, paste migrations in full, list the files the reviewer must read.
5. Update README.md / CLAUDE.md / docs/SPEC.md if commands or behavior changed; add any decisions to
   `docs/DECISIONS.md`.
6. Commit (`docs: add $ARGUMENTS milestone report`), push the branch, and open a PR into `develop` using
   `.github/pull_request_template.md` (use `gh pr create --base develop` if available; otherwise print the PR
   title and body for me).
7. Print the report path and PR link. Do not merge. Do not start the next milestone.
