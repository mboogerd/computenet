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

## A host REBOOT, not a crash: the epic claim is gone and so is `$SCRATCH`

Both are correct consequences and neither is covered above (computenet-hd2f).
The dead process's holder token is DEAD by definition, so a sibling's step-3
sweep has probably **released your epic** — re-run `claim-epic.sh` before
touching the subtree (it SKIPs if another machine has since taken it, and
that is the right answer). And `$SCRATCH` under `/private/tmp` does not
survive a reboot: re-create it, re-write `slot-start` from the ORIGINAL slot
start (above), and re-ledger any job you restart.

## macOS revoked `~/Documents` mid-slot (`Operation not permitted` everywhere)

Signature: `ls ~/` works, `ls ~/Documents` is denied, and the beads DB, the
checkout's `.git/objects` and `.beads/config.yaml` all return EPERM — the
**identical** error with `dangerouslyDisableSandbox`, EPERM from the Read
tool too, and `request_directory` grants with no effect; some individual
files still read while enumeration fails (computenet-hc3s). **It is not the
Bash sandbox** and an unattended session cannot recover it: only a human at
System Settings can. Do not retry, do not re-request the folder. Finish what
is already certified and stop: `cd /` (outside the denied tree), then
`gh pr ready <n> --repo mboogerd/computenet` for any PR a reviewer has
already passed, `gh pr comment` the verdicts you cannot record in bd, and
end the session with the list of bead writes that did not happen. The two
git reads 5e needs have `gh` substitutes: `gh api
repos/mboogerd/computenet/commits/main` for the landed-since fetch, and
`gh run view <run-id> --log --repo mboogerd/computenet` for the
executed-not-skipped read. A certified green PR still ships. Finalize's
publication push is impossible and that is recoverable: the local bead
writes are intact on disk, and the next session's step-3 sweeps close the
merged items and release the dead epic claim on their own. A DISPATCHED
agent that loses bd mid-task writes its verdict to a file outside the tree
and names the path in its result (agent-execution.md says so) — the
orchestrator posts it.
