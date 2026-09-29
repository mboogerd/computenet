# Eisenhower backlog triage over ComputeNet's own queue

Phase 1 of using `:demo:alignment` to triage ComputeNet's real development
backlog (feature `computenet-i00bh`). The alignment demo already had the hard
parts — multi-dimension rating, `value × factor ÷ cost` scoring, a
Bradley–Terry pairwise→rating bridge, disagreement surfacing, a facilitator
override. This adds the three integrations it was missing: real candidates in,
machine raters alongside the human, and a bias-safe worklist a machine can walk.

## What it is

One **standing round** — a single topic, `triage`, re-seeded in place — over the
**ready epics** of a real beads workspace, rated on the two **Eisenhower** axes:

| dimension | direction | weight | means |
|---|---|---|---|
| `importance` | VALUE | 1.0 | peripheral → critical |
| `urgency` | VALUE | 1.0 | can wait → needed now |

The score is their weighted mean, which is the right single ordering. The 2×2 it
necessarily flattens comes back as `quadrant` on every `/aggregate` row:
`do` (important + urgent), `schedule` (important, not urgent), `delegate`
(urgent, not important), `drop` (neither), or `null` while either axis is
unrated.

Three rater classes, all **ordinary alignment participants in one ratings map**:

- **human** — the Rate view's sliders, or the Compare view (place every idea on
  one axis at once; much faster for a whole backlog).
- **coding agent** — pairwise, `POST /topics/triage/judge`, which `PairwiseFit`
  turns into that agent's own `[1, 9]` ratings.
- **Jev** — a direct scalar per (idea, dimension), under the participant name
  `jev`, written at seed time.

There is no separate aggregation path for machine judgement and no weighting of
machines against people. A human disagreeing with Jev shows up exactly as two
humans disagreeing: a spread of 2.0 or more on one (idea, dimension) sets
`split` and the idea joins the board's Discuss group.

## Run it

Read-only against the tracker. The service never claims, closes or imports —
`/work` remains the only thing that writes to beads.

```bash
# from the repo root; :run's working directory is the subproject, like
# backlog-triage's own `--seed ../../backlog`
./gradlew :demo:alignment:run --args="8097 --seed-beads ../.. --journal $HOME/.local/state/computenet-triage/triage.jsonl"
```

Then open `http://localhost:8097/t/triage`.

`--seed-beads <workspace>` runs `bd -C <workspace> ready --type=epic --json`,
creates the topic if absent, upserts one idea per ready epic **keyed by the bead
id verbatim** (`computenet-8x9`, already a valid slug — so Phase 2 maps an
ordered board back onto `bd` ids with no lookup table), and writes Jev's
ratings. It is idempotent: an unchanged tracker appends nothing to the journal,
so it is safe on every boot.

A candidate that *leaves* `bd ready` (closed, deferred, newly blocked) keeps its
row rather than being removed — `removeIdea` cascades through every
participant's ratings and judgements, and that is irreversible human input.

### In its own worktree

The round outlives any one session, so it gets a dedicated worktree that is not
a `/work` feature worktree and has no bead of its own:

```bash
git worktree add ../computenet-worktrees/triage origin/main
```

Keep the **journal and run-dir outside the tree** (`~/.local/state/computenet-triage/`
above) so `git status` in the worktree stays clean and rebuilding the worktree
never loses the round.

**Neither `/work` sweep reaps it** — checked against both scripts at
`195d92ad`, rather than assumed:

- `reclaim-worktrees.sh` enumerates `git worktree list`, then skips any
  worktree whose basename does not match `computenet-*` (its own line 118) and,
  past that, requires a **closed bead** of that id. `triage` fails the first
  test and has no bead behind it, so it is doubly out of scope; a `--dry-run`
  confirms the path is never mentioned.
- `sweep-merged-prs.sh` is driven by beads' `metadata.worktree`, so it only
  touches worktrees a bead names. No bead names this one.

That is a property of the *name*, not of the directory: calling it
`computenet-triage` would put it in reclaim's id space and make it depend on
no bead of that id ever being closed. Keep the plain name.

## The agent loop

Each agent picks one stable kebab-case handle naming its perspective
(`api-ergonomics`, `runtime-risk`) and one dimension, then loops:

```bash
BASE=http://localhost:8097

# the bias-safe worklist: no rank, score, quadrant, means or other participants
curl -s "$BASE/topics/triage/worklist?participant=YOUR-NAME&dim=importance"
# → {"ideas":[{"id":"computenet-8x9","title":"…","description":"…","mine":0,"rated":null}, …],
#    "next":{"a":"<id>","b":"<id>"},   ← a pair YOU have not judged
#    "judgements":[…],                  ← YOUR judgements on this dim only
#    "phase1Complete":false}            ← true once every idea is in ≥ 2 of yours
# ideas are ordered least-judged-by-you first, shuffled within ties.

# judge the suggested pair: "a" | "b" | "equal", from YOUR perspective
curl -s -X POST "$BASE/topics/triage/judge" \
  -d '{"participant":"YOUR-NAME","dim":"importance","a":"<id>","b":"<id>","outcome":"a"}'
```

Judge independently until `phase1Complete`, then read
`GET /topics/triage/aggregate` and spend a handful of high-conviction
judgements on orderings you disagree with — the same two-phase discipline
`demo/backlog-triage/AGENT-PROMPT.md` sets out.

`/worklist` is per dimension because judgements are, and it exists because
`/me` is a read of one participant's own state in board order: it tells an agent
nothing about *where* to spend its next judgement.

## The Jev design, and why it is arithmetic

`demo/deliberate`'s `JevJudge` calls TypeSafe System One over HTTP and needs
`TYPESAFE_API_KEY`. This one is **built fresh, self-contained, and makes no
external call** (user decision, 2026-09-29).

The Eisenhower axes are the one place where that costs nothing, because **beads
already carries the signal**. Importance and urgency here are questions about
the tracker's own graph and timestamps, not about the world. A model asked "is
`computenet-8x9` important?" would be guessing at `dependent_count` from prose;
reading it is cheaper *and* more accurate. So the judge is arithmetic over the
fields `bd ready --json` already returns — deterministic, free, offline, and
unit-testable against stated numbers rather than a recorded fixture.

With `p = (3 − priority) / 3` (P0 → 1.0, P3 → 0.0),
`d = min(dependent_count, 5) / 5`, and `a = min(age_days, 60) / 60`:

| axis | formula | dominated by |
|---|---|---|
| `importance` | `1 + 8·(0.35·p + 0.65·d)` | `d` — structural consequence |
| `urgency` | `1 + 8·(0.65·p + 0.35·a)` | `p` — declared priority |

Each term is in `[0, 1]` and each axis' weights sum to 1, so both outputs are in
`[1, 9]` by construction with no clamping. The two axes are deliberately **not**
both driven by `priority`: that would collapse the 2×2 onto its diagonal and
make the matrix decorative. `TriageTest` asserts that two candidates with equal
priority and opposite dependent/age profiles land in *opposite* quadrants.

Reading of each term, so a human who disagrees knows what they are arguing with:
`d` is *how much this unblocks* — an epic five others wait on is important
whatever its label says. `a` is *cost of delay accrued* — an item untouched for
two months is treated as **more** urgent, because neglect is the failure this
board exists to surface.

**Abstention is a real answer.** With no usable priority, `Jev.rate` returns
null and the seeding path writes no rating, leaving the slot *absent* — which is
alignment's honest unrated state, never a middling 5. That mirrors the real
`JevJudge`'s `knowledge` gate returning `OUTSIDE_KNOWLEDGE` whatever the score
said: a judge that cannot see the input should decline, not average.

**The calibration knob** is `Jev.DEPENDENT_CAP` and `Jev.AGE_CAP_DAYS`, exposed
rather than inlined. They are judgements about *this* backlog's shape — how many
dependents count as "a lot", how long is "stale" — and the first real round is
expected to move them. Changing one re-rates Jev on the next seed and leaves
every human rating untouched.

## Deliberately not in this phase

- **The other four dimensions.** readiness (as a non-compensatory `FACTOR`),
  unlock, effort, risk, kernel-truth, demo-leverage are designed and deferred. A
  facilitator can still add any of them at runtime through
  `POST /topics/triage/dimensions`; the score then becomes their weighted
  combination and `quadrant` keeps reading only importance and urgency.
- **`/work` integration** (Phase 2). Nothing consumes the order yet; it is read
  by a human. When it lands, `/work` step 3 gains a `triage-order.sh` between
  `resumable-epics.sh` and `bv --robot-triage`, and that script must bound
  staleness loudly — a stale round silently steering the queue is worse than no
  triage, the same hazard class as AGENTS.md's frozen-`bv`-export warning.
- **The `:demo:beadsmirror` candidate source** (Phase 3). `CandidateSource` is
  the seam for it: the mirror's `ReadySetCell` is already differentially tested
  against `bd ready --json`, so that is a new binding and no edit here. Its
  prerequisite is a decision, not code — `BeadsMirrorApp`'s `refuseIfLiveBeads`
  refuses this repo's live `.beads` in *every* mode, so the mirror path needs to
  poll a `bd dolt clone` rather than the live workspace.

## Tests

```bash
./gradlew :demo:alignment:test --rerun
```

`TriageTest` covers the pure units — the `bd ready` parse (both JSON shapes,
`bd`'s preamble, dropped and defaulted rows), Jev's arithmetic (the worked
corners, scale bounds, cap saturation, abstention, axis independence) and the
2×2 (corners, the midpoint rule, null while unrated). `TriageBoardTest` covers
the board end to end against a fixture `CandidateSource` — seeding, bead-id
keying, Jev's abstention arriving unrated, journal-silent re-seeding, a departed
candidate keeping its ratings, the three rater classes in one population with
`split` and `override`, the worklist's bias-safety and coverage ordering, and
restart over a journal.
