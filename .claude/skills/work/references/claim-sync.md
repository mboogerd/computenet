# Claiming safely

You (the orchestrator) claim every item — epics, features, and tasks.
Subagents are handed ids that are already claimed and never claim their own.

**Read this before you decide a claim is safe.** Claim safety here is weaker
than it used to be, and the weakening is deliberate. It is worth knowing the
exact shape of what it no longer covers, because the failure is silent.

## What actually happens now

A session touches the shared tracker twice:

```bash
bd dolt pull            # SKILL.md step 3, session start
...                     # every claim, close, park, metadata write — all local
bd dolt push            # SKILL.md step 6, Finalize
```

That is the whole of it. There is **no per-claim push, and no confirm cycle**.
Claiming used to push immediately and then re-read the assignee to see who
won; that protocol is gone. Per-session sync cost was `count × ~34s`, and the
count was 10 in a minimal session (`doc/ops/beads-sync-cost.md`); it is now 2.

**Claim by id, never `bd ready --claim`.** This part did not change and still
matters. `bd ready` has no `--id` filter, and `--claim` takes whatever is
first *at claim time* — which need not be the item you just read and decided
to work. Select with `bd ready ... --json`, then claim that specific id with
`bd update`.

## What a claim still guarantees

- **This machine's own work.** `bd list --status=in_progress
  --assignee="$BEADS_ACTOR"` reads the local DB, which is authoritative about
  what this machine did. Resuming your own epic, finding your own in-progress
  feature, and the stale-claim sweep all work exactly as before — none of them
  ever needed the network.
- **Anything the other machine pushed at its last Finalize.** The step-3 pull
  brings those claims in. If machine B claimed an epic and finished a session
  since your last start, you see that claim and skip the epic.
- **Two overlapping runs on the *same* machine** are handled without sync at
  all, by the two guards in SKILL.md: a run refuses to resume an epic touched
  in the last 15 minutes, and the sweep only releases claims older than 6h, so
  a live run's items are never taken from under it. (They share a
  `BEADS_ACTOR` and cannot tell each other apart — `CLAUDE_SESSION_ID` is not
  in the shell environment, so anything built on it would be silently empty.)

## What it no longer guarantees

**Two machines starting slots between each other's Finalize pushes can both
claim the same item, and neither finds out during the session.**

Concretely. Machine A starts at 02:00, pulls, sees `computenet-xyz` unclaimed,
claims it, and works it for five hours. Machine B starts at 02:20, pulls — A's
claim is still sitting in A's local DB and will not be pushed until 07:00 — so
B also sees `computenet-xyz` unclaimed, claims it, and works it too. Both
sessions run to completion believing they own it. The claim is not a lock
during the window; it is a record that becomes visible later.

**The window is the whole session**, up to the slot length (~5h), not a few
seconds. It is widest for a fresh epic taken from `bd ready` at step 3, since
that is the only decision made purely from just-pulled shared state.

## What the collision looks like when it surfaces

You will not see an error. You see, at the next pull or after the nightly job:

- **One item, two sets of children.** Both machines ran a breakdown, so the
  epic has two near-duplicate feature sets, or the feature has two task sets
  with different ids describing the same work.
- **Two branches and two PRs for the same id shape** — `feature/<id>` exists
  on both machines' pushes, or two PRs titled from the same feature.
  `metadata.branch` / `metadata.pr` holds whichever value the *later* Finalize
  push wrote; the earlier machine's PR url is simply gone from the tracker
  while its PR is still open on GitHub.
- **`assignee` naming one machine while the other machine's worktree is full
  of committed work for it** — last-write-wins on `updated_at` picks a winner
  silently.
- **A `bd dolt pull` that aborts with `merge conflicts in issues require
  operator resolution`** — the loud version, since the two sides edited the
  same rows. `bd` has no conflict-resolution subcommand; the resolution goes
  through the `dolt` CLI directly (see `doc/ops/beads-sync-cost.md`, which
  transcribes one that happened).

A human spots it by looking for duplicate work rather than for an error:
`bd list --parent=<epic> --all` showing two features with the same intent, or
`gh pr list` showing two open PRs for one feature id. Recovery — which branch
survives, which duplicate ids get closed — is a human call, not something a
session should attempt mid-run. It is documented in
`doc/ops/beads-sync-runbook.md`.

**If you find one, stop working that item and park a question**
([ask-human.md](ask-human.md)). Do not pick a winner yourself: the losing side
may hold committed, pushed, unreviewed work.

## The two surviving sync sites fail loudly

There is no retry protocol left to run, so both sites report rather than
recover:

- **The step-3 pull fails** → **stop the session and report.** Do not proceed
  on stale state. Without that pull you cannot see the other machine at all,
  and you would claim from a snapshot of unknown age. This is the
  computenet-kg7 / computenet-3v8 lesson: a session once ran a whole slot
  against a local-only DB with claim safety silently gone, and the cost was
  the run's state.
- **The Finalize push fails** → **say so at the top of the session summary,
  and never swallow it.** The session's entire tracker state — claims, closes,
  parked questions, friction issues — is local-only until the nightly job or a
  human pushes it. Until then the other machine sees this epic as it was at
  step 3, which widens exactly the window described above, and losing this
  machine loses all of it.
