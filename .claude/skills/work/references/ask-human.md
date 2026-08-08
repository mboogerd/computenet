# Parking a question

The bar (all four are "default to asking," not "default to guessing"):

- **Ambiguous** — the spec/ticket genuinely supports more than one reading and you'd be picking, not deriving.
- **Expensive** — meaningfully more tokens, time, or money than the item implied (e.g. the "task" turns out to need a rearchitecture).
- **Risky** — could break something outside the item's own scope (data, other machines' in-flight work, prod-adjacent config).
- **Hard to revert** — schema changes, published APIs, deleted data, anything you can't just re-edit away.

Ordinary implementation judgment calls (variable names, which existing helper
to reuse, test structure) are not this bar. Don't park those.

## How to park it

Hand the item to a human — reassign it, don't just tag it:

```bash
bd update <id> --status=blocked --add-label=human --assignee=human
bd comment <id> "QUESTION: <the actual question, with enough context that someone cold can answer it — what you were doing, the options you're choosing between, what you'd do by default, and why it's not a call you should make unilaterally>"
bd dolt push
```

All three flags do work. `assignee=human` + `blocked` takes the item out of
the startup stale-claim sweep (which reopens `assignee=<machine>` items left
`in_progress`), so a question survives instead of being silently reopened
and re-claimed by the next run. `blocked` also takes it out of `bd ready`.
The `human` label is what surfaces it in `bd human list`, where it's
answered with:

```bash
bd human respond <id> -r "the answer"
```

One side effect to know: `bd create` inherits the parent's labels, so any
item created under a parked one picks up `human` and shows up in
`bd human list` as a question nobody asked. Pass `--no-inherit-labels` when
creating a child of a parked item.

That comments the answer and closes the flag, but does **not** unblock the
original item — a later session (or you) reopens it with
`bd update <id> --status=open`.

After parking, don't wait for the answer. Report that the item is blocked
and finish.

**A parked question blocks one item, never the tree.** Park it on the
narrowest item that's genuinely stuck — the task, not its feature; the
feature, not its epic. The orchestrator keeps working that feature's other
ready tasks, and moves to another feature if this one has none. If you find
yourself wanting to block a feature or epic because one task under it is
ambiguous, park the task instead and let the rest proceed.
