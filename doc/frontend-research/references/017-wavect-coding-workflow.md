---
title: "Fable Is Back. Here's How to Actually Code With It"
source_url: https://wavect.io/blog/coding-with-claude-fable-5/
author: Kevin Riedl
date: 2026-07-02
type: experience-report
retrieved: 2026-07-11
---

# Summary

A hands-on coding-workflow report describing the "Fable sandwich" pattern — the clearest concrete workflow
skeleton found in this research set for splitting work across models by phase.

## Context

Fable returned (as of July 1, 2026) with stricter safety classifiers routing certain requests to Opus 4.8.
The author frames this as requiring deliberate model routing rather than "Fable can no longer code."

## The "Fable sandwich" — three/four-phase structure

1. **Explore** — cheaper model surveys the codebase/problem space.
2. **Plan** — Fable produces the plan, given its long-horizon reasoning strength.
3. **Execute** — Opus or Sonnet implements against the plan.
4. **Verify** — Fable reviews the result again before it's considered done.

Reported as more reliable than asking Fable to handle an entire task solo end-to-end.

## Other concrete techniques

- **Judgment-based allocation**: reserve Fable for architecture, migrations, complex debugging, and final
  review; delegate routine syntax fixes, CRUD changes, and boilerplate to cheaper models.
- **Lean repository prep**: keep CLAUDE.md to build/test commands and project gotchas, not long tutorials;
  use SKILL.md files for reusable domain-specific workflows.
- **Subagent delegation**: Fable orchestrates, cheaper models implement; parallel experiments via git
  worktrees, with Fable comparing final diffs.
- **Defensive security framing**: phrase security-adjacent work as "defensive code review" rather than
  exploit-suggestive language, to avoid unnecessary classifier fallback.

## Gotchas

- Even benign coding tasks can occasionally trigger safety fallback.
- Asking for "hidden reasoning" causes refusals (matches `001`'s guidance to use structured thinking blocks
  instead of asking the model to expose raw reasoning).
- A large context window doesn't mean tokens are free — cost still scales with what's actually loaded.
- Effort-level calibration matters: "high" suits most tasks; "xhigh" is oversized for routine work (echoes
  `001` and `013`).

## Relevance to this project

The "Fable sandwich" (explore → plan → execute → verify) is the strongest single candidate skeleton for this
project's concise workflow document — it's corroborated independently by the phase structure previewed in
`012` and the routing logic in `009`.
