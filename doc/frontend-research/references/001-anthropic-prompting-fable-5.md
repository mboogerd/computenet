---
title: "Prompting Claude Fable 5"
source_url: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5
publisher: Anthropic (official docs)
type: prescribed-practice (primary/official)
retrieved: 2026-07-11
---

# Summary

Anthropic's official migration/prompting guide for Fable 5, written for developers moving prompts and
harnesses from Opus (or older models) onto Fable 5. Its throughline: Fable 5 plans and self-corrects far
more than earlier models, so scaffolding built to compensate for a weaker model becomes noise (or actively
harmful) once ported unchanged.

## Capability shifts that motivate new prompting habits

- Sustains long-horizon autonomy with strong instruction retention across multi-day tasks.
- Higher first-shot correctness on well-specified problems, needing less iteration.
- Stronger vision for dense technical images/screenshots (can use bash/crop tools on flipped or noisy images).
- More dependable at dispatching and supervising parallel subagents over sustained work.

## Concrete scaffolding changes recommended

- **Turns run longer by default** at higher effort settings — adjust client timeouts, use async status
  checks rather than blocking, and stream progress instead of waiting on a single long response.
- **Effort parameter as first-class control**: default to `high` for most work, `xhigh` only for
  capability-sensitive tasks, and `medium`/`low` for routine work — even "low" reportedly beats older
  models' best effort on many tasks.
- **Stop over-planning**: tell the model to act once it has enough information rather than re-deriving
  settled facts or narrating options it won't take.
- **Stop unsolicited refactors**: bug fixes shouldn't come with surrounding cleanup; avoid designing for
  hypothetical future requirements.
- **Lead with the outcome**: the model's first sentence back to a user should answer "what happened,"
  with supporting detail after — not the reverse.
- **Checkpoint only for genuine decisions**: pause the user only for irreversible actions, real scope
  changes, or input only they can supply — not for routine confirmation.
- **Ground progress claims in tool evidence**: before reporting progress, the model should audit each claim
  against an actual tool result from the session, and say plainly when something is unverified, failing, or
  skipped rather than hedge or oversell.
- **State explicit boundaries**: when the user is thinking out loud or diagnosing, the deliverable is the
  assessment, not an unsolicited fix; check evidence before any state-changing command.
- **Delegate deliberately**: give explicit guidance on when subagent delegation is appropriate, prefer async
  communication with subagents over blocking, and keep long-lived subagents around to reuse cached context.
- **Build an explicit memory system**: one lesson per markdown file, one-line summary at the top, updating
  existing notes instead of duplicating, deleting notes that turn out wrong.
- **Autonomy framing for unattended runs**: explicitly tell the model the user isn't watching and cannot
  answer mid-task, so it should proceed on reversible actions rather than asking "want me to...?", and check
  its own last paragraph for undone promised work before ending a turn.
- **Don't surface context-budget counts**: this can trigger unwanted "should I start a new session?"
  behavior; reassure the model it has ample context if this comes up.
- **Give the reason, not just the request**: state the larger task, who it's for, and what the output
  enables before the specific ask — this helps the model connect the request to broader context.
- **Separate "working" register from "reporting" register**: terse shorthand between tool calls is fine,
  but the final summary should be written as if for a reader who saw none of that — full sentences, no
  invented shorthand or arrow chains.
- **Add a dedicated "send to user" tool** for long asynchronous agents, so verbatim deliverables and
  progress updates reach the user without being paraphrased away by a turn-ending summary.

## Recommended migration checklist

1. Start near the top of the difficulty range you'd assign the model — it's built for harder problems than
   prior generations.
2. Make self-verification explicit via separate verifier subagents rather than relying on self-critique.
3. Re-audit existing prompts/skills for over-prescriptiveness inherited from weaker models; cut what default
   behavior now already handles.
4. Don't ask the model to reproduce its raw reasoning (can trigger a refusal); use structured `thinking`
   blocks with adaptive effort instead.
5. Build the send-to-user tool described above for long-running agentic use.

## Relevance to GUI/frontend building with Opus + Fable

This is the primary document underpinning the "don't over-specify steps, do specify effort/boundaries/
verification" pattern that recurs across nearly every third-party article in this research set (see
`009`, `013`, `017`, `018`). It's also the source for the specific instruction snippets many blog posts
reproduce nearly verbatim.
