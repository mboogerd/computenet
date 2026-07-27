# Dynamic Graph Exploration by Interactively Linked Node-Link Diagrams and Matrix Visualizations

- **Type**: Academic paper (Visual Computing for Industry, Biomedicine, and Art, Springer, 2021)
- **Source**: https://link.springer.com/article/10.1186/s42492-021-00088-8
- **Accessed**: 2026-07-11

## Core idea

Rather than picking node-link *or* matrix (see 12), keep both on screen simultaneously and link them: interactions (selection, clustering, reordering) in one view propagate to and inform the other.

> "insights such as clusters or anomalies from one or several combined views can be used to influence the layout or reordering of the other views."

Rationale: real graphs are frequently **globally sparse but locally dense** — exactly the profile where neither representation alone is uniformly best. Node-link handles the sparse regions well; matrix handles locally dense clusters well.

> "Because graphs can contain both properties, being globally sparse and locally dense, a combination of several visual metaphors as well as static and dynamic visualizations is beneficial."

## Concrete UX mechanism

Selecting a cluster in one view (say, via a layout/reordering algorithm's output) can be applied as a filter across other views/layouts — letting the user check whether a cluster found by one algorithmic lens holds up under a different one, essentially triangulating structure with the user's own visual judgment rather than trusting one algorithm's output blindly.

## User-study feedback (real, not hypothetical)

Participants wanted: more simultaneous views, better zoom/focus controls, label-based (not just visual) selection, and search over node properties — reinforcing that even a well-designed dual-view system still needs strong search/filter primitives layered on top.

## Relevance

Directly informs a "power user" tier for an argumentation-graph UI: once a debate has hundreds of claims and a densely contested sub-region (a claim with 40 competing rebuttals, say), a linked matrix/adjacency inset for *that specific sub-region* could resolve the crossing/clutter problem that a pure node-link view can't avoid at that local density — without abandoning node-link as the default global view. See index: node-link-vs-matrix, scale-to-large-graphs, linked-views.
