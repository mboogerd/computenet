# deliberate — goal specification

A demo in which a human poses a question and watches a **deliberation graph**
grow for it in real time: LLM agents (Claude CLI and Codex CLI) recursively
propose arguments for and against each claim; **Jev** (TypeSafe System One,
`jev-latest`) supplies every judgment that steers the exploration and every
credence; the kernel-hosted agora argumentation graph propagates those
credences. The human can override the explorer's depth decisions per claim.

## 1. Vocabulary

- **Question** — free text the human submits. It becomes the **root claim**
  (a question like "Should X?" is restated by the root as-is; no rewriting).
- **Claim** — an agora `CLAIM` node: one self-contained declarative sentence.
- **Argument** — a claim linked to a **parent** claim by an agora `EDGE` of
  polarity `SUPPORT` (pro) or `ATTACK` (con). Edge direction: child → parent.
- **Proposer** — an argument generator: `claude` (Claude CLI) or `codex`
  (Codex CLI). Every argument records which proposer produced it.
- **Judge** — Jev. Judgments are typed (score / noul / choice), never prose.
- **Round** — one pass over a claim: ask every proposer for new pro and con
  arguments on the unsaturated sides, dedupe, attach survivors, re-judge
  saturation.
- **Expansion** — running rounds on a claim until it is saturated or its round
  limit is hit, then enqueuing its children.
- **Override** — the human's per-claim setting: `AUTO` (Jev decides),
  `EXPAND` (force expansion), `STOP` (force no further expansion).

## 2. Credence model (requirements CRED-*)

- **CRED-01** Every claim receives a Jev *plausibility* judgment — a Score over
  five ordered levels (almost certainly false … almost certainly true) mapped
  linearly to [0,1] — judged on the claim alone plus the root question as
  context, not on its arguments. It is applied as the agora stance of user
  `jev` on that claim.
- **CRED-02** Every edge receives a Jev *relation strength* judgment — a Score:
  "if the child claim were true, how strongly would it bear on the parent in
  the stated direction" (irrelevant … decisive), mapped to [0,1]. Applied as the
  `jev` stance on the edge node.
- **CRED-03** Displayed credence is the agora-propagated credence (DF-QuAD over
  stances and incoming edges). The deliberation code never computes credence
  itself.

## 3. Exploration (requirements EXP-*)

- **EXP-01** Submitting a question creates the root claim and starts its
  expansion immediately. Several questions may coexist; each is its own tree.
- **EXP-02** A round asks **both** proposers, concurrently, for up to
  `argsPerCall` (default 2) new arguments per unsaturated side, giving each
  proposer the root question, the path from root to the claim, and the
  existing pro/con arguments of the claim (so it proposes *new* ones).
- **EXP-03** Before attaching, Jev judges each candidate for **duplication**
  (a Choice among the claim's existing arguments on that side plus `none`, one
  question per candidate in one request). Duplicates are dropped and counted.
  Candidates from the same round are also deduped against each other.
- **EXP-04** After each round, Jev judges **saturation** per side (a Noul:
  "is an important consideration on this side still missing from the existing
  arguments?", read as saturated = 1 − p). A side is saturated when that value
  ≥ `saturation` (default: calibrated on live Jev samples, see §10), or when it
  already holds `maxArgsPerSide` (default 4) arguments. Saturated sides receive
  no further proposals. Expansion ends when both sides are saturated or
  `maxRounds` (default 3) is reached.
- **EXP-05** Relevance decays along the tree. Each claim has a **reach**:
  1 for the root, `reach(parent) × strength(edge)` for an argument (CRED-02
  strength of the edge attaching it). Before expanding a non-root claim, Jev
  judges **relevance** (a Noul given the root question and the full path:
  "would analysing this claim further materially change how the root question
  should be answered?"). The claim is expanded only when
  `relevance × reach ≥ minInfluence` (default: calibrated, §10); otherwise it
  is `PRUNED`. The root is always expanded. Beyond `maxDepth` (default 3)
  claims are `DEPTH_LIMIT` without a relevance call — a safety net, not the
  primary stop.
- **EXP-06** A global `maxClaims` budget (default 60 per question) is enforced:
  no argument is attached once the tree holds that many claims; remaining
  queued claims become `BUDGET`. Together with `maxArgsPerSide` this keeps
  any one claim from consuming the budget.
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

## 4. Human control (requirements CTL-*)

- **CTL-01** The human can set any claim's override to `AUTO`, `EXPAND` or
  `STOP` at any time.
- **CTL-02** `EXPAND` skips the relevance and depth gates for that claim and,
  if the claim already finished (saturated/pruned/limit), runs it again with
  one extra round allowance and saturation ignored for that first round.
  It does not bypass `maxClaims`.
- **CTL-03** `STOP` cancels queued work for that claim and prevents future
  rounds; an in-flight round finishes but its results are still attached
  (arguments are never silently dropped once produced). Status becomes
  `STOPPED`. Descendants are not affected.
- **CTL-04** `AUTO` returns the decision to Jev; setting it on a `STOPPED`
  claim re-queues it through the normal gates.

## 5. Claim status (the state machine the UI renders)

`QUEUED → JUDGING → EXPLORING → SATURATED | ROUND_LIMIT`, with terminal
alternatives `PRUNED`, `DEPTH_LIMIT`, `BUDGET`, `STOPPED`, `FAILED`.
Every status change is broadcast.

## 6. HTTP surface (the UI contract)

- `POST /question` form `text=` → `{"root":"<ref>"}`
- `POST /override` form `id=<ref>&mode=AUTO|EXPAND|STOP` → `ok`
- `GET  /graph` → `GraphDto` (see `Dto.kt`)
- `GET  /events` → SSE, each message a full `GraphDto` (coalesced, ≤ 10/s)
- `GET  /` → the built UI (`ui/dist`) when present.

## 7. UI (requirements UI-*)

- **UI-01** A single input where the question is typed and submitted.
- **UI-02** The deliberation graph of the selected question is shown as a tree
  rooted at the question, growing live via SSE with no reload.
- **UI-03** Each claim shows its text, its credence (bar/number), its status,
  the proposer that produced it, and its override control. Each argument shows
  its polarity (pro/con visually distinct) and relation strength.
- **UI-04** Minimal, modern, slick: a calm neutral palette, pro/con as the only
  saturated colours, smooth enter animation for new nodes, light and dark mode.

## 8. Non-goals (v1)

Persistence across restarts; multiple users; human stances; editing claims;
cross-tree links; merging equivalent claims across branches.

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
