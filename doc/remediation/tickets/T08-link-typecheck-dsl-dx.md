# T08 — Link type-check, DSL & observation DX

**Phase 2 · parallel with T07/T09/T10/T12 · fresh session · Sonnet 5**
**Prereq**: Phase 1 merged.
**Write scope**: `kernel/src/main/kotlin/civictech/cell/{link,graph,observe}`,
`README.md` (the building-a-flow section), `demo/slotfinder/**` (exemplar
migration), matching kernel tests.
**Do not touch**: `gen/**` (T09), other demos beyond compile fixes forced by
signatures (there should be none — everything here is additive).

## Problem

API/DX + concurrency audits (verified 2026-07-27 at `742f7ca`). Theme: the
typed affordances are real, but each is a veneer over an untyped substrate
that leaks at exactly the boundaries demos cross.

1. **Payload-type mismatch on a link is never checked — anywhere (high).**
   `ManagedHost.kt:817-818` is the entire type story of `connect`:
   `(outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)`. Both
   `FanOutlet` (:31-33) and `FanInlet` (:56-57) carry the payload class as a
   constructor field, yet the handshake (`link/Handshake.kt`) reconciles
   natures and policies and never compares payload types. The README's
   claim (`README.md:101` "typed: mismatch = compile error") does not
   survive replay: `GraphBuilder.link` lowers immediately to a string
   `ConnectStep` (`GraphDsl.kt:82,457-461`), and `GraphSpec.applyTo`/
   `applyRemote` (:262-271, :311-328) re-apply strings through the unchecked
   path — so **every** graph is untyped the second time it is applied. A
   miswired graph reports `Connected` and fails as a `ClassCastException` on
   the dispatch thread at first delivery, sanitized into a dead letter,
   arbitrarily far from the `connect` that caused it.
2. **The observation API discards the types the DSL just established
   (medium-high).** `ObserveAllBuilder.set/map/count` (`Observe.kt:210-219`)
   take a bare `CellRef` with hard-coded erased folds
   (`View.set<Any?>()`); `CompositeSink.current(): Map<String, Any?>`
   (:261). Call sites re-assert types with unchecked casts —
   `SlotFinderApp.kt:167-173`'s `as? Set<Slot> ?: emptySet()` means a wrong
   fold degrades to a **silently-empty panel**. This is the highest-traffic
   API in the repo (every demo's UI edge).
3. **`graph {}` cannot return a value, so every pipeline is built around
   `lateinit` + `!!` (medium).** `graph(host, block)` returns only
   `GraphSpec` (`GraphDsl.kt:478-479`); the documented happy path
   (`README.md:96-107`, `SlotFinderApp.kt:73-104`, `TieringApp.kt:68-104`)
   is `lateinit var refs` mutated from inside the block, then
   `host.lookup(items)!!` — `lookup` returns `A?` (`TypedRef.kt:28`) for a
   ref the DSL just minted, so the nullability carries no actionable
   information.
4. **`ObservationSink` fires app listeners while holding its lock on the
   host scheduler thread (medium, concurrency audit).** `Observe.kt:130-139`:
   the fold **and** `listeners.forEach { it(latest) }` run inside
   `synchronized(lock)`; `onChange` (:144-151) invokes the new listener
   under the same lock; `CompositeSink` (:251-268) nests `sinkLock →
   compositeLock`. The demos' SSE broadcast is registered via `onChange` —
   a blocked socket write stops the entire host from dispatching (every
   in-flight `enqueueAwaiting` then dies on the 5s timeout). The
   under-lock choice is deliberate (KDoc :112-114: total ordering of
   delivery vs catch-up) — the ordering goal is right; pinning the host
   thread to app I/O is the defect.

## Solution

### A. Payload check in the handshake (finding 1)

In `Handshake.handshake`, compare the outlet's and inlet's payload classes
(`FanOutlet.clazz` / `FanInlet.clazz`); on mismatch return
`LinkResult.Rejected("payload mismatch: <outletType> -> <inletType> at <ports>")` —
the handshake already returns `Rejected` for policy/nature failures, so this
is additive. Erasure caveat: this catches `SetDelta` vs `MapDelta`, not
`SetDelta<A>` vs `SetDelta<B>` — state that in the KDoc. Steps:

1. Implement the check; run the **whole** repo suite — any existing link
   that relied on laxness (e.g. an interface-vs-impl class asymmetry) will
   surface; resolve each by aligning the declared port types, not by
   weakening the check to `isAssignableFrom` unless a legitimate
   subtype-link exists (if so, use `isAssignableFrom` outlet→inlet and
   document why).
2. Tests: a deliberately mismatched `connect` → `Rejected` with the
   diagnostic message; the same via `GraphSpec.applyTo` replay (the
   previously unguarded path).
3. Fix `README.md:101`'s comment to the honest claim (compile-checked at DSL
   build; runtime-rejected on replay/string paths).

### B. Typed observation (findings 2)

Additive overloads — do not break the `CellRef` forms:

1. `ObserveAllBuilder.set/map/count` overloads accepting `TypedRef<...>`.
2. `CompositeSink`: registration returns (or exposes by name) a typed handle;
   add `fun <T> get(name: String): T` performing a **checked** cast against
   the `View` type recorded at registration, throwing with a real message
   ("'byDay' was registered as count (Map<String,Long>), requested
   Set<Slot>") instead of the silent `as?`-empty degrade.
3. Migrate `SlotFinderApp` (the exemplar demo) off its `@Suppress
   UNCHECKED_CAST` unwraps (:167-173) onto the typed accessors; leave other
   demos for opportunistic migration (note in report).

### C. `graph` result + `lookupOrThrow` (finding 3)

1. Add a result-carrying overload: `fun <R> graph(host, block: GraphBuilder.() -> R): Pair<R, GraphSpec>`
   (or `graphOf` if overload resolution against the existing `Unit` form is
   ambiguous — try the overload first; keep exactly one new entry point).
2. Add `fun <A : Any> Host.lookupOrThrow(ref: TypedRef<A>): A` with a
   message naming the cell/ref on failure.
3. Rewrite the README example (`README.md:92-109`) without `lateinit`/`!!`;
   migrate `SlotFinderApp` as the in-tree exemplar. Other demos untouched.

### D. Listener dispatch off the host thread (finding 4)

Preserve the total-ordering guarantee, drop the host-thread hostage-taking:
give each `ObservationSink` a dedicated single-thread dispatch executor —
the fold and snapshot swap stay under `lock` on the host thread; listener
invocation is submitted to the sink's executor (single consumer ⇒ total
order preserved, including the `onChange` catch-up which enqueues the
current snapshot to the same executor). `CompositeSink` routes through the
same mechanism (its nested-lock issue dissolves when listeners no longer run
under `sinkLock`). Lifecycle: the executor is a daemon thread created
per-sink; add `close()` if the sink has a disposal path — check `observe`'s
lifecycle and follow it. Update the KDoc at `Observe.kt:112-114` to state
the new ordering argument. Tests: (a) ordering — a subscriber added
mid-stream sees catch-up then increments with no gap/duplicate; (b) a
listener that blocks does not stall host dispatch (bounded-wait assert that
other cells keep processing).

## Verification

```bash
./gradlew :kernel:test
./gradlew test
./gradlew :demo:slotfinder:test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
```

## Report

Any link the payload check flagged in the existing tree (each is a latent
miswire — list them prominently); the README diff; ordering-test names for D.
