# GUI Design Guide: Frontend for an Argumentation Graph Backend

**Status**: Draft, synthesized from `references/` (24 sources: academic papers, vendor best-practice guides, and practitioner/tool retrospectives on argument-mapping and graph-visualization UX). See `references/index.md` for the full concept-by-concept trace of which source says what, and where sources agree or disagree.

**How to use this document**: each section states a recommendation, then the specific tension in the source material it resolves, and *why* it resolves that way for this specific domain (argumentation graphs) rather than graph visualization in general. Cross-references like `[06]` point to `references/06-*.md`.

---

## 0. The shape of the problem, in one paragraph

An argumentation graph is not a generic network. It has a distinguishing structural bias — mostly tree-like (a claim, its supporting/opposing arguments, their sub-arguments) with occasional cross-links (a rebuttal reused against two claims, a shared piece of evidence) — and a distinguishing task profile: users mostly ask "what supports/attacks this," "how did we get from evidence to this conclusion," and "what's unresolved here," rather than doing general network-science analysis (density, centrality, community detection). Every recommendation below follows from that: this is closer to Kialo/Deliberatorium/Compendium territory than to Gephi/Neo4j-Bloom territory, even though the general graph-visualization literature ([01]-[13]) supplies most of the mechanical guidance.

---

## 1. Domain model comes first

Before any layout or interaction decision, the UI's information architecture depends on the backend's actual relation schema. The literature disagrees on how rich that schema needs to be:

- Minimal (3 elements: issue / idea / argument) — Conklin `[15]`, Eight2Late `[16]`, and MIT Deliberatorium `[19]` all independently argue restraint is a *feature*, not a limitation: it's what makes live, real-time capture possible and keeps the notation learnable.
- Slightly richer (4 elements, splitting out nested issues) — the general IBIS/Wikipedia formulation `[14]`.
- Richer still (Toulmin's "warrant": not just *that* a premise supports a conclusion, but *why*) — `[20]`.
- Kialo's production simplification (plain pro/con, no warrants) — shown to work at real scale but explicitly criticized for losing nuance `[17]`.

**Recommendation**: don't pick a schema richness level from the literature — read it off the backend. This design guide assumes the frontend renders whatever relation vocabulary the backend actually exposes (see open item 1, and the note below on the agora module). If the backend's relation model is closer to Kialo's plain pro/con, resist the temptation to invent UI affordances for warrants/nuance the data doesn't carry — that mismatch is exactly what produces a confusing UI. If it's closer to full IBIS, the UI needs at minimum four visually distinct relation encodings, not two.

> **Note on the ComputeNet-specific "agora" module**: at the time of writing, a background search of the connected ComputeNet project (`kernel`, `runtime`, `gen`, `gen-test`, `doc/adr`, `doc/spec`, and full git history across all local branches) found **no `agora` module or package anywhere in the repository**. The codebase is currently pure kernel/runtime plumbing (`Cell`/`Port`/`Link`/`Host` in `civictech.kernel.*`) with no argumentation-domain entities defined yet. This means §1's recommendation above cannot yet be executed against real code — it's a placeholder pending either (a) pointing this research at wherever the agora module actually lives (a sibling project, an unfetched remote, or work not yet started), or (b) treating this guide as informing the *design* of that schema rather than reading an existing one. See the "Outstanding — agora module" note at the end of this document.

---

## 2. Multiple view modes, not one universal screen

Canonical Debate Lab `[18]` names a tension no single source resolves alone: rating-sorted views (show the strongest arguments first, per branch — Kialo's model `[17]`) and full-structure views (show everything, for a curator or researcher) serve genuinely different intents, and forcing them into one screen degrades both. `[18]` concludes explicitly: *"Perhaps it is not possible to support all these goals simultaneously via a single interface."*

**Recommendation**: ship at least two distinct top-level view modes over the same underlying graph, consistent with the "data/view separation" principle both Argdown `[21]` and ComputeNet's own architecture already embody (the dataflow graph is explicit, inspectable data — the rendering is a projection of it, not the source of truth):

- **Debate mode** (default, most users): rating/strength-sorted pro/con columns beneath a focal claim, collapsed by default beyond 1-2 levels, closely modeled on Kialo's proven pattern `[17]`. Optimized for "should I believe this, and why."
- **Map mode** (power users, moderators, researchers): the full node-link structure, force-directed or layered depending on local shape (§3), with search/filter as the primary navigation tool rather than rating. Optimized for "what does the whole debate actually look like, and where are the gaps."

Both modes should share an "overview first" entry point — Shneiderman's mantra `[07]`, echoed by nearly every other source `[02][10][17][22]` — so a user always lands on a coarse view (a sunburst-style or simple root-claim view, à la Kialo `[17]`) before either mode's detail loads.

---

## 3. Layout strategy: pick per-region, not per-graph

Three sources converge on the same conclusion via different reasoning, which is why this is treated as settled rather than contested:

- `[03]` (Cambridge Intelligence): force-directed as the general-purpose default; switch to layered/hierarchical specifically for "tiered" data with a clear flow direction; radial for high fan-out from one node.
- `[01]` (GuidelineExplorer): layout and edge-encoding guidelines are only valid within the density/position-freedom conditions they were validated under — a guideline that works in a sparse region can actively break in a dense one *of the same graph*.
- `[11]` (Purchase): aesthetic criteria conflict with each other (clustering vs. even distribution; crossing-minimization vs. edge-length), and "a nice layout is unlikely to be sufficient for intuitive use" on its own — semantic grouping matters more than syntactic tidiness.

**Recommendation**: default to a **layered/hierarchical layout rooted at the focal claim** (root at top, pro/con branching below, per `[03]`'s "tiered data" case — this *is* an argument tree) for the common case. Where a region of the graph is locally dense — one claim with dozens of direct rebuttals — fall back to a **force-directed sub-layout scoped to just that neighborhood** rather than force-directing the whole graph (which would sacrifice the tree's overall legibility for the sake of one crowded node). This directly resolves Purchase's clustering-vs-distribution conflict `[11]` in favor of clustering: grouping an argument with the sub-claims it supports is more semantically load-bearing for this domain than even spacing. Preserve node positions across incremental graph updates (new arguments added live) per the mental-map-stability guidance both `[01]` and `[03]` give — don't re-layout the whole tree every time one node is added.

For very dense sub-regions specifically, `[13]`'s linked node-link + matrix technique is worth offering as an opt-in inset (not the default) — e.g., a small adjacency-matrix panel scoped to "all direct responses to this one claim" when that count gets large enough that a fan-out of edges becomes unreadable. `[12]`'s finding that node-link wins on topology tasks (which dominate this domain) is why matrix stays secondary rather than becoming a parallel primary view.

---

## 4. Visual encoding: relation type, strength, and the "don't imply meaning you don't intend" rule

- Follow `[06]`'s discipline: at most ~5 encoded variables (node = claim/argument, label = text, color = relation-type-of-incoming-edge or claim-status, size = a strength/weight signal if the backend has one, link style = relation type), and **always ship a legend** — `[06]` treats this as mandatory, not optional, the moment color/shape carries meaning.
- Encode relation type (support/attack/at minimum; more if the backend schema supports it, per §1) primarily through **edge color + style** (solid vs. dashed, or `[04]`'s "mixed link shapes" pattern), not through animation or tapering — `[01]`'s tapered-edge case study shows exactly this technique degrades under the density this domain produces around contested claims. Reserve `[04]`'s "link priority" technique (de-emphasize non-relevant edges on selection) for exactly this situation: selecting a heavily-attacked claim should visually recede the rest of the graph, not just add more lines.
- Apply `[06]`'s three reading warnings directly to onboarding/empty-state copy or a persistent legend footnote: layout position is not importance, orientation is not meaningful, and visual centrality on screen ≠ argument centrality in the debate. This matters more here than in generic graph viz, because users will be primed (correctly, in Debate mode) to read "central/prominent" as "important," and need a clear signal for when that's actually true (Debate mode's rating-based prominence) vs. when it's just a layout artifact (Map mode's force-directed regions).
- `[22]`'s observation that user-placed proximity *does* carry intentional meaning, while algorithm-placed proximity does not, means: if the UI ever allows manual repositioning (e.g., a moderator manually arranging a summary view), visually distinguish "pinned/user-arranged" nodes from "auto-laid-out" nodes (e.g., a subtle pin icon or different node border), so the two kinds of spatial meaning are never ambiguous.
- Accessibility is non-negotiable given the civic/deliberative use case this domain implies (per ComputeNet's own vision doc, and per `[20]`'s note that most existing argument-mapping tools fail on this): follow `[05]` directly — full keyboard navigation, ARIA-labeled or text-equivalent structure alongside the canvas, colorblind-safe palettes with redundant (non-color) encoding for relation type, legible label sizing with graceful truncation rather than illegible micro-text, and a global animation-off toggle.

---

## 5. Interaction model

Structure interaction design around Heer & Shneiderman's three groups `[09]`, which strictly extend Yi et al.'s seven primitives `[08]` — treat this as the section structure for interaction, not as two competing taxonomies to reconcile:

**Data & view specification** — filter (by relation type, by "unresolved," by participant, by recency), sort (rating-based in Debate mode, structural in Map mode), derive (surface a computed roll-up-strength score per `[18]`'s named gap — no existing tool in this set does this well, which makes it a genuine differentiation opportunity rather than a solved problem to copy).

**View manipulation** — pan/zoom/select per the standard graph-viz baseline `[02]-[06]`; collapse/expand per-branch (Kialo's proven mechanism `[17]`) as the primary way to manage local complexity, capped at two levels of default disclosure per view per NN/g's rule `[10]`, with an explicit, clearly-labeled "expand further" affordance rather than an ambiguous one (NN/g's second pitfall `[10]`).

**Process & provenance** — this is the group most under-served by generic graph-viz tools and most load-bearing for argumentation specifically `[09][15][18]`:
- *Annotate*: let users attach a note or flag to a claim without editing its text (useful for moderators and for casual participants who want to react without formally arguing).
- *Record/history*: full undo/redo `[07]`, plus a visible history of how a sub-debate evolved — `[18]` names "transparency and versioning" as an unsolved challenge; even a simple "added by X on date" + diff-on-hover goes further than most existing tools.
- *Share*: deep-linkable state — a URL that reproduces "this specific sub-debate, expanded to this depth, filtered this way" — because `[15]`'s "point back to a captured argument" is explicitly named as a rhetorical/social tool, not just a technical nicety, and that only works if the pointer is a stable, shareable link.

**Authoring is a separate concern from viewing/exploring**, and the sources split into two genuinely different authoring modes rather than one:
- *Live/facilitated capture* `[15][16]`: a trained facilitator adding nodes in real time during a synchronous discussion, working from a "guess and check the guess" provisional loop — needs fast, low-friction node creation, easy re-parenting/re-wording, and forgiving correction, not a heavyweight structured form.
- *Asynchronous/solo authoring* `[21]`: a participant composing an argument offline, closer to writing than to direct graph manipulation — Argdown's model (write structured text, let the system render/lay out the result) is worth offering as an alternative input path alongside direct node-and-edge manipulation, especially for longer or more carefully composed contributions.

**Moderation as a first-class, fast surface, not an admin afterthought** `[16][19]`: a lightweight "pending → certified" workflow (per Deliberatorium `[19]`) where moderators check structural placement rather than merit, plus quick merge/flag-duplicate actions (per `[16]`). Deliberatorium's real-world data — ~70% of submissions certified without changes once the format was explained — suggests this friction can be kept low with good onboarding/inline guidance rather than requiring a heavyweight review step by default.

---

## 6. Scaling strategy (the central open problem, addressed rather than deferred)

`[18]` names this the hardest unsolved UX problem in the whole domain, and `[19]` is the only source that proposes a concrete mechanism: automated "deliberation metrics" (under-argued positions, signs of groupthink/polarization, staleness) driving a **personalized, continuously-updated "where to look" recommendation layer**, because pure manual browsing stops scaling once a debate covers hundreds of topics.

**Recommendation, synthesizing `[18]` and `[19]` rather than picking one**: use §2's multiple-view-mode split as the *structural* answer to scale (different modes for different intents keeps any one screen from having to do everything), and layer a lightweight recommendation/highlight affordance on top of Debate mode specifically — e.g., a persistent but unobtrusive "3 claims near you have new unrated arguments" or "this branch hasn't been touched in 6 months" signal — as the *dynamic* answer to scale that static two-level progressive disclosure `[10]` can't fully cover on its own. Treat this as a genuine differentiator to design deliberately rather than bolt on later, since no existing tool in this research set does it well.

---

## 7. Collaboration (conditional on backend support)

If the agora backend supports concurrent multi-user graph mutation, adopt Figma's pattern `[23]`: apply local edits optimistically (don't block the UI on server round-trips), suppress transient conflict states rather than surfacing raw diffs mid-edit, and make undo/redo behave predictably for a single user even while others edit concurrently. Add lightweight presence indicators (who's currently viewing/editing which claim) per `[24]`, but keep them subtle by default — Pitch's restraint pattern (regular cursors by default, richer presence only opt-in) is the right default here, since the argument content is already visually dense and shouldn't compete with social-presence chrome. This section is explicitly conditional — see open item 2.

---

## 8. What "slick and modern" means operationally here

Given the domain and the research above, "slick and modern" should be read as: generous whitespace and restrained default chrome (progressive disclosure, §2/§5, keeps the default view from feeling cluttered even as content density grows); confident, purposeful motion used only for state transitions that need it (layout settling, expand/collapse, node-focus transitions) and switched off entirely under the accessibility motion toggle `[05]`; a single coherent color system where every color carries semantic meaning (relation type, status) rather than decoration, backed by a legend `[06]`; and typography/label handling that degrades gracefully (truncate + tooltip) rather than ever overflowing or forcing tiny illegible text `[05]`. This is a direct, literature-backed rejection of both extremes: neither a dense, connections-app "wall of nodes" (fails progressive disclosure) nor an over-animated, gesture-heavy canvas (fails accessibility and `[23]`'s responsiveness principle).

---

## 9. Outstanding — agora module

This guide was requested to be grounded in the actual `agora` module of the ComputeNet backend. A thorough search of the connected project (all source under `kernel`/`runtime`/`gen`/`gen-test`, `doc/adr`, `doc/spec`, and the full git history across all three local branches — `main`, `lab/port-requirements`, `wip/runner`) found no file, package, class, or commit referencing "agora" anywhere. The codebase currently implements only the generic Cell/Port/Link kernel (`civictech.kernel.*`); no argumentation-domain entities (claims, statements, relations, votes) exist yet in this repository.

Before this guide can be turned into an implementation-ready spec (concrete component list mapped to concrete backend types, per §1), one of the following needs to happen:
1. Point this research at the actual location of the agora module, if it lives in a different local path, a different git remote, or a sibling project not currently connected.
2. If the module hasn't been built yet, treat §1's schema question as a *design decision to make* (informed by the IBIS/Toulmin/Kialo comparison in `references/index.md`) rather than a fact to look up, and this guide's recommendations (§2-§8) can still be used as-is to inform both the backend schema design and the frontend, since they're derived from the argumentation-graph domain generally rather than from any particular implementation.

---

## Source map (quick reference)

| # | Source | Core contribution to this guide |
|---|---|---|
| 01 | GuidelineExplorer (arXiv) | Guidelines are conditional on graph density/shape, not universal |
| 02 | Cambridge Intelligence — fundamentals | 7 general design principles, progressive disclosure |
| 03 | Cambridge Intelligence — force-directed | Layout-per-data-shape decision rule |
| 04 | Cambridge Intelligence — link styles | Edge/relation-type encoding options |
| 05 | Cambridge Intelligence — accessibility | Concrete accessibility requirements |
| 06 | Linkurious — visual language | Encoding discipline, legend requirement, 3 reading warnings |
| 07 | Shneiderman 1996 | Overview-first mantra, 7 task types |
| 08 | Yi et al. 2007 | 7 interaction primitives |
| 09 | Heer & Shneiderman 2012 | 12-technique taxonomy incl. provenance/annotation |
| 10 | NN/g — progressive disclosure | 2-level cap, clear-path-to-more rule |
| 11 | Purchase — aesthetics | Named conflicts between layout criteria |
| 12 | Node-link vs. matrix (revisited) | Node-link wins on topology tasks |
| 13 | Linked node-link + matrix views | Matrix as secondary inset for dense regions |
| 14 | Wikipedia — IBIS | 4-element argumentation schema |
| 15 | Conklin — IBIS: A Tool for All Reasons | Minimal notation by design; provisional capture loop |
| 16 | Eight2Late — dialogue mapping | Facilitation as craft; moderation affordances |
| 17 | Wikipedia — Kialo | Production rating-sorted debate-tree UI, and its limits |
| 18 | Canonical Debate Lab | Names the scaling problem and the mode-split resolution |
| 19 | MIT Deliberatorium | Structured moderation at scale; recommender-layer proposal |
| 20 | Wikipedia — Argument map | Toulmin warrants; tool landscape; accessibility gap |
| 21 | Argdown | Text-first authoring; data/view separation |
| 22 | Bookmarkify — infinite canvas | Zoom-as-thinking-mode; proximity semantics |
| 23 | Figma multiplayer | Optimistic UI, conflict handling, undo under concurrency |
| 24 | Prototypr — live cursors | Presence UI restraint pattern |
