---
name: review-pr
description: Independent review of a milestone branch against spec and checklist (use the strongest model)
argument-hint: M<N>
disable-model-invocation: true
---

Review milestone $ARGUMENTS.

1. You did not write this code; don't trust the report. Read `CLAUDE.md`, the $ARGUMENTS parts of `docs/SPEC.md`,
   `docs/REVIEW_CHECKLIST.md`, `docs/reports/$ARGUMENTS.md`.
2. Read `git diff develop...HEAD` and every file under "Files the reviewer must read in full".
3. Run `./gradlew build`.
4. Output: checklist PASS/FAIL per item, then findings by severity (blocker / should-fix / nit) with file:line and
   the fix. State whether it is ready to merge into `develop`.
5. Don't change code unless asked. Don't merge.
