---
description: Build the architect context bundle for the claude.ai project (read-only)
argument-hint: (none)
---

Invoke the `handover` skill and follow it exactly — it is the single source of truth for this workflow.

The full, authoritative instructions live in `.claude/skills/handover/SKILL.md` (run the skill via
`Skill(handover)`). This command is only a thin entry point so `/handover` keeps working; do not duplicate the
steps here. If the skill and this file ever disagree, the skill wins.
