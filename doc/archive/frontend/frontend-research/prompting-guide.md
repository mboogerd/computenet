# Prompting Guide: Claude Opus + Fable for GUI Building

Consolidated from the sources in `references/` (see `references/index.md` for pointers into each idea).
Written for building the argumentation-graph frontend, where Opus does most of the well-scoped
implementation work and Fable is reserved for harder, more ambiguous design and review work.

## 1. The one rule that overrides the rest: give context, not steps

Across nearly every source, the single biggest lever is stating the goal, who it's for, and the
constraints — then letting the model work out its own sequence — rather than dictating a numbered
procedure. This applies to both models, but matters most on Fable, whose planning is strong enough that
prescriptive step lists actively make output worse: it will follow wrong steps faithfully instead of
noticing they're wrong.

A reliable shape for both Opus and Fable requests:

```
I'm working on [the larger task] for [who it's for]. They need [what the output enables].

Context: [current state / constraints / what must be preserved]
Goal: [the outcome, stated as an outcome, not a procedure]
Boundaries: [what NOT to touch, what's out of scope]
Output format: [what "done" looks like]
Verification: [how you — or the model — will check this is actually correct]
```

Reserve numbered step-by-step instructions for cases where the order genuinely matters and isn't
discoverable from context (e.g., a migration that must run in a specific sequence) — not as a default
scaffolding habit inherited from prompting older, weaker models.

## 2. Structure with XML tags and examples, not longer prose

This is the model-agnostic baseline underneath everything else:

- Wrap distinct prompt components in tags: `<context>`, `<instructions>`, `<input>`, `<example>`.
- Give 1–2 concrete examples of the target output rather than describing it in the abstract — this
  resolves more ambiguity than another paragraph of description.
- Assign a role/persona up front when it changes how the model should approach the problem (e.g., "as a
  senior frontend engineer reviewing this for accessibility").
- State the expected output scope explicitly (a one-line answer vs. a full design document) — the model
  will otherwise guess at an ambiguous middle ground.

## 3. Calibrate effort deliberately — don't use rhetoric instead

Use the `effort` parameter (low/medium/high/xhigh) as a first-class control rather than phrases like "think
harder" or "be thorough." Defaults that hold up across sources:

- `high` for most substantive frontend/design work — this is the recommended default, not an escalation.
- `xhigh` only for genuinely capability-sensitive work (e.g., resolving a real architectural ambiguity in
  the graph-visualization data model).
- `low`/`medium` for routine, well-scoped edits — reported to still exceed older models' best effort, and
  meaningfully cheaper.
- Don't dress up a simple, well-specified request as a hard reasoning problem just to get more diligence —
  that triggers costlier extended thinking for no benefit and can make small tasks slower without making
  them better.

## 4. Say what NOT to do, explicitly

Both models default toward doing more than asked unless told otherwise:

- Bug fixes and small edits shouldn't come with surrounding refactors, cleanup, or new abstractions "while
  we're in here." State this if it matters.
- When the request is a question or a diagnosis, the deliverable is the assessment — say explicitly that
  the model should report findings and stop, not silently apply a fix.
- If something outside the stated scope looks wrong, the model should flag it rather than fix it
  unprompted.

## 5. Require evidence-grounded reporting

Ask the model to check its own progress claims against actual tool output before reporting, and to say
plainly when something is unverified, failed, or skipped, rather than defaulting to confident-sounding
status updates. A simple labeling convention borrowed from this research (VERIFIED / REASONED / ASSUMED)
is worth adopting for any claim about whether a UI component actually renders correctly, passes a test, or
matches the design spec.

For UI work specifically, lean on vision-based self-verification: ask the model to take a screenshot of
what it built and check it against the stated requirements (states, data, layout) before calling the task
done.

## 6. Frontend/UI-specific prompt additions

These matter specifically for the argumentation-graph frontend, on top of the general rules above:

- **Design system before components.** Before asking for any individual screen or component (the graph
  canvas, the node inspector, the claim/rebuttal editor), have the model define a design system first:
  color roles (e.g., what "supported," "disputed," "unresolved" claims look like), a type scale, and
  spacing/sizing tokens. Build everything else against that, rather than letting each component invent its
  own conventions.
- **Specify interaction states explicitly.** Hover, focus, loading, empty, and error states are skipped by
  default unless asked for. For a graph editor this especially matters for: an empty graph, a
  loading/syncing state while the backend computes something, a node/edge selection state, and drag/
  connection-in-progress states.
- **Use realistic data, not placeholders.** Prompt with actual example arguments/claims/rebuttals rather
  than "Node 1," "Node 2" — this measurably improves how finished the output looks and surfaces layout
  problems real content would cause (long claim text, deeply nested rebuttals, many edges into one node).
- **Ask explicitly for distinctive aesthetics.** Without this, output trends toward generic, sample-look
  "AI slop" — ask for a specific typographic and color point of view rather than accepting defaults.
- **Supply a reference layer.** If there's an existing design system, brand doc, or even just a few
  screenshots of UI you like, provide them — this anchors multi-session work and prevents visual drift
  across screens built in separate conversations.
- **Know the image-generation gap.** Neither model reliably originates custom illustrations, icons, or
  branded imagery from scratch — expect to source or separately generate any custom visual assets (e.g.,
  node-type icons) rather than asking the UI-generation step to produce them inline.

## 7. Keep Opus disciplined without Fable-level cost

Most of the frontend implementation work in this project should run on Opus, not Fable — reserve Fable for
the harder 10–20% (see the workflow doc for the split). To keep Opus's output close to Fable's reliability
on the routine work, encode discipline explicitly rather than relying on the model to supply it:

- **Pre-writing checks**: require reading relevant existing files/patterns before writing new UI code,
  rather than assuming context.
- **Failure-pattern rules**: as the project accumulates recurring mistakes (e.g., "changing a node's shape
  breaks edge-anchor calculations downstream"), write them down as symptom → wrong instinct → correct move
  triples and feed them back into future prompts or a project skill file.
- **Decision branches for concurrency/state**: for anything involving async updates to the graph (e.g.,
  live collaboration, backend push updates), require the prompt to force an explicit answer to "who owns
  this update, who's notified, where do conflicts get resolved" before code is written.
- **Verification recipes**: give Opus an ordered, concrete verification sequence (lint → type-check → unit
  test → visual check) with what each failure means, rather than a vague "make sure it works."

## 8. Manage memory and configuration files deliberately

- Keep a lean project instruction file (equivalent to CLAUDE.md) limited to: a one-paragraph project
  summary, the essential build/test/run commands, and non-obvious conventions specific to this codebase.
  Resist the urge to make it a tutorial — extra length competes for the model's attention rather than
  helping it.
- Record durable lessons one-per-file in a simple memory folder, each with a one-line summary at the top;
  update existing notes rather than duplicating, and delete ones that turn out to be wrong.
- Don't surface raw context-window/budget counts to the model in long sessions — if the topic comes up,
  reassure it there's ample room rather than letting it self-interrupt to suggest a new session.

## 9. Don't ask for raw reasoning; use structured thinking instead

Requesting that a model expose or reproduce its internal reasoning verbatim can trigger a safety refusal on
current models. If visibility into the reasoning process is needed for debugging or review, rely on the
model's structured `thinking` output (via the adaptive-thinking/effort mechanism) rather than asking it to
narrate or dump its chain of thought directly.

## 10. Expect safety-classifier fallback occasionally

Prompts that read as security-exploit-oriented (even benign code review of authentication/auth-adjacent
code) can trigger a fallback from Fable to Opus. If the argumentation-graph project ever touches
authentication, permissions, or anything that could be read as attack-surface analysis, frame it as
"defensive code review" rather than "find the vulnerabilities" to reduce false-positive fallback — and be
prepared to just continue on Opus if it happens rather than fighting the classifier.

---

See `workflow.md` for how these prompting practices map onto an actual step-by-step process for building
the frontend, including when to route work to Fable vs. Opus.
