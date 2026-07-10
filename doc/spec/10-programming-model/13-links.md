# 13 — Links

> **Status**: Partial (handshake phase 1 implemented: Link/LinkResult/unlink/policies; suspension lifecycle and async cross-host results open)
> **Sources**: ADR — Task Connectivity, ADR — Computelet Kernel, ADR — Anatomy of Cellular Programs
> **Implementation**: `cell.port.Link`/`LinkResult`/`LinkSupport`/`LinkPolicy`, `cell.port.LinkTo`/`LinkFrom`, `ManagedHost.connect`

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
Implemented as `linkTo(Use)`/`subscribe`/`serve`/`delegate` — the plain
mechanism layer beneath the handshake.

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
fun onLink(link: Link): LinkResult   // pre-install veto point
fun onUnlink(link: Link)
// post-install, fired on BOTH sides once the counterpart is reachable —
// the source-side seam for late-join catch-up (21, G-22):
fun onLinked(link: Link)
```

Handshakes are where cardinality (12), ownership constraints (20/23),
policies, and setup/cleanup run. Rejection reasons include: at capacity,
schema/contract mismatch, policy denial, ownership violation.

*(G-12 phase 1 implemented: `cell.port.Link`/`LinkResult`/`LinkSupport`.
`linkTo(LinkFrom)` runs the target-side handshake — policies → cardinality →
`onLink` — and returns `Connected(link)` / `Rejected(reason)`; the link is
retained by both ports (`port.linking.links`); `link.unlink()` is idempotent,
detaches both sides and fires the target's `onUnlink`. Cardinality violations
now return `Rejected` instead of throwing. `ManagedHost.connect` routes
through the handshake and surfaces the `LinkResult` to the caller.
Cross-host caveat: a proxy-initiated `linkTo` returns `LinkResult.Deferred` —
the authoritative handshake runs on the target's host, and a rejection there
is emitted to that host's `deadLetterOutlet`; a synchronous reply channel
needs the wire layer (M5). Delegation chains (`inlet.linkTo(use)`,
serve/delegate) remain plain mechanism — they are intra-cell composition, not
topology links. Ownership enforcement in `onLink` (G-21) waits for ownership
markers (M5).)*

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

*(G-14 phase 1 implemented: link-time policies as composable
`LinkPolicy { (LinkRequest) -> Rejected? }` attached to ports
(`port.linking.policies`), evaluated before `onLink`, first rejection wins.
`LinkRequest` carries the identity slot from day one — `Identity` is a bare
marker; verification is G-29. Flow-time policies wait for the membrane
layer. Failure **supervision** policies are deliberately not link policies:
they are host-management configuration per cell — see 30/31 rule 5.)*

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
