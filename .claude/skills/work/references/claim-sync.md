# Claim, synced

Goal: never let two machines end up working the same item. Beads' Dolt sync
is git-speed, not realtime, so "claim" is not safe until it's pushed and
verified — a local `--claim` alone is not enough.

```bash
bd dolt pull
```

Pull first. Claiming against stale state is how two machines both see an item
as open.

If you were given a **preferred theme** (an epic/feature id — the session
orchestrator that dispatched you passes this to keep the session on one
epic), try that scope first:

```bash
bd ready --parent=<theme-id> --claim --json
```

If that returns empty, report back "nothing claimable" — do **not** fall
through to the global claim. A dry theme means the session's epic is
exhausted, and the orchestrator ends the session rather than switching
epics. The global claim below is only for when you were given no theme at
all (the first item of a session).

If `BEADS_EXCLUDE_OWNER_LABELS` is set (comma-separated `owner:<machine>`
labels — configure it per machine alongside `BEADS_ACTOR`, listing every
*other* machine's label), exclude them so you never pick up work under an
epic another machine already owns:

```bash
bd ready --exclude-label="$BEADS_EXCLUDE_OWNER_LABELS" --claim --json
```

Otherwise, or if that variable is unset:

```bash
bd ready --claim --json
```

`--claim` is atomic *locally*: it claims the single highest-priority ready
item (assignee → you, status → in_progress) in the same call that reads it.
Sort policy defaults to priority, which is what we want. If this returns an
empty list, there is nothing claimable — stop, nothing else to try.

Take the `id` from the result. **If it's a `task`, `bug`, or `chore`** (not
`epic`/`feature` — see the note at the bottom), stamp it with this session's
id before pushing, so a `SessionEnd` release later can tell "my claims from
this run" apart from "my claims from an overlapping, still-running run" of
the same actor:

```bash
bd update <id> --set-metadata session=$CLAUDE_SESSION_ID
```

Then push immediately — before any real work starts:

```bash
bd dolt push
```

### If the push succeeds

You have it. Confirmed. Move on to the reference for the item's type.

### If the push fails (non-fast-forward / conflict)

Someone else pushed in between — possibly a competing claim on the same item,
including from an overlapping run of *your own* session if a previous slot on
this machine overran into this one.

```bash
bd dolt pull
bd show <id> --json   # check .assignee, .status, and .metadata.session
```

- If `assignee` is you, `status` is `in_progress`, **and** `metadata.session`
  is `$CLAUDE_SESSION_ID`: you won the race after the merge. Proceed.
- Otherwise — assignee is someone else, *or* it's you but the session id
  doesn't match: you lost. Same actor with a different session id means an
  overlapping run of yours got there first; treat it exactly like losing to
  another machine. Do **not** retry-push or fight over it. Go back to
  `bd ready --claim --json` for a different item (the lost one is no longer
  "ready" so it won't be offered again).

Retry this whole sequence up to 3 times. If you're still losing every race
after 3 attempts, stop and report it — that's unusual enough to be worth a
human noticing, not something to loop on indefinitely.

### Epics and features don't get the session stamp

Don't run `--set-metadata session=...` when the claimed item's type is
`epic` or `feature`. A machine's `SessionEnd` hook automatically releases
`in_progress` items it stamped with the terminating session's id — but epic
and feature ownership (`in_progress` + the `owner:<machine>` label, see
[epic.md](epic.md)) is meant to persist across every session until the whole
epic/feature is done, not just this one. Stamping them would make the very
next session-end silently un-claim the epic and reopen it to the other
machine, defeating the whole point of epic-level exclusivity.
