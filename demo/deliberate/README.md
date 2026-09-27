# deliberate

Ask a question and watch a deliberation graph grow for it live. Two LLM
CLIs (Claude Code and Codex) propose arguments for and against each claim,
recursively. **Jev** (TypeSafe System One, `jev-latest`) makes every judgment
that steers the exploration: triage of new arguments, their quality and
relevance, saturation, and the stances credence is computed from. A kernel-hosted
argumentation graph, modelled on agora's, propagates credence. You can override the explorer per claim: force it
to expand a claim (it always runs at least one more round, even when the
question's claim budget is spent), or stop it. The goal specification is [`SPEC.md`](SPEC.md).

## Credence model

1. Every claim gets a Jev *plausibility* judgment (five levels, false … true, mapped to [0,1]). It is judged on the claim and the question alone: no path, parent or direction, which a live investigation showed pulling the judgment towards the claim's role in the argument.
2. Every pro/con edge gets a Jev *relation strength* judgment: how strongly the child would bear on the parent if it were true.
   A new argument gets both judgments as soon as its round ends: plausibility in its own request, in parallel with one request that asks its strength, quality and relevance (see *Exploration*).
3. Both judgments are recorded as stances of the user `jev`, on the claim and on the edge (an edge is a claim too: it has its own credence, and an undercutter attacks it).
4. One cell graph propagates credence: supports raise a claim from its plausibility, attacks lower it, and each argument is weighted by its own credence and the strength of its edge. It does so under **seven semantics at once** — every claim and edge cell computes a credence *vector*, one value per layer: `dfquad` (agora's DF-QuAD), `wlo` (weighted log-odds), `jnb` (Jeffrey / naive-Bayes), `woe` (weight of evidence), `euler`, `qe` (quadratic energy) and `mlp`. An edge tells its target both its own credence vector (the strength) and its source's, so every semantics computes its own energy from the two — `jnb` conditions on the source's credence exactly as its definition says.
5. The UI's headline number is the **consensus**: the geometric mean of the odds of the member layers (`wlo`, `jnb`, `woe` by default), with the **spread** (lowest to highest credence over all layers) drawn as a band behind it. Each claim cell derives both from its vector and emits them with it; they only summarise and never feed back into a layer. The deliberation code never propagates credence itself.

## Exploration

Each round asks the proposers for new arguments, then Jev sorts every proposal
in one request (SPEC EXP-03): **add** it, drop it as a **duplicate** (its
proposer is noted on the existing argument), **replace** a weaker wording,
**merge** it with an overlapping argument (Claude rewrites the two as one
sentence), **refine** an existing argument (it is nested under that argument
as evidence), move it to the **other side**, **undercut** an existing
argument (it denies that the argument bears on the claim, so it attacks that
argument's link rather than the claim), or **drop** it as not a real
argument. Rewording and merging only touch arguments nobody explored yet.

Within a round the proposers take turns — Claude, then Codex (the order of
`--proposers`) — and the second sees what the first just contributed, after
triage, so it goes after what is missing. Both write arguments in a
*canonical form*: one checkable proposition, the reason rather than a
conclusion, explicit subject and scope, no hedges, dated only when time
matters, at most 25 words. Saturation is judged after both have had their
say, and a side with fewer arguments than the other is never saturated by
Jev alone (below its cap), so the sides stay balanced.

Every attached argument gets a **contribution**: reach × relevance × quality
(quality is Jev's judgment of whether the argument is well constructed;
canonical form is asked of the proposers, never scored).
Exploration is best-first by contribution across one queue, one round per
task. A claim with rounds left goes back into the queue at
contribution × `--round-decay` per round it already ran, so a strong claim's
second round still beats a weak sibling's first. Arguments below
`--min-influence` are never explored (`PRUNED`), so irrelevant or badly built
ones cost nothing, and the claim budget is spent on the strongest ones first.

Each question also watches its own **returns**. Every non-root round records a
yield — the value of the arguments it attached (strength × relevance × quality),
discounted by the share of proposals triage threw away as repeats or drops,
per argument asked for. Once a question holds 40 claims and 16 non-root rounds,
it stops when its last 8 rounds yielded less than 0.6 × its earlier average
and there is a queued claim to halt: no new round starts, and its waiting
claims end `DIMINISHING` ("returns diminished"). Root rounds are excluded
because their yields are naturally higher. Rounds in flight still attach what
they found. A tree that ran out of work on its own does not report a stop. The question
header then says "stopped: returns diminished" (or "stopped: claim budget
spent" when `--max-claims` ended it). An `EXPAND` still explores a
`DIMINISHING` claim.

## Prerequisites

- JDK 21 (the Gradle toolchain provisions it) and Node 22+ for the UI.
- `TYPESAFE_API_KEY` in the environment. Jev judges every step, and the backend refuses to start without the key.
- The `claude` and `codex` CLIs on `PATH` and logged in. Each proposer call runs one CLI process with no tools, in an empty temp directory, with a 120 s timeout. Use `--proposers claude` or `--proposers codex` to run with just one of them. Merges (above) always ask `claude`; if it fails, the proposal counts as a duplicate.

## Run

```bash
# 1. build the UI once (the backend serves ui/dist at /)
cd demo/deliberate/ui && npm install && npm run build && cd -

# 2. start the backend (default port 8091)
./gradlew :demo:deliberate:run --args="--max-depth 2 --max-claims 30"
open http://localhost:8091
```

Gradle's `run` task uses `demo/deliberate` as its working directory, and the backend finds `ui/dist` from there or from the repo root. Pass `--ui <dir>` to serve the UI from somewhere else. If there is no built UI, `/` serves a one-line hint page. The API still works.

**UI dev mode** (hot reload): leave the backend running and run `cd demo/deliberate/ui && npm run dev`. Vite proxies `/graph`, `/events`, `/question` and `/override` to `DELIBERATE_BACKEND`, which defaults to `http://localhost:8091`.

## Knobs

| flag | default | meaning |
|---|---|---|
| `[port]` (first bare arg, or `$PORT`) | 8091 | HTTP port |
| `--proposers claude,codex` | both | which CLIs propose arguments |
| `--claude-model <m>` / `--codex-model <m>` | CLI default | model passed to that CLI |
| `--max-processes <n>` | 8 | concurrent CLI processes, app-wide (EXP-07) |
| `--args-per-call <n>` | 1 | arguments per proposer call, per side |
| `--max-rounds <n>` | 3 | rounds per claim before `ROUND_LIMIT` |
| `--max-depth <n>` | 5 | claims deeper than this are `DEPTH_LIMIT` |
| `--max-claims <n>` | 180 | claims per question; unexplored claims past it become `BUDGET`, explored ones end `ROUND_LIMIT` ("budget exhausted"). An `EXPAND` still explores past it |
| `--max-args-per-side <n>` | 6 | a side of the root holding n arguments is saturated; a round never attaches beyond it |
| `--max-args-per-side-child <n>` | 3 | the same cap for every claim below the root |
| `--saturation <p>` | 0.22 | a side whose Jev saturation (1 − p(an important consideration is still missing)) is ≥ p gets no more proposals |
| `--min-influence <p>` | 0.10 | a non-root claim is expanded only if its contribution (reach × Jev relevance × Jev quality) ≥ p, else `PRUNED` |
| `--round-decay <f>` | 0.5 | a claim's next round is queued at contribution × f^(rounds run) |
| `--yield-stop on\|off` | on | stop a question once its returns diminish (EXP-10); `off` leaves only `--max-claims` |
| `--yield-window <n>` | 8 | …when the mean yield of its last n non-root rounds (and never before 2n such rounds) |
| `--yield-ratio <f>` | 0.6 | …falls below f × the mean yield of all its earlier rounds |
| `--yield-min-claims <n>` | 40 | …and never before the question holds n claims |
| `--data <dir>` | volatile | keep deliberations in `<dir>`: they survive restarts, including `kill -9` |
| `--semantics-layers <ids>` | all seven | credence layers to propagate (`dfquad` always runs) |
| `--consensus <ids>` | `wlo,jnb,woe` | layers averaged into the headline consensus |
| `--semantics <id>` | `consensus` | what a node's `credence` reports: the consensus, or one layer id |
| `--wlo-k` / `--wlo-p` / `--wlo-gamma` / `--wlo-alpha` | 2.4 / 2 / 1.3 / 1 | weighted log-odds parameters |
| `--codex-input-rate` / `--codex-cached-rate` / `--codex-output-rate` | 4.00 / 0.40 / 20.00 | Codex price in USD per 1M tokens (SPEC §12); required for a `--codex-model` other than `gpt-5.6-sol`, else its cost is "rate unknown" and left out of the total |
| `--jev-input-rate` / `--jev-output-rate` | 0.042 / 0 | Jev price in USD per 1M tokens — an assumption (third-party listing; TypeSafe publishes none) |

**Reach** is how much a claim can still matter to the question. The root has
reach 1, and an argument's reach is its parent's reach times the Jev strength
of the edge that attaches it (0.5 is assumed if that judgment failed). Reach
therefore decays down the tree even where Jev keeps calling every claim fairly
relevant. The `--saturation` and `--min-influence` defaults were calibrated on
live Jev judgments of real proposer output; the data and reasoning are in
[`CALIBRATION.md`](CALIBRATION.md). In short, Jev's saturation reading rises
only weakly with the number of arguments, so `--max-args-per-side` is the
dependable stop. Most depth-2 claims fall below `--min-influence`, so
`DEPTH_LIMIT` is a safety net that rarely fires.

The budget is spent in contribution order (see *Exploration*). With the
defaults, the per-side caps bound the root to 12 arguments and any other claim
to 6. Each default round offers two new arguments per side, giving Jev a
chance to stop a side before the cap supplies the dependable stop.

## HTTP

- `POST /question` with form field `text=…` returns `{"root":"<ref>"}`.
- `POST /override` with form fields `id=<ref>&mode=AUTO|EXPAND|STOP` returns `ok`. Bad input returns 400, and an unknown ref returns 404.
- `GET /graph` returns a `GraphDto` (see `Dto.kt`). Every node has its `credences` per layer, `consensus`, `spreadLow` and `spreadHigh`; an undercutter has `undercuts`, the ref of the edge it attacks.
- `GET /events` is an SSE stream. Every message is a full `GraphDto`, and messages are coalesced to at most about 10 per second.

## Cost and time

Every round of every claim makes one CLI call per proposer per unsaturated
side, and each claim also costs about 4–6 Jev requests. A single CLI call takes
roughly 4–10 s, but at most `--max-processes` run at once, so when a whole tree
level expands together, most of the wall time is spent queueing for a process
slot.

Measured on 2026-09-27 with `--max-depth 2 --max-claims 30 --max-rounds 2
--args-per-call 1`: a 30-claim tree took about 1–2 minutes. It ran 14–17 rounds,
which is roughly 60 CLI invocations billed to your Claude and Codex accounts,
plus a couple of hundred Jev requests. In a calibration run using a per-side
cap of 4, 2 arguments per call, `--min-influence 0.35`, and 8 processes, a
60-claim question took about 1 minute on 2026-09-27. It expanded the root and
6 of the 8 depth-1 claims, one round each, before the cap saturated them.
Of the 50 depth-2 claims, 37 were `PRUNED` and 13 were `BUDGET`, and no claim
hit `DEPTH_LIMIT`. With best-first exploration and triage (defaults, both CLIs,
"Should cities ban private cars from their centres?"), a 60-claim tree took
about 1.5 minutes on 2026-09-27: every depth-1 claim was explored, 6 of 38
depth-2 claims were `BUDGET`, and Jev's triage merged 8 and nested 10
proposals as evidence. Jev calls slower than 20 s are logged to stderr.

Iteration 4 (turns, balance, canonical prompts, seven credence layers,
`--data`), measured on 2026-09-27 with the defaults on "Should cities ban
private cars from their centres?": 140 claims in about 3.5 minutes, when the
tree stopped growing by itself (below the 180-claim budget). The root ended
6 pro / 5 con after 3 rounds; 11 of the 140 claims were explored at depth 1–3,
most depth-2 and depth-3 claims ended `PRUNED`, and 27 claims sit at depth 4–5
(`DEPTH_LIMIT`). Jev's triage over 149 proposals: 92 added, 39 nested as
evidence, 9 undercuts, 3 duplicates, 2 moved sides, 4 dropped. With the
proposers taking turns, **no** root argument was a cross-proposer duplicate
(0 of 11), where before about half of Claude/Codex same-round pairs at the root
were. The data directory then held about 38 MB after three trees (263 claims)
and three restarts, most of it host journal — see *Durability* for what it
holds now.

Iteration 5 (quality without the canonical factor, `--min-influence 0.10`,
the per-question yield stop) was measured on 2026-09-27. That historical run
included root rounds and could label an already exhausted tree as stopped;
the final rule excludes root rounds and records a diminishing stop only when
queued work is actually halted. See `CALIBRATION.md`, iteration 5, for the raw
measurements and the correction.

### The cost figure (SPEC §12)

The dollar figure next to a question is what its model calls have cost so
far; click it for the breakdown per backend and a projection (spent + queued
claims × the mean cost per completed round, once 3 rounds have run). Caveats:

- **Claude** is priced by the CLI's own `total_cost_usd`: the API-equivalent
  cost, **not your bill** if you use Claude Code on a subscription.
- **Codex** is priced from its reported tokens at the published
  `gpt-5.6-sol` rates (looked up 2026-09-27; the input price is promotional
  through at least 2026-11-21). A Codex subscription bills differently too.
- **Jev**'s price is **assumed**: TypeSafe publishes none; $0.042 per 1M input
  tokens is a third-party listing.
- The projection assumes one more round per queued claim at the question's
  mean so far; new arguments those rounds add are not in it.
- Questions restored from data written before cost tracking show `—`. If they
  run more rounds, the new spend is shown as an “at least” lower bound; their
  earlier spend and a whole-question projection remain unknown.

`DELIBERATE_LOG_USAGE=1` logs each call's raw usage to stderr (Claude's
`total_cost_usd`, Codex's `turn.completed` lines, Jev's `usage`), to check
the totals against.

## Models

Defaults come from a model experiment (2026-09-27): 12–24 recorded proposer
contexts per model, scored by Jev (contribution, novelty), a blind Opus
fact-check and blind pairwise preference against each provider's default.

| Job | Default | Why |
|---|---|---|
| Claude proposer | CLI default (`claude-sonnet-5`) | Haiku 4.5 only qualifies with thinking off and is not cheaper per call (Sonnet reads cached prompt tokens); Opus is ~4× the cost. |
| Claude merger | CLI default (`claude-sonnet-5`) | Nothing cheaper qualified. |
| Codex proposer | `gpt-5.6-sol`, `model_reasoning_effort="none"` | Same-or-better Jev contribution than effort `low`, ~6 s instead of ~17 s per call. |

`gpt-5.6-luna` (effort `none`) scores as well by Jev at ~1/30 of the Codex
cost, but a blind judge preferred Sol in 18 of 24 contexts (p = 0.011); use
`--codex-model gpt-5.6-luna` plus its rate flags if that trade-off is
acceptable. Sonnet's arguments were flagged false or unverifiable 15% of the
time vs 2% for Sol — Jev does not catch this.

## Durability

With `--data <dir>` a deliberation survives a restart, `kill -9` included.
Only **inputs** are kept: `graph.jsonl` records every claim and edge once
(append-only, in creation order), and the write-ahead `host.journal` holds
the engine's per-claim metadata — status, override, proposer, rewritten text,
the Jev judgments (which are the `jev` stances), triage counts, rounds,
errors — as field-level changes written into one hosted cell, the only
journaled cell on the host. Nothing derived is written: every credence,
influence and consensus is recomputed from those inputs on boot. The journal
compacts itself to one checkpoint at boot, at shutdown, and whenever it has
grown by more than 64 KB and its own last checkpoint size. On restart the
trees are rebuilt, and every claim that was waiting or being explored is
queued again; an interrupted round simply runs again. Restarting with a
different `--semantics-layers` is fine: the layers are recomputed anyway.

Measured live on 2026-09-27 ("Should cities ban private cars from their
centres?", `--max-claims 60`, all seven layers): 87 KB for the 60 claims while
running (1.4 KB/claim, the journal not yet compacted), 36 KB (0.6 KB/claim)
after a SIGTERM, and still 36 KB after two more restarts — SIGTERM, then
`kill -9` — with every layer's credence and every consensus identical after
each restart. The one-graph-per-layer design used about 71 KB per claim and
grew about five-fold over three restarts. A data directory from that design is
refused with a message; start a fresh one.

## Tests

```bash
./gradlew :demo:deliberate:test --rerun         # fakes only, no network
DELIBERATE_LIVE=1 ./gradlew :demo:deliberate:test --tests '*LiveSmokeTest' --rerun
# re-measure the Jev thresholds (CALIBRATION.md); regenerates material only if its cache is absent
DELIBERATE_CALIBRATE=1 ./gradlew :demo:deliberate:test --tests '*CalibrationTest' --rerun
cd demo/deliberate/ui && npm run typecheck && npm test
```
