# Agora UI Design Specification

**Status**: Draft v2. Grounds `doc/gui-design-guide.md` (general argumentation-graph UX research, 24 sources in `doc/references/`) in the actual `agora` module as of this writing. Optimized for progressive disclosure as the graph grows from tens to thousands of claims, and for a slick, modern visual system with a concrete, validated color language.

**v2 changes** (from an API-grounded implementation review): corrected the credence-band table so both "leaning" bands derive from their strong pole at 45% opacity (§5); rewrote the hot-change signal as client-computed drift, since backend magnitude bands do *not* cross the SSE wire (§5, §7); replaced "all stances" in the detail panel with "your stance + aggregate," since `NodeDto` exposes no per-user stance data (§4, §6, §9); specified two hit targets per debate row so edges stay selectable (§3); moved edge reification into the Map-mode spec (§3); added an interaction/connection-states section (§7); and marked the density-mitigation components as v2/at-scale (§8). The companion `doc/agora-ui-implementation-plan.md` sequences the build.

**Read this after** `doc/gui-design-guide.md` — that document explains *why* each pattern below was chosen (with source citations); this document specifies *what to build* against agora's real API.

---

## 1. What agora actually exposes today (grounding)

Agora (`civictech.agora`, module `:agora`, depends only on `:kernel`) is an HTTP+SSE service, not a raw Cell/Port/Link surface — the frontend talks to it over HTTP, never by linking ports directly.

**Wire shape** (`GET /graph` returns a JSON array of these; `GET /events` is an SSE stream that pushes the *same full array* on every change — see §7 for why this matters):

```json
// NodeDto
{
  "ref": "string (UUID)",
  "kind": "CLAIM" | "EDGE",
  "text": "string | null",
  "polarity": "ATTACK" | "SUPPORT" | null,
  "source": "string (UUID) | null",
  "target": "string (UUID) | null",
  "head": false,
  "credence": 0.5
}
```

- **`kind: CLAIM`** — a claim/argument. `text` set, `polarity`/`source`/`target` null.
- **`kind: EDGE`** — a relation. `polarity` set (`ATTACK` or `SUPPORT`), `source`/`target` point at the two nodes it connects (either of which may itself be an `EDGE`). `text` is currently null for edges in the wire shape (edges are internally `ClaimCell` subtypes and *can* carry their own stances/incoming edges, but the DTO doesn't surface edge text today — flag this as a likely near-term backend addition once edges need their own label, e.g. "this attack is weak because...").
- **`credence`** — always present, `[0.01, 0.99]` in practice (DF-QuAD clamps the base score into this range, so an edge or claim is never rendered as absolutely 0 or 1 certain). This is true for both claims *and* edges — an edge has its own credence, separate from the credence of the nodes it connects.
- **`head: true`** — this edge is a designated cycle-breaker (its `sourceInlet` absorbs sub-threshold updates to stop a feedback loop oscillating forever). Rare, but when present it should be visibly explained, not silently hidden — a user who sees a value "stuck" needs to know why.

**Commands** (`POST /op`, form-encoded `action=`):
```
action=claim   text=...                                   → creates a CLAIM
action=edge    source=<ref> target=<ref> polarity=...      → creates an EDGE (source/target may be any node, including another EDGE)
action=stance  id=<ref> user=<string> value=<double|blank> → sets/clears one user's stance on any node
action=remove  id=<ref>                                    → removes a node; cascades to dangling edges
```

**No user/identity/auth model** beyond a client-supplied opaque `user` string per stance (today the debug UI uses a session-random string; the real UI should persist a random id in `localStorage`, not `sessionStorage`, so "your stance" survives a reload — see §6). **No "issue" or "debate" grouping entity** — the whole graph is one flat pool of claims and edges; there is no backend concept of a root topic. Both of these are frontend-layer concerns to design around, not backend features to assume.

**One consequence worth stating up front, since it shapes several sections below**: `GET /graph` and the SSE stream carry only *aggregate* `credence` per node — there is no per-user stance data on the wire, and no history. So "your stance" is a device-local memory of what this client last submitted (§6), the credence sparkline is session-local (§6), and the "hot change" signal is computed client-side by diffing snapshots (§7), not received from the backend. None of these are backend features to assume; all three are noted as open items in §9.

---

## 2. The core UI idea agora's data model forces: **edges are arguable, so edges must be selectable like claims**

This is the single most important, least-generic design implication in this spec (see `references/14`–`references/16` on IBIS notation for the conceptual ancestor, but agora's kernel-level realization — *EdgeCell extends ClaimCell* — is more literal than any tool in the research set). The backend's own README states the rationale plainly: *""B attacks A" is itself a claim, and someone can argue that the attack doesn't hold without arguing against B."*

Concretely: a user must be able to click an edge (an arrow between two claims) and get the same detail/interaction surface as clicking a claim — see its own credence, set a stance on it, and attack or support *it*. The visual design has to make edges feel clickable and "claim-like," not just decorative connectors. Two changes this drives, relative to a generic graph-viz tool:

- **Edges render with their own credence-driven visual weight** (see §5), not just a static line style — an edge with low credence (a rejected attack) should visually recede.
- **Selecting an edge opens the same detail panel as a claim** (§6), with an "Argue against this argument" primary action that creates a new EDGE whose `target` is the *selected edge's ref*, not the claim it points to. This is a genuinely non-obvious interaction (most users' mental model of "arguing" is claim-vs-claim) and needs a clear affordance and a one-line explainer the first few times it's used — a natural home for a short, dismissible inline hint rather than a permanent UI element (progressive disclosure again: teach it once, then get out of the way).

---

## 3. Two view modes, grounded in the real Kind/Polarity model

Per `doc/gui-design-guide.md` §2, ship two modes over the same graph rather than one universal screen:

### Debate mode (default)
A focal claim at the top, its direct `SUPPORT` edges in a right/green-leaning column and `ATTACK` edges in a left/red-leaning column (Kialo-style, `references/17`), sorted by **the edge's own credence**, descending — this is a meaningfully better sort than Kialo's user-rating average, because agora's credence is already a computed, recursively-aggregated strength score, not a raw average.

> **Sort caveat**: an edge's *effective* influence on the focal claim is `credence(edge) × credence(source)` (per the agora README), so sorting by edge credence alone can rank a strong edge from a discredited source above a moderate edge from a solid one. v1 ships the edge-credence sort as specified above (it's the simpler, more legible rule), but the frontend should compute both values and expose the product-sort as a one-line switch — a decision to make once there's real content to eyeball, not upfront.

**Each argument row is an (edge, source-claim) pair, and must expose *two* distinct hit targets** — this is the concrete realization of §2's "edges are arguable":
- The **claim card body** selects the source claim (its text, its own credence, its own arguments).
- A small, credence-band-filled **edge chip** on the connector between the row and the focal claim selects the *edge* — opening the same detail panel (§6) with "Argue against this argument" and a "N challenges to this link" count. The chip must read as clickable, not decorative; a row with no challenges still shows the chip (that's the affordance that teaches the interaction).

Each row also shows an expand affordance to see *that source claim's* own supporting/attacking sub-edges. Collapsed by default beyond 2 levels deep (NN/g's two-level cap, `references/10`), with an unambiguous "N more replies" label rather than a bare chevron (NN/g's second pitfall — "must be obvious how users progress").

### Map mode (power users / moderators)
The full node-link structure, layered/hierarchical rooted at whichever claim is currently focal, force-directed fallback scoped only to locally dense neighborhoods (`doc/gui-design-guide.md` §3). This is where cycle heads (`head: true`), edge-on-edge structures, and the true shape of the debate are visible — things Debate mode deliberately hides for clarity. Search/filter (by kind, by polarity, by credence band, by "touched recently") is the primary navigation tool here, not scroll.

**Reify every edge as a junction node.** Because agora's edges are claims that can themselves be attacked (an edge's `source` or `target` may be another edge), the drawing graph cannot be "claims are nodes, edges are lines" — a line can't be a line's endpoint. Instead, render **every `EDGE` as its own small junction node** (a diamond/dot, filled with its credence band per §5, with the cycle-guard badge when `head: true`), and draw two segments per edge: `source → junction` and `junction → target`. This is not a rendering workaround — it *is* agora's data model made visual (`EdgeCell extends ClaimCell`), and it pays for itself three ways: edge-on-edge structures become ordinary node-to-node connections, the junction gives the edge a real hit target (selectable exactly like a claim, §2/§6), and the junction's fill shows the edge's own credence at a glance. The segments then carry only polarity styling (§5); the "arguable strength" of the edge lives in the junction, not the line. Note this makes generic node-link libraries that assume edges-are-lines a poor fit (see the implementation plan).

Both modes share one entry point: since agora has no built-in "topic" grouping, the frontend needs its own lightweight **"focal claims" picker** — a curated or recency/activity-sorted list of claims to start from (this is a UI-layer concept to design and build; nothing in the backend provides it today, see §9).

---

## 4. Progressive disclosure levels (concrete, not just principle)

Two levels per view, per NN/g's rule (`references/10`), applied concretely:

| Level | Debate mode | Map mode |
|---|---|---|
| **Primary (always visible)** | Focal claim text + credence; top-level pro/con columns, 1 level deep, top 3-5 per side by credence | Immediate neighborhood of the focal claim (1-2 hops), collapsed clusters shown as a single "N claims" summary node |
| **Secondary (one explicit click away)** | "N more replies" per branch; full node detail panel on click (text, exact credence, *your* stance + aggregate, all incoming/outgoing edges, session-local history) | Expand a collapsed cluster; search/filter panel; matrix inset for a locally dense neighborhood (`doc/gui-design-guide.md` §3, `references/13`) |

Do not add a third static level — per NN/g, more than two creates its own navigation problem. Where more depth is genuinely needed at scale, that's the job of the "where to look" affordance in §7, not a third disclosure tier.

*(The Map-mode cells above describe the disclosure model at its eventual scale. Two of the mechanisms named there — collapsed-cluster "N claims" summary nodes and the matrix inset — are marked **[v2/scale]** in §8 and are not built for v1; at tens of nodes the focal neighborhood renders in full. The cells stay in the table as the target shape, not the v1 build.)*

---

## 5. Visual design system

Colors below are drawn from a validated, colorblind-checked palette (light/dark bands, contrast, and CVD separation already computed — not eyeballed) rather than picked freehand, so the same system holds up under accessibility review.

### Semantic color logic

Credence and polarity share one consistent valence logic instead of using unrelated color languages for each: **blue reads "toward belief," red reads "toward disbelief," gray reads "neutral/contested."** This applies to both node fill (credence) and edge tint (polarity), which is a deliberate choice — a support edge visually leans the same direction (blue) as a high-credence node, an attack edge leans the same direction (red) as a low-credence node, so a user's color intuition transfers between the two rather than having to learn two unrelated color codes.

**Node fill — credence, 5 discrete bands** (discrete reads faster than a continuous gradient at a glance, per `references/11`'s finding that syntactic polish alone doesn't guarantee readability; exact value is always available on hover/detail panel — details-on-demand, `references/07`):

| Band | Credence range | Light | Dark |
|---|---|---|---|
| Strongly rejected | 0.01–0.20 | `#e34948` | `#e66767` |
| Leaning rejected | 0.20–0.40 | `#e34948` @ 45% opacity over surface | `#e66767` @ 45% opacity over surface |
| Contested / neutral | 0.40–0.60 | `#f0efec` fill + diagonal hairline texture | `#383835` fill + diagonal hairline texture |
| Leaning accepted | 0.60–0.80 | `#2a78d6` @ 45% opacity over surface | `#3987e5` @ 45% opacity over surface |
| Strongly accepted | 0.80–0.99 | `#2a78d6` | `#3987e5` |

Both "leaning" bands are deliberately defined as **their strong pole at 45% opacity over the surface**, not as separately-picked hexes — this keeps the ramp internally consistent (each leaning step is visibly "the same hue, less committed"), and derives the dark-mode value for free from the strong-mode dark hex rather than needing a hand-picked one. (v1 draft carried a standalone `#5598e7` for "leaning accepted" with no dark value; that inconsistency is resolved here.)

The "contested/neutral" band additionally gets a subtle diagonal-hairline texture fill (not just flat gray) — this is the accessibility/CVD-safe channel from the same design system, repurposed here to mark "genuinely unresolved" in a way that survives grayscale or colorblind rendering, not just low chroma.

**Edge stroke — polarity**, shape-first so color is never the only signal (per `references/05`'s "don't rely on color alone"):
- **Support**: solid line, chevron arrowhead pointing at target.
- **Attack**: dashed line, flat/blocked arrowhead (T-bar) pointing at target.
- At rest, edges render in muted gray (`#898781`) regardless of polarity, to keep an unselected view calm; on hover/selection, or when the edge is one of the top-N by credence in Debate mode, the stroke tints toward the blue/red pole matching its polarity, at full saturation. This is `references/04`'s "link priority" pattern (recede the non-relevant, emphasize the relevant) implemented with agora's actual credence signal driving what counts as "relevant."

**Cycle-guard flag (`head: true`)**: a small loop icon badge on the edge, always paired with a one-line tooltip/legend explanation ("this edge dampens a feedback loop; large source changes may take a moment to fully propagate") — never color-only, since this is a structural state, not a value.

**"Hot change" indicator** (see §7 for why this matters more here than in a generic graph tool): a brief amber pulse ring (`#fab219`, the reserved status-amber, used for exactly this "state, not identity" purpose) around a node whose credence just moved by a large increment, fading over ~2 seconds, paired with a small "recently changed" label on hover — never the ring alone. **The "large increment" is computed client-side, and must be windowed, not per-message**: one stance vote produces a *burst* of SSE snapshots (one per propagation hop) whose individual per-message deltas are each small, so a per-message threshold would either never fire or fire on noise. Detect drift as `|credence(now) − credence(~2.5s ago)|` from the client's own rolling history (§6), pulsing when it crosses ~0.15–0.2 (mirroring the backend's `MAGNITUDE_BANDS` HIGH boundary). Suppress the pulse entirely on the first snapshot after a (re)connect (§7's `resync`), or a reconnect will light up the whole graph.

**Chrome**: page background `#f9f9f7` / `#0d0d0d` (dark), canvas surface `#fcfcfb` / `#1a1a19`, primary text `#0b0b0b` / `#ffffff`, secondary text `#52514e` / `#c3c2b7`, hairline borders `rgba(11,11,11,0.10)` / `rgba(255,255,255,0.10)`. System sans throughout (`system-ui, -apple-system, "Segoe UI", sans-serif`); tabular figures reserved for the numeric credence readout in the detail panel, not for in-canvas labels.

**Before shipping**: run the palette's own validator script against both 45%-opacity "leaning" steps (blue and red, light *and* dark) specifically — those are the four cells derived by opacity rather than pulled directly from a pre-validated ramp step, and should be confirmed (or nudged half a step) rather than shipped on eyeballed confidence, consistent with how the rest of this palette was built.

**Motion**: reserve animation for state transitions that carry information — a node's credence-band change, expand/collapse, and the hot-change pulse. No idle/ambient motion. A single global "reduce motion" toggle disables all of it (`references/05`), consistent with the same toggle governing the hot-change pulse and any layout-settling animation in Map mode.

---

## 6. Detail panel (details-on-demand, shared by claims and edges per §2)

Triggered by selecting any node (claim or edge). Contents:
- Full text (claim) or a synthesized description for edges without their own text today ("`SUPPORT` from *[source]* to *[target]*" — until/unless the backend adds edge text, see §9).
- Exact credence value (numeric, tabular figures) alongside the band indicator from §5 — this is the *aggregate* credence, read-only.
- **Your stance**: a continuous slider, not a binary up/down vote — agora's `StanceDelta.value` is a continuous double, and Debate-map tools that flatten this to thumbs-up/down (Kialo's model, `references/17`) lose real expressiveness the backend already supports. Include a clear "clear my stance" affordance (maps to `value: null`). **Crucial implementation note**: the wire carries no per-user stance data back (only aggregate credence), so this slider is bound to a *device-local memory* of what this client last submitted for this node — it is **not** two-way-bound to the received credence, and there is therefore nothing to reconcile when live credence updates arrive mid-drag. Keep the two visually distinct: the slider is "what I think," the credence readout beside it is "what the graph currently computes." (Persist the per-node local stance keyed by the `localStorage` user id from §1, so it survives reload.)
- **All incoming edges** (who/what supports or attacks this node), each rendered as a mini-row with its own credence band — this is where "argue against this argument" (§2) lives as an action per row. For an edge's own panel, this row list is "challenges to this link."
- A **history/provenance stub** — even a simple "this node's credence over the last N changes" sparkline is valuable (`doc/gui-design-guide.md` §5 names history/provenance as the most under-served category in existing tools) — this requires the frontend to retain its own local rolling history from SSE events, since the backend doesn't currently persist/expose history over HTTP (see §9). **Label it honestly**: the sparkline covers only "since you opened this page" — history is lost on reload and differs per client. Do not present it as an authoritative provenance record; that needs a backend history endpoint (§9).

---

## 7. Real-time updates and scaling: designing around a full-snapshot SSE contract

This is the sharpest divergence from the generic graph-viz literature and needs its own section: **`GET /events` pushes the entire graph as JSON on every single change**, not an incremental delta (the agora README names this explicitly as a known limitation). Practically, this means:

- **The frontend must do its own diffing.** On each SSE message, diff the incoming node array against the previously-rendered state client-side, and only re-render/re-animate the nodes that actually changed (by `ref` + changed fields) — never blindly re-render the whole canvas on every push, or Map mode will visibly jitter on every single stance vote anywhere in the graph, however unrelated to what the user is looking at.
- **Off-screen/collapsed nodes should still update their data model on every push (so counts and "N more replies" labels stay correct) without triggering a visual re-render** until they're actually expanded/visible — this is the mechanism that makes the progressive-disclosure levels in §4 hold up under live updates, not just on first load.
- **The magnitude signal is computed by the backend but does *not* cross the wire — the frontend re-derives it.** The backend does prioritize dramatic changes internally (`InfluenceDelta`/`CredenceUpdate` implement a magnitude interface, and `AgoraService`'s `AttentionBand`s — HIGH ≥0.2, NORMAL ≥0.05, LOW otherwise — dispatch large swings before micro-adjustments). But the SSE payload is just `NodeDto[]` with a single aggregate `credence` per node; the band a change fell into is *not* transmitted. So the frontend's "hot change" indicator (§5) and a lightweight **activity ticker** ("3 claims changed significantly in the last minute," clickable to jump to them) are computed client-side from the same diff that drives everything else — windowed drift over the client's rolling history, with thresholds deliberately mirroring the backend bands (≥0.15–0.2 → pulse, ≥0.05 → ticker) so the UI's notion of "significant" matches the scheduler's. This still answers `doc/gui-design-guide.md` §6's "where to look next" goal, and needs no backend work — but it's a client-side re-derivation, not a signal handed to us. (If the backend later surfaced the band on the wire, the frontend could drop the windowing heuristic and use it directly — noted in §9.)
- **This is a genuine scaling risk once the graph reaches hundreds/thousands of nodes** (full-payload SSE cost grows with graph size, not with change size). Flag to the team: the frontend can absorb this up to some size via client-side diffing and virtualized rendering (only mount DOM/canvas nodes for what's actually visible or expanded), but a true fix is backend-side (incremental delta push over SSE, or a WebSocket subscription scoped to a viewport/subgraph via the `:wire` module's existing `WsTransport`, which agora doesn't currently use). Treat client-side mitigation as the v1 plan and incremental backend push as a named v2 dependency, not a silent assumption. **The frontend's sync layer should be built with a single seam — one "apply this graph state" entry point — so that the eventual move to per-cell subscriptions is a swap behind that seam, not a rewrite** (the implementation plan makes this concrete).

### Interaction and connection states (don't skip these — they're skipped by default)

Per `doc/frontend-research/prompting-guide.md` §6, the non-happy-path states must be specified or they won't get built:

- **Connection status.** A small, unobtrusive status pip reflecting `connecting` / `live` / `reconnecting`. `EventSource` auto-reconnects on its own, and the backend re-sends a full snapshot immediately on every (re)connect (`AgoraApp.handleEvents`), so **that on-connect snapshot is the entire resync story** — there is no separate "refetch on reconnect" to build, and no merge logic. The one rule: flag the first snapshot after a reconnect as a `resync` and suppress hot-change pulses and ticker entries for it (§5), so recovering the connection doesn't strobe the whole graph. State still fully reconciles on that snapshot (including removals) because snapshots are absolute.
- **Empty graph.** A first-run empty state with a single clear call to action ("Add the first claim"), not a blank canvas.
- **Command errors.** `POST /op` returns `400` with a plain-text body on bad input; surface that as a brief, dismissible toast rather than failing silently. No optimistic UI: the SSE echo is effectively instant locally, so commands don't need to speculatively mutate local state (and skipping optimism removes a whole class of reconcile-on-failure bugs).
- **Loading/first paint.** Between page load and the first SSE snapshot, show a lightweight skeleton, not a flash of empty state; if no snapshot arrives within a few seconds, fall back to a one-shot `GET /graph` and show a connection-degraded hint.

---

## 8. Component inventory (for implementation planning)

Tagged **[v1]** (ships in the first build) or **[v2/scale]** (deferred — a density mitigation with no density to mitigate at tens of nodes; keep listed so it isn't re-invented, build it when the graph actually gets dense):

- **[v1]** `ClaimNode` / `EdgeNode` — shared base rendering (credence band, selection state, hot-change ring). In Debate mode `EdgeNode` is the credence-band **edge chip** on a row (§3); in Map mode it's the reified **junction node** (§3) with the polarity-styled segments as separate line primitives.
- **[v1]** `CredenceBadge` — compact band indicator + exact value on hover; used in node chrome, list rows, and the detail panel.
- **[v1]** `StanceSlider` — continuous input bound to the device-local "your stance" value, highlighted distinctly from the read-only aggregate credence (§6).
- **[v1]** `DebateColumn` — sorted, collapsible pro/con list (Debate mode).
- **[v1]** `GraphCanvas` — pan/zoom node-link renderer (Map mode) with a **layered/hierarchical layout rooted at the focal claim**; diffed re-render per §7. *(The scoped force-directed fallback is **[v2/scale]** — the layered layout alone is sufficient at v1 sizes.)*
- **[v2/scale]** `MatrixInset` — opt-in adjacency view scoped to one dense neighborhood (`references/13`). No dense neighborhoods exist yet to warrant it.
- **[v1]** `DetailPanel` — §6.
- **[v1]** `ActivityTicker` — §7's "where to look" affordance (client-computed, §7).
- **[v1]** `FocalClaimsPicker` — the UI-layer entry-point list agora doesn't natively provide (§9); a simple activity/recency-sorted list, derived on demand, not a maintained structure.
- **[v1]** `Legend` — mandatory whenever the color/shape system above is in view (`references/06`); doubles as the explainer for cycle-guard icons and the "edges are arguable" affordance on first encounter.

Also **[v2/scale]**, not in the list above but named here so they aren't accidentally built early: collapsed-cluster summary nodes, the Map-mode search/filter panel, and virtualized rendering — all three are hundreds/thousands-scale concerns.

---

## 9. Open items for the backend/product team (not resolvable from the frontend alone)

1. **Edge text**: the wire shape has no `text` field for edges today, even though `EdgeCell` architecturally supports its own stances (which implies people may want to explain *why* they think an attack/support is weak or strong in words, not just via a number). Worth a small backend addition once the "argue against this argument" flow (§2) is built.
2. **No topic/issue grouping**: the "focal claims picker" (§3, §8) is a frontend-only workaround for something the backend doesn't model. Worth deciding whether grouping belongs in agora itself eventually, or stays a client-side/curation-layer concern permanently.
3. **No history endpoint**: the sparkline in §6 requires the frontend to accumulate its own rolling history from SSE traffic, which means history is lost on page reload/new session and differs per client. A minimal backend history endpoint would fix both.
4. **Incremental SSE push**: named explicitly in §7 as the real long-term fix for scaling; client-side diffing is a mitigation, not a substitute.
5. **Identity/auth**: `user` is currently just a client-supplied string with no verification. Any real deployment needs this settled before stance data can be trusted or attributed — out of scope for this UI spec, but the UI's "who voted what" and moderation ideas from `doc/gui-design-guide.md` §5 assume *some* identity model exists.
6. **No per-user stance data in the read model / DTO**: `NodeDto` and the SSE payload expose only the *aggregate* `credence` — there is no way to read back what any user (including the current one) staked on a node. This is why the detail panel's "your stance" is a device-local memory rather than authoritative state (§6), and why "who voted what" / stance-distribution moderation views are simply not buildable today. A small DTO addition (or a per-node stance-read endpoint), gated on item 5's identity model, unblocks both. This is the most load-bearing of the open items for the detail panel.
7. **(Optional) Magnitude band on the wire**: the backend already computes an attention band per change internally (§7), but the SSE payload doesn't carry it, so the frontend re-derives "significance" with a windowing heuristic. Surfacing the band per change would let the frontend drop that heuristic and use the authoritative signal for the hot-change pulse and activity ticker. A nice-to-have, not a blocker.

---

## Source grounding

This spec inherits all citations from `doc/gui-design-guide.md`; nothing here overrides that document's general reasoning — this document only makes it concrete against agora's actual `NodeDto`/`Polarity`/`Kind`/command API as explored directly in the `agora` module source (`AgoraApp.kt`, `AgoraService.kt`, `agora/cell/*.kt`, `agora/README.md`) on 2026-07-11. Color values are drawn from a validated, colorblind-checked palette system (light/dark contrast and CVD-separation pre-computed) rather than chosen freehand.
