# Claiming safely

You (the orchestrator) claim every item — epics, features, and tasks.
Subagents are handed ids that are already claimed and never claim their own.

Beads' Dolt sync is git-speed, not realtime, so a local claim proves nothing
on its own. A claim is safe only once pushed and confirmed.

```bash
bd dolt pull            # claiming against stale state is the bug
bd update <id> --claim  # the id you chose; idempotent if already yours
bd dolt push
```

**Claim by id, never `bd ready --claim`.** `bd ready` has no `--id` filter,
and `--claim` takes whatever is first *at claim time* — which need not be
the item you just read and decided to work. Select with `bd ready ... --json`,
then claim that specific id with `bd update`.

## If the push fails

A failed push is not automatically a lost race — the remote may simply be
unreachable. Distinguish the two, because treating an outage as a race makes
both machines conclude they won:

```bash
bd dolt pull            # must succeed; if this also fails, it's an outage
bd show <id> --json     # then read .assignee
```

- **The pull failed too** → the remote is unreachable. Stop and report. Do
  not proceed on an unconfirmed claim: with no sync, the other machine can
  hold the same item and neither will ever know.
- **The pull succeeded and `assignee` is you** → you won. Proceed.
- **`assignee` is someone else** → you lost. Don't retry-push or fight over
  it; select a different item. Give up after 3 rounds and report.

## What a claim does and doesn't protect

`in_progress` + `assignee` is the lock. It works **between machines**,
because their `BEADS_ACTOR` values differ.

It does **not** distinguish two overlapping runs on one machine — they share
an actor, and there is no session marker to tell them apart (`CLAUDE_SESSION_ID`
is not present in the shell environment, so anything built on it would be
silently empty). Two protections cover that instead, both in SKILL.md: a
run refuses to resume an epic touched in the last 15 minutes, and the
startup sweep only releases claims older than 6h, so a live run's items are
never taken from under it.
