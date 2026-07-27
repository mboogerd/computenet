# How Figma's Multiplayer Technology Works

- **Type**: Engineering/product blog post (Figma)
- **Source**: https://www.figma.com/blog/how-figmas-multiplayer-technology-works/
- **Accessed**: 2026-07-11

## Perceived responsiveness over strict consistency

> "Property changes on the client are always applied immediately instead of waiting for acknowledgement from the server since we want Figma to feel as responsive as possible."

Local-first optimistic updates are treated as a UX requirement, not just a performance nicety — the interaction has to feel instant even though the "true" state lives on a server other users are also editing.

## Avoiding visual flicker during conflicts

To keep concurrent edits from producing jarring visual snaps, Figma discards server updates that conflict with a user's own unacknowledged local change, instead showing "our best prediction of what the eventually-consistent value will be" — i.e., the UI actively hides transient inconsistency rather than surfacing raw conflict state to the user.

## Accepting a visible, bounded tradeoff

For rare structural conflicts (e.g., two users reparenting the same object simultaneously), the chosen solution lets "the object temporarily disappear" rather than build a more complex resolution UI — an explicit acknowledgment that some conflict cases are rare enough to accept a simple, slightly-visible glitch instead of engineering a perfect fix.

## Predictable undo under concurrency

> "if you undo a lot, copy something, and redo back to the present (a common operation), the document should not change" — undo/redo must remain intuitive to a single user even while other users are concurrently editing the same document, which the naive/local-history version of undo does not guarantee.

## Relevance

ComputeNet's own architecture (per doc/spec: incremental delta propagation, "the topology is explicit, inspectable, and mutable at runtime") already matches this optimistic, delta-driven collaboration model conceptually. This source is useful less as "should we add live cursors" and more as a caution: if the agora backend supports concurrent multi-user editing of the argument graph, the frontend needs an explicit strategy for (a) applying local changes optimistically, (b) hiding transient conflict states rather than exposing raw diffs, and (c) making undo behave predictably per-user despite concurrent remote changes. See index: collaborative-editing, optimistic-ui, undo-under-concurrency.
