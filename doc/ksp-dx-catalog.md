# KSP + KotlinPoet DX catalog — cell authoring and graph wiring

Status: design catalog. **Update (RS-10.1, post `restructure(RS-4.1)`): Phases
0-4 and the §6b `@CellBase` extension have landed** (Phase 5 has not — see the
per-phase annotations in §6 below). This header and the phase bodies otherwise
describe the pre-implementation design as originally written; treat §6's
phase annotations as the authoritative landed/pending status. Scope: developer experience for
*creating new cells* and *combining cells into larger dataflow graphs*. The
spec's own codegen gaps (G-60, G-52, G-47) are catalogued as later phases but
this document does not block on them.

This catalog absorbs and supersedes two backlog proposals:
`backlog/typed-port-links.md` and `backlog/05-typed-graph-wiring.md` (their
designs appear here as Phase 1 and Phase 3). It leaves
`backlog/05-relational-view-dsl.md` and
`backlog/set-algebra-builder-combinators.md` untouched — those are DSL
vocabulary, orthogonal to codegen.

---

## 1. Motivation: the boilerplate inventory

Every item below was verified in the current tree. Numbers are stable
references used throughout this document (B1–B11).

| # | Boilerplate | Where (representative) |
|---|---|---|
| B1 | Port declarations via raw-`Class` ctor with `@Suppress("UNCHECKED_CAST")` casts | `demo/tiering/.../FuseCell.kt:77-87`, `demo/backlog-triage/.../RankingCells.kt`, `demo/skillmatch/.../SkillMatchApp.kt` — while `kernel/.../data/SetCell.kt:55` already uses the cast-free reified `FanInlet.create<SetOps<E>>()`, and `kernel/.../port/PortDelegates.kt` offers `by input<T>()`/`by output<T>()` |
| B2 | Stringly-typed wiring: `connect(from, "outlet", to, "inlet")` — typos, wrong side, or `SetDelta` vs `MapDelta` mismatches fail only at runtime as `LinkResult.Rejected` | `HostManagementApi.connect` (`kernel/.../host/Host.kt:114`), `GraphBuilder.connect` (`kernel/.../graph/GraphDsl.kt:241`), every demo app |
| B3 | Hand-written single-property proxy interfaces for `host.lookup<T>(ref)` — 23 of them | `demo/agora/.../AgoraService.kt:16-19`, `demo/tiering/.../TieringApp.kt:104-112`, `demo/slotfinder/.../SlotFinderApp.kt:93`, plus every `*PipelineTest.kt` |
| B4 | Late-join catch-up block (`outlet.linking.onLinked = { link -> … propagate(delta-from-empty) }`) copy-pasted | `FuseCell.kt:97`, `RankingCells.kt:80,145`, `agora/.../ClaimCell.kt`, `EdgeCell.kt`, kernel data cells |
| B5 | Effective-only diff-and-emit (`publishDiff`) duplicated | `RankingCells.kt` (twice, near-identical), `FuseCell.kt` |
| B6 | `SetHubCell`/`MapHubCell`/`SetFold` families copy-pasted | `SetHubCell` in 4 demos (`shopping/Main.kt:54`, `SkillMatchApp.kt:157`, `TieringApp.kt:131`, `TriageApp.kt:149`), `MapHubCell` in 3 |
| B7 | `Stateful.snapshot()/restore()` hand-rolled `HashMap`/`ArrayList` packing with unchecked casts | `FuseCell.kt:130-141`, `agora/.../ClaimCell.kt`, `EdgeCell.kt`, `SetCell.kt` |
| B8 | Manual polymorphic wire-serializer registration listing every delta subclass | `demo/agora/.../AgoraWireSerializers.kt` |
| B9 | CRDT tag-source identity block (`UUID.nameUUIDFromBytes(...)` + counter) copy-pasted | `SetCell.kt`, `KeyedSetCell.kt` |
| B10 | `serve(object : Propagate<T> { override fun propagate(...) })` anonymous objects — `Propagate` is `@FunctionalInterface` but not a Kotlin `fun interface` | everywhere an inlet is served |
| B11 | `companion object { fun create() }` factories | every data cell (one line each; left alone) |

## 2. Constraints any DX layer must honor

From `doc/spec/00-foundations/02-design-principles.md`,
`doc/spec/50-development-process/51-construction.md`, and `AGENTS.md`:

- **P5 correctness by construction** — encode rules in types and structural
  boundaries, not runtime discipline. This is the direct mandate for typed
  wiring.
- **P9 serialization-friendly everywhere** — no lambdas or
  `java.lang.reflect.Method` on the wire; proxies are generated for this
  reason (ADR 3).
- **P3 explicit topology** — links stay first-class and inspectable; sugar
  must not hide the graph.
- **"No new semantics in the DSL layer, ever"**
  (`51-construction.md:60`) — every DSL affordance must lower to management
  invocations the host already accepts; the DSL gains *parameters, not verbs*.
  Concretely: `GraphSpec`/`ConnectStep` replay must stay byte-identical, and
  the string `connect` remains the canonical wire form and dynamic escape
  hatch.
- **Generated descriptors are authoritative runtime metadata** — thread new
  descriptor fields through the registry; never recompute reflectively; never
  hand-edit `build/generated/`.
- **Port registry key == property name** — the host resolves ports by that
  string (`kernel/.../port/PortRegistry.kt`,
  `kernel/.../proxy/HostedCellProxy.kt:49` derives it from getter names).
- Gap-table status to respect (`doc/spec/90-roadmap/91-gap-analysis.md`):
  **C-6 is resolved** (both port styles feed `PortRegistry`; delegates
  preferred for hosted cells) — the raw-`Class` cast style in demos (B1) is
  *stale style*, not a missing feature. **C-5 is resolved** for contract
  proxies (KSP-generated `ProxyRegistry`); `HostedCellProxy`'s structural
  navigation deliberately remains a dynamic proxy.

A hard KSP limitation that shapes the design: **KSP is declaration-only** (no
initializer/expression AST), so a compile-time lint that
`registerPort("left", …)` matches the property name `left` is impossible.
That invariant becomes a runtime spawn check driven by generated metadata
(§5, Phase 3).

## 3. Decision: convention over annotation

Derive everything from existing structure — `Cell` subclasses, their Api
interfaces, and port-typed properties — exactly as
`gen/.../wire/ContractProcessor.kt` already does (it convention-scans every
`Cell` subclass via `CELL_MARKER`, `ContractProcessor.kt:67-73,410`, with zero
annotations). No `@GenCell`.

Rationale: the normative authoring shape is already fixed by
`51-construction.md` (Api interface + port properties + registry-key ==
property name); an annotation would be a second source of truth and one more
thing to forget. If a cell ever needs to opt out of ports generation, add a
narrowly-scoped `@ExcludePorts` then — not in v1.

## 4. Decision: library first, KSP only for name/type mirroring

KSP generation is only warranted where *names and types must be mirrored from
source into another artifact* (metadata tables, phantom-typed constants,
proxies). Everything else is cheaper, more debuggable, and more maintainable
as plain kernel library code. The split:

| Boilerplate | Resolution | Why not codegen |
|---|---|---|
| B1 cast-style ports | **Migration only** — adopt `FanInlet.create<T>()` / `by input<T>()` (already work for generic cells; Kotlin only forbids a *bare* type parameter as reified argument, and `E` is a class parameter here) | Nothing to build |
| B2 stringly wiring | **Library** (instance path, Phase 1) + **KSP** (ref-only path, Phase 3) | Type info lives on the port objects; only the ref-only path needs generated name/type mirrors |
| B3 lookup proxies | **Library**: `TypedRef<Api>` phantom-typed ref (Phase 2) | The generic instantiation (`E = Valuation`) is a *use-site* fact KSP cannot see on the cell class |
| B4 late-join catch-up | **Library**: `FanOutlet.catchUpOnLinked { snapshotDelta }` | Pure behavior, no name mirroring |
| B5 diff-and-emit | **Library**: `MapDiffPublisher<K, V>` in kernel data | Same |
| B6 hub-cell families | **Library**: promote to kernel `cell.data`, first checking overlap with the existing `SetView`/`MapView`/`CountView` folds | Same |
| B7 snapshot packing | **Library** (low priority): small named-slots helper | Same |
| B8 wire-serializer registration | **Deferred** — kotlinx-serialization module generation is real KSP work but off the critical path | Follow-up |
| B9 tag-source identity | **Library** (low priority): expose a `MintedTags`-style minter | Same |
| B10 `object : Propagate` | **Library**: make `Propagate` a Kotlin `fun interface` | Same |
| B11 `create()` factories | Leave alone | One line each |
| Port descriptor metadata, typed port ids, spawn-time name validation | **KSP** (Phase 3) | Names/types mirrored from source; "cold cell enumerable without instantiation" requires generation |
| Membrane Mediate proxy (G-52), Borrowed-tap descriptors (G-47) | **KSP** (Phase 5, direction only) | Spec-mandated generation |

## 5. Typed-wiring design (end-to-end)

Two lowering-equivalent front ends. Both only *recover strings the caller used
to type by hand*, producing byte-identical `ConnectStep`s — no new semantics.

### 5a. Instance path (pure library) — `link(a.outlet, b.left)`

For wiring inside `graph { }` where the built cell instances are in hand.

`PortRegistry.register` additionally records a port→owner back-reference
(global synchronized `WeakHashMap<Port, Any>`; safe because port
implementations don't override `equals`):

```kotlin
data class PortAddress(val cell: CellRef, val name: String)
fun PortRegistry.Companion.addressOf(port: Port): PortAddress?  // null if owner is not a spawned Cell
```

`GraphBuilder.spawn` becomes generic so the builder stops discarding the typed
instance (today `CellHandle` keeps only `(name, ref, builder)`,
`GraphDsl.kt:227-238`):

```kotlin
fun interface TypedCellFactory<C : Cell> : CellFactory {   // still Serializable
    override fun create(ref: CellRef): C
}
open class CellHandle internal constructor(...)            // opened
class TypedCellHandle<C : Cell> internal constructor(
    name: String, ref: CellRef, builder: GraphBuilder, val cell: C
) : CellHandle(name, ref, builder)

fun <C : Cell> spawn(name: String, identity: IdentityBinding = FreshLogical,
                     parent: CellHandle? = null,
                     factory: TypedCellFactory<C>): TypedCellHandle<C>
```

Source-compatible: every existing call site is a SAM lambda
(`spawn("items") { SetCell<String>() }` infers `C`); `SpawnStep.factory:
CellFactory` is unchanged (a `TypedCellFactory` *is* a `CellFactory`), so
`GraphSpec` serialization and replay are untouched. Then:

```kotlin
// GraphBuilder — payload type Api must unify; direction enforced by Subscribe vs Serve/Use
fun <Api> link(from: Subscribe<Api>, to: Serve<Api>) {
    val f = PortRegistry.addressOf(from as Port) ?: error("outlet is not owned by a spawned cell")
    val t = PortRegistry.addressOf(to as Port) ?: error("inlet is not owned by a spawned cell")
    connect(handleOf(f.cell), f.name, handleOf(t.cell), t.name)  // existing method: applies + records ConnectStep
}
// + (Subscribe, Use) overload — distinct erasures, no clash
// + standalone live wiring outside the DSL:
fun <Api> Use<HostManagementApi>.link(from: Subscribe<Api>, to: Serve<Api>): LinkResult
```

`handleOf` reads a `ref → handle` map the builder fills during `spawn`. Usage:
`link(tierAvg.cell.outlet, fused.cell.left)` — a payload mismatch or
inlet-to-inlet wiring is a *compile error*; the recorded step is exactly
`ConnectStep("tierAvg", "outlet", "fused", "left")`.

### 5b. Ref-only path (KSP) — `connect(ref, SetCellPorts.outlet<Slot>(), …)`

For call sites holding only `CellRef`s (pipeline `Refs` structs, remote hosts,
later membranes). Kernel support types:

```kotlin
class InletId<Api>(val name: String)   // phantom Api; runtime value is just the name
class OutletId<Api>(val name: String)  // erased on purpose: ConnectStep stays strings

fun <Api> GraphBuilder.connect(from: CellHandle, out: OutletId<Api>, to: CellHandle, inn: InletId<Api>) =
    connect(from, out.name, to, inn.name)
fun <Api> HostManagementApi.connect(from: CellRef, out: OutletId<Api>, to: CellRef, inn: InletId<Api>): LinkResult =
    connect(from, out.name, to, inn.name)
```

Per public cell, the processor generates a `<CellName>Ports` object into the
cell's package. Generics survive erasure by *re-introducing the cell's type
parameters as function type parameters*, instantiated at each call site:

```kotlin
// generated: civictech/cell/data/SetCellPorts.kt
public object SetCellPorts {
    public const val INLET: String = "inlet"
    public const val OUTLET: String = "outlet"
    public const val DELTA_INLET: String = "deltaInlet"

    public fun <E> inlet(): InletId<SetOps<E>> = InletId(INLET)
    public fun <E> outlet(): OutletId<Propagate<SetDelta<E>>> = OutletId(OUTLET)
    public fun <E> deltaInlet(): InletId<Propagate<SetDelta<E>>> = InletId(DELTA_INLET)
}
```

For a non-generic cell the accessors collapse to `val` properties:

```kotlin
// generated: civictech/demo/tiering/FuseCellPorts.kt
public object FuseCellPorts {
    public val left: InletId<Propagate<MapDelta<String, Double>>> = InletId("left")
    public val right: InletId<Propagate<MapDelta<String, Double>>> = InletId("right")
    public val outlet: OutletId<Propagate<MapDelta<String, Tiered>>> = OutletId("outlet")
}
```

Port scan rules: walk own + inherited property declarations of each discovered
`Cell` subclass; classify by resolved *concrete* port class
(`FanInlet`/`Inlet` → IN, `FanOutlet`/`Outlet` → OUT). Do **not** classify by
the Api-interface projections (`Use`/`Serve`/`Subscribe`) — direction usage is
inconsistent there (`SetApi.inlet` is `Use` but `FuseApi.left` is `Serve`).
Skip private/local cells (same visibility filter as the proxy generator,
`ContractProcessor.kt:229-232`); skip a port whose type fails to resolve, with
a KSP warning naming the property.

### 5c. Descriptor extension (the G-60 port-metadata slot)

Additive extension of `gen/.../wire/ContractDescriptor.kt`:

```kotlin
enum class PortDirection { IN, OUT }
data class PortDescriptor(
    val name: String, val direction: PortDirection,
    val contractFqn: String,   // raw contract interface, e.g. civictech.cell.data.SetOps
    val contractId: Long,      // StableHash.of(contractFqn) — joins to ContractDescriptor
)
data class CellDescriptor(val fqn: String, val color: CellColor,
                          val ports: List<PortDescriptor> = emptyList())
```

Ownership/management/exclusive bits come free at runtime by joining
`contractId` into the existing `ContractDescriptor`/`MethodDescriptor` tables
— no duplication in `PortDescriptor`. This is exactly the "port metadata
(ownership flags, color, data/management marker)" line item of
`51-construction.md`'s code-generation section, and it makes cold cells
enumerable without instantiation (the KMP/registry direction noted in
`PortRegistry.kt`).

**Spawn-time name validation** (the lint KSP can't do): in `ManagedHost.spawn`,
after `PortRegistry.of(cell)` is populated, if
`ContractRegistry.cellDescriptor(cell.javaClass)` carries non-empty `ports`,
require `registeredNames ⊇ descriptorNames` — a `registerPort("lft", …)` typo
under property `left` fails loudly at spawn, naming both sets. Subset, not
equality, so dynamically-registered extra ports stay legal.

### 5d. Typed lookup without proxy interfaces (library)

The 23 proxy interfaces (B3) exist because `lookup<SetApi<Valuation>>` cannot
pin `E = Valuation` through reification. The fix is a phantom-typed ref minted
where the live instance is in hand, so the check is done once at graph build:

```kotlin
class TypedRef<A : Any>(val ref: CellRef)  // Serializable

inline fun <reified A : Any> TypedCellHandle<*>.refAs(): TypedRef<A> {
    require(cell is A) { "cell ${cell::class} does not implement ${A::class}" }
    return TypedRef(ref)
}
inline fun <reified A : Any> HostManagementApi.lookup(tref: TypedRef<A>): A? =
    @Suppress("UNCHECKED_CAST") (lookup(tref.ref, A::class.java) as A?)  // the ONE central cast
```

Pipeline `Refs` structs then carry `TypedRef<SetApi<Valuation>>`;
`host.lookup(refs.vals)!!.inlet.call` is fully typed; all proxy interfaces are
deleted. The erased class handed to `HostedCellProxy` is the Api interface
itself, whose properties are `Port` subtypes — exactly what
`HostedCellProxy.cellInvocation` requires, so the full Api interface proxies
fine.

## 6. Phased roadmap

Each phase lands green, is additive, and wire-compatible:
`ConnectStep`/`GraphSpec`/descriptor tables only gain fields with defaults; no
runtime dispatch path changes. Phases 0–2 are pure library and remove most
demo boilerplate before any processor code is written; Phase 3 is the only new
generation and pays twice (G-60 metadata + typed ref-path wiring).

### Phase 0 — library debloat + port-style migration (no KSP) — **LANDED**

- `Propagate` → Kotlin `fun interface` (`kernel/.../data/Propagate.kt`;
  binary-compatible; generated proxies unaffected). Optional sugar:
  `fun <T> Serve<Propagate<T>>.onEach(f: (T) -> Unit) = serve(Propagate(f))`. (B10)
- New `kernel/.../port/CatchUp.kt`:
  `fun <D : Any> FanOutlet<Propagate<D>>.catchUpOnLinked(snapshot: () -> D?)`
  (null = nothing to catch up; installs `linking.onLinked`). (B4)
- New `kernel/.../data/MapDiffPublisher.kt`: holds `published`,
  `publish(next: Map<K, V>)` effective-only diff-emit, `catchUpDelta()`;
  epsilon-comparator ctor param for `RatingCell`. (B5)
- Promote `SetFold`/`SetHubCell`/`MapHubCell` into kernel `cell.data` — after
  checking overlap with `SetView`/`MapView`/`CountView`; if the views already
  fold membership, hub cells become thin `onUpdate` wrappers over them. (B6)
- Demo migration: replace every `Propagate::class.java as Class<…>` with
  `FanInlet.create<…>()`/`FanOutlet.create<…>()` (or `by input<…>()` where no
  Api-interface override forces explicit style) across all six demos. (B1;
  consistent with resolved C-6's "delegates preferred".)

Tests: existing demo pipeline tests (tiering, skillmatch, slotfinder, agora)
pin behavior — migration lands green with zero test edits. Risks: low; the
`fun interface` conversion is the only kernel-semantics touch.

### Phase 1 — typed wiring, instance path (library; absorbs `backlog/typed-port-links.md` + `backlog/05-typed-graph-wiring.md`) — **LANDED**

- `PortRegistry.kt`: reverse map, `PortAddress`, `addressOf`.
- `GraphDsl.kt`: `TypedCellFactory`, `TypedCellHandle`, generic `spawn`,
  `ref → handle` map, `link` overloads, standalone
  `Use<HostManagementApi>.link`.
- Migrate `kernel/.../graph/RelationalGraphs.kt` internals plus
  `demo/tiering` and `demo/shopping` wiring to `link`.

Tests (`kernel/src/test/.../graph/TypedLinkTest.kt`): (1) equivalence — same
graph via string `connect` and via `link`, assert the two `GraphSpec`s are
step-for-step equal and a serialization round trip of the `link`-built spec
replays identically onto a second host (the backlog acceptance criterion);
(2) `addressOf` null for un-owned ports; (3) `link` against a port not spawned
in this builder throws usefully; (4) `LinkResult.Connected` + data flows.
Negative-compile assertions can't live in kernel tests (kotlin-compile-testing
sits in `gen`, which cannot depend on kernel — cycle via
`ksp(project(":gen"))`); the type system itself is the enforcement.
Risks: `spawn` signature change (no call site passes a `CellFactory`-typed
value today; only tests build `SpawnStep` directly, unaffected); SAM inference
of `TypedCellFactory<C>` from `{ SetCell<String>() }` — verify with one
compile early; fallback is a separately-named `spawnTyped`.

### Phase 2 — `TypedRef` lookups; delete the proxy-interface family (library) — **LANDED**

- New `kernel/.../graph/TypedRef.kt`: `TypedRef<A>`, `refAs<A>()`, reified
  `lookup(TypedRef<A>)` on `HostManagementApi` (+ `ManagedHost` twin).
- Migrate all demos: `Refs` structs carry `TypedRef<…Api>`; delete all 23
  proxy interfaces in `TieringApp.kt`, `TriageApp.kt`, `SkillMatchApp.kt`,
  `SlotFinderApp.kt`, `AgoraService.kt`, `shopping/Main.kt` and the pipeline
  tests.

Tests: demo pipeline tests green post-migration (they exercise outlet-side
`Subscribe` proxying, today done via hand-written outlet proxies —
`SkillMatchPipelineTest.kt:24-40`); new kernel test that `refAs<WrongApi>()`
fails at graph build and `lookup(TypedRef)` round-trips through
`HostedCellProxy`. Risk: low — the cast moved, not multiplied; if
`HostedCellProxy` chokes on some Api property shape, fall back to a narrowed
generated "client view" for that cell (reassess in Phase 3).

### Phase 3 — KSP sweep: `PortDescriptor` + `<Cell>Ports` objects (aligns with G-60) — **LANDED** (generation only; call-site migration to `<Cell>Ports`/`PortIds` is out of scope for the restructure — see `doc/RESTRUCTURE-PLAN.md`'s explicitly-out-of-scope list)

- `gen/.../wire/ContractDescriptor.kt`: `PortDescriptor`, `PortDirection`,
  `CellDescriptor.ports` (additive default).
- `gen/.../wire/ContractProcessor.kt`: port-property scan (concrete port
  classes, inherited included), `ports` emission into the cells table,
  `<CellName>Ports` generation per public cell, unresolvable-type warnings.
  Same single pass, same module hash, same aggregating `Dependencies`.
- New `kernel/.../graph/PortIds.kt`: `InletId`/`OutletId` + connect overloads.
- `ManagedHost.spawn`: descriptor ⊆ registry name check.
- Acceptance: rewire one ref-only demo path (`TieringApp` hub connects or
  `TriageApp`) to `connect(ref, XPorts.outlet(), ref, YPorts.inlet)`.

Tests: `ContractProcessorTest` additions via kotlin-compile-testing — generic
fixture cell in, assert the generated `…Ports` source and the emitted
`PortDescriptor`s; name-collision and private-cell fixtures. `gen-test` gains
a fixture cell so kernel's compile (which `dependsOn(":gen-test:test")`)
proves generation compiles. Kernel test asserting
`ContractRegistry.cellDescriptor(SetCell::class.java)!!.ports` and that the
spawn check rejects a deliberately mis-registered fixture.
Risks (the real ones): KSP resolution of *inferred* property types
(`override val inlet = registerPort(…)` — resolves in KSP) and *delegated*
properties (`by input<…>()` — may not). Mitigations in order: resolve from the
overridden Api-interface property's explicit type; warn + skip (descriptor
stays partial; the spawn check is subset-based so nothing breaks); as last
resort require explicit types on delegated port properties.

### Phase 4 — retire `gen/async` (`GenerateSuspended`) — **LANDED**

`51-construction.md` marks `GenerateSuspended` superseded by the color/adapter
model — "fold or retire (G-1)". Delete `gen/src/main/kotlin/civictech/gen/async/`,
the `gen-test` async fixtures, and the two `civictech.gen.async.*` provider
lines in `gen/src/main/resources/META-INF/services/`. Verified: no
kernel/demo/wire references. Sequenced after Phase 3 only so the richest
KotlinPoet reference code is still in-tree while writing the Ports generator.
Test: full build green.

### Phase 5 (direction only) — G-52 membrane proxy, G-47 tap descriptors — **NOT LANDED**: `civictech.cell.membrane.MediateProxy` remains the hand-written proxy this phase describes replacing; still G-52's residual.

Once membrane exposure declarations stabilize (`CompositeCell`,
`TrafficLightCell`), the same processor pass generates the
Invocation-capturing Mediate proxy per exposure and typed exposed-alias port
ids, lowering the membrane DSL to spawn/serve/delegate/connect (the
`91-gap-analysis.md` G-52 proposal). G-47's Borrowed-tap descriptors slot into
the same `PortDescriptor` row (a `role` field). Phase 3's port scan and
descriptor table are the prerequisites for both.

### Deferred / rejected

- **B8 wire-serializer module generation** — real KSP work (generate the
  kotlinx `SerializersModule` from `@Serializable` delta subclasses instead of
  hand-listing them); off the critical path, revisit after Phase 3.
- **B7/B9 snapshot + tag-source helpers** — small library helpers, land
  opportunistically with Phase 0 or whenever a cell is next touched.
- **`@GenCell`-style annotations** — rejected (§3).
- **Codegen for hub cells / catch-up / diff-emit** — rejected; library
  abstractions suffice (§4).

## 6b. Landed extension: `@CellBase` static handler binding — **LANDED**

For cells whose inlet behavior is fixed at authoring time, the imperative
`init { inlet.onEach { … } }` step is ceremony. `@CellBase` on the Api
interface generates an abstract `<Name>CellBase` that declares + registers
every port and statically binds each inlet: `Serve<Propagate<T>>` becomes
`protected abstract fun on<Name>(value: T)`; a non-Propagate contract inlet
becomes `protected abstract fun <name>Handler(): C` served at construction.
The cell is then just overridden methods — no `registerPort`, no `serve`,
and the G-17 name==property invariant cannot be violated by construction.
(KSP is additive-only, so a method-level annotation on the cell itself
cannot inject the port members; the interface-driven base is the honest
form of "declare the handler statically".) v1 ceiling: single-round
processing means subclasses of generated bases miss descriptor/Ports rows
— see `CellBase.kt`'s ponytail note for the upgrade path.

## 7. Open questions

1. **SAM inference of `TypedCellFactory<C>`** from existing
   `spawn("x") { SetCell<String>() }` call sites — expected to work, needs one
   early compile check; fallback is a separately-named `spawnTyped`.
2. **Delegated-property type resolution in KSP** (`by input<T>()`) — if KSP
   can't resolve the property type, the Api-interface override usually can;
   worst case delegated ports need explicit type annotations to appear in
   descriptors.
3. **Hub cells vs `SetView`/`MapView`/`CountView`** — the consumer-side views
   may already cover the fold half of the hub-cell families; Phase 0 should
   decide wrap-vs-replace before promoting.
4. **Where `PortAddress` back-refs live** — global `WeakHashMap<Port, Any>` in
   `PortRegistry` vs threading owner through `PortRef.cell` (which already has
   a nullable `cell: CellRef?` slot). The registry map is less invasive; the
   `PortRef` route is cleaner long-term but touches port construction
   everywhere. Phase 1 starts with the map.
