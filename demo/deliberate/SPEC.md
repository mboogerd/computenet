# deliberate — goal specification

A demo in which a human poses a question and watches a **deliberation graph**
grow for it in real time: LLM agents (Claude CLI and Codex CLI) recursively
propose arguments for and against each claim; **Jev** (TypeSafe System One,
`jev-latest`) supplies every judgment that steers the exploration and every
credence; a kernel-hosted argumentation graph (agora's model) propagates those
credences. The human can override the explorer's depth decisions per claim.

(Credence is propagated by deliberate's own cell graph, modelled on agora's:
claims and edges as cells, edges being claims. "agora `CLAIM`/`EDGE`" below
names that model.)

## 1. Vocabulary

- **Question** — free text the human submits. It becomes the **root claim**
  (a question like "Should X?" is restated by the root as-is; no rewriting).
- **Claim** — an agora `CLAIM` node: one self-contained declarative sentence.
- **Argument** — a claim linked to a **parent** claim by an agora `EDGE` of
  polarity `SUPPORT` (pro) or `ATTACK` (con). Edge direction: child → parent.
- **Undercutter** — a claim that does not dispute a claim but denies that one
  of its arguments bears on it ("this does not show that"): its `ATTACK` edge
  targets that argument's *edge* (agora edges are claims), lowering the edge's
  credence and with it the argument's influence (EXP-03 `UNDERCUT`).
- **Proposer** — an argument generator: `claude` (Claude CLI) or `codex`
  (Codex CLI). Every argument records which proposer produced it.
- **Judge** — Jev. Judgments are typed (score / noul / choice), never prose.
- **Round** — one pass over a claim: the proposers take turns, each asked
  for new pro and con arguments on the unsaturated sides and its proposals
  triaged and attached before the next proposer's turn; then the round's
  arguments are assessed and saturation is re-judged.
- **Expansion** — running rounds on a claim until it is saturated or its round
  limit is hit. Each round is one task in the exploration queue; an attached
  argument joins that queue as soon as its attach-time assessment completes
  (§3 "Exploration order").
- **Contribution** — how much exploring a claim is worth: reach × relevance ×
  quality (EXP-05); 1 for the root.
- **Yield** — what one round returned, per argument asked for (EXP-10); a
  question stops when its recent yields fall well below its earlier ones.
- **Override** — the human's per-claim setting: `AUTO` (Jev decides),
  `EXPAND` (force expansion), `STOP` (force no further expansion).

## 2. Credence model (requirements CRED-*)

- **CRED-01** Every claim receives a Jev *plausibility* judgment — a Score over
  five ordered levels (almost certainly false … almost certainly true) mapped
  linearly to [0,1] — judged on a state holding only `root_question` and
  `claim`: no path, parent, direction or date, and not its arguments (a live
  investigation measured the path pulling the judgment towards the claim's
  role in the argument, e.g. 0.31 with the path vs 0.53 without; dropping it
  shifts fresh arguments by −0.011 on average). The instruction tells Jev the
  question only names the topic, and not to reward or penalise the claim for
  the answer it favours. It is applied as the stance of user `jev` on
  that claim. An argument is judged the moment it is attached: plausibility
  in its own request, in parallel with one request asking its CRED-02
  strength and EXP-05 quality and relevance (independent questions over the
  argument's full state); the root, or an argument whose assessment failed, is
  judged when its expansion starts.
- **CRED-02** Every edge receives a Jev *relation strength* judgment — a Score:
  "if the child claim were true, how strongly would it bear on the parent in
  the stated direction" (irrelevant … decisive), mapped to [0,1]. Applied as the
  `jev` stance on the edge node. An argument whose text changes (EXP-03
  REPLACE or MERGE) is assessed again.
- **CRED-03** Credence is propagated over the `jev` stances and the incoming
  edges by one kernel-hosted cell graph, under each of several gradual
  semantics ("Credence layers and consensus" below). The deliberation code
  never propagates credence itself; it only reads the graph's results.

### Credence layers and consensus

- **CRED-04** Every deliberation is propagated by **one** cell graph whose
  credences are vectors: one `ClaimNode` per claim and one `EdgeNode` per
  edge (an edge is a claim — "source supports/attacks target" — with its own
  `jev` stance, its own incoming edges and its own credence, so an
  undercutter attacks it), each computing one credence per semantics — a
  **layer**. A node emits its **credence vector**; an edge emits an
  **influence** carrying structured data: its ref, polarity, its own credence
  vector (the **strength**: the strength stance, lowered by any undercutter)
  and its source's credence vector. Each semantics computes its own energy
  from the pair — `energy(strength, credence)`, DF-QuAD's product for most —
  and combines a node's base with the energies of its attacks and supports.
  Layers (`--semantics-layers`, default all seven): `dfquad` (agora's
  DF-QuAD), `wlo` (weighted log-odds: σ(α·logit(base) + k·(‖S^γ‖_p −
  ‖A^γ‖_p)), α = 1, k = 2.4, p = 2, γ = 1.3), `jnb` (Jeffrey / naive-Bayes:
  the argument's likelihood ratio LR(s) = ((1+s)/(1−s))^K is Jeffrey-
  conditioned on its source's credence c, energy ln(c·LR + (1−c)·LR^−r),
  exact because s and c arrive separately), `woe` (log-odds DF-QuAD, weight
  of evidence −ln(1 − e)), `euler` (Euler-based), `qe` (quadratic energy),
  `mlp` (MLP-based); formulas and defaults as in the prototype
  `semantics.js`, and every layer keeps agora's base (the clamped mean of the
  stances). `dfquad` always runs. Cycle handling is agora's: the edge that
  closes a cycle is its head and absorbs a returning source update whose
  largest per-layer change is below the quiescence threshold; a node's
  arguments are folded in ref order, so emission is deterministic. Every
  message carries a magnitude — the largest per-layer change — that the host
  schedules by.
- **CRED-05** A node's **consensus** is σ(mean over the member layers of
  logit(cᵢ)), each cᵢ clamped to [0.001, 0.999] — the geometric mean of their
  odds. Members (`--consensus`, default `wlo,jnb,woe`: the rules that pass
  every intuition check D1–D5c at their defaults). Its **spread** is the
  [min, max] of the credence over *all* layers. The consensus is a summary:
  nothing feeds it back into any layer or into a parent. The UI's headline
  number and verdict are the consensus, drawn over the spread as a band.
  `--semantics` names the layer shown as `NodeDto.credence` (default
  `dfquad`). No averaging layer beyond this consensus exists.
- **CRED-06** The consensus and spread are **derived by the cells**: a
  claim or edge cell emits `{vector, consensus, spreadLow, spreadHigh}` with
  every change of its vector, and the graph's hub folds those emissions. The
  consensus is a pure function of the vector, so computing it where the
  vector is computed is the simplest derived form — no second cell per node,
  no second hop, no second fold. The snapshot only reads the hub.

## 3. Exploration (requirements EXP-*)

- **EXP-01** Submitting a question creates the root claim and starts its
  expansion immediately. Several questions may coexist; each is its own tree.
- **EXP-02** A round gives the proposers **turns**, in their configured order
  (`--proposers`, default `claude,codex`): each is asked, concurrently per
  side, for up to `argsPerCall` (default 1) new arguments per unsaturated side
  that still has room, given the root question, the path from root to the
  claim and the claim's existing pro/con arguments — *including those the
  previous proposer contributed in this round, after triage* — so the second
  focuses on what is still missing. Rounds of different claims still run in
  parallel. Arguments are asked for in **canonical form** (one checkable
  proposition, the reason rather than its bearing on the claim, explicit
  subject and scope, no hedges, dated only when time matters, no invented
  details, ≤ 25 words; `CliProposer.CANONICAL_RULES`, with examples about
  invented, mundane subjects only — no real person, political figure or
  contested topic may appear in a prompt, so no example content leaks into a
  deliberation); proposers are not asked to label rebuttals and undercutters —
  that is triage's job. Canonical form is **only** asked for: it is not
  scored or gated anywhere (EXP-05).
- **EXP-03** Before attaching, exact-text repeats are dropped, then Jev
  **triages** every remaining candidate of the round in one request: per
  candidate an *action* Choice and, when there is anything to point at, an
  independent *target* Choice (`none` + the claim's existing arguments on both
  sides, labelled by side, + the candidates before it in the list — so
  near-duplicates within one round are caught in the same request). Triage
  runs once per proposer turn (EXP-02), so a later turn's candidates are
  compared with the earlier turns' arguments as existing ones. Actions:
  - `ADD` — a new point on its stated side: attached there;
  - `DUPLICATE` — the same point as the target: dropped, counted in
    `duplicatesDropped`, its proposer recorded in the target's
    `alsoProposedBy`;
  - `REPLACE` — a clearly stronger/clearer version of the target: the target
    takes its text and provenance;
  - `MERGE` — overlaps the target, each adding something: Claude (the `claude`
    CLI, same process gate, sandbox and timeout as EXP-09) rewrites the two as
    one sentence, which becomes the target's text (`merged: true`, the
    candidate's proposer in `alsoProposedBy`);
  - `REFINE` — a specific instance of / evidence for the target: attached as a
    `SUPPORT` argument under the target instead of under the claim;
  - `OTHER_SIDE` — argues the opposite side: attached there;
  - `UNDERCUT` — does not dispute the claim but denies that the target
    argument bears on it: attached as an undercutter, an `ATTACK` edge
    targeting the target's *edge*. For paths and context an undercutter is a
    claim about the target's parent, at the target's depth; it is assessed
    against the link it denies (its `parent_claim` is "The argument "X" is a
    reason to accept/reject the claim "Y"."), and explored like any claim. An
    argument holds at most one per-side cap of undercutters;
  - `DROP` — not a real argument about the claim (off-topic, incoherent, a
    question, a restatement of the claim).

  REPLACE and MERGE only rewrite a target nobody has explored yet (still
  `QUEUED`, no children); otherwise, or if the merge call fails (error
  recorded), they fall back to `DUPLICATE`. A targeted action without a target
  becomes `ADD` (`DUPLICATE`: dropped). The claim counts each action taken in
  `triage`. Rewording lives in the deliberation layer (the graph's claim text is
  immutable); if the triage request fails, every candidate is `ADD`.
- **EXP-04** After each round, Jev judges **saturation** per side (a Noul:
  "is an important consideration on this side still missing from the existing
  arguments?", read as saturated = 1 − p). A side is saturated when that value
  ≥ `saturation` (default 0.22, calibrated on live Jev samples, see §10), or
  when it already holds its cap: `maxArgsPerSide` (default 6) for the root,
  `maxArgsPerSideChild` (default 3) below it. **Balance:** a side below its
  cap that holds fewer arguments than the other side is never saturated by
  Jev's judgment (Jev's saturation reads systematically higher for con, so
  con sides stopped early — one root ended 5 pro / 2 con). Saturation is
  judged once per round, after every proposer had its turn. Saturated sides
  receive no further proposals, and a round never attaches beyond the cap.
  Expansion ends when both sides are saturated or `maxRounds` (default 3) is
  reached.
- **EXP-05** Relevance decays along the tree. Each claim has a **reach**:
  1 for the root, `reach(parent) × strength(edge)` for an argument (CRED-02
  strength of the edge attaching it). When an argument is attached, Jev also
  judges its **relevance** (a Noul given the root question and the full path:
  "would analysing this claim further materially change how the root question
  should be answered?") and its **quality** (a Noul: "is this a
  well-constructed argument — a self-contained, coherent claim that actually
  bears on its parent in the stated direction, not a restatement, off-topic or
  a rhetorical question?"). Its **contribution** is
  `reach × relevance × quality`. An undercutter's reach is
  `reach(parent) × strength(its edge) × strength(the undercut edge)`. A
  non-root claim whose contribution is below `minInfluence` (default 0.10,
  §10 iteration 5) is `PRUNED` without being explored — an irrelevant or
  poorly constructed argument never is. (Iteration 4 multiplied a
  canonical-form Noul into quality; it anti-correlated with relevance,
  r −0.3 to −0.54, and pruned the most on-point arguments, so iteration 5
  removed it: canonical form is asked of the proposers only, EXP-02.) If the assessment fails, strength 0.5 is used and
  relevance and quality count as 1. The root is always expanded. Beyond
  `maxDepth` (default 5) claims are `DEPTH_LIMIT` — a safety net, not the
  primary stop.
- **EXP-06** A global `maxClaims` budget (default 180 per question) is enforced:
  no argument is attached once the tree holds that many claims. The budget is
  spent in exploration order (below), so it goes to the most contributing
  claims first. Gates run in the order depth → contribution → budget →
  diminishing returns (EXP-10), so `BUDGET` means the claim would otherwise
  have expanded and never did; a claim that already ran a round and then meets
  the budget ends `ROUND_LIMIT` with `error = "budget exhausted"`. A question
  at its budget reports `stoppedBy = "budget"`. The budget is a ceiling; the
  per-question stop that normally ends a tree is EXP-10.
- **EXP-07** Concurrency is bounded: at most `maxProcesses` (default 8) CLI
  processes run at once across the whole app. Jev calls are not rate-limited
  by us but retry 429/529 with exponential backoff (≤ 4 attempts).
- **EXP-08** A proposer or Jev failure never kills the deliberation: the
  failing call is logged, the claim records the error, and the round
  continues with what it has (a claim where *every* call failed becomes
  `FAILED`).
- **EXP-09** Proposers run with no tool access, in an empty temp directory,
  with a per-call timeout (default 120 s). Output is parsed as a JSON array of
  strings, tolerating surrounding prose/code fences; anything unparseable is a
  failed call.
- **EXP-10** Every question stops by its own **diminishing returns**. Each
  round of any **non-root** claim in the question that asked for at least one
  argument records a **yield**: Σ over the arguments it attached (REFINE and UNDERCUT
  included) of `strength × relevance × quality` (the EXP-05 fallbacks for a
  failed assessment), × `1 − (DUPLICATE + DROP) / triaged` (the round's triage
  counts, exact-text repeats included; 1 when nothing was triaged), ÷ the
  number of arguments asked for. Root rounds are excluded because their
  naturally high yields otherwise inflate the earlier mean. The question
  keeps its non-root yields in completion order. Once it holds ≥
  `yieldMinClaims` (default 40) claims and ≥ 2 × `yieldWindow` (default 8)
  non-root yields, and is below its budget, it stops
  when mean(last `yieldWindow` yields) < `yieldRatio` (default 0.6) ×
  mean(all earlier yields), **provided at least one claim in `QUEUED` can
  actually be halted**. If the threshold is first observed after the question
  ran out of queued work on its own, no stop is recorded and `stoppedBy`
  remains null. Stopping: no new round starts in the question; every claim
  waiting for its first or next round, and every argument
  attached later that passes the depth and contribution gates, ends
  `DIMINISHING` (terminal); rounds in flight complete and attach, but their
  yields are not recorded (the series is frozen at the stop, so it shows why
  the question stopped). `EXPAND` still forces a round on a `DIMINISHING`
  claim (CTL-02), whose new arguments then meet the stop like any other.
  `--yield-stop off` disables the stop (yields are still recorded). The
  question reports `yieldRounds` (non-root rounds), `yieldRecent` (mean of the last window),
  `yieldEarlier` (mean before it) and `stoppedBy` (`"diminishing"`,
  `"budget"` or null); the yields and the stop are durable (DUR-02). The
  relative, per-question comparison is the point: absolute yields differ
  several-fold between questions (§10 iteration 5), so any absolute threshold
  would again starve one question and overgrow another.

### Exploration order

Work is one priority queue across all questions; each task is one round of
one claim. A claim's first round is queued at its contribution (the root at
1); after each round a claim that is not finished goes back into the queue at
`contribution × roundDecay^rounds` (`roundDecay` default 0.5), so a strong
claim's second round competes fairly with a weaker sibling's first. Ties go
first-in, first-out. An argument is queued, at its contribution priority, as
soon as its attach-time assessment completes — at the end of the round that
attached it, since a later turn may still reword it; it does not wait for its
parent to finish later rounds. A claim the human forces with `EXPAND` is queued ahead
of all contributions.

## 4. Human control (requirements CTL-*)

- **CTL-01** The human can set any claim's override to `AUTO`, `EXPAND` or
  `STOP` at any time.
- **CTL-02** `EXPAND` always explores: whatever the claim's status —
  queued, running, or finished for any reason including `BUDGET` and
  `DIMINISHING` — its next
  round is **forced**, and it runs at least that round. It skips the
  contribution and depth gates, is queued ahead of all contributions, and
  raises the claim's round limit by one if needed. The forced round ignores
  Jev saturation, the round limit and `maxClaims`: it has its own allowance of
  up to one per-side cap of new arguments per side (shared by the proposers'
  turns), and they are attached even when the tree is at its budget. Triage
  still applies. The new arguments then face the gates like any other.
- **CTL-03** `STOP` cancels queued work for that claim and prevents future
  rounds; an in-flight round finishes but its results are still attached
  (arguments are never silently dropped once produced). Status becomes
  `STOPPED`. Descendants are not affected.
- **CTL-04** `AUTO` returns the decision to Jev; setting it on a `STOPPED`
  claim re-queues it through the normal gates.

## 5. Claim status (the state machine the UI renders)

`QUEUED → JUDGING → EXPLORING → SATURATED | ROUND_LIMIT`, with terminal
alternatives `PRUNED`, `DEPTH_LIMIT`, `BUDGET`, `DIMINISHING` (EXP-10),
`STOPPED`, `FAILED`.
Every status change is broadcast.

## 6. HTTP surface (the UI contract)

- `POST /question` form `text=` → `{"root":"<ref>"}`
- `POST /override` form `id=<ref>&mode=AUTO|EXPAND|STOP` → `ok`
- `GET  /graph` → `GraphDto` (see `Dto.kt`): every node carries its
  `credences` per layer, its `consensus`, `spreadLow` and `spreadHigh`; an
  undercutting claim carries `undercuts` (the edge it attacks, which is also
  its edge's `target`); the graph carries `consensusMembers`; every question
  carries `yieldRounds`, `yieldRecent`, `yieldEarlier` and `stoppedBy`
  (EXP-10), and `costUsd`, `projectedUsd` and `cost` (§12).
- `GET  /events` → SSE, each message a full `GraphDto` (coalesced, ≤ 10/s)
- `GET  /` → the built UI (`ui/dist`) when present.

## 7. UI (requirements UI-*)

- **UI-01** A single input where the question is typed and submitted.
- **UI-02** The deliberation graph of the selected question is shown as a tree
  rooted at the question, growing live via SSE with no reload.
- **UI-03** Each claim shows its text, its credence (bar/number: the
  consensus, with the spread as a thin band), its status, the proposer that
  produced it, and its override control. Each argument shows its polarity
  (pro/con visually distinct) and relation strength; an undercutter is shown
  under the argument whose link it attacks, labelled "undercuts the link".
  The facts panel lists every layer's credence, marks the consensus members,
  and says how far the rules agree.
- **UI-04** Minimal, modern, slick: a calm neutral palette, pro/con as the only
  saturated colours, smooth enter animation for new nodes, light and dark mode.

## 8. Non-goals (v1)

Multiple users; human stances; editing claims; cross-tree links; merging
equivalent claims across branches; exploring an *edge* as a claim of its own
("[child] is a reason for [parent]", with proposers asked for arguments
about the link) — only Jev's `UNDERCUT` re-targeting exists (residual of
iteration 4).

## 9. Acceptance

1. Engine tests with fake proposers/judge prove EXP-02..08 and CTL-01..04
   deterministically (no network in `./gradlew :demo:deliberate:test`).
2. Jev client and CLI proposer parsing are unit-tested against recorded
   payloads; a live smoke test runs only when `DELIBERATE_LIVE=1`.
3. A live manual run against a real question grows a multi-level graph whose
   credences move in the UI, and both overrides visibly work.

## 10. Calibration (iteration 2)

The first live runs showed both Jev gates inert (saturation 0.04–0.43 vs a
0.7 threshold; relevance never below 0.5), so exploration was stopped only by
hard limits. Thresholds are therefore set from evidence: a calibration run
over real claims at depths 0–3 with 0–6 arguments per side, recorded in
`demo/deliberate/CALIBRATION.md`, must show that with the defaults (a) a side
typically saturates by 3–4 arguments and (b) a typical question tree stops
growing through `PRUNED` before `DEPTH_LIMIT` for most depth-2 claims.
Criterion (a) is met on the median; the per-side cap remains the dependable
stop because Jev's saturation signal is shallow.

Iteration 4 changed plausibility's state (CRED-01) and added the canonical
factor to quality (EXP-05) after this calibration, so `saturation` and
`minInfluence` were calibrated under the old judgments. The first live run
under the new ones showed the influence gate misbehaving (every depth-1
argument `PRUNED`), so `minInfluence` was rescaled to 0.15.

Iteration 5 (`CALIBRATION.md`) found tree size decided at depth 1 and very
uneven across questions (21 vs 57 and 110 claims in one run): the canonical
factor pruned the most on-point arguments, two prompt examples about a real
politician were copied into one question's root arguments, and a flat
threshold cannot suit questions whose argument quality differs. So quality
lost its canonical factor (EXP-05), the prompt examples became topic-neutral
(EXP-02), `minInfluence` became 0.10 (an offline replay of the recorded trees
balanced best there), and each question now stops by its own diminishing
returns (EXP-10) rather than by the budget. Root rounds are excluded from its
history, and a diminishing stop is recorded only when it actually halts a
`QUEUED` claim; these corrections prevent a high-yield root from depressing
the apparent return of its children and prevent exhausted trees from claiming
they were stopped. A full recalibration of
`saturation` is still residual.

## 11. Durability (requirements DUR-*)

- **DUR-01** With `--data <dir>`, deliberations survive restarts, including
  `kill -9`; without it the app is volatile. Only **inputs** are durable:
  the structure — every claim and edge, once, in creation order — in one
  append-only log `graph.jsonl` (a torn last line is cut off on boot), and
  the engine's metadata, which includes the `jev` stances, in the host
  journal (`host.journal`, write-ahead, synced per frame). Nothing derived —
  no credence vector, influence or hub update — is ever written: the
  metadata cell is the only journaled cell on the host (a per-cell journal
  selector), every credence cell is volatile, and on boot the graph
  recomputes every credence from the structure and the re-applied stances,
  with catch-up baselines enabled. A restart reproduces every layer's
  credence and every consensus (within 1e-9), restart after restart.
- **DUR-02** The engine's per-claim metadata (question membership, status,
  override, proposer, rewritten text, Jev judgments — plausibility and edge
  strength are the `jev` stances —, saturation, triage counts, rounds,
  errors) is one record per claim of named fields, written as routed
  invocations into a hosted observation cell (a last-writer-wins fold per
  field). Only the fields that changed are written (a field back at its
  default is written as a removal), every 100 ms and when the engine closes
  (before its workers are interrupted); the text is written only when a
  rewrite changed it, since the structure log holds the original. The engine
  seeds what it last wrote from the state it loaded, so an unchanged record
  is never rewritten. After a restart the host journal replays into the fold;
  a fence record tells the app when the replay has been folded. The journal
  compacts itself to one checkpoint of the fold when **quiescent** — writes
  held off and a fence folded, so every frame it holds has been applied: at
  boot after the replay, at shutdown, and whenever it has grown by more than
  64 KB and its own last checkpoint size.
- **DUR-03** On restart the trees are rebuilt from the structure (claims and
  the edges placing them, in creation order) plus those records. A claim
  whose record never reached the journal is rebuilt from the structure alone
  and queued afresh; a claim created without the edge that places it (the
  process died between the two writes) is left out. Each question's EXP-10
  record (its yields and whether it stopped) is one more record of the same
  store. Every known stance is
  re-applied (the graph skips a stance a node already holds). Every claim
  that was `QUEUED`, `JUDGING` or `EXPLORING` is re-queued — an interrupted
  round simply runs again — and an argument whose attach-time assessment
  never completed is assessed first.
- **DUR-04** A data directory in the earlier one-agora-graph-per-layer format
  (`graph-<layer>.jsonl`) is refused at startup with a message; it is not
  migrated.

## 12. Cost (requirements COST-*)

- **COST-01** Every external call records what it used, attributed to the
  question whose claim caused it: the engine binds that question's usage sink
  around every Judge, Proposer and Merger call (`Usage.within`), and the
  adapters report into it — so the `Proposer`/`Judge`/`Merger` interfaces
  carry no cost plumbing, and a call that hands work to another thread (Jev's
  parallel plausibility request) passes the sink along. Reading usage never
  fails a call: an unreadable usage is logged and dropped.
  - **Claude CLI** (proposer and merger) runs with `--output-format json`:
    the answer is the envelope's `result` (parsed as before, EXP-09), an
    envelope with `is_error` fails the call, and the usage is `usage`
    (`input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`,
    `output_tokens`), the models are the keys of `modelUsage`, and the cost is
    the CLI's own `total_cost_usd`.
  - **Codex CLI** runs with `--json`: stdout is a JSONL event stream whose
    `turn.completed.usage` (`input_tokens`, `cached_input_tokens`,
    `cache_write_input_tokens`, `output_tokens`, `reasoning_output_tokens`) is
    summed over the call's turns; the answer is still read from `-o`.
    Cached input and cache writes are part of `input_tokens`, and reasoning
    tokens are part of `output_tokens` (verified live: a 13-reasoning-token
    answer reported 20 output tokens where the same answer without reasoning
    reported 7), so neither is counted twice.
  - **Jev**: every successful response's `usage` (`input_tokens`,
    `output_tokens`) is one call; a retried 429/529 is not.
- **COST-02** Prices (flags; defaults below, each shown with its source and
  date):
  - Codex `gpt-5.6-sol` (`--codex-input-rate`, `--codex-cached-rate`,
    `--codex-output-rate`, USD per 1M tokens): $4.00 input, $0.40 cached
    input, $20.00 output; reasoning billed as output; cache writes 1.25×
    input; a call whose longest prompt exceeds 272K tokens pays 2× on input
    and 1.5× on output. Source: developers.openai.com/api/docs/models/gpt-5.6-sol,
    looked up 2026-09-27 (the input price is promotional through at least
    2026-11-21). With another `--codex-model` the rate flags must be given,
    else the rate is **unknown**: its tokens are shown, its cost is left out
    of every total and the details say so.
  - Jev (`--jev-input-rate`, `--jev-output-rate`): $0.042 per 1M input
    tokens, output free — a third-party listing (OpenRouter
    typesafe/jev-1.13, MindStudio), since TypeSafe publishes no pricing, so
    it is labelled **assumed**.
  - Claude: the CLI's reported `total_cost_usd`, labelled "API-equivalent as
    reported by Claude Code; not your bill if you use a subscription".
- **COST-03** Every question reports `costUsd` (the sum of its priced calls),
  `projectedUsd` (`costUsd` + claims still `QUEUED`/`JUDGING`/`EXPLORING` ×
  the mean cost per completed round in the question — one more round each;
  null until the question completed 3 rounds) and `cost`: per backend its
  calls, tokens by kind, USD, the rate applied, its source and date, whether
  it is assumed, and its caveat, plus the rounds, queued claims and cost per
  round behind the projection. A question restored from a durable record made
  before cost tracking is marked incomplete: it has no projection, and any
  newly tracked spend is only a lower bound.
- **COST-04** The cost is durable (DUR-02): per question and backend one
  aggregate counter set (calls, token sums, USD, unpriced calls, models) —
  never a per-call log — stored in the question's record as one field per
  backend (`cost.claude`, `cost.codex`, `cost.jev`), so a call rewrites only
  its backend's field. A restart restores it unchanged.
- **COST-05** UI: the question header shows only the dollar figure (e.g.
  `$0.84`; `<$0.01` below a cent), subtle and in tabular figures. It is a
  button; it opens a compact popover — per backend calls, tokens, estimated
  cost, the price with its source and date (marked *assumed* where it is),
  the projection ("≈$2.10 if the 9 queued claims are explored") and the
  Claude subscription caveat — that closes on Escape or a click outside.
  A restored pre-cost question shows `—` and “cost not tracked for this
  question (created before cost tracking)”; after new tracked calls it shows
  “at least $X (earlier rounds not tracked)”.
  `?mock` shows plausible figures. The legend says what the figure means.
