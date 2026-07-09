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
   configuration, not a protocol fork.
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

## ⚠ GAP (G-29): threat model and identity

Undefined: peer identity (keys? DIDs?), authentication of links, integrity of
replicated deltas (signing?), Sybil resistance for interest signals (34 — an
attacker claiming attention could summon computation). None of this blocks
layers 10–30, but the policy substrate (G-14) should carry
identity-bearing link requests from day one:
`LinkRequest(peer: PeerId, credentials, contract)` even while `PeerId` is
just "local".

## Sequencing

Security work SHOULD trail the policy/membrane substrate, not precede it:
(1) handshakes G-12 → (2) policies G-14 → (3) identity on link requests →
(4) encrypted bridges → (5) sandboxed hosts → (6) replay recovery. Items 1–2
are already on the critical path for other reasons; security adds
requirements to their design (identity slot, deny-by-default option) rather
than new mechanisms.
