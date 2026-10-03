---
name: deliberate
description: Runs one decision through a three-model council — Opus and Sol ideate options, each option gets its strongest case and an adversarial critique, then Opus, Sol and Jev each score every option 0-100 for acceptability — and returns DECIDED (the passing option with the highest mean, where passing is mean at least 70 and no score below 60) or NO-CONSENSUS with every objection. A pure process with no tracker or git integration; callers feed it one brief and record the verdict where the decision lives. Use when a caller (sync-report's park pass, the weekly retrospective) has a decision that would otherwise need a person, or when the user says "deliberate", "run the council", "ask the panel".
---

# /deliberate

A last attempt to settle a decision before it costs a person's attention. The
council is three independent members, so a verdict is worth more than one
agent's opinion. It is still a model verdict: it resolves ambiguity, and it
cannot grant permission.

**Pure.** This skill reads a brief and writes files into an output directory.
It never calls `bd`, `git` or `gh`. Whatever tracker the decision lives in, the
caller reads it, writes the brief, and records the verdict afterwards.

## 1. Is it a council question?

Refuse and hand straight back to a person when the question is really:

- **A permission.** For example "may an agent push a deliberately broken
  commit" or "may an agent install a harness hook". Agreement cannot authorize
  an action the harness or a policy reserves for a human.
- **A choice the person reserved.** A rule the person decided themselves may
  be re-examined, but only when the brief says it was reserved and asks for
  advice. The verdict then goes back as advice, never as a decision.
- **Not a choice at all.** Missing facts, a measurement to take, or a
  question an agent can answer by reading the code. Go and get the fact.

## 2. Write the brief

One decision per brief, and one brief per run. Members never see two
decisions at once. Write `brief.md` for a cold reader who has never seen the
repo:

1. **The frame.** Why the project exists: its vision and the principles that
   bear on this decision. For ComputeNet, draw on
   `doc/spec/00-foundations/01-vision.md` and `02-design-principles.md`, plus
   what is at stake here: honesty over convenience, no silent loss, spec-led,
   reversibility. Members judge consequences against this frame, so a thin
   frame gets a generic answer.
2. **The problem.** What is stuck, in plain words, and what it blocks.
3. **The context.** Only the facts needed to judge: what exists, what was
   measured, what the spec says. Quote requirement ids, and say what each one
   means.
4. **Options already on the table**, if any, each with its consequences. The
   council adds any that are missing.
5. **Constraints.** Anything that is not negotiable, and anything the person
   has already said.

Keep it neutral. Do not recommend anything; the brief's framing is the one
bias every member shares.

## 3. Run

```bash
.claude/skills/deliberate/scripts/deliberate.py --brief <dir>/brief.md --out <dir>/council
```

A run makes ten model calls and took 2.5 minutes on its first real decision;
allow up to 20, and run it in the background. Opus runs through the newest `claude` CLI bundled with the desktop
app, because Opus 5.5 needs CLI 2.1.280 or later. Sol runs through `codex exec`
and Jev through the TypeSafe API (`TYPESAFE_API_KEY`). A member that fails
aborts the run: report the error and do not treat a partial run as a verdict.
Every prompt and reply is kept in `<out>/`.

## 4. Read and hand back the verdict

`<out>/verdict.md` starts with `DECIDED: <id> — <title>` or `NO-CONSENSUS`,
followed by every member's score for every option, each option's mean,
minimum and pass mark, every objection, the options, and, when decided, the
deciding factors and the strongest case against the winner. `verdict.json`
holds the same data for scripts.

- Each member scores **every** option 0-100 for acceptability. Jev is asked
  P(acceptable) for each option independently, so more options do not dilute
  its scores. A score below 60 is an objection, and the member states it (Jev
  gives no reasons; the option's case against stands as its objection).
- An option **passes** at a mean of at least 70 with no score below 60.
  **DECIDED** names the passing option with the highest mean, ties going to
  the higher minimum. No passing option is **NO-CONSENSUS**, listing every
  objection.
- The caller always records **every member's scores and objections**. That
  record is what stops the same expensive run happening twice. When the
  outcome is DECIDED, it also records the chosen option and its deciding
  factors.
- Treat a DECIDED verdict whose brief turns out to have misstated a fact as
  void. Fix the brief and run again.
