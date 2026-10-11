---
name: start-milestone
description: Start a milestone: branch, read spec, plan, wait for approval
argument-hint: M<N>
disable-model-invocation: true
---

Start milestone $ARGUMENTS.

1. Read `CLAUDE.md`, `docs/WORKFLOW.md`, `docs/DECISIONS.md`, the $ARGUMENTS parts of `docs/SPEC.md`, and the
   previous milestone report in `docs/reports/` if any.
2. Check `git status` is clean. `git checkout develop`, create `feature/<lowercase id>-<short-slug>`
   (or check it out if it exists).
3. If an open decision blocks this milestone, stop and ask.
4. Write a plan, no code: files grouped into slices of ≤ ~300 lines; migrations; tests and which test proves each
   "Done when"; fixtures or sample scanner outputs needed; questions.
5. Stop and wait for approval.
