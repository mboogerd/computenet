# shopping — backend bug: votes survive item removal (phantom votes)

**Module:** `:demo:shopping` — `demo/shopping/src/main/kotlin/civictech/demo/Main.kt`
**Severity:** medium (user-visible incorrect count; not a data-loss/safety issue)
**Found:** 2026-07-23, single-process mode, port 8137

## Observations

Reproduced end-to-end against a running instance (frames are the SSE
`/events` snapshots):

```
add  Tea            items:[…,Tea]  votes:[…]          voteCount:2
vote Tea            items:[…,Tea]  votes:[…,Tea]      voteCount:3
remove Tea          items:[…]      votes:[…,Tea]      voteCount:3   <-- Tea gone from list, still counted as voted
```

After the item is removed from the list it keeps its vote: `votes` still
contains `Tea`, `voteCount` stays `3`, and re-adding `Tea` later brings the
`★` straight back. The header `Items (N voted)` therefore counts votes for
items that are no longer on the list — a count the user cannot reconcile with
what they see (no visible item carries the "missing" star).

## Expectation

A vote is a vote *for a list item*. When the item leaves the list, it should
stop being counted/shown as voted. `voteCount` should equal the number of
distinct **currently-listed** items that have a vote, and no `★` should refer
to an absent item.

## Root-cause analysis

`handleOp` wires votes and items as two independent per-user writer cells:

```kotlin
"add"    -> itemOps.add(item)
"remove" -> itemOps.remove(item)   // touches the items writer only
"vote"   -> voteOps.add(item)      // votes writer; never removed
```

- `voteOps` (`votesUnion`) is add-only in the app; nothing ever removes a
  vote, and `remove` deliberately only tombstones the *items* writer.
- `voteCount` comes from `votesUnion → CountCell`, and the `★` from
  `votesHub`, both fed purely by the votes union. Neither is gated by item
  membership, so a removed item's vote lingers indefinitely.

The two streams are never related to each other, so the derived "voted" state
drifts from the "items" state the moment an item is removed.

## Solution direction

Two options; the second is the spec-idiomatic one:

1. **Destructive**: on `remove`, also `voteOps.remove(item)`. Simplest, but it
   throws the vote away (bad if the item is re-added) and inherits the
   per-writer OR-set caveat (only removes *this* user's vote tag — see
   `shopping-found-backend-bugs-remove-not-observed-remove.md`).

2. **Derived (recommended)**: compute the "voted" view as
   `itemsUnion ∩ votesUnion` with an `IntersectSetCell`, and drive both the
   `★` markers and the count (`CountCell` on the intersection) from that.
   Non-destructive — the raw vote is retained and re-appears if the item comes
   back — and it's pure incremental dataflow, exactly the operator library the
   demo exists to show. This session already added an `items ∩ votes`
   "still wanted" view (see the module diff) that demonstrates the wiring; the
   fix is to point `voteCount`/`★` at an intersection instead of the raw votes
   union.
