# agora backend bug — self-edges allowed (a claim can argue about itself)

**Severity**: Medium (logical coherence + self-referential credence manipulation)
**Component**: `demo/agora` — `civictech.agora.AgoraService.createEdge`
**Found**: 2026-07-23, via direct `POST /op` probing on a fresh journaled instance.

## Observation

An edge whose `source == target` is accepted (HTTP 200) and behaves as a live
relation. A claim can therefore attack or support **itself**:

```sh
B=http://localhost:8080
S=$(curl -s -X POST $B/op -d 'action=claim&text=I%20support%20myself' | sed 's/.*"ref":"//;s/".*//')
curl -s -X POST $B/op -d "action=edge&source=$S&target=$S&polarity=SUPPORT"   # -> 200, creates edge
curl -s $B/graph
```

Observed effects:

- **Self-SUPPORT** raised the claim's own credence to ~0.656 with zero external
  argument (pure circular reasoning / begging the question).
- **Self-ATTACK** lowered a claim's own credence to ~0.398.
- The self-edge is auto-designated a **cycle head** (`head: true`), because
  `reaches(from = target, to = source)` is trivially true when `target == source`.
  Head-gating (quiescence 1e-3) tames the 1-cycle so it settles instead of running
  away — so the symptom is a *wrong-but-bounded* value, not a hang.

## Expectation

A relation is between two **distinct** propositions ("B attacks A"). A node arguing
about itself is not a meaningful argumentation move — it is a self-referential loop
that lets a claim move its own credence with no external support. `createEdge`
should reject `source == target` with a 400, the same way it rejects unknown
endpoints. (The reified edge-on-edge model is unaffected: an edge targeting *another*
edge is still fine — only literal `source == target` should be refused.)

Secondary: self-loops are the one structure that makes the cycle-head machinery
fire on a degenerate input. Removing them at the boundary keeps head designation
reserved for genuine multi-node feedback, and makes durability recovery exact for
these nodes (see "Related" — the only nodes that diverged on kill-9 recovery in my
test were the two self-loop claims, drifting ~0.002, within the documented cyclic
head-threshold tolerance; eliminating self-loops removes that avoidable wobble).

## Root-cause analysis

`AgoraService.createEdge` validates endpoint existence but not distinctness:

```kotlin
require(source in nodes) { "unknown source $source" }
require(target in nodes) { "unknown target $target" }
val head = reaches(from = target, to = source)   // == true for source == target
```

No `require(source != target)`. The self-loop then flows through the normal
head/quiescence path, which is why it settles at a plausible-looking but
unjustified value.

## Solution direction

Add a single guard at the top of `createEdge`:

```kotlin
require(source != target) { "an edge cannot connect a node to itself" }
```

Cheap, at the trust boundary, and it also spares the cycle-head logic a degenerate
case. Add a focused test asserting `createEdge(x, x, …)` throws
`IllegalArgumentException` (surfaced as 400 by `AgoraApp.handleOp`).

## Related

- `agora-found-backend-bugs-duplicate-edges-double-count.md` — the sibling missing
  guard in the same validation block (uniqueness of `(source, target, polarity)`).
- Durability recovery (kill -9 + journal replay) was otherwise exact for all DAG
  nodes in testing; only these self-loop claims diverged, and within tolerance.
