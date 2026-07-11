---
title: "Claude Fable 5: What Changed, and How to Stop Prompting It Like Opus"
source_url: https://kenhuangus.substack.com/p/claude-fable-5-what-changed-and-how
author: Ken Huang
date: 2026-07-04
type: experience-report / prescribed-practice
retrieved: 2026-07-11
---

# Summary

Argues that Fable 5's stronger native planning makes the detailed step-by-step scaffolding developers built
up for Opus counterproductive — a direct, named contrast between "how to prompt Opus" and "how to prompt
Fable."

## Old Opus-era habits to abandon on Fable

Numbered step lists, "think step by step" directives, extensive behavior checklists, and elaborate
instruction manuals — all of which compensated for weaker planning in older models.

## New approach for Fable

- Lead with the larger project context and *why* it matters.
- State current conditions and specific constraints.
- Present the core goal.
- Let the model determine the optimal sequence itself.

The author reports that under the old, prescriptive approach "the model followed my steps faithfully,
including the three steps that were wrong," whereas removing procedural dictation surfaced better solutions
the author hadn't considered.

## Effort as the real lever

Recommends `output_config.effort` (five levels, low through max) as a first-class control rather than
rhetorical "think harder" prompting — and notes low-to-medium effort on Fable often outperforms Opus's
highest setting while costing less.

## Key insight

The leverage point shifts from clever instruction-writing to engineering the surrounding loop: memory
systems, verification agents, and explicit boundaries around autonomous runs — not more detailed
instructions.

## Relevance to this project / where it sits against other sources

This directly agrees with `001`'s "stop over-planning" and "stop unsolicited refactors" guidance, and with
`009`'s framing of Fable as suited to ambiguous, plan-requiring work. It's in mild tension with `005`'s rule
4 (don't over-frame simple requests as hard problems) only insofar as both are really about the same
underlying skill — calibrating how much structure/effort a given request actually needs, not a real
disagreement.
