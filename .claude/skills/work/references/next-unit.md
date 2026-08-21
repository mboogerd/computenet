# 5f — next feature, or wait, or stop

The routing table for what a session does when the unit it was working
finishes. Read top to bottom and **take the first route that applies**, with
the one exception route 2b names for itself. SKILL.md 5e sends you here.

Route 0 is not part of that cascade — it answers a different question (a free
lane while a unit is still running) and is reachable from step 5 as well.

Take the first that applies. **Routes 1, 3 and 4 are all closed after
T-90m** — new work you can't review and merge before the slot ends is a
stranded branch.

**0. A free capacity lane while the current unit is still running.** This one
is **not part of the cascade** and is not reached by falling through 5f: step
5 sends you here directly, from the two places you stand when a lane frees
under you — "one feature at a time" and the direct-child loop. Read it there
and come back; a session that waits for 5f to become reachable idles half the
machine for the length of every unit on a one-item-wide epic
(computenet-0a76). **A second unit may run concurrently, under all four of
these:**

- it is **within machine capacity** (`next-batch.py`'s cap counts every live
  agent, not just this batch);
- its `metadata.files` claim is **disjoint from every running unit's** — and
  from their acceptance cross-references, per 5b;
- **build contention is part of the test, and `metadata.files` cannot
  answer it.** Every implementer runs the repo-wide `./gradlew test` gate
  (task.md step 6 via AGENTS.md), so **any two concurrent implementers
  contend on the whole build** — shared caches, daemons, `buildLogic.lock` —
  whatever their claims name. A docs-only or leaf-module claim does not
  exempt one: a single-markdown unit's gate timed out `InspectorEventsTest`
  at load 13 while its sibling built `:bench` (computenet-xbkm). Two
  implementers run together only when the dispatch prompt **scopes the
  gate** — a docs-only unit is told to skip `./gradlew test` and why, or the
  contention is accepted and the prompt says a timeout in an untouched module
  is load, to re-run in isolation before reporting. Otherwise the second
  lane carries reviewers or non-Gradle work;
- it lands on **its own branch and PR** — never a second unit merging into a
  feature branch another unit is still using, which is 5c's serialized merge
  and does not survive two writers.

Any one failing → **leave the lane idle deliberately, and say so** on the
epic, rather than letting it read as an oversight. Two kernel-lane units at
capacity 2 is the case the strict rule is right about.

**Where the second unit comes from, on the shape that produced this.** A ready
surface exactly one item wide means this *epic* has nothing else — so the
candidate is routes 3 and 4's pool (a cross-epic blocker of this epic's
remaining work; otherwise continuation work), taken under their acquisition
bracket and their admission gates, and closed after T-90m like they are.
Read them now rather than on the next pass. Nothing there survives the four
tests → the lane stays idle, and that is the answer, not a gap. Record the
call on the epic **at the time**, either way (computenet-0a76 did, and that is
why it is auditable).

**1. Another feature under this epic is ready or in progress** (and not
recently parked) → 5a. **A ready SUB-EPIC child counts as workable surface
here too**, but it goes to step 4's breakdown under step 3's no-claim rule,
never to 5a — it is not a feature to implement.

**2. The only remaining work depends on a feature this session just marked
ready.** Features branch from `origin/main`, so one sees another's work only
once it *merges*. Poll in the foreground, 60s apart (a floor — your polls
and any monitors share the machine's sockets), max 30 rounds or until T-45m.
A failed `gh` call is not a `state` reading — print the error, retry the
round. `DIRTY`/`BEHIND` → resolve per 5e (waiting on a conflict only you can
clear is deadlock). `MERGED` → `git fetch origin main`, start. `CLOSED` or
cap reached → park a question, continue down this list. Never background an
unbounded loop — a PR on a red check stays `OPEN` forever.

**2b. The feature you are ON is un-completable because one of its tasks is
blocked by a SIBLING feature of this same epic.** This is the near-miss route
5b's `blocked` verdict used to send to a park, stranding an almost-finished
feature for the whole session (computenet-c0uf). The blocker is *inside* this
epic, so it is yours to clear:

**Check this one before route 1**, the single exception to "take the first
that applies". Route 1's "another feature under this epic is ready" *also*
matches the blocking sibling, so taking it that way starts the blocker with
no budget test and leaves the near-complete feature parked with no handoff
comment — which is the whole failure computenet-c0uf recorded.

- **Park the near-complete feature with a handoff comment** naming the blocking
  feature by id, which of its outputs is needed, and what state the parked
  feature is in (which tasks closed, what remains). A park whose comment does
  not name the unblocker is how the next session re-derives all of this.
- **Then look at the blocking feature.** Fits the remaining budget with margin
  — route 4's test, read on its estimate, not on elapsed fraction — → work it
  via 5a; clearing it is what unparks the first.
- **Doesn't fit → BREAK IT DOWN without claiming it**, so the next session
  starts from tasks rather than from an unsplit feature. A *feature* breakdown
  involves no claim in either direction: 5a's `bd update --claim` is what you
  are declining to do, and no epic claim is in question (a feature is not an
  epic). Leave it `open`, unassigned, with its new children under it.

**A breakdown is admissible late in a slot when an implementation is not.** It
creates no branch, no worktree and no PR, so it cannot strand — which is the
whole reason routes 1/3/4 close at T-90m. So the T-90m "start no new work" bar
does not catch it, and a breakdown between T-90m and T-45m is a legitimate use
of the tail of a slot where starting an implementation is not. **T-45m still
binds**: it closes *dispatches*, and a breakdown is one — past T-45m, park the
feature with its handoff comment and go to Finalize.

**3. The epic's remaining work is blocked solely by an item in a different
epic** → claim and work **that item** (not its epic; this adds no epic
claim), unless the SDLC exclusion catches it. The claim is an acquisition —
bracket it: `bd dolt pull`, re-verify still ready and unclaimed, claim by id,
`bd dolt push`. And because a concurrent session's *child* claims are local
while its epic is open, **check the item's epic first** — an epic claim is
always pushed, a child claim only once its epic closes (5b):

```bash
.claude/skills/work/scripts/epic-of.sh <candidate-id>
bd show <that epic> --json | sed -n '/^[[{]/,$p' | jq -r '.[0] | "\(.status) \(.assignee) \(.updated_at)"'
```

- open or in_progress with the other machine's assignee, or *any* status
  touched within 15 minutes → treat as live; take the next candidate.
  **This branch has no age test, and that is deliberate** (computenet-9ynn).
  Step 3 will *take over* an open epic untouched for 15 minutes, so 5f is
  strictly stricter than step 3 for the same state — an epic a dead machine
  abandoned hours ago is claimable at step 3 but its children are skipped
  here. The asymmetry is kept because the two branches guard different
  things, not because one is merely more cautious — both claims are pushed
  acquisitions, so "announced by a push" is not the discriminator
  (claim-sync.md brackets 5f routes 3–4 exactly like step 3). Two reasons:

  - **Step 3's age test only ever runs on an epic that is already
    `open`.** `claim-epic.sh` refuses an `in_progress` epic outright, so
    there the 15 minutes are a *secondary* guard on a claim that was
    already released. Here the branch is the *primary* guard — nothing else
    stands behind it.
  - **The epic's `updated_at` is not evidence of deadness under an open
    epic.** Owned-territory writes stay local until Finalize, so from
    another machine's view the timestamp freezes at claim time and every
    live multi-hour session reads as stale within 15 minutes. And the
    holder's *child* claims are local by design (claim-sync.md, "…except on
    5f routes 3–4"), so pulled state shows those children unclaimed. An age
    test here would therefore declare essentially every live epic dead and
    hand its unclaimed-looking children to a second machine — removing the
    only visible protection they have.

  The cost of leaving it is a machine declining work it is entitled to; the
  cost of closing it is two machines on one item. Take the next candidate
  and let step 3 reclaim the epic on the next session.
- closed, older than 15 minutes → the assignee is provenance, so read the
  **candidate's own** `status`/`assignee` instead and skip it if another
  machine holds it. That reading is trustworthy here and only here: 5b makes
  a child claim under a closed epic an acquisition, so it was pushed. The
  15-minute floor stays as the guard for the seconds between that claim and
  its push, and for a session that died in between.
- `(unparented)` → safe: an unparented item can't be someone's local child
  claim, so any competing claim was itself pushed.

If you later find a sibling PR touching your item's files, stop and park —
don't pick a winner; the losing side may hold pushed, unreviewed work.

**4. The epic is dry but real budget remains** → continuation work, claimed
by specific id (same acquisition bracket as route 3; never a second *epic*).
If the epic went dry because everything left is human-gated or cross-epic
blocked, also `bd defer` it (step 3's route) so the next session doesn't
resume a dead queue.

Build the pool from `bd ready --json`: features and tasks of other epics,
plus every bug and chore with **no epic ancestor** — `epic-of.sh <id>`
answering `(unparented)`, which is the test, not "has no `parent` field".
The two differ: a residual filed by a previous session's reviewer is parented
to the unparented item it came out of (review-feature.md §7), so it *has* a
parent and still has no epic. Filtering on the raw `parent` field drops
exactly the review-discovered work continuation is meant to pick up, and
nothing else would ever find it (computenet-wpvy.42). Drop: `human`-labeled; SDLC-excluded (both
halves of the test); `parked_at` within 6h; **anything that is review or
verification of this session's own output** (warm context makes
self-approval likely — excluded whatever its score); anything whose
`metadata.files` overlaps a claim already `in_progress`. Order the rest:
direct dependents of this session's completed items; file-surface overlap
with this session's pushed branches (`git diff --name-only
origin/main...<branch>`, never titles); ready bugs/chores with no epic
ancestor; general ready order — ties break by the next criterion down.

**A gated item: the discriminator is WHO MAY DECIDE, not what it costs.**
Read the gate in two places, because they hold different ones — the bead's own
text, *and* the contract of the file it touches (`concord/schema/*.md` states
its own gate in its header). Then:

- **A gate on the FORM of a change** — "must be its own ticket", "not to be
  done opportunistically", "schema changes are gated" — constrains *how*, not
  *whether*. An unattended session satisfies it by filing such a ticket and
  working that: workable.
- **A gate that requires an ANSWER from a person** — a preference, a policy
  call, a tradeoff nobody has made — is an `ask-human.md` park, **whatever it
  costs**. Cheap does not make it yours to decide.
- **An open DESIGN question is neither.** A design question a dispatched agent
  can settle *on evidence* and a reviewer can check is workable — settle it,
  and record which way it went and why. Rule 3 (`ask-human.md`'s bar) applies
  only when the evidence does not settle it.

**Record the decision on the bead either way** (`bd comment`), so the next
session does not re-litigate it — and so a park that turns out to be wrong is
visible *as a park* rather than as silence (computenet-bypi).

Three admission gates:

- **Its stated blocker or precondition still holds, checked against the
  artifact it names** — 5b's rule, and it bites hardest here, where nothing
  broke the item down and its "blocked until X lands" may be days old
  (computenet-rjyl). A commit subject line is not the check.

- **The item's own compute demand fits the slot.** A bead can demand
  thousands of suite runs while your dispatch prompt forbids starving the
  machine; you are the only party seeing both (computenet-9vt). Read the
  acceptance/TEST clauses, multiply sample by per-run cost. Doesn't fit →
  say so in the dispatch prompt in as many words (run what fits, file the
  rest as follow-up) and `bd update <id> --set-metadata compute=dedicated`
  so a later session routes it to a dedicated slot. Never leave the
  collision for the implementer to discover.
- **Its 45–60m estimate fits the remaining budget with margin** (15 min
  default; `WORK_CONTINUATION_MARGIN_MIN` overrides). Never admit on elapsed
  fraction alone — that converts idle time into half-finished branches,
  which is worse than idling.

**A directly-filed bug or chore usually has no acceptance criteria** — nothing
broke it down, so nobody wrote them. Write them onto the bead before you
dispatch (`bd update <id> --acceptance=…`, to
[issue-quality.md](issue-quality.md)'s standard), and
say in the dispatch prompt that they are yours. Skip this and the reviewer
invents the bar it then certifies against, which is marking its own paper; and
because the bar then lives only in a dispatch prompt that is discarded, a
resumed item gets a different one (computenet-n58c). This is orchestrator work
under the authorship rule above — it is a claim about what "done" means, and
it gets a reviewer like anything else you write.

Work the admitted item by shape: feature via 5a; a task via its parent
feature's flow; an unparented bug/chore as its own worktree/branch/PR like a
feature. Every shape records `branch`/`worktree` metadata, so an overrun
leaves resumable state, never a stranded branch.

**5. Nothing can progress → Finalize.**
