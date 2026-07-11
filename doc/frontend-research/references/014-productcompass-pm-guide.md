---
title: "Claude Fable 5 for PMs: Ultimate Guide (Safeguards, Subagents)"
source_url: https://www.productcompass.pm/p/claude-fable-5-guide
author: Paweł Huryn
date: 2026-06-11 (updated 2026-07-03)
type: prescribed-practice / experience-report
retrieved: 2026-07-11
---

# Summary

A product-management-oriented guide covering migration hygiene, safety-classifier behavior, and how to
think about subagent orchestration cost/benefit under Fable.

## Migration priority

Before adopting Fable 5, audit existing instruction files (CLAUDE.md, skills, rule files) for
contradictions, stale constraints, and patterns that only existed to compensate for older, weaker models —
directly echoing the "refactor existing prompts and skills" step in `001`.

## Safeguard navigation

Sessions that trigger safety classifiers (cybersecurity, biotech, model-distillation topics) automatically
reroute to Opus 4.8 with a notification. Recommended response: decide quickly whether to continue on Opus
or restart fresh, rather than trying to argue the classifier down.

## Cost-outcome framing

Fable bills at roughly 2x Opus 4.8's per-token rate, but deep-audit use cases show improved cost-per-finding
— notably for cross-file issues, where Fable is reported to catch planted bugs roughly 10x more reliably.
The recommendation is to price the *outcome*, not the raw token cost.

## Subagent orchestration

Fable reportedly surfaces contradictions and takes verification steps without being explicitly told to,
implying it can be given lighter scaffolding than earlier models needed — echoing `010` and `013`'s "less
prescriptive instruction, more trust" theme, though from the angle of orchestration design rather than
single-shot prompting.

## Relevance to this project

The "audit instruction files before migrating" step is a concrete pre-flight task for this project if any
existing CLAUDE.md/skill files already exist for the Computenet backend work — worth checking before
writing new Opus/Fable-specific instructions for the frontend, so the two don't conflict.
