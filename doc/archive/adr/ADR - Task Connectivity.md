# ADR: Task Connectivity - Design and Implementation

## Status
Proposed

## Context
In our collaborative dataflow graph, Computelets (Tasks) interact via Ports (Inlets and Outlets). The connectivity model must be flexible enough to support various usage patterns, from simple variable-like access to complex, high-performance, and consistency-aware pipelines.

We face four primary challenges:
1. **Ad Hoc vs. Explicit Linking**: Supporting both "open" computelets (like `Var<X>`) and "closed" computelets with strict linking constraints (cardinality, state cleanup, link rejection).
2. **Context-Awareness**: Propagating necessary metadata (Origin Port ID, Timestamp) for causal consistency and glitch-freedom without cluttering the developer's API.
3. **Runtime Optimization**: Minimizing indirections when computelets merely route data (the delegation model), while maintaining the ability to change routing at runtime.
4. **Location Transparency**: Ensuring location transparency when connecting computelets across different execution runners (Pure, Blocking, Suspending), or using them in different contexts.

## Design Considerations

### 1. Linking Lifecycle & Cardinality
We distinguish between two main styles of connectivity:
- **Ad Hoc (Free-form)**: Any component with a reference to the port can send messages. This is ideal for shared state computelets (e.g., `Var<X>`).
- **Explicit (Handshake-based)**: Requires a formal `link` operation. This allows:
    - **Cardinality Enforcement**: Static or runtime checks for SPSC (Single Producer Single Consumer) vs. Multi-link.
    - **Handshake Logic**: The computelet can perform setup/cleanup or even reject a link attempt (e.g., if it's already at capacity).

### 2. Contextual Inlets
To support certain more advanced features, interactions may require context.
- **Implicit Context**: The framework should ideally automatically capture the context (for example: current timestamp and port), if that is required downstream.
- **Context Injection**: When a port is "used", it should provide an API that is already bound to the relevant context, or accept context as a parameter:
  ```kotlin
  outlet.use(context) { 
      updateValue(newValue) 
  }
  ```
- **Transparent Propagation**: If a computelet calls an outlet in response to an inlet message, the context should automatically flow from inlet to outlet.

### 3. Optimization: Delegation & Invalidation
To reduce latency in complex graphs (e.g., `IfElse` routing or deep nesting), we use a delegation model:
- **Delegation**: A `Serve` endpoint can delegate its implementation to a downstream `Use` endpoint.
- **Invalidation**: If a computelet changes its behavior (e.g., switching branches in an `IfElse` computelet), it invalidates its upstream trackers in $O(1)$.
- **Lazy Re-resolution**: Upstream components re-resolve the implementation only when they next "use" the port, ensuring the cost of change is proportional to the number of re-definitions, not the number of port triggers or behavior changes.

Note that there is an interplay between this optimization and context: Even if a computelet doesn't change behavior, the context will have changed (at least the port is different), so it's not a real no-op.

### 4. Location Transparency
The connectivity layer must hide the complexity of crossing runner boundaries:
- **Local Dispatch**: Within the same runner, ports should resolve to direct method calls.
  - From within a computelet, an output might be broadcasted on, or unicasted with a particular downstream port reference.
  - From within a computelet, an input might be agnostic about what upstream port triggered it, or it may have specific logic that depends on the upstream port.
- **Remote Dispatch**: Across runners, ports should resolve to a proxy that enqueues a `Message` into the target runner's mailbox (potentially indirectly via a priority scheduler)
- **Async Bridge**: When specific protocol translation is needed (e.g., converting a blocking call to a suspending one), the framework may insert transparent "Bridge" computelets.
- **Task Suspension**: When a task or runner is suspended, its mailbox becomes unavailable. When an upstream task has not been made aware, it will nonetheless attempt to send the message. The ComputeNet should ensure that the link that's provided cross-runner can fall back to retrieving an updated mailbox reference when the initial dispatch fails (which might be a mailbox that just persists these messages to a disk queue)

All in all, we are looking for abstractions that are as simple and uniform as possible, while covering all the above requirements.

## Decision
(See the [Appendix](#appendix-core-connectivity-interfaces) for the proposed interface definitions.)

1. We will adopt the **Link and Lease Port Model** (`Serve`, `Use`, `Invalidating`) as the foundation for optimizing local call chains.
2. We will implement **Explicit Link Handshakes** to support stateful connectivity and cardinality constraints.
3. We will standardize a **MessageContext** containing at least `Timestamp` and `SourcePortRef`.
4. We will use **Dynamic Proxies** or generated adapters to provide location transparency across runner boundaries.

## Consequences
- **Complexity**: The Link and Lease model introduces internal complexity (invalidation logic) to achieve high performance in routing scenarios.
- **Type Safety**: Using dynamic proxies for cross-runner communication requires robust handling of return types (e.g., wrapping in `CompletableFuture` or `Deferred` if the call is request-response).
- **Predictability**: Handshake-based linking makes graph topology more predictable and easier to validate before execution.

## Appendix: Core Connectivity Interfaces

The following Kotlin-style interfaces define the core connectivity model, satisfying requirements for performance (via the Link and Lease model), causal consistency (via context propagation), and explicit topology management.

### 1. The Link and Lease Model (Optimization & Invalidation)

The Link and Lease model minimizes indirections by allowing ports to delegate to each other and using lazy re-resolution via $O(1)$ invalidation.

```kotlin
/**
 * A marker for components that can be invalidated. Invalidation triggers
 * a lazy re-resolution of the implementation on the next 'use'.
 */
fun interface Invalidating {
    /**
     * Notifies this component that its cached implementation is no longer valid.
     */
    fun invalidate()
}

/**
 * The consumer-side of a port (Outlet).
 */
interface Use<Api> {
    /**
     * "Leases" an [Api] instance bound to the given [context] for the duration 
     * of the [block].
     * 
     * Returning the Api instance via a block (rather than directly) discourages 
     * callers from storing stale references, as behavior along the path may 
     * change after each call.
     * 
     * For remote calls, the [block] is executed against a proxy that handles 
     * transparent retries by re-resolving through this [Use] instance if the 
     * remote runner is found to be closed.
     */
    fun <R> use(context: MessageContext, block: Api.() -> R): R

    /**
     * Subscribes [observer] to invalidation events from the upstream provider.
     * When the implementation served by this [Use] changes, the [observer] is invalidated.
     */
    fun attach(observer: Invalidating)
    fun detach(observer: Invalidating)
}

/**
 * The provider-side of a port (Inlet).
 */
interface Serve<Api> : Invalidating {
    /**
     * Defines the implementation to be served for a given context.
     * The [provider] can use the [MessageContext] to return specialized 
     * implementations (e.g., for buffering in glitch-free tasks).
     */
    fun serve(provider: (MessageContext) -> Api)

    /**
     * Efficiently delegates all requests to another [Use] endpoint, 
     * bypassing this computelet's logic.
     */
    fun delegate(upstream: Use<Api>)
    
    /**
     * Hook called when a formal link is established. Allows the computelet 
     * to perform setup, state initialization, or reject the link.
     */
    fun onLink(link: Link): LinkResult = LinkResult.Connected(link)

    /**
     * Hook called when a formal link is removed.
     */
    fun onUnlink(link: Link) {}
}
```

### 2. Message Context (Causal Consistency)

`MessageContext` carries the necessary metadata for glitch-freedom and causal consistency across task boundaries.

```kotlin
data class MessageContext(
    val timestamp: Timestamp, // Logical time / propagation wave ID
    val sourcePort: PortRef   // Identity of the calling port
)

/**
 * Marker for protocol identification.
 */
@JvmInline
value class ProtocolId(val id: Int)

/**
 * Logical time or propagation wave ID.
 */
@JvmInline
value class Timestamp(val time: Long)
```

### 3. Ports and Linking (Explicit Topology)

These interfaces define how computelets are composed and how connections are formally established and managed.

```kotlin
enum class PortDirection { IN, OUT, BIDIRECTIONAL }
enum class PortCardinality { SINGLE, MULTIPLE }

interface Port {
    val ref: PortRef
    val direction: PortDirection
    val cardinality: PortCardinality
}

/** An Inlet receives messages (serves an API). */
interface Inlet<Api> : Port, Serve<Api>

/** An Outlet sends messages (uses an API). */
interface Outlet<Api> : Port, Use<Api>

/** A Bidirectional port acts as both an Inlet and an Outlet. */
interface BiPort<InApi, OutApi> : Inlet<InApi>, Outlet<OutApi>

/**
 * A port that can serve multiple different protocols.
 */
interface MultiplexPort : Port {
    fun <Api> asInlet(protocolId: ProtocolId): Inlet<Api>
    fun <Api> asOutlet(protocolId: ProtocolId): Outlet<Api>
}

/**
 * A formal connection between two ports.
 */
interface Link {
    val from: PortRef
    val to: PortRef
    
    /**
     * Tears down the connection and performs any required cleanup.
     */
    fun unlink()
}

interface Linker {
    /**
     * Establishes a formal link between an Outlet and an Inlet.
     * This is where cardinality is enforced and handshake logic is executed.
     */
    fun <Api> link(outlet: Outlet<Api>, inlet: Inlet<Api>): LinkResult
}

sealed interface LinkResult {
    data class Connected(val link: Link) : LinkResult
    data class Rejected(val reason: String) : LinkResult
}
```
