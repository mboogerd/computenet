# 43 — Security, Privacy, and Trust

> **Status**: Exploratory (posture fixed; mechanisms undesigned)
> **Sources**: ADR 0 (§6), ADR 1 (§13), ADR — Anatomy of Cellular Programs (membranes as authority), ADR — Cellular Software Development Process (security model)
> **Implementation**: none

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

⚠ Still undefined: *authentication* of the claimed name (keys? DIDs? —
today the transport connection vouches for it), integrity of replicated
deltas (signing), Sybil resistance for interest signals (34 — an attacker
claiming attention could summon computation), encryption at rest. Encryption
in transit is transport configuration (wss://).

## Sequencing

Security work SHOULD trail the policy/membrane substrate, not precede it:
(1) handshakes G-12 → (2) policies G-14 → (3) identity on link requests →
(4) encrypted bridges → (5) sandboxed hosts → (6) replay recovery. Items 1–2
are already on the critical path for other reasons; security adds
requirements to their design (identity slot, deny-by-default option) rather
than new mechanisms.
