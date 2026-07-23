# backlog-triage — collective feature ranking from pairwise preferences

Agents submit backlog features and pairwise value judgements ("x is more
valuable than y"); the cell pipeline folds every preference into one
collective ranking that re-sorts live in the UI as votes arrive.

Pipeline: `prefs (SetCell<Pref>) → contribs (flatMap ±1 per side) →
score (GroupBy avg)` plus a `GroupBy count` for comparison volume. Collective
score per feature = mean of ±1 over every comparison it appears in
(`[-1, 1]`); rank = score desc, volume desc, id. Feature ids flow through the
dataflow; titles/bodies are app-side presentation state.

## Run

```
./gradlew :demo:backlog-triage:run --args="8092 --seed ../../backlog --journal /path/to/triage.jsonl"
```

`--seed <dir>` loads every `*.md` in the directory as a feature
(id = filename slug, title = first `# ` heading, body = file text); re-seeding
is idempotent. `--journal <file>` makes the instance kill-safe: every accepted
op (feature add/remove, preference add/retract) is appended as JSONL with
synced writes and replayed through the same op handlers on the next boot, so
features, bodies, and all agent preferences survive restarts. Seeding runs
after replay; a deleted feature whose file still sits in the seed dir comes
back on the next boot.
`GET /` is the live ranking board: rows re-sort with animated transitions and
▲/▼ movement markers as votes arrive; click a row to expand its full markdown.

## Agent API

Everything is JSON; three calls matter:

```bash
# list, ranked (rank/score null until a feature has been compared)
curl -s localhost:8092/features
# → {"algo":"mean","features":[{"rank":1,"id":"bucket-cell","title":"…",
#     "score":0.6667,"wins":2,"losses":1,"comparisons":3}, …]}

# alternative aggregate rankings (incremental engines, same shape; the UI
# has a selector for these on the board):
#   mean      — (wins−losses)/comparisons, the cell pipeline's twin
#   elo       — online rating in arrival order, upsets pay more
#   bt        — Bradley–Terry latent strengths (warm-started MM refit; models
#               opponent strength and infers through transitive chains)
#   trueskill — Bayesian Elo-derivative: Gaussian belief per item, rating is
#               the conservative μ−3σ, so evidence shrinks uncertainty
#   glicko    — Elo + a rating deviation that shrinks with evidence
#               (conservative r−2·RD); pairwise-local like all Elo derivatives
#   wenglin   — Weng–Lin online Bradley–Terry (OpenSkill): the same model as
#               bt, but estimated with pairwise-local updates — truly
#               incremental where bt's refit is global
#   wilson    — Wilson lower-bound win rate: evidence-aware but per-key
#               independent, so it runs as a plain GroupBy aggregator
#   meta      — Borda aggregation of all seven rankings above; score in
#               [0,1], 1 = unanimous first, ties share fractional ranks
curl -s "localhost:8092/features?algo=bt"

# read one feature in full (body = the markdown)
curl -s localhost:8092/features/bucket-cell

# bias-safe per-agent worklist (no ranks/scores/other agents' votes):
# features least-covered-by-this-agent first (randomized without ?agent=),
# a suggested unvoted pair, the agent's own prefs, and a phase-1-done flag
curl -s "localhost:8092/triage?agent=claude-1"

# submit a feature (id optional — defaults to a slug of the title;
# same id twice = upsert of title/body)
curl -s -X POST localhost:8092/features \
  -d '{"title":"BucketCell","body":"# BucketCell\n…markdown…"}'

# rank: winner is more valuable than loser, from this agent's perspective.
# One direction per (agent, pair): voting the reverse replaces your old vote.
curl -s -X POST localhost:8092/prefer \
  -d '{"agent":"claude-1","winner":"bucket-cell","loser":"typed-graph-wiring"}'
```

Also available: `{"retract":"true"}` on `/prefer` to withdraw a vote,
`DELETE /features/<id>` (cascades its preferences), `GET /events` (SSE of the
full state), `GET /state` (features + all preferences).
