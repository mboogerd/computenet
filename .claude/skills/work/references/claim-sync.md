# Claiming safely

You (the orchestrator) claim every item — epics, features, and tasks.
Subagents are handed ids that are already claimed and never claim their own.

Beads' Dolt sync is git-speed, not realtime, so a local `--claim` proves
nothing on its own. A claim is safe only once pushed and confirmed.

```bash
bd dolt pull                       # claiming against stale state is the bug
bd ready <filters> --claim --json  # atomic locally; picks highest priority
```

For a **task/bug/chore**, stamp it with this session before pushing:

```bash
bd update <id> --set-metadata session=$CLAUDE_SESSION_ID
```

Then push, before any work starts:

```bash
bd dolt push
```

**Push succeeded** → it's yours. Proceed.

**Push failed** (non-fast-forward) → someone pushed in between. That someone
may be another machine, or an overlapping run of *your own* session if a
previous slot overran into this one.

```bash
bd dolt pull
bd show <id> --json     # .assignee, .status, .metadata.session
```

You won only if `assignee` is you **and** `metadata.session` is
`$CLAUDE_SESSION_ID`. Anything else — including your own actor with a
different session id — means you lost. Don't retry-push or fight over it;
claim a different item. Give up after 3 rounds and report it.

## Never stamp an epic or feature

The `SessionEnd` hook reopens `in_progress` items carrying the terminating
session's id. That is exactly right for tasks and exactly wrong for epics and
features: their claim must outlive the session, because staying `in_progress`
is the only thing keeping the other machine out (`bd ready` skips
`in_progress`). Stamp one and the next session-end hands your epic away.
