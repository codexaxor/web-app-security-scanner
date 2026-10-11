---
description: Independent review of a milestone branch against spec and checklist (use the strongest model)
argument-hint: M<N>
---

Review milestone $ARGUMENTS as an independent reviewer. You did not write this code; do not trust the report.

1. Read `CLAUDE.md`, the $ARGUMENTS parts of `docs/SPEC.md`, `docs/REVIEW_CHECKLIST.md`, and
   `docs/reports/$ARGUMENTS.md`.
2. Read the full diff `git diff develop...HEAD`. Read every file listed under "Files the reviewer must read in full".
3. Run `./gradlew build`.
4. Output: checklist with PASS/FAIL per item, then a list of findings ordered by severity
   (blocker / should-fix / nit), each with file:line and the fix. Say clearly whether it is ready to merge.
5. Do not change code unless I ask.
