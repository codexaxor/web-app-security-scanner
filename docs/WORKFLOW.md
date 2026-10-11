# Development workflow

## Branches

| Branch | Purpose | Who writes to it |
| --- | --- | --- |
| `main` | Released, stable. One merge per finished milestone, tagged `m0`, `m1`, … | Only via PR from `develop`, after review |
| `develop` | Integration branch. Always builds and passes tests | Only via PR from feature branches |
| `feature/m<N>-<slug>` | One milestone, e.g. `feature/m0-skeleton`, `feature/m2-scan-engine` | Claude Code |
| `fix/<slug>` | Bug fix found after a merge | Claude Code |
| `docs/<slug>` | Docs-only change | Anyone |

```
main     ──●────────────────●──────────── (tags m0, m1, …)
            \              /
develop  ────●──●───●─────●───●──────────
              \    / \       /
feature/m0     ●──●   \     /
feature/m1             ●──●
```

## Flow per milestone

1. `git checkout develop && git pull`
2. `git checkout -b feature/m<N>-<slug>`
3. Plan → approve → implement in slices; commit after each green build.
4. `git push -u origin feature/m<N>-<slug>`
5. Open PR → `develop` using the PR template; attach `docs/reports/M<N>.md`.
6. Review (human + strong-model review against `docs/REVIEW_CHECKLIST.md`). CI must be green.
7. Squash-merge into `develop`, delete the feature branch.
8. When `develop` is verified: PR `develop` → `main`, merge (merge commit), tag `m<N>`, push tag.

## Commits

Conventional Commits: `type(scope): summary`

- types: `feat`, `fix`, `test`, `refactor`, `docs`, `build`, `ci`, `chore`
- scopes: `core`, `app`, `scanner`, `ui`, `db`, `ci`, `docs`
- Example: `feat(core): reconcile marks missing findings solved only when scanner succeeded`

## GitHub settings (one-time)

- Default branch: `develop`
- Branch protection on `main` and `develop`: require PR, require CI status check `build`, no force-push,
  no deletion.
- Settings → General: allow squash merge and merge commits; auto-delete head branches.

## Hotfix

`fix/<slug>` from `develop` → PR to `develop`. If `main` is affected, after merging to develop, PR `develop` → `main`
and tag `m<N>.1`.
