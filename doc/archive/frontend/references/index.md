# Concept Index — UI Design for Argumentation Graphs

One entry per idea/concept surfaced in `references/`. Each entry lists every reference that touches it, notes where sources agree vs. disagree, and points to the resolution (if any) adopted in `../gui-design-guide.md`.

Reference key: `[NN]` = `references/NN-*.md`, filename given on first mention per file.

---

### Overview-first / "orient before you explore"
The single most repeated idea across the whole set: show the whole structure at a coarse level before letting the user drill into detail.
- **Sources**: [07] Shneiderman 1996 (origin: "overview first, zoom and filter, then details-on-demand") · [02] Cambridge Intelligence graph-viz fundamentals (principle 1-2) · [10] NN/g progressive disclosure · [17] Kialo (sunburst overview diagram) · [22] Bookmarkify infinite canvas (zoom-out = big picture)
- **Agreement**: Universal — no source argues against showing structure before detail.
- **Nuance**: sources differ on the *mechanism* for "overview" — a literal zoomed-out canvas view (22), a separate summary diagram like Kialo's sunburst (17), or a non-spatial list/outline overview. See design-guide §2.

### Progressive disclosure
Show a few primary options/details by default; put the rest behind an explicit, well-labeled "more."
- **Sources**: [10] NN/g (canonical definition, 2-level-max rule) · [02] principle 2 · [18] Canonical Debate Lab ("details...perhaps only to those that seek them") · [19] MIT Deliberatorium (recommender layer as disclosure-at-scale)
- **Agreement**: Universal in principle.
- **Disagreement/nuance**: NN/g caps this at two disclosure levels for a static app UI; [19] argues that at true graph scale (hundreds/thousands of nodes) two static levels aren't enough and you need a *dynamic*, personalized layer (what to show "next" changes per user/session). Design-guide resolves this by treating "two levels" as the ceiling for any *single* view, while using multiple view modes (see below) to add further levels without violating the two-level-per-view rule.

### Details-on-demand
Click/select an item to reveal its full attributes without navigating away from context.
- **Sources**: [07] (task type 4) · [09] Heer & Shneiderman ("Navigate" + "Select")
- **Agreement**: Universal, uncontested.

### Layout algorithm choice (force-directed vs. hierarchical/layered vs. radial)
- **Sources**: [03] Cambridge Intelligence force-directed FAQ (force-directed = general fallback; layered for tiered/flow data; radial for high fan-out) · [01] GuidelineExplorer (layout guidelines are conditional on node-position freedom and density) · [11] Purchase (no single "best" layout — task- and criterion-dependent) · [12] node-link-vs-matrix comparison (topology tasks favor node-link generally, independent of *which* node-link layout)
- **Disagreement**: None of these actually disagree on facts — they agree the "best" layout is context-dependent, but differ on *what context signal to key off*: [03] keys off data shape (tiered vs. general), [01] keys off density/position-constraints, [11] keys off task type. Design-guide §3 synthesizes all three signals into one decision rule.

### Mental-map stability under updates
When a live/dynamic graph changes, don't let the layout jump; preserve relative node positions.
- **Sources**: [03] ("nodes move as much as they need to and no more") · [01] GuidelineExplorer (fixed layout across time steps for temporal graphs)
- **Agreement**: Consistent.

### Edge/relation-type encoding
- **Sources**: [04] Cambridge Intelligence 5 link styles (shape/color/width per relation type) · [01] tapered/curved/partial/animated edges, density-dependent · [05] accessibility ("don't rely on color alone") · [20] Wikipedia argument-map (Toulmin warrant as a *richer* relation than plain support/attack)
- **Disagreement**: [01]'s density caveat means the encoding style [04] recommends generically (tapered/animated direction cues) can fail exactly where an argumentation graph gets locally dense (a heavily contested claim). Design-guide §4 recommends a density-adaptive edge style.

### Visual encoding discipline (node size/color/label mapping; legends)
- **Sources**: [06] Linkurious visual language (≤5 variables, mandatory legend, three reading warnings) · [02] principles 3-6
- **Agreement**: Consistent; [06] is the most rigorous single source on this.

### Misleading layout semantics (position/orientation/centrality ≠ meaning)
Users will over-read spatial position as meaningful even when it's an artifact of the layout algorithm.
- **Sources**: [06] (three explicit warnings: relative distance, arbitrary rotation, visual vs. network centrality) · [22] (proximity *does* carry intended meaning when the user manually placed items — creating an ambiguity between algorithm-placed and user-placed proximity)
- **Tension, explicitly resolved in design-guide §4**: distinguish algorithmically-laid-out regions (no positional meaning implied) from any user-curated/pinned layout (where position is meaningful) — visually differentiate the two states.

### Aesthetic criteria & their tensions (crossings, bends, symmetry, clustering, even distribution)
- **Sources**: [11] Purchase (catalog of criteria; empirical evidence is mixed; explicit conflicts named: clustering vs. even distribution, crossing-minimization vs. edge-length/straightness) · [01] (crossing-minimization = most agreed-upon single criterion)
- **Agreement**: Crossing minimization is the least controversial criterion.
- **Disagreement**: Clustering related nodes vs. even distribution are named as *directly conflicting* — no source resolves this generically; design-guide §3 picks a side for this domain specifically (favor clustering, since grouping an argument with its sub-claims is more semantically load-bearing than even spacing).

### Node-link vs. matrix (adjacency) representation
- **Sources**: [12] revisited comparison (node-link wins on topology tasks; roughly tied on cluster tasks; no difference on memorability) · [13] linked node-link + matrix views (combine both for globally-sparse/locally-dense graphs) · [07] Shneiderman (flags this as an unsolved general problem, declines to pick a winner)
- **Agreement**: For topology-style tasks (which dominate argumentation-graph use), node-link wins or ties; no source recommends matrix as the *primary* view.
- **Nuance, not disagreement**: [13] argues a *linked secondary* matrix view still adds value for locally dense sub-regions — this is additive, not competing, with [12]'s conclusion. Design-guide §3 adopts node-link as primary, matrix as an optional power-user inset.

### Scaling to large graphs (the central unsolved problem)
- **Sources**: [18] Canonical Debate Lab (explicitly names this the hardest open problem: "10,000-foot view...and still provide reasonable navigability") · [19] MIT Deliberatorium (proposes automated deliberation-metrics-driven recommendations as the answer at real scale) · [10] progressive disclosure (two-level cap, insufficient alone at this scale per [19]) · [13] (linked views as a *local*-density answer, not a whole-graph answer)
- **Disagreement, not fully resolved by any single source**: [18] suggests multiple purpose-built view *modes* might be the only honest answer ("perhaps it is not possible to support all these goals simultaneously via a single interface"); [19] suggests a single view with an intelligent recommender layer. Design-guide §6 adopts both: multiple modes *and* a lightweight "where to look next" affordance within each mode.

### Interaction primitives (select/explore/reconfigure/encode/filter/connect/abstract)
- **Sources**: [08] Yi et al. (the 7-category taxonomy) · [09] Heer & Shneiderman (12-technique taxonomy, superset covering process/provenance too)
- **Agreement**: [09]'s taxonomy strictly extends [08]'s — no conflict, just different granularity. Design-guide §5 uses [09]'s three groupings (data/view spec, view manipulation, process/provenance) as the section structure for interaction design.

### Filtering & collapse/expand
- **Sources**: [08] (Filter category) · [10] (progressive disclosure as the UX-practice framing of the same idea) · [17] Kialo (branch collapse/expand as the concrete mechanism)
- **Agreement**: Consistent.

### Annotation, sharing state, and provenance/history
- **Sources**: [09] (Record/Annotate/Share as a named, distinct group — argues these are under-served relative to pure exploration features) · [07] (History as one of the 7 original task types) · [15] Conklin (pointing back to a captured argument as a social/rhetorical tool, not just a technical one) · [18] (transparency/versioning named as one of the 6 unsolved challenges)
- **Agreement**: All sources treat this as necessary but consistently under-built in existing tools — a genuine gap rather than a debated point.

### IBIS notation and the argumentation-graph domain model
- **Sources**: [14] Wikipedia IBIS (4 node types: issue/position/argument/nested-issue) · [15][16] Conklin & Eight2Late (3-element simplification: issue/idea/argument; the minimalism is treated as deliberate) · [19] Deliberatorium (same 3-element IBIS core, at scale) · [20] Wikipedia argument-map (contrasts with the richer Toulmin "warrant" relation)
- **Disagreement**: 3-element (Conklin/Deliberatorium) vs. 4-element (Wikipedia IBIS, counting nested-issue separately) vs. Toulmin's richer warrant-relation model are three different levels of schema richness, all defended by different sources as sufficient for their context. Design-guide §1 recommends checking this against the agora backend's actual schema (pending — see open items) rather than assuming one is "correct."

### Real-time / live authoring and the provisional-capture workflow
- **Sources**: [15] Conklin ("guess and check the guess" listening cycle; low-tech whiteboard IBIS is equally valid to software) · [16] Eight2Late (facilitation as craft, not mechanical process) · [21] Argdown (text-first authoring, decoupled from the visual projection)
- **Tension**: [15]/[16] assume a human facilitator doing live capture during a synchronous conversation; [21] assumes a solo/asynchronous author writing structured text. Design-guide §5 treats these as two distinct authoring modes the frontend should support separately, not one universal editor.

### Moderation & dedup affordances
- **Sources**: [16] (merge/rephrase/flag-as-duplicate as facilitator actions) · [19] (structural-only moderation, ~1:20 moderator:author ratio, "pending" status workflow) · [17] Kialo (gatekeeping criticized when opaque)
- **Agreement**: Moderation as a first-class, fast UI surface (not an admin-only afterthought) is uniformly supported.
- **Nuance**: [19]'s empirical ~70%-certified-without-changes finding suggests moderation friction can be kept low if the format is well-explained upfront — relevant to onboarding design.

### Rating-based sorting vs. showing full structure
- **Sources**: [17] Kialo (rating-sorted Pro/Con columns as the default, working answer to "which of 40 rebuttals to show") · [18] Canonical Debate Lab (names this as directly in tension with a curator's need for full-structure visibility) · [18] again (roll-up scoring as the missing piece: how does leaf-level strength propagate to the root thesis)
- **Disagreement, explicitly named by [18]**: rating-sorted view and full-structure view serve different intents and probably shouldn't be forced into one screen. Design-guide §2 adopts this as two distinct modes, not a single hybrid.

### Data/view separation as an architectural principle
- **Sources**: [21] Argdown (data source decoupled from visualization; multiple views over one model) · ComputeNet's own vision doc (`doc/spec/00-foundations/01-vision.md`): "Links connect ports; the topology is explicit, inspectable, and mutable at runtime" — the backend already treats the graph as data, not as a fixed picture.
- **Agreement**: This is a case where the backend's own architecture and the UX literature independently converge — strengthens the case for a multi-view frontend (see "multiple view modes" and "scaling" above).

### Toulmin model / richer relation semantics
- **Sources**: [20] (warrant as the reason *behind* an inference, distinct from the inference itself)
- **Status**: Single-source, not corroborated elsewhere in this set — flagged as worth validating against the actual agora schema rather than treated as settled guidance.

### Accessibility (color, contrast, keyboard, screen reader, motion)
- **Sources**: [05] Cambridge Intelligence accessible graph viz (concrete keyboard/ARIA/contrast/motion guidance) · [20] (notes most argument-mapping tools fail on accessibility, one exception cited)
- **Agreement**: Consistent; [05] is the operative source, [20] confirms this is a known gap in the domain specifically (i.e., don't assume argumentation tools are accessible by default just because general graph-viz guidance exists).

### Collaborative/multi-user editing UX (optimistic UI, conflict handling, undo, presence)
- **Sources**: [23] Figma multiplayer (optimistic local updates; hide transient conflicts; predictable undo under concurrency) · [24] Prototypr live cursors (presence as a "feels alive" signal; restraint pattern — don't let presence UI compete with content)
- **Agreement**: Consistent; two different aspects (correctness/consistency vs. presence/social signal) of the same theme, not conflicting.
- **Relevance flag**: only applicable if/when the agora backend supports concurrent multi-user graph mutation — see open items.

---

## Open items surfaced by this research (not resolved by any source, need a project decision)

1. Which relation-type schema does the agora backend actually implement — plain support/attack, IBIS-style issue/position/argument, or something richer (Toulmin-style warrants)? Several sources disagree on how much relation richness is "enough," so this needs to be settled against the real schema rather than the literature. *(Pending — see note in design guide.)*
2. Does/will the backend support concurrent multi-user editing of the same graph region? This determines whether the collaborative-editing findings (23, 24) are load-bearing or out of scope for v1.
3. At what graph size does "scaling" become the dominant design problem for this project — tens of nodes, hundreds, or thousands? [18] and [19]'s scaling guidance materially changes depending on the answer.
