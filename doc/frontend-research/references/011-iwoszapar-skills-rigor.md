---
title: "Claude Fable 5 Skills: Keep the Rigor on Opus 4.8"
source_url: https://www.iwoszapar.com/p/claude-fable-5-skills
author: Iwo Szapar
date: 2026-07-03 (updated 2026-07-10)
type: experience-report / prescribed-practice, with informal benchmark
retrieved: 2026-07-11
---

# Summary

Packages Fable-level rigor as installable Claude Skills for use with Opus 4.8, with a blind-graded
comparison as informal validation.

## Key workflow patterns

- **Plan before executing**: document goals, unknowns, success criteria, and step order before touching
  anything.
- **Self-refutation**: actively look for flaws in your own work and surface them in the deliverable rather
  than silently patching and moving on.
- **Verify against live systems, not documentation** — docs "are stale by default," so check actual
  code/behavior directly.
- **Strict scope adherence**: execute exactly what was asked; flag adjacent issues instead of silently
  fixing them.
- **Aggressive trimming**: cut roughly 30% of a draft's content while retaining all essential information —
  treated as a deliberate quality pass, not just brevity for its own sake.
- **Session memory management**: decide explicitly what persists across sessions and when previously-recalled
  information needs re-verification rather than being trusted as still true.

## Validation approach (methodologically interesting)

Tested six skills against plain Opus 4.8 using blind-graded comparisons with deliberately planted traps
(contradictory specs, stale READMEs, off-by-one errors). Reported result: 12 wins, 0 losses, 2 ties across
published benchmarks, with failure cases included rather than cherry-picked.

## Implementation detail

Skills install as markdown files at `~/.claude/skills/<name>/SKILL.md`, with roughly 6–7% token overhead
per task — treated as negligible under a paid plan.

## Relevance to this project

The "verify against live systems, not docs" and "strict scope adherence, flag don't fix" rules are directly
applicable to keeping Opus disciplined while it does the bulk of frontend implementation work for the
argumentation-graph UI, per the routing split described in `009`.
