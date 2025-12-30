# ADR: Unified Task Model with Cold/Hot Phases

## Status

Proposed

## Context

We are designing a dataflow graph abstraction in which developers define Tasks, Inlets, and Outlets. A key design requirement is that tasks must support:

- **Statically-named, type-safe ports** (e.g., `zipTask.leftIn`)
- **Dynamic graph construction and connection**
- **Separation of declarative definition vs. running instance**, but with minimal boilerplate
- **Compositional operations like `map()`**
- **Support for both coroutine-based and virtual-thread-based runners**

This raises the question: should we separate task and port definitions from their hosted (running) counterparts?

## Alternatives Considered

### 1. **Separate TaskDefinition and TaskInstance class hierarchies**

- `ZipTaskDef` contains `InletDef` and `OutletDef`
- Instantiating produces a `ZipTask`, which exposes `Inlet` and `Outlet`

**Pros**:
- Clean separation of concerns
- Makes identity and lifecycle boundaries explicit
- Easier to introduce codegen or DSLs later for task definitions

**Cons**:
- Duplicates class structures and field declarations
- Requires error-prone glue code or codegen
- Adds complexity to early development and debugging

---

### 2. **Unified Task interface with Hosted State**

- Use a single class per task (e.g., `ZipTask<A,B>`)
- Fields like `val id: TaskId?` indicate whether the task is hosted
- Operations like `.map()` work differently depending on whether the task is in a cold (graph-builder) or hot (executed) phase

**Pros**:
- No duplicated classes or wiring logic
- Familiar model (similar to Kotlin's cold `Flow` vs hot `StateFlow`)
- Enables intuitive DSLs (`zipTask.map { ... }`)
- Simplifies static port access (`task.outlet`)

**Cons**:
- Task behavior depends on lifecycle phase, requiring careful state management
- Developer may accidentally call hosting-specific operations before the task is hosted
- May need to track/guard hosted state transitions internally

---

### 3. **Meta-driven instantiation via reflection or codegen**

- Tasks declare port fields (`val out by outlet<...>()`)
- Codegen generates instantiation glue and runtime binding

**Pros**:
- No manual duplication
- Can provide high-level, ergonomic APIs

**Cons**:
- Requires KSP or reflection, increasing build complexity
- Harder to debug during early prototyping
- Adds indirection that may not pay off early on

---

## Decision

We will proceed with a **Hybrid Unified Model with Explicit Lifecycle Phases**.

This allows us to:

- **Avoid duplication**: A single class acts as both the specification (Cold) and the logic provider (Hot).
- **Declarative definition**: Ports are declared using property delegates (`by input()`, `by output()`) for automated discovery by the Runner.
- **Managed Activation**: Logic is moved from the constructor to an `onActivate(CellContext)` hook, ensuring it only runs when hosted.
- **Late-Bound Wiring**: Runners manage connections between cells, allowing for dynamic reconfiguration and "Fast Path" optimizations.

This model leans into cold vs. hot semantics:
- **Cold Phase**: Cell instantiation (e.g., in a Graph DSL). Ports are dormant metadata.
- **Hot Phase**: Runner calls `onActivate()`. Ports are "hydrated" and logic is established.

---