# The Eyes Have It: A Task by Data Type Taxonomy for Information Visualizations

- **Type**: Foundational academic paper (Ben Shneiderman, 1996, IEEE Symposium on Visual Languages)
- **Source**: https://www.cs.umd.edu/~ben/papers/Shneiderman1996eyes.pdf
- **Accessed**: 2026-07-11

## The mantra

> **"Overview first, zoom and filter, then details-on-demand."**

This is the single most cited interaction principle in information visualization, and the ancestor of "progressive disclosure" applied specifically to visual/spatial interfaces.

## Seven task types

1. **Overview** — a zoomed-out view of the whole collection (often paired with an adjoining detail view), so the user has a mental map before diving in.
2. **Zoom** — magnify an area of interest; smooth zoom preserves the user's spatial orientation ("point to a location and issu[e] a zooming command").
3. **Filter** — dynamic queries that remove irrelevant items so users "quickly focus on their interests by eliminating unwanted items."
4. **Details-on-demand** — select an item to reveal its full attributes, typically via click → pop-up/panel.
5. **Relate** — view relationships between items (e.g., cross-highlighting, filter-by-attribute-of-selection).
6. **History** — "keep a history of actions to support undo, replay, and progressive refinement" — exploration should be reversible/replayable, not one-way.
7. **Extract** — let users save a filtered subset or query for reuse outside the tool.

## On networks specifically

Shneiderman explicitly flags graphs as an unsolved hard case even within this framework: **"Network visualization is an old but still imperfect art because of the complexity of relationships and user tasks."** He notes node-link and matrix representations both exist, without declaring a winner — consistent with later research finding the "right" choice is task- and scale-dependent (see: node-link-vs-matrix-revisited-comparison.md).

## Relevance

This is the canonical source for "overview first" — the base pattern that Deliberatorium, Kialo, and virtually every modern graph-viz vendor guide in this reference set independently re-derive. It's also the origin of "details-on-demand," which maps directly onto "click a claim to see its full argument/evidence" in an argumentation UI. See index: overview-first, details-on-demand, progressive-disclosure, history-undo.
