# KSP DX — implementation plan (full detail)

Executes `doc/ksp-dx-catalog.md`. Every code block below was written against
the current source (anchors cite file:line as of this commit). Steps are
commit-sized; each lands with `./gradlew build` green. The demo pipeline tests
(`TieringPipelineTest`, `SkillMatchPipelineTest`, `SlotFinderPipelineTest`,
backlog-triage/agora/shopping tests) are the behavior pins: green **unedited**
except in 2.2, which migrates them by design.

Commit-subject convention per repo history: `kernel: …`, `gen: …`, `demo: …`.

**[gate]** marks a spike/decision whose outcome reroutes later steps.

---

## Phase 0 — library debloat + port-style migration (no KSP)

### 0.1 `kernel: Propagate as fun interface + onEach` (B10)

Current (`kernel/src/main/kotlin/civictech/cell/data/Propagate.kt`):

```kotlin
@Contract
@FunctionalInterface
interface Propagate<T> {
    fun propagate(value: T)
}
```

New (whole file body):

```kotlin
@Contract
fun interface Propagate<T> {
    fun propagate(value: T)
}

/**
 * Serves a lambda as this inlet's handler. A plain `serve { … }` cannot
 * SAM-convert (the parameter's declared type is the type variable `Api`,
 * not the fun-interface literal), hence this named helper.
 */
fun <T> Serve<Propagate<T>>.onEach(handler: (T) -> Unit) = serve(Propagate(handler))
```

(import `civictech.cell.port.Serve`.)

Facts that make this safe:
- `fun interface` needs exactly one abstract method — satisfied.
- Existing `object : Propagate<X> { … }` sites stay valid (SAM types accept
  anonymous objects); migration of those sites is opportunistic, not forced.
- The generated `Propagate_Proxy_…` class (a class implementing the
  interface, `ContractProcessor.generateProxyClass`) is unaffected — a class
  may implement a fun interface.
- Binary compatibility: `fun interface` vs `interface` does not change the
  classfile shape of the interface.

Verify: full build; `ProxyGenerationTest` and demo tests green.

### 0.2 `kernel: FanOutlet.catchUpOnLinked` (B4)

`LinkSupport.onLinked` is `var onLinked: (Link) -> Unit = {}`
(`kernel/.../port/Link.kt:156`), `Link.to: PortRef` (`Link.kt:13`),
`FanOutlet.at(portRef): Api` (`FanOutlet.kt:157`).

New file `kernel/src/main/kotlin/civictech/cell/port/CatchUp.kt`:

```kotlin
package civictech.cell.port

import civictech.cell.data.Propagate

/**
 * Late-join catch-up (G-22): on every new link, send the current state as a
 * delta-from-empty to just the new subscriber. [snapshot] returns null when
 * there is nothing to catch up (the empty-state guard every hand-rolled copy
 * of this block carried). Installs [LinkSupport.onLinked] — the single-slot
 * hook; a cell needing additional onLinked behavior composes it manually.
 */
fun <D : Any> FanOutlet<Propagate<D>>.catchUpOnLinked(snapshot: () -> D?) {
    linking.onLinked = { link ->
        snapshot()?.let { at(link.to).propagate(it) }
    }
}
```

Adopt in kernel cells that hand-roll the block today (`SetCell`,
`KeyedSetCell`, `CombineLatestCell`, `MapCell`, …), e.g. in `SetCell`:

```kotlin
// before (init)
outlet.linking.onLinked = { link ->
    if (adds.isNotEmpty()) outlet.at(link.to).propagate(SetDelta(adds.toMap(), emptyMap()))
}
// after
outlet.catchUpOnLinked { if (adds.isEmpty()) null else SetDelta(adds.toMap(), emptyMap()) }
```

(Exact per-cell snapshot expressions: copy each cell's current block verbatim
into the lambda; do not change what is sent.) `ProtocolSupport`/StateRequest
handlers in those cells are untouched — only the `onLinked` assignment moves.

Test `kernel/src/test/kotlin/civictech/cell/port/CatchUpTest.kt`: a
`FanOutlet<Propagate<String>>` with `catchUpOnLinked`; link a subscriber late →
receives the snapshot once; snapshot () -> null → no delivery.

### 0.3 `kernel: MapDiffPublisher` (B5)

Extracted from the three near-identical implementations
(`FuseCell.kt:104-128` `handler`, `RankingCells.kt:87-102` and `:152-167`
`publishDiff`). New file
`kernel/src/main/kotlin/civictech/cell/data/MapDiffPublisher.kt`:

```kotlin
package civictech.cell.data

/**
 * Effective-only diff-and-emit state for a `MapDelta<K, V>` publisher: owns
 * the published map, recomputes touched keys, and returns only the delta
 * that actually changes downstream state — or null (emit nothing).
 * [changed] customizes the value comparison (epsilon floats: RatingCell).
 */
class MapDiffPublisher<K, V>(
    private val changed: (V, V) -> Boolean = { a, b -> a != b },
) {
    private val published = LinkedHashMap<K, V>()

    /** Recompute [keys] via [next] (null = key dies), diff against published. */
    fun publish(keys: Iterable<K>, next: (K) -> V?): MapDelta<K, V>? {
        val puts = LinkedHashMap<K, V>()
        val removals = LinkedHashSet<K>()
        keys.forEach { key ->
            val value = next(key)
            val prev = published[key]
            when {
                value == null && prev != null -> { published.remove(key); removals += key }
                value != null && (prev == null || changed(prev, value)) -> {
                    published[key] = value; puts[key] = value
                }
            }
        }
        return if (puts.isEmpty() && removals.isEmpty()) null else MapDelta(puts, removals)
    }

    /** Published state as a delta-from-empty, or null when empty (catch-up form). */
    fun catchUpDelta(): MapDelta<K, V>? =
        if (published.isEmpty()) null else MapDelta(LinkedHashMap(published), emptySet())

    /** Restore path (Stateful.restore): rebuild published from recomputed state. */
    fun reset(state: Map<K, V>) { published.clear(); published.putAll(state) }

    fun current(): Map<K, V> = published.toMap()
}
```

`FuseCell` after 0.2 + 0.3 (the shape every migrated publisher takes):

```kotlin
private val fused = MapDiffPublisher<String, Tiered>()

init {
    left.onEach { recompute(it, tierAvg) }
    right.onEach { recompute(it, prefAvg) }
    outlet.catchUpOnLinked { fused.catchUpDelta() }
}

private fun recompute(delta: MapDelta<String, Double>, side: MutableMap<String, Double>) {
    side.putAll(delta.puts)
    delta.removals.forEach { side.remove(it) }
    fused.publish(delta.puts.keys + delta.removals) { Tiering.fuse(tierAvg[it], prefAvg[it]) }
        ?.let { outlet.call.propagate(it) }
}

override fun restore(state: Serializable) {
    …rebuild tierAvg/prefAvg…
    fused.reset(buildMap { (tierAvg.keys + prefAvg.keys).forEach { i ->
        Tiering.fuse(tierAvg[i], prefAvg[i])?.let { put(i, it) } } })
}
```

Test `MapDiffPublisherTest`: add / change / remove / no-op / re-publish-same
matrix; epsilon comparator path; `catchUpDelta` empty → null.

### 0.4 `kernel: hub cells over the materialized views` (B6) **[gate]**

Gate resolved by reading `SetView` (`kernel/.../data/SetView.kt`):
`apply(delta): Boolean` returns *effective membership change* and wraps the
same `TagState` the operator cells use — so hub cells become thin wrappers
(**replace**, not wrap-decide). `MapView.apply` has the same Boolean shape.

New file `kernel/src/main/kotlin/civictech/cell/data/HubCells.kt`:

```kotlin
package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import java.util.UUID

/** Sink cell folding a SetDelta stream into live membership; [onUpdate] fires on effective change. */
class SetHubCell<E>(
    private val onUpdate: (Set<E>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private val view = SetView<E>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())

    init {
        inlet.onEach { if (view.apply(it)) onUpdate(view.current()) }
    }
}

/** Sink cell folding a MapDelta stream into current entries; [onUpdate] fires on effective change. */
class MapHubCell<K, V>(
    private val onUpdate: (Map<K, V>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private val view = MapView<K, V>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, V>>>())

    init {
        inlet.onEach { if (view.apply(it)) onUpdate(view.current()) }
    }
}
```

**Behavioral delta, called out deliberately:** the demo copies invoke
`onUpdate` on *every* delta (`TieringApp.kt:140-147`); the kernel version
guards on `view.apply` — pure tag churn no longer triggers an app update.
That is the intended fix, but check each demo's SSE/update cadence
assumptions when migrating (0.5); if a demo test counts updates, the count
may shrink.

The demo `SetFold` (`TieringApp.kt:117-128`, a naive tag fold) is deleted in
favor of `SetView` — same OR-set semantics, now the kernel's single fold.

Test `HubCellsTest`: add/remove through a linked `SetCell` → membership
callback sequence; a tag-churn-only delta → no callback.

### 0.5 `demo: port-style + helper migration` (B1, B4, B5, B6, B10)

One commit per demo. The mechanical rewrite for every cast-style port:

```kotlin
// before (FuseCell.kt:77-79 shape)
@Suppress("UNCHECKED_CAST")
override val left =
    registerPort("left", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Double>>>))
// after
override val left = registerPort("left", FanInlet.create<Propagate<MapDelta<String, Double>>>())
```

(Reified `create` handles parameterized arguments — `SetCell.kt:55` is the
in-tree precedent; the JVM class is erased identically, so no runtime change.)

Migration table (every site):

| File | Sites | Actions |
|---|---|---|
| `demo/tiering/FuseCell.kt` | :77-87 ports; :97-101 catch-up; :104-128 handler; :130-141 restore | `create<>()`; `catchUpOnLinked`; `MapDiffPublisher` (§0.3 sketch) |
| `demo/tiering/TieringApp.kt` | :117-128 `SetFold`, :131-148 `SetHubCell`, :151-169 `MapHubCell` | delete all three, import kernel `SetHubCell`/`MapHubCell` |
| `demo/backlog-triage/RankingCells.kt` | :49-53, :123-133 ports; :80-84, :145-149 catch-up; :87-102, :152-167 publishDiff | `create<>()`; `catchUpOnLinked`; `MapDiffPublisher` (RatingCell passes an epsilon `changed`) |
| `demo/backlog-triage/TriageApp.kt` | :135-184 `SetFold`/`SetHubCell`/`MapHubCell` | delete, import kernel versions |
| `demo/skillmatch/SkillMatchApp.kt` | :143-184 hub cells + ports | same |
| `demo/slotfinder/SlotFinderApp.kt` | :98+ `SlotMembership`/`SlotHubCell` | replace with kernel `SetHubCell` where the shape matches; a hub with extra per-slot logic stays local but adopts `create<>()`/`onEach` |
| `demo/shopping/Main.kt` | :54 `SetHubCell` | delete, import kernel version |
| `demo/agora/cell/ClaimCell.kt`, `EdgeCell.kt` | catch-up blocks (:70-72, :51-53); `serve(object : Propagate…)` | `catchUpOnLinked`; `onEach` (ports already use `create<>()`) |

Phase 0 acceptance (mechanical):

```
grep -rn 'as Class<' demo/                          # → empty
grep -rn 'class SetHubCell\|class MapHubCell\|class SetFold' demo/   # → empty
```

---

## Phase 1 — typed wiring, instance path (library only)

Implements `backlog/typed-port-links.md` + `backlog/05-typed-graph-wiring.md`.
Verified role hierarchy: `Use<Api> : LinkFrom<Api>`, `Serve<Api> : LinkTo<Api>`,
`Subscribe<Api> : LinkTo<Api>` (Use.kt:13, Serve.kt:9, Subscribe.kt:12), all
rooted in `Port`; `FanInlet : Use, Serve` and `FanOutlet : Use, Subscribe`.

### 1.0 **[gate]** SAM-inference spike

Before editing `GraphDsl`, scratch-compile in a kernel test:

```kotlin
fun interface TypedCellFactory<C : Cell> : CellFactory { override fun create(ref: CellRef): C }
fun <C : Cell> probe(factory: TypedCellFactory<C>): C = factory.create(CellRef(UUID.randomUUID()))
val cell = probe { SetCell<String>() }   // must infer C = SetCell<String>
```

Also serialization: SAM lambdas already Java-serialize through `CellFactory`
(the existing `GraphSpec` round-trip test proves the altMetafactory path);
`TypedCellFactory : CellFactory : Serializable` rides the same mechanism —
the equivalence test in 1.2 re-proves it. If inference fails: keep `spawn`
as-is and add `spawnTyped` with the generic signature; every later step then
says `spawnTyped` where it says `spawn`.

### 1.1 `kernel: port→owner back-refs in PortRegistry`

Current file is 39 lines (`PortRegistry.kt`). Full replacement:

```kotlin
package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import java.lang.ref.WeakReference
import java.util.*

/** A registered port's owning cell + registry name — the two strings `connect` needs. */
data class PortAddress(val cell: CellRef, val name: String)

class PortRegistry internal constructor(private val owner: WeakReference<Any>? = null) {
    private val ports = LinkedHashMap<String, Port>()

    fun register(name: String, port: Port) {
        require(ports.put(name, port) == null) { "Duplicate port name: $name" }
        owner?.let { owners[port] = it to name }
    }

    operator fun get(name: String): Port? = ports[name]

    fun names(): Set<String> = ports.keys

    companion object {
        // ponytail: JVM-global weak map; KSP-generated registries are the KMP path (C-5, M5)
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, PortRegistry>())

        // port → (owner, property name), owner held weakly: the value must not
        // strongly reach the registries key (owner) or neither entry ever clears.
        private val owners =
            Collections.synchronizedMap(WeakHashMap<Port, Pair<WeakReference<Any>, String>>())

        fun of(owner: Any): PortRegistry =
            registries.getOrPut(owner) { PortRegistry(WeakReference(owner)) }

        /** Typed-wiring seam: recovers the strings `connect` needs from a port object. */
        fun addressOf(port: Port): PortAddress? {
            val (ownerRef, name) = owners[port] ?: return null
            val cell = ownerRef.get() as? Cell ?: return null
            return PortAddress(cell.ref, name)
        }
    }
}

fun <P : Port> Any.registerPort(name: String, port: P): P =
    port.also { PortRegistry.of(this).register(name, it) }
```

Notes: the existing KDoc stays; ports don't override `equals`, so the
`WeakHashMap<Port, …>` uses identity semantics safely; `PortDelegateProvider`
(`PortDelegates.kt:17`) already routes through `of(owner).register(...)`, so
delegate-declared ports get back-refs for free. The constructor becomes
`internal` — no external construction sites exist (`of` is the only factory).

Test `PortRegistryAddressTest`: registered port on a `Cell` → address with
that cell's ref and the registered name; a port on a non-Cell owner → null;
an unregistered/free-standing port (`Use.fixed`) → null.

### 1.2 `kernel: typed spawn + link in GraphDsl`

Edits to `GraphDsl.kt` (anchors from the current file):

**(a)** After `CellFactory` (:20-22) add:

```kotlin
/** [CellFactory] that remembers the concrete cell type — SAM-compatible with every existing `spawn { … }` lambda. */
fun interface TypedCellFactory<C : Cell> : CellFactory {
    override fun create(ref: CellRef): C
}
```

**(b)** `CellHandle` (:204-211) opens; a typed subclass keeps the built
instance (today discarded at :238):

```kotlin
open class CellHandle internal constructor(
    val name: String,
    val ref: CellRef,
    internal val builder: GraphBuilder,
) {
    /** Default-port link: `writer linkTo union` connects "outlet" → "inlet". */
    infix fun linkTo(target: CellHandle) = builder.connect(this, "outlet", target, "inlet")
}

class TypedCellHandle<C : Cell> internal constructor(
    name: String,
    ref: CellRef,
    builder: GraphBuilder,
    /** The locally-built instance — typed port access for [GraphBuilder.link].
     *  Local-apply only; a remote spawn has no instance on this side. */
    val cell: C,
) : CellHandle(name, ref, builder)
```

**(c)** `GraphBuilder.spawn` (:227-239) becomes generic (same body + handle
registration); `GraphBuilder` gains a `handles` map and `link`:

```kotlin
private val handles = mutableMapOf<CellRef, CellHandle>()

fun <C : Cell> spawn(
    name: String,
    identity: IdentityBinding = IdentityBinding.FreshLogical,
    parent: CellHandle? = null,
    factory: TypedCellFactory<C>,
): TypedCellHandle<C> {
    require(names.add(name)) { "duplicate handle '$name'" }
    val ref = identity.resolve()
    val cell = factory.create(ref)
    requireBoundRef(name, identity, ref, cell.ref)
    steps += SpawnStep(name, factory, identity, parent?.name)
    return TypedCellHandle(name, host.call.spawn(cell), this, cell)
        .also { handles[it.ref] = it }
}
```

`SpawnStep.factory: CellFactory` (:63) is untouched — a `TypedCellFactory`
*is* a `CellFactory`; `GraphSpec` shape, serialization, and both apply paths
are byte-identical.

**(d)** typed `link` — three target overloads because `FanInlet` implements
*both* `Use` and `Serve`: with only `(Subscribe, Serve)` + `(Subscribe, Use)`,
passing a `FanInlet`-typed property (what `registerPort` returns, e.g.
`FuseCell.left`) would be ambiguous; the `FanInlet` overload is strictly more
specific and wins resolution. All erasures are distinct.

```kotlin
/** Typed port wiring (P5): payload mismatch or inlet-to-inlet is a compile error.
 *  Lowers to the exact string [connect] — same applied link, same recorded ConnectStep. */
fun <Api> link(from: Subscribe<Api>, to: FanInlet<Api>) = linkPorts(from, to)
fun <Api> link(from: Subscribe<Api>, to: Serve<Api>) = linkPorts(from, to)
fun <Api> link(from: Subscribe<Api>, to: Use<Api>) = linkPorts(from, to)

private fun linkPorts(from: Port, to: Port) {
    val f = requireNotNull(PortRegistry.addressOf(from)) { "link: outlet is not a registered port of a cell" }
    val t = requireNotNull(PortRegistry.addressOf(to)) { "link: inlet is not a registered port of a cell" }
    val fromHandle = requireNotNull(handles[f.cell]) { "link: cell ${f.cell} was not spawned in this graph builder" }
    val toHandle = requireNotNull(handles[t.cell]) { "link: cell ${t.cell} was not spawned in this graph builder" }
    connect(fromHandle, f.name, toHandle, t.name)
}
```

**(e)** standalone live wiring (outside the DSL), same file:

```kotlin
fun <Api> Use<HostManagementApi>.link(from: Subscribe<Api>, to: Serve<Api>): LinkResult = linkLive(from, to)
fun <Api> Use<HostManagementApi>.link(from: Subscribe<Api>, to: FanInlet<Api>): LinkResult = linkLive(from, to)

private fun Use<HostManagementApi>.linkLive(from: Port, to: Port): LinkResult {
    val f = requireNotNull(PortRegistry.addressOf(from)) { "link: outlet is not a registered port of a cell" }
    val t = requireNotNull(PortRegistry.addressOf(to)) { "link: inlet is not a registered port of a cell" }
    return call.connect(f.cell, f.name, t.cell, t.name)
}
```

Test `kernel/src/test/kotlin/civictech/cell/graph/TypedLinkTest.kt`:

```kotlin
@Test fun `link lowers to the identical ConnectStep`() {
    val stringSpec = graph(hostA.managementInlet) {
        val a = spawn("a") { SetCell<String>() }
        val u = spawn("u") { UnionSetCell<String>() }
        connect(a, "outlet", u, "inlet")
    }
    val typedSpec = graph(hostB.managementInlet) {
        val a = spawn("a") { SetCell<String>() }
        val u = spawn("u") { UnionSetCell<String>() }
        link(a.cell.outlet, u.cell.inlet)
    }
    assertEquals(stringSpec.steps.filterIsInstance<ConnectStep>(),
                 typedSpec.steps.filterIsInstance<ConnectStep>())
    // graphs-as-data acceptance: serialization round trip replays on a third host
    val bytes = serialize(typedSpec); val replayed = deserialize(bytes).applyTo(hostC.managementInlet)
    assertEquals(setOf("a", "u"), replayed.keys)
}
@Test fun `link rejects a port with no owner`() { … Use.fixed endpoint → require fails … }
@Test fun `link rejects a cell foreign to this builder`() { … spawn on another builder … }
@Test fun `linked cells flow data`() { … add via inlet, assert union membership … }
// payload mismatch is a COMPILE error — kept as a comment (kotlin-compile-testing
// lives in :gen which cannot depend on :kernel; the type system is the enforcement):
//   link(setCell.outlet /* Propagate<SetDelta<String>> */, mapHub.inlet /* Propagate<MapDelta<…>> */)
```

### 1.3 `kernel+demo: adopt link`

Migrate the internal string connects of
`kernel/.../graph/RelationalGraphs.kt`, then `demo/tiering`'s `TierPipeline`
(`TieringApp.kt:86-90`) and `demo/shopping`. Example (TierPipeline):

```kotlin
// before                                        // after
connect(vals, "outlet", tierAvg, "inlet")        link(vals.cell.outlet, tierAvg.cell.inlet)
connect(tierAvg, "outlet", fused, "left")        link(tierAvg.cell.outlet, fused.cell.left)
```

Pipeline tests stay green unedited (`GraphSpec` recorded identically).

---

## Phase 2 — `TypedRef` lookups; delete the proxy-interface family

### 2.1 `kernel: TypedRef + reified lookup`

Why not fully compile-time: the tempting
`fun <A : Any> TypedCellHandle<out A>.refAs()` does **not** compile — the
projection argument must satisfy `C : Cell`, and an Api interface (`SetApi`)
is not a `Cell` subtype. So the mint is a reified erasure-level check at
graph-build time (once, where the instance is in hand) — the same guarantee
the hand-written proxy interfaces give today, minus 23 interfaces.

New file `kernel/src/main/kotlin/civictech/cell/graph/TypedRef.kt`:

```kotlin
package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.port.Use
import java.io.Serializable

/**
 * A [CellRef] that remembers, in its type, an API the referenced cell
 * implements — minted at graph build ([refAs]) where the instance is in
 * hand, consumed by [lookup] with no per-cell proxy interface. Pure data:
 * serializable, wire-compatible, nothing but the ref at runtime.
 */
data class TypedRef<A : Any>(val ref: CellRef) : Serializable

/** Mint a typed ref; verifies (erasure-level, at build time) the built cell implements [A]. */
inline fun <reified A : Any> TypedCellHandle<*>.refAs(): TypedRef<A> {
    require(cell is A) { "cell ${cell::class.qualifiedName} does not implement ${A::class.qualifiedName}" }
    return TypedRef(ref)
}

inline fun <reified A : Any> ManagedHost.lookup(tref: TypedRef<A>): A? =
    lookup(tref.ref, A::class.java)

inline fun <reified A : Any> Use<HostManagementApi>.lookup(tref: TypedRef<A>): A? =
    call.lookup(tref.ref, A::class.java)
```

Why the erased class works: `lookup(ref, A::class.java)` hands
`HostedCellProxy.create` the *Api interface* class; its property getters
return `Port` subtypes, which is exactly the navigation
`HostedCellProxy.cellInvocation` performs (`HostedCellProxy.kt:48-61` — port
name from getter name, port Api class from the generic return type). The
full Api interface is therefore proxyable the same way the single-property
hand-written interfaces are.

Test `TypedRefTest`: `refAs<WrongApi>()` throws at build;
`lookup(TypedRef<SetApi<String>>)` → `.inlet.call.add("x")` round-trips into
the hosted cell; outlet-side navigation (`.outlet` on the Api) reaches the
port.

### 2.2 `demo: TypedRef migration, delete proxies` (tests migrated by design)

Representative before/after — `TierPipeline` (`TieringApp.kt:55-114`):

```kotlin
// before                                   // after
data class Refs(                            data class Refs(
    val items: CellRef,                         val items: TypedRef<SetApi<String>>,
    val vals: CellRef,                          val vals: TypedRef<SetApi<Valuation>>,
    val prefs: CellRef, …)                      val prefs: TypedRef<SetApi<Pref>>,
                                                val fused: TypedRef<FuseApi>, …)

// build(): refs[it.name] = it.ref          // build():
                                            Refs(items = items.refAs(), vals = vals.refAs(),
                                                 prefs = prefs.refAs(), fused = fused.refAs(), …)

// call site                                // call site
host.lookup<ValuationInletProxy>(ref)!!     host.lookup(refs.vals)!!
    .inlet.call.add(v)                          .inlet.call.add(v)

interface ItemInletProxy { … }              // deleted
interface ValuationInletProxy { … }         // deleted
interface PrefInletProxy { … }              // deleted
```

(`refAs()` infers `A` from the declared `Refs` field type via the expected
type — no explicit type argument needed at the mint site.)

Delete all 23 proxy interfaces: `AgoraService.kt:16-19` (4),
`TieringApp.kt:104-114` (3), `TriageApp.kt:126-131` (2),
`SkillMatchApp.kt:134-139` (2), `SlotFinderApp.kt:93` (1),
`shopping/Main.kt:88-92` (2), `SkillMatchPipelineTest.kt:24-40` (5),
`TieringPipelineTest.kt:23` (1), `SlotFinderPipelineTest.kt:23-27` (2), plus
any in `GraphDslTest` (kernel — migrate the same way).

Fallback: if `HostedCellProxy` fails on a particular Api property shape, keep
that one hand-written interface, note it, reassess in Phase 3.

Phase 2 acceptance: `grep -rn 'interface .*Proxy' demo/` → empty.

---

## Phase 3 — KSP sweep: `PortDescriptor` + `<Cell>Ports` (G-60 slot)

### 3.0 **[gate]** KSP property-type-resolution spike

In `gen`'s existing compile-testing setup, add fixture cells covering the
three declaration styles and assert what `KSPropertyDeclaration.type.resolve()`
yields:

```kotlin
class ExplicitCell : Cell { override val ref = … ; val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>()) }
class DelegateCell : Cell { override val ref = … ; val inlet by input<Propagate<String>>() }
interface ApiCell { val outlet: Subscribe<Propagate<String>> } // + impl overriding with registerPort
```

Expected: explicit-with-inferred-type resolves (KSP resolves inferred
property types); the delegate case may resolve to the delegate provider's
`getValue` return (`FanInlet<T>` via `ReadOnlyProperty`) or fail. Resolution
order encoded in 3.2: class property type → overridden Api-interface
property's declared type → `logger.warn` + skip. Worst case, delegated port
properties need explicit type annotations to appear in descriptors — spawn
validation is subset-based, so a skipped port never breaks anything.

### 3.1 `gen: PortDescriptor in the descriptor model`

`gen/src/main/kotlin/civictech/gen/wire/ContractDescriptor.kt` — extend
(additive, existing generated tables still compile/load):

```kotlin
enum class PortDirection { IN, OUT }

/**
 * One declared port of a cell (G-60 port-metadata slot). Ownership /
 * management / exclusivity are NOT duplicated here — join [contractId]
 * into the contract table at runtime.
 */
data class PortDescriptor(
    val name: String,          // == property name == registry key (G-17)
    val direction: PortDirection,
    val contractFqn: String,   // raw port Api interface, e.g. civictech.cell.data.Propagate
    val contractId: Long,      // StableHash.of(contractFqn)
)

data class CellDescriptor(
    val fqn: String,
    val color: CellColor,
    val ports: List<PortDescriptor> = emptyList(),
)
```

`ContractRegistry` needs no change (`cellDescriptor(clazz)` at
`ContractRegistry.kt:41` already returns the whole `CellDescriptor`).

### 3.2 `gen: port scan + Ports-object generation`

All inside the existing single pass of `ContractProcessor.process`
(`emitted` flag, module hash, aggregating `Dependencies` — unchanged).

**Scan** (new private fun; FQN constants join the companion at :404-414):

```kotlin
// companion additions
const val FAN_INLET = "civictech.cell.port.FanInlet"
const val INLET = "civictech.cell.port.Inlet"
const val FAN_OUTLET = "civictech.cell.port.FanOutlet"
const val OUTLET = "civictech.cell.port.Outlet"
// FeedbackInlet deliberately skipped in v1: its type argument is a payload,
// not a port Api contract — warn and omit.

private data class ScannedPort(
    val name: String, val direction: PortDirection, val apiType: KSType,
)

private fun scanPorts(cell: KSClassDeclaration): List<ScannedPort> =
    cell.getAllProperties().mapNotNull { prop ->
        val type = resolvePortType(prop, cell) ?: return@mapNotNull null
        val direction = when {
            isSubtype(type, FAN_INLET) || isSubtype(type, INLET) -> PortDirection.IN
            isSubtype(type, FAN_OUTLET) || isSubtype(type, OUTLET) -> PortDirection.OUT
            else -> return@mapNotNull null
        }
        val api = type.arguments.firstOrNull()?.type?.resolve()
        if (api == null) {
            logger.warn("port ${cell.simpleName.asString()}.${prop.simpleName.asString()}: unresolvable Api type — skipped", prop)
            return@mapNotNull null
        }
        ScannedPort(prop.simpleName.asString(), direction, api)
    }.toList()

// resolution order fixed by gate 3.0:
private fun resolvePortType(prop: KSPropertyDeclaration, cell: KSClassDeclaration): KSType? =
    runCatching { prop.type.resolve() }.getOrNull()?.takeUnless { it.isError }
        ?: overriddenApiPropertyType(prop, cell)   // walk cell.superTypes' declared properties by name
```

Direction classified by the **concrete** port class only — never `Use`/
`Serve`/`Subscribe` projections (`SetApi.inlet` is `Use` but `FuseApi.left`
is `Serve`; role interfaces don't encode direction reliably). The Api-
interface fallback in `resolvePortType` therefore contributes the *type
argument* (the Api contract) while direction still requires the class-side
concrete type; if only the projection is resolvable, map `Subscribe` → OUT,
`Serve` → IN, and skip `Use` with a warning (ambiguous role).

**Descriptor emission** — the cells-table initializer (:181-193) gains ports:

```kotlin
cells.forEach { cell ->
    val ports = scanPorts(cell)
    add("%T(fqn·=·%S, color·=·%T.%L, ports·=·listOf(\n⇥", CellDescriptor::class, fqn, CellColor::class, color.name)
    ports.forEach { p ->
        val contractFqn = p.apiType.declaration.qualifiedName!!.asString()
        add("%T(%S, %T.%L, %S, %LL),\n", PortDescriptor::class.asClassName(),
            p.name, PortDirection::class.asClassName(), p.direction.name,
            contractFqn, StableHash.of(contractFqn))
    }
    add("⇤)),\n")
}
```

**`<CellName>Ports` object generation** (new fun beside
`generateProxyClass`, same `sources` dependencies, same visibility filter as
:229-232; nested cells skipped with a warning in v1 — simple-name collision):

```kotlin
private fun generatePortsObject(cell: KSClassDeclaration, ports: List<ScannedPort>, sources: Dependencies) {
    val pkg = cell.packageName.asString()
    val objName = cell.simpleName.asString() + "Ports"
    val typeParamResolver = cell.typeParameters.toTypeParameterResolver()
    val cellTypeVars = cell.typeParameters.map { it.toTypeVariableName(typeParamResolver) }

    val builder = TypeSpec.objectBuilder(objName)
    ports.forEach { p ->
        val nameConst = p.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
        builder.addProperty(
            PropertySpec.builder(nameConst, STRING, KModifier.CONST).initializer("%S", p.name).build())
        val idClass = if (p.direction == PortDirection.IN) INLET_ID else OUTLET_ID
        val apiTypeName = p.apiType.toTypeName(typeParamResolver)
        val idType = idClass.parameterizedBy(apiTypeName)
        if (cellTypeVars.isEmpty()) {
            builder.addProperty(PropertySpec.builder(p.name, idType)
                .initializer("%T(%L)", idClass, nameConst).build())
        } else {
            // generics survive erasure by re-introducing the cell's type
            // parameters as function type parameters, instantiated per call site
            builder.addFunction(FunSpec.builder(p.name)
                .addTypeVariables(cellTypeVars)
                .returns(idType)
                .addStatement("return %T(%L)", idClass, nameConst).build())
        }
    }
    FileSpec.builder(pkg, objName).addType(builder.build()).build().writeTo(codeGenerator, sources)
}
// companion: val INLET_ID = ClassName("civictech.cell.graph", "InletId")
//            val OUTLET_ID = ClassName("civictech.cell.graph", "OutletId")
```

Resulting generated source, verbatim expectations for the two shapes:

```kotlin
// kernel/build/generated/ksp/main/kotlin/civictech/cell/data/SetCellPorts.kt
public object SetCellPorts {
    public const val INLET: String = "inlet"
    public const val OUTLET: String = "outlet"
    public const val DELTA_INLET: String = "deltaInlet"
    public fun <E> inlet(): InletId<SetOps<E>> = InletId(INLET)
    public fun <E> outlet(): OutletId<Propagate<SetDelta<E>>> = OutletId(OUTLET)
    public fun <E> deltaInlet(): InletId<Propagate<SetDelta<E>>> = InletId(DELTA_INLET)
}

// demo/tiering/build/generated/ksp/main/kotlin/civictech/demo/tiering/FuseCellPorts.kt
public object FuseCellPorts {
    public const val LEFT: String = "left"
    public const val RIGHT: String = "right"
    public const val OUTLET: String = "outlet"
    public val left: InletId<Propagate<MapDelta<String, Double>>> = InletId(LEFT)
    public val right: InletId<Propagate<MapDelta<String, Double>>> = InletId(RIGHT)
    public val outlet: OutletId<Propagate<MapDelta<String, Tiered>>> = OutletId(OUTLET)
}
```

**Kernel support types** — new file
`kernel/src/main/kotlin/civictech/cell/graph/PortIds.kt`:

```kotlin
package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
import civictech.cell.port.LinkResult
import civictech.cell.port.Use

/** Phantom-typed port name for the ref-only wiring path; runtime value is just the string. */
class InletId<Api>(val name: String)
class OutletId<Api>(val name: String)

fun <Api> GraphBuilder.connect(from: CellHandle, outlet: OutletId<Api>, to: CellHandle, inlet: InletId<Api>) =
    connect(from, outlet.name, to, inlet.name)

fun <Api> Use<HostManagementApi>.connect(from: CellRef, outlet: OutletId<Api>, to: CellRef, inlet: InletId<Api>): LinkResult =
    call.connect(from, outlet.name, to, inlet.name)
```

**Build wiring:** kernel already runs the processor
(`ksp(project(":gen"))` + generated srcDir, `kernel/build.gradle.kts:22`).
Demo modules do **not** apply KSP today (verified: `demo/tiering/build.gradle.kts`
has no ksp plugin) — kernel cells' Ports objects reach demos via the kernel
jar; a demo wanting Ports for its *own* cells (e.g. `FuseCellPorts`) adds:

```kotlin
plugins { alias(libs.plugins.ksp) }
dependencies { ksp(project(":gen")) }
kotlin { sourceSets["main"].kotlin.srcDir("build/generated/ksp/main/kotlin") }
```

Do this for `demo/tiering` only (the acceptance demo); others opt in when
they care.

**Processor tests** (in `gen`'s existing kotlin-compile-testing suite —
fixtures *stub* kernel types, since `:gen` cannot depend on `:kernel`; extend
the existing stub prelude the Cell-scan tests already use with:
`civictech.cell.port.FanInlet/FanOutlet` (minimal generic classes),
`civictech.cell.graph.InletId/OutletId`, `civictech.cell.data.Propagate`):

- generic fixture cell → generated `…Ports` source contains
  `public fun <E> outlet(): OutletId<…>` and the cells table carries the
  expected `PortDescriptor(name, direction, contractFqn, contractId)` rows;
- non-generic fixture → `val` properties;
- private cell → descriptor rows only, no Ports object;
- delegate-declared port → per gate 3.0 outcome (resolved or warned+skipped).

Plus a real fixture cell in `gen-test` so kernel's compile
(`dependsOn(":gen-test:test")`, `kernel/build.gradle.kts:28`) proves the
generated code compiles against the real kernel types.

### 3.3 `kernel: spawn-time port-name validation`

`ManagedHost.kt`, inside `spawn` after the port loop (:894-903), before
`cell.onActivate(ctx)`:

```kotlin
// generated descriptors are authoritative (AGENTS.md): if the processor saw
// this cell's ports, every declared port must be registered under its
// property name — the KSP-unlintable half of G-17, enforced at spawn.
ContractRegistry.cellDescriptor(cell.javaClass)?.ports?.takeIf { it.isNotEmpty() }?.let { declared ->
    val registered = PortRegistry.of(cell).names()
    val missing = declared.map { it.name }.filterNot { it in registered }
    require(missing.isEmpty()) {
        "cell ${cell.javaClass.name}: descriptor declares ports $missing not found in " +
            "registry $registered — registerPort's name must equal the property name (G-17)"
    }
}
```

Subset check (registry ⊇ descriptor): dynamically-registered extra ports stay
legal; a port the scanner skipped (3.0 fallback) never blocks a spawn.

Test: a kernel test fixture cell with a deliberate
`registerPort("lft", …)` under property `left` + a hand-registered
`ContractModule` carrying its descriptor → `spawn` throws naming both `lft`
and `left`; all existing cells spawn clean (the real regression assertion —
the full kernel test suite exercises it).

### 3.4 `demo: ref-only typed connects` (acceptance)

Rewire `demo/tiering`'s live hub connects (currently
`manage.connect(refs.X, "outlet", hub, "inlet")` string form in
`TieringApp`) to:

```kotlin
manage.connect(refs.fused.ref, FuseCellPorts.outlet, hubRef, MapHubCellPorts.inlet<String, Tiered>())
```

Pipeline test green; `TieringApp` compiles against generated
`FuseCellPorts`/kernel `SetCellPorts`.

---

## Phase 4 — retire `gen/async` (G-1 legacy cleanup)

`51-construction.md:21` marks `GenerateSuspended` superseded ("fold or retire
(G-1)"; the gap table's G-1 module deletion is done — this is its `gen/async`
remnant).

1. Re-verify no references:
   `grep -rn 'gen\.async\|GenerateSuspended' kernel demo wire --include=*.kt` → empty.
2. Delete `gen/src/main/kotlin/civictech/gen/async/` (SerializerProcessor,
   GenerateSuspended, GenerateSuspendedRegistration, AsyncRecipe,
   AsyncRecipeRegistry, DerivedSuspends, Op, KTypeOps,
   SuspendedProxyFactoryRegistry, …).
3. Delete `gen-test/src/test/kotlin/civictech/gen/async/` fixtures (Demo,
   ChanneledDataOwner, ExhaustiveInterface, …).
4. Remove the two `civictech.gen.async.*` provider lines from
   `gen/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
   (keeping `civictech.gen.wire.ContractProcessorProvider`).
5. Full build green.

Sequenced after Phase 3 only so the richest KotlinPoet reference
(`SerializerProcessor`) is in-tree while writing 3.2 — it can land earlier if
Phase 3 stalls.

---

## Phase 5 — direction only (not scheduled)

G-52 membrane Mediate proxy and G-47 tap descriptors extend the same
processor pass once membrane exposure declarations stabilize
(`CompositeCell`/`TrafficLightCell`); Phase 3's port scan + `PortDescriptor`
table (plus a future `role` field for taps) are their prerequisites.

## Deferred

- B8 wire-serializer `SerializersModule` generation — after Phase 3.
- B7 snapshot named-slots helper, B9 tag-source minter — opportunistic.

## Execution order & gates (summary)

```
0.1 → 0.2 → 0.3 → 0.4[gate: views verified — replace] → 0.5 (×6 demos)
1.0[gate: SAM] → 1.1 → 1.2 → 1.3
2.1 → 2.2 (×6 demos + kernel GraphDslTest)
3.0[gate: KSP resolution] → 3.1 → 3.2 → 3.3 → 3.4
4 (any time after 3.2 starts compiling)
```

~20 commits. After each phase: full build; spec-conformance spot-check
against `51-construction.md:60` ("no new semantics in the DSL layer") and
update `doc/spec/90-roadmap/91-gap-analysis.md` rows — G-60 (after Phase 3)
and the G-1 remnant (after Phase 4); mark `backlog/typed-port-links.md` +
`backlog/05-typed-graph-wiring.md` implemented (after Phase 1).
