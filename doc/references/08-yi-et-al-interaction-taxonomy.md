# Toward a Deeper Understanding of the Role of Interaction in Information Visualization

- **Type**: Academic paper (Yi, ah Kang, Stasko, Jacko — IEEE InfoVis 2007 / TVCG)
- **Source**: https://faculty.cc.gatech.edu/~stasko/papers/infovis07-interaction.pdf
- **Accessed**: 2026-07-11

## Seven categories of interaction technique

1. **Select** — "mark something as interesting"; make chosen items visually distinct so users can track them across views/changes (e.g., marking in Dust & Magnet).
2. **Explore** — "show me something else"; pan, walk through the information space when it doesn't fit on screen at once.
3. **Reconfigure** — "show me a different arrangement"; change the spatial layout to expose different relationships (sort a table's columns, rotate a 3D view).
4. **Encode** — "show me a different representation"; change color/size/shape/orientation mappings without moving the underlying data.
5. **Abstract/Elaborate** — "show me more or less detail"; drill-down and zoom between overview and granular attributes.
6. **Filter** — "show me something conditionally"; dynamic-query sliders/toggles that immediately narrow the visible set.
7. **Connect** — "show me related items"; highlight relationships between elements (brushing across coordinated views) or surface hidden-but-relevant items.

## Relevance

This is a more implementation-oriented complement to Shneiderman's task taxonomy (07) — it's less "what workflow should the UI support" and more "what specific interaction primitives do I need to build." Every one of the seven maps onto a concrete argumentation-graph UI feature:
- **Select** → pinning/bookmarking a claim while exploring.
- **Explore** → panning the canvas.
- **Reconfigure** → switching between tree layout and force-directed layout for the same graph.
- **Encode** → toggling a "confidence/rating" color overlay on and off.
- **Abstract/Elaborate** → collapsing a whole sub-branch of counter-arguments into a single summarized node, or expanding it.
- **Filter** → showing only unresolved/unrated arguments, or arguments from a given participant.
- **Connect** → highlighting every node that cites or rebuts the currently-selected claim.

See index: interaction-primitives, filtering, collapse-expand.
