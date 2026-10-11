Finish milestone $ARGUMENTS.

0. We work locally: no pull requests, no CI.
1. Run `./gradlew build` (and `cd ui && npm run build` if `ui/` changed). Fix failures; if you can't, stop and
   report. Never weaken or skip tests.
2. Check each "Done when" for $ARGUMENTS against a named test or a manual step you actually ran.
3. Self-review `git diff develop...HEAD` against `docs/REVIEW_CHECKLIST.md`; fix what you find.
4. Write `docs/reports/$ARGUMENTS.md` from the template: factual, migrations in full, files the reviewer must read.
5. Update README / CLAUDE.md / SPEC if commands or behavior changed; add decisions to `docs/DECISIONS.md`.
6. Commit `docs: add $ARGUMENTS milestone report`; `git push -u origin HEAD` as backup.
7. Print report path and branch name. Do NOT merge into `develop` or `main`. Do not start the next milestone.
