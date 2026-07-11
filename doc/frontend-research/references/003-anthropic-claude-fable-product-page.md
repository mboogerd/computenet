---
title: "Claude Fable (product page)"
source_url: https://www.anthropic.com/claude/fable
publisher: Anthropic (official)
type: prescribed-practice (primary/official, positioning)
retrieved: 2026-07-11
---

# Summary

Anthropic's product page positions Fable 5 as the frontier-tier model sitting above Opus 4.8, intended for
demanding, long-running, largely unsupervised work rather than quick everyday interactions.

## Positioning vs. Opus

- Fable is explicitly framed as *not* the default choice for everyday tasks — Opus 4.8 remains the workhorse
  for routine, well-scoped work; Fable targets "ambitious, long-running, asynchronous work."
- Customers are quoted describing it as needing "far less correcting and nudging," capable of "one-shotting"
  complex applications where earlier models needed multiple passes.

## Highlighted capabilities

- Self-verification / quality-checking of its own outputs before returning them.
- Advanced vision for diagrams, charts, and document-embedded visuals.
- Strong performance on complex coding, research, and analytical problems.
- Handles multi-stage workflows with reduced need for human intervention at each step.

## Constraints worth designing around

- Certain biology/cybersecurity-adjacent queries are automatically downgraded to Opus 4.8 by safety
  classifiers — a defensive-review project on the argumentation-graph backend should expect occasional
  fallback if prompts read as exploit-oriented (see also `017` for phrasing workarounds).
- Requires 30-day data retention for monitoring purposes, which may matter if the project has data-handling
  constraints.

## Relevance to this project

Confirms the two-model split this whole research task assumes: Opus as the economical, well-scoped
workhorse; Fable as the premium model reserved for ambiguous, high-stakes, or long-horizon work (UI/UX
architecture decisions, cross-cutting design-system work) — a split elaborated operationally in `009`,
`016`, and `017`.
