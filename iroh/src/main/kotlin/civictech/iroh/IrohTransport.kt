package civictech.iroh

import civictech.cell.BoundaryDenial
import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.host.IntakeClosedException
import civictech.cell.link.AuthLevel
import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.UnboundReason
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryMirrorCell
import civictech.cell.wire.denialReasonFor
import civictech.identity.Ed25519
import civictech.identity.anchor.decodeIdentityStatementToken
import civictech.identity.anchor.encodeIdentityStatementToken
import civictech.identity.fingerprint
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The iroh transport driver (DSC0, epic `computenet-egl`, feature
 * `computenet-egl.2`): the same peering `:wire`'s `WsTransport` establishes,
 * carried over an iroh QUIC stream instead of a WebSocket.
 *
 * Nothing about the *model* changes here, and that is the point. Frames from a
 * [BridgeEgressCell] go out as `DATA` on one sidecar link; inbound `DATA` is
 * handed — still encoded — to a bridge-hosted ingress
 * ([Peering.hostIngress]), so the sidecar's reader thread never runs framework
 * logic. The first `DATA` each way is a hello carrying the local mirror's
 * [CellRef]; receiving it wires announcements ([Peering.announceTo]) and the two
 * peers become one graph. The kernel is untouched: `:iroh -> :kernel`, never the
 * reverse (feature rule 2).
 *
 * ## The hello is a link-local grammar, and it asserts no identity at all
 *
 * The sidecar protocol has no text/binary split the way a WebSocket does
 * (`iroh/sidecar/PROTOCOL.md` §3: `DATA` is `DATA`), so the hello cannot be told
 * from a wire frame by its *kind*. It is told apart by its **position**: the
 * first `DATA` frame on a link is the hello and every later one is an opaque
 * `WireCodec` frame. `PROTOCOL.md` §3 guarantees per-link delivery order, which
 * is exactly the premise `WsTransport` takes from WebSocket message order, so
 * hello-before-announcements holds here for the same reason it holds there
 * (egl.2-D1). The bytes are new and are deliberately *not* `WsTransport`'s:
 * `[DSC1-HELLO-10]` freezes that line's bytes for that transport, and nothing
 * here may claim to speak it.
 *
 * Two line forms, told apart by their prefix (feature `computenet-5y8t.3`,
 * decision 5y8t.F3-D4), both UTF-8:
 *
 * - `IROH-HELLO1 <mirrorRef>[ <name>]` ([IrohTransport.HELLO_PREFIX]) — the
 *   form a side holding no identity statements sends, byte-for-byte what it
 *   sent before DSC4.
 * - `IROH-HELLO2 <mirrorRef> <claimedName> <statement>{1,8}`
 *   ([IrohTransport.HELLO2_PREFIX]) — the form a side whose
 *   `Peering.Side.credentials` hold statements sends: the name it claims and
 *   the anchor-signed statements vouching for it, each one
 *   `civictech.identity.anchor.encodeIdentityStatementToken`. No key token
 *   (the NodeId IS the key) and no nonce (the QUIC handshake is the proof of
 *   possession, below).
 *
 * The sender picks the form by what it holds, so every side configured before
 * DSC4 emits exactly the bytes it always did. **The break is loud and one-way,
 * as on `:wire`**: a pre-DSC4 side reading `IROH-HELLO2 ...` fails its
 * `IROH-HELLO1 ` prefix check and refuses `DenialReason.MALFORMED_HELLO`,
 * accounted and closed — never misread as the older line with extra tokens.
 *
 * ## Admission: the link is proven on the NodeId key, the binding names the identity
 *
 * Feature `computenet-egl.3` replaced egl.2-D4's interim, in which the hello
 * asserted a name and that name was the admission token. **The [KeyId] a
 * connection is proven on is
 * `fingerprint(Ed25519.publicKeyFromRaw(remoteNodeId))`** — the ed25519 public
 * key the sidecar reports as the remote endpoint of *this* QUIC connection
 * ([Session.remoteNodeId]: `SidecarLink.remoteNodeId` on an accepted link, the
 * dialled `peerNodeId` on a dialled one). Nothing a peer writes can move it.
 *
 * The identity is what this side's binding resolves that key to, **given what
 * the hello presented**: `Peering.Side.identityBinding.resolve(key, presented)`,
 * once per hello, with `presented` the decoded statements of an `IROH-HELLO2`
 * line and empty for `IROH-HELLO1`. Its `IdentityResolution.Bound` peer is the
 * identity stamped on every delivery and nothing else (feature
 * `computenet-376c`): this module derives a *key identifier* from key material
 * and never an identity. A key the binding resolves to
 * `IdentityResolution.Unbound` is refused at the hello with
 * `civictech.cell.wire.denialReasonFor(reason)` — `UNVOUCHED` or
 * `STATEMENT_EXPIRED`, the same table `:wire` reads — before the allowlist is
 * consulted; the allowlist then judges the resolved identity, never the key.
 *
 * **[Side.peer] is not written to the wire over this transport.** A name
 * token, on either form, is checked and never trusted: a claimed name equal to
 * the resolved identity is admitted, one that differs is refused
 * `DenialReason.ID_MISMATCH` — the `:wire` `[DSC1-HELLO-06]` shape. A token can
 * only ever CONFIRM the resolved identity; it can never supply one. So an
 * `IROH-HELLO2` line read by a side on the interim binding (which ignores
 * statements and resolves the key-derived name) is refused `ID_MISMATCH`, and
 * an `IROH-HELLO1` line read by an anchor-bound side is refused `UNVOUCHED`
 * (`UnboundReason.NO_STATEMENT`).
 *
 * ## Why [AuthLevel.Authenticated], and what it does not claim
 *
 * [bindAndAnnounce] fixes `fromPeerAuth = AuthLevel.Authenticated` as a
 * parameter at the admission decision. It appears in no frame and is read from
 * no frame (`BridgeIngressCell.peerAuth`, `[DSC1-HELLO-05]`).
 *
 * The argument: iroh's QUIC/TLS 1.3 handshake is a **proof of possession** of
 * the NodeId key, bound to this connection instance — the endpoint certificate
 * is self-signed under the NodeId key and the handshake signs the transcript, so
 * a link that came up is a link whose far end demonstrated the private half.
 * That is the same thing `:wire`'s `HELLO2`/`PROOF` exchange establishes, done
 * by the transport instead of by a frame.
 *
 * The trust assumption it rests on, stated so it can be attacked: **the sidecar
 * is part of this side's trusted computing base.** It is a local child process
 * reached over a loopback socket, and this side believes the NodeId it reports
 * for a link the way a `:wire` side believes the peer certificate its TLS
 * library reports. Neither side verifies its own crypto library.
 *
 * `[DSC1-NV-01]` (stolen key) stays **explicitly unverified**: an attacker
 * holding a peer's iroh secret key is that peer, here as everywhere. Key-bound
 * admission closes self-assertion, not key theft.
 *
 * No kernel consumer makes `Authenticated` unsound without `Side.credentials`:
 * the level flows into `Principal.Peer` and `minAuth` floors only, and
 * `PeerAuthPolicy.RequireAuthenticated`'s credentials requirement is a
 * construction-time demand on a *side*, which this transport does not set.
 *
 * ## Scope
 *
 * One sidecar child process per transport instance (egl.2-D2): [listen] and
 * [connect] each spawn their own and own its lifetime.
 *
 * A peering **survives its link**. A dialled [IrohConnection] holds a
 * *succession* of [Session]s, one per link instance: a link that goes down
 * unplanned is re-dialled on an injectable backoff schedule
 * ([DEFAULT_RECONNECT_BACKOFF]), and the link that replaces it gets a fresh
 * Session with a fresh mirror. The Session that went down stays down — its
 * mirror is detached for good, and nothing re-opens it (computenet-dqy.14; see
 * [Session]). A close this side *asked* for ([IrohConnection.sever],
 * [IrohConnection.close]) is never re-dialled, which is `WsTransport`'s
 * planned-close discipline.
 */
object IrohTransport {

    /**
     * The statement-free hello line's prefix, trailing space included. A side
     * whose credentials hold no statements sends exactly
     * `IROH-HELLO1 <mirrorRef>`; the ref is a [UUID] in its canonical form,
     * which contains no space.
     *
     * A hello this side *reads* may carry one trailing token — a foreign or
     * older client asserting a name — which the split still isolates
     * unambiguously. That token is only ever compared against the identity the
     * connection's own key resolved to; see [Session.onHello]. The line
     * presents no statements, so a binding that requires them resolves it
     * `Unbound`.
     *
     * The version digit is part of the token rather than a separate field
     * because the whole grammar is one line: [HELLO2_PREFIX] is a different
     * prefix and cannot be misread as this one with extra tokens.
     */
    const val HELLO_PREFIX: String = "IROH-HELLO1 "

    /**
     * The statement-carrying hello line's prefix, trailing space included
     * (feature `computenet-5y8t.3`, decision 5y8t.F3-D4). The grammar is
     *
     * ```
     * IROH-HELLO2 <mirrorRef> <claimedName> <statement>{1,8}
     * ```
     *
     * split strictly on single spaces (no trim, no limit): a canonical [UUID]
     * mirror ref, a non-empty claimed name, and between one and
     * [MAX_HELLO_STATEMENTS] tokens each of which
     * `civictech.identity.anchor.decodeIdentityStatementToken` accepts. Every
     * deviation is refused `DenialReason.MALFORMED_HELLO` with a detail that
     * names the shape and a token index, never the bytes.
     *
     * A side sends this form exactly when `Peering.Side.credentials` holds at
     * least one statement; otherwise it sends [HELLO_PREFIX]'s.
     */
    const val HELLO2_PREFIX: String = "IROH-HELLO2 "

    /**
     * The most statements one [HELLO2_PREFIX] line may carry (feature
     * `computenet-5y8t.3`'s breakdown: 1..8 per line on both transports). A
     * bound on attacker-chosen verification work per hello, not a semantic
     * limit: a peer holds statements from a handful of issuers at most.
     */
    const val MAX_HELLO_STATEMENTS: Int = 8

    /**
     * The production re-dial backoff: fixed doubling from 1s, capped at 30s,
     * retrying forever. `attempt` is 0-based — the delay *before* the
     * (attempt+1)-th re-dial.
     *
     * The values are `WsTransport.DEFAULT_RECONNECT_BACKOFF`'s (M10.3), and the
     * seam is the same T12 seam: a test injects a near-zero schedule instead of
     * paying wall-clock delays. The *type* is declared here rather than imported
     * from `:wire` deliberately — this module's whole dependency claim is
     * `:iroh -> :kernel` (feature rule 2), and taking a `:wire` dependency for
     * one lambda type would couple two transports that have no business on each
     * other's classpath. The cost is one duplicated four-line lambda; the price
     * of the alternative is a module edge.
     */
    val DEFAULT_RECONNECT_BACKOFF: (attempt: Int) -> Long = { attempt ->
        // guard overflow on a long-lived failing connection: cap the shift itself
        (1_000L shl attempt.coerceAtMost(20)).coerceAtMost(30_000L)
    }

    /**
     * How many consecutive links may come **up** and go **down without ever
     * being admitted** before this side stops re-dialling (computenet-4gzr).
     *
     * ## The case it bounds, and why nothing else could
     *
     * A hello refused at the listening side's allowlist is not a failed dial.
     * The `DIAL` succeeds, `LINK_UP` arrives, the hello goes out — and only then
     * does the peer refuse it by closing the link. `PROTOCOL.md` carries no
     * refusal reason, so the resulting `LINK_DOWN` is byte-identical to a
     * transport drop's, and [IrohConnection.retire] treated it as one. Worse,
     * because the dial itself succeeded, each re-dial loop terminated
     * immediately and the *next* refusal started a fresh loop at `attempt = 0`:
     * the schedule never escalated past its first delay, so a refused peer
     * re-dialled at a fixed ~1s forever, charging the refusing side one link
     * accept plus one hello parse per second, indefinitely.
     *
     * The case is nonetheless distinguishable **locally**, which is why this
     * needs no wire change: `LINK_UP` followed by `LINK_DOWN` with
     * [Session.peered] never true. Carrying a refusal reason on the wire is the
     * other remedy the filing bead offers and is deliberately not taken here —
     * it is a `PROTOCOL.md` change, which belongs to the open sibling
     * `computenet-ey4v`, and AGENTS.md holds this module to wire compatibility
     * absent a spec requirement.
     *
     * ## Why five, and what it does not bound
     *
     * The number is a cost ceiling, not a semantic threshold: it is the total
     * accept-plus-hello-parse work one refused peer may charge a listener per
     * connection handle, and any small number does that. Five leaves room for
     * the handful of unadmitted opens a *transient* fault can produce — a link
     * torn down mid-handshake, a peer restarting between the dial and the hello
     * — before the connection concludes it is being refused rather than
     * unlucky. An **admitted** link resets the count to zero
     * ([IrohConnection.unadmittedOpens]), so a healthy peering that flaps for
     * hours never approaches it.
     *
     * **The listening side's cost is this limit plus at most one.** The re-dial
     * loop re-arms itself when it finds no live session, and that check can win
     * the race against the `LINK_DOWN` that would have set the give-up — so one
     * further link can be in flight when the run completes. It is exactly one:
     * the single-flight [IrohConnection.reconnecting] guard admits no second
     * loop, and the in-flight link's own `LINK_DOWN` finds the give-up already
     * set. So a refused peer costs a listener between `limit` and `limit + 1`
     * link-accept-plus-hello-parses, and never more —
     * [IrohConnection.unadmittedOpens] can likewise finish one above the limit,
     * because the in-flight link still counts itself when it goes down.
     *
     * It does **not** bound a dial that never establishes at all — an
     * unreachable or absent peer. That throws inside the re-dial loop, produces
     * no link and no accept for the peer to pay for, and still retries forever
     * on purpose, exactly as `:wire`'s reconnect into an unbound port does
     * (`WsReconnectRefusedTest`).
     */
    const val REFUSED_DIAL_LIMIT: Int = 5

    /**
     * A [Peering.Side] whose credentials no [HELLO2_PREFIX] line can carry:
     * more than [MAX_HELLO_STATEMENTS] statements, a name that is empty or
     * holds a space, or a statement `encodeIdentityStatementToken` refuses (an
     * unsigned one). Thrown by [listen] and [connect] **before a sidecar is
     * spawned** (computenet-5y8t.6).
     *
     * Why at start rather than only in `Session.hello`, which refuses the same
     * credentials and still does: a dialler's `hello` runs inside the re-dial
     * loop, which would log and retry it forever; and a listener's runs from
     * `onHello` on the `SidecarClient` reader thread, whose catch-all fails
     * every link on the sidecar — so one admitted hello would have taken down
     * every peering the listener held. A configuration fault is refused at the
     * call that configured it instead.
     *
     * Credentials with **no** statements are never refused here: they send
     * [HELLO_PREFIX]'s line, which carries no name.
     *
     * `WsTransport.UnsendableHelloCredentialsException` is the same refusal on
     * `:wire`, declared twice by name because this module does not depend on
     * `:wire` (see [DEFAULT_RECONNECT_BACKOFF] for the same trade).
     */
    class UnsendableHelloCredentialsException internal constructor(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    /** Refuses [side] with [UnsendableHelloCredentialsException] when `Session.hello` could not send its credentials. */
    private fun requireSendableHelloCredentials(side: Peering.Side) {
        val credentials = side.credentials ?: return
        val statements = credentials.statements
        if (statements.isEmpty()) return
        val name = credentials.peerId.name
        if (statements.size > MAX_HELLO_STATEMENTS) {
            throw UnsendableHelloCredentialsException(
                "credentials for $name hold ${statements.size} statements; an IROH-HELLO2 line carries at most " +
                    "$MAX_HELLO_STATEMENTS",
            )
        }
        if (name.isEmpty() || ' ' in name) {
            throw UnsendableHelloCredentialsException(
                "credentials name '$name' cannot be an IROH-HELLO2 name token (empty or contains a space)",
            )
        }
        statements.forEachIndexed { index, statement ->
            try {
                encodeIdentityStatementToken(statement)
            } catch (e: IllegalArgumentException) {
                throw UnsendableHelloCredentialsException(
                    "credentials for $name: statement $index cannot be an IROH-HELLO2 token: ${e.message}",
                    e,
                )
            }
        }
    }

    /**
     * Serve peerings on a fresh sidecar. Returns once the sidecar is listening;
     * [IrohListener.nodeId] and [IrohListener.addresses] are what a dialler
     * needs ([connect]'s two first arguments).
     *
     * [binary] is the sidecar executable — in tests, `SidecarBinary.orSkip()`.
     * [sidecarArgs] are passed to the child verbatim (see [SidecarProcess.spawn]):
     * pinning `--secret-key` and `--bind-addr` is what lets a listener come back
     * at the same endpoint after its process died, which is how an *unplanned*
     * drop is staged. [SidecarProcess.spawn] may append `--relay-url` on top of
     * [sidecarArgs] when the JVM system property `iroh.relay.url` is set and
     * [sidecarArgs] names neither `--offline` nor `--relay-url` itself — see
     * its KDoc.
     *
     * @throws UnsendableHelloCredentialsException before any sidecar is
     *   spawned, when [side]'s credentials cannot be sent in a hello.
     */
    fun listen(
        side: Peering.Side,
        binary: Path,
        timeout: Duration = 30.seconds,
        stderrSink: (String) -> Unit = {},
        sidecarArgs: List<String> = emptyList(),
    ): IrohListener {
        requireSendableHelloCredentials(side)
        val process = SidecarProcess.spawn(binary, stderrSink = stderrSink, args = sidecarArgs)
        val listener = try {
            IrohListener(process, process.connect(timeout), side)
        } catch (e: Throwable) {
            process.close()
            throw e
        }
        try {
            listener.start(timeout)
        } catch (e: Throwable) {
            listener.close()
            throw e
        }
        return listener
    }

    /**
     * Dial [peerNodeId] on a fresh sidecar and open one peering over the
     * resulting link. [peerAddresses] are the peer's `LISTENING` addresses
     * (`ADD_PEER`, `PROTOCOL.md` §3) — offline dialling, no discovery service.
     *
     * Returns once the link is up and this side's hello has been sent; the
     * peer's hello, admission and announcements follow asynchronously on the
     * sidecar's reader thread.
     *
     * The returned connection **outlives its link**: an unplanned `LINK_DOWN`
     * re-dials [peerNodeId] on [backoff], each re-dial bounded by
     * [redialTimeout]. Both are seams for tests — a near-zero schedule and a
     * short dial timeout make reconnect observable without wall-clock waits.
     *
     * The one thing it does not do forever is re-dial a peer that keeps
     * **refusing** it: [refusedDialLimit] consecutive links that came up and
     * went down without ever being admitted end the re-dialling for good
     * ([IrohConnection.abandonedAfterRefusals]). See [REFUSED_DIAL_LIMIT].
     *
     * @throws UnsendableHelloCredentialsException before any sidecar is
     *   spawned, when [side]'s credentials cannot be sent in a hello.
     */
    fun connect(
        side: Peering.Side,
        peerNodeId: ByteArray,
        peerAddresses: List<String>,
        binary: Path,
        timeout: Duration = 30.seconds,
        stderrSink: (String) -> Unit = {},
        backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF,
        redialTimeout: Duration = timeout,
        sidecarArgs: List<String> = emptyList(),
        refusedDialLimit: Int = REFUSED_DIAL_LIMIT,
    ): IrohConnection {
        requireSendableHelloCredentials(side)
        val process = SidecarProcess.spawn(binary, stderrSink = stderrSink, args = sidecarArgs)
        return try {
            val client = process.connect(timeout)
            client.addPeer(peerNodeId, peerAddresses, timeout)
            IrohConnection(process.asSidecar(), client, side, peerNodeId, backoff, redialTimeout, refusedDialLimit)
                .also { it.openLink(timeout) }
        } catch (e: Throwable) {
            process.close()
            throw e
        }
    }

    /**
     * One iroh **endpoint**: a single sidecar process and a single
     * [SidecarClient] that listens, accepts inbound links and opens outbound
     * ones (F3-D2, ktn1l-D11). Returns once the sidecar is listening, exactly
     * as [listen] does.
     *
     * This is a **fourth** entry point beside [listen] and [connect], not a
     * replacement: both of those keep spawning their own sidecar and are
     * untouched. The difference that matters is identity. [listen] plus
     * [connect] in one JVM is two endpoints with two NodeIds, so the key a peer
     * discovers is not the key that dials it; a discovery-driven peering needs
     * the advertised key, the accepting key and the dialling key to be one key
     * ([IrohNode]).
     *
     * @throws UnsendableHelloCredentialsException before any sidecar is
     *   spawned, when [side]'s credentials cannot be sent in a hello.
     */
    fun node(
        side: Peering.Side,
        binary: Path,
        timeout: Duration = 30.seconds,
        stderrSink: (String) -> Unit = {},
        sidecarArgs: List<String> = emptyList(),
    ): IrohNode {
        requireSendableHelloCredentials(side)
        val process = SidecarProcess.spawn(binary, stderrSink = stderrSink, args = sidecarArgs)
        val node = try {
            IrohNode(process.asSidecar(), process.connect(timeout), side)
        } catch (e: Throwable) {
            process.close()
            throw e
        }
        try {
            node.start(timeout)
        } catch (e: Throwable) {
            node.close()
            throw e
        }
        return node
    }

    /**
     * One peer link: bridge cells and mirroring on the local side, `DATA` frames
     * on the wire. The direct analogue of `WsTransport.Session`, and deliberately
     * the same shape — hello, admission, mirror, ingress, announcements, in that
     * order — because the whole claim of this feature is that only the transport
     * changed.
     *
     * A Session is built per **link**, never reused across links. That is the
     * whole difference from `WsTransport.Session`, which keeps *one* Session
     * across every reconnect and re-opens its state on each re-hello: here the
     * state a reconnect would have to reset does not survive to be reset, and
     * [IrohConnection] holds a succession of Sessions instead — one per link
     * instance, each retired for good by [onDown].
     *
     * ## The mirror is per link INSTANCE (computenet-dqy.14)
     *
     * [RegistryMirrorCell.peer]'s KDoc argues the late bind safe from four
     * premises; each has an analogue here and none is inherited by assertion.
     * All four are stated **about one link instance**, which is why they survive
     * reconnect unchanged: a re-dial creates a different Session over a different
     * link id, sharing no mirror, no egress and no ingress with the one it
     * replaces, so there is no cross-instance ordering left for them to be about
     * (see [IrohConnection.openLink] for the one cross-instance ordering there
     * is, and why it is not a premise of any of them):
     *
     * 1. **Our hello, carrying this instance's mirror ref, is written before we
     *    announce anything.** On a dialled link it is the first `DATA` we send
     *    ([openLocalHello] at the end of [IrohConnection.openLink], on the first
     *    dial and on every re-dial alike); on an accepted link it is
     *    sent from [onHello], still before [bindAndAnnounce] — see [onHello] for
     *    why the accepting side waits.
     * 2. **The peer cannot address this mirror before it has read that hello**,
     *    because the ref exists nowhere else: it is minted in [hello] and
     *    published to nobody but this link.
     * 3. **The peer announces only while handling our hello**, which is strictly
     *    earlier in our→peer order than any frame it sends afterwards.
     * 4. **Per-link delivery preserves order** (`PROTOCOL.md` §3), and
     *    [SidecarClient]'s single reader thread dispatches one link's messages in
     *    arrival order. So [onHello] runs, and returns, before [onData] can route
     *    a single frame — [ingress] does not exist until it does.
     *
     * On top of those, and independently of all of them: a frame arriving while
     * [ingress] is null is dropped and counted on [preHelloDrops], so a refused
     * hello leaves nothing routable and the admission decision cannot be raced by
     * a frame that beats it.
     *
     * ### A link the [HelloGate] closes quietly (ktn1l-D12)
     *
     * A [Verdict.CloseQuietly] closes a link **after** admission and **before**
     * this side's hello and announcement, which is a point none of the four
     * premises above had to be stated about. Taken in order:
     *
     * - Premise 1 is untouched on an **accepted** link, because on that path
     *   neither our hello nor any announcement is ever written: [admitAndBind]
     *   returns before [openLocalHello], so this side mints no mirror at all
     *   and there is no ref for a peer to address.
     * - On a **dialled** link our hello was already written, at the end of
     *   [IrohConnection.openLink] — so premise 1 holds there for the ordinary
     *   reason. What does not follow is any announcement: [bindAndAnnounce] is
     *   never reached, so no `announceTo` sweep starts and no `Remote` location
     *   is published for this link. The peer may therefore hold our mirror ref
     *   and address it; frames it sends arrive with [ingress] still null and
     *   take the counted drop path above.
     * - The mirror [hello] minted on that dialling side is detached by [onDown]
     *   when the close lands, exactly as on any other drop — a quiet close is a
     *   different *reason* for a link to end, never a different lifecycle.
     *
     * So the quiet close adds one state — "our hello sent, nothing announced,
     * link closing" — which is a strict prefix of the ordinary dialled path and
     * needs no premise the ordinary path does not already have.
     *
     * A link that goes down detaches its mirror **permanently** ([onDown]); there
     * is no way to re-open one, so a frame the sidecar had already staged for a
     * dead link is refused at a shut gate rather than re-installing a
     * `LocationRegistry.Remote` for a ref the peer may since have dropped.
     *
     * ## What happens if a link's send queue is full
     *
     * The sidecar bounds a link's send queue at 256 messages and **refuses**
     * rather than waits (`PROTOCOL.md` §2/§3, computenet-3gij, merged as
     * `4679de93c`): a `DATA` arriving with 256 frames already outstanding on that
     * link is answered with `ERROR` **on that link** and is **not sent**, while
     * the host connection stays responsive throughout — `GET_ID`, `CLOSE_LINK`
     * and `SHUTDOWN` still answer, on every link including the flooded one. So
     * the failure mode is a lost frame on one link, never a wedged sidecar.
     *
     * This Session does not attempt to recover from such a refusal, and that is a
     * deliberate limit rather than an oversight: **`computenet-ey4v` is the open
     * residual.** An `ERROR` carries a kind byte, a link id and a UTF-8 reason
     * and no sequence number, while an *accepted* `DATA` is answered with nothing
     * at all — so a host with more than one `DATA` outstanding learns that *one*
     * frame on the link was refused and never *which one*, and a blind resend
     * would reorder the link. What a host is REQUIRED to do about that is
     * unsettled and is `computenet-ey4v`'s to settle; nothing here forecloses any
     * of its candidate remedies (this file encodes no frame identifier of its own
     * and alters no `DATA` header).
     *
     * What this Session does instead is take the one route `PROTOCOL.md` already
     * sanctions: **avoid the refusal.** [SidecarLink.send] on an accepted
     * (inbound) link waits for the dialler's first frame before sending (§3,
     * `LINK_UP`), which is where an unadopted stream's queue would otherwise fill
     * with no consumer at all; and this side's own traffic on a healthy peering
     * is a hello plus a catch-up burst bounded by the local registry, both of
     * which the peer is actively draining. A refusal that happens anyway is
     * reported through [IrohListener.linkErrors] / [IrohConnection.linkErrors]
     * rather than absorbed.
     */
    internal class Session(
        private val side: Peering.Side,
        /**
         * The 32 raw ed25519 public-key bytes the sidecar reports as the remote
         * endpoint of this link — `SidecarLink.remoteNodeId` on an accepted
         * link, the `peerNodeId` this side dialled on a dialled one (iroh
         * guarantees the dialled id is the endpoint reached). Both production
         * sites hold it before any `DATA` can arrive.
         *
         * This, and nothing a peer writes, is what [admissionKey] is derived
         * from.
         */
        private val remoteNodeId: ByteArray,
        private val send: (ByteArray) -> Unit,
        private val refuse: () -> Unit,
        /**
         * Seam-1 accounting for a refused hello (spec 40/43 seam 1, `[SEC1-07]`),
         * supplied by the caller for the reason `WsTransport.Session`'s is: a
         * refused hello closes its link, the listener drops the Session on the
         * resulting `LINK_DOWN`, and a sink owned by the Session would be
         * discarded together with the very refusal it recorded. [IrohListener]
         * therefore allocates one and hands it to every Session it opens.
         */
        private val admissionSink: BoundaryDenialSink = BoundaryDenials().sinkFor("hello"),
        /**
         * The policy consult on the hello path, default [HelloGate.ADMIT_ALL] —
         * so every site that does not pass one keeps byte-for-byte today's path
         * ([admitAndBind]). @see HelloGate
         */
        private val gate: HelloGate = HelloGate.ADMIT_ALL,
        /**
         * Which way this link was opened, handed to [gate] because the
         * mutual-dial tie-break is a decision *about* the direction (aas-D7).
         * The default is the listener's, [LinkDirection.INBOUND];
         * [IrohConnection.openLink] passes [LinkDirection.OUTBOUND].
         */
        private val direction: LinkDirection = LinkDirection.INBOUND,
        /**
         * How a [Verdict.CloseQuietly] closes the link. Defaults to [refuse],
         * which is the same physical close — the parameter exists so a dialling
         * [IrohConnection] can mark the close as blame-free before it happens,
         * since the `LINK_DOWN` that follows is indistinguishable from any
         * other on the wire (ktn1l-D13, the same problem
         * [IrohConnection.closeRequested] solves for a requested close).
         */
        private val closeQuietly: () -> Unit = refuse,
        /**
         * Invoked with the resolved identity the instant this link is bound and
         * announced — the one moment "admitted" becomes true. A node's link
         * registry (ktn1l-D14) needs that edge; nothing else reads it, and the
         * default makes every existing site unchanged.
         */
        private val onAdmitted: (PeerId) -> Unit = {},
    ) {
        /**
         * This side's announcement signer is borrowed from the `Peering.Side`,
         * exactly as `WsTransport.Session` borrows it — null on a side with no
         * signing configuration, which encodes byte-identically to an unsigned
         * frame.
         */
        val egress = BridgeEgressCell(signer = side.announcementSigner)

        /**
         * This link's admission key: the [KeyId] fingerprinted from
         * [remoteNodeId]'s ed25519 public key, computed **once per connection**
         * and held for this Session's whole life.
         *
         * Once, not per frame, and the cost is why it is spelt out here.
         * [Ed25519.publicKeyFromRaw] forces eager Edwards-point validation
         * because the JDK's `KeyFactory` for EdDSA does not validate at parse
         * time — it stores the keybits and decompresses on first `Signature`
         * use. Measured at ~30µs against ~1.1µs for a parse that skips it
         * (~28x). That is nothing once per QUIC connection, which is what
         * bind-once admission means; it would be a real per-message cost if
         * anything called it per delivery. Nothing does: [onHello] reads this
         * value and [bindAndAnnounce] carries the result onto the ingress.
         *
         * A [Result] rather than a throw, because a NodeId that is not a valid
         * key is a **refusal** ([DenialReason.MALFORMED_HELLO]) and not a
         * crash — the sidecar reporting unusable bytes is a contract violation
         * this side accounts for rather than propagates.
         */
        private val admissionKey: Result<KeyId> by lazy(LazyThreadSafetyMode.NONE) {
            runCatching { fingerprint(Ed25519.publicKeyFromRaw(remoteNodeId)) }
        }

        /** @see Session — minted by [hello], retired for good by [onDown]. */
        @Volatile
        private var mirror: RegistryMirrorCell? = null

        @Volatile
        private var ingress: Propagate<ByteArray>? = null

        /**
         * Whether the first `DATA` on this link has arrived. The grammar is
         * positional (see [IrohTransport]'s KDoc), so this is what says whether
         * the next frame is a hello or wire traffic — and, once a hello has been
         * refused, what keeps every later frame on the drop path.
         */
        @Volatile
        private var helloSeen = false

        @Volatile
        private var announcement: AutoCloseable? = null

        /**
         * Frames dropped because no admitted hello had installed an [ingress] yet
         * — the `WsTransport.Session.preHelloDrops` analogue.
         *
         * On this transport the *first* frame is the hello by construction, so
         * what this counts is the frames that follow a hello which was **not**
         * admitted: malformed, unparseable, or refused at [Peering.Side.allow].
         * The link is closed on every one of those paths, but closing is
         * asynchronous — the peer may already have written more — and those
         * frames have nowhere to route. They are dropped, exactly as before, and
         * now counted rather than silent.
         */
        private val preHelloDropCount = AtomicLong()
        val preHelloDrops: Long get() = preHelloDropCount.get()

        /** @see admissionSink */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * The last hello refusal this link recorded — its [DenialReason], the
         * peer it was attributed to and its detail — kept for the reason
         * `WsTransport.Session.lastAdmissionDenial` is: the sink is
         * reporter-less on this seam, so without this the *reason* a hello was
         * refused would be observable nowhere. One record, not a log: every
         * refusal closes the link, so there is at most one worth reading.
         */
        @Volatile
        var lastAdmissionDenial: BoundaryDenial? = null
            private set

        /** This link instance's mirror ref, once [hello] has minted it. */
        val mirrorRef: CellRef? get() = mirror?.ref

        /**
         * This link instance's mirror itself — the cell whose gate [onDown] shuts
         * for good. Exposed so that a *retired* instance's fence can be checked
         * directly (`RegistryMirrorCell.refusedAnnouncements` counts what the
         * shut gate refuses), which is the only way to tell a mirror that is
         * detached from one that is merely unused.
         */
        internal val mirrorCell: RegistryMirrorCell? get() = mirror

        /** True once an admitted hello has installed this link's ingress. */
        val peered: Boolean get() = ingress != null

        /**
         * The identity this link was bound to, once [bindAndAnnounce] has run —
         * null on every link that never got that far.
         *
         * Read so that a node can say *who* a live link carries without reading
         * the mirror (ktn1l-D12): the mirror is a cell whose `peer` is written
         * for the registry's benefit, and a reader outside this file has no
         * business reaching into it.
         */
        @Volatile
        var attributedPeer: PeerId? = null
            private set

        init {
            egress.outlet.subscribe(
                Use.fixed(
                    object : Propagate<ByteArray> {
                        override fun propagate(value: ByteArray) {
                            try {
                                send(value)
                            } catch (e: Exception) {
                                // A dead link noticed before LINK_DOWN reached us:
                                // unpublish now so later sends take the park fast
                                // path, and signal "destination unavailable" the way
                                // a closed intake does — the registry parks THIS
                                // invocation too. Same branch, same reasons, as
                                // WsTransport.Session's.
                                side.registry.unpublishRemotes(via = egress)
                                throw IntakeClosedException(egress.ref)
                            }
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        /**
         * Mint this link instance's mirror and send the hello that names it, once.
         *
         * Called directly by the **dialler** the instant its link is up: its
         * hello is its first frame, which is also what adopts the QUIC stream at
         * the accepting sidecar (`PROTOCOL.md` §3). The **accepting** side calls
         * it from [onHello] instead — see there.
         */
        fun openLocalHello() {
            if (mirror != null) return
            send(hello())
        }

        /**
         * The line form is picked by what this side HOLDS, never configured
         * separately: credentials carrying statements send [HELLO2_PREFIX]'s
         * form with the credentials' name and every statement as a token;
         * anything else sends `IROH-HELLO1 <mirrorRef>` exactly as before DSC4.
         * `side.peer` is never written — over iroh the connection's NodeId
         * already carries the key, so a bare name would be an assertion nobody
         * may act on (see the class KDoc).
         *
         * Credentials that could only produce a line the receiving side must
         * refuse — more than [MAX_HELLO_STATEMENTS] statements, or a name that
         * is empty or holds a space — fail here, loudly, rather than being
         * truncated into a different claim. [listen] and [connect] refuse the
         * same credentials before a sidecar exists
         * ([UnsendableHelloCredentialsException]); these checks stay as defence
         * in depth for a Session built directly.
         */
        private fun hello(): ByteArray {
            val credentials = side.credentials
            val statements = credentials?.statements.orEmpty()
            if (credentials != null && statements.isNotEmpty()) {
                val name = credentials.peerId.name
                check(statements.size <= MAX_HELLO_STATEMENTS) {
                    "credentials for $name hold ${statements.size} statements; an IROH-HELLO2 line carries at most " +
                        "$MAX_HELLO_STATEMENTS"
                }
                check(name.isNotEmpty() && ' ' !in name) {
                    "credentials name '$name' cannot be an IROH-HELLO2 name token (empty or contains a space)"
                }
                // Tokens are computed before the mirror is minted, so a statement
                // the codec refuses leaves no mirror behind.
                val tokens = statements.map { encodeIdentityStatementToken(it) }
                val fresh = Peering.spawnMirror(side, toPeer = egress)
                mirror = fresh
                val line = buildString {
                    append(HELLO2_PREFIX).append(fresh.ref.id).append(' ').append(name)
                    tokens.forEach { append(' ').append(it) }
                }
                return line.toByteArray(StandardCharsets.UTF_8)
            }
            val fresh = Peering.spawnMirror(side, toPeer = egress)
            mirror = fresh
            return (HELLO_PREFIX + fresh.ref.id).toByteArray(StandardCharsets.UTF_8)
        }

        /**
         * One inbound `DATA`. Routed into the ingress once an admitted hello has
         * installed one; read as the hello when it is the first frame on this
         * link; dropped and counted otherwise.
         */
        fun onData(payload: ByteArray) {
            val current = ingress
            if (current != null) {
                current.propagate(payload)
                return
            }
            if (!helloSeen) {
                helloSeen = true
                onHello(payload)
                return
            }
            preHelloDropCount.incrementAndGet()
        }

        /**
         * **The single admission point.** Every trust decision this transport
         * makes about a peer is taken here, between parsing the hello and
         * [Peering.Side.admits]; no ingress, no bound mirror and no announcer
         * exists on any path that does not reach the end of this method.
         *
         * The accepting side sends **its own** hello from here rather than at
         * link-up, for two independent reasons:
         *
         * - `SidecarLink.send` on an accepted link waits for the dialler's first
         *   frame, and that frame is dispatched by the very reader thread a
         *   link-up handler runs on — so sending there would deadlock this side
         *   against itself until the wait expired. By the time [onHello] runs,
         *   the dialler has spoken by definition.
         * - Our hello must precede anything else we write on this link, because
         *   the peer reads its first `DATA` as a hello. Sending it here, before
         *   [bindAndAnnounce], is what guarantees that.
         *
         * A **refused** hello sends nothing at all: this side never mints a
         * mirror for a peer it will not talk to, and the link is closed.
         *
         * Both line forms reach the same admission order: the key from the
         * connection, ONE resolution of it given what the line presented, the
         * claimed-name comparison, and only then the allowlist ([admitted]).
         */
        private fun onHello(payload: ByteArray) {
            val text = String(payload, StandardCharsets.UTF_8)
            when {
                text.startsWith(HELLO2_PREFIX) -> onHello2(text)
                text.startsWith(HELLO_PREFIX) -> onHello1(text)
                else -> refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    null,
                    "first frame on this link refused: ${payload.size} bytes that do not open with a hello prefix",
                )
            }
        }

        /** `IROH-HELLO1 <mirrorRef>[ <name>]` — presents no statements. @see HELLO_PREFIX */
        private fun onHello1(text: String) {
            val parts = text.removePrefix(HELLO_PREFIX).trim().split(" ", limit = 2)
            val key = linkKeyOrRefuse() ?: return
            // Presents nothing: this line form carries no statements, so a
            // binding that needs them resolves it Unbound (NO_STATEMENT).
            val bound = resolveOrRefuse(key, emptyList()) ?: return
            val peer = bound.peer
            val peerMirrorRef = runCatching { UUID.fromString(parts[0]) }.getOrNull()
            if (peerMirrorRef == null) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    peer,
                    "hello refused: its first token is not a mirror ref UUID",
                )
                return
            }
            // A trailing token can only CONFIRM the resolved identity, never
            // supply one: equal is redundant and admitted, different is refused
            // (`[DSC1-HELLO-06]`'s shape, recording both values by name).
            val asserted = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (asserted != null && asserted != peer.name) {
                refuseClaimMismatch(asserted, peer)
                return
            }
            admitAndBind(bound, key, peerMirrorRef)
        }

        /**
         * `IROH-HELLO2 <mirrorRef> <claimedName> <statement>{1,8}`. @see HELLO2_PREFIX
         *
         * The whole line is parsed before anything else is decided, so a
         * malformed line is refused on its shape alone and never reaches the
         * binding. The claimed name is compared as a string with the resolved
         * identity's name — no `PeerId` is built from attacker-chosen bytes.
         */
        private fun onHello2(text: String) {
            val tokens = text.removePrefix(HELLO2_PREFIX).split(" ")
            if (tokens.size !in 3..(2 + MAX_HELLO_STATEMENTS)) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    null,
                    "IROH-HELLO2 refused: ${tokens.size} tokens where a mirror ref, a claimed name and " +
                        "1..$MAX_HELLO_STATEMENTS statements are required",
                )
                return
            }
            val peerMirrorRef = runCatching { UUID.fromString(tokens[0]) }.getOrNull()
            if (peerMirrorRef == null || peerMirrorRef.toString() != tokens[0]) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    null,
                    "IROH-HELLO2 refused: token 0 is not a canonical mirror ref UUID",
                )
                return
            }
            val claimedName = tokens[1]
            if (claimedName.isEmpty() || !claimedName.isWellFormedUtf16()) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    null,
                    "IROH-HELLO2 refused: token 1 is not a claimed name (empty or ill-formed UTF-16)",
                )
                return
            }
            val presented = ArrayList<IdentityStatement>(tokens.size - 2)
            for (index in 2 until tokens.size) {
                val statement = decodeIdentityStatementToken(tokens[index])
                if (statement == null) {
                    refuseHello(
                        DenialReason.MALFORMED_HELLO,
                        null,
                        "IROH-HELLO2 refused: token $index is not a decodable identity statement",
                    )
                    return
                }
                presented += statement
            }
            val key = linkKeyOrRefuse() ?: return
            val bound = resolveOrRefuse(key, presented) ?: return
            if (claimedName != bound.peer.name) {
                refuseClaimMismatch(claimedName, bound.peer)
                return
            }
            admitAndBind(bound, key, peerMirrorRef)
        }

        /**
         * The admission key comes from the connection, not from the line — and
         * it is settled BEFORE the allowlist is consulted, so a NodeId this side
         * cannot make a key of never reaches an allowlist decision at all. The
         * detail names the shape only, never the bytes.
         *
         * @return the key, or null after refusing the hello.
         */
        private fun linkKeyOrRefuse(): KeyId? = admissionKey.getOrElse {
            refuseHello(
                DenialReason.MALFORMED_HELLO,
                null,
                "hello refused: this link's remote NodeId is not a valid Ed25519 public key",
            )
            null
        }

        /**
         * The ONE resolution on a hello path (feature `computenet-376c`): the
         * identity is whatever this side's binding maps the link's key to,
         * given the statements the line [presented] — never the fingerprint
         * read as a name and never a `PeerId` built here.
         *
         * A key the binding holds no identity for is refused right here (task
         * `computenet-hbqvz`), before the claimed name is compared and before
         * the allowlist is consulted: nothing below may run without an identity
         * to attribute it to, and none is invented. Accounted under
         * `denialReasonFor(reason)` — `UNVOUCHED` or `STATEMENT_EXPIRED`, the
         * one table both transports read (feature `computenet-5y8t.3`) — with
         * the machine-readable `UnboundReason` in the detail. No principal: an
         * unresolved key has no identity to attribute the refusal to, and a
         * claimed name is exactly what may not stand in for one.
         *
         * @return the binding's verdict, or null after refusing the hello.
         */
        private fun resolveOrRefuse(
            key: KeyId,
            presented: List<IdentityStatement>,
        ): IdentityResolution.Bound? =
            when (val resolution = side.identityBinding.resolve(key, presented)) {
                is IdentityResolution.Bound -> resolution
                is IdentityResolution.Unbound -> {
                    val reason = resolution.reason
                    val window = when (reason) {
                        UnboundReason.EXPIRED, UnboundReason.NOT_YET_VALID -> " under this side's clock"
                        else -> ""
                    }
                    refuseHello(
                        denialReasonFor(reason),
                        null,
                        "hello presenting key ${key.name} refused: this side's identity binding holds no " +
                            "identity for it (UnboundReason.${reason.name}$window)",
                    )
                    null
                }
            }

        /** A claimed name that differs from the resolved identity — `[DSC1-HELLO-06]`'s shape, both named. */
        private fun refuseClaimMismatch(claimed: String, resolved: PeerId) {
            refuseHello(
                DenialReason.ID_MISMATCH,
                resolved,
                "hello claims $claimed but this link's NodeId resolves ${resolved.name}",
            )
        }

        /**
         * The allowlist on the resolved identity, then the [gate], then our
         * hello, then bind + announce.
         *
         * **The gate is consulted after [admitted] and before [openLocalHello],
         * and that order is load-bearing** — see [HelloGate]'s KDoc: a gate
         * that ran first would let a hello the allowlist is about to refuse
         * displace a live peering with an admitted peer.
         */
        private fun admitAndBind(bound: IdentityResolution.Bound, key: KeyId, peerMirrorRef: UUID) {
            if (!admitted(bound.peer)) return
            when (val verdict = gate.judge(key, remoteNodeId, direction, bound.peer)) {
                is Verdict.Admit -> Unit

                is Verdict.CloseQuietly -> {
                    // Blame-free by construction: no denial, no counter, no
                    // hello, no announcement — nothing but the close. See the
                    // class KDoc's re-derivation of the happens-before argument
                    // for why leaving at this exact point is safe.
                    System.err.println("[IrohTransport] closing link quietly: ${verdict.detail}")
                    closeQuietly()
                    return
                }

                is Verdict.Refuse -> {
                    refuseHello(verdict.reason, verdict.principal, verdict.detail)
                    return
                }
            }
            // Our own hello first (see onHello's KDoc), then bind + announce.
            openLocalHello()
            // Every iroh admission is Authenticated (the NodeId IS the proven
            // key), so the resolution's issuer rides onto the stamp unconditionally.
            bindAndAnnounce(bound.peer, key, peerMirrorRef, bound.issuer)
        }

        /**
         * `Peering.Side.admits`, with the refusal accounted — the same code, the
         * same stderr line and the same denial shape `WsTransport` writes.
         *
         * The allowlist judges the **resolved** identity (epic
         * `computenet-5y8t`): [peer] is what this side's binding resolved the
         * link's proven key (its NodeId) to, and it is both what the allowlist
         * judges and what the denial record attributes the refusal to.
         * Allowlists name identities; the key is what the hello is proven on
         * and plays no part here. An `IdentityResolution.Unbound` key is
         * refused before this is reached (`WsTransport.Session.admitted`'s
         * KDoc for the whole argument).
         *
         * @return true when the peer is admitted; false after refusing it, in
         *   which case the caller must return without binding anything.
         */
        private fun admitted(peer: PeerId): Boolean {
            if (side.admits(peer)) return true
            System.err.println("[IrohTransport] refusing peer $peer: not on the allowlist (spec 43)")
            // Seam 1 (spec 40/43, [SEC1-07]): accounted before the link is
            // refused. Nothing throws — a denial is not a cell fault (BS-14) —
            // so this never reaches supervision.
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = DenialReason.NOT_ADMITTED,
                principal = peer,
                subject = null,
                detail = "hello from $peer refused: not on the allowlist (spec 43)",
            )
            refuse()
            return false
        }

        /**
         * Account a refused hello on the seam-1 sink, then close the link. Every
         * refusal path goes through here or through [admitted], so
         * [admissionDenialCount] counts all of them and none can close a link
         * unaccounted.
         *
         * [detail] names ids, reasons and shapes only — never the raw line, which
         * is attacker-chosen bytes.
         */
        private fun refuseHello(reason: DenialReason, principal: PeerId?, detail: String) {
            System.err.println("[IrohTransport] refusing hello: $detail")
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = reason,
                principal = principal,
                subject = null,
                detail = detail,
            )
            refuse()
        }

        /**
         * Bind the mirror's peer, install the ingress, announce — in that order,
         * and only ever from a path that has already admitted [peer]. See the
         * class KDoc's four-step happens-before argument for why the late bind is
         * safe on this transport.
         *
         * [issuer] is the `IdentityResolution.Bound.issuer` the hello admission
         * resolved, fixed here beside the level for the same reason: a delivery's
         * `PeerStamp.issuer` is a parameter of the admission, never read later.
         */
        private fun bindAndAnnounce(peer: PeerId, key: KeyId, peerMirrorRef: UUID, issuer: IssuerId?) {
            val instance = checkNotNull(mirror) { "onHello admitted a peer without opening a link instance" }
            // Bind BEFORE announcing, so every Remote location this link installs
            // — including the peer's own catch-up burst, which cannot start
            // before it has seen our hello — records the peer's name (V4-PEERID).
            instance.peer = peer
            attributedPeer = peer
            // The level is a parameter fixed HERE, at the admission decision,
            // before the ingress exists — never read from a frame. See the class
            // KDoc for the proof-of-possession argument and its assumptions.
            ingress = Peering.hostIngress(
                side,
                fromPeer = peer,
                fromPeerAuth = AuthLevel.Authenticated,
                fromPeerIssuer = issuer,
                fromKey = key,
            )
            announcement?.close()
            announcement = Peering.announceTo(side, CellRef(peerMirrorRef), via = egress)
            // Last, so an observer that reads `peered`/`attributedPeer` from
            // this callback sees a link that is fully bound.
            onAdmitted(peer)
        }

        /**
         * The link is down: retire the announcer and detach the mirror, both
         * permanently.
         *
         * `detach` shuts the gate and retracts this link's Remote locations in
         * one step, which a bare `unpublishRemotes(via = egress)` could not: this
         * runs on the sidecar's reader thread while the announcements it retracts
         * are applied two scheduler hops later on the bridge host, so an
         * announcement decoded before this close can be applied after it.
         */
        fun onDown() {
            announcement?.close()
            announcement = null
            mirror?.detach()
        }
    }

    /**
     * The listening side: a sidecar that has `LISTEN`ed, plus one [Session] per
     * link it accepts.
     */
    class IrohListener internal constructor(
        private val process: SidecarProcess,
        private val client: SidecarClient,
        private val side: Peering.Side,
    ) : AutoCloseable {

        /** This side's iroh endpoint id — what a dialler passes to [connect]. */
        val nodeId: ByteArray get() = process.nodeId

        @Volatile
        private var listeningAddresses: List<String> = emptyList()

        /** The `LISTENING` addresses, `ADD_PEER`-ready — see [connect]. */
        val addresses: List<String> get() = listeningAddresses

        private val sessions = ConcurrentHashMap<Long, Session>()

        /**
         * One sink for every Session this listener opens, for the reason
         * `WsListener.admissionDenials` is allocated once: a refused hello's
         * Session is removed from [sessions] on the `LINK_DOWN` its own refusal
         * caused, so a per-Session sink would be discarded with the count it just
         * recorded. Read directly, never summed over [sessions] — the same sink
         * is shared, so summing would multiply-count one refusal.
         */
        private val admissionDenials = BoundaryDenials()
        private val admissionSink = admissionDenials.sinkFor("hello")

        /** @see admissionSink */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * Frames dropped before an admitted hello, summed over the links that are
         * still up (`WsListener.preHelloDrops`' analogue).
         */
        val preHelloDrops: Long get() = sessions.values.sumOf { it.preHelloDrops }

        /**
         * Every `ERROR` the sidecar reported on a link of this listener, in
         * arrival order — including a `DATA` refused by a full send queue.
         *
         * Recording is all this side owes: `PROTOCOL.md` §2 makes such an
         * `ERROR` terminal for its link, and [SidecarClient] has already sent the
         * `CLOSE_LINK` by the time `onError` runs (`computenet-ey4v`). The
         * `LINK_DOWN` that follows retires the Session exactly as any other
         * would; the accepting side does not re-dial, and the dialler's own
         * reconnect is what brings the peering back.
         */
        val linkErrors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        internal fun sessionFor(linkId: Long): Session? = sessions[linkId]

        internal fun start(timeout: Duration) {
            client.onInboundLink { link ->
                val session = Session(
                    side,
                    // The key this link is admitted on: the endpoint the sidecar
                    // authenticated when it accepted the QUIC connection.
                    link.remoteNodeId,
                    send = { link.send(it) },
                    refuse = { link.close() },
                    admissionSink = admissionSink,
                )
                sessions[link.id] = session
                object : LinkListener {
                    override fun onData(link: SidecarLink, payload: ByteArray) = session.onData(payload)

                    override fun onDown(link: SidecarLink, reason: String) {
                        sessions.remove(link.id)
                        session.onDown()
                    }

                    override fun onError(link: SidecarLink, reason: String) {
                        linkErrors += reason
                        System.err.println(
                            "[IrohTransport] link ${link.id} error: $reason — the link is closed " +
                                "(PROTOCOL.md §2: an ERROR on an established link is terminal for it)",
                        )
                    }
                }
            }
            listeningAddresses = client.listen(timeout)
        }

        override fun close() {
            runCatching { client.shutdown() }
            runCatching { client.close() }
            process.close()
        }
    }

    /**
     * What an [IrohConnection] needs of the sidecar process it runs over: this
     * side's endpoint id, and shutting the process down. Everything else it
     * speaks to the sidecar through is [SidecarClient].
     *
     * A named seam rather than the concrete [SidecarProcess] because
     * IrohConnection's *host-side* decisions — [IrohConnection.retire]'s refusal
     * guard above all — are otherwise unreachable in a test. A [SidecarClient]
     * can be pointed at any loopback socket, and so can be driven by a fake that
     * speaks `PROTOCOL.md` by hand; a [SidecarProcess] cannot exist without
     * spawning the Rust binary, which would gate every such test on cargo. The
     * seam costs one interface and one adapter and buys the whole of
     * `SidecarBackpressureTest`'s IrohConnection half (computenet-pozr).
     */
    internal interface Sidecar : AutoCloseable {
        /** @see SidecarProcess.nodeId */
        val nodeId: ByteArray
    }

    /** [SidecarProcess] as the narrow [Sidecar] an [IrohConnection] needs. */
    internal fun SidecarProcess.asSidecar(): Sidecar = object : Sidecar {
        override val nodeId: ByteArray get() = this@asSidecar.nodeId
        override fun close() = this@asSidecar.close()
    }

    /**
     * The dialling side: one sidecar, and a **succession** of links — each with
     * its own [Session] — under one connection handle.
     *
     * ## Requested down versus unplanned down
     *
     * Every `LINK_DOWN` arrives the same way whatever caused it
     * (`PROTOCOL.md` §3: exactly one per side, from the link's own observer), so
     * the difference has to be held here rather than read off the wire. A close
     * this side asked for — [sever], [close] — sets [closeRequested] *before* it
     * asks, and the handler consumes that flag instead of re-dialling. Anything
     * else is unplanned and starts [scheduleReconnect]. This is `WsTransport`'s
     * planned-close discipline (its `reconnect` flag), and it is what keeps a
     * severed peering severed until the test heals it.
     *
     * ## What a re-established link shares with the one it replaces: nothing
     *
     * A re-dial mints a whole new [Session] — new mirror, new egress, new
     * ingress, new hello, and a fresh `announceTo` catch-up over the local
     * registry. The retired Session's mirror is detached permanently, and there
     * is deliberately no way to re-open it (see [RegistryMirrorCell.detach]:
     * "the gate never re-opens, and that is what makes the fence total"). The
     * returning peer loses nothing by that, because a re-announcement is a full
     * `localRefs` sweep.
     *
     * What *is* connection-scoped, and survives across instances, is only the
     * accounting a per-link object could not carry honestly: the admission sink
     * (a refused hello takes its own link down, so a per-Session sink would be
     * discarded together with the count it just recorded — [IrohListener] owns
     * one for the same reason) and the pre-hello drop total.
     */
    class IrohConnection internal constructor(
        private val sidecar: Sidecar,
        private val client: SidecarClient,
        private val side: Peering.Side,
        private val peerNodeId: ByteArray,
        private val backoff: (attempt: Int) -> Long,
        private val redialTimeout: Duration,
        private val refusedDialLimit: Int = REFUSED_DIAL_LIMIT,
        /**
         * Whether [close] owns the [client] and [sidecar] it was handed
         * (ktn1l-D13). True for [connect], which spawned both for this
         * connection alone. **False** under an [IrohNode], where one client and
         * one sidecar process serve the listener and every connection at once,
         * and closing one connection must leave the endpoint usable.
         */
        private val ownsClient: Boolean = true,
        /**
         * Where an **unplanned** link down is reported instead of being
         * re-dialled here (ktn1l-D13). Null — the default, and what [connect]
         * passes — keeps [scheduleReconnect]'s own retry loop, so every
         * pre-existing path is byte-for-byte what it was.
         *
         * Non-null hands the decision to the caller: this connection still does
         * all of the *accounting* (the unadmitted run, the abandonment, the
         * quiet-close exemption) and reports it in the [LinkOutcome], but it
         * issues no dial of its own. That is what lets a discovery policy
         * schedule re-dials across many peers on one bounded executor rather
         * than one unbounded thread per peer (F3-D8, ktn1l-D17).
         *
         * It is invoked on the sidecar reader thread, inside [retire]: enqueue
         * only.
         */
        private val onUnplannedDown: ((LinkOutcome) -> Unit)? = null,
        /** @see HelloGate — passed to every [Session] this connection opens. */
        private val gate: HelloGate = HelloGate.ADMIT_ALL,
        /** @see LinkObserver */
        private val observer: LinkObserver? = null,
        /**
         * Whether an unadmitted drop of this connection's link is the far side
         * closing the **mutual-dial tie-break loser** rather than refusing us
         * (ktn1l-D16, aas-D7, `[DSC2-DIAL-05]`).
         *
         * Consulted in [retire], with [peerNodeId], and **only** for a link
         * that was never admitted. It exists because the losing link is closed
         * by whichever side reaches the verdict first, and the other side
         * learns of it as an ordinary `LINK_DOWN`: `PROTOCOL.md` carries no
         * cause, so a drop that is nobody's fault is indistinguishable here
         * from a peer that refused us. Charging it to [unadmitted] would
         * abandon a peer this node is, at that very moment, still linked to —
         * over the one link the tie-break kept.
         *
         * The host supplies the predicate because the fact it turns on — "an
         * INBOUND link from this key is up" — is a property of the *endpoint's*
         * link registry, which a single connection cannot see
         * ([IrohNode.dialDiscovered] passes it). The default answers false, so
         * every pre-existing caller classifies exactly as it did.
         */
        private val tieBreakLoss: (ByteArray) -> Boolean = { false },
    ) : AutoCloseable {

        /**
         * How one link instance of this connection ended, as [retire] classified
         * it (ktn1l-D13). Reported to [onUnplannedDown] and to [observer]; the
         * caller decides what to do about it, and this connection has already
         * decided what to *count*.
         *
         * @param peered whether the link had been admitted when it went down.
         * @param quiet whether a [Verdict.CloseQuietly] closed it — blame-free,
         *   charged to nothing.
         * @param afterRefusal whether the sidecar had refused a frame on it
         *   ([linkRefused]); such a down is not evidence about the peer either.
         * @param abandoned whether this down was the one that ended the
         *   re-dialling for good ([abandonedAfterRefusals]).
         * @param lastDenial the last hello refusal recorded on the link, if any.
         */
        data class LinkOutcome(
            val peered: Boolean,
            val quiet: Boolean,
            val afterRefusal: Boolean,
            val abandoned: Boolean,
            val lastDenial: BoundaryDenial?,
        )

        /**
         * The link lifecycle of this connection, for a host that keeps a
         * registry across several connections and its own accepted links
         * (ktn1l-D14, [IrohNode]).
         *
         * [onAdmitted] and [onDown] run on the sidecar reader thread; [onUp]
         * runs on whichever thread called [openLink] (the caller's, or the
         * re-dial loop's). **Enqueue only** on all three.
         */
        internal interface LinkObserver {
            fun onUp(link: SidecarLink)
            fun onAdmitted(linkId: Long, peer: PeerId)

            /** [outcome] is null for a close this side asked for; see [LinkOutcome]. */
            fun onDown(linkId: Long, outcome: LinkOutcome?)
        }

        /** @see IrohConnection — one sink across every link instance. */
        private val admissionSink = BoundaryDenials().sinkFor("hello")

        private val currentLink = AtomicReference<SidecarLink?>(null)
        private val currentSession = AtomicReference<Session?>(null)

        /** Drops charged to links that are already gone; see [preHelloDrops]. */
        private val retiredPreHelloDrops = AtomicLong()

        /**
         * Set immediately before this side asks for a close, and consumed by the
         * `LINK_DOWN` that close produces. A one-shot rather than a level: it
         * must not suppress the reconnect for the *next* link.
         */
        private val closeRequested = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Set by the live Session's `closeQuietly` callback immediately before
         * it closes the link, and consumed by the `LINK_DOWN` that close
         * produces — a one-shot in the shape of [closeRequested], and for the
         * same reason: `PROTOCOL.md` carries no cause, so the difference has to
         * be held here (ktn1l-D13).
         *
         * What it buys is that a tie-break loss costs the peer nothing: the
         * drop is charged to neither [unadmitted] nor a re-dial. Consumed
         * **after** [shuttingDown] and [closeRequested], like [linkRefused] and
         * in the same safe direction — a quiet close followed by a [sever] or a
         * [close] leaves the flag set to exempt the next unplanned down, which
         * can only delay abandonment by one link, never trigger it early.
         */
        private val quietClose = java.util.concurrent.atomic.AtomicBoolean(false)

        /** False until [close]; nothing re-dials after it. */
        @Volatile
        private var shuttingDown = false

        /**
         * Single-flight guard on the re-dial loop, for the reason
         * `WsConnection.reconnecting` exists: one loop retries, and a
         * `LINK_DOWN` that arrives while a loop is running must not spawn a
         * second one racing it onto the same connection.
         */
        private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Consecutive links that came **up** and went **down without ever being
         * admitted** — the refusal signal `PROTOCOL.md` does not carry, read off
         * the local link lifecycle instead (computenet-4gzr, [REFUSED_DIAL_LIMIT]).
         *
         * Reset to zero by any link that *was* admitted, so this counts a run of
         * refusals rather than a lifetime total: a peering that flaps for hours
         * and re-peers each time never approaches the limit.
         */
        private val unadmitted = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * True once [refusedDialLimit] consecutive unadmitted opens have ended
         * the re-dialling. Terminal for the connection's own retry loop — only
         * an explicit [heal] resumes it, which is an operator's decision to try
         * again rather than a schedule's.
         */
        @Volatile
        private var abandoned = false

        private val backoffCalls = AtomicLong()
        private val highestAttempt = java.util.concurrent.atomic.AtomicInteger(-1)

        /** @see IrohListener.linkErrors */
        val linkErrors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        /**
         * Set when the live link's `ERROR` closed it, and consumed by the
         * `LINK_DOWN` that close produces — a one-shot in the shape of
         * [closeRequested], and for a related reason: the `LINK_DOWN` that
         * follows a refusal is indistinguishable on the wire from any other, and
         * the difference has to be held here.
         *
         * What it buys is that such a down does **not** count toward
         * [unadmitted]. That counter means "the peer keeps refusing us"
         * ([REFUSED_DIAL_LIMIT]); a link this side tore down because its own send
         * queue overflowed is no evidence of that, and letting it accumulate
         * there would abandon a perfectly willing peer after a flood. A refusal
         * still re-dials — it just re-dials as an ordinary unplanned down.
         *
         * ## It is consumed AFTER [retire]'s early returns, on purpose
         *
         * [retire] reads this only past `shuttingDown` and `closeRequested`, so
         * an `ERROR` followed by a shutdown or a [sever] — rather than by the
         * `LINK_DOWN` the client's own `CLOSE_LINK` normally produces — leaves
         * the flag set, and it exempts the *next* unplanned down instead. That is
         * deliberate, and it is the safe direction: the only effect is that
         * abandonment is delayed by at most one link, never triggered early, and
         * [heal] clears the run outright anyway. Consuming it above the early
         * returns would buy an exactness no caller can observe, at the cost of a
         * second write on the [close] path — where the connection is being torn
         * down and nothing will read the flag again. Pinned by
         * `SidecarBackpressureTest`'s
         * `the refusal flag outlives a caller-requested close ...`, so a future
         * change of mind here is a deliberate one (computenet-pozr).
         */
        private val linkRefused = java.util.concurrent.atomic.AtomicBoolean(false)

        /** @see IrohListener.admissionDenialCount */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * @see IrohListener.preHelloDrops
         *
         * Summed over every link instance this connection has had, live one
         * included — a count that reset on each re-dial would understate exactly
         * the case it exists for.
         */
        val preHelloDrops: Long get() = retiredPreHelloDrops.get() + (currentSession.get()?.preHelloDrops ?: 0L)

        /** True while a link is up whose peer hello was admitted. */
        val peered: Boolean get() = currentSession.get()?.peered ?: false

        /**
         * The live link instance's mirror ref, or null while there is no link.
         * **A different [CellRef] after every re-establishment** — the assertion
         * computenet-dqy.14's per-instance rule is checked by.
         */
        val mirrorRef: CellRef? get() = currentSession.get()?.mirrorRef

        /** @see Session.mirrorCell */
        internal val mirrorCell: RegistryMirrorCell? get() = currentSession.get()?.mirrorCell

        /**
         * How many times the re-dial schedule has been consulted, and the highest
         * `attempt` it was consulted with (-1 = never). Together they say that the
         * seam is real and which delays it was asked for — a reconnect test that
         * injects a near-zero schedule asserts on these rather than on elapsed
         * time, which no loaded machine can promise.
         */
        val backoffConsultations: Long get() = backoffCalls.get()

        /** @see backoffConsultations */
        val highestReconnectAttempt: Int get() = highestAttempt.get()

        /**
         * How many consecutive links have come up and gone down without ever
         * being admitted. @see REFUSED_DIAL_LIMIT
         */
        val unadmittedOpens: Int get() = unadmitted.get()

        /**
         * True once this connection has given up re-dialling a peer that keeps
         * refusing it ([REFUSED_DIAL_LIMIT]). Read rather than inferred from
         * silence: a dialler that has stopped retrying must never be invisible,
         * which is the same reason `WsConnection.scheduleReconnect` announces an
         * interrupted retry loop instead of dying quietly.
         */
        val abandonedAfterRefusals: Boolean get() = abandoned

        /** This side's own iroh endpoint id. */
        val nodeId: ByteArray get() = sidecar.nodeId

        /**
         * Take this connection's link down **without** re-dialling it: the
         * transport's programmatic partition (egl.2-D3). `CLOSE_LINK` yields
         * exactly one `LINK_DOWN` per side (`PROTOCOL.md` §3), so both peers
         * detach their mirror; [heal] is what brings the peering back, and only
         * as a new link.
         */
        fun sever() {
            val link = currentLink.get() ?: return
            closeRequested.set(true)
            link.close()
        }

        /**
         * Close this connection's live link **quietly** — the [sever] of a
         * tie-break loser (aas-D7, ktn1l-D16).
         *
         * Same physical close as [sever], and the opposite classification:
         * [sever] is a partition this side asked for and reports no outcome at
         * all, while this is the blame-free close [Verdict.CloseQuietly] makes
         * from inside a [Session] — no unadmitted open, no re-dial here, and a
         * `LinkOutcome` with `quiet` set, so the host that decided it sees the
         * link end rather than merely vanish. @see quietClose
         *
         * The route from outside exists because a mutual dial is judged on the
         * link that *survives*: the verdict on this node's INBOUND hello is
         * what says the OUTBOUND link lost, and the acceptor hello that would
         * have carried the verdict onto this link is never written. The Session
         * on this link is therefore never asked anything, and the host closes
         * it from the outside on the other link's verdict.
         *
         * A no-op while this connection holds no link. Nothing about the
         * Session's happens-before argument moves: this closes a link, it does
         * not change when a hello or an announcement is written on it.
         */
        internal fun closeCurrentLinkQuietly() {
            val link = currentLink.get() ?: return
            // Marked BEFORE the close is asked for, exactly as the Session's
            // own callback does: the `LINK_DOWN` it produces is all `retire`
            // sees.
            quietClose.set(true)
            link.close()
        }

        /**
         * Re-establish the peering as a NEW link, and therefore a new [Session]
         * with a fresh mirror, a fresh hello and a fresh announcement catch-up.
         * Nothing about the severed instance is resumed — there is nothing to
         * resume it to.
         *
         * This is also the only way back from [abandonedAfterRefusals]: an
         * explicit heal clears the unadmitted run, because a caller asking for a
         * link is making a decision the schedule is not entitled to make on its
         * own (computenet-4gzr). `WsConnection.heal` (computenet-f6dr) is the
         * `:wire` counterpart, closing what was otherwise a silent asymmetry
         * between the two transports' give-up paths.
         *
         * ## It dials only when no re-dial loop is already dialling
         *
         * This takes the same [reconnecting] single-flight guard
         * [scheduleReconnect] takes, and **returns without dialling** when a loop
         * holds it. The two paths were previously exclusive only by convention:
         * [sever] sets [closeRequested], so [retire] starts no loop and a heal
         * after a sever is alone in [openLink] — but after an *unplanned* drop the
         * loop IS running, and a host calling this then put a second thread into
         * [openLink]. Both dial, and both finish with an unconditional
         * `currentLink.set` / `currentSession.set` (unlike [retire], which
         * compare-and-sets), so the loser's link is never closed, its
         * [LinkListener] stays registered, its [Session]'s ingress stays installed
         * and its mirror is never detached — a duplicated peering and a leaked
         * mirror, which is exactly what computenet-dqy.14's per-instance
         * discipline forbids (computenet-m475).
         *
         * Returning is the right answer rather than a weaker one: the loop is
         * already re-establishing this very peering and retries forever, so the
         * caller's intent — "be peered again" — is served either way. What the
         * caller uniquely asks for is the clearing of the refusal run above, and
         * that happens unconditionally, **before** the guard, so a heal is never
         * a complete no-op: it always lifts [abandonedAfterRefusals].
         *
         * Nothing is lost by not dialling here, because the two states that
         * *stop* a loop from dialling also mean no loop holds the guard:
         * [abandoned] is set by [retire] on a path that returns without
         * scheduling, and [shuttingDown] means this connection is closed.
         *
         * @throws SidecarException as before, when it does dial and the dial
         *   fails — a caller-driven heal reports its own failure rather than
         *   retrying silently.
         */
        fun heal(timeout: Duration = redialTimeout) {
            unadmitted.set(0)
            abandoned = false
            if (!reconnecting.compareAndSet(false, true)) return // a loop is already dialling
            try {
                openLink(timeout)
            } finally {
                reconnecting.set(false)
            }
            // Reached only when the dial succeeded. A `LINK_DOWN` for the link
            // just installed can have landed while the guard was held; its
            // `scheduleReconnect` found the guard and returned, and nothing else
            // would retry for it — the same hole [scheduleReconnect]'s own tail
            // closes for the loop.
            if (!shuttingDown && currentSession.get() == null) scheduleReconnect()
        }

        /**
         * Dial one link and install a Session on it.
         *
         * The one ordering that spans link instances lives here: the re-dial runs
         * on the reconnect thread while the previous instance was retired on the
         * sidecar's reader thread. It is not a premise of [Session]'s
         * happens-before argument, and cannot become one, because the two
         * instances share no cell: the retired mirror's gate is already shut when
         * this returns, and this instance's mirror is minted below — after the
         * link exists and before any frame can be routed on it, since
         * [SidecarClient.dial] registers the listener before the `DIAL` goes out
         * and `LINK_UP` precedes every `DATA` on that id.
         */
        internal fun openLink(timeout: Duration) {
            val linkHolder = AtomicReference<SidecarLink?>(null)
            val session = Session(
                side,
                // The id we dialled IS the endpoint iroh reached — a dial only
                // completes against the holder of that NodeId's key — so it is
                // the authenticated remote key on this side too.
                peerNodeId,
                send = { payload ->
                    val link = linkHolder.get() ?: throw SidecarException("this connection has no link yet")
                    link.send(payload)
                },
                refuse = { linkHolder.get()?.close() },
                admissionSink = admissionSink,
                gate = gate,
                direction = LinkDirection.OUTBOUND,
                // Mark the close blame-free BEFORE asking for it: the
                // `LINK_DOWN` it produces is the only thing `retire` sees.
                closeQuietly = {
                    quietClose.set(true)
                    linkHolder.get()?.close()
                },
                onAdmitted = { peer -> linkHolder.get()?.let { observer?.onAdmitted(it.id, peer) } },
            )
            val link = client.dial(
                peerNodeId,
                object : LinkListener {
                    override fun onData(link: SidecarLink, payload: ByteArray) = session.onData(payload)

                    override fun onDown(link: SidecarLink, reason: String) = retire(session, link, reason)

                    override fun onError(link: SidecarLink, reason: String) {
                        linkErrors += reason
                        linkRefused.set(true)
                        System.err.println(
                            "[IrohTransport] link ${link.id} error: $reason — the link is closed and will be " +
                                "re-dialled (PROTOCOL.md §2: an ERROR on an established link is terminal for it)",
                        )
                    }
                },
                timeout,
            )
            linkHolder.set(link)
            currentLink.set(link)
            currentSession.set(session)
            // The dialler's hello is its FIRST frame, and it is what adopts the
            // QUIC stream at the accepting sidecar (PROTOCOL.md §3). The peer
            // cannot have spoken before it: an accepting Session sends nothing
            // until it has read this hello (Session.onHello), so no inbound DATA
            // can reach the listener above before this line runs.
            observer?.onUp(link)
            session.openLocalHello()
        }

        /**
         * One link instance is over: charge its drops to the connection, shut its
         * mirror's gate for good, and decide whether a replacement is owed.
         *
         * Who re-dials depends on [onUnplannedDown] alone (ktn1l-D13). With it
         * null — every pre-existing caller — the classification below ends in
         * [scheduleReconnect] exactly as it always did. With it set, the same
         * classification is reported as a [LinkOutcome] and nothing is dialled
         * from here.
         */
        private fun retire(session: Session, link: SidecarLink, reason: String) {
            retiredPreHelloDrops.addAndGet(session.preHelloDrops)
            session.onDown()
            currentSession.compareAndSet(session, null)
            currentLink.compareAndSet(link, null)
            if (shuttingDown) {
                observer?.onDown(link.id, null)
                return
            }
            // A close this side asked for is not a partition to recover from.
            if (closeRequested.getAndSet(false)) {
                observer?.onDown(link.id, null)
                return
            }
            // A link the gate closed quietly is nobody's fault (aas-D7,
            // [DSC2-DIAL-05]): it charges no unadmitted open, ends no run, and
            // is never re-dialled from here — the host that installed the gate
            // decides whether this key is worth another link. @see quietClose
            if (quietClose.getAndSet(false)) {
                val outcome = LinkOutcome(
                    peered = session.peered,
                    quiet = true,
                    // linkRefused is deliberately NOT consumed here: a quiet
                    // close is not the down that flag was set for, and leaving
                    // it to exempt the next down is the same safe direction
                    // retire's other early returns take.
                    afterRefusal = false,
                    abandoned = abandoned,
                    lastDenial = session.lastAdmissionDenial,
                )
                System.err.println("[IrohTransport] link ${link.id} closed quietly ($reason); not re-dialled, not charged")
                observer?.onDown(link.id, outcome)
                onUnplannedDown?.invoke(outcome)
                return
            }
            // A link that was never admitted and dropped while an INBOUND link
            // from the same key is up is the FAR side's tie-break close
            // reaching us (ktn1l-D16): both sides evaluate `loserDirection`
            // identically, so the peer closing our outbound link is the same
            // verdict we would have reached ourselves on its acceptor hello —
            // which never arrives, because a quietly closed link is never
            // written to. Classified exactly as our own quiet close: no
            // unadmitted open, no re-dial from here, no blame. @see tieBreakLoss
            if (!session.peered && runCatching { tieBreakLoss(peerNodeId) }.getOrDefault(false)) {
                val outcome = LinkOutcome(
                    peered = false,
                    quiet = true,
                    afterRefusal = false,
                    abandoned = abandoned,
                    lastDenial = session.lastAdmissionDenial,
                )
                System.err.println(
                    "[IrohTransport] link ${link.id} went down unadmitted ($reason) while an inbound link from the " +
                        "same key is up; classified as the mutual-dial tie-break loss it is, not as a refusal",
                )
                observer?.onDown(link.id, outcome)
                onUnplannedDown?.invoke(outcome)
                return
            }
            // A link the sidecar refused something on is closed by the client
            // (PROTOCOL.md §2, computenet-ey4v). It re-dials like any other
            // unplanned down, but it is not evidence about the PEER's willingness
            // and must not accumulate in `unadmitted`. @see linkRefused
            val afterRefusal = linkRefused.getAndSet(false)
            // A link that came UP and went DOWN without ever being admitted is
            // the local shadow of a refusal PROTOCOL.md cannot report
            // (computenet-4gzr). An admitted link clears the run; a run that
            // reaches the limit ends the re-dialling for good, because nothing
            // that happens on this wire will ever change the peer's mind.
            if (session.peered) {
                unadmitted.set(0)
            } else if (!afterRefusal && unadmitted.incrementAndGet() >= refusedDialLimit) {
                abandoned = true
                System.err.println(
                    "[IrohTransport] link ${link.id} went down unplanned ($reason) after $refusedDialLimit " +
                        "consecutive links that were never admitted; this peer is refusing us and will not be " +
                        "re-dialled. Call heal() to try again.",
                )
                reportUnplanned(session, link, afterRefusal)
                return
            }
            System.err.println("[IrohTransport] link ${link.id} went down unplanned ($reason); re-dialling")
            if (!reportUnplanned(session, link, afterRefusal)) scheduleReconnect()
        }

        /**
         * Report one unplanned down to [observer] and [onUnplannedDown].
         *
         * @return true when [onUnplannedDown] took the re-dial decision, so the
         *   caller must not schedule one of its own.
         */
        private fun reportUnplanned(session: Session, link: SidecarLink, afterRefusal: Boolean): Boolean {
            val outcome = LinkOutcome(
                peered = session.peered,
                quiet = false,
                afterRefusal = afterRefusal,
                abandoned = abandoned,
                lastDenial = session.lastAdmissionDenial,
            )
            observer?.onDown(link.id, outcome)
            val delegated = onUnplannedDown ?: return false
            delegated.invoke(outcome)
            return true
        }

        /**
         * Re-dial on [backoff] until a link comes back or [close] is called;
         * retries forever, as `WsConnection.scheduleReconnect` does.
         *
         * The schedule is consulted once per attempt, immediately before that
         * attempt — including the first, so a schedule is never bypassed by a
         * re-dial that happens to succeed at once.
         */
        private fun scheduleReconnect() {
            if (shuttingDown || abandoned) return
            if (!reconnecting.compareAndSet(false, true)) return // a loop is already retrying
            Thread({
                try {
                    var attempt = 0
                    while (!shuttingDown && currentSession.get() == null) {
                        try {
                            Thread.sleep(delayFor(attempt))
                            attempt++
                            if (shuttingDown) break
                            openLink(redialTimeout)
                        } catch (_: InterruptedException) {
                            System.err.println("[IrohTransport] re-dial loop interrupted; this connection will not retry")
                            return@Thread
                        } catch (e: Exception) {
                            System.err.println("[IrohTransport] re-dial attempt $attempt failed: $e")
                        }
                    }
                } finally {
                    reconnecting.set(false)
                }
                // A LINK_DOWN that landed while this loop was winding down found
                // the guard held and returned; nothing else would retry for it.
                if (!shuttingDown && currentSession.get() == null) scheduleReconnect()
            }, "iroh-reconnect-${peerNodeId.toHex().take(8)}").apply { isDaemon = true }.start()
        }

        /** The schedule seam, with its consultation recorded. @see backoffConsultations */
        private fun delayFor(attempt: Int): Long {
            backoffCalls.incrementAndGet()
            highestAttempt.updateAndGet { maxOf(it, attempt) }
            return backoff(attempt)
        }

        /**
         * Take this connection down for good.
         *
         * With [ownsClient] false this closes **only this connection's link**:
         * the [SidecarClient] and the sidecar process are the node's, shared
         * with its listener and its other connections, and shutting them here
         * would take an endpoint down to close one peering (ktn1l-D13).
         */
        override fun close() {
            shuttingDown = true
            closeRequested.set(true)
            runCatching { currentLink.get()?.close() }
            if (!ownsClient) return
            runCatching { client.shutdown() }
            runCatching { client.close() }
            sidecar.close()
        }
    }
}

/**
 * The verdict a [HelloGate] returns for one hello that has already been
 * resolved and admitted (DSC2 feature `computenet-ktn1l`, decision ktn1l-D12).
 *
 * Feature decision F3-D5 wrote the gate as `(key, direction) -> Boolean`. This
 * sealed type is the **later** decision (ktn1l-D12) and refines it rather than
 * replacing its intent: F3-D7 needs a refusal that carries a *reason* from the
 * same decision point, which a Boolean cannot express. [Admit] and
 * [CloseQuietly] are exactly F3-D5's two Boolean arms; [Refuse] is the third
 * arm F3-D7 adds.
 */
sealed interface Verdict {

    /** Proceed exactly as a gate-less Session would: hello, bind, announce. */
    data object Admit : Verdict

    /**
     * Close this link **without blaming anybody**: no [BoundaryDenial] is
     * recorded, no local hello is sent, no announcement follows, and no
     * dialling connection charges the drop against its unadmitted run. The
     * mutual-dial tie-break ([DSC2-DIAL-05], aas-D7) is the case it exists for
     * — the losing link of a pair this side itself opened is nobody's fault.
     *
     * [detail] names ids and shapes only; it reaches a log line, never a
     * denial record.
     */
    data class CloseQuietly(val detail: String) : Verdict

    /**
     * Refuse this link the way every other hello refusal is refused: a
     * [BoundaryDenial] on the admission seam carrying [reason] and
     * [principal], then the link is closed. [DSC2-ID-05]'s identity-mismatch
     * arm is what needs this.
     */
    data class Refuse(val reason: DenialReason, val principal: PeerId?, val detail: String) : Verdict
}

/**
 * The one policy hook on the hello path: consulted once per hello, **after**
 * this side's allowlist has already admitted the resolved identity and before
 * this side's own hello is written (ktn1l-D12).
 *
 * ## The ordering is a security property, not an implementation detail
 *
 * F3-D5 placed the hook "right after `resolveOrRefuse` and before
 * `admitAndBind`"; ktn1l-D12 moves it **inside** `admitAndBind`, past
 * `Session.admitted`. The difference matters: a gate is where supersession and
 * tie-break bookkeeping learns that a key is live, so a gate consulted before
 * `Peering.Side.allow` would let a **stranger's** hello — one the allowlist is
 * about to refuse — supersede or displace a peering with a peer this side
 * actually admits. Consulting the allowlist first means the gate only ever
 * sees hellos this side would have peered with anyway.
 *
 * Pinned by `IrohNodeTest`'s "a hello the allowlist refuses never reaches the
 * gate"; moving the consult above [IrohTransport.Session.admitted] fails it.
 *
 * ## What it may and may not do
 *
 * It runs on the sidecar reader thread, synchronously, inside the hello
 * handler: it is the one *synchronous* consult on that thread (ktn1l-D17), so
 * it must take locks only — never IO, never a dial, never a wait.
 *
 * [resolved] is the identity this side's binding resolved the link's key to —
 * the same value the allowlist judged and the same one the mirror will be
 * stamped with. [key] and [remoteNodeId] are two views of the *same* proven
 * key: the fingerprint and the 32 raw NodeId bytes, the latter being what a
 * peer table keyed by key identifier compares (F3-D3).
 */
fun interface HelloGate {

    fun judge(key: KeyId, remoteNodeId: ByteArray, direction: LinkDirection, resolved: PeerId): Verdict

    companion object {
        /** The default: every admitted hello proceeds. Existing callers get exactly today's path. */
        val ADMIT_ALL: HelloGate = HelloGate { _, _, _, _ -> Verdict.Admit }
    }
}

/**
 * True when every surrogate in this string is part of a high-low pair. A
 * string decoded from UTF-8 with replacement cannot hold a lone surrogate, so
 * this guards the claimed-name token's contract rather than a reachable decode
 * path; it is cheap and keeps the check next to the grammar that states it.
 */
private fun String.isWellFormedUtf16(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
            i += 2
            continue
        }
        if (Character.isLowSurrogate(c)) return false
        i++
    }
    return true
}
