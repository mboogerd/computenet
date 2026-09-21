# Distribution lane findings

**Status**: Living

Findings from the distribution lane (spec `doc/spec/40-distribution/`), one
entry per milestone or investigation, oldest first. New entries are **appended
at the end**; nothing above the insertion point is edited, reordered, or
deleted.

Append-only is the whole point of the file, not a filing convention. A findings
file whose past entries can be revised is a file in which an inconvenient
result — a claim that turned out weaker than it read, a gap that turned out to
be open — can quietly stop existing, and then no later reader can tell whether
a position was reached or chosen. If later work contradicts an earlier entry,
**append the contradiction** — say which entry it contradicts and what changed
— rather than correcting the earlier entry.

**A corrected entry is never marked as corrected — the correction is below it.**
That is the direct consequence of the rule above, and it is the one way this
file can mislead a reader who does not read it end to end: an entry later found
wrong still reads exactly as it was published, with no marker, no strikethrough
and no link forward, and the only record of the correction is a later entry that
names it. **Before citing any entry, scan the `##` headings that follow it for
one that names it.**

The discipline above is inherited from `doc/bench/findings.md`, which states it
first and at length. Two differences are deliberate and should not be read as
oversights:

- **There is no renderer.** `:bench` entries are emitted by
  `civictech.bench.Findings.entry`, which refuses an entry that is incomplete or
  too dispersed to report. This lane's findings are prose positions and gap
  accounting, not measurements, so nothing mechanical checks an entry here. The
  honesty burden sits entirely on the author.
- **What an entry owes is a *position*, not a number.** A distribution entry
  says what landed, what is explicitly not verified, and which gaps the work
  left open or newly surfaced — each with the item id that carries it forward.

Standing gaps this lane's entries speak to
(`doc/spec/90-roadmap/91-gap-analysis.md`):

- **G-29** — threat model and identity. Phase 1 (transport-vouched `PeerId`
  stamping, `allowPeers`, the ingress admission gate) landed at M8.2–M8.3; the
  authentication-*strength* half is what the DSC1 entry below addresses.
- **G-62** — the economic layer every interest-driven policy defers to,
  including per-`Principal` budgets and a concrete cost to mint an identity.
  DSC1 does not touch it, and says so below rather than letting "identity is now
  cryptographic" be read as Sybil resistance.

The `**Status**` line above uses the vocabulary
`concord/src/main/kotlin/civictech/concord/lint/DocLints.kt` enforces for
`doc/spec`. `docLints` scans only `doc/spec`, so for this file the line is a
courtesy — spelled the enforced way so that a later widening of that scan finds
it already compliant.

---

## 2026-08-21 — DSC1: cryptographic peer identity and signed announcements (epic `computenet-ssa`)

The G-29 crypto half, ingress-side. Before this epic `AuthLevel.Authenticated`
was a declared-but-unreachable enum constant, a peer's identity was a string it
asserted about itself in the `HELLO` line, and anyone who could open a socket to
a listener could claim any allowlisted name and inherit every authority keyed on
it.

### What landed

- **`:identity` (`computenet-ssa.1`, PR #305)** — a new JDK-only leaf module:
  Ed25519 keypairs from the JDK provider (JEP 339, no third-party crypto), a
  fail-closed file-backed key store whose refusals are machine-distinguishable
  (world-readable, malformed — naming the path and the defect, minting no
  replacement), key-derived `PeerId` fingerprints, and the canonical
  announcement encoding. `:identity -> :kernel`; `:kernel` must not depend on it.
- **Canonical-encoding injectivity (`computenet-9qgg`, PR #353)** —
  `canonicalBytes` *rejects* ill-formed UTF-16 (throwing, naming the field and
  the index) rather than encoding it. That decision is right, and it created an
  ingress failure mode carried below as a surfaced gap.
- **Authenticated hello in `:wire` (`computenet-ssa.2`, PR #354)** — an explicit
  `HELLO2`/`PROOF` grammar replacing the `split(" ", limit = 2)` parse that
  silently absorbed any extra token into the peer name, a nonce
  challenge/response, verification *before* admission, the allowlist evaluated
  on the derived id, and `PeerAuthPolicy` on `Peering.Side`.
- **`Authenticated` reaches the principal (`computenet-ssa.3`, landed through
  PR #357)** — `AuthLevel`'s ordering made explicit with every comparison site
  audited (`.3.1`), and the crossing's achieved `AuthLevel` carried from the
  admitted connection through `BridgeIngressCell` to `currentPrincipal()`
  (`.3.2`), so a delivery over an authenticated socket reads
  `Principal.Peer(derivedId, Authenticated)`.
- **Signed announcements end-to-end (`computenet-ssa.4`, PR #375)** — additive
  `WireFrame` signing fields with `VERSION` unchanged, emit-side signing with a
  monotonic counter and an expiry, and one ingress verification gate with a
  closed set of typed refusals: `UNSIGNED`, `BAD_SIGNATURE`, `REPLAY`,
  `EXPIRED`, `ID_MISMATCH`, plus `AUTH_REQUIRED` and `MALFORMED_HELLO` on the
  hello seam.
- **Restart re-convergence (`computenet-ssa.6`, PR #386)** — the announcement
  counter is seeded from an injected incarnation, so a signing process that
  restarts re-minting the same identity re-converges instead of having its whole
  catch-up burst classified `REPLAY`. It fails closed, and its residual is
  carried below.
- **Inspector `DenialReason` union (`computenet-ssa.7`, PR #385)** — the UI's
  union synced with the kernel enum, and pinned.
- **The closing sweep (`computenet-ssa.5`)** — the epic's BS-01..BS-18 scenario
  matrix verified as named tests in their designated modules, the BS-03
  default-open regression proof added (`.5.1`), the scenario-to-test-to-module
  mapping recorded on `computenet-ssa.5`'s comment thread (`.5.2`), and this
  entry (`.5.3`). That comment is the test inventory; this file is not.

Default-open behaviour is unchanged: a `Peering.Side` constructed without an
identity policy — as every existing demo does — still admits at
`TransportVouched`, puts no signing fields on encoded frames, and adds no dead
letters (`[DSC1-WIRE-06]`, BS-03).

### Explicitly unverified — stated, not softened

The epic recorded three claims up front (§4.6) that DSC1 cannot check honestly.
They are repeated here in as many words, because this file, not the epic body,
is what a later reader of the distribution lane reaches for. None of them is
partially verified, and no test in the suite covers any of them; a test that
appeared to would be a defect, not coverage.

- **[DSC1-NV-01] Stolen-key resistance is EXPLICITLY UNVERIFIED.** DSC1 cannot
  demonstrate resistance to a peer whose private key is stolen. A thief holding
  the key *is* the peer, by construction: every signature verifies, every
  derived id matches, and no seam in the system can tell the two apart. Key
  compromise is detectable only through rotation/revocation, which this epic
  does not build (§7). No test shall be written that pretends otherwise.
- **[DSC1-NV-02] Sybil resistance is EXPLICITLY UNVERIFIED — and is not
  claimed.** Minting a fresh keypair remains free. What DSC1 changed is that
  identity became *countable and attributable*, not *costly*. Bounding identity
  creation needs per-`Principal` budgets and a concrete minting cost; that is
  the Sybil half of **G-62** and belongs to ECO1. Any requirement claiming Sybil
  resistance here would be vacuous and must not be written.
- **[DSC1-NV-03] Clock-skew adequacy is EXPLICITLY UNVERIFIED.** Expiry checking
  (`[DSC1-ANN-09]`) assumes loosely synchronised clocks. The skew allowance is a
  configured constant — `DEFAULT_ANNOUNCEMENT_SKEW_MILLIS = 30_000L` in
  `kernel/src/main/kotlin/civictech/cell/wire/AnnouncementAdmission.kt` — and
  its adequacy is an operational assumption, not a tested property. DSC1 builds
  no skew *detection*: a receiver whose own clock runs slow refuses a live
  announcement with exactly the refusal a genuinely stale one gets, which is why
  the `EXPIRED` record names the clock that refused. What the tests do check is
  that expiry is evaluated against an *injected* clock (BS-07) — that is
  determinism of the mechanism, not adequacy of the constant.

`computenet-ssa.6`'s incarnation seeding leans on the same clock assumption from
a second direction: its default incarnation source is
`System::currentTimeMillis`, so the ordering of a signer's incarnations is the
ordering of its own wall clock across its own restarts. See the surfaced gaps
below.

### Key rotation: a documented position, not a mechanism

Per the epic (§9.2, risk 2), DSC1 ships a *position* on key rotation and files a
follow-up; it does not build rotation or revocation, and §7 forbids building it
here.

**The position: identity IS the key.** A `PeerId` is the fingerprint of a public
key, which is what makes it unspoofable and is the whole point of the epic. The
direct consequence is that **rotating a key renames the peer.** Everything keyed
on the old name goes stale at once:

- allowlists (`Peering.Side.allow`, `allowPeers(...)`) name an id that no longer
  authenticates;
- mirrored `Remote` locations are attributed to an id that will never announce
  again;
- durable attribution — the per-`Principal` statements AGO2 records, which are
  replicated and user-visible — points at an identity with no live holder, and
  no mechanism lets a reader learn that the two ids are the same speaker.

The option space, named without choosing (choosing is the follow-up's work):

1. **A stable identity key signing rotating session keys.** The long-lived key
   never moves, so names stay stable; the cost is a second key tier, a
   delegation format on the wire, and the unanswered question of what happens
   when the *identity* key itself is compromised.
2. **An explicit rotation announcement signed by the old key.** Peers learn
   `old -> new` from the holder itself; the cost is that a thief with the old
   key can perform the rotation — which entangles this option with
   `[DSC1-NV-01]` — and that a peer offline across the announcement never learns
   the mapping.
3. **Accept rename-on-rotate.** No mechanism at all: rotation is
   re-introduction, and every consumer re-authorizes. Cheapest to build, and it
   pushes the whole cost onto operators and onto durable attribution.

All three have consequences beyond this epic — for SOC2's moderation decisions,
which are keyed on the peer, and for AGO2's attribution, which is durable and
user-visible. That is why the choice is not made here.

**Follow-up filed: `computenet-aimh`** (epic `computenet-ssa`,
`lane:distribution`) — decide the option and its consequences, and say what it
does about revocation.

### Kernel gaps surfaced by this epic

- **`computenet-l8y5` (open, in flight at the time of writing)** — an ill-formed
  announcement encoding reaches ingress *unclassified*. `canonicalBytes` rejects
  unpaired surrogates by throwing `IllegalArgumentException`; the kernel wire
  codec decodes a lone `\ud800` escape straight into `WireFrame.portName`, so a
  remote peer can make the encoder throw. The frame does become a dead letter,
  but its reason is not a member of the epic's closed refusal set — today it is
  recorded as `BAD_SIGNATURE`. A distinct `DenialReason` is pending on that
  item; as of this entry's commit the enum ends at `EXPIRED`, and
  `MALFORMED_HELLO` covers only the hello seam.
- **`computenet-tdcx` (open)** — a clock-seeded incarnation can *observe*
  monotonicity across restarts, never *prove* it. `computenet-ssa.6`'s default
  incarnation source is the wall clock, so an NTP correction across a
  crash-restart, or a container with no battery-backed clock, silently
  reproduces the pre-`.6` defect with its original symptom: a peering that will
  not re-converge, silent from the sender's side. It fails closed — a backwards
  step yields a floor below the peer's high-water mark and the burst
  dead-letters as `REPLAY` — which is why this is a residual and not a hole.
  `AnnouncementSigningConfig.incarnation` is a `() -> Long` read once at
  construction, so a durable source drops in without a kernel change. Note the
  coverage limit `.6` rejected its alternative over: a *derived* identity (a
  seed phrase, an HSM- or KMS-backed key) has no file next to the key, so a
  file-backed incarnation covers a strict subset of what the clock default
  covers — additive, not a replacement.

### Test-suite findings from the completeness sweep

These come from `computenet-ssa.5.2`'s mapping and its review, and were
re-checked against the test sources for this entry. **No coverage gaps were
found**: all 18 scenarios, plus `computenet-ssa.6`'s restart addendum, exist as
named tests in their designated modules, and the adversarial set BS-04..BS-14 is
present in full, each asserting its own `DenialReason` or outcome rather than
name-dropping a marker. What follows are conventions and attributions, recorded
so the next reader does not re-derive them:

- **Two rows carry their `BS-nn` marker only in KDoc, not in the `@Test` name**:
  BS-01 (`WsAuthenticatedHelloTest`, `:wire`) and BS-17
  (`AnnouncementCanonicalBytesPropertyTest`, `:identity`, where the marker is
  class-level). 16 of 18 carry it in the test name. Both tests are functionally
  sound; what breaks is the "grep the marker on the `@Test` line" convention,
  so a future marker sweep undercounts by two unless it also reads KDoc.
- **BS-01's Then-clause is split across two tests.** Its third part — a delivery
  arriving over the connection observes `Principal.Peer(id, Authenticated)` — is
  asserted by `WsPrincipalPromotionTest` (`:wire`), whose KDoc names itself
  "BS-01's **delivery** clause", and not by the `WsAuthenticatedHelloTest` test
  the mapping names on the BS-01 row. Coverage is complete; a single-test
  attribution for BS-01 is not.
- **`TrustBoundaryTest`'s `BS-14` comments are SEC1's BS-14, not DSC1's.** That
  file's markers cite `[SEC1-06]`/`[SEC1-07]` — a denial is not a fault, no
  `SupervisionPolicy.RESTART` fires — which is a different epic's scenario that
  happens to share a number. DSC1's BS-14 (the allowlist evaluated on the
  derived id) is covered in full by `WsHelloAllowlistDerivedIdTest` (`:wire`).
  A marker grep across epics collides here: read the cited requirement id, not
  the number.
- The `:concord` corpus is unchanged by this epic, as §5 intended: the corpus
  has no vocabulary for peers, hellos, keys, signatures or dead-letter reasons,
  and adding one is a gated schema change, not a side effect of DSC1.

---

## 2026-08-21 — `computenet-l8y5` closed: the ill-formed-announcement gap is no longer open

Names the DSC1 entry above, whose "Kernel gaps surfaced by this epic" section
records `computenet-l8y5` as *open, in flight at the time of writing*, with "a
distinct `DenialReason` … pending on that item; as of this entry's commit the
enum ends at `EXPIRED`". Both statements were true of that entry's commit
(`a65a09f5`) and are recorded, not edited, per this file's append-only rule.

What changed: `computenet-l8y5` landed as PR #408 (`69deff06` on `main`, merged
while this lane's first entry was still on its feature branch). Ingress now
classifies an ill-formed announcement encoding as **`MALFORMED_ANNOUNCEMENT`** —
a fourteenth `DenialReason`, distinct from `BAD_SIGNATURE` (which is what the
case was misfiled as) and from `MALFORMED_HELLO` (same kind of fact, different
sub-protocol). The record names the field, the offending index and the string's
length, never the string itself (`[DSC1-OBS-05]`). The enum therefore no longer
ends at `EXPIRED`, and the bead is `closed`.

Nothing else in the entry above is affected: `computenet-tdcx` is still open,
`[DSC1-NV-01..03]` are still explicitly unverified, and the key-rotation
position and its follow-up (`computenet-aimh`) are unchanged.

---

## 2026-09-12 — Key rotation decided (option 4) and the revocation path designed over anchor rebinding (`computenet-aimh`)

**Names the entry "Key rotation: a documented position, not a mechanism" in the
DSC1 entry above, and supersedes its position.** That section states *"The
position: identity IS the key"* and lays out a three-option space as unchosen.
Both statements were true of that entry's commit and are recorded, not edited,
per this file's append-only rule. They are now wrong: the premise was rejected
and a fourth option was adopted. Per the file's own warning at the top, a reader
who cites that section without reading forward will cite a superseded position.

This entry records a **decision and a design**, not a mechanism. Nothing in it is
built by `computenet-aimh`; the rotation half is being built under epic
`computenet-5y8t` (DSC4) and the revocation half is built by nobody yet.

### The decision: option 4, anchor-vouched stable name

Taken by the maintainer on 2026-08-29 (recorded on `computenet-aimh`), amended
the same day. The acceptance criteria permitted *"one of the three ... or a
fourth explicitly argued"*; this is the fourth, and it rejects the premise the
other three share — that identity is key material and the consequences are to be
negotiated around.

1. A peer's identity is a **stable name**. It is not derived from, and does not
   change with, any key the peer holds.
2. The binding from that name to the peer's **current public key** is a signed
   statement. Its trustworthiness comes from a signature by a **centrally
   managed anchor key**, not from where the mapping is stored or how it arrives.
3. **Delivery is decentralized.** Signed statements may be gossiped, replicated
   as mesh state, cached, or piggybacked on the hello. A signed statement is
   self-validating: a peer holding the anchor's public key verifies it offline,
   with no lookup and no reachability requirement. There is **no central lookup
   service**, and none may be introduced on the strength of this decision.
4. **Rotation is a new signed binding for the same name.** The name survives, so
   allowlists, mirrored `Remote` locations and durable AGO2 attribution all keep
   pointing at a live, resolvable identity.

Why the premise was rejected: binding identity to key material means rotation
renames the peer *at whatever tier the binding sits*. Option 1 relocates that
problem rather than solving it — the identity key becomes the thing that can
never rotate, so identity-key compromise, or an algorithm migration
(post-quantum being the obvious one), returns the original problem with a
delegation tier added for nothing. This is R7 direction (2) in
`doc/spec/90-roadmap/95-research-plan.md`, recorded there as *not pursued*
because direction (1) was smaller and sufficient at the time. It is now pursued;
R7 itself judged this shape as matching the niche, so the reversal is on **size,
not merit**.

A self-certifying alternative (name derived from a long-lived genesis key,
current key reachable by a signed delegation chain, no anchor) was argued and
**rejected**: it cannot recover from identity-key compromise. Under pure
self-certification a stolen root key makes the thief the peer permanently. An
anchor-vouched mapping can rebind a compromised name to a new key after
out-of-band authentication — the capability the self-certifying design
structurally lacks, and the capability the revocation design below is built on.

Three objections to a central anchor were **withdrawn** once vouching was
distinguished from delivery, recorded so they are not raised again: availability
is not a correctness dependency (a statement verifies offline); it is not a
category change (a signing key is not a service, and can be held offline); and
the P7/P10 contradiction is weakened but not eliminated — see R3 as amended.

### Residual risks — accepted, not solved

Carried from the 2026-08-29 decision. **Do not read the decision, or this entry,
as closing any of them.**

- **R1. Anchor-key compromise is total.** Whoever holds the anchor key can mint a
  binding from *any* name to a key they control, impersonating every peer
  without stealing anything, and a malicious rebinding is indistinguishable from
  a legitimate rotation. This is strictly more powerful than the single-key
  theft the decision defends against. Known mitigations exist and are **not
  chosen**: an append-only transparency log gossiped over the mesh
  (Certificate-Transparency shaped), threshold signing or multiple independent
  anchors, and first-seen pinning with an alert on change.
- **R2. Anchor-key rotation is unsolved.** The anchor's own key cannot rotate
  without the same rename problem one level up. Standard answers (overlapping
  validity, cross-signing, multiple configured anchors) are not chosen here.
- **R3 (as amended 2026-08-29 — the amendment replaces this residual's framing,
  it is not a second item alongside it). An anchor is the architecture, not a
  compromise.** R3 was first written as *"tension accepted for the deployments
  we have now"*. That undersells the position. No single system fits all needs
  and real security is layered: purely technical decentralization has a poor
  empirical record, under cryptocurrency-scale financial incentive to make it
  work. A trust anchor is valuable precisely because breaching it requires
  defeating more than a technical system — it sits inside norms, law, and the
  enforcement of both. The intended long-run anchors are **institutional**
  (governments issuing identities for people being the clearest case); the
  anchor ComputeNet operates for itself is explicitly a **dummy implementation**
  standing in for that. What remains genuinely open is the open-mesh case:
  `doc/spec/40-distribution/43-security.md` §Posture's *"mutually untrusting
  contributors"*, P10 in `doc/spec/00-foundations/02-design-principles.md`, and
  SOC3 (`computenet-6a2`, the declared north star, whose §1 is an OPEN mesh).
  The tension is **resited, not dissolved**: the claim is no longer "we accept a
  central point reluctantly" but "trust roots are institutional by design, and
  the technical layer's job is to make *which* institution is trusted an
  explicit, per-relying-peer policy choice". **SOC3 must not be planned as
  though this is settled**; it still has to say what it does when participants
  share no issuer.
- **R4. Revocation is deferred, and `[DSC1-NV-01]` remains EXPLICITLY
  UNVERIFIED.** The anchor makes revocation *possible* — rebinding is the
  mechanism — but no revocation path is designed, published or verified by the
  decision. This entry designs it; **designing it does not build it**, and
  `[DSC1-NV-01]` stays unverified until a peer can act on "this key is no longer
  me". See the entry below.

The amendment also *raises* the value of the resolution seam rather than
lowering it: the seam is the socket institutional issuers plug into later, so it
must be designed as an issuer boundary — more than one anchor expressible, the
anchor nameable and attributable in the verification result so a relying peer
applies its own policy (the shape `BoundaryPolicy`'s identity-keyed predicates
already have), and our own anchor not privileged in the code.

### The revocation design — over anchor rebinding, not a second mechanism

This is the half `computenet-aimh` was reopened for on 2026-09-11. It is a
design position; no code implements it.

**The mechanism is the one DSC4 already has.** The only authoritative statement
about a name is the anchor-signed binding of that name to a public key.
Revocation is therefore not a new object type and not a new protocol: it is the
anchor issuing a **superseding binding** for the same name — to a replacement
key after out-of-band authentication, or to a designated *no valid key*
tombstone when the name is to be admitted by nobody. Anything that introduces a
separate revocation channel — a CRL, an OCSP-shaped responder, a "revoked" list
a peer must fetch — contradicts the decision's property 3 and must not be built.

DSC4's stated design properties bind here, and each one constrains the design:

- **Offline verification against a held anchor public key, no reachability
  requirement.** This is the property that fixes revocation's character: a
  relying peer can verify that a binding it *holds* is genuine, but it can never
  establish offline that the binding it holds is the *newest* one. **Revocation
  is therefore eventual and best-effort by construction**, and there is no point
  at which a peer knows it is current. Any design that makes revocation prompt
  by requiring a peer to check something reachable is a different decision, not
  an implementation of this one.
- **Decentralized delivery.** A superseding binding travels the same way the
  original did — gossip, mesh state, cache, hello piggyback. Delivery is the
  whole of revocation latency.
- Two consequences follow that the design must carry, or revocation does not
  work at all:
  1. **A monotone issuance counter per name, signed inside the statement,
     strictly-greater-wins.** Without an ordering discipline, a peer receiving
     an old and a new binding for one name by different gossip paths has no
     ground to prefer either, and a thief holding the superseded key can
     **re-inject the superseded binding** and undo its own revocation. The
     counter is what makes the superseded statement permanently non-authoritative
     to any peer that has seen the newer one — and only to those peers.
  2. **A validity window in the statement**, because it is the only bound on
     staleness a peer can evaluate offline — and note what that buys it on:
     a clock. `[DSC1-NV-03]` (clock-skew adequacy) is recorded EXPLICITLY
     UNVERIFIED in this same file and stays that way, so the one offline
     bound this design has rests on a property the project has not checked.
     It caps how long a superseded
     binding remains acceptable to a peer that never hears the rebinding. The
     tension is real and is named rather than hidden: short windows buy
     revocation latency by making liveness depend on re-issuance *reaching*
     peers, which is a reachability requirement arriving by the back door. The
     position: the window is a **per-deployment policy knob, not a protocol
     constant**, and a deployment choosing a short window is choosing
     availability risk over revocation latency, knowingly.
- **Verification behind the `SignatureVerifier`-shaped seam.** In landed code
  that seam is `PeerIdentityBinding` in
  `kernel/src/main/kotlin/civictech/cell/link/Identity.kt` — the kernel declares
  it, `:identity` supplies the implementation, and its `Interim` binding maps a
  `KeyId` to the `PeerId` of the same name. Revocation is expressible entirely
  as the behaviour of a non-interim binding: it resolves a `KeyId` to a `PeerId`
  only while an unsuperseded, in-window statement vouches for that pairing.
  **A blocking finding for DSC4, stated here because it is cheaper to know now:
  `PeerIdentityBinding.identityOf(key: KeyId): PeerId` is total and cannot
  express "no identity".** A revoked key has no identity to resolve to, so
  either that signature admits refusal (a nullable or sealed result) or
  revocation cannot live behind the one seam the decision requires it to live
  behind, and a second seam appears — exactly the scattering `computenet-376c`
  built the seam to prevent. Filed as `computenet-hbqvz` under DSC4.
- **More than one anchor expressible; the anchor nameable in the verification
  result.** Revocation is therefore **issuer-scoped**: issuer A superseding a
  binding says nothing about a name issuer B vouches for. Under multiple accepted
  issuers "revoked" is not a global fact, and a relying peer accepting two
  issuers for one name must decide whether one issuer's supersession overrides
  another's live binding. Per the 2026-08-29 amendment that choice is **the
  relying peer's own policy**, not the protocol's — which is precisely why the
  verification result must *name* the issuer: a peer that cannot tell which
  anchor vouched cannot express the policy at all. The consequence to state
  plainly: a peer whose policy admits a name on *any* accepted issuer's live
  binding has revocation only as strong as its most permissive issuer.

### What revocation costs the three consumers

Each of these is a cost of the mechanism as designed, not a defect to be fixed
later by tuning.

- **Allowlists (`Peering.Side.allow`, `allowPeers(...)`).** The central
  distinction, and it is easy to misread: **anchor revocation answers "this key
  is no longer this name", never "this name is no longer welcome".** Rebinding
  is authentication, not authorization; ejecting a peer remains allowlist
  removal and is a separate act.

  **But that only pays off once an allowlist entry names the name, and in
  landed code neither of these does.** Both are configured in `KeyId`s since
  `computenet-376c`: `Peering.Side.allow` is a `Set<KeyId>?` compared against
  the key on the wire at hello time, and `allowPeers(vararg keys: KeyId,
  binding)` resolves each *configured key* through the binding per evaluation
  and compares the result against the stamped `PeerId`. So today a rotation —
  and a revocation, which is the same statement — leaves both entries naming a
  key that no longer resolves to the peer, and an operator must re-point them
  by hand; the "re-admitted by the same entry the moment the new binding
  arrives, with no operator action" property option 4 is supposed to buy is a
  property DSC4 still has to deliver by moving allowlist *configuration* to
  names. Until it does, the two seams behave oppositely: `allowPeers` fails
  closed (the configured key stops resolving), while `Peering.Side.allow`
  never consults the binding at all, so anchor revocation is invisible to
  ingress admission. Both are DSC4's to change, not this entry's.

  Second cost, and it survives whichever way that is settled: until the
  superseding binding reaches a given peer, that
  peer's allowlist keeps admitting the stolen key. The window is bounded only by
  gossip and the validity window, and no peer can observe its own exposure.
- **Mirrored `Remote` location attribution
  (`civictech.cell.location.LocationRegistry.Remote.peer`).** Keyed on the
  stable identity, so rotation and revocation both leave mirrors intact — the
  location stays attributed to the same name, now reachable through a different
  key. The cost is retroactive: a location mirrored while a stolen key was live
  is attributed to the legitimate name and is **indistinguishable after the fact
  from a genuine one**. Revocation is prospective only; nothing re-attributes or
  invalidates what is already mirrored, and nothing marks a mirror as having
  been recorded inside a compromise window.
- **AGO2 durable per-`Principal` attribution.** The same shape with the worst
  consequence, because the statements are durable, replicated and user-visible.
  Rebinding keeps a rotated speaker's history linked to one name — the gain
  option 3 could never deliver. Revocation, though, renders the stolen key inert
  **going forward only** and changes nothing about statements already recorded
  and replicated under that name. A reader cannot tell which statements the
  thief made. Retroactive repudiation — a signed "statements attributed to me
  between t1 and t2 were not mine" — is **not** provided by rebinding: it needs
  its own statement type, a rule for how a reader renders repudiated history,
  and a decision about whether replicated durable state may be re-rendered at
  all. That is out of scope here and is named as the residual this design does
  not close.

### Scope, and what this entry deliberately does not touch

`computenet-aimh` is documentation only. It implements nothing, and it does not
change `PeerId` derivation, the hello, `allowPeers`/`Peering.Side.allow`,
mirrored `Remote` attribution or `Principal` — all DSC4's territory (epic
`computenet-5y8t`). The `KeyId`/`PeerId` split the design needs has already
landed (`computenet-376c`, `computenet-egl.3`).

The rotation **documentation reconciliation** listed on `computenet-aimh`'s
2026-08-29 "REMAINING SCOPE" comment — `doc/spec/40-distribution/43-security.md`
(*"a PeerId derived as the key's fingerprint"*, and §G-29's open list, where key
rotation/revocation is now *decided-but-unbuilt* rather than undefined),
`doc/spec/90-roadmap/95-research-plan.md` R7 (direction (2) is now the adopted
shape, and R7's *"no decided position yet"* closing line is superseded),
`doc/ARCHITECTURE.md`'s `:identity` row, and `doc/spec/90-roadmap/91-gap-analysis.md`'s
G-29 row — is **not done here**: the maintainer disposition of 2026-09-11
narrowed this bead to revocation, and doing it would be the rotation half.

State it exactly, because the 2026-08-29 inventory has drifted and because
ownership is currently unclaimed:

- **Still asserting identity-is-key at this entry's commit**:
  `43-security.md:56` (*"a `PeerId` derived as the key's fingerprint"*, plus
  §G-29's open list, where key rotation/revocation is now
  *decided-but-unbuilt* rather than undefined) and `95-research-plan.md` R7
  (direction (2) recorded as *not pursued* though it is now the adopted
  shape, and the *"no decided position yet"* closing line superseded).
- **Already reconciled, and the 2026-08-29 inventory is stale on both**:
  `doc/ARCHITECTURE.md`'s `:identity` row was rewritten by `computenet-376c`
  (`82158fb49`, PR #616) and `computenet-egl.3` (`0ecfc869d`, PR #620) and now
  describes key-derived **`KeyId`** fingerprints with `PeerId` resolving
  through the `PeerIdentityBinding` seam; `91-gap-analysis.md`'s G-29 row
  never asserted the derivation, and reads as accurate as written.
- **Ownership is unassigned, not delegated.** The disposition moved the
  *rotation* half to DSC4 but said nothing about these files, and epic
  `computenet-5y8t`'s own description lists "the documentation
  reconciliation, which is `computenet-aimh`'s remaining scope" under
  EXPLICITLY OUT OF SCOPE. Both items therefore currently sit outside both
  beads. Filed as a residual rather than silently left to whoever lands DSC4.
`doc/spec/CONCORDANCE.md` is generated and must never be hand-edited.

---

## 2026-09-12 — `[DSC1-NV-01]` revisited: stolen-key resistance REMAINS EXPLICITLY UNVERIFIED (`computenet-aimh`)

**Names the DSC1 entry's "Explicitly unverified — stated, not softened" section
above, and does not weaken it.** This entry exists so that the decision and the
design recorded immediately above cannot be read as having closed
`[DSC1-NV-01]`. They have not.

The state is unchanged in substance and changed only in prospect:

- **Unchanged.** A thief holding a peer's private key *is* that peer. Every
  signature verifies, every derived key identifier matches, and no seam in the
  system can tell the two apart. `PeerIdentityBinding.Interim` — the binding
  landed on `main` 2026-09-02 by `computenet-376c` (`82158fb49`, PR #616) —
  maps a key identifier to an identity of the same name and
  claims nothing about theft; naming the derivation does not strengthen it.
- **Changed only in prospect.** Option 4 makes recovery *possible*: an
  anchor-vouched name can be rebound to a fresh key after out-of-band
  authentication, which is the capability DSC1's identity-is-key model and the
  rejected self-certifying alternative both structurally lack. Possible is not
  built. No anchor, no signed binding statement, no issuance counter and no
  validity window exists in code at this entry's commit; the only binding in the
  repository is the interim one.
- Even fully built, the design above would **not** make `[DSC1-NV-01]` a checked
  property on its own. Revocation is eventual by construction: detection of
  compromise is out-of-band, and the window between theft and the superseding
  binding reaching a given peer is unbounded and unobservable by that peer. What
  a working path would let the suite check is narrower and honest — that a peer
  which *has received* a superseding binding no longer admits the superseded
  key. That is the requirement worth writing when the mechanism exists; it is
  not stolen-key resistance.

**No test is written here, and none may be written that appears to demonstrate
stolen-key resistance without a working revocation path.** Per AGENTS.md a
requirement that cannot be checked honestly is filed in
`concord/corpus/DISPUTES.md`, never weakened into a passing scenario;
`[DSC1-NV-01]` is filed there as of this entry. It has no corpus scenario and
must not acquire one until there is a mechanism for a scenario to exercise.

`[DSC1-NV-02]` (Sybil resistance) and `[DSC1-NV-03]` (clock-skew adequacy) are
untouched by this work and remain explicitly unverified exactly as the DSC1
entry states them.

---

## 2026-09-14 — DSC4 landed: anchor-vouched stable peer identity (epic `computenet-5y8t`)

**Names, does not edit, the three entries above**: `2026-08-21 — DSC1 ...`,
`2026-09-12 — Key rotation decided (option 4) ...`, and `2026-09-12 —
[DSC1-NV-01] revisited`. Where those said *decided, not built* or *design-
decided, not implemented*, this entry records what building it actually
produced — measured against tests, not restated from the decision.

### What landed

Six features and two out-of-band fixes, all merged to `main`:

- `computenet-5y8t.1` (issuer-boundary seam: `IdentityStatement`,
  `IdentityResolution.Bound`/`Unbound`, `PeerIdentityBinding.resolve`,
  `PeerStamp.issuer`) — PR #854, `bc58cf43b`.
- `computenet-5y8t.2` (offline verification in `:identity`: canonical
  statement bytes, `AnchorIssuer`, `AnchorVouchedBinding`) — PR #861,
  `f4b72fcff`.
- `computenet-5y8t.3` (the versioned wire break: `HELLO3` on `:wire`,
  `IROH-HELLO2` on `:iroh`; admission resolves through the binding) — PR
  #865, `a2cbab699`.
- `computenet-5y8t.4` (allowlists name identities: `Peering.Side.allow:
  Set<PeerId>?`, `allowPeers(vararg peers: PeerId)`) — PR #862, `3df3f1fad`.
- `computenet-5y8t.7` (signed announcements from a named peer are admitted:
  the minting check and the replay high-water mark re-keyed onto the bound
  name, not the signer key fingerprint) — PR #872, `621ac28e2`.
- `computenet-5y8t.6` (hello-sendable credentials validated at transport
  start, not on the sidecar reader thread) — PR #876, `c0abd6d96`.
- `computenet-tlb83` (an anchor-bound side refuses a nameless legacy hello
  `UNVOUCHED`) — PR #871, `9ee7ea5ec`.
- `computenet-28pnr` (43-security's Interim-bound-vs-anchor-bound admission
  wording corrected) — PR #873, `2b96b0853`.

Feature `computenet-5y8t.5` itself (this feature: the end-to-end rotation
test, the cross-transport test, and this entry) is **PR #877, still open as
a draft at the time this entry is written** — no `main` sha exists for it
yet; the two test classes it added are named below by class, not by sha.

### The wire break, and why it was required

`:wire`'s `HELLO2` line (`HelloProtocol.kt`) requires the claimed id to be
key-derived (`isKeyDerivedPeerIdForm`) before any binding is consulted — a
stable name literally cannot be written into that line's claimed-id field
without failing its own grammar check, let alone being admitted. `:iroh`'s
pre-DSC4 hello (`IROH-HELLO1`) carries at most a trailing name that may only
*confirm* the NodeId-derived identity, never assert an independent one. A
stable name therefore needed a new line form on each transport, not a
relaxation of the old one: `HELLO3` on `:wire` and `IROH-HELLO2` on `:iroh`,
each carrying one or more encoded `IdentityStatement`s alongside the existing
fields. Both new forms have their own prefix so a legacy or `HELLO2`/
`IROH-HELLO1` reader refuses them loudly (`NOT_HELLO2`/`NOT_HELLO3` on
`:wire`; the `IROH-HELLO1 ` prefix check on `:iroh`) rather than misparsing
them.

`HELLO2` and the legacy line, and `IROH-HELLO1`, are **retained** on an
Interim-bound side — `PeerAuthPolicy.Open` keeps today's behaviour
byte-for-byte there — so nothing pre-DSC4 broke. The consequence stated
plainly, and it is the shape of the break: **a stable-name peering needs
both sides on the new grammar.** An anchor-bound side receiving a
statement-less `HELLO2`/legacy/`IROH-HELLO1` hello gets
`Unbound(NO_STATEMENT)` from the binding and refuses, typed and accounted,
never silently downgrading to a key-derived identity (43-security.md's
Interim-bound/anchor-bound distinction, corrected by `computenet-28pnr`
before this feature started).

### Delivery and anchor-key distribution — the two design properties this feature exercises, not invents

**Delivery is hello piggyback**, exactly as epic property 3 names it: the
statement rides the same `HELLO3`/`IROH-HELLO2` line as the rest of the
hello. Gossip, replication-as-mesh-state, and caching remain admissible
later — nothing built here forecloses them, and nothing built here
implements them either.

**Anchor public-key distribution is relying-peer configuration** (epic
property 4): a relying process constructs its `AnchorVouchedBinding` from
the `acceptedIssuers: Map<IssuerId, PublicKey>` it chooses to hand in —
`AcceptedIssuerStore` loads public halves from files and refuses a private
key found in that directory — with no discovery, no fetch, and no default
issuer compiled in. Both test classes below build the binding this way: the
listener's map names exactly the anchor(s) it accepts, and nothing else is
reachable.

### The statement format

One `IdentityStatement` shape, declared in `:kernel`
(`civictech.cell.link.Identity.kt`) as data the kernel carries but never
verifies: `name: PeerId`, `keyId: KeyId`, `issuer: IssuerId`,
`issuance: Long`, `notBefore: Long`, `notAfter: Long`, `signature: ByteArray`.
The bytes an issuer signs are the canonical encoding in
`civictech.identity.anchor.IdentityStatementBytes.kt`, domain-tagged
`computenet/DSC4/identity-binding/v1` (`IDENTITY_BINDING_DOMAIN_TAG`) first,
then the fields in fixed order with length prefixes on the variable ones —
the same discipline as `AnnouncementSigningInput.canonicalBytes`, pinned by
a golden vector.

`issuance` is **carried** into every `Bound` resolution
(`IdentityResolution.Bound.statement.issuance`) and **compared with
nothing** by `AnchorVouchedBinding.resolve` — any verifying in-window
statement resolves, even one presented after a higher-issuance statement for
the same name was seen elsewhere. The validity window (`notBefore`/
`notAfter`) **is enforced**, against the receiver's own clock with a
lag-only skew allowance shaped like
`AnnouncementAdmission.DEFAULT_ANNOUNCEMENT_SKEW_MILLIS`
(`AnchorVouchedBinding.check`). Supersession — a later `issuance`
retiring an earlier one — is **not built**; `AnchorVouchedBinding`'s own
KDoc states the absence on purpose ("No supersession, no revocation (epic
residual R4)"), and `WsStableNameRotationOfflineAnchorTest`'s second test
method pins it by construction (below).

### The allowlist-by-name resolution of the 2026-09-12 cost paragraph

The "What revocation costs the three consumers" paragraph above (this file's
2026-09-12 entry) stated the gap plainly: *"today a rotation ... leaves both
entries naming a key that no longer resolves to the peer, and an operator
must re-point them by hand; the 're-admitted by the same entry the moment
the new binding arrives, with no operator action' property option 4 is
supposed to buy is a property DSC4 still has to deliver by moving allowlist
configuration to names."* `computenet-5y8t.4` is that delivery:
`Peering.Side.allow` is now `Set<PeerId>?`, judged against the *resolved*
identity (key proven → `identityBinding.resolve` → name on the allowlist),
and `allowPeers(vararg peers: PeerId)` drops its `binding` parameter
entirely — there is no longer a second way to configure the same allowlist
in keys. `WsStableNameRotationOfflineAnchorTest`'s
`rotate()` fixture is the property, run over real sockets and real crypto:
alice's rotation from K1 to K2 is admitted with `allow` unchanged and
`l.side.allow shouldBeSameInstanceAs allowAtStart` — the allowlist object
itself is never touched.

### Feature-5 evidence: the two test classes

- **`civictech.wire.WsStableNameRotationOfflineAnchorTest`**
  (`wire/src/test/kotlin/civictech/wire/`, task `computenet-5y8t.5.1`). One
  class, parameterised over `Binding.INTERIM` and `Binding.ANCHOR_VOUCHED`,
  run against a real socket with real Ed25519 keys and an offline
  `AnchorIssuer` object that is never listened on. It **shows**: a peer
  (alice) rotates from key K1 to key K2 under a fresh anchor statement for
  the same name and is admitted both times by one unchanged allowlist entry,
  with the mirrored `Remote` location and the `Principal.Peer` stamp both
  naming `PeerId("alice")` both times (the epic's first acceptance
  criterion, on the real bindings, not asserted structurally); the contrast
  under `Binding.INTERIM`, where the same steps rename and the allowlist
  refuses the rotated key (the pre-DSC4 behaviour, unchanged); and, in its
  second test method, that a dialer still holding K1 and its *original*
  issuance-1 statement is **still admitted as alice after the rotation** —
  the class's own KDoc states this is the absence of revocation, deliberate
  and required, so a reader of a green rotation test does not infer
  stolen-key resistance. It **does not show**: revocation, supersession, or
  anything about network reachability beyond "nothing the verification
  needed had an address to reach" (the anchor object holds no listener and
  no socket in the test JVM).
- **`civictech.iroh.IrohWireStableNameTest`**
  (`iroh/src/test/kotlin/civictech/iroh/`, task `computenet-5y8t.5.2`; needs
  `-Piroh.enabled=true` and a built sidecar, else it reports `SKIPPED`). It
  **shows**: one `Peering.Side` served by both an `IrohTransport` listener
  and a `WsTransport` listener at the same time, with alice dialing in on a
  *different* key over each transport (`fingerprint(Ki) != fingerprint(Kw)`
  asserted) and both dials resolving to one `PeerId("alice")` — read from
  the listener's own registry and its probe's stamped `Principal`, never
  from the dialer's state — which is `computenet-egl.3`'s own stated goal
  ("the same `PeerId` across transports") realized by a stable name rather
  than by key-derivation. It **does not show**: revocation, supersession,
  or stolen-key resistance — both keys are admitted because the anchor
  vouched for each, and a thief holding either key with its statement is
  alice exactly as anyone else would be.
- The second class requires `iroh/build.gradle.kts` to add
  `testImplementation(project(":wire"))` — needed because the cross-
  transport test dials the same listener over `WsTransport`, defined in
  `:wire` — and `testImplementation(libs.java.websocket)`, needed to compile
  against `WsTransport.WsListener`/`WsConnection` as every other `:wire`
  consumer already declares. **Production stays free of `:wire`**: the
  added edges are `testImplementation` only, and `:iroh`'s production code
  imports nothing from `:wire`.

### Residuals carried forward, unchanged in substance

- **R1. Anchor-key compromise is total.** Unaddressed; no mitigation
  (transparency log, threshold signing, multiple anchors, first-seen
  pinning) is chosen or built by this feature.
- **R2. Anchor-key rotation is unsolved.** Unaddressed.
- **R3, as amended.** Unaddressed by this feature; the open-mesh /
  no-shared-issuer question (SOC3) is untouched.
- **R4. Revocation is deferred.** Unaddressed by design *and* pinned by
  test: `AnchorVouchedBindingTest` and
  `WsStableNameRotationOfflineAnchorTest`'s old-key case both assert the
  absence of supersession, on purpose, so a green suite is never read as
  having built it.
- **Per-issuer allowlist predicates** ("admit alice only if issuer A
  vouches") remain unwired. `PeerStamp.issuer`/`Principal.Peer.issuer`
  (feature 1) make the predicate expressible in `BoundaryPolicy`'s
  identity-keyed vocabulary; nothing here constructs one. Left to a later
  item.
- **`[DSC1-NV-03]` (clock-skew adequacy)** stays explicitly unverified
  under the window: `AnchorVouchedBinding`'s own KDoc says the window rests
  on it, and nothing built by DSC4 measures adequacy between real peers'
  clocks.

**`[DSC1-NV-01]` (stolen-key resistance) REMAINS EXPLICITLY UNVERIFIED.**
This is not a residual alongside the others above; it is restated in its
own paragraph because it is the property this epic's design work was most
at risk of appearing to close. Nothing built by DSC4 changes the analysis in
this file's `2026-09-12 — [DSC1-NV-01] revisited` entry: a thief holding a
peer's private key is that peer, every signature verifies, and no seam
introduced here — the issuer boundary, the anchor-vouched binding, the
`HELLO3`/`IROH-HELLO2` grammar, the name-keyed allowlists and replay ledger
— can tell the two apart. Both new test classes say so explicitly in their
own KDoc, and neither asserts anything that would read as revocation or
theft resistance. `[DSC1-NV-01]` remains filed in
`concord/corpus/DISPUTES.md`, unedited by this feature, and must not
acquire a corpus scenario until a revocation mechanism exists for one to
exercise.

### `doc/spec/90-roadmap/91-gap-analysis.md`'s G-29 row

Read, not edited (lane-forbidden here per the epic's "out of scope" list).
Its current text — "crypto authentication and encryption-at-rest remain" —
predates DSC4 and reads as though no anchor-vouched authentication exists;
it likely needs a line naming DSC4 as landed (with revocation still open,
R4), the same correction this entry makes to 43-security.md and
95-research-plan.md. Left to whoever next has that row in their claim.

`concord/corpus/DISPUTES.md` is unchanged by this feature.

## 2026-09-21 — DSC2 landed: peer discovery over iroh (epic `computenet-aas`)

**Names, does not edit, the entries above**: `2026-08-21 — DSC1 ...`,
`2026-09-12 — Key rotation decided (option 4) ...`, `2026-09-12 —
[DSC1-NV-01] revisited`, and `2026-09-14 — DSC4 landed`. This entry records
DSC2 as the epic's own re-scope (2026-09-19) left it: iroh delivered most of
what the former body designed by hand, and DSC2's residual is a LAN mDNS
flag, an opt-in self-hosted rendezvous mode, and a discovery-driven dial
policy that keeps identity resolution at the hello, never at discovery.

### What iroh delivered, and what DSC2 built

iroh already supplies the four things the former `:discover` design would
have had to build itself: a connection cryptographically bound to the key
that advertised it (`Endpoint::connect(EndpointId)` cannot be steered to a
different key by a lying advertisement); self-authenticating signed address
records via pkarr (preset `N0`'s `PkarrPublisher`/`PkarrResolver` publish and
resolve a `SignedPacket` under the endpoint's own key — the former
`[DSC2-REC-01..08]` record design is this, verbatim, and DSC2 defines no
record type, encoding, or signature of its own); reachability and relay
fallback, owned by DSC3, untouched here; and re-dial of a once-known key
(`IrohConnection.scheduleReconnect`, already landed). Identity itself —
which name a key speaks for — is DSC1 + DSC4's, not DSC2's: a `KeyId`
fingerprint identifies which key backs a connection, a durable `PeerId`
identifies who the peer is, and the single seam `PeerIdentityBinding.resolve`
joins them at the hello (`IrohTransport.Session.onHello2`). Discovery
consumes this and defines none of it.

What DSC2 itself delivered, across the three merged features under this
epic: (1) LAN enumeration — `computenet-ne2oh`, an opt-in `--mdns` sidecar
flag adding `iroh-mdns-address-lookup` as an address-lookup service, and a
host-protocol `WATCH_PEERS`/`PEER_DISCOVERED`/`PEER_EXPIRED` subscription, off
by default (no multicast socket when the flag is absent) — PR #943, sha
`8ff7b1e5`; (2) a discovery-driven dial policy in `:iroh`'s
`civictech.iroh.discover` — `computenet-ktn1l`: one live peering per key
identifier, self excluded, bounded concurrency and retained state, capped
backoff on an injected schedule, the mutual-dial tie-break (aas-D7), and key
rotation kept at the identity resolved by the hello, not minted by discovery
— PR #946, sha `b65f96a2`; (3) self-hosted rendezvous — `computenet-vnscs`, a
new `LookupMode::Rendezvous` behind `--pkarr-relay-url`/`--dns-origin` and a
loopback `computenet-iroh-dns` binary, with the development default left at
`N0` — PR #952, sha `1923610f`. Two features remain open under the epic at
the time of this entry and are not counted above: `computenet-63um5`
(beadsmirror forms its two-node peering by discovery; BS-03/BS-09) and
`computenet-md1dt` (BS-08 mutual-dial tie-break over real sidecars — no
mutual dial is exercised by anything merged so far). This feature,
`computenet-qzr7n` (key rotation over real sidecars, BS-06, and this entry),
is PR #953.

The former body's own mechanisms — a new `:discover` Gradle module, a
hand-designed signed `PeerRecord` with its own canonical encoding, a jmdns or
hand-rolled DNS-SD responder, and a `PeerDirectoryCell` rendezvous dialling
`WsTransport::connect` — were **not built**, and are not a gap: they were
RE-SCOPED on 2026-09-19 (epic comment `SUPERSEDED WORDING`) because they
were designed before DSC0 adopted iroh (which delivers the record and the
LAN/rendezvous mechanism) and before DSC4 changed what a peer identity is
(which the former `[DSC2-ID-01..06]` assumed was the derived key, not an
anchor-vouched name). The former text is preserved verbatim on the epic for
audit; nothing here edits or restates it beyond the identity table below.

### Decisions `aas-D1`..`aas-D9`

- **aas-D1 — Home.** No `:discover` module. LAN/rendezvous lookup lives in
  the Rust sidecar behind `SidecarConfig`; the dial policy lives in `:iroh`
  under `civictech.iroh.discover`. `:kernel`, `:wire`, `:identity` gain
  nothing.
- **aas-D2 — Records.** iroh's pkarr signed packet IS the peer record; no
  ComputeNet record type, canonical encoding, signature, or epoch is defined.
- **aas-D3 — LAN mechanism.** `iroh-mdns-address-lookup` 0.5.0, not jmdns, not
  a hand-rolled responder, not the older `swarm-discovery` crate directly,
  behind `--mdns`, orthogonal to `LookupMode` (composes with `Offline` for
  tests, with `Rendezvous` and `N0` for deployments). The premise this
  decision carried unverified — that the crate compiles against iroh exactly
  1.0.3 — resolved without a version bump: `computenet-ne2oh` landed against
  1.0.3 unchanged (PR #943), so the park this decision reserved was never
  triggered.
- **aas-D4 — Rendezvous default.** A new `LookupMode::Rendezvous` variant is
  additive (`Offline`, `N0`, `Relay` unchanged); the mode exists and is
  proven in CI (`computenet-vnscs`, PR #952), but the DEVELOPMENT DEFAULT
  stays `N0` — this is a DOCUMENTED default, not a decision this epic makes
  for a deployer, in the same pattern `doc/iroh-adoption.md`'s "Relay hosting
  policy" section uses for the relay default: that section documents what
  n0's public infrastructure observes and states plainly that self-hosting is
  needed only for a deployment that must not disclose that metadata, while
  leaving n0's relay as the default anyone gets without choosing otherwise.
  DSC2's rendezvous default reads the same way: `--pkarr-relay-url` and
  `--dns-origin` self-host the directory for a deployment that must not
  publish peer addresses to n0's infrastructure; absent those flags, `N0`
  remains what a fresh `computenet-iroh-sidecar` invocation gets, not because
  self-hosting was rejected but because nothing here forces the choice.
- **aas-D5 — Not enumeration beyond the LAN.** pkarr/DNS resolve a KNOWN key;
  `iroh-dns-server` enumerates nothing; a directory or peer exchange is
  GOS1's (`computenet-yk6`) or SOC3's bootstrap to build. **The sentence
  SOC3 can act on**: DSC2 enumerates peers on one LAN segment only (via
  `--mdns`); beyond that segment, a node needs one bootstrap key supplied out
  of band, or GOS1.
- **aas-D6 — Identity under clause 2'.** A discovery event yields a KEY
  IDENTIFIER only; deduplication, self-exclusion and dial bookkeeping are per
  key. The identity is resolved solely at the hello through
  `Side.identityBinding`; `Side.allow` (names) is never evaluated earlier. A
  second key whose hello resolves to a name that already has a LIVE peering
  is the rotation this epic proves (`computenet-qzr7n.1`'s arm 1, below); a
  second key whose hello resolves to that name AFTER the old link has
  already dropped is a different, merely-observed ordering (arm 2, below) —
  the landed `PeerTable.judge` recognises only the former as a supersession.
- **aas-D7 — Mutual dial.** When both sides discover and dial each other, the
  side whose own NodeId is lexicographically smaller (unsigned byte order)
  keeps its outbound link and closes its inbound; the other keeps its inbound
  and closes its outbound, decided before the losing link's `Session` reaches
  `bindAndAnnounce` so no mirror is announced twice and the closing link does
  not count toward `unadmitted`. Built in `computenet-ktn1l` (`MutualDialTest`
  over `FakeSidecar`); the real-sidecar exercise of this tie-break is
  `computenet-md1dt`, still open — no figure is given for it here.
- **aas-D8 — Protocol.** `WATCH_PEERS` (`0x08`)/`WATCHING` (`0x89`) and
  `PEER_DISCOVERED` (`0x87`)/`PEER_EXPIRED` (`0x88`) are additive host↔sidecar
  kinds on link 0, emitted only after `WATCH_PEERS`; a discovered peer's
  addresses also feed the endpoint's `MemoryLookup` so `DIAL <id>` needs no
  `ADD_PEER`.
- **aas-D9 — Defaults.** `--mdns` off; `LookupMode` default `N0` unchanged;
  `WATCH_PEERS` never sent by any existing call site. Every existing
  sidecar-backed test and `IrohConvergenceSuiteTest` runs byte-for-byte as
  before with no flag (`[DSC2-NEU-03]`, `[DSC2-MDNS-03]`).

### Evidence

Every figure below is `MEASURED` with its run id or command, or `ESTIMATED`;
a row for a suite still unmerged names its bead instead.

| Suite | Scenario(s) | Platform | Outcome | Label |
|---|---|---|---|---|
| `iroh/sidecar/tests/protocol.rs`, `mdns.rs`, `relay_mode.rs`, `rendezvous_mode.rs`, `two_endpoints.rs` | BS-01, BS-02, BS-11 | ubuntu, `iroh-sidecar` lane | pass (part of the lane's `cargo test` step) | MEASURED (merged features' own PRs #943, #946, #952 — the lane runs `cargo test` on every push) |
| `:iroh` FakeSidecar suites (default lanes, every PR) | BS-04, BS-05a, BS-05b, BS-07, BS-08 (fake half), BS-12 | any (no `-Piroh.enabled`) | pass | MEASURED (required checks on #943/#946/#952/#953) |
| `SidecarMdnsDiscoveryTest` | BS-01 (JVM half) | ubuntu, `iroh-sidecar` lane | pass | MEASURED (PR #943's lane run) |
| `KeyRotationContinuityFakeTest` | BS-06 (fake twin) | any | pass | MEASURED (PR #946's default-lane run) |
| `MutualDialTest` | BS-08 (fake half) | any | pass | MEASURED (PR #946's default-lane run) |
| `IdentityMismatchFakeTest` | BS-05b(a) — both `[DSC2-ID-05]` mismatches (qzr7n-D8: this is where BS-05b(a) lives; no real-sidecar variant is built) | any | pass | MEASURED (default-lane runs) |
| `DiscoveryFloodTest` | BS-07 | any | pass | MEASURED (default-lane runs) |
| `IrohDiscoveredKeyRotationTest` | BS-06 (real, arm 1 rotation + arm 2 restart ordering) | macOS arm64 (this machine) | SKIPPED — `MulticastGate.deliveryOrSkip`, host does not deliver multicast (ne2oh-B6) | not executed here; see next row |
| `IrohDiscoveredKeyRotationTest` | BS-06 (real, arm 1 + arm 2) | ubuntu, `iroh-sidecar` lane, branch `feature/computenet-qzr7n`, head `0dcd0c05` | **2 PASSED, 0 FAILED, 0 SKIPPED** — both arms EXECUTED | MEASURED (CI run `35554798313`, job "iroh Gradle check, flag-enabled (timed)", `BUILD SUCCESSFUL in 4m 19s`) — the first execution of BS-06 over real sidecars anywhere, and the first real-sidecar exercise of `IrohNode` + `DiscoveredPeering` at all |
| `IrohDiscoveredKeyRotationTest` (earlier revision) | BS-06 (real), arm 2 as first drafted | ubuntu, `iroh-sidecar` lane, head `88e74883` | arm 1 PASSED; arm 2 FAILED at `await("a retry armed for K1")` | MEASURED (CI run `35552410099`) — this failure DISPROVED the drafted restart mechanism and produced `computenet-qzr7n.3`, which rewrote arm 2 to assert landed behaviour instead; the restart ordering above is therefore a RECORDED observation from a real run, not a prediction |
| beadsmirror discovery-formed rig (BS-03, BS-09) | — | — | not yet built | pending `computenet-63um5` (open) |
| BS-08 mutual dial over real sidecars | — | — | not yet built | pending `computenet-md1dt` (open) |

### `[DSC2-NV-01..06]` — EXPLICITLY UNVERIFIED

Restated verbatim from the epic's §4.8, as clause 2' and F5-D4 require, with
one sentence each on why no test closes it:

- **[DSC2-NV-01]** A loopback mDNS test proves the flag wiring, the event
  path and the dial; it proves nothing about switches, IGMP snooping,
  wireless client isolation or firewalls. Real-LAN behaviour is untested. —
  Every test in this epic, including `IrohDiscoveredKeyRotationTest`, runs on
  loopback; no test here or planned drives a real LAN segment.
- **[DSC2-NV-02]** Eclipse: a rendezvous or a LAN attacker can WITHHOLD;
  signed records defeat forgery, not omission. No quorum, gossip cross-check
  or liveness evidence is built. — BS-13 asserts the documented LIMITATION
  (a peer cannot detect an omission), never a detection; withholding is a
  property no test can positively falsify.
- **[DSC2-NV-03]** Sybil: minting a key is free; the anchor decision changes
  who issues NAMES and supplies no Sybil bound. — `[DSC2-MDNS-05]` bounds
  memory and dials, not identities; a Sybil cost bound is ECO1's, not built
  here.
- **[DSC2-NV-04]** Reachability is iroh's and DSC3's; a discovered address
  may be unroutable. — DSC2 dials whatever address discovery hands it and
  reports only success or failure; making an unreachable address reachable
  is outside this epic.
- **[DSC2-NV-05]** Advertising is metadata disclosure under a stable key;
  mitigated only by the off-by-default flag. Rotating or blinded
  advertisement is not built. — No test proves non-disclosure; the mitigation
  is that `--mdns` and the rendezvous flags are opt-in, not a property a test
  demonstrates.
- **[DSC2-NV-06]** (new, aas-D5) Enumeration beyond one LAN segment is not
  delivered. — aas-D5's own sentence above states the boundary; no test can
  prove a capability that was deliberately not built.

### Former `[DSC2-ID-*]` reconciled against the current `§4.2`

| Former id | Current id | What changed | Why |
|---|---|---|---|
| ID-01, ID-02 | ID-01 | A discovered peer is identified by its KEY IDENTIFIER for dialing/dedup; its IDENTITY comes solely from `Side.identityBinding.resolve` at the hello. | The former pair made a key-derived id the identity itself; DSC4 made identity an anchor-vouched NAME, so a derived id can no longer BE the identity — only the key it is dialled under. |
| ID-01's "sole naming scheme" clause | ID-02 | The one naming scheme for identity is DSC4's stable name, not a derived fingerprint; no `iroh/` site mints or derives a `PeerId` from key material. | Same cause: clause 2'(b) forbids a second naming scheme and forbids deriving identity from key material at any site. |
| ID-04 ("no second allowlist or trust store") | ID-03 | Restated to say an anchor-issuer trust store is PERMITTED; a second PEER allowlist or trust store is still forbidden. | Clause 2'(c): under the anchor model a trust store for ANCHOR public keys is expected, not a violation of the former prohibition, which targeted a second peer-level allowlist. |
| ID-05 | ID-04 | Unchanged: a discovery event raises no `AuthLevel`, satisfies no `BoundaryPolicy`, admits no traffic. | Clause 2'(d) retains this verbatim; the decision does not touch it. |
| ID-06 | ID-05 (split) | Split per clause 2'(e) into two distinct, named refusals: `IDENTITY_MISMATCH` (hello's resolved identity differs from one already attributed to the key) and the existing key-backing refusal (hello's presented key does not back the connection). | The former single mismatch clause conflated "wrong identity" and "wrong key"; under DSC4 these are different failures on different axes and needed different reasons (`IdentityMismatchFakeTest` exercises both). |
| ID-03 ("record names a peer other than its key") | dropped | No replacement; this case cannot occur. | iroh binds the dial itself to the key — a record cannot make a dial land on a different key than the one advertised, so the scenario the former clause guarded against is structurally impossible under iroh. |
| — | ID-06 (new) | A peer that rotates its key keeps its identity: a discovery event for the new key leads to a peering resolving to the SAME identity, and every attribution surface sees the same `PeerId` before and after. | New under clause 2'(f) — "the single clearest behavioural difference the decision makes to this epic" — and is this feature's own subject (BS-06, below). |

### Rotation across a restart — an observed gap, not a defect filed here

`computenet-qzr7n.1`'s `IrohDiscoveredKeyRotationTest` (CI run `35554798313`,
both arms executed) records that the landed `PeerTable.judge`/`linkDown`
recognise only ONE of two orderings as the rotation `[DSC2-ID-06]` describes.
**Arm 1** — the new key's hello arrives while the old key's link is still
live — is the rotation aas-D6 defines: A peers K2 as the same `b`, marks K1
`Superseded(by=K2)` without touching its link, refuses nobody, and once B1
dies never re-dials K1, not at the link drop and not ten years later. **Arm
2** — B1 is killed first and restarted at the same `--bind-addr` under K2 —
does NOT produce a supersession: `PeerTable.linkDown` nulls the entry's
`attributedPeer` on the link drop, so by the time K2's hello is judged the
table has already forgotten K1 was `b`; K2 is admitted as a plain `Admit`,
and K1 is re-dialled on the injected schedule against its now-dead endpoint.
What happens to that re-dial next is a race the test asserts as a
disjunction rather than a single outcome, because `LINK_DOWN` and the
sidecar's `PEER_EXPIRED` can arrive in either order: if `PEER_EXPIRED` lands
first it is swallowed (`PeerTable.expire` refuses a key whose `upLinks` are
not empty) and K1 stays `Retained` with one armed retry that the frozen
clock never releases; if it lands after the re-dial is already in flight, K1
moves to `Expired` with no retry armed. CI run `35552410099` — the only real
execution — observed the second ordering, with K1 expiring within the
dial's 5 s bound of B1's process exiting, not at the 30–43 s mDNS TTL that
`ne2oh-B5` measured for a peer that merely stops being seen; the opposite
ordering is not excluded and the test must not depend on either. Every
identity claim still holds in arm 2 — same `PeerId`, same `Principal`, no
denial — so what it pins beyond identity is that at most one retry is armed
and K1 is `Retained`, `Expired`, or still `Dialling` — never `Superseded`
and never re-`Peered` — pinning that disjunction as landed, not a single
predicted outcome.

This is a real, deliberately unresolved tension with the LETTER of
`[DSC2-ID-06]` ("the old key's connection shall be retired without re-dial
once its link drops"): under a clean restart the old key IS re-dialled the
moment its link drops, and is retired — if at all — by mDNS expiry racing
the re-dial's own result, not by supersession at the new hello. No mechanism
that would cancel K1's retry on recognizing K2's hello as the same identity
was built; this feature's scope was proving BS-06 over real sidecars, not
building a new mechanism. The earlier draft of arm 2 asserted the stronger,
undelivered claim (a retry armed immediately after the failed dial) and CI
run `35552410099` disproved it — arm 1 passed, arm 2 failed at
`await("a retry armed for K1")` — which is why the restart ordering above is
recorded as an observation from that real run, not a prediction; the
substitution is `computenet-qzr7n.3`. Whether a clean-restart rotation
should cancel the old key's retry once the new key's hello names the same
identity is left for the orchestrator to decide and, if wanted, file as a
new item; nothing here proposes a design for it.

### No `concord/corpus/DISPUTES.md` entry

None is triggered. A `DISPUTES.md` entry is filed only when a concord corpus
requirement is written against a claim this epic leaves unverified, and none
exists: `grep -rn 'DSC2' concord/corpus/` returns zero hits, and the epic's
own §5 states "Concord corpus impact: NONE" — no `[44-*]` requirement id and
no schema vocabulary for peers or discovery exists for a scenario to cover. A
later reader of this entry need not go looking in `concord/corpus/` for a
DSC2 dispute; there is nothing there to find.
