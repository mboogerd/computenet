# Parking a question

The bar is **ambiguous AND costly** — both halves, not either one:

- **Ambiguous** — the spec/ticket genuinely supports more than one reading and you'd be picking, not deriving.
- and at least one of:
  - **Risky** — could break something outside the item's own scope (data, other machines' in-flight work, prod-adjacent config).
  - **Hard to revert** — schema changes, published APIs, wire formats, deleted data, anything you can't just re-edit away.
  - **Expensive to unpick** — the choice spreads across many call sites, so getting it wrong is a rewrite rather than an edit.

The test: *if this turns out wrong, is it a five-minute fix or a five-hour
one?* Five minutes, decide it and record the assumption. Five hours, park it.

Two things that are **not** this bar:

- **Ordinary judgment calls** — variable names, which existing helper to
  reuse, file layout, test structure. Decide them.
- **Big but clear.** Work that's simply larger than its item implied isn't a
  question, it's a sizing error: split it into beads items
  (`bd create --parent=…`, per [issue-quality.md](issue-quality.md)), say so
  in a comment, and keep going. Parking it stalls work nobody is confused
  about.

Both directions cost real time — a parked item waits for a human, and a wrong
expensive guess costs more than the run was worth. An agent that parks every
small uncertainty makes no progress, which is the same as failing.

## How to park it

Hand the item to a human — reassign it, don't just tag it:

```bash
bd update <id> --status=blocked --add-label=human --assignee=human
bd comment <id> "QUESTION: <the actual question, with enough context that someone cold can answer it — what you were doing, the options you're choosing between, what you'd do by default, and why it's not a call you should make unilaterally>"
```

The parked question lives in the local beads DB until the session's Finalize
push (SKILL.md step 6) — that push is what puts it in front of a human via
`bd human list` on another machine. Don't sync it yourself, and do name the
parked item in your report: the report is what reaches a human first.

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
