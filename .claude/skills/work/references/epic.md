# Epic → features

Break one epic into features, then stop. Don't implement anything yourself.

The epic is already claimed and already carries its `owner:` label — the
orchestrator did both before dispatching you. Don't repeat either.

## Reconcile first

```bash
bd list --parent=<id> --all --json
```

A previous breakdown may have died part-way. Create only what's missing.

## Break it down

Read the full epic (`bd show <id>`) and every spec/doc section it cites —
the cited spec text is the authority (AGENTS.md), not your first instinct.

Propose features that together deliver the epic, each independently
shippable or at least independently reviewable.

**Write them to [issue-quality.md](issue-quality.md)** — the feature section
in particular (example mapping, EARS-phrased rules, concrete examples). It is
the standard the feature reviewer judges against, so an issue that doesn't
meet it fails later rather than never.

```bash
bd create --type=feature --parent=<epic-id> --validate \
  --title="<outcome as a change to the system>" \
  --description="<what the system does here today, why this work exists, which spec sections govern it, what's out of scope>" \
  --acceptance="<EARS-phrased rules that define 'this feature is delivered'>" \
  --design="<the examples: Given/When/Then per rule, plus assumptions you decided>"
```

`--acceptance` is not optional. A dedicated reviewer judges the finished
feature against exactly these statements and decides on that basis whether
its PR ships ([review-feature.md](review-feature.md)) — a feature without
them gives that gate nothing to check. Write them at feature level: what
must be true once the whole thing works, not what each task does.

Give each feature enough context to be decomposed later *without this
conversation*: what the system does there today, why the work exists, which
spec sections govern it. The agent that turns it into tasks starts fresh and
knows the codebase only through what you cite.

If the epic's own success criteria don't meet
[issue-quality.md](issue-quality.md) — vague, uncheckable, or absent — repair
them in place (`bd update <epic-id> --acceptance=…`) before splitting. You
cannot trace features to criteria that don't exist, and every later gate
depends on that trace.

Wire `bd dep add` only for real output dependencies — one feature genuinely
cannot start until another lands. Not a preferred order, and not "these
might touch the same files" (file overlap is handled by task-level
scheduling — see [feature.md](feature.md)). Over-wiring starves the queue.

Apply the [ask-human.md](ask-human.md) bar: if the epic's scope is genuinely
ambiguous, or the split has a risky/expensive/hard-to-revert fork, park a
question on the epic rather than guessing a split.

## Finish

```bash
bd lint <feature-ids...>
```

Fix anything `bd lint` reports. The features you created live in the local
beads DB until the orchestrator's Finalize push (SKILL.md step 6) sends them
to the shared tracker — don't sync here; the session syncs twice in total, a
pull at start and that push at the end.

Then check the trace: every
epic success criterion is covered by at least one feature, and every feature
serves at least one criterion. A criterion with no feature means the
breakdown isn't finished; a feature serving none means it's out of scope.

Comment the features created on the epic. Leave the epic `in_progress` — it
stays claimed across sessions until all its features close. Report the
feature ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
