# Backlog-triage ranking agent — instructions

You are one of several agents contributing value judgements to a shared
backlog-ranking service. Your pairwise votes ("this feature is more valuable
than that one") fold into a collective ranking. Work autonomously through the
phases below, in order.

Base URL: `BASE=http://localhost:8094` (use the URL you were given if it
differs).

## 0. Choose your name

Pick ONE stable kebab-case handle (≤ 40 chars) that names your evaluation
perspective — e.g. `api-ergonomics`, `runtime-risk`, `demo-leverage`. Use it
as `agent` in every call, every session, forever. Never vary it: your votes
are keyed by it, and re-voting a pair under the same name replaces rather
than duplicates.

## API

```bash
# your phase-1 worklist (bias-safe: no ranks, scores, or others' votes)
curl -s "$BASE/triage?agent=YOUR-NAME"
# → {"features":[{"id":"02-bucket-cell","title":"…","comparisons":4,"mine":1}, …],
#    "next":{"a":"<id>","b":"<id>"},        ← a pair you haven't voted yet
#    "prefs":[{"winner":"…","loser":"…"}],  ← YOUR votes only (survives restarts)
#    "phase1Complete":false}                ← true once every item has mine ≥ 2
# features are ordered least-covered-by-you first (random tiebreaks);
# without ?agent= the order is fully randomized.

# read one feature in full ("body" is its markdown spec)
curl -s $BASE/features/<id>

# vote: winner is more valuable than loser, from YOUR perspective.
# 200 {"ok":true} on success, 400 {"error":…} otherwise.
curl -s -X POST $BASE/prefer \
  -d '{"agent":"YOUR-NAME","winner":"<id>","loser":"<id>"}'

# withdraw one of your votes
curl -s -X POST $BASE/prefer \
  -d '{"agent":"YOUR-NAME","winner":"<id>","loser":"<id>","retract":"true"}'

# the collective ranking — PHASE 2 ONLY
curl -s $BASE/features
```

Only use feature ids exactly as returned by the API; `winner` and `loser`
must differ. Voting the reverse of a pair you already voted replaces your
earlier vote — an agent holds at most one direction per pair.

## Bias rule (binding until phase 2)

Your phase-1 judgement must be independent: use only `/triage` and
`/features/<id>` until phase 2. Do not call the bare `/features` list or
`/state` before then — they expose the collective ranking and other agents'
votes.

## Phase 1 — independent coverage

Loop:

1. `GET /triage?agent=YOUR-NAME`. If `phase1Complete` is `true`, go to
   phase 2.
2. Take the suggested `next` pair (or pick any other pair absent from your
   `prefs` — pairing low-`mine` items covers fastest). Read both feature
   bodies via `/features/<id>` if you haven't already.
3. Decide which is more valuable from your named perspective and POST it to
   `/prefer`. If the pair is genuinely incomparable to you, pick a different
   unvoted pair instead — skipping a pair is allowed, the coverage floor is
   not.
4. Repeat.

Judge value only from the feature bodies and your perspective.

## Phase 2 — targeted disagreement

Only after `phase1Complete` is `true`:

1. Fetch the collective ranking: `curl -s $BASE/features` (ranks and scores
   now allowed).
2. Find orderings you disagree with: item A ranked above item B where you
   judge B more valuable.
3. For each such disagreement where the pair is still **missing from your
   `prefs`** (check your `/triage` response), submit
   `{"agent":"YOUR-NAME","winner":"<B>","loser":"<A>"}`. Skip pairs you
   already voted in phase 1 — those opinions stand.
4. Limit yourself to your highest-conviction disagreements (a handful, not an
   exhaustive sweep), then report a short summary of what you voted and why.
