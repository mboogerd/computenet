---
title: "Claude Fable 5 Prompt Library"
source_url: https://every.to/p/claude-fable-5-prompt-library
publisher: Every.to
date: 2026 (exact day not shown)
type: prescribed-practice / prompt-library (secondary)
retrieved: 2026-07-11
---

# Summary

A categorized library of prompt *shapes* (not literal prompts) for using Fable 5 across a project
lifecycle, from discovery through post-hoc learning. Useful as a menu of prompt "jobs to be done" rather
than line-level phrasing advice.

## Prompt categories

1. **Discovery & planning** — scoping tasks and identifying what's actually worth Fable's cost before
   committing to it.
2. **Delegation & overnight tasks** — unsupervised multi-hour runs with an explicit finish line and
   workaround protocol for when the model gets stuck.
3. **Architecture & strategy** — technical planning/tradeoff documentation, not just code.
4. **Building & implementation** — staged first-version builds and ports.
5. **Feedback integration** — turning scattered human feedback into one coherent batch of changes rather
   than reacting to each comment individually.
6. **Verification & testing** — visual checks, recorded video evidence, adversarial review passes.
7. **Workflow design** — dynamic, parallelizable execution plans that can re-plan mid-flight and persist
   intermediate findings.
8. **Looping & recurrence** — converting one-off jobs into repeatable systems with memory.
9. **Context organization** — structuring what's available to the agent and resolving conflicting sources.
10. **Creative/exploratory development** — iterative argument-testing before committing to a draft.
11. **Post-execution learning** — compounding sessions into durable, non-overgeneralized lessons.

## Structural techniques

- Pre-execution briefing: restate the problem, name the gaps, and propose an approach before proceeding.
- Staged verification at each phase, not just at the end.
- Route decisions needing human judgment separately from the executable work rather than blocking on them.
- Tie every recommendation to a specific source/tool result, not "consensus."
- Treat the workflow design itself as a reviewable artifact (a diagram or markdown map), not just an
  internal plan.
- Track already-processed work explicitly to avoid duplicate effort across loop iterations.

## Framing quotes worth keeping in mind (short, attributed)

The library repeatedly frames the human's job as judgment and the model's job as "judgment-free execution
and multi-source synthesis" — echoing phrases like "do not stop at analysis" and "audit every claim against
tool results."

## Relevance to this project

Category 10 ("creative/exploratory development... iterative argument-testing before drafting") is
unusually apt for an *argumentation-graph* frontend specifically — it's a workflow shape for testing
competing arguments before committing to a design, directly mirroring what the tool itself will do for its
end users.
