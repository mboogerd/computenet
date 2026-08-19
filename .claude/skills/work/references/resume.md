# Resuming after the host process died

**Two different things stop a slot, and they need opposite responses. Tell
them apart before reading further:**

| | host process **DIED** | host **SUSPENDED** |
|---|---|---|
| Signature | `task-notification` with `status=stopped` from the previous session | two or more budget notifications arriving **together**, often with the monitor's "stream ended" right behind |
| The monitor | gone; nothing re-arms it | survived and **fired everything at once** — the notifications are not missing, they are worthless |
| The process | exited | alive throughout |
| Response | re-arm from the original slot start, below | recompute elapsed and usually go **straight to Finalize** |

The rest of this file is the DIED route. For the suspended one there is
nothing to re-arm: `sleep` counts wall clock including time the machine spent
asleep, so every tier elapsed during the suspension. Recompute from
`$SCRATCH/slot-start` and act on the number, not on which tier fired —
measured once at 834m true elapsed against a 300m slot (computenet-3gf5). A
session that reads this file for that case finds it does not match, which is
the confusion this table removes.

Read on when a `task-notification` with **`status=stopped`** says it comes
*from the previous session*: the Claude Code host process exited and this is
a resume — not a failure (observed mid-breakdown, computenet-024s). Three
things are true at once:

- **`status=stopped` is an UNKNOWN outcome, explicitly not a failure.** The
  agent may have finished its work and died before reporting.
- **The budget monitor is gone and nothing re-arms it.** Without this file
  the session runs with no clock at all (computenet-m5l, computenet-776).
- **Re-arm from the ORIGINAL slot start, never from now.** Step 2's offsets
  are fixed `sleep`s, so a naive re-arm grants a full fresh slot.

## Re-arm the clock

```bash
S=<step 2's scratch dir, spelled out — the PREVIOUS session's path>
start=$(cat "$S/slot-start"); slot=$(cat "$S/slot-seconds")
left=$(( start + slot - $(date -u +%s) ))
echo "seconds left in slot: $left"     # <=0 → go straight to Finalize
```

Arm one monitor over `$left`, keeping step 2's shape against the *original*
slot end: warn at `left-6300`, `left-3600`, `left-900` seconds, dropping any
that is already ≤0 and going straight to the next one.

Those files survive a host-process exit, so the usual reason to miss them is
not knowing the path, not deletion. If you genuinely cannot recover it, take
the start from the epic's claim — `bd show <epic> --json | sed -n
'/^[[{]/,$p' | jq -r '.[0].started_at'`, set by `--claim` at step 3, minutes
after the true start — and the slot length from the routine that invoked
you. (A first bd comment's timestamp works too, but nothing before step 5
requires one, so on an early resume there may be none.) Failing all of
those, say so and treat the remaining budget as **one hour** — a short slot
that finishes is worth more than a long one nobody is timing.

## Query for side effects before re-dispatching anything

A killed agent may have already done its work: a breakdown that died may
have filed its children (computenet-8kj.5.1 existed, complete and usable) —
re-dispatching duplicates it, and reading the stop as failure discards it.

```bash
bd list --parent=<feature-or-epic> --all --json     # a breakdown's children
git -C <task-worktree> log --oneline; git -C <task-worktree> status --short
```

**Resume, don't restart**, wherever those show work landed — 5b's resume
query picks up an `in_progress` item with its worktree and branch intact.
Re-dispatch only what left nothing behind.
