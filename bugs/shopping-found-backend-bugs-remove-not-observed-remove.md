# shopping — backend bug: remove is writer-local, not observed-remove

**Module:** `:demo:shopping` — `demo/shopping/src/main/kotlin/civictech/demo/Main.kt`
**Severity:** medium (surprising UX) — **may be an intentional design choice; flagged for a decision**
**Found:** 2026-07-23, single-process mode, port 8137

## Observations

```
alice add Coffee     items:[…,Coffee]
bob   add Coffee     items:[…,Coffee]        (one entry; two OR-set tags)
alice remove Coffee  items:[…,Coffee]        <-- still present after alice removes it
bob   remove Coffee  items:[…]               <-- gone only once BOTH removers fire
```

An item added by two users needs *every* adder to remove it before it
disappears. A single user's `remove` on an item they did not personally add is
a silent no-op — yet the UI shows a `remove` button on every item to every
user, so the button appears simply not to work.

## Expectation

The README sells the model as an observed-remove set: "observed-remove tags
(spec 24) make concurrent edits order-independent." Under standard OR-Set
semantics, `remove(e)` tombstones **every add-tag of `e` the remover has
observed**. Since the union view has made both alice's and bob's `Coffee` tags
visible to everyone, either user's remove should tombstone both and the item
should leave the list on the first remove (add-wins only resolves *concurrent*
add/remove, not a remove that causally follows both adds).

## Root-cause analysis

Each user is backed by their own writer `SetCell`
(`writerFor(user)` → `demo-writer:items:$user@…`). `remove` is applied to the
caller's writer only:

```kotlin
val (itemOps, voteOps) = writerFor(user)
"remove" -> itemOps.remove(item)
```

`SetCell.remove` can only tombstone tags **held in that writer's own local
state** — i.e. tags this user's writer minted. Another user's add lives in a
different writer/replica; its tag reaches everyone through `UnionSetCell`, but
the removing writer never had it in local state, so it emits no tombstone for
it. The union keeps merging the surviving tag and the element stays live.

In effect the CRDT "replica" granularity is *per user writer*, and remove is
replica-local, so it structurally cannot express "remove what I can see that
someone else added."

## Solution direction

This is a semantics decision, not a one-line fix:

- If **shared removal** is intended (any user can delete any listed item —
  the normal shopping-list expectation), route removes so the tombstone covers
  the element's currently-observed tags across the union, not just the local
  writer's. E.g. remove against a shared/authoritative items writer, or have
  the remove path read the union's live tags for the element and tombstone them
  all (observed-remove over the merged view).
- If **per-user provenance** is intended (you can only retract your own adds),
  then this is working as designed — but the UI should reflect it (only show
  `remove` for items the current user added, or label it "remove mine"), so the
  button is not silently inert.

Either way the current behavior (writer-local remove + universal remove button)
is internally inconsistent and should be reconciled deliberately.
