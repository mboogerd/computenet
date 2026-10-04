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
- **Link** — the claim an edge makes: "“<child>” is a reason for|against
  “<parent>”". Every argument's edge is one; it is explored like a claim
  (§3 "Links as claims").
- **Undercutter** — a claim that does not dispute a claim but denies that one
  of its arguments bears on it ("this does not show that"): an argument
  *against that argument's link*, its `ATTACK` edge targeting the argument's
  *edge* (agora edges are claims), lowering the edge's credence and with it
  the argument's influence (EXP-03 `UNDERCUT`). A **link supporter** is the
  converse: a `SUPPORT` edge on the link ("why this does bear on it").
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
- **Contribution** — reach × relevance × quality (EXP-05); 1 for the root.
  Shown for reference only: since model C it no longer orders exploration or
  gates a claim (see **Value of information**).
- **Sway** — the signed exact secant `R(node=1) − R(node=0)` shown through the
  wire-compatible `NodeDto.sensitivity` field: which way, and by how much, an
  answer root can move when the node resolves.
- **Value of information (VoI)** — the exact, propagated-credence-weighted
  expected root movement from resolving a claim or link (§3 "Exact value of
  information"). It is a claim's priority in the exploration queue and, once
  it falls below
  `--voi-eps` for every remaining node in a question, the question's stop
  condition — replacing contribution-based ordering, `minInfluence`,
  `maxDepth` as a stop rule and the yield stop (below).
- **Yield** — what one round returned, per argument asked for (EXP-10);
  recorded and shown (`yieldRounds`/`yieldRecent`/`yieldEarlier`) for
  reference only — since model C it no longer stops a question (see
  **Value of information**).
- **Override** — the human's per-claim setting: `AUTO` (Jev decides),
  `EXPAND` (force expansion), `STOP` (force no further expansion).

## 2. Credence model (requirements CRED-*)

- **CRED-01** Every claim receives a Jev *plausibility* judgment — a Score over
  five ordered levels (almost certainly false … almost certainly true) mapped
  linearly to [0,1] — judged on a state holding only `claim`: no root question,
  path, parent, direction or date, and not its arguments (question context
  biases the judgment of a reusable claim; evidence in `CALIBRATION.md`). It
  is applied as the stance of user `jev` on that claim. An argument is judged
  the moment it is attached: plausibility
  in its own request, in parallel with one request asking its CRED-02
  strength and EXP-05 quality and relevance (independent questions over the
  argument's full state); the root, or an argument whose assessment failed, is
  judged when its expansion starts. **Model D:** the same request also asks a
  `knowledge` Choice — `WITHIN_MY_KNOWLEDGE` / `OUTSIDE_MY_KNOWLEDGE`, does
  judging `claim` need knowledge Jev does not have — and `OUTSIDE_MY_KNOWLEDGE`
  maps the plausibility to `Judge.OUTSIDE_KNOWLEDGE` (0.5) whatever the score
  says; an unrecognised answer fails the call. No Jev request carries a
  current date, on any judgment kind: the judgment rests on Jev's own
  knowledge (a date moved the judgment of claims about recent events). The
  stance is journaled input (DUR-01): restoring a claim keeps the plausibility
  recorded by an older request and does not ask Jev to reinterpret it.
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
  Layers (all eight always run): `dfquad` (agora's
  DF-QuAD), `wlo` (weighted log-odds: σ(α·logit(base) + k·(‖S^γ‖_p −
  ‖A^γ‖_p)), α = 1, k = 2.4, p = 2, γ = 1.3), `jnb` (Jeffrey / naive-Bayes:
  the argument's likelihood ratio LR(s) = ((1+s)/(1−s))^K is Jeffrey-
  conditioned on its source's credence c, energy ln(c·LR + (1−c)·LR^−r),
  exact because s and c arrive separately), `woe` (log-odds DF-QuAD, weight
  of evidence −ln(1 − e)), `euler` (Euler-based), `qe` (quadratic energy),
  `mlp` (MLP-based), `glo` (gated log-odds: an argument's energy is
  2·atanh(min(s·u(c), 0.999)) with the gate u(c) = max(0, 2·σ(5·logit c) − 1),
  so a source at or below ½ is inert; σ(logit(base) + ‖S‖₂ − ‖A‖₂), support and
  attack weighed alike — the rule the credence benchmark selected,
  `doc/research/deliberate-credence-bench`); formulas and defaults of the first
  seven as in the prototype `semantics.js`, and every layer keeps agora's base
  (the clamped mean of the stances). `dfquad` always runs. Cycle handling is agora's: the edge that
  closes a cycle is its head and absorbs a returning source update whose
  largest per-layer change is below the quiescence threshold; a node's
  arguments are folded in ref order, so emission is deterministic. Every
  message carries a magnitude — the largest per-layer change — that the host
  schedules by.
- **CRED-05** A node's **consensus** is σ(mean over the member layers of
  logit(cᵢ)), each cᵢ clamped to [0.001, 0.999] — the geometric mean of their
  odds. Members (default `wlo,jnb,woe`: the rules that pass
  every intuition check D1–D5c at their defaults). Its **spread** is the
  [min, max] of the credence over *all* layers. The consensus is a summary:
  nothing feeds it back into any layer or into a parent. The UI's headline
  number and verdict are the consensus, drawn over the spread as a band.
  `--semantics` names what `NodeDto.credence` shows: `consensus` (the
  default) or one layer id. No averaging layer beyond this consensus exists.
- **CRED-06** The consensus and spread are **derived by the cells**: a
  claim or edge cell emits `{vector, consensus, spreadLow, spreadHigh}` with
  every change of its vector, and the graph's hub folds those emissions. The
  consensus is a pure function of the vector, so computing it where the
  vector is computed is the simplest derived form — no second cell per node,
  no second hop, no second fold. The snapshot only reads the hub.
- **Model D — the arguments-first neutral-prior vector.** Every claim and edge
  cell also evaluates every layer with its local base shrunk towards a neutral
  prior before its direct arguments are weighed: `base' = LayerSet.NEUTRAL_PRIOR +
  LayerSet.WEAK_PRIOR_WEIGHT * (base - LayerSet.NEUTRAL_PRIOR)`, with
  `NEUTRAL_PRIOR = 0.5` and `WEAK_PRIOR_WEIGHT = 0.0` — a fully neutral base,
  the smallest choice (a weak, non-zero prior would be `0 < w < 1`). This
  rides the node's own `Credence` emission as `neutral`, computed the moment
  its ordinary vector is, from the same stances and the same ordinary attack
  and support inputs. A node with no incoming arguments instead copies its
  ordinary vector: no argument means no invented neutral standing. A question
  or reading root's `neutralCredence` keeps its earlier value, the neutral ½
  evaluation, until the root has an argument. The
  arguments-first vector never feeds an influence, queue, verdict
  or another layer; `priorWeight == 1.0` leaves every ordinary vector exactly
  as before. The cached prior-dominance measurement in `CALIBRATION.md` is
  design evidence for exposing this diagnostic view, not evidence that it
  improves answer accuracy.

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
  - `REFINE` — a specific instance of / evidence for the target: recorded as
    an evidence entry on the target claim itself (model B) — no claim is
    created and no budget is reserved;
  - `OTHER_SIDE` — argues the opposite side: attached there. In a link round,
    a genuine counter-argument is not attached to the link: it is attached as
    an `ATTACK` on the link's parent claim (or `DROP`ped when it is not a real
    counter-argument);
  - `UNDERCUT` — does not dispute the claim but denies that the target
    argument bears on it: attached as an undercutter — a con argument of the
    target's *link* (§3 "Links as claims"), an `ATTACK` edge targeting the
    target's *edge*. Like every argument of a link it sits one level below the
    link (the link is at the target's depth), its path runs through the link's
    text, it is assessed against the link (its `parent_claim` is "“X” is a
    reason for|against “Y”"), and explored like any claim. A link holds at
    most one per-side cap of undercutters;
  - `DROP` — not a real argument about the claim (off-topic, incoherent, a
    question, a restatement of the claim).

  REPLACE and MERGE only rewrite a target nobody has explored yet (still
  `QUEUED`, no children); otherwise, or if the merge call fails (error
  recorded), they fall back to `DUPLICATE`. A targeted action without a target
  becomes `ADD` (`DUPLICATE`: dropped). The claim counts each action taken in
  `triage`. Rewording lives in the deliberation layer (the graph's claim text is
  immutable); if the triage request fails, every candidate is `ADD`.

  **Premise vs. bearing (model B).** After triage, every candidate proposed as
  a con (`ATTACK`) that triage resolved to `ADD`, against a claim whose
  plausibility is at or above `BEARING_PLAUSIBILITY` (0.8, a named constant —
  a starting value from a scratch model review, not a calibration run), is
  additionally asked a Choice: does it dispute the claim, deny that the
  argument bears on it, or neither? A candidate triage already placed more
  specifically (`DUPLICATE`, `REPLACE`, `MERGE`, `REFINE`, `UNDERCUT`,
  `OTHER_SIDE`) or dropped keeps that verdict — the Choice is asked only where
  triage's answer was the generic `ADD`. "Disputes the claim" leaves the `ADD`
  unchanged; "denies bearing" reroutes it to `UNDERCUT` of the claim's *own*
  link (not a con child of the claim); "neither" reroutes it to `DROP`. A
  failed Choice call keeps triage's verdict (EXP-08).
- **EXP-04** After each round, Jev judges **saturation** per side (a Noul:
  "is an important consideration on this side still missing from the existing
  arguments?", read as saturated = 1 − p). A side is saturated when that value
  ≥ `saturation` (default 0.22, calibrated on live Jev samples, see §10), or
  when it already holds its cap: `maxArgsPerSide` (default 6) for the root,
  `maxArgsPerSideChild` (default 3) below it. **Balance:** a side below its
  cap that holds fewer arguments than the other side is never saturated by
  Jev's judgment (Jev's saturation reads systematically higher for con; see
  `CALIBRATION.md`). Saturation is
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
  `reach × relevance × quality`, shown for reference only — since model C
  (§3 "Exact value of information") it does not gate or order
  exploration; there is no contribution floor and no `DONE`/`PRUNED`
  outcome from contribution (the `minInfluence` flag is removed). An undercutter's reach is
  `reach(parent) × strength(its edge) × strength(the undercut edge)`. Quality
  carries no canonical-form factor: canonical form is asked of the proposers
  only (EXP-02; why, in `CALIBRATION.md`). If the assessment fails, strength
  0.5 is used and relevance and quality count as 1. The root is always
  expanded. `maxDepth` survives only as an internal engine bound (unbounded by
  default) that tests use to keep a fake-driven tree small; it has no CLI flag
  and a claim finished with reason `DEPTH_LIMIT` does not otherwise arise in
  practice.
- **EXP-06** A global `maxClaims` budget (default 180 per question) is enforced:
  no argument is attached once the tree holds that many claims. The budget is
  spent in exploration order (below), so it goes to the highest
  value-of-information claims first. Gates run in the order links-off → depth
  → budget → value of information (below); a claim that meets the cap ends
  `DONE` with reason `BUDGET` whether or not it already ran a round — `rounds` still tells the
  two cases apart. A question at its budget reports `stoppedBy = "budget"`. The budget is a
  hard ceiling that stands beside the value-of-information stop below; it is
  checked first, so it wins over a claim whose value of information is still
  high.
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
- **EXP-10** Each round of any **non-root** claim in the question that asked
  for at least one argument still records a **yield**: Σ over the arguments it
  attached (REFINE and UNDERCUT included) of `strength × relevance × quality`
  (the EXP-05 fallbacks for a failed assessment), × `1 − (DUPLICATE + DROP) /
  triaged` (the round's triage counts, exact-text repeats included; 1 when
  nothing was triaged), ÷ the number of arguments asked for. Root rounds are
  excluded because their naturally high yields otherwise inflate the earlier
  mean. The question keeps its non-root yields in completion order and
  reports `yieldRounds` (their count), `yieldRecent` (mean of the last
  `yieldWindow`, default 8) and `yieldEarlier` (mean of the rest) — for
  reference only. **Since model C (below) yield no longer gates or stops
  anything**: there is no yield ratio, no minimum-claims threshold and no
  `--yield-stop` flag (removed); the yields remain durable (DUR-02) purely as
  a diagnostic.

### Exact value of information

Value of information is a pure, on-demand two-point re-evaluation of the
credence graph snapshot. For candidate node `n`, let `q` be its propagated
headline credence, `R` an active answer root's current headline, and `R₀` and
`R₁` that root after replacing every layer of `n` by 0 or 1 and recomputing
every dependent node from its replayed stances and influences. Every path to
the root participates; converging paths are evaluated together. The root's
exact value is:

`VoI(n, R) = 2 · (q·|R₁−R| + (1−q)·|R₀−R|)`.

The factor 2 preserves model C's scale: on a locally linear path this equals
`|dR/dn|·4q(1−q)`. A claim reachable from several active answer roots uses the
sum of `VoI(n, R)` over those roots. Results are memoised per credence/topology
version. `NodeDto.sensitivity` keeps its wire shape but carries the signed exact
secant `R₁−R₀` for the root to which the node has the largest VoI; no derivative
or sensitivity cell feeds display, scheduling or stopping. Exact VoI is derived,
never journaled (DUR-01), and a restart recomputes it from the restored structure
and stances.

This value, decayed by `roundDecay^(rounds already run)`, **is the
claim's priority in the exploration queue** (below), replacing the earlier
reach × relevance ordering; and once it falls below `--voi-eps`
(`DEFAULT_VOI_EPSILON` = 0.01 — a starting value from a one-off scratch
review, not a calibration run) the claim gets no (further) round and ends
`DONE` with reason `DIMINISHING`; the UI wording is "not worth exploring" /
"nothing left could change the answer". A question therefore stops once the
largest value of information over its remaining nodes falls below `--voi-eps`
— reported `stoppedBy = "voi"` — with `maxClaims` (EXP-06) still standing as
a hard cap checked first. `--voi-eps 0` disables the value-of-information
stop; `maxClaims` alone then bounds a question. **This replaces `minInfluence`
(removed), `maxDepth` as a stop rule (removed as a flag; the field survives
only as an unbounded-by-default internal bound, EXP-05) and the yield stop
above (`--yield-stop`, removed) as a question's stop rule.**

**Cruxes.** `QuestionDto.cruxes` lists up to 3 refs of the question's nodes
(claims below the root, and links as `EDGE` refs) by that exact VoI without
round decay — best first, ties in creation order. The UI's "what would change
the answer" panel (§7) lists them.

### Exploration order

Work is one priority queue across all questions; each task is one round of
one claim. A claim's first round is queued at its value of information (the
root at 1); after each round a claim that is not finished goes back into the
queue at its value of information decayed by `roundDecay^rounds` (`roundDecay`
default 0.5), so a strong claim's second round competes fairly with a weaker
sibling's first. Ties go first-in, first-out. An argument is queued, at its
value-of-information priority, as soon as its attach-time assessment
completes — at the end of the round that attached it, since a later turn may
still reword it; it does not wait for its parent to finish later rounds.
Because credences change as arguments settle, exact priorities are recomputed
from the current snapshot when a worker takes the next task, not fixed when a
claim is enqueued. A claim
the human forces with `EXPAND` is queued ahead of everything else
(`FORCED_PRIORITY`). Links (below) share this one queue.

### Links as claims

Every edge is also a claim — its **link**: "“<child>” is a reason for
“<parent>”" (SUPPORT) or "… is a reason against …" (ATTACK). The engine builds
that text from the two ends (it is never stored, so a REPLACE or MERGE of the
child rewords its link), and explores the link exactly like a claim:

- **LINK-01 Arguments.** A link's pro arguments say why the connection holds
  ("why this does bear on the parent"), its con arguments why it fails — the
  undercutters of EXP-03. Both attach by `SUPPORT`/`ATTACK` edges that target
  the *edge node* (the cell model propagates edge-targeted edges: a link's
  arguments move the edge's credence, and with it the argument's pull on its
  parent). A link's `jev` stance is its argument's CRED-02 strength; a link is
  never assessed itself.
- **LINK-02 Place.** A link's parent (for paths and context) is its argument's
  parent, and its depth its argument's depth; its arguments sit one level
  below, their path running through the link text, and each is assessed
  (CRED-01/02, EXP-05) with the link text as `parent_claim`. Arguments about
  a link have links of their own.
- **LINK-03 Contribution and value of information.** `contribution(link) =
  contribution(argument) × 4·s·(1 − s)`, with `s` the argument's CRED-02
  strength (0.5 when that judgment failed: factor 1) — shown for reference
  only, like a claim's contribution (EXP-05). `reach(link) = reach(argument)`.
  A link's **value of information** follows the same formula as a claim's
  (§3 "Exact value of information"): `q` is the edge node's propagated
  credence, and resolving it to 0 or 1 re-evaluates every path from that edge
  to each active answer root. A clear-cut link therefore has little or no
  expected value, while an open link that can move an answer is explored early.
  An argument about a link then has
  `reach = reach(argument) × strength(its own edge)` — for an undercutter,
  exactly EXP-05's formula.
- **LINK-04 Scheduling.** A link joins the queue with its argument, once the
  argument's attach-time assessment completed (its strength is then known),
  at its value of information; the links-off, depth, budget and
  value-of-information gates (§3) apply as to a claim. `--explore-links off`
  (default on) keeps links from being explored automatically — they end
  `DONE` with reason `PRUNED` — while `EXPAND` still explores one.
- **LINK-05 Rounds.** A link's round is EXP-02..04 with the link as the claim:
  per-side cap `maxArgsPerSideChild`, triage against the link's own
  arguments (EXP-03, so duplicates are caught against them; the claim-level
  `UNDERCUT` re-targeting of EXP-03 is unchanged and lands here), saturation
  per side, yields (EXP-10) recorded like any non-root round. Proposers get
  `ClaimContext.link` (the argument, the parent, the direction) and a prompt
  variant (`CliProposer.linkPrompt`, same canonical rules and examples): FOR
  asks why, if the argument is true, it really does count as a reason for/
  against the claim; AGAINST why, even if true, it does not — neither may
  dispute the argument or argue the claim on other grounds. Its prompt gives a
  topic-neutral example distinguishing an undercutter from a counter-argument,
  and link triage sends a genuine counter-argument to `OTHER_SIDE` against the
  parent claim (or `DROP`) rather than attaching it to the link.
- **LINK-06 Accounting.** A link is part of its question's work, not of its
  claims: its rounds count in `cost.rounds`, its calls are billed to the
  question (COST-01), an active link keeps the question `active` and counts in
  `cost.queued`, but `QuestionDto.claims` and the `maxClaims` budget count
  claims only (the arguments a link gets are claims and count). This remains
  bounded even with `--voi-eps 0` (the value-of-information stop disabled):
  every non-root claim has exactly one link, so a question under `maxClaims`
  has at most `maxClaims − 1` links, and
  the budget gate prevents another automatic link round once the claim ceiling
  is reached. Human-forced CTL-02 rounds remain deliberately outside the cap.

### Framing (model A)

Before a question root runs its first round, it may be **framed**: split into
several readings of an ambiguous term, or several competing answers to an
open question, each explored as a root of its own.

- **FRA-01 Framing call.** Once per question root, before round 1 (never on a
  reading/position — a root-like claim of the same question — or a link), the
  root is asked a [`Framer`] (`CliFramer` over the `claude` CLI, EXP-08/EXP-09
  process gate and COST-01 billing apply like any proposer/judge call), which
  answers `NONE` (one natural reading — explored as asked), `READINGS`
  (the question is ambiguous: up to `Framing.MAX_READINGS` (3) restated
  yes/no questions, each fixing one sense of the ambiguous term) or
  `POSITIONS` (the question is open — what/which/how/why, not yes/no: up to
  `Framing.MAX_POSITIONS` (5) mutually exclusive declarative answers). Fewer
  than 2 items in a `READINGS`/`POSITIONS` answer is treated as `NONE`. A
  failed call (the root records `error = "framing: …"`) also explores the
  question unframed. Prompt hygiene follows EXP-02: canonical, topic-neutral
  examples only.
- **FRA-02 Readings/positions are question-root claims.** Each item becomes
  its own claim of the same question — `root` = the original question's ref,
  `parent = null` (a second root of the same tree, `proposer` = `Claim.READING`
  ("reading") or `Claim.POSITION` ("position") by mode) — so it inherits, with
  no engine change beyond creating it: its own CRED-01 first impression
  (`Judge.plausibility`, judged from the item's self-contained restated text
  alone), its own model D neutral-prior verdict
  and disagreement flag, its own exact-VoI answer root (value 1, like any
  root), and the shared budget (EXP-06), cost
  (§12) and pause (CTL-05) of the question. The question root itself takes no
  round: it finishes `Status.FRAMED` (§5) the moment framing succeeds, and an
  `EXPAND` on it runs no round either.
- **FRA-03 POSITIONS: the issue cell.** A `POSITIONS` root additionally gets
  one `IssueNode` cell, fed by every position's credence outlet, emitting
  `Shares` (per layer, and the consensus) to a `sharesHub` — derived and
  volatile like credence (DUR-01), never journaled, and wired **one-way**:
  nothing flows from it back into any credence cell (CRED-03).
  `Softmax.shares` (the object keeps its original name, but is no longer a
  softmax) treats each clamped credence as an absolute weight and divides by
  `max(1, sum(weights))`. The listed shares therefore sum to at most 1; the
  residual `1 - sum(shares)` is the share that none of the listed answers
  holds. Two complementary positions `(p, 1-p)` retain shares `(p, 1-p)`;
  first impressions (0.15, 0.2, 0.7) simply normalise to (0.143, 0.190,
  0.667); and jointly implausible positions (0.1, 0.1, 0.1) leave 0.7 for
  none of the listed answers. Before any position has emitted, its weight is
  0.5; the listed shares are therefore 1/n when `n > 2` and (0.5, 0.5) when
  `n = 2`, with no residual. `READINGS` spawns no `IssueNode`: each reading's
  verdict is its own credence, with no shares to fold.
- **FRA-04 Durability.** `CredenceGraph` applies one `GraphSpec` delta for an
  issue framing — the position claims, the `IssueNode` when present and every
  wire — as one write-ahead topology record, with the
  refs pre-allocated. The delta is atomic at journal admission: if its record
  is absent on restart, none of that framing's topology is rebuilt; if it is
  present, the whole framing is restored under its recorded refs before
  metadata frames replay. The root claim's record carries
  `framingMode`/`framingTerm` (null when unframed); on restart a root whose
  complete issue delta was not journaled restores `QUEUED` and is framed again
  (EngineRecords).

## 4. Human control (requirements CTL-*)

- **CTL-01** The human can set any claim's — or link's (§3 "Links as
  claims"; the `/override` id is then the edge ref) — override to `AUTO`,
  `EXPAND` or `STOP` at any time. CTL-02..04 apply to a link unchanged.
- **CTL-02** `EXPAND` always explores: whatever the claim's status —
  queued, running, or `DONE` for any reason including `BUDGET` and
  `DIMINISHING` — its next
  round is **forced**, and it runs at least that round. It skips every
  scheduling gate (links-off, depth, budget and value of information), is
  queued ahead of everything else (`FORCED_PRIORITY`), and raises the claim's
  round limit by one if needed. The forcing is not durable: an `EXPAND` whose
  round a restart interrupted is not resumed — the claim restores like any
  other and the human expands it again. The forced round ignores Jev
  saturation, the round limit, `maxClaims` and the value of information: it
  has its own allowance of up to one per-side cap of new arguments per side
  (shared by the proposers' turns), and they are attached even when the tree
  is at its budget. Triage still applies. The new arguments then face the
  gates like any other.
- **CTL-03** `STOP` cancels queued work for that claim and prevents future
  rounds; an in-flight round finishes but its results are still attached
  (arguments are never silently dropped once produced). Status becomes
  `STOPPED`. Descendants are not affected — except on a **question root**,
  where `STOP` ends the whole question: every claim and link of it that waits
  for a round (`QUEUED`, or `EXPLORING` with rounds left) ends `STOPPED` at
  once and no pending forced round survives; a round in flight anywhere in
  the question finishes, its arguments are attached and assessed, and they —
  like its claim, at the round boundary — end `STOPPED` instead of being
  queued. The question is no longer `active` once its rounds in flight end,
  and reports `stoppedBy = "human"` (the hero reads "stopped by you", and the
  hero's Stop control says it stops the whole question). `EXPAND` on a claim of a stopped question
  is still CTL-02 (on the root: the root's round only); what it attaches ends
  `STOPPED` too. The stop is durable: it is part of the question's record
  (DUR-02), and a restart restores the question stopped — whatever the
  restart interrupted ends `STOPPED`, nothing is re-queued. It is independent
  of the pause (CTL-05). A framed root (§3 "Framing") follows the same rule
  over its readings/positions, though the UI offers it no control (UI-09).
- **CTL-04** `AUTO` returns the decision to Jev; setting it on a `STOPPED`
  claim re-queues it through the normal gates. On the root of a stopped
  question it restarts the question: every claim and link the stop ended
  `STOPPED` (any whose own override is not `STOP`) is re-queued through the
  normal gates, exactly as a restart would (DUR-03) — an argument never
  assessed is assessed first — and `stoppedBy` clears.
- **CTL-05** The human can **pause** and **resume** a whole question
  (`POST /question/pause`). A paused question starts no new round: a round
  in flight finishes and its results are attached and assessed (as CTL-03);
  its queued claims and links stay `QUEUED` — and a claim with rounds left
  stays `EXPLORING` — without being dequeued, and no gate is applied to them
  meanwhile; an argument restored unassessed (DUR-03) is not even assessed.
  A forced round (CTL-02 `EXPAND` on one claim or link of the question) still
  runs — the human asked for exactly that — and the arguments it attaches
  wait like the rest. Resuming re-schedules everything the pause withheld
  through the normal gates, exactly as a restart would (DUR-03). The pause is
  durable: it is part of the question's record (DUR-02) and survives a
  restart. `QuestionDto.paused` reports it; `active` still reports queued
  work, so a paused question with queued claims is active and paused.

## 5. Claim status (the state machine the UI renders)

The wire status vocabulary is exactly `QUEUED`, `JUDGING`, `EXPLORING`,
`FRAMED`, `DONE`, `STOPPED`, `FAILED`. Normal automatic completion is
`QUEUED → JUDGING → EXPLORING → DONE`; a `DONE` claim carries exactly one
reason from `SATURATED`, `ROUND_LIMIT`, `PRUNED`, `DEPTH_LIMIT`, `BUDGET`,
`DIMINISHING`. `reason` is null for every non-`DONE` status. `STOPPED` and
`FAILED` remain distinct terminal statuses. `FRAMED` is also terminal and is
used only for a question root reached from `QUEUED` in place of `JUDGING`
(model A, §3 "Framing"): the root was split into readings or positions, each
explored as a root of its own; the root itself never runs a round. Therefore
the engine's finished set is exactly `{DONE, STOPPED, FAILED, FRAMED}`. Every
status or reason change is broadcast.

## 6. HTTP surface (the UI contract)

- `POST /question` form `text=` → `{"root":"<ref>"}`
- `POST /override` form `id=<ref>&mode=AUTO|EXPAND|STOP` → `ok` (a claim ref,
  or an edge ref for its link)
- `POST /question/pause` form `root=<question ref>&paused=true|false` → `ok`
  (CTL-05); 400 on a malformed ref or flag, 404 on a ref that is not a question
- `GET  /graph` → `GraphDto` (see `Dto.kt`): every node carries its
  `credences` per layer, its `consensus`, `spreadLow` and `spreadHigh`, and its
  model D `argumentsFirstCredences` per layer and
  `argumentsFirstConsensus` (equal to the ordinary vector/consensus when the
  node has no incoming arguments), and its wire-compatible `sensitivity`
  (the exact signed sway `R(node=1)−R(node=0)`, §3); an
  undercutting claim carries `undercuts` (the edge it attacks, which is also
  its edge's `target`) and every argument about a link carries `onLink` (that
  edge); a claim carries `evidence` (model B REFINE outcomes) when it has any;
  an EDGE carries its link's claim-like fields (`text`, `depth`,
  `status`, nullable `reason`, `override`, `reach`, `contribution`, `proSaturation`,
  `conSaturation`, `rounds`, `duplicatesDropped`, `triage`, `error`); every
  claim and link carries `activity` while it is being explored, judged or
  assessed; the graph carries `consensusMembers`; every question
  carries `yieldRounds`, `yieldRecent`, `yieldEarlier` (EXP-10, informational)
  and `stoppedBy` (`"human"` — STOP on its root, CTL-03 —, `"budget"`,
  `"voi"` or null; model C, §3), `paused`
  (CTL-05), `cruxes` (model C, up to 3 refs for "what would change the
  answer"), `costUsd`, `projectedUsd` and `cost` (§12), and — model D —
  `firstImpression` (Jev's plausibility of the question itself, judged before
  any argument; null until judged), `neutralCredence` (the root's headline
  credence with the same arguments weighed from the neutral prior instead;
  null until the root cell emits) and `verdictsDisagree` (true when the root's
  ordinary credence and `neutralCredence` fall strictly on different sides of
  0.5 — `LayerSet.oppositeSides`; a value of exactly 0.5 on either side is on
  neither, so it never sets the flag), and — model A, §3 "Framing" — `framing`
  (`FramingDto { mode: "READINGS" | "POSITIONS", term?, positions:
  PositionDto[] }`; null when the question was explored as asked, and then
  the model D fields above are the question's own; when set they are null/false
  on the question and each `PositionDto { ref, text, credence, firstImpression?,
  neutralCredence?, verdictsDisagree?, share? }` carries its own — `share`
  only for `POSITIONS`, absent for `READINGS`; listed shares may sum below 1,
  and the remainder is the derived none-of-the-listed share). A node's
  `positionOf` (set
  only on a reading/position, to its question's root ref) marks it as one.
- `GET  /events` → SSE, each message a full `GraphDto` (coalesced, ≤ 10/s)
- `GET  /` → the built UI (`ui/dist`) when present.

## 7. UI (requirements UI-*)

- **UI-01** A single input where the question is typed and submitted.
- **UI-02** The deliberation graph of the selected question is shown as a tree
  rooted at the question, growing live via SSE with no reload.
- **UI-03** Each claim shows its text, its credence (bar/number: the
  consensus, with the spread as a thin band), its status, the proposer that
  produced it, and its override control. Each argument shows its polarity
  (pro/con visually distinct) and relation strength. The facts panel lists
  every layer's credence, marks the consensus members, and says how far the
  rules agree; for a claim or link with no arguments yet it says "no
  arguments yet — all rules agree with the first impression" (the spread is
  zero by construction, not a bug), and its bar marks the single value.
- **UI-04** Minimal, modern, slick: a calm neutral palette, pro/con as the only
  saturated colours, smooth enter animation for new nodes, light and dark mode.
- **UI-05** The connector between a claim and an argument is a control: it
  shows the link strength and how many arguments the link has; hovering or
  focusing it previews the link as a claim, pressing it (click, tap, Enter)
  opens it — its text, credence (the edge's) with its spread, status, its
  own Auto/Expand/Stop and its numbers. The link's arguments — "why it holds",
  and "why it fails" (undercutters, labelled "undercuts the link") — are drawn
  under the link, dashed and tagged "link", never under the claim.
- **UI-06** Under each question a "now" line names what the deliberation is
  doing this moment: which claims and links are being explored or judged.
- **UI-07** Under each question's tree, a "what would change the answer"
  panel (§3 "Exact value of information") lists its
  `cruxes` — the claims and links whose settling could move the answer most —
  each with its exact secant sway (`|sensitivity|`), how settled it is (its
  plausibility or, for a link, its strength) and which way resolving it true
  would pull the answer. Nothing is shown until the backend names a crux.
- **UI-08 (model D).** All eight layers are still computed for every node, but
  only the consensus is shown by default: no spread band on a claim's or the
  question's gauge, no per-layer caption, no per-rule values or tooltip text.
  A "rules" pill button in the header (`aria-pressed`, off by default) toggles
  the *research view* on for the session; `?research` in the URL starts it on
  (like `?debug`), and it is not otherwise persisted. In the research view the
  spread band, the "rules: a–b%" caption and each rule's own credence (facts
  panel "By rule") reappear exactly as before model D. Every claim and link's
  facts also compare "first impression" with its `argumentsFirstConsensus`
  and list `argumentsFirstCredences` under "Arguments first by rule"; this is
  the local diagnostic view and does not replace its ordinary credence. The
  question's hero caption reads "first impression F% · arguments alone N%" (`firstImpression`,
  `neutralCredence`; either half is left out until known), with " · rules
  a–b%" appended in the research view; when `verdictsDisagree` is true a note
  (`role="note"`) says the first impression decides the side, naming which way
  the arguments alone lean. The root claim's Facts row that shows its
  pre-argument judgment is labelled "First impression" (a non-root claim keeps
  "Plausible on its own"); the Legend explains both the rules button and
  "First impression"/"Arguments alone".
- **UI-09 (model A).** When a question's `framing` is set, its hero keeps the
  question text but hides the yes/no gauge and the model D caption/note
  (UI-08) — there is no single verdict to show — and instead shows a framing
  line: "depends on what you mean by `<term>`" (`READINGS` with a term),
  "depends on the reading" (`READINGS`, no term) or "several possible
  answers" (`POSITIONS`); `POSITIONS` additionally shows a distribution, one
  row per position in `framing.positions` order with its share as a
  percentage and a bar proportional to it; when the listed shares sum below
  1 it appends a "None of the listed answers" row for the residual. Below the
  hero, one reading/position section follows per position, in the same order:
  its text as a
  heading, its own credence gauge (the same markup and research-view band as
  the question's, driven by its own node), its own model D caption built from
  its `PositionDto` (and, when its verdicts disagree, the same disagreement
  note as UI-08), a status/override control keyed by the position's own ref,
  a Facts toggle, and its argument tree beneath it — built exactly as the
  question's tree is, rooted at the position instead. The "now" line (UI-06),
  cost (§12), pause (CTL-05) and the cruxes panel (UI-07) stay per question,
  shown once, above the reading/position sections — they already span every
  position's subtree, since a position's claims count as the question's
  (§3 "Framing", FRA-02). The hero's "N pro · M con" line is adapted too:
  a framed root has no arguments of its own (they live under its
  readings/positions), so it sums each reading/position's own direct
  pro/con arguments — the level an unframed hero counts, the root's direct
  arguments; deeper claims reply to an argument, not to the question, and
  are not counted. The hero also
  drops its own status/override control when framed, since EXPAND/STOP on a
  framed root run no round (FRA-02); each position keeps its own control
  instead. The Legend explains readings and positions.

## 8. Non-goals (v1)

Multiple users; human stances; editing claims; cross-tree links; merging
equivalent claims across branches (cross-branch claim reuse — a DAG with
contradiction detection — is deferred). (Exploring an edge as a claim of its
own is in scope: §3 "Links as claims".)

## 9. Acceptance

1. Engine tests with fake proposers/judge prove EXP-02..08 and CTL-01..05.
   Their inputs are deterministic — the fakes return scripted proposals and
   judgments, and `./gradlew :demo:deliberate:test` makes no network or CLI
   call — but the engine runs on real (virtual) threads, so the tests
   synchronise with latches and bounded waits (`awaitIdle`, `awaitUntil`)
   rather than a simulated clock, and assert outcomes, not interleavings.
2. Jev client and CLI proposer parsing are unit-tested against recorded
   payloads; a live smoke test runs only when `DELIBERATE_LIVE=1`.
3. A live manual run against a real question grows a multi-level graph whose
   credences move in the UI, and both overrides visibly work.

## 10. Calibration

Jev's gate signals are weaker and differently scaled than their prompts
suggest, so `saturation` — the one Jev-consumed threshold that still gates
anything — is set from evidence, never by intuition. A calibration run over
real claims at depths 0–3 with 0–6 arguments per side, recorded in
`demo/deliberate/CALIBRATION.md`, must show that with the defaults a side
typically saturates by 3–4 arguments. (`minInfluence`, the EXP-10 yield-stop
parameters and `maxDepth` as a stop rule are removed by model C — §3
"Exact value of information" — and appear only as history.
`--voi-eps`'s default is a starting value from a one-off scratch model
review, not a calibration run.)

The measurements, the history of each default, and what remains to
recalibrate live in `CALIBRATION.md`; this section states only the criteria.

The 2026-10-03 bounded CRED-01 recalibration paired question-context and
claim-only requests, both with the model D `knowledge` Choice, on 24 claims
from the existing three-question live corpus. One first impression (4.2%)
changed side of 0.5. The mean signed shift was +0.002; 4/24 crossed the 0.8
bearing boundary (three down, one up). This does not justify moving
`BEARING_PLAUSIBILITY` from 0.8, and CRED-01 does not change the separate
saturation request, so `saturation` remains 0.22. Sampling and caveats are in
`CALIBRATION.md`.

## 11. Durability (requirements DUR-*)

- **DUR-01** With `--data <dir>`, deliberations survive restarts, including
  `kill -9`; without it the app is volatile. Only **inputs** are durable:
  the structure — every claim and edge, once, in creation order — is carried
  by topology records in the one write-ahead host journal (`host.journal`),
  together with the engine's metadata, which includes the `jev` stances.
  Nothing derived —
  no ordinary or arguments-first credence vector, influence, hub update, or
  exact-VoI evaluation (§3 "Exact value of information") — is ever written:
  the metadata cell is the only journaled cell on the host (a per-cell journal
  selector), every credence cell is volatile, and on boot the graph recomputes
  every credence from the structure and re-applied stances; exact VoI is then
  evaluated on demand. Topology journals written by the retired model-C build
  may contain `SensitivityFactory` spawns and links: their legacy types and hub
  endpoint remain loadable, but their derivative output is ignored, while new
  topology deltas create no sensitivity cells. A restart
  reproduces every layer's ordinary and arguments-first credence and every
  consensus (within 1e-9), restart after restart. Arguments-first evaluation
  makes no model call and therefore adds no cost record.
- **DUR-02** The engine's per-claim metadata (question membership, status and
  nullable completion reason,
  override, proposer, rewritten text, Jev judgments — plausibility and edge
  strength are the `jev` stances —, saturation, triage counts, rounds,
  errors) is one record per claim of named fields — and one per link (§3
  "Links as claims": status, completion reason, override, rounds, saturation, triage, reach,
  contribution…), keyed `l:<edge ref>` — written as routed
  invocations into a hosted observation cell (a last-writer-wins fold per
  field). Only the fields that changed are written (a field back at its
  default is written as a removal), every 100 ms and when the engine closes
  (before its workers are interrupted); the text is written only when a
  rewrite changed it, since the topology factory holds the original. The engine
  seeds what it last wrote from the state it loaded, so an unchanged record
  is never rewritten. After a restart the host journal replays into the fold;
  the kernel's quiescence fence (`Recovery.awaitApplied`) tells the app when
  the replay has been folded. The journal compacts itself to one checkpoint
  of the fold when **quiescent** — writes held off and the kernel's fence
  (`ManagedHost.quiescence().await(...)`) awaited, so every frame it holds
  has been applied: at boot after the replay, at shutdown, and whenever it
  has grown by more than 64 KB and its own last checkpoint size.
- **DUR-03** On restart the trees are rebuilt from the topology fold (claims
  and the edges placing them, in creation order) plus the metadata records. A
  claim whose record never reached the journal is rebuilt from the topology
  fold alone and queued afresh; a claim created without the edge that places
  it (the process died between the two topology deltas) is left out. A
  topology delta whose record never reached the journal is absent and its
  cells are not rebuilt; a complete issue delta is restored as one graph
  construction, so the process cannot leave a half-created framing. Each question's EXP-10
  record (its non-root round yields, for reference only — its stop is
  computed live from restored claim status, not journaled) is one more
  record of the same store. Before enum decoding, restore applies this literal
  legacy field map: `SATURATED → DONE/SATURATED`, `ROUND_LIMIT →
  DONE/ROUND_LIMIT`, `PRUNED → DONE/PRUNED`, `DEPTH_LIMIT →
  DONE/DEPTH_LIMIT`, `BUDGET → DONE/BUDGET`, and `DIMINISHING →
  DONE/DIMINISHING`. The historical pair `status=ROUND_LIMIT` and
  `error="budget exhausted"` restores as `DONE/BUDGET` with the obsolete error
  removed. New-vocabulary records decode directly, and a restored legacy
  record is rewritten with `status=DONE` plus `reason` on the next metadata
  write. A record written before model C may also carry
  `diminished` (the removed yield stop), which decodes and is dropped on the
  next write. Every argument's link is rebuilt with it and its `l:` record
  re-applied; an edge targeting an edge places its source under that edge's
  link. A link whose `l:` record never reached the journal is rebuilt from
  the structure alone and queued afresh, like any other such claim. With
  `--explore-links off` a restored link — whatever status its record holds,
  an interrupted `EXPLORING` one included — ends `DONE` with reason `PRUNED`
  at the LINK-04 gate and runs no round unless expanded. Every known stance is
  re-applied (the graph skips a stance a node already holds). Every claim
  that was `QUEUED`, `JUDGING` or `EXPLORING` is re-queued — an interrupted
  round simply runs again — and an argument whose attach-time assessment
  never completed is assessed first.
- **DUR-06** `--start-paused` pauses (CTL-05) every question restored at boot
  before anything is scheduled, so a boot runs no round and no Jev call until
  the human resumes a question; questions asked afterwards run normally. The
  pause is recorded, so it outlasts the boot that set it: a later restart
  without the flag keeps those questions paused until each is resumed. No
  forced round survives the restart (CTL-02), so nothing in a restored
  question runs until it is resumed or a claim in it is expanded again.

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
  - Jev (no flag): $0.042 per 1M input
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
  round behind the projection. A question simply has the costs it recorded.
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
  `?mock` shows plausible figures. The legend says what the figure means.
