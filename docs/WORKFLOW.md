# Development workflow (local mode)

We develop and run everything locally. There are no pull requests and no CI for now; the local
`./gradlew build` is the quality gate and GitHub is only a remote backup. Revisit when we deploy (D-012).

## Branches
| Branch | Purpose | Who writes to it |
| --- | --- | --- |
| `main` | Stable. One merge per finished, reviewed milestone, tagged `m0`, `m1`, … | Human, local merge from `develop` |
| `develop` | Integration. Always builds and passes tests | Human, local merge from feature branches |
| `feature/m<N>-<slug>` | One milestone | Claude Code |
| `fix/<slug>` | Bug fix after a merge | Claude Code |
| `docs/<slug>` | Docs-only change | Anyone |

Claude Code never commits to, merges into, or pushes `main` or `develop`. Merging is a human step after review.

## Flow per milestone
1. `git checkout develop`
2. `/start-milestone M<N>` → creates `feature/m<N>-<slug>`, plans, waits for approval
3. Implement in slices; commit after each green `./gradlew build`
4. `/finish-milestone M<N>` → build, self-review, write `docs/reports/M<N>.md`, commit, push the feature branch
5. Fresh session, strongest model: `/review-pr M<N>`; paste report + review into the planning chat
6. Fix findings on the same branch
7. Human merges locally:
```bash
   ./gradlew build
   git checkout develop
   git merge --no-ff feature/m<N>-<slug> -m "Merge M<N>: <name>"
   ./gradlew build
   git checkout main
   git merge --no-ff develop -m "Release m<N>"
   git tag m<N>
   git push origin main develop --tags
   git branch -d feature/m<N>-<slug>
   git checkout develop
```

## Commits
Conventional Commits `type(scope): summary`. Types: feat, fix, test, refactor, docs, build, chore.
Scopes: core, app, scanner, ui, db, docs.

## Undo a milestone
`git checkout develop && git revert -m 1 <merge-commit>` (same on `main` if released).

## When we start deploying
Re-enable a GitHub Actions build, PRs into `develop`, and branch protection on `main`/`develop`.
