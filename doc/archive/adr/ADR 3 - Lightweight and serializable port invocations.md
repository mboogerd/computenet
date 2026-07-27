# ADR 3: Lightweight and Serializable Port Invocations

## Status
Proposed

## Context
In the current implementation, port interactions (Inlets and Outlets) are mediated by the `Use.use` method, which takes a lambda: `use { provide(data) }`. While this provides a convenient and idiomatic Kotlin API, it has several drawbacks that hinder the framework's goals of location transparency and scalability:

1.  **Serialization Complexity**: Lambdas are notoriously difficult to serialize and transfer across JVM boundaries. They are sensitive to classloader differences, JVM versions, and captured scope.
2.  **Transaction Overlap**: The lambda-based `use` was partially intended to support "small transactions" on a cell. However, transactions are not the primary focus of this framework (incremental dataflow is). Building core communication on a transactional primitive adds unnecessary complexity.
3.  **Overhead of Complex Protocols**: When a port requires a complex protocol (multiple methods or parameters), developers often have to choose between manually encoding data structures for each method or using the "clever" but fragile lambda API.
4.  **Developer Experience**: Statically accessing methods (e.g., `inlet.provide(data)`) is more direct and discoverable than wrapping every call in a `use { ... }` block.
5.  **Runtime Savings**: Initial assumptions that passing lambdas would provide runtime savings (e.g., by avoiding manual data structure encoding) have not materialized in practice as significant benefits.

## Design Rationale
-   **Transparency**: Linking two ports should be transparent regardless of whether cells are on the same thread, different JVMs, or different machines.
-   **Contract-Based**: A contract is a set of push-only methods. Bi-directional communication is modeled as two sets of push-only methods, one for each direction.
-   **Flexibility**: We want the performance of direct method calls for local communication while allowing easy serialization of method names and arguments for remote communication.

## Decision
We will move away from lambda-based `Use.use` blocks as the primary mechanism for port communication. Instead, we will adopt a mechanism that captures method calls on port interfaces in a serializable format.

1.  **Static Method Access**: Port consumers will interact with ports through typed interfaces. Instead of `outlet.use { provide(x) }`, the preferred style will be `outlet.provide(x)` or `outlet.call.provide(x)`.
2.  **Serializable Invocations**: Method calls on these interfaces will be captured as `Invocation` objects (containing method identification and arguments) that are easily serializable. We will avoid using `java.lang.reflect.Method` in the serialized form, preferring method names or stable IDs.
3.  **Context-Aware Dispatch**:
    -   **Local Dispatch**: Within the same runner or thread, the framework will attempt to provide the actual implementation directly, allowing for zero-cost method dispatch.
    -   **Remote/Proxy Dispatch**: When crossing boundaries, a Proxy (either dynamic or generated via KSP/Poet) will capture the call and route it as a message.
4.  **Transaction Decoupling**: Transactions, if needed, will be built on top of these primitives rather than being baked into the core `use` interface.

## Consequences
-   **Improved Portability**: Communication between cells on different JVMs or machines becomes much simpler and more robust.
-   **Type Safety and Discoverability**: Developers get better IDE support and clearer code by calling methods directly on port-related interfaces.
-   **Code Generation**: To support Kotlin Multiplatform (KMP) and avoid reflection overhead, we will likely use KSP or Kotlin Poet to generate the necessary proxies and adapters for port interfaces.
-   **API Migration**: Existing code using `Use.use { ... }` will need to be migrated to the new style. The `use` method may be deprecated or retained for specific local-only or transactional scenarios.
