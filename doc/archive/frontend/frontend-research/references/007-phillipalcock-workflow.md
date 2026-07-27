---
title: "Prompting Claude Fable 5"
source_url: https://phillipalcock.substack.com/p/prompting-claude-fable-5
author: Phillip Alcock
date: 2026-07-02
type: experience-report / prescribed-practice
retrieved: 2026-07-11
---

# Summary

Positions prompting Fable 5 as workflow design rather than conversational query-writing — the most
explicit statement of the "prompt as a small system" framing in this research set.

## Key recommendations

- Provide context: explain *why* the task matters and who benefits, not just *what* to do.
- Favor action over planning: let the model proceed once it has enough information rather than generating
  an endless menu of options.
- Match effort to complexity: deeper prompting for strategy/research; lighter prompting for simple edits.
- Define boundaries explicitly: state what must be excluded or preserved to prevent unwanted rewrites or
  scope creep.
- Request self-verification: ask the model to compare its output against the original request and rate its
  own completion before calling the task done.
- Demand specific progress updates: ask for concrete completed-steps accounts rather than vague reassurance.
- Prioritize clarity for the reader: write final output for someone who didn't see the intermediate work;
  lead with the result.
- Use memory selectively: store only recurring, reusable patterns — avoid accumulating clutter.
- Limit reasoning exposure: ask for conclusions with key reasons, not an exhaustive reasoning trace.
- Treat prompts as workflows: combine context + task + limits + output format + verification into one
  structured request rather than a bare ask.

## Where this diverges from mainstream framing

Alcock's "workflows over prompts" framing is more formal/systems-oriented than most other sources, which
mostly describe individual prompting habits. It agrees in substance with `001` and `009` but packages the
advice as a repeatable request template rather than a list of tips — directly useful as the skeleton for
this project's own workflow document.

## Relevance to this project

The five-part template (context, task, limits, output format, verification) is a strong candidate as the
canonical prompt shape to standardize on for both Opus and Fable requests in this project's workflow guide.
