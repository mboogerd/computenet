# 51 — Constructing Cellular Programs

> **Status**: Partial (cell authoring exists; DSL/tooling exploratory)
> **Sources**: ADR — Cellular Software Development Process, ADR — Task Definitions, ADR 3 (codegen)
> **Implementation**: hand-written cells + host API; KSP seed (`gen`); no DSL, no scaffolding

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

## Graph construction DSL (⚠ GAP G-30)

The process ADR promises declarative graph construction and operator
composition (`cell.map { }` etc. — Task Definitions' cold/hot operators).
*Proposal*: a builder that stays a thin veneer over the host protocol —
`graph { val a = spawn(EchoCell()); val b = a.map { ... }; a.outlet linkTo b.inlet }`
— producing spawn/connect invocation sequences (which are serializable, hence
graphs-as-data, hence the declarative/no-code path later). No new semantics in
the DSL layer, ever: anything the DSL does must be expressible as management
invocations.

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
