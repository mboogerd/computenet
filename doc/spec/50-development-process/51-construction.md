# 51 — Constructing Cellular Programs

> **Status**: Partial (cell authoring + graph DSL exist; codegen/tooling exploratory)
> **Sources**: ADR — Cellular Software Development Process, ADR — Task Definitions, ADR 3 (codegen)
> **Implementation**: hand-written cells + host API; `cell.graph` DSL (`graph`/`GraphSpec`); KSP seed (`gen`); no scaffolding

## Authoring a cell today (normative pattern)

```kotlin
interface EchoApi {                       // 1. API interface (proxyable, 41)
    val inlet: Use<Consumer<String>>
    val outlet: Subscribe<Consumer<String>>
}

class EchoCell(override val ref: CellRef = CellRef.random()) : Cell, EchoApi {
    override val inlet by input<Consumer<String>>()     // 2. ports as declarations
    override val outlet by output<Consumer<String>>()

    override fun onActivate(ctx: CellContext) {         // 3. logic in onActivate
        inlet.serve(Consumer { outlet.call.provide(it) })
    }
}
```

Composition happens through hosts:

```kotlin
val host = ManagedHost()
val api = host.managementInlet.call
val a = api.spawn(EchoCell()); val b = api.spawn(CollectorCell())
api.connect(a, "outlet", b, "inlet")
```

Rules: contracts are push-only interfaces (12); no constructor logic (15);
declare merge semantics for concurrent state (24); declare color if not pure
(32).

## Graph construction DSL (G-30, implemented M4.5)

A builder that stays a thin veneer over the host protocol (`cell.graph`):

```kotlin
val spec = graph(host.managementInlet) {
    val a = spawn("writer") { SetCell<String>() }
    val u = spawn("union")  { UnionSetCell<String>() }
    a linkTo u                        // default ports; connect(a, "outlet", u, "inlet") for explicit
}
spec.applyTo(otherHost.managementInlet)   // replay: fresh cells, same topology
```

Every builder operation both applies immediately (`spawn`/`connect` on the
management inlet — link rejections fail construction loudly) and records into
a serializable `GraphSpec(steps)` — **graphs-as-data**, the substrate for the
generative harness (52) and the declarative/no-code path later. Spawn steps
carry a `CellFactory` (a `Serializable` fun interface; lambda captures must
be serializable), so replay mints fresh cells and refs. No new semantics in
the DSL layer, ever: anything the DSL does must be expressible as management
invocations. Operator combinators (`handle.map { }` — Task Definitions'
cold/hot operators) stay unbuilt until a caller needs more than
spawn-and-link.

## Code generation (direction fixed by ADR 3)

KSP (already wired: `gen` module, `SerializerProcessor`,
`GenerateSuspended`) will generate, per contract interface:

- serializable method-id tables + argument serializers (41);
- reflection-free proxies (14, C-5) for JVM and eventually KMP;
- port metadata (ownership flags 23, color 32, data/management marker G-11).

Legacy `GenerateSuspended` (blocking→suspending derivation) is superseded by
the color/adapter model (32) — fold or retire (G-1).

## The programming environment (vision, unbuilt)

Runtime-integrated IDE: scaffolding of cell types, declarative wiring for
no-code workflows, automatic dependency discovery from connected ports,
standard Git/Gradle/test integration. Depends on: topology introspection
(P3 — largely available), the DSL (G-30), and visualization tooling (the
process ADR's own named risk: "runtime graph complexity may grow without
strong visualization tools").
