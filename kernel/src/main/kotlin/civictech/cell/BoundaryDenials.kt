package civictech.cell

import civictech.cell.link.PeerId
import civictech.cell.proxy.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Denial accounting for `BoundaryPolicy` refusals (spec 40/43, decided 93
 * I-28; epic requirements `[SEC1-25]`/`[SEC1-26]`).
 *
 * ## Realization (B): a narrow accounting sink, not a thrown `BoundaryDenied`
 *
 * The feature that owns this seam (`computenet-usd.1`) named two candidate
 * realizations and **decided (B)**, this sink. The decision is settled — it is
 * recorded here so the next reader does not re-litigate it:
 *
 * 1. **Precedent.** The same defect class was closed at a different site by
 *    `[24-DUR-06]` (`ManagedHost`'s `Effectful` contextless-frame refusal):
 *    explicit discharge, an *additive* counter, a sanitized dead letter, and
 *    **no exception ridden through supervision**. A refusal there is not a
 *    fault; consistency with that shape is worth more than novelty.
 * 2. **(A) cannot serve all three flow-time sites.** The disclosure filter runs
 *    inside `FanOutlet`'s broadcast loop, in the **emitting** cell's dispatch.
 *    A thrown `BoundaryDenied` there would abort delivery to the remaining
 *    *permitted* targets and taps, and would surface as the emitter's fault —
 *    it never reaches `ManagedHost.enqueue`'s catch on that path. So (A)'s
 *    premise (reuse the landed catch) is simply false for seam 3b.
 * 3. **`[SEC1-29]`/BS-14 holds by construction.** "A denial is not a cell
 *    fault" needs no proof that `RESTART` does not fire when nothing throws:
 *    this sink never consults [civictech.cell.host.SupervisionPolicy], never
 *    escalates, and never mints or advances a wave (the report it hands the
 *    host carries a null [MessageContext]). Under (A) that would have been a
 *    property to demonstrate rather than a structural guarantee.
 *
 * ## Where this file lives, and why
 *
 * At the `civictech.cell` root, beside `Ownership.kt`. Every adopting package
 * already has an edge to `cell` in the T10-C architecture ratchet baseline
 * (`kernel/src/test/resources/architecture/package-edges.txt`): `membrane ->
 * cell`, `port -> cell`, `protocol -> cell`, `link -> cell` and `host -> cell`
 * are all pinned there, so denial accounting reaches every seam **without a
 * new package edge**. The one import this file adds, [PeerId], rides the
 * already-pinned `cell -> link` edge (line `cell -> link` in that file). See
 * [BoundaryDenial.principal] for why the record carries a `PeerId` rather than
 * the membrane's own `Principal` ADT.
 */

/** Which `BoundaryPolicy` seam refused a crossing (spec 40/43 "three seams, one per dispatch class"). */
enum class BoundarySeam {
    /**
     * Seam 1, the wire crossing itself: `BridgeIngressCell` refused a frame
     * from a peer not on its allowlist, before `WireCodec.decode` even runs
     * (spec 40/43 seam 1; 93 I-28 §4.3; `[SEC1-07]`). Distinct from
     * [LINK_AUTHORITY]: this is the frame admitted onto the bridge at all,
     * not a mediated-inlet link request the frame's invocation later carries.
     */
    ADMISSION,

    /** Seam 2, `onLink`: `BoundaryPolicy.linkAuthority` refused a mediated-inlet link. */
    LINK_AUTHORITY,

    /** Seam 3, `PORT_PROTOCOL`: `BoundaryPolicy.protocolAuthority` refused a metadata-plane frame. */
    PROTOCOL_AUTHORITY,

    /** Seam 3, `PORT_API` outbound: `BoundaryPolicy.disclosure` suppressed an emission. */
    DISCLOSURE,

    /** Seam 3, `PORT_API` inbound: `BoundaryPolicy.integrity` refused an arriving delta. */
    INTEGRITY,
}

/**
 * Why a crossing was refused. Deliberately closed and named per seam so a
 * denial record is machine-readable (auditability is the point — SOC2 needs
 * refusals it can count and classify, not a free-text log line).
 *
 * A **clamp is not a denial** (30/34 decision 6): an attention assertion over
 * `ProtocolAuthority.ceiling` is stored as `min(asserted, ceiling)` with the
 * emitter's LWW version preserved, and produces no record and no counter
 * movement. There is deliberately no reason constant for it.
 */
enum class DenialReason {
    /**
     * Seam 1: `BridgeIngressCell`'s `admit` predicate did not name this peer
     * on the allowlist (93 I-28 §4.3, `[SEC1-07]`). The frame is refused
     * before decode; the raw bytes are the sole `deniedArgs` entry.
     */
    NOT_ADMITTED,

    /**
     * Seam 2: a `LinkPolicy` in `linkAuthority` rejected the requesting peer.
     *
     * **Also a seam-1 reason** (computenet-a4ha): a
     * `civictech.cell.wire.RemoteLink` request whose named `(cell, port)` is not
     * a location the requesting peer announced —
     * `civictech.cell.wire.BridgeIngressCell`'s `"link-request"` sink. One
     * constant for the two, on the same reasoning [BAD_SIGNATURE] and [REPLAY]
     * are shared: "this peer may not have this link" is one fact about a
     * crossing, and [BoundaryDenial.seam] already says which boundary refused
     * it (`ADMISSION` here, `LINK_AUTHORITY` there). What separates them for a
     * reader is *when*: seam 1 refuses before the request ever reaches a port,
     * so no `LinkPolicy` was consulted and no endpoint was constructed.
     */
    LINK_REFUSED,

    /** Seam 3 protocol: the crossing's `Principal` is below `ProtocolAuthority.minAuth`. */
    MIN_AUTH,

    /** Seam 3 protocol: this `Principal` exceeded `ProtocolAuthority.ratePerWindow` (per-principal, never shared). */
    RATE,

    /** Seam 3 disclosure: `DisclosurePolicy.Deny` — no state crosses this boundary at all. */
    DISCLOSURE_DENIED,

    /** Seam 3 disclosure: a registered `Projection` returned null, suppressing this particular emission. */
    DISCLOSURE_PROJECTED_AWAY,

    /** Seam 3 integrity: the argument did not arrive in a `SignedDelta` envelope. */
    UNSIGNED,

    /**
     * Seam 3 integrity: the `SignatureVerifier` rejected the envelope.
     *
     * **Also a seam-1 hello reason** (DSC1, `[DSC1-HELLO-07]`): a `PROOF`
     * whose Ed25519 signature does not verify over the challenge bytes under
     * the public key its `HELLO2` presented. The two uses are deliberately one
     * constant — "the signature did not verify" is one fact about a crossing,
     * and the [BoundaryDenial.seam] already says which boundary refused. What
     * the seam-1 use must NOT be confused with is [ID_MISMATCH]: a signature
     * that verifies under a key whose fingerprint is not the id the peer
     * claimed is a *different* attack (impersonation) with a different reason.
     */
    BAD_SIGNATURE,

    /**
     * Seam 3 integrity: the `SignedDelta.counter` did not strictly increase for
     * this minting peer.
     *
     * **Also a seam-1 hello reason** (DSC1, `[DSC1-HELLO-11]`): a `HELLO2`
     * nonce or a `PROOF` signature this side already accepted from this peer
     * within the configured retention window
     * (`civictech.cell.wire.PeerAuthPolicy.RequireAuthenticated.nonceRetentionMillis`).
     * The mechanism differs — announcements are ordered by a monotonic
     * counter, hellos are one-shot challenges remembered in a bounded window —
     * but the refused fact is the same one: this crossing has been seen before.
     */
    REPLAY,

    /**
     * Seam 1 hello: the side's policy is
     * `civictech.cell.wire.PeerAuthPolicy.RequireAuthenticated` and the hello
     * did not carry the key material or the signature that policy demands —
     * a legacy name-only `HELLO`, or a `HELLO2` never followed by a `PROOF`
     * (`[DSC1-HELLO-08]`, `[DSC1-HELLO-09]`).
     *
     * **Machine-distinguishable from [NOT_ADMITTED] on purpose.** The epic
     * requires a downgrade attempt to be tellable apart from an allowlist
     * refusal: `NOT_ADMITTED` means "I know who you say you are and you are
     * not welcome", this means "you did not prove who you are at all". A peer
     * operator reading an audit trail acts differently on each.
     */
    AUTH_REQUIRED,

    /**
     * Seam 1 hello: the peer id a `HELLO2` claimed is not the id derived from
     * the public key that same `HELLO2` presented (`[DSC1-HELLO-06]`) — i.e.
     * `civictech.identity.fingerprint(presentedKey) != claimedId`.
     *
     * Distinct from [BAD_SIGNATURE]: the proof may verify perfectly under the
     * presented key. What fails is the *binding* between key and name, which
     * is the impersonation attempt the epic's BS-09 provokes. The denial
     * record carries both ids, and neither is secret.
     */
    ID_MISMATCH,

    /**
     * Seam 1 hello: a structurally invalid authenticated hello — the wrong
     * token count on a `HELLO2` line, an undecodable base64url field, a
     * claimed id outside the fixed key-derived form, or a protocol message
     * arriving out of order (a `PROOF` before any `HELLO2`).
     *
     * This exists so a malformed hello can never be *absorbed* into a peer
     * name. The legacy grammar's `split(" ", limit = 2)` does exactly that —
     * any extra whitespace-separated token becomes part of the claimed name,
     * yielding a wrong `PeerId` instead of an error — which is why
     * `civictech.wire.parseHello2` returns a machine-readable malformation
     * kind and this reason exists to record it.
     */
    MALFORMED_HELLO,

    /**
     * Seam 1 announcement: the announcement's `notAfter` is in the past
     * relative to the *receiver's* clock, less that receiver's configured skew
     * allowance (DSC1, `[DSC1-ANN-07]`;
     * `civictech.cell.wire.AnnouncementVerification`).
     *
     * **The record names the clock that refused** (epic §9.6), in
     * [BoundaryDenial.detail]: expiry is the one reason in this taxonomy whose
     * verdict depends on state the *peer cannot see*, so "expired" without
     * whose-now is unactionable — a clock this side is running slow produces
     * exactly the same refusal as a genuinely stale frame, and only the named
     * clock separates them. Nothing here detects skew
     * (`civictech.cell.wire.DEFAULT_ANNOUNCEMENT_SKEW_MILLIS` states that
     * limit); the allowance is a configured constant (`[DSC1-NV-03]`).
     *
     * Distinct from [REPLAY], which they are easily confused with because both
     * refuse an announcement that "arrived too late": [REPLAY] means this side
     * has already accepted an announcement at least as new from that identity,
     * and is a *statement about this receiver's history*. This one means the
     * signer itself declared the announcement stale, and holds even for a
     * counter this side has never seen.
     */
    EXPIRED,

    /**
     * Seam 1 announcement: the announcement's `portName` or its minting peer's
     * name is not well-formed UTF-16 — a surrogate code unit that is not part
     * of a high/low pair (DSC1; `computenet-l8y5`, discovered by the review of
     * `computenet-9qgg`).
     *
     * Such an announcement is **unencodable**, not merely unverifiable: the
     * canonical signing encoding (`civictech.identity.announce.canonicalBytes`)
     * refuses it rather than substituting `'?'`, because that substitution
     * collides distinct announcements and so lets one signature verify two.
     * Reachable from the network — the wire codec is kotlinx JSON, whose
     * escapes are per-UTF-16-code-unit, so a lone `\ud800` escape decodes
     * straight into `civictech.cell.wire.WireFrame.portName`
     * (`WirePortNameSurrogateReachabilityTest` measures exactly that).
     *
     * **Machine-distinguishable from [BAD_SIGNATURE] on purpose**, which is the
     * whole content of `computenet-l8y5`. A total verifier answers `false` for
     * an announcement it cannot encode, so before this constant the case
     * classified as "the signature does not verify" — honest, and wrong about
     * the cause. Nothing was *wrong with the crypto*: no signature over these
     * fields could ever verify, by anybody, because the bytes to verify against
     * do not exist. An operator reading `BAD_SIGNATURE` looks at keys and
     * clocks; the fact worth acting on is that a peer sent a structurally
     * impossible announcement.
     *
     * Also distinct from [MALFORMED_HELLO], which is the same *kind* of fact
     * (a structurally invalid frame) at the hello sub-protocol rather than at
     * an announcement. The two are kept apart for the reason the whole taxonomy
     * is per seam: [BoundaryDenial.seam] is `ADMISSION` for both, so the reason
     * is the only thing that says which crossing was refused.
     *
     * The record names the field, the offending index and the string's length —
     * never the string itself ([DSC1-OBS-05]): an ill-formed string rendered
     * into a log line is precisely the thing a reader cannot trust their
     * terminal about.
     */
    MALFORMED_ANNOUNCEMENT,
}

/**
 * One refused crossing (spec 40/43; the shape is settled by the epic): the
 * [seam] that refused, the [principal] it refused, the [exposure] it was
 * crossing, the [subject] (protocol id, or contract/method) it was refused
 * on, and the [reason].
 *
 * This is a **report**, never a payload carrier: the refused arguments travel
 * separately to the host, which sanitizes them per spec 23 R8 before anything
 * fans out. Nothing here may hold a live [Owned]/[Leased] handle.
 */
data class BoundaryDenial(
    val seam: BoundarySeam,
    /** The membrane `Exposure.externalName` the refused crossing was addressed to. */
    val exposure: String,
    /**
     * The peer this refusal is attributed to —
     * `civictech.cell.membrane.Principal.Peer.id`.
     *
     * Like [subject], this is **not one convention across the four seams**,
     * and a reader of an audit trail has to know which it is looking at:
     *
     * - seam 2 (`linkAuthority`) and seam 3 `PORT_PROTOCOL`/`PORT_API`
     *   outbound (`protocolAuthority`, `disclosure`): the **crossing's
     *   ambient principal** (`civictech.cell.membrane.currentPrincipal()`, i.e.
     *   the `CurrentPeer` stamp — for seam 2, the `LinkRequest.identity`).
     *   There `null` genuinely means `Principal.LocalTrusted`: an in-process
     *   crossing with no peer stamped.
     * - seam 3 `PORT_API` inbound (`integrity`): the
     *   `SignedDelta.mintingPeer` the refused envelope named — *not* the
     *   ambient principal, because what an integrity refusal is about is the
     *   peer that minted the delta. `null` here means **no peer could be
     *   named at all** (a `UNSIGNED` refusal carries no envelope to read one
     *   from), which is a distinct thing from "the crossing was local".
     *
     * `DeadLetters.boundaryDenial` renders a null as the literal
     * `LocalTrusted`, so on an `UNSIGNED` record read that word as
     * "unattributed", not as a claim about the transport.
     *
     * The record deliberately carries the [PeerId] rather than the membrane's
     * `Principal` ADT: this file sits at the `civictech.cell` root precisely
     * so that `membrane`, `port`, `protocol`, `link` and `host` all reach it
     * over edges the T10-C ratchet already pins, and `cell -> membrane` is not
     * one of them (`cell -> link`, which [PeerId] rides, is). A denial record
     * is not worth opening a new package cycle. The `AuthLevel` a [MIN_AUTH]
     * refusal turned on rides in [detail].
     */
    val principal: PeerId?,
    /**
     * What was refused, named per seam — the three seams that carry a
     * subject at all disagree on its shape, so this is not one convention:
     *
     * - seam 3 `PORT_PROTOCOL` (`protocolAuthority`, `MIN_AUTH`/`RATE`):
     *   `ProtocolId.name`.
     * - seam 3 `PORT_API` inbound (`integrity`, [civictech.cell.membrane.MediateProxy]):
     *   `contract#method` — the reflective `Method` is in scope at that seam.
     * - seam 3 `PORT_API` outbound (`disclosure`,
     *   [civictech.cell.membrane.DisclosurePolicy]): the contract's simple
     *   name **alone**, with no method — [civictech.cell.port.FanOutlet]'s
     *   `disclosureFilter` hot-path signature is arguments-only, and widening
     *   it to also carry a `Method` was declined as disproportionate to an
     *   audit field (`computenet-usd.1.4`).
     * - seam 2 `onLink` (`linkAuthority`): always `null` — a link rejection
     *   has no protocol/contract subject to name.
     */
    val subject: String?,
    val reason: DenialReason,
    /** Free-text specifics for the audit trail (the observed counter, the offending `AuthLevel`, the refusing policy). */
    val detail: String? = null,
)

/**
 * Where a [BoundaryDenial] and its refused arguments are reported. The single
 * implementation is `ManagedHost`'s wiring onto its own `DeadLetters`
 * ([BoundaryDenials.attachReporter]) — the sanitization of `deniedArgs` is
 * **inherited** from `DeadLetters.sanitizeForDeadLetter` (spec 23 R8, G-46),
 * never reimplemented here.
 */
fun interface BoundaryDenialReporter {
    fun report(denial: BoundaryDenial, deniedArgs: List<Any?>)
}

/**
 * The per-[civictech.cell.membrane.Exposure] accounting sink: a monotonic
 * [denialCount] plus a report hook, mirroring
 * `civictech.cell.host.DeadLetters.deadLetterCount`.
 *
 * **Where a test reads the counter** (the placement this task was asked to
 * decide and record): on this instance, reached from the membrane that owns
 * it — `composite.boundaryDenials["exposedOutlet"]!!.denialCount`. Not on the
 * host: the counter is *per boundary*, and one host may carry many membranes
 * whose refusals must stay distinguishable. A host-level aggregate in
 * `SupervisionAccounting` is deliberately **not** added — a denial is not a
 * supervision event, and the sanitized dead letter is already the host-level
 * evidence.
 *
 * `computenet-usd.6` added a host-wide *sum* beside that, not inside it:
 * `ManagedHost.boundaryDenialCount()`. It exists because refusals had to be
 * taken **out** of `deadLetterCount` (whose readers all treat that number as a
 * fault count) once the denial rate became remote-controlled, and an operator
 * holding only the host still needs somewhere to see them. This per-exposure
 * counter remains the authoritative one; that is its sum.
 */
class BoundaryDenialSink internal constructor(
    /** The `Exposure.externalName` this sink accounts for. */
    val exposure: String,
    private val owner: BoundaryDenials,
) {
    private val count = AtomicLong()

    /** Monotonic count of refusals at this boundary. Never decrements, never resets. */
    val denialCount: Long get() = count.get()

    /**
     * Accounts one refusal: increments [denialCount] and hands the record plus
     * the refused arguments to the attached [BoundaryDenialReporter], if any.
     *
     * [deniedArgs] are the arguments the refused crossing carried. They may
     * hold exclusives ([Owned]/[Leased]), and this method takes **exactly one**
     * of two mutually exclusive routes with them — never both:
     *
     * - **A reporter is attached** (the membrane is hosted). The arguments are
     *   handed on untouched, and the sole production reporter
     *   ([attachReporter]'s caller, `ManagedHost`) discharges them exactly once
     *   inside the host's spec-23-R8 sanitization (`Owned -> freeze()`,
     *   `Leased -> release()` + [Redacted]) — the process's one sanitizer, and
     *   on this path its one discharge site. Discharging here as well would be
     *   a *double* discharge and would degrade the dead letter's `Frozen` value
     *   to a `Redacted` marker, so this path deliberately does not. (A test
     *   reporter that only records the [BoundaryDenial] and ignores its
     *   arguments therefore leaves them live — that is the test's choice, made
     *   by attaching a reporter that does not sanitize.)
     * - **No reporter is attached** (a membrane never spawned onto a
     *   `ManagedHost`). There is no sanitizer downstream, so the exclusives are
     *   discharged here, by [dischargeRefusedArgs] — consume/release only, no
     *   `Frozen`/`Redacted` substitution and therefore no second sanitizer.
     *   `computenet-usd.1` left them live; that is a silent drop of an
     *   exclusive payload, which the standing AGENTS.md invariant forbids on
     *   *every* failure/suppression path, hosted or not (`computenet-usd.2.1`,
     *   BS-5, `[SEC1-23]`). What is lost with no host is the *record*, not the
     *   discharge.
     *
     * Either way the counter still moves and nothing throws: accounting a
     * denial must never itself be a failure path, so an argument some upstream
     * already consumed is tolerated rather than raised (see
     * [dischargeRefusedArgs]).
     *
     * A caller holding exclusives inside its own envelope type must surface
     * them before calling — `MediateProxy` unwraps `SignedDelta.payload`,
     * because neither this sink nor the host's sanitizer knows membrane types.
     *
     * Exactly-once discharge across *repeated* filter evaluation — the
     * disclosure-filter-called-twice hazard — was sibling task
     * `computenet-usd.2.2`'s, and it **landed with this one**: a suppressed
     * emission now evaluates its disclosure filter at most once
     * (`civictech.cell.port.FanOutlet.disclosureFilter`), so this method is
     * reached with the refused arguments exactly once per emission and every
     * further suppressed attempt arrives argument-less through
     * `FanOutlet.onRepeatSuppression`. Nothing on this seam relies on the
     * tolerance below to make that true; the tolerance covers an upstream that
     * consumed a wrapper before the refusal, not a second discharge of our own.
     *
     * A denial is **not a fault** (`[SEC1-29]`, BS-14): no supervision policy
     * is consulted, no escalation fires, no wave is minted or advanced, and no
     * source/tag continuity changes.
     */
    fun deny(
        seam: BoundarySeam,
        reason: DenialReason,
        principal: PeerId? = null,
        subject: String? = null,
        detail: String? = null,
        deniedArgs: List<Any?> = emptyList(),
    ): BoundaryDenial {
        val denial = BoundaryDenial(seam, exposure, principal, subject, reason, detail)
        count.incrementAndGet()
        val reporter = owner.reporter
        if (reporter == null) dischargeRefusedArgs(deniedArgs) else reporter.report(denial, deniedArgs)
        return denial
    }
}

/**
 * Discharges the exclusives a refusal is dropping when nothing downstream
 * will: [Owned] consumed, [Leased] released, containers walked — the landed
 * `Proxy.discharge` primitive (the `ManagedHost` refusal precedent, commit
 * `02ac610`), reused rather than reimplemented.
 *
 * **Discharge, not sanitization.** It performs no `Owned -> Frozen` /
 * `Leased -> Redacted` substitution and synthesizes no record: that rule
 * (spec 23 R8, G-46) has exactly one implementation in the repository,
 * `civictech.cell.host.DeadLetters.sanitizeForDeadLetter`, and this is
 * deliberately not a second one. It is only ever called where no sanitizer
 * runs at all — an unattached [BoundaryDenialSink], or a
 * `civictech.cell.membrane.MediateProxy` built with no sink — and never
 * alongside one.
 *
 * Tolerant per argument, because accounting a refusal may never itself throw
 * ([BoundaryDenialSink.deny]'s stated contract): a wrapper an upstream already
 * took or released has nothing left to discharge, and the remaining arguments
 * are still discharged after it.
 */
internal fun dischargeRefusedArgs(deniedArgs: List<Any?>) {
    deniedArgs.forEach { arg -> runCatching { Proxy.discharge(arg) } }
}

/**
 * A membrane's collection of per-exposure [BoundaryDenialSink]s, plus the one
 * reporter the hosting `ManagedHost` attaches ([attachReporter]).
 *
 * The reporter lives here rather than on each sink so that a sink created
 * *after* the host attached — a membrane that declares an exposure lazily —
 * still reports; there is no ordering constraint between exposure declaration
 * and spawn.
 */
class BoundaryDenials {
    private val sinks = ConcurrentHashMap<String, BoundaryDenialSink>()

    @Volatile
    internal var reporter: BoundaryDenialReporter? = null
        private set

    /** The sink for [exposure], created on first request. One instance per exposure, for this membrane's lifetime. */
    fun sinkFor(exposure: String): BoundaryDenialSink =
        sinks.computeIfAbsent(exposure) { BoundaryDenialSink(it, this) }

    /**
     * The sink for [exposure] if one was ever created; null for an exposure
     * that can carry no seam at all — a plain `flatten()` with no
     * `linkAuthority`. A **mediated** exposure allocates its sink at
     * declaration time whether or not its `BoundaryPolicy` currently declares
     * a predicate, so this is non-null there even before any adopter consults
     * it.
     */
    operator fun get(exposure: String): BoundaryDenialSink? = sinks[exposure]

    /** Every accounted exposure name — diagnostics and tests. */
    val exposures: Set<String> get() = sinks.keys.toSet()

    /** Refusals summed over every exposure of this membrane. Monotonic, like each [BoundaryDenialSink.denialCount]. */
    val denialCount: Long get() = sinks.values.sumOf { it.denialCount }

    /**
     * Wires every sink of this membrane — present and future — to [reporter].
     * Called once by the hosting `ManagedHost` at spawn; a membrane hosted
     * nowhere keeps a null reporter and simply counts.
     */
    internal fun attachReporter(reporter: BoundaryDenialReporter) {
        this.reporter = reporter
    }
}

/**
 * The narrow seam by which a hosting `ManagedHost` reaches a hosted cell's
 * boundary denial accounting (implemented by
 * `civictech.cell.membrane.CompositeCell`).
 *
 * Narrow on purpose: `DeadLetters` stays `internal` to `civictech.cell.host`
 * and is *not* exported wholesale — the host keeps sole control of dead-letter
 * emission and sanitization, and a membrane only ever hands it a record plus
 * the refused arguments.
 */
interface BoundaryDenialAccounting {
    val boundaryDenials: BoundaryDenials
}
