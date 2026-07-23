# tiering — backend bug: `unitem` cascade races the async read model, leaving ghost items

**Status**: confirmed, reproducible
**Severity**: moderate — permanent data inconsistency; orphaned entries cannot be cleared without a server restart
**Location**: `demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt`, `handleOp` → `"unitem"` branch (≈ lines 241–251)

## Observation

Firing `add item` → `set tier` → `remove item` in rapid succession (no settle time between
ops) leaves the item's **valuation and board entry behind after the item itself is gone**.

Reproduction (from the page, 15 iterations, no inter-op await on the `tier` call):

```js
for (let i=0;i<15;i++){
  const n = 'r'+i;
  await op({action:'item',   name:n});
  op({action:'tier', agent:'z', item:n, tier:'S'}); // not awaited
  await op({action:'unitem', name:n});
}
```

Resulting `/state` (abridged):

```
items:        ["tacos"]                       // all r* items removed
signals:      ["r0","r1", … ,"r14","tacos"]   // 15 ghosts remain
valuations:   [{agent:"z", item:"r0", tier:"S"}, …]   // 15 ghost valuations
board.S:      ["r0", … , "r14"]               // ghosts sit in the S tier forever
```

Every `r*` item is absent from `items` yet still fully present in `valuations`, `signals`,
and the `S` tier of the board. Because the item is gone from `items`, the UI never renders a
rate row for it, so there is **no way to retract the ghost valuation** — it survives until the
process restarts.

## Expectation

Removing an item must retract *all* of that item's own signals (valuations + preferences), so
the item disappears cleanly from every derived view — exactly what the code comment
("cascade the item's own signals so it doesn't haunt the board") intends. This must hold
regardless of how closely the removal follows the signals that created it.

## Root-cause analysis

The cascade filters the **eventually-consistent read model**, not authoritative state:

```kotlin
"unitem" -> {
    itemOps.remove(item)
    synchronized(state) {
        valuations.filter { it.item == item }.forEach {        // <-- async-folded view
            valOps.remove(it); currentValuation.remove(it.agent to item)
        }
        prefs.filter { it.winner == item || it.loser == item } // <-- async-folded view
            .forEach { prefOps.remove(it) }
    }
}
```

`valuations` and `prefs` are app fields updated **asynchronously** by the `SetHubCell`
callbacks that run off the SSE broadcast path (`setHub<Valuation>(refs.vals) { valuations = it }`).
The pipeline runs on `VirtualThreadScheduler`, so `valOps.add(v)` from the preceding `tier` op
only *stages* a message; the hub that updates the `valuations` field folds it some time later.

When `unitem` runs before that fold completes, `valuations.filter { it.item == item }` returns
an empty list → `valOps.remove(...)` is never issued for the just-added valuation → it lingers
in `tierAvg`/`fused` → the removed item keeps a fused score and haunts the board. The `prefs`
cascade has the identical race for preferences.

The `synchronized(state)` block guards the *read* of the field but does nothing about the
add→fold latency, so it doesn't help here. Over plain HTTP the race is usually masked by
inter-request latency (curl process spawn ≈ tens of ms lets the fold land); it reproduces
reliably when ops are pipelined from one client without awaiting the intermediate fold.

## Solution direction

Drive the cascade from **authoritative, synchronously-maintained** app state rather than the
broadcast-derived view:

- A synchronous index already exists for valuations — `currentValuation` (`(agent,item) →
  Valuation`) is written inside the `tier` handler under `synchronized(state)`, *before* the
  fold. The cascade should iterate `currentValuation` entries with `key.second == item` (and
  remove them) instead of filtering the folded `valuations` set. That closes the valuation race
  with no new state.
- Preferences have no equivalent synchronous index. Add one (e.g. keep a `livePrefs:
  MutableSet<Pref>` written in the `pref`/`unpref` handlers) and cascade from it, or otherwise
  track the app's own writes so removal never depends on the read model.

More broadly: the demo's write path (op handlers) and its read path (SSE hubs) are two
different consistency domains; any op whose correctness depends on *what was previously written*
should consult the write-side record, never the asynchronously-folded read model. This is
arguably a demo-findings-worthy ergonomic gap (retraction-by-value over OR-sets forces every
app to keep its own authoritative index — cf. F-3 in `doc/demo-findings.md`).
