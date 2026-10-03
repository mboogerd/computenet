# deliberate

Ask a question and watch a deliberation graph grow for it live. Two LLM
CLIs (Claude Code and Codex) propose arguments for and against each claim,
recursively. **Jev** (TypeSafe System One, `jev-latest`) makes every judgment
that steers the exploration: triage of new arguments, their quality and
relevance, saturation, and the stances credence is computed from. A kernel-hosted
argumentation graph, modelled on agora's, propagates credence. You can override the explorer per claim: force it
to expand a claim (it always runs at least one more round, even when the
question's claim budget is spent), or stop it. Stop on the question itself ends
the whole deliberation ("stopped by you": queued work is cancelled, rounds in
flight finish); Auto on it restarts it. The goal specification is [`SPEC.md`](SPEC.md).

## Credence model

1. Every claim gets a Jev *plausibility* judgment (five levels, false … true, mapped to [0,1]). It is judged on the claim and the question alone: no path, parent or direction, which a live investigation showed pulling the judgment towards the claim's role in the argument.
2. Every pro/con edge gets a Jev *relation strength* judgment: how strongly the child would bear on the parent if it were true.
   A new argument gets both judgments as soon as its round ends: plausibility in its own request, in parallel with one request that asks its strength, quality and relevance (see *Exploration*).
3. Both judgments are recorded as stances of the user `jev`, on the claim and on the edge (an edge is a claim too: it has its own credence, and an undercutter attacks it).
4. One cell graph propagates credence: supports raise a claim from its plausibility, attacks lower it, and each argument is weighted by its own credence and the strength of its edge. It does so under **eight semantics at once** — every claim and edge cell computes a credence *vector*, one value per layer: `dfquad` (agora's DF-QuAD), `wlo` (weighted log-odds), `jnb` (Jeffrey / naive-Bayes), `woe` (weight of evidence), `euler`, `qe` (quadratic energy), `mlp` and `glo` (gated log-odds: a doubted argument is inert, support and attack weighed alike). An edge tells its target both its own credence vector (the strength) and its source's, so every semantics computes its own energy from the two — `jnb` conditions on the source's credence exactly as its definition says.
5. The UI's headline number is the **consensus**: the geometric mean of the odds of the member layers (`wlo`, `jnb`, `woe` by default), with the **spread** (lowest to highest credence over all layers) drawn as a band behind it — visible only in the *research view* (below). Each claim cell derives both from its vector and emits them with it; they only summarise and never feed back into a layer. The deliberation code never propagates credence itself.
6. The plausibility judgment also asks a **knowledge** question: does judging this claim need knowledge Jev doesn't have? "Outside my knowledge" maps the plausibility to 0.5 — neither believed nor doubted — whatever the five-level score would have said, rather than the low score a model gives what it hasn't heard of. No Jev request carries a current date; that pulled the judgment of claims about recent events.
7. The question's root also gets a second verdict: the same arguments weighed from a **neutral prior** (½) instead of Jev's own first impression of the question — "what the arguments say" alongside "what Jev thought going in". See *First impression vs. arguments alone* below.

## Exploration

Each round asks the proposers for new arguments, then Jev sorts every proposal
in one request (SPEC EXP-03): **add** it, drop it as a **duplicate** (its
proposer is noted on the existing argument), **replace** a weaker wording,
**merge** it with an overlapping argument (Claude rewrites the two as one
sentence), **refine** an existing argument (recorded as an evidence entry on
it — no child claim, no budget spent), move it to the **other side**,
**undercut** an existing argument (it denies that the argument bears on the
claim, so it attacks that argument's link rather than the claim), or **drop**
it as not a real argument. Rewording and merging only touch arguments nobody
explored yet.

A con Jev would **add** against a claim it judges at least 80% plausible is
asked one more thing: does it dispute the claim itself, deny only that it
*bears* on the claim, or neither? "Denies bearing" attacks the claim's own
link (an undercutter of the link, not a con of the claim) instead; "neither"
drops it. A con triage already placed more specifically (duplicate, replace,
merge, refine, undercut, other side) or dropped is not asked.

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
canonical form is asked of the proposers, never scored). It is shown for
reference; it no longer decides what gets explored.

Alongside credence, a second graph of cells computes each claim's
**sensitivity** top-down — how much settling it would move the root's answer,
`d root / d claim` — the root at 1, propagated by chain rule down every path.
A claim's (or link's) **value of information** is
`|sensitivity| × 4·p·(1 − p)`, `p` its plausibility (a link's is its own
strength): 1 for an unsettled claim under a sensitive part of the tree, 0 once
either its sensitivity or its plausibility is pinned down. Exploration is
best-first by value of information across one queue, one round per task
(priorities are re-read when a worker is ready, since the sensitivity cells
settle asynchronously). A claim with rounds left goes back into the queue at
its value of information × 0.5 per round it already ran, so a strong claim's
second round still beats a weak sibling's first. Once a claim's value of
information falls below `--voi-eps` it gets no further round and ends
`DIMINISHING` ("not worth exploring"); a question stops once every remaining
claim is below that threshold, reporting "stopped: nothing left could change
the answer" (or "stopped: claim budget spent" when `--max-claims` ended it
first — the hard cap is checked before the value-of-information gate). An
`EXPAND` still explores a `DIMINISHING` claim, and skips every gate to do it.
The tree's top-3 claims and links by value of information are its **cruxes**
— "what would change the answer" (below).

Each question also still tracks its own **returns**, for reference only:
every non-root round records a yield — the value of the arguments it attached
(strength × relevance × quality), discounted by the share of proposals triage
threw away as repeats or drops, per argument asked for. Root rounds are
excluded because their yields are naturally higher. These numbers no longer
stop anything; the value-of-information rule above does.

### Links as claims

Every argument's edge is a claim too — its **link**: "“A” is a reason for
“B”" (SPEC §3 "Links as claims"). It is explored like a claim, in the same
queue: the proposers are asked why the connection **holds** (why A, if true,
really bears on B) and why it **fails** (why A, even if true, does not show
it — the undercutters), with a prompt that forbids disputing A or arguing B on
other grounds. Their arguments attach to the edge node, so they move the
edge's credence and with it A's pull on B; Jev sorts them against the link's
own arguments and judges them with the link as their parent. A link's value of
information follows the same rule as a claim's, reading its own strength as
its plausibility and its edge's sensitivity as its sensitivity:
`|sensitivity| × 4·s·(1 − s)`, with s the link strength — an open link (s near
½) whose answer is sensitive to it is explored early, a clear-cut one (s near
0 or 1) is left alone unless you expand it. (Its contribution — argument's
contribution × 4·s·(1 − s) — is still shown, for reference only.) Links count
in the question's rounds and cost, not in its claim count. `--explore-links
off` stops automatic link exploration (`EXPAND` still works).

**Pre-model-C** (measured once, 2026-09-27, defaults but `--max-claims 80`, a
question about motion-activated streetlights, back when links were ordered by
contribution rather than value of information): 8 of 82 links were explored
automatically (5 of the 7 root arguments' links, contributions 0.38–0.44, and
3 at depth 2, 0.30–0.31), gathering 10 reasons a link holds and 13 that it
fails, before the budget stopped the question at 26 rounds and $1.12;
expanding one more link by hand (strength 0.5, left at `BUDGET`) added two
undercutters, took its credence from 0.50 to 0.35 and moved the root from
0.594 to 0.604, for $0.07.

In the UI the connector — "Pro · strong link 72%" under an argument — is a
button: hover or focus it for a preview of the link, press it to open the
link with its own credence, status, Auto/Expand/Stop and its "why it holds" /
"why it fails" arguments, drawn dashed under the link. A "now" line under the
question names the claims and links being explored or judged at the moment.
A claim or link without arguments shows no spread band, because every rule
starts from the same first impression; the UI says so instead of drawing an
invisible band.

Under each question's tree, a **"What would change the answer"** panel lists
its top-3 cruxes — the claims and links with the highest value of information
— each with its sway (how far the answer moves per unit change in it), how
settled it is (plausible/strong, as a percentage) and, when known, which way
it would pull the answer. Nothing is shown until the backend names a crux.

### First impression vs. arguments alone

Every question carries two verdicts. **First impression** is Jev's own
plausibility of the question, judged before any argument exists — the prior
the ordinary credence graph starts from and never stops reflecting. **Arguments
alone** re-weighs the very same arguments, but starting the root from a neutral
½ instead of that first impression. The gauge caption reads "first impression
90% · arguments alone 62%"; when the two verdicts land on opposite sides of the
answer, a note under the gauge says so and names which way the arguments alone
lean — the first impression, not the arguments, is deciding the side.

All eight credence rules are still computed for every claim, but by default
the UI shows only the consensus: no spread band, no per-rule numbers, no "By
rule" breakdown. A **rules** button in the header (or `?research` in the URL)
turns on the *research view* for the session, restoring the band, the
"rules: a–b%" caption and each rule's own value everywhere they used to show.
The root claim's "before any argument" fact is labelled **First impression**
(other claims keep "Plausible on its own").

### Framing

Before a question's first round, Claude is asked once whether it should be
explored as asked, or **framed**: split into several readings of an
ambiguous term, or several competing answers to an open question, each then
explored as a root of its own. "Do fish sleep?" comes back **READINGS**
(ambiguous term "sleep"): "Do fish enter a rest state with lowered
responsiveness?" and "Do fish show REM-like brain activity?", up to 3. "How
many will attend?" comes back **POSITIONS** (an open question): up to 5
mutually exclusive answers, e.g. "under 100", "100 to 300", "over 300". A
question with one natural reading comes back unframed and explores as asked;
so does a failed framing call.

Each reading or position is a claim of the same question, at the question's
depth, explored exactly like a root — its own first impression, its own
"arguments alone" verdict, its own cruxes — while the question root itself
takes no round and finishes **FRAMED**. For POSITIONS, each position's
share of the answer is a softmax over their credences (temperature 1 is
plain odds normalisation: credences (0.8, 0.6, 0.2) give shares (0.70, 0.26,
0.04)); the shares are derived for display only and never feed back into any
credence.

In the UI a framed question's hero keeps its text but, since there is no
single yes/no verdict to show, replaces the gauge with the framing line
("depends on what you mean by sleep", or "several possible answers" with the
position shares as a small bar chart) and lists one section per reading or
position below — each with its own gauge, caption, status, override control
and argument tree. The question's cost, pause control, "now" line and cruxes
panel stay above them, shown once.

## Prerequisites

- JDK 21 (the Gradle toolchain provisions it) and Node 22+ for the UI.
- `TYPESAFE_API_KEY` in the environment. Jev judges every step, and the backend refuses to start without the key.
- The `claude` and `codex` CLIs on `PATH` and logged in. Each proposer call runs one CLI process with no tools, in an empty temp directory, with a 120 s timeout. Use `--proposers claude` or `--proposers codex` to run with just one of them. Merges (above) always ask `claude`; if it fails, the proposal counts as a duplicate.

## Run

```bash
# 1. build the UI once (the backend serves ui/dist at /)
cd demo/deliberate/ui && npm install && npm run build && cd -

# 2. start the backend (default port 8091)
./gradlew :demo:deliberate:run --args="--max-claims 30"
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
| `--max-rounds <n>` | 3 | rounds per claim before `ROUND_LIMIT` |
| `--max-claims <n>` | 180 | hard cap: claims per question; claims that meet it become `BUDGET` (whether or not they already ran a round). An `EXPAND` still explores past it |
| `--max-args-per-side <n>` | 6 | a side of the root holding n arguments is saturated; a round never attaches beyond it |
| `--saturation <p>` | 0.22 | a side whose Jev saturation (1 − p(an important consideration is still missing)) is ≥ p gets no more proposals |
| `--voi-eps <e>` | 0.01 | explore a claim (or link) only while its value of information, `\|d answer/d node\| × 4·p·(1 − p)`, is at least `e`; a question stops once none of its remaining nodes clears it (`0` disables the stop, leaving only `--max-claims`) |
| `--explore-links on\|off` | on | explore links ("A is a reason for B") like claims; `off` leaves them `PRUNED` unless expanded |
| `--data <dir>` | volatile | keep deliberations in `<dir>`: they survive restarts, including `kill -9` |
| `--start-paused` | off | every restored question starts paused: nothing runs, not even a Jev call, until you resume a question; new questions run normally (see *Restarting paused*) |
| `--semantics <id>` | `consensus` | what a node's `credence` reports: the consensus, or one layer id |
| `--codex-input-rate` / `--codex-cached-rate` / `--codex-output-rate` | 4.00 / 0.40 / 20.00 | Codex price in USD per 1M tokens (SPEC §12); required for a `--codex-model` other than `gpt-5.6-sol`, else its cost is "rate unknown" and left out of the total |

`--max-depth`, `--min-influence` and `--yield-stop` are **removed**: passing
one errors, naming `--voi-eps` as its replacement (model C — see
*Exploration*).

Fixed in code (`DeliberationEngine.Config`, `DeliberateApp.SemanticsConfig`,
`Pricing`), no longer flags: one argument per proposer call per side, a
per-side cap of 3 below the root, a round decay of 0.5, an internal `maxDepth`
bound (unbounded by default; only the test suite sets it — it no longer
gates or stops anything the app does), the EXP-10 yield-reporting window (8;
the old diminishing-returns ratio and minimum-claims thresholds are gone with
the yield stop), all eight credence layers with the
consensus over `wlo,jnb,woe`, the weighted log-odds parameters (k 2.4, p 2,
γ 1.3, α 1), and the assumed Jev price (USD 0.042 per 1M input tokens, output
free — a third-party listing; TypeSafe publishes none).

**Reach** is how much a claim can still matter to the question. The root has
reach 1, and an argument's reach is its parent's reach times the Jev strength
of the edge that attaches it (0.5 is assumed if that judgment failed). Reach
therefore decays down the tree even where Jev keeps calling every claim fairly
relevant; it still feeds a link's own reach (*Links as claims*) and the shown
contribution figure. The `--saturation` default was calibrated on
live Jev judgments of real proposer output; the data and reasoning are in
[`CALIBRATION.md`](CALIBRATION.md). In short, Jev's saturation reading rises
only weakly with the number of arguments, so `--max-args-per-side` is the
dependable stop for a side. `--voi-eps`'s default is a starting value from a
one-off scratch review, not a calibration run (`minInfluence` and
`DEPTH_LIMIT`-as-a-stop-rule are gone with model C, so most claims now stop on
value of information, saturation or the claim budget, not depth).

The budget is spent in value-of-information order (see *Exploration*). With
the defaults, the per-side caps bound the root to 12 arguments and any other
claim to 6. Each default round offers two new arguments per side, giving Jev a
chance to stop a side before the cap supplies the dependable stop.

## HTTP

- `POST /question` with form field `text=…` returns `{"root":"<ref>"}`.
- `POST /override` with form fields `id=<ref>&mode=AUTO|EXPAND|STOP` returns `ok`. The ref is a claim's, or an edge's to steer its link. Bad input returns 400, and an unknown ref returns 404.
- `POST /question/pause` with form fields `root=<question ref>&paused=true|false` returns `ok` (SPEC CTL-05). A paused question finishes its rounds in flight and starts no new one; `EXPAND` on one of its claims or links still runs that one. The UI's **Pause/Resume** button sits next to the question's cost figure.
- `GET /graph` returns a `GraphDto` (see `Dto.kt`). Every node has its `credences` per layer, `consensus`, `spreadLow`, `spreadHigh` and `sensitivity` (model C); an argument about a link has `onLink` (the edge), and an undercutter also `undercuts`; a claim carries `evidence` (model B) when it has any; an edge carries its link's `text`, `status`, `override`, `rounds`, `contribution`, `triage`…; a node being explored, judged or assessed has `activity`; a question carries `cruxes` (model C, up to 3 refs for "what would change the answer") and, model D, `firstImpression` (Jev's plausibility of the question before any argument), `neutralCredence` (the root's headline credence from the same arguments weighed from a neutral prior) and `verdictsDisagree` (the two fall on strictly opposite sides of 50%).
- `GET /events` is an SSE stream. Every message is a full `GraphDto`, and messages are coalesced to at most about 10 per second.

## Cost and time

Every round of every claim makes one CLI call per proposer per unsaturated
side, and each claim also costs about 4–6 Jev requests. A single CLI call takes
roughly 4–10 s, but at most `--max-processes` run at once, so when a whole tree
level expands together, most of the wall time is spent queueing for a process
slot.

**Pre-model-C** (these three paragraphs predate the value-of-information stop
and were measured with the removed `--max-depth`, `--min-influence` and
`--yield-stop` flags; kept as history, not as current behaviour):

Measured on 2026-09-27 with `--max-depth 2 --max-claims 30 --max-rounds 2`
(one argument per call): a 30-claim tree took about 1–2 minutes. It ran 14–17 rounds,
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
the (then-)final rule excluded root rounds and recorded a diminishing stop
only when queued work was actually halted — since superseded by model C's
value-of-information stop above. See `CALIBRATION.md`, iteration 5, for the
raw measurements and the correction.

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
| Claude framer | CLI default (`claude-sonnet-5`) | Same process gate and timeout as the merger; not part of the model experiment above — not measured. |
| Codex proposer | `gpt-5.6-sol`, `model_reasoning_effort="none"` | Same-or-better Jev contribution than effort `low`, ~6 s instead of ~17 s per call. |

`gpt-5.6-luna` (effort `none`) scores as well by Jev at ~1/30 of the Codex
cost, but a blind judge preferred Sol in 18 of 24 contexts (p = 0.011); use
`--codex-model gpt-5.6-luna` plus its rate flags if that trade-off is
acceptable. Sonnet's arguments were flagged false or unverifiable 15% of the
time vs 2% for Sol — Jev does not catch this.

## Durability

With `--data <dir>` a deliberation survives a restart, `kill -9` included.
Only **inputs** are kept in the one write-ahead file, `host.journal`: topology
records capture every claim, edge, sensitivity cell and issue framing in
creation order, while the journal's metadata fold holds per-claim status,
override, proposer, rewritten text, the Jev judgments (which are the `jev`
stances), triage counts, rounds and errors. Nothing derived is written: every
credence, influence, consensus and sensitivity (model C) is recomputed from
those inputs on boot. Each framing is one topology delta, so it is either
present as a complete graph construction or absent. The journal compacts
itself to one checkpoint at boot, at shutdown, and whenever it has grown by
more than 64 KB and its own last checkpoint size. On restart the topology is
rebuilt under its recorded refs before metadata frames replay, the trees are
rebuilt from the topology fold, and every claim that was waiting or being
explored is queued again; an interrupted round simply runs again. An `EXPAND`
whose forced round the restart interrupted is not resumed: expand the claim
again.

Measured live on 2026-09-27 ("Should cities ban private cars from their
centres?", `--max-claims 60`, all seven layers): 87 KB for the 60 claims while
running (1.4 KB/claim, the journal not yet compacted), 36 KB (0.6 KB/claim)
after a SIGTERM, and still 36 KB after two more restarts — SIGTERM, then
`kill -9` — with every layer's credence and every consensus identical after
each restart. The one-graph-per-layer design used about 71 KB per claim and
grew about five-fold over three restarts.

### Restarting paused

A restart re-queues whatever was still running, so it can start spending on
questions you considered finished. Restart with `--start-paused`: every
restored question comes back paused (SPEC DUR-06), nothing runs, and you
resume only the questions you want to continue, each with the **Resume**
button in its header (or `POST /question/pause` `paused=false`). The pause is
recorded, so later restarts keep those questions paused until you resume them.

```bash
build/install/deliberate/bin/deliberate 8091 --data <dir> --start-paused
```

## Tests

```bash
./gradlew :demo:deliberate:test --rerun         # fakes only, no network
DELIBERATE_LIVE=1 ./gradlew :demo:deliberate:test --tests '*LiveSmokeTest' --rerun
# re-measure the Jev thresholds (CALIBRATION.md); regenerates material only if its cache is absent
DELIBERATE_CALIBRATE=1 ./gradlew :demo:deliberate:test --tests '*CalibrationTest' --rerun
cd demo/deliberate/ui && npm run typecheck && npm test
```
