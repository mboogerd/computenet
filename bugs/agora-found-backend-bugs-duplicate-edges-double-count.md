# agora backend bug — duplicate edges compound influence (credence inflation)

**Severity**: High (integrity — a single actor can drive any node's credence to the clamp)
**Component**: `demo/agora` — `civictech.agora.AgoraService.createEdge`
**Found**: 2026-07-23, via direct `POST /op` probing on a fresh journaled instance.

## Observation

The same relation (identical `source`, `target`, `polarity`) can be created any
number of times. Each duplicate is a distinct `EdgeCell` that independently feeds
the target, so its influence **compounds**. Measured on a fresh graph, one highly
credible supporter (stance 0.95) supporting a target claim `T`:

| edges from the same source | credence(T) |
|---|---|
| 0 (base)             | 0.5000 |
| 1 × SUPPORT          | 0.7375 |
| 2 × SUPPORT (dup)    | 0.8622 |
| 3 × SUPPORT (dup)    | 0.9276 |

Reproduction:

```sh
B=http://localhost:8080
claim(){ curl -s -X POST $B/op -d "action=claim&text=$1" | sed 's/.*"ref":"//;s/".*//'; }
edge(){  curl -s -X POST $B/op -d "action=edge&source=$1&target=$2&polarity=$3" | sed 's/.*"ref":"//;s/".*//'; }
T=$(claim T); S=$(claim S)
curl -s -X POST $B/op -d "action=stance&id=$S&user=alice&value=0.95" >/dev/null
edge $S $T SUPPORT; edge $S $T SUPPORT; edge $S $T SUPPORT   # 3 identical edges — all accepted
curl -s $B/graph   # credence(T) climbs toward 0.99
```

`GET /graph` shows N distinct `EDGE` nodes with identical `source`/`target`/`polarity`.

## Expectation

A relation between two nodes with a given polarity is a single fact. Re-asserting
it should be idempotent (return/refer to the existing edge), not create a second
independent evidence path. DF-QuAD's probabilistic-sum aggregation is designed to
combine *distinct* arguments; feeding it N copies of the same argument treats one
piece of evidence as N, which is not the intended semantics. As-is, any client can
push any claim (or any edge) to the `[0.01, 0.99]` clamp by spamming identical
`action=edge` calls — no stance, no distinct argument required.

## Root-cause analysis

`AgoraService.createEdge` validates only that the endpoints exist:

```kotlin
require(source in nodes) { "unknown source $source" }
require(target in nodes) { "unknown target $target" }
// … no check for an existing (source, target, polarity) edge …
```

There is no uniqueness constraint on `(source, target, polarity)`, and no index to
detect one. Every call spawns a new `EdgeCell` and wires a fresh
`influenceOutlet -> routedInfluence(target)` link, so the target sums all of them.

## Solution direction

Decide the intended semantics first — the two reasonable options:

1. **Idempotent edges (recommended)**: treat `(source, target, polarity)` as a key.
   Keep a `Map<Triple<CellRef, CellRef, Polarity>, CellRef>` (or scan `nodes`) and,
   on a duplicate `createEdge`, return the existing ref instead of spawning a new
   cell. This matches the "a relation is one fact" mental model and the UI's
   "N challenges to this link" counting (which currently would count copies).
   - Note the opposite-polarity pair `(A→B SUPPORT)` and `(A→B ATTACK)` should
     remain allowed (they are genuinely different assertions), so the key must
     include polarity, as above.
2. **Explicitly allow multiplicity but de-duplicate influence**: if multiple people
   independently asserting "A supports B" is desirable as a signal, keep the edges
   distinct but have the target combine same-`(source,target,polarity)` edges as a
   single influence (e.g. max, or one representative). This is more invasive and
   changes the aggregation model.

Option 1 is the smaller, clearer change and the natural trust-boundary fix. Add a
focused test asserting a second identical `createEdge` returns the first ref and
does not move the target's credence. Guard belongs server-side (the API is directly
callable); the frontend can additionally hide the redundant affordance.

## Related

- See `agora-found-backend-bugs-self-edges-allowed.md` — the sibling missing guard
  in the same `createEdge` validation block.
