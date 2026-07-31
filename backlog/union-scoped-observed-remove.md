# Union-scoped observed-remove ("remove what I can see")

> **Status: IMPLEMENTED** — shape 1 (coordinating remove over the union),
> `D-UNION`. `UnionSetCell.removeObserved(e)` via `ObservedRemoveOps`;
> `TagState` gained an opt-in `retainTombstones` mode so the union fences
> catch-up resurrection. `demo/shopping`'s `Main.kt` routes `"remove"` through
> `removeObserved`, with per-writer `"remove-mine"` kept as a distinct action.
> See `kernel/.../cell/data/op/UnionSetCell.kt`,
> `kernel/.../cell/data/delta/TagState.kt`, `UnionObservedRemoveTest.kt`.

## Origin
In `demo/shopping`, removal is applied to the caller's **own** per-user writer
`SetCell` (`itemOps.remove(item)`). Observed behavior (verified live):
```
alice add Coffee ; bob add Coffee     -> Coffee present (two OR-set tags)
alice remove Coffee                   -> Coffee STILL present (only alice's tag tombstoned)
bob   remove Coffee                   -> Coffee gone
```
An item added by N users needs all N to remove it; a user's remove of an item
they didn't personally add is a silent no-op — yet the UI offers "remove" on
every item to everyone. This is correct *per-replica* OR-set semantics
(`SetCell.remove` can only tombstone tags in its own local `TagState`), but there
is **no primitive** for the thing a shared list actually wants: "remove this
element as it currently exists in the merged view." The per-user writers are
load-bearing (deterministic identity for journal replay, M10.4), so the fix
cannot be "use one shared writer."

## What it is
An observed-remove that is scoped to a **merged/union view** rather than a single
writer: given an element and the union's currently-observed tag set for it, issue
the tombstones that cover all of those tags across their originating writers, so
one remove retracts every causally-preceding add the remover has seen.
(Add-wins for genuinely *concurrent* adds is preserved — only tags observed
before the remove are tombstoned.)

## Why it fits the framework
- It is squarely in the OR-set model the framework already implements
  (observed-remove tags, spec 24) — it generalizes remove from "replica-local"
  to "over an explicitly-named merged view", which is the level a multi-writer
  `UnionSetCell` actually operates at.
- The framework's story is that in-process and distributed paths share observable
  semantics; today "anyone can delete" simply isn't expressible, so apps invent
  ad-hoc coordinators. A named primitive keeps the CRDT reasoning in the kernel.
- It cleanly separates two intents the demo currently conflates: retract *my*
  contribution (writer-local, exists today) vs. remove *the item* (union-scoped,
  missing).

## Solution sketch
Two candidate shapes, to be chosen during design:
1. **Coordinating remove over a union:** `UnionSetCell.removeObserved(e)` reads
   the element's live tags from the union's merged `TagState` and emits tombstones
   for exactly those tags (routed to whichever writers own them, or as a union-level
   tombstone delta the merge honors). Journaled like any other routed op, so replay
   stays deterministic.
2. **Authoritative shared "eraser" writer:** a dedicated cell that observes the
   union and, on `remove(e)`, tombstones the observed tag set — leaving per-user
   *add* writers untouched. Keeps writer identity for adds; centralizes deletes.

Distributed caveat to resolve in design: a remove can only cover tags this node
has observed; a concurrently-arriving remote add (unobserved) survives by
add-wins — document this as the intended boundary, not a bug.

## Inputs / outputs
- **Input:** an element `e` and the merged view's current tags for `e`.
- **Output:** a `SetDelta` whose `dels` cover all observed tags of `e`; the union
  membership for `e` drops iff no unobserved/concurrent add remains.

## Acceptance criteria
- After `alice add e; bob add e; alice removeObserved e`, `e` is absent in the
  converged union (single remove suffices), while `alice add e; (concurrent) bob add e; alice removeObserved e`
  keeps `e` (add-wins on the concurrent, unobserved tag).
- Journal replay reconstructs the same post-remove membership after `kill -9`
  (deterministic tags preserved).
- Two-JVM convergence: a `removeObserved` on peer A converges on peer B for all
  tags A had observed at remove time; the concurrent-add boundary is covered by a
  test and documented.
- `demo/shopping` can offer a working "anyone can remove" button (and/or a
  distinct "remove mine") built on the primitive, replacing the current
  silently-inert button.
