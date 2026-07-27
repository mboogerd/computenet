# Revisited Experimental Comparison of Node-Link and Matrix Representations

- **Type**: Academic paper (empirical comparison study)
- **Source**: https://arxiv.org/abs/1709.00293 (full text: https://ar5iv.labs.arxiv.org/html/1709.00293)
- **Accessed**: 2026-07-11

## Setup

Compares node-link (NL) diagrams against adjacency-matrix (AM) representations on a real-world, scale-free dataset of 258 nodes / 1,090 edges — notably larger than most earlier studies, which typically used 20-100 node graphs (an important scoping caveat: older "matrices win at scale" findings may not generalize to graphs this large).

## Findings by task type

- **Topology tasks** ("select all neighbors of node X") — node-link wins clearly: outperformed matrix on 5 of 10 topology tasks, lost on only 2.
- **Cluster/group tasks** (identify or compare node groups) — roughly a tie (similar results on 4 of 5 tasks), with matrix ahead on the remaining one.
- **Memorability tasks** — no meaningful difference between the two representations.

## Caveat the authors themselves flag

> "these results apply to the specific underlying network and the specific implementations of NL and AM visualizations" — i.e., don't over-generalize a single study's numbers into a universal rule; density, structure, and task all interact.

## Relevance

For an argumentation graph, the dominant user tasks are almost entirely topology-shaped ("what supports this claim," "what does this argument attack," "trace the chain of reasoning from claim to root") rather than dense adjacency-pattern tasks (e.g., "which two clusters of claims are most interconnected"). This result is a reasonably strong empirical argument for defaulting to node-link over a matrix view for the primary UI, while leaving room for a matrix/adjacency *supplementary* view for power users doing structural analysis on very dense sub-graphs (see also 13, dynamic-graph-exploration-linked-views.md, which argues for combining both). See index: node-link-vs-matrix, layout-choice.
