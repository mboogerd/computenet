# deliberate

Ask a question and watch a deliberation graph grow for it live. Two LLM
CLIs (Claude Code and Codex) propose arguments for and against each claim,
recursively. **Jev** (TypeSafe System One, `jev-latest`) makes every judgment
that steers the exploration: triage of new arguments, their quality and
relevance, saturation, and the stances credence is computed from. The kernel-hosted agora argumentation
graph propagates credence. You can override the explorer per claim: force it
to expand a claim (it always runs at least one more round, even when the
question's claim budget is spent), or stop it. The goal specification is [`SPEC.md`](SPEC.md).

## Credence model

1. Every claim gets a Jev *plausibility* judgment (five levels, false … true, mapped to [0,1]). It is judged on the claim and the question alone: no path, parent or direction, which a live investigation showed pulling the judgment towards the claim's role in the argument.
2. Every pro/con edge gets a Jev *relation strength* judgment: how strongly the child would bear on the parent if it were true.
   A new argument gets both judgments as soon as its round ends: plausibility in its own request, in parallel with one request that asks its strength, quality and relevance (see *Exploration*).
3. Both judgments are recorded as stances of the agora user `jev`.
4. Agora propagates credence: supports raise a claim from its plausibility, attacks lower it, and each argument is weighted by its own credence and the strength of its edge. It does so under **seven semantics at once**, one agora graph (layer) per semantics on the same host: `dfquad` (agora's DF-QuAD), `wlo` (weighted log-odds), `jnb` (Jeffrey / naive-Bayes), `woe` (weight of evidence), `euler`, `qe` (quadratic energy) and `mlp`.
5. The UI's headline number is the **consensus**: the geometric mean of the odds of the member layers (`wlo`, `jnb`, `woe` by default), with the **spread** (lowest to highest credence over all layers) drawn as a band behind it. The consensus only summarises; it never feeds back into a layer. The deliberation code never propagates credence itself.

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
(quality includes whether the argument is stated in canonical form).
Exploration is best-first by contribution across one queue, one round per
task. A claim with rounds left goes back into the queue at
contribution × `--round-decay` per round it already ran, so a strong claim's
second round still beats a weak sibling's first. Arguments below
`--min-influence` are never explored (`PRUNED`), so irrelevant or badly built
ones cost nothing, and the claim budget is spent on the strongest ones first.

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
| `--max-depth <n>` | 3 | claims deeper than this are `DEPTH_LIMIT` |
| `--max-claims <n>` | 180 | claims per question; unexplored claims past it become `BUDGET`, explored ones end `ROUND_LIMIT` ("budget exhausted"). An `EXPAND` still explores past it |
| `--max-args-per-side <n>` | 6 | a side of the root holding n arguments is saturated; a round never attaches beyond it |
| `--max-args-per-side-child <n>` | 3 | the same cap for every claim below the root |
| `--saturation <p>` | 0.22 | a side whose Jev saturation (1 − p(an important consideration is still missing)) is ≥ p gets no more proposals |
| `--min-influence <p>` | 0.15 | a non-root claim is expanded only if its contribution (reach × Jev relevance × Jev quality) ≥ p, else `PRUNED` |
| `--round-decay <f>` | 0.5 | a claim's next round is queued at contribution × f^(rounds run) |
| `--data <dir>` | volatile | keep deliberations in `<dir>`: they survive restarts, including `kill -9` |
| `--semantics-layers <ids>` | all seven | credence layers to propagate (`dfquad` always runs) |
| `--consensus <ids>` | `wlo,jnb,woe` | layers averaged into the headline consensus |
| `--semantics <id>` | `dfquad` | the layer reported as a node's `credence` |
| `--wlo-k` / `--wlo-p` / `--wlo-gamma` / `--wlo-alpha` | 2.4 / 2 / 1.3 / 1 | weighted log-odds parameters |

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
were. The data directory holds about 38 MB after three trees (263 claims) and
three restarts, most of it host journal.

## Durability

With `--data <dir>` a deliberation survives a restart, `kill -9` included.
Each credence layer keeps its structure in `graph-<layer>.jsonl`, and one
write-ahead host journal (`host.journal`) holds every stance and credence
update — the `AgoraApp --journal` mechanism. The engine's own per-claim
metadata (status, override, proposer, rewrites, Jev judgments, triage
counts, rounds, errors) rides the same journal: it is written as records into
a hosted cell, so one mechanism covers the graph and the engine. On restart
the trees are rebuilt, and every claim that was waiting or being explored is
queued again; an interrupted round simply runs again. Restarting with a
different `--semantics-layers` is fine (a new layer is built from `dfquad`'s
structure); the journal grows with every restart (no compaction yet).

## Tests

```bash
./gradlew :demo:deliberate:test --rerun         # fakes only, no network
DELIBERATE_LIVE=1 ./gradlew :demo:deliberate:test --tests '*LiveSmokeTest' --rerun
# re-measure the Jev thresholds (CALIBRATION.md); regenerates material only if its cache is absent
DELIBERATE_CALIBRATE=1 ./gradlew :demo:deliberate:test --tests '*CalibrationTest' --rerun
cd demo/deliberate/ui && npm run typecheck && npm test
```
