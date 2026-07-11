# Graph Viz 101: A Visual Language of Node-Link Diagrams

- **Type**: Vendor best-practices article (graph analysis platform)
- **Source**: https://linkurious.com/blog/graph-viz-101-visual-language-node-link-diagrams/
- **Accessed**: 2026-07-11

## Encoding model

Recommends mapping at most five data variables onto a node-link diagram: nodes, node labels, links, one qualitative attribute, one quantitative attribute — mapped as:
- **Node size** → quantitative value
- **Node color** → qualitative category/rank
- **Labels** → identifying text
- **Links** → the relationship itself

> "Dot size corresponds to sales volume during the year. Dot color corresponds to the rank in the company."

## Three warnings every graph reading needs

1. **"Distances are not absolute but relative to local connections"** — don't let users read graphical distance between two nodes as meaningful (unlike a scatterplot, layout algorithms don't preserve a metric).
2. **"The representation may be rotated in every direction"** — orientation carries no semantic weight; up/down/left/right in a force-directed or many hierarchical layouts is often arbitrary.
3. **"Nodes at the center of the picture may not be central at all"** — visual/geometric centrality (position on screen) is not the same as network centrality (degree, betweenness); don't let layout accidentally imply importance it doesn't have.

## Layered information strategy

Recommends a "base map" model: topology (nodes + links) is the stable foundation; other attributes are added as optional interpretive layers on top. A **legend is treated as mandatory**, not optional, whenever visual encoding (color/size/shape) is used to carry meaning.

## Relevance

The three warnings are a direct caution against a natural failure mode in argumentation-graph UIs: users will be tempted to read "this claim is drawn in the middle / high up" as "this is the most important or most agreed-upon claim," which the layout algorithm may not intend at all. See index: visual-encoding, legend, misleading-layout-semantics.
