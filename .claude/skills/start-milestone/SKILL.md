---
name: start-milestone
description: Start a milestone: branch, read spec, plan, wait for approval
argument-hint: M<N>
disable-model-invocation: true
---

Start milestone $ARGUMENTS.

1. Read `CLAUDE.md`, `docs/WORKFLOW.md`, `docs/DECISIONS.md`, and the parts of `docs/SPEC.md` relevant to
   $ARGUMENTS (its row in "Milestone backlog" plus the sections it depends on). Read the previous milestone report
   in `docs/reports/` if one exists.
2. Check `git status` is clean. `git checkout develop && git pull`, then create
   `feature/<lowercase milestone id>-<short-slug>` (e.g. `feature/m2-scan-engine`). If the branch already exists,
   check it out and continue instead.
3. If any open decision in `docs/DECISIONS.md` blocks this milestone, stop and ask.
4. Write a plan (do not write code yet):
   - files to add/change, grouped into slices of ≤ ~300 lines each
   - migrations (table + columns)
   - tests, and which test proves each "Done when" criterion
   - fixtures or sample scanner outputs needed
   - questions / anything the spec doesn't cover
5. Stop and wait for my approval of the plan.
