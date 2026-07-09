# 13 — Links

> **Status**: Partial (ad-hoc linking implemented; handshake protocol specified but unbuilt)
> **Sources**: ADR — Task Connectivity, ADR — Computelet Kernel, ADR — Anatomy of Cellular Programs
> **Implementation**: `germ.port.LinkTo`/`LinkFrom`, `ManagedHost.connect`; legacy `kernel.link.Link`/`DefaultLink`

## Definition

A **Link** is a first-class, directional connection from an outlet to an
inlet. Links are the only way data moves between cells. The set of links *is*
the topology; it is inspectable and mutable at runtime (P3, P8).

## Two connectivity styles (normative)

Per the Task Connectivity ADR, both styles are supported, chosen per port:

### 1. Ad hoc (free-form)

Any party holding a reference to the port's external face may interact —
appropriate for open, shared-state cells (`Var<X>`-like, the data cells of
20/24). No handshake; cardinality is effectively unconstrained.
This is what germ implements today: `outlet.linkTo(inlet)` and
`producer.outlet.linkTo(consumerProxy.inlet)` always succeed.

### 2. Explicit (handshake-based)

A formal `link` operation that the target may **accept or reject**:

```kotlin
interface Link {
    val from: PortRef
    val to: PortRef
    fun unlink()
}

sealed interface LinkResult {
    data class Connected(val link: Link) : LinkResult
    data class Rejected(val reason: String) : LinkResult
}

// hooks on the serving port (membrane surface #1):
fun onLink(link: Link): LinkResult
fun onUnlink(link: Link)
```

Handshakes are where cardinality (12), ownership constraints (20/23),
policies, and setup/cleanup run. Rejection reasons include: at capacity,
schema/contract mismatch, policy denial, ownership violation.

⚠ GAP (G-12, continued): germ has no `Link` object, no handshake, no
`unlink()`. `ManagedHost.connect(from, "outlet", to, "inlet")` resolves ports
reflectively and calls `linkTo` unconditionally.
*Proposal — implement in this order*:
1. Introduce `Link` as a returned handle from `linkTo`, retained by both ports.
2. Add `onLink/onUnlink` hooks with default-accept, called synchronously
   within the owning host's queue (ordering safety).
3. Route `ManagedHost.connect` through the handshake and surface
   `LinkResult` to the caller.
4. Enforce cardinality and ownership rules inside `onLink`.

`unlink()` is load-bearing for the whole architecture: suspension and
migration (30/33) are defined as link manipulation, and links are where
stale-reference re-resolution happens (40/41).

## Policies

A **policy** is a predicate/effect pair attached to a port or membrane,
evaluated at link time (authorization, schema compatibility, quotas) and
optionally at flow time (rate limits — but note P2: flow-time policies must be
cheap or pushed to the boundary host).

Policies turn links into **negotiated relationships**. Examples: whitelisted
peers, encryption requirements (40/43), rate limits, resource quotas.

⚠ GAP (G-14): no policy representation exists. *Proposal*: start with
link-time-only policies as composable `(LinkRequest) -> LinkResult` functions
attached to ports; defer flow-time policies until the membrane layer exists.

## Cross-boundary links

A link between cells in different hosts is still *one logical link*; the
runtime realizes it as: local direct call → (bridge, 30/32) → remote enqueue →
local direct call (40/41). Location transparency requires that `link`,
`unlink`, `onLink`, `onUnlink` behave identically regardless of realization.

Cross-host links MUST also carry the **stale-reference fallback**: when a send
fails because the target host closed (suspension/migration), the link
re-resolves the target's current location and retries — possibly landing in a
persistent overflow queue (30/33, 40/41).

## Link lifecycle summary

```
proposed --onLink--> connected --unlink--> disconnected
             \-> rejected(reason)
suspended target: connected link buffers/fails-fast per policy, then
re-resolves (never silently drops)
```

Invariant (normative): **no message loss at link operations** — a message
accepted by a link before `unlink`/suspend MUST be delivered or explicitly
parked in a durable/inspectable place; ordering per link MUST be preserved
(see 30/33 for the drain protocol).
