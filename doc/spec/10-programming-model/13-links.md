# 13 — Links

> **Status**: Partial (handshake phase 1 implemented: Link/LinkResult/unlink/policies; suspension lifecycle and async cross-host results open; rebind, admission/activation, cycle-check, edge-event, and saturation rules decided in 93, unimplemented)
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
schema/contract mismatch, policy denial, ownership violation. One rejection
is structural-topological (decided in
[93 I-5](../90-roadmap/93-feature-interactions.md)): a `connect` that would
close a cycle wholly visible to one host and containing no declared
`CycleHead` (20/22) MUST be `Rejected` with a `CycleWithoutHead` reason — a
rare-path walk of the new cycle (P2 permits expensive linking). Cross-host
cycles are not locally visible; they fall to the runtime hop guard (20/22).

**Admission vs activation** (decided in 93 I-26): "link time" decomposes.
*Admission* is structural and phase-independent — policy predicates, the
cardinality budget, the ownership exclusive bit, contract compatibility
`(portName, contractId)`, and protocol-capability negotiation read only
declaration-derived port metadata, so they run in any lifecycle phase and
are binding from construction (a cold cell admits). *Activation* is
behavioral: data traffic dispatches only into an installed handler (10/15);
a link admitted before activation is live topology with a parked tail —
inbound invocations park in order and replay at activation, before any
post-activation send lands. A *stateful* `onLink` (one that consults hot
state — a per-port declaration; the default is structural-only) on a
not-yet-hot cell MUST defer on the always-open management inlet (30/33) and
replay its handshake at activation, surfacing `Connected`/`Rejected` then —
the same `LinkResult.Deferred` contract already used cross-host (below).

⚠ GAP (G-55): the admission (structural, from construction) vs activation
(behavioral, handler-establishment) split needs its enforcement surface —
stateful-onLink classification, deferred-admission result surfacing
including cross-host, Eager verification, dropped-protocol observability,
and remote-spawn rejection channels. *Proposal*: a per-port structural-only
vs stateful `onLink` declaration with defined defer/replay/result-surfacing
of admission requests to not-yet-hot cells, composing with
`LinkResult.Deferred` and registry park/replay across the wire; a
KSP-checked Eager capability (handler in constructor, pure, allocation-free,
host-context-free) from which unhosted-linking permission derives; a
count/log policy for protocols dropped before handler install; and a typed
rejection surface for wrong-color or invalid remote spawns pinned against
G-26/G-12 (93 I-26/I-15).

*(G-12 phase 1 implemented: `cell.port.Link`/`LinkResult`/`LinkSupport`.
`linkTo(LinkFrom)` runs the target-side handshake — policies → cardinality →
`onLink` — and returns `Connected(link)` / `Rejected(reason)`; the link is
retained by both ports (`port.linking.links`); `link.unlink()` is idempotent,
detaches both sides and fires the target's `onUnlink`. Cardinality violations
now return `Rejected` instead of throwing. `ManagedHost.connect` routes
through the handshake and surfaces the `LinkResult` to the caller.
Cross-host caveat: a proxy-initiated `linkTo` returns `LinkResult.Deferred` —
the authoritative handshake runs on the target's host, and a rejection there
is emitted to that host's `deadLetterOutlet`. (M5.4 decision: this contract
holds across the wire too — a synchronous reply channel was deliberately not
built; cross-peer linking uses registry proxies + `Use.fixed`, and remote
`linkFrom` arguments are live objects that cannot cross the wire anyway.
Revisit only when a real topology needs it.) Delegation chains (`inlet.linkTo(use)`,
serve/delegate) remain plain mechanism — they are intra-cell composition, not
topology links. Ownership enforcement landed in M5.6 (G-21): exclusive
contracts reject a second link at the `FanOutlet.subscribe` funnel, on every
path — handshake, `Use.fixed`, bridged (20/23).)*

`unlink()` is load-bearing for the whole architecture: suspension and
migration (30/33) are defined as link manipulation, and links are where
stale-reference re-resolution happens (40/41).

**Rebind semantics** (decided in 93 I-2): any *new* full-ref link MUST run
the full target-side handshake — policies → cardinality → `onLink`.
Migration and RESTART preserve `instanceId` (the same instance moves or
recovers in place, 30/31, 30/33), so their links are *not* rebinds: direct
links travel with the cell, routed links re-resolve the same ref, and no
spurious handshake re-runs. A promotion relink (50/53) *is* a rebind — a new
`instanceId` — so the handshake DOES re-run against the candidate, including
the port-compatibility check: for each rebindable link the candidate MUST
present a port with matching `(portName, contractId)`, else the relink is
`Rejected`. No link ever transfers silently past its veto point.

For the promotion swap, admission is hoisted out of the commit (decided in
93 I-11): the candidate's link policies are dry-run against each inbound
`LinkRequest` in a side-effect-free Phase 0, before any traffic buffers, so
the commit-time relink runs `onLink` as *setup only* and MUST NOT newly
reject. Buffered traffic's home is the retained incumbent until the commit
fully succeeds — a failed commit re-greens onto the incumbent and replays
the buffer there, so no buffered message is ever orphaned (50/53).

⚠ GAP (G-49): the two-phase swap + state-transform design is by-convention
at its load-bearing spots — non-vetoing commit, contract-schema identity
across builds, source continuity under representation change, fallback
soundness, hidden-state cells, coupled-flow windows, and
rollback-after-retire. *Proposal*: KSP-distinguish admission policies
(Phase 0) from setup-only commit hooks; a contract-version discipline
guarding `importFrom` schemaVersion against same-FQN hash collisions; pin
sourceId adoption vs fresh-source reset when a candidate changes delta
representation (drain-convergence fallback otherwise); a fallback-tier
soundness marker refusing catch-up for non-idempotent cells; an explicit
non-promotable declaration for hidden-state cells; a retention window for
the retired incumbent's export snapshot with rollback-by-journal-reversal
semantics pinned against 53/24; and a transform-correctness generative
harness (93 I-11/I-27/I-21).

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

G-54 core is landed (W4.1, `civictech.cell.membrane.BoundaryPolicy`):
`linkAuthority` composes onto the existing `port.linking.policies` slot
above (seam 2) for organelle-inlet exposures, and `disclosure`/`integrity`
run at flow-time (40/43). Residual, still open: capability hand-out/
revocation for exposed ports and taps (tearing down *live* links, not just
refusing new ones); management-plane authority for remote graph mutation
across a bridge; composition of disclosure/integrity across nested/
transitive membranes and multi-hop relays; and an at-rest encryption stance
for durable journals and parked/overflow state (93 I-28 §8).

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

**Edge events** (decided in 93 I-13): a successful `onLink`/`onUnlink` on a
topology-interested edge MUST emit an in-band `EdgeOpen`/`EdgeClose` marker
riding the link's own per-link FIFO channel — `EdgeOpen` ahead of any data,
`EdgeClose` after the final data — so topology changes sit in the same
logical-time domain as the waves they affect (20/22 §Topology versioning).
Emission is gated on a downstream having expressed topology-order interest;
outlets without glitch-free subscribers pay nothing.

Invariant (normative): **no message loss at link operations** — a message
accepted by a link before `unlink`/suspend MUST be delivered or explicitly
parked in a durable/inspectable place; ordering per link MUST be preserved
(see 30/33 for the drain protocol). Under intake saturation (decided in
93 I-12) the invariant extends to name the SATURATED state: a message
accepted by a link MUST be delivered, coalesced (mergeable deltas fold into
a bounded per-source pending slot), or parked in a bounded inspectable
place — with a visible dead-letter on park overflow, never silent loss.

⚠ GAP (G-34): intakes are unbounded — no saturation signal, no admission
gate; the ADR-2 color bridges are degenerate and every parking bound
(pre-activation park, router funnel, park-at-sender) is unenforceable.
*Proposal*: a three-state OPEN/SATURATED/CLOSED intake flag on the existing
closure fast-path read; saturated sends dispatch by payload class
(mergeable deltas coalesce into bounded per-source pending slots,
exclusive/non-mergeable park in-order at the sender, management band
exempt); `SaturationSignal` rides the metadata plane upstream with a
terminal park-overflow policy (visible dead-letter default, Block(timeout)
opt-in only, with Block × glitch-free-wave semantics pinned), realized at
the `enqueueHostedInvocation` seam keyed by (senderHostColor,
targetHostColor), including the cross-wire saturation frame vs transport
flow control (93 I-12/I-15/I-9/I-19/I-26).
