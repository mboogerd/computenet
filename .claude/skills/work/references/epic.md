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

```bash
bd create --type=feature --parent=<epic-id> \
  --title="<outcome as a change to the system>" \
  --description="<what the system does here today, why this work exists, which spec sections govern it>" \
  --acceptance="<checkable statements that define 'this feature is delivered'>"
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

Wire `bd dep add` only for real output dependencies — one feature genuinely
cannot start until another lands. Not a preferred order, and not "these
might touch the same files" (file overlap is handled by task-level
scheduling — see [feature.md](feature.md)). Over-wiring starves the queue.

Apply the [ask-human.md](ask-human.md) bar: if the epic's scope is genuinely
ambiguous, or the split has a risky/expensive/hard-to-revert fork, park a
question on the epic rather than guessing a split.

## Finish

```bash
bd dolt push
```

Comment the features created on the epic. Leave the epic `in_progress` — it
stays claimed across sessions until all its features close. Report the
feature ids.
