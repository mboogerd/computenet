---
title: "Prompting best practices (general)"
source_url: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices
publisher: Anthropic (official docs)
type: prescribed-practice (primary/official, model-agnostic baseline)
retrieved: 2026-07-11
---

# Summary

Anthropic's general-purpose prompt-engineering reference, applicable to Opus and Fable alike. Serves as
the baseline that model-specific guides (esp. `001`) layer their Fable-specific adjustments on top of.

## Core principles

- Be clear and direct — write instructions as if briefing a new employee with no other context.
- Add context/motivation, not just the instruction — explain why, so the model can generalize correctly.
- Use 3–5 diverse examples in `<example>` tags to pin down format, tone, and structure rather than
  describing them abstractly.
- Structure prompts with consistent, descriptive XML tags (`<instructions>`, `<context>`, `<input>`) to
  reduce misparsing, especially as prompts grow complex.
- Give the model an explicit role/persona in the system prompt to focus tone and behavior for the use case.
- For long documents: place them above the query, tag their structure, and ask the model to quote relevant
  sections before answering.

## Output and formatting control

- Tell the model what to do, not just what to avoid — positive instructions steer format more reliably.
- Request specific design/visual-hierarchy/motion choices explicitly for document or UI generation — recent
  models are more concise/direct by default and need to be told when more visible reasoning is wanted.
- Don't rely on prefilled assistant turns for steering final output on current-generation models — use
  explicit instructions instead (prefill on final turns is no longer supported from Claude 4.6 on).

## Tool use and agentic behavior

- Be explicit about *when* to use a tool; avoid ambiguous "suggest changes" phrasing if you actually want
  the change implemented.
- Use tags like `<default_to_action>` / `<do_not_act_before_instructions>` to control proactivity, and
  `<use_parallel_tool_calls>` to encourage batching independent tool calls for speed.
- Dial back generic "use more tools"/anti-laziness boilerplate for current models — they're already
  proactive, and blanket instructions can cause overtriggering.
- Use adaptive `thinking` with the `effort` parameter (rather than the deprecated `budget_tokens`) to tune
  how much multi-step reasoning the model performs.

## Agentic-system specific guidance

- For long-horizon/state-tracking work: use structured formats (JSON, git) for durable state and free text
  for progress notes; give explicit context-reorientation prompts across multi-window workflows.
- Require confirmation before destructive or hard-to-reverse actions (deletes, force-push, external posts).
- For research tasks: define success criteria explicitly, and encourage source verification and hypothesis
  tracking rather than single-pass answers.
- Let the model delegate to subagents naturally, but bound excessive delegation on simple sequential work
  with an explicit `<use_subagents>`-style guard.
- For workflows needing inspectable intermediate output, chain explicit multi-step calls (draft → review →
  refine) instead of a single end-to-end call.
- Instruct cleanup of temporary files created mid-task, and require investigation (`<investigate_before_
  answering>`) before claims are made about code, to reduce hallucination.
- Explicitly instruct against hard-coding to pass specific test cases rather than solving generally.

## Frontend/design-specific tip (directly relevant to this project)

- For UI generation specifically, prompts should explicitly ask for distinctive aesthetics, unique
  typography, and a cohesive color/motion system — otherwise output trends toward generic, sample-looking
  "AI slop." This is the earliest/clearest statement of a theme repeated by nearly every UI-focused article
  in this set (`004`, `006`, `008`).

## Relevance to this project

This is the model-agnostic foundation: XML-tag structuring, example-driven steering, and explicit tool-use
framing apply whether the requesting agent is Opus (reasoning/backend) or Fable (UI generation). The
frontend-aesthetics tip is the seed of the "avoid generic AI slop" guidance elaborated at length in the
UI-specific sources below.
