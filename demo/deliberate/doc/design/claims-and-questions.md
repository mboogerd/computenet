# Design: one claim store, many question shapes

Status: **draft; direction decided 2026-09-28** (§10).  Epic `computenet-dq2fy`.

This note consolidates three inputs into one solution design:

| Input | What it is | Where |
|---|---|---|
| **R1** claim reuse | Research `computenet-6uimj`: identity, implication, cores, context-free judging | `demo/deliberate/doc/research/claim-reuse.md`, draft PR mboogerd/computenet#1159 |
| **R2** multi-class | Research `computenet-1ow0x`: K-class questions, none-of-these, categorical VoI, accuracy | `doc/research/deliberate-multiclass/REPORT.md`, draft PR mboogerd/computenet#1161 |
| **B** open design beads | `nxege` (prior dominance), `mkimq` (reading selection), `ilknp` (multi-user), `dq2fy.22` (single owner), `dq2fy.24.*`, `x91yk`, `dw2wh`, `drz8.3`/`259rq` | beads |

Every point is tagged: **[decided]** (by the user, cited), **[R1 §n]** / **[R2 §n]**
(measured), **[proposal]** (inferred here), **[open]** (needs a decision, §10).

---

## 0. Summary

1. **Claims are the unit; questions are views onto them.** One global claim store.
   A claim's credence is context-free and shared; everything question-specific
   (bearing on an answer, relevance, value of information, budget) lives on edges
   or on the question. [decided, R1 §3]
2. **Three question shapes share that store:** binary (today), multi-class MECE
   (closed-world log-linear read-out + a "none of these" hurdle claim), and
   overlapping classes (K ordinary binary claims). The multi-class build waits
   on one more accuracy experiment; until then model A's positions stay.
   READINGS stay, with human selection. [R2 §8.1, §9.1; `mkimq`; §10]
3. **Jev judges relations graded, never by Choice**, and never with the question
   in view of a claim's own plausibility. Both studies found the same thing
   independently. [R1 §5.2, §5.5; R2 §5.4]
4. **Implication is a soft constraint, not evidence.** Identity is its limit
   (a twin = mutual implication near 1). [R1 §6]
5. **Value of information is computed exactly, per question root, q-weighted.**
   This fixes model C's bug *and* is the only VoI form that survives a
   multi-parent graph. [R2 §5.5; R1 §7.4; proposal]
6. **Product role: explanation, audit and consistency — not an accuracy engine.**
   Arguments did not beat asking Opus directly [R2 §7]. That is compatible with
   the stated goal — "a single source of truth for checking any claim and
   understanding why it has its credence" [decided, R1 §1.2] — which is about
   reasons and consistency, not beating a direct forecast.

---

## 1. Where the inputs agree, and where they collide

### 1.1 Convergences (independent findings that reinforce each other)

| Finding | R1 | R2 | Consequence |
|---|---|---|---|
| Question context biases claim judgments | Question in plausibility acts as bias: 8.6% flip sides; context lowers match precision .72→.60 (§5.2, §5.5) | Per-class judgments leak Jev's class prior (.32–.70) unless told not to judge overall plausibility (§5.4) | Context-free claim judgments; the question appears only on edges (bearing) and in VoI |
| Jev's Choice is unreliable for relations | yes/no implication recall .54 vs graded .94 (§5.2) | Choice degenerate, −74 Metaculus; hard 0/1 elimination over-calls (§2, §5.4) | Graded Score everywhere for relations; Choice only for `knowledge` |
| Deep trees barely move the answer | Coherence adjustments move roots ≤ .008 (§5.4) | Propagated credence differs from plausibility by .004–.03; recursion added nothing (§7.3) | Independent confirmation of `nxege`'s prior-dominance concern |
| Bearing belongs on the edge | "Bearing on a parent lives on the edge" (§3) | Per-class compatibility κ is a property of (claim, class) (§5.2) | A multi-class bearing is an edge from a claim to a question, carrying a κ vector |
| Jev's knowledge limit confounds plausibility | Bill rewrites drop .75→.24, likely cut-off (§5.5) | 41% of forecasting claims OUTSIDE_MY_KNOWLEDGE (§7.1) | `knowledge` Choice on every plausibility call, kept (model D) |
| Budget goes to the wrong nodes | — | 70–79 of 90 claims ended BUDGET, mostly on link arguments and undercutters (§7.3) | Exact VoI; steer budget to decisive claims |

### 1.2 Collisions, and how this design resolves them

| Collision | Resolution |
|---|---|
| Model A makes each position its own tree **vs** R2: per-position trees contain no discriminating arguments and lose (§7.3) | Target: POSITIONS become one multi-class question (§3). **Decided: positions stay until the multi-class accuracy experiment (S3a) says otherwise** (§10). READINGS stay separate roots. |
| `mkimq`: each reading gets its own full budget **vs** R1: shared claims explored once | Budgets stay per question/reading; a claim already explored by another question costs nothing to reuse. Two readings that share claims get more depth for the same money. |
| `dq2fy.22`: one state owner *per question* (KeyedCells per question) **vs** R1: claims shared across questions | Ownership moves to the claim store (§7). Per-question state shrinks to queue, budget, cost and status. `dq2fy.22` needs re-scoping. [open] |
| `nxege`: arguments should outweigh the prior **vs** R2: arguments alone score −37 against Opus direct; as an adjustment, neutral | Do not make arguments dominate the headline on this evidence. Generalise model D's arguments-alone view to every node, and show both (§5.2). `nxege` still measures, and its semantics proposal is judged against R2's data too. |
| `ilknp`: human stances per claim **vs** a claim now serves many questions | A human's stance on a claim is global, like Jev's. Human arguments go through the same intake as proposer claims, so they are reused and deduplicated (§6). |
| Model C sensitivity assumes "exactly one path per node" (SPEC §3) **vs** a multi-parent, cyclic graph | Replace the chain-rule sensitivity cells by exact on-demand VoI per question root (§5.4). |
| `dq2fy.24` fragment 7: drop cycle-head support unless cross-tree links arrive | They arrive. Keep it. |

---

## 2. Principles carried forward

The seven-principle yardstick (R2 §1.3) stays: generation separate from
judgment; verdict derived, never asserted; pluralism of layers; first
impression apart from arguments; priced exploration; explicit cruxes; human
steering. This design adds two:

8. **Context-free claims.** A claim is self-contained and judged on its own
   text. [decided, R1 §3.2]
9. **One proposition, one credence.** The same proposition never carries two
   credences in two questions; related propositions are held coherent by
   believed implications. [decided, R1 §3.1, §3.3]

---

## 3. Data model

```
Question  (entry point; per-question: shape, classes, queue, budget, cost, status)
   │  bearing edges  claim ──(polarity | κ-vector)──► question-or-claim
   ▼
Claim store (global)
   Claim        self-contained text, aliases, stances {jev, participants…}, knowledge flag
   Edge         claim→claim argument; is itself a claim (links as claims, LINK-*)
   Bearing      claim→question, one κ_k per class (multi-class only)             [proposal]
   Implication  X ⇒ Y with belief w; claim-like (arguable), never an argument     [R1 §7.3]
```

- **Claim** loses its single `parent` and `root` [R1 §7.5]: it has many incoming
  argument edges, many parents, and belongs to many questions through reachability.
- **Aliases.** A reused twin's text becomes a journaled, reversible alias of the
  canonical claim; merges must be splittable. [R1 §7.2]
- **Bearing edge (multi-class).** One edge per (claim, question) carrying
  κ ∈ [0,1]^K from Jev's graded `instr` bearing judgment [R2 §5.4]. It is a
  claim like any edge, so it can be argued and undercut. What an undercut does
  to a κ *vector* is not settled. [open, small]
- **Question shapes:**
  - **Binary:** a root claim, as today.
  - **Multi-class MECE:** listed classes C1..CK + a hurdle claim L, "the answer
    to Q is one of C1..CK". L is an ordinary claim in the store. [R2 §5.3]
  - **Overlapping classes:** K ordinary binary claims ("C_k is a cause of X"),
    reported as a marginal vector. No distribution is formed. [R2 §5.2, argued
    not tested]
  - **Readings:** separate binary or multi-class questions linked to the
    original, awaiting human selection. [`mkimq`]

---

## 4. Judgments (Jev)

| Judgment | Input | Shape | Source |
|---|---|---|---|
| Plausibility | the claim text **only** (drop `root_question` from CRED-01) + `knowledge` Choice | Score, 5 levels | [decided, R1 §5.5] |
| Relation strength | child, parent, direction | Score | CRED-02, unchanged |
| Per-class bearing | claim, question, all classes; "don't judge the answer's overall plausibility" | `instr` Score per class, graded; never thresholded to 0/1; no prior subtraction | [R2 §5.4] |
| Absoluteness of an objection | claim, class | Score, thresholded at .5 (only for the optional Hmin-pen, §5.3) | [R2 §5.3, medium] |
| Implication belief | X, Y (bare texts, no context), both directions | graded Score w | [R1 §5.2] |
| Self-containment | claim | combined wording as a pre-filter only; never a gate | [R1 §5.5] |

Removing the question from plausibility changes about 9% of first impressions
[R1 §5.5]; SPEC §10 recalibration applies before it ships.

---

## 5. Credence

### 5.1 Layers unchanged

Raw credence c stays exactly as today: kernel cells, seven layers, consensus of
`wlo,jnb,woe`, derived and never journaled (CRED-03..06, DUR-01). Nothing in
either study argues for changing the binary layers themselves. `nxege` may.

### 5.2 Views

A **view** is a choice of base per node, run through the same layers:

| View | Base | Shown where | Source |
|---|---|---|---|
| model | Jev stance | headline (today) | CRED-04 |
| arguments alone | neutral 0.5 | **every node** (today: question root only) | model D, generalised [proposal] |
| participants | mean human stance, falling back to Jev | beside model | `ilknp` [decided there] |
| coherent | model c′ after the coherence step (§5.5) | claim detail | R1 §7.3 [proposal] |

Generalising the arguments-alone view to every node is the minimal step on
`nxege`: it makes prior dominance visible per claim without changing the
headline. Whether the headline itself should shift is `nxege`'s question to
answer, now with R2's accuracy data as a second yardstick.

Views multiply cell output (layers × views). Keep the vector per node, as
model D's `neutral` rides the `Credence` emission today.

### 5.3 Multi-class read-out [R2 §5.2–5.3, strong]

Per log-odds layer L ∈ {wlo, woe, jnb, mlp}, over the question's bearing claims i:

```
score_k = α·log π_k − k_L · ‖ { w_L(e_i) · (1 − κ_ik) }_i ‖_{p_L}
s^L     = softmax(score)
s       = log-pool(s^L)            layer disagreement = JSD
P(k)    = P(L)·s_k,   P(none) = 1 − P(L)     (pool P(L) and s separately)
```

- e_i is the claim's energy (strength × credence; jnb uses its own). Exact at
  K=2 against every existing layer, given the base inside the clamp.
- π is uniform for the arguments-alone view and the first impression for the
  model view [R2 §9.2]. The combined verdict is at best accuracy-neutral
  [R2 §7.2]; show it, but do not claim it is better.
- "None" rises only through argued claims on L (A0). The Hmin-pen extension
  (absolute objections lower P(L)) is medium-strength and has an open
  junk-class veto; leave it out of the first build. [R2 §5.3]
- dfquad, euler, qe have no verified K-class form; they are not part of the
  multi-class read-out. [R2 §5.2]

Bearing claims are depth-1 claims whose proposer saw **all** classes and was
asked for discriminating and none-of-these claims [R2 §9.1, strong]. Below
depth 1, each bearing claim is an ordinary binary claim in the store with its
own context-free subtree. That composition — contrastive at the question,
claim-only below — is what R1's reuse needs, and it is exactly R2's untested
"contrastive arguments at depth" (§8.4). Today's engine can already run it:
deliberate each contrastive depth-1 claim as its own binary root and apply
the read-out offline. That is experiment S3a (§9, §10).

### 5.4 Value of information [R2 §5.5; R1 §7.4]

- **Exact and q-weighted:** VoI(n) = E_q |R(n→1) − R(n→0)| weighted by
  propagated credence q, not plausibility p. For a multi-class question, eTV =
  E_q Σ_k |s_k^r − s_k|; under the hurdle, a node under L gets 2E|ΔP(L)| and
  a listed node gets P(L)·eTV(s).
- **On demand, per active question root**, as a pure function of the hub
  snapshot, memoised per hub version; about 15 `evaluate()` calls per
  candidate; fallback re-ranks only the top 3–5 exactly [R2 §5.5].
- This **replaces the sensitivity cells** (SPEC §3 model C). They compute a
  chain-rule tangent that assumes one path per node — wrong near clamps
  (`dw2wh`) and wrong in a DAG with cycles (R1 §7.4). Exact evaluation needs
  neither assumption. [proposal]
- A claim reachable from several active questions has priority Σ over those
  questions' VoI: widely used claims get explored first. [R1 §7.4]
- `--voi-eps` needs re-tuning for K > 2 (eTV runs ~2× eM_w) and after `dq2fy.17`.
- Cruxes = the top-eTV nodes, annotated when resolving them flips the argmax.

### 5.5 Coherence [R1 §5.4, §7.3]

- Believed implications X ⇒ Y penalise c(X) > c(Y) with stiffness s(w) =
  w/(1−w), w ≤ .95; minimal logit adjustment, convex, solved by local
  relaxation to quiescence. Derived, never journaled.
- **Parents read c, not c′, in the first build.** [proposal; R1 open Q1] c′ is
  a claim-level view (§5.2). Evidence: question roots moved ≤ .008 with no
  flips [R1 §5.4], so feeding c′ upward buys almost nothing now and would force
  VoI through the solver. Twins are handled structurally, by sharing the node,
  which removes the largest coherence gaps. Revisit when overlap at scale is
  measured.
- Firmness aₙ = 1 to start; the study found it made no difference [R1 §5.4].

---

## 6. Intake of a new claim (extends EXP-03)

From a proposer, a human participant (`ilknp`) or a core extractor, alike:

1. **Canonical form** requested of proposers (EXP-02, already ~95% effective).
   Jev combined wording as a pre-filter; on a flag, a stronger model rewrites
   the unnamed referent from the parent. [R1 §5.5]
2. **Recall:** embedding neighbours in the global store, cos ≥ .85 (twins),
   ≥ .80 (implications, cores). [R1 §5.1]
3. **Relate**, with Jev graded implication belief in both directions:
   - min(w_ab, w_ba) ≥ .5 → **REUSE** the node; add the text as an alias
     (P .94, R .83) [R1 §5.2];
   - one direction ≥ .5 → new claim + **implication** relation;
   - related, neither → extract a **common core**, verify both implications,
     reuse or create the core, link both by implication (15% coverage vs 4%
     for twins) [R1 §5.6] — *second build step*, §9;
   - otherwise → new claim.
4. **Judge** plausibility (context-free) + `knowledge`; strength of its edge;
   per-class bearing if it attaches to a multi-class question.
5. **Proposers** for a claim's own subtree get the claim, not the path
   (EXP-02). At a multi-class question they see the question and all classes.

Cost: < $0.0001 per claim for recall + relation [R1 §5.2].

---

## 7. Ownership, durability, budget

- **Ownership [open]:** one owner of the claim store (claims, edges,
  implications, aliases, stances). Per question: queue entries, budget, cost,
  status, framing/selection state. This re-scopes `dq2fy.22` from "actor per
  question" to "actor for the store + per-question schedulers". Still blocked
  on `vcrc7`.
- **Durability:** one global structure log. New structure ops: alias (and its
  split), implication, bearing (κ is a stance on the bearing edge), question
  shape. Inputs only; c, c′, views, VoI recomputed (DUR-01). Old journals with
  FRA-04 `"issue"` ops need a restore mapping to the multi-class shape. [open,
  small]
- **Budget:** per question, per selected reading [`mkimq`]. A round is charged
  to the question whose VoI contributed most to the claim's priority.
  Re-using an explored claim is free. [proposal; R1 §7.4 left it open]
- **Budget steering:** exact VoI already de-prioritises low-leverage link
  arguments and undercutters; re-measure R2's 70–79/90 BUDGET figure after it
  lands before adding any explicit cap on link exploration. [proposal]

---

## 8. Disposition of existing beads

| Bead | Disposition under this design |
|---|---|
| `dw2wh` model C linearised VoI | **Do first**, as its notes already recommend (exact E_q on demand). Two adjustments: re-evaluate every path from the node to the root, not a single node→root walk (a DAG has several), and do not keep the sensitivity cells for display, since their chain rule is wrong in a DAG (§5.4) |
| `x91yk` model A shares | **Fix now**: positions stay (§10.5) |
| `nxege` prior dominance | Proceeds as measurement; add R2's accuracy data as a second yardstick; the generalised arguments-alone view (§5.2) is its minimal outcome |
| `mkimq` reading selection | Proceeds as filed; readings that share claims get extra depth once the store lands |
| `ilknp` multi-user | After the claim store; human stances global per claim; human arguments through intake (§6) |
| `dq2fy.22` single owner | Re-scope: store owner + per-question schedulers (§7) |
| `dq2fy.24.4` per-question folds | Absorb into the re-scoped `dq2fy.22`, as its own comment recommends |
| `dq2fy.24` fragment 7 cycle heads | Keep: cross-question cycles now arrive |
| `dq2fy.24.5` / `259rq` / `drz8.3` | The global claim store is a candidate shared substrate for agora and `:demograph`; decide after the store exists, not before |
| `dq2fy.17` eps recalibration | After exact VoI and the CRED-01 change; both shift the numbers |
| `1ow0x`, `6uimj` | Close on acceptance of this note; implementation beads filed per §9 |
| Model B (shipped): premise-vs-bearing Choice | Keep the routing; it fits the store (premise → the claim's own subtree, bearing → its link). But it is a Choice over a relation, the kind both studies found unreliable; measure it or move it to a graded Score before relying on it more [open] |
| Model B (shipped): REFINE evidence entries | An instance of or evidence for the target implies it; in the store a REFINE becomes a claim + implication relation rather than a text-only entry [proposal] |
| EXP-03 MERGE (text rewrite) | Must never rewrite a claim that another question uses; it degrades to core extraction there (§6) [proposal] |
| Model C (shipped): sensitivity cells, 4p(1−p), reach | Replaced by exact q-weighted VoI (S0); cruxes kept, recomputed from it |
| Model D (shipped): neutral-prior vector, `knowledge` Choice | Kept; the neutral view is generalised to every node, and lifted to K classes as the uniform-π read-out |

---

## 9. Build order

Each step is shippable and useful alone. Bead ids are `computenet-<id>` under
`computenet-dq2fy`; the epic's 2026-09-28 plan comment is the live order. Also
filed: `n99jt` (measure model B's premise-vs-bearing Choice).

| Step | Content | Depends on | Evidence strength |
|---|---|---|---|
| **S0** `dw2wh` | Exact q-weighted VoI on demand (`dw2wh`), replacing sensitivity cells | — | medium-strong |
| **S0'** `pyyi0` | CRED-01 without `root_question`; recalibrate with `knowledge` | — | decided; 9% shift measured |
| **S0''** `nxege` | Arguments-alone view at every node; `nxege` measurement | — | measured concern |
| **S1** `ra3o2` | Claim store: multi-parent claims, question membership, global structure log, aliases, twin REUSE at intake, EXP-02 claim-only subtrees; SPEC §8 non-goal dropped | S0 | strong for twins |
| **S2** `i9ujc` | Implication relations + coherence view (c′ shown, parents read c) | S1 | medium (n=12 / one snapshot) |
| **S3a** `6di6y` | Multi-class accuracy experiment (R2 option b): a harder question set (Opus ≈ 50% top-1, Jev knowledgeable), contrastive depth-1 claims each deliberated as its own binary root by today's engine, plus R2's offline checks; go/no-go for S3b | — | — |
| **S3b** | *Not filed — S3a was a no-go.* Was: *only if S3a says go:* multi-class questions (bearing edges, `instr` judgment, LL read-out, hurdle L, eTV), retiring POSITIONS | S0, S1, S3a | strong mechanism; accuracy not shown |
| **S4** `vyxyl` | Common cores at intake | S2 | 15% coverage on a sample |
| **S5** `ilknp` | Multi-user (`ilknp`) on the store | S1 | decided |

Cheap offline checks from R2's committed data (`data/DATA.md`) — prior
tempering, OUTSIDE_MY_KNOWLEDGE handling — belong to S3a. [R2 §8.3]

---

## 10. Decisions

Decided by the user, 2026-09-28:

1. **Multi-class: accuracy push first** (R2 option b). No multi-class build is
   filed until the S3a experiment reports; S3b is filed only on a go.
   **Outcome 2026-09-28: no-go.** On a harder set (Opus ≈ 60% top-1) arguments
   still add no accuracy, and contrastive arguments at depth move claims but
   not answers (`doc/research/deliberate-multiclass/ACCURACY-PUSH.md`, PR #1161).
   S3b is not filed; POSITIONS stay.
5. **Keep POSITIONS for now**; fix `x91yk` in the meantime.

Defaults adopted for planning (recommendations; revisit if evidence changes):

2. **Parents read c, not c′** (§5.5).
3. **Ownership:** `dq2fy.22` re-scoped to a store owner + per-question schedulers.
4. **Budget:** a round is charged to the question contributing most to the
   claim's priority; reuse is free.

## 11. What is not known

- **Overlap at scale** — the premise of reuse — is not measured. [R1 §9]
- **Accuracy:** no arm beat Opus direct; contrastive recursion untested. [R2 §8]
- **Gold labels** in both studies come from models (Opus; Opus + Sol), not humans.
- **Coherence** was solved on one static snapshot, not incrementally. [R1 §9]
- **Exact VoI wall time** in a large shared graph is unbenchmarked; Sol asked
  for that first. [R2 §5.5]
- **Views × layers** cost in the cells is unmeasured.
