# Workflow: Building the Argumentation-Graph Frontend with Opus + Fable

Concise, actionable version — see `prompting-guide.md` for the reasoning and `references/` for sources.

## Model split (default routing)

| Use Fable when... | Use Opus when... |
|---|---|
| The task is ambiguous and needs investigation before it can even be scoped (e.g., "what's the right interaction model for editing a large argument graph?") | The task is a well-specified, narrow ticket (e.g., "add a delete button to the node inspector panel") |
| It's an architecture/design-system decision that will constrain many future screens | It's routine implementation against an already-decided design system |
| You need a plan reviewed or a batch of Opus output judged/critiqued | It's high-volume, repetitive, or latency-sensitive work |
| The session will run long/unsupervised and needs to self-correct along the way | Budget/quota is tight — Fable costs ~2x Opus per token |

Default assumption: most day-to-day component and screen-building work runs on Opus. Fable is invoked
deliberately, not by default, for the harder slice of the work.

## The core loop: explore → plan → execute → verify

1. **Explore** (cheap/fast pass, Opus or a subagent): survey the existing codebase/screens, the
   argumentation-graph backend's data model (cells/ports/links per the Computenet spec), and any prior
   design decisions. Output: a short written summary of current state and open questions — not code yet.
2. **Plan** (Fable, when the design decision is non-trivial): given the explore output, produce a written
   plan — design-system choices, component breakdown, interaction states, data shapes needed from the
   backend. Commit this plan as a markdown file in the repo before moving on.
3. **Execute** (Opus): implement against the committed plan file, not against re-explained prose context.
   Keep each execution task narrowly scoped to one component/screen at a time.
4. **Verify** (Fable, for anything design-system-level or cross-cutting; Opus, for routine correctness):
   check the implementation against the plan and the design system — screenshot-based visual check, state
   coverage check (hover/focus/loading/empty/error), and a plain-language readback of what's done vs. not.

Treat this as a sandwich, not a single end-to-end call to one model — splitting by phase is reported more
reliable than asking either model to do all four steps solo.

## Before starting: a short pre-flight checklist

- [ ] Audit any existing project instruction files (CLAUDE.md-equivalent, skill files) for stale or
      over-prescriptive rules inherited from earlier, weaker models — trim before adding new guidance.
- [ ] Write (or confirm) a lean project instructions file: one-paragraph summary, build/run/test commands,
      non-obvious conventions. Nothing tutorial-length.
- [ ] Define the design system skeleton up front (color roles for claim states, type scale, spacing) before
      any component work begins — this is a Plan-phase, Fable-appropriate task.
- [ ] Decide what durable "lessons" storage looks like (one markdown file per lesson, one-line summary at
      top) so recurring mistakes get captured once and reused, not relearned every session.

## Per-task prompt checklist (apply the 5-part shape from the prompting guide)

- [ ] State context: the larger goal, who it's for, why it matters.
- [ ] State the goal as an outcome, not a procedure.
- [ ] State boundaries: what must NOT change, what's out of scope.
- [ ] State the output format / what "done" looks like.
- [ ] State how it'll be verified (screenshot check, test command, design-system compliance).
- [ ] Pick the effort level deliberately (`high` default; `xhigh` only for genuinely hard design
      decisions; `low`/`medium` for routine edits).
- [ ] For UI tasks specifically: confirm realistic data and all interaction states are requested, not left
      to default.

## During execution

- Let the model delegate to subagents for independent sub-tasks (e.g., parallel component builds) rather
  than doing everything serially — but check in on subagents rather than blocking on each one.
- Require progress reports to be evidence-grounded: what's actually been verified (screenshot taken, test
  passed) vs. assumed. Don't accept "should be working now" without a check.
- If a request unexpectedly falls back from Fable to Opus (safety classifier), don't fight it — decide
  quickly whether to continue on Opus or restart the request reframed as "defensive review" rather than
  exploit-style language.

## After a task: close the loop

- If a genuinely new, recurring failure pattern showed up (not a one-off), write it down as a short
  symptom → wrong instinct → correct move note and add it to the lessons file — after confirming it's
  actually a pattern, not a one-off.
- Trim anything in the plan/instruction files that turned out to be unnecessary scaffolding — keep the
  configuration lean over time rather than letting it accumulate.
- Don't over-generalize a single session's lesson into a blanket rule; confirm it recurs before promoting
  it from "note" to "rule."

## Cost/quota discipline

- Treat Fable's ~2x per-token cost as worth paying specifically when it reduces total correction cycles
  (architecture, cross-cutting design decisions, final review) — not as a blanket "use the best model for
  everything" default.
- Watch quota burn specifically on Fable; route the routine 80% of work to Opus to preserve Fable budget for
  the harder 20%.
