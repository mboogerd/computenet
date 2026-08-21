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
