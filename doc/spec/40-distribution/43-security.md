# 43 — Security, Privacy, and Trust

> **Status**: Partial (posture fixed; boundary-policy design decided in [93 I-28](../90-roadmap/93-feature-interactions.md) and landed for in-process membranes (W4.1); authentication strength and at-rest encryption open)
> **Sources**: ADR 0 (§6), ADR 1 (§13), ADR — Anatomy of Cellular Programs (membranes as authority), ADR — Cellular Software Development Process (security model)
> **Implementation**: G-29 phase 1 — `PeerId` stamping (`LinkRequest.identity`), `allowPeers(...)` link policy, ingress admission gate (`Peering.Side.allow` / `WsTransport`); verified by `TrustBoundaryTest`. W4.1 (G-54 core): `civictech.cell.membrane.BoundaryPolicy` (`Principal`/`AuthLevel`, the five predicates) bound to a `CompositeCell` `Exposure`; the three seams — `mediate()`'s `linkAuthority` (seam 2), `mediateOutlet()`'s `disclosureFilter`/`ProtocolSupport.inboundFilter` (seam 3 outbound/protocol), and `MediateProxy`'s `RequireSigned` verify-at-ingress (seam 3 inbound) — with `AuthLevel.TransportVouched` the only identity strength available (phase-2 keys/DIDs remain research, 95 §R7); verified by `BoundaryPolicyTest`/`MediateProxyIntegrityTest`. The wire-crossing bridge does not yet consult a `BoundaryPolicy` (still G-29 phase 1's `allowPeers`); disclosure-projection composition across hops, capability revocation, cross-membrane management authority, and encryption at rest remain open (93 I-28 §8).

## Posture

Open and local-first **by default** (P7); security is a set of **boundary
controls** — applied at membranes and links — not an ambient property of the
core. The decentralized default runtime must eventually let mutually
untrusting contributors participate safely.

## The five mechanisms (from the ADRs)

1. **Authority at boundaries** (11): permissions, capability checks, and
   link authorization belong to membranes/policies, never to business logic.
   Concretely: policies (13, G-14) evaluated in `onLink`, and membrane-level
   cross-port rules once membranes exist (G-10).
2. **Peer allowlists + encryption requirements** are per-cell/per-port policy
   declarations (ADR 1 §13): whitelisted peers; encryption in transit and at
   rest for flagged flows. Enforcement point: the network bridge cells (41) —
   because network crossings are ordinary cells, encryption is a bridge
   configuration, not a protocol fork. *(M5.3: the enforcement point now
   exists — `cell.wire.BridgeEgressCell`/`BridgeIngressCell` are ordinary
   cells with ordinary links; nothing here is implemented yet, but it has a
   place to live.)*
3. **Serialization boundaries**: all inter-cell communication is structured
   `Invocation` data (P9) — no shared memory, no lambda/code injection through
   ports by construction. This is already true in-process and must remain
   true on the wire (41).
4. **Isolation for untrusted code**: untrusted cells run in constrained hosts
   (WASI / containers / restricted JVMs). The host hierarchy (31, G-28) is the
   natural sandbox unit: an untrusted host gets resource quotas and a
   restricted management surface. Privileged cells require explicit user
   approval to link.
5. **Recovery by replay**: because state transitions are journaled
   serializable invocations (24 durability), a compromised graph can be
   restored by replaying the log **minus the malicious cells' inputs**.
   *(M10: the journal + replay machinery now exists — `Journal`,
   `recoverFrom`; the selective minus-malicious-inputs filter remains
   future security work.)*

## G-29: threat model and identity (phase 1 landed, M8.2–M8.3)

Landed: `PeerId` as transport identity — the WebSocket hello carries the peer
name, the bridge ingress stamps every delivery, and handshakes running during
a bridged delivery see it on `LinkRequest.identity` (`CurrentPeer`); local
links carry null. Deny-by-default is a boundary control in both layers:
`allowPeers(...)` as a link policy, and the ingress admission gate
(`Peering.Side.allow` / `WsTransport` refusing unlisted peers at hello time)
— refusals surface as ordinary dead letters. Verified: `TrustBoundaryTest`
(100 seeds + open-mode control).

⚠ Still undefined: authentication *strength* — the phase-2 key/DID +
signed-nonce upgrade that promotes a peer past `TransportVouched` — and
encryption at rest. The rest of the former open list is design-decided below
(93 I-28): integrity of replicated deltas is `RequireSigned` verification at
ingress, and Sybil resistance for interest signals is structural (attention
clamping + per-`Principal` quotas). Encryption in transit is transport
configuration (wss://).

## `BoundaryPolicy`: three seams, one per dispatch class (decided in 93 I-28, unimplemented)

The "missing" security features — per-protocol authority, disclosure,
attention attenuation, delta integrity — are one vocabulary of identity-keyed
predicates evaluated at seams the membrane already owns: the Mediate
exposure proxy (93 I-10's membrane exposure modes) and the G-29 ingress
gate, now consulting policy keyed on the `PeerId` ingress already stamps.
Nothing new lives at the boundary.

**Identity.** Every crossing MUST carry a `Principal`: `LocalTrusted` for
in-host/same-registry crossings (today's null identity), or `Peer(id, auth)`
for bridge crossings — `id` the stamped `PeerId`, `auth` an `AuthLevel` of
`TransportVouched` (phase 1, landed: the transport connection vouches for
the name) or `Authenticated` (phase 2: `PeerId` carries or resolves to a
public key / DID and the hello adds a signed-nonce challenge). Predicates
read `Principal`; only the strength that `AuthLevel` certifies changes
across the upgrade — no CA, no global identity registry (P4, P10).

**Vocabulary.** A `BoundaryPolicy` attaches to a membrane `Exposure` (93
I-10) and holds five predicates, each defaulting to today's open behavior (P7/P6): `admission` (a
`PeerPredicate`, default allow-all — `allowPeers(...)` is the landed
instance), `linkAuthority` (a list of G-14 `LinkPolicy`, default empty),
`protocolAuthority` (per `ProtocolId`: a `minAuth` floor, an attention
`ceiling` band, a per-`Principal` `ratePerWindow`), `disclosure` (`Full` |
`Project(ProjectionId)` | `Deny`), and `integrity` (`None` |
`RequireSigned`). A `ProjectionId` names a registered pure `Delta → Delta`
redaction/scoping transform — never a lambda on the wire (P9).

**The three seams.** Enforcement is native to the crossing's dispatch class;
there is no fourth subsystem:

1. **Admission** (`PORT_MANAGEMENT` peering): the transport hello / bridge
   ingress MUST evaluate `admission`; a failing `Principal` is refused at
   hello time and its traffic dead-lettered — the landed G-29 gate.
2. **Link-time** (`PORT_MANAGEMENT` link): every new full-ref link runs
   `linkAuthority` first-rejection-wins with `CurrentPeer` = the crossing's
   `Principal` (landed). Privileged ports SHOULD deny by default;
   migration/RESTART preserve `instanceId` and are not rebinds; promotion
   *is* a rebind and MUST re-authorize.
3. **Flow-time** (the Mediate proxy): declaring any of `protocolAuthority`,
   `disclosure`, or `integrity` forces the exposure to Mediate, and that
   proxy is the sole flow-time enforcement point. `PORT_PROTOCOL` frames
   check `protocolAuthority[protocolId]` — below `minAuth` or over
   `ratePerWindow` → dead letter; an asserted attention level is clamped,
   `slot.level = min(asserted, ceiling)`, leaving the fold and band-gating
   untouched. `PORT_API` outbound passes `disclosure`: one filter covers
   both the `onLinked` catch-up and the live stream (a snapshot IS a delta);
   `Deny` suppresses catch-up entirely, for attention- or management-only
   peerings. `PORT_API` inbound passes `integrity`: `RequireSigned` verifies
   a signature over (contractId, methodId, payload, minting `PeerId`,
   per-source counter) before `deltaInlet` delivery — failure dead-letters,
   and because Replicable merges are idempotent, drop-and-reconverge is the
   recovery: no ack, no version vector, no second sync protocol. The counter
   defeats replay; signing is per-emitting-peer, never per-logical-cell.

⚠ GAP (G-50): promotion is mechanically complete but has no declarative
policy or authority story — judge criteria, observation windows, differential
no-worse-than comparison, who may register or trigger a swap, canary staging,
and multi-partition rollout orchestration are open. Proposal: a
PromotionPolicy as a serializable artifact beside the candidate GraphSpec
(ObservationWindow, SatisfactionCriterion grammar, differential comparison
over partial violation orders) so a promotion is fully described by spec +
policy; registration/trigger authority gated by the membrane/policy layer
under federated governance; a small-blast-radius canary staged-promotion path
for unshadowable closed-loop candidates; and an ordering/monitoring/abort
policy for partitioned rolling promotion (93 I-21/I-17/I-27).

**Attention is a request, not an entitlement.** A remote attention assertion
is subject to `protocolAuthority[ATTENTION]`: clamped to `ceiling`,
rate-limited per `Principal`, refused below `minAuth`. Sybil resistance is
structural — an unauthenticated flood clamps to the floor, and authenticated
principals are bounded by per-`Principal` quotas that reuse the G-28
host-hierarchy quota walk (a remote-driven resource claim is charged to the
claiming `Principal`'s budget). No global attention coordinator (P4).

⚠ GAP (G-62): every interest-driven policy defers to an economic layer that
does not exist — replica spawn-vs-subscribe, migration-toward-attention,
partition-host split/merge, resharding triggers, and per-Principal attention
budgets (the Sybil economics). Proposal: an attention/quota-driven economic
layer (the G-6 residual, on the G-28 quota walk) deciding when to spawn a
local replica vs subscribe remotely and where replicas live, migration
candidacy under persistent high attention with remote hotspots, partition
split/merge and bulk-rebalance triggering by load/size/attention, and
per-Principal resource budgets bounding authenticated interest claims with a
concrete cost to mint an identity (93 I-3/I-9/I-19/I-8/I-28).

**Phasing.** Absent a `BoundaryPolicy` every predicate is its default and
the exposure Flattens — today's behavior, byte-for-byte; security cost
exists only where a boundary declares it, and only on the bridge crossing
(P2). Authentication strength is phased behind the stable vocabulary: today
all bridge peers are `TransportVouched` and default `minAuth` admits them;
phase-2 self-sovereign keys promote peers to `Authenticated` and unlock the
predicates (`integrity`, high-`minAuth` protocol authority) that
transport-vouched identity cannot safely satisfy. Encryption in transit
stays transport configuration (wss://); encryption at rest remains open.

G-54 core is landed (W4.1): the `BoundaryPolicy` vocabulary (admission,
linkAuthority, protocolAuthority, disclosure, integrity) attached to a
`CompositeCell` `Exposure`, evaluated at the three seams, with a registered
`ProjectionId → Projection` transform for `disclosure` and `RequireSigned`
verify-at-ingress for `integrity` (`AuthLevel.TransportVouched` only — real
key/DID strength stays research, 95 §R7). Residual, still open: capability
hand-out/revocation for exposed ports and taps (tearing down *live* links,
not just refusing new ones); management-plane authority for remote graph
mutation across a bridge (who may drive `PORT_MANAGEMENT`); composition of
`disclosure`/`integrity` across nested/transitive membranes and multi-hop
relays; and an at-rest encryption stance for durable journals and
parked/overflow state (93 I-28 §8).

## Sequencing

Security work SHOULD trail the policy/membrane substrate, not precede it:
(1) handshakes G-12 → (2) policies G-14 → (3) identity on link requests →
(4) encrypted bridges → (5) sandboxed hosts → (6) replay recovery. Items 1–2
are already on the critical path for other reasons; security adds
requirements to their design (identity slot, deny-by-default option) rather
than new mechanisms.
