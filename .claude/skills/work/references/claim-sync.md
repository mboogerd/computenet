# Claiming safely

You (the orchestrator) claim every item — epics, features, and tasks.
Subagents are handed ids that are already claimed and never claim their own.

## The principle

**Sync brackets acquisition, not writes. Ownership makes writes free.**
(Decided 2026-08-13, computenet-wpvy.3, superseding the exactly-two-syncs
rule — which dated from ~10-minute round-trips; a round-trip is now ~30s,
`doc/ops/beads-sync-cost.md`.)

- **Owned territory** — items under the epic you claimed, items you
  claimed — is yours to write locally. Closes, parks, metadata, breakdown
  children: no per-write sync. The Finalize push (SKILL.md step 6) is the
  *publication* that carries them off this machine.
- **Acquisition** — claiming an epic (step 3), claiming an item in another
  epic (5f routes 3–4), filing or upvoting under the SDLC epic (step 7),
  stealing a stale claim — is a write to a surface you don't own yet. Each
  gets its own bracket: `bd dolt pull` → verify (still unclaimed? not a
  duplicate?) → write → `bd dolt push`.

The push half of the bracket is what turns a claim from a record into a
lock: the other machine's next pull sees it and stays off. The race window
is the seconds between your pull and your push, not the hours between
session boundaries.

**Claim by id, never `bd ready --claim`.** `bd ready` has no `--id` filter,
and `--claim` takes whatever is first *at claim time* — which need not be
the item you just read and decided to work. Select with `bd ready ...
--json`, then claim that specific id with `bd update`.

## What a claim guarantees

- **Cross-machine exclusivity on acquired surfaces**, up to the seconds-wide
  pull→push window. Two machines racing the *same* acquisition inside that
  window can still both win it; the residue is accepted, and the collision
  signs below are how it surfaces.
- **This machine's own work.** `bd list --status=in_progress
  --assignee="$BEADS_ACTOR"` reads the local DB, which is authoritative
  about what this machine did. Crash-leftover release at startup, resume
  queries, and the stale-claim sweep all work without the network.
- **Two overlapping runs on the *same* machine** are handled without sync at
  all, by the two guards in SKILL.md: a run stops at startup if this machine
  holds an epic claim touched in the last 15 minutes, and the sweep only
  releases claims older than 6h. (They share a `BEADS_ACTOR` and cannot tell
  each other apart — `CLAUDE_SESSION_ID` is not in the shell environment, so
  anything built on it would be silently empty.)

## What it does not guarantee

- **Ownership is a convention, not a fence.** Another agent may legitimately
  write *into* your claimed epic — most commonly filing a story that
  thematically belongs there. Accepted by design: the value of a stable home
  for such items outweighs the occasional surprise child. Consequence: a
  breakdown or close-out query can return children this session didn't
  create; treat them as work, not corruption.
- **A crash between acquiring and Finalize** publishes the acquisition but
  not the work: the claim is visible, the closes and metadata under it are
  not. The other machine sees a claimed epic with no progress — that is what
  a *visible-but-stale* claim means, and why the startup release (step 3)
  and the 6h sweep exist. The git side (pushed branches, PRs) is durable
  independently; tracker and git diverge in that direction only.
- **Owned-territory writes are invisible until publication.** A task closed
  locally at 5c is still open in the other machine's view until Finalize
  pushes. That is fine precisely because the other machine has no business
  inside your claimed epic — the epic-level claim is what it respects.

## What a collision looks like when it surfaces

Rare now, but the signs are unchanged — you will not see an error at claim
time. At some later pull:

- **One item, two sets of children** — both machines ran a breakdown.
- **Two branches and two PRs for the same feature shape** — `metadata.branch`
  / `metadata.pr` holds whichever value the later push wrote.
- **`assignee` naming one machine while the other machine's worktree holds
  committed work for it** — last-write-wins on `updated_at` picked silently.
- **A `bd dolt pull` aborting with `merge conflicts ... require operator
  resolution`** — the loud version; resolution goes through the `dolt` CLI
  (`doc/ops/beads-sync-runbook.md` §3.3). More frequent brackets make these
  conflicts smaller but not impossible.

**If you find one, stop working that item and park a question**
([ask-human.md](ask-human.md)). Do not pick a winner yourself: the losing
side may hold committed, pushed, unreviewed work.

## When sync fails

Every bracket fails loudly; none retries silently:

- **The step-3 pull fails** → stop the session and report. Without it you
  cannot see the other machine at all (the computenet-kg7 / computenet-3v8
  lesson).
- **An acquisition push is rejected** → pull, re-verify the target is still
  yours to take, retry once. Still failing → stop and report; an unpushed
  acquisition is exactly the window the bracket exists to close.
- **The Finalize publication push fails** → say so at the top of the session
  summary and ask for a human to run `scripts/beads-nightly-sync.sh` —
  nothing is scheduled to do it (`doc/ops/beads-sync-runbook.md` §5). Never
  swallow it: unpublished state is local-only and dies with this machine.
