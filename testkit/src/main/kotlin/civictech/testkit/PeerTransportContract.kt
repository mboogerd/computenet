package civictech.testkit

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.wire.HelloCredentialLimits
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.Peering
import civictech.cell.wire.UnsendableHelloCredentialsException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

/**
 * The executable contract of the kernel's transport seam
 * (`civictech.cell.wire.PeerTransport`, feature `computenet-gyvli`, gyvli-D1
 * and gyvli-D8 rule 1): every binding passes the same cases.
 *
 * A binding's test subclasses this and supplies [transport] (a fresh
 * instance per test, shared by both ends — bindings are rig-scoped) and
 * [listenAddress]. [stack] builds one side (registry, application host,
 * bridge host, [Peering.Side]); override it only if the binding needs a
 * differently built side.
 *
 * The bounds are the design's: a carrying link moves an announced ref within
 * [CARRY_MS]; a severed or closed one must not move one within [HOLD_MS].
 * A binding under test should run its re-dial backoff near zero, so that
 * "held" is a statement about the binding's intent flags rather than about a
 * backoff longer than the hold window.
 */
abstract class PeerTransportContract {

    /** A fresh transport for one test; both ends of the peering use it. */
    protected abstract fun transport(): PeerTransport

    /** Where the test's listener binds; may read [transport]. A "port 0"-style request is fine. */
    protected abstract fun listenAddress(): PeerAddress

    /** The binding's hello statement ceiling — what case (f) exceeds by one. */
    protected open val maxHelloStatements: Int = HelloCredentialLimits.DEFAULT_MAX_STATEMENTS

    /** One side of a peering: its registry, an application host, and the bridge host its [side] runs on. */
    class Stack(
        val name: String,
        val registry: LocationRegistry,
        val host: ManagedHost,
        val bridgeHost: ManagedHost,
        val side: Peering.Side,
    ) {
        /** Spawn a fresh [SetCell] on [host] and return its ref. */
        fun spawnCell(): CellRef = SetCell<String>().also { host.managementInlet.call.spawn(it) }.ref

        /** Is [ref] located at a peer, as seen from this stack's registry? */
        fun seesRemote(ref: CellRef): Boolean = registry.location(ref) is LocationRegistry.Remote
    }

    /**
     * Build one side named [name] (a label for messages), optionally holding
     * [credentials]. The side is anonymous unless it holds credentials, and
     * then asserts their identity: the contract is about carrying refs, not
     * about identity, and a configured-name `PeerId` constructed here would be
     * a new site for `IdentityDerivationRatchetTest` to judge.
     */
    protected open fun stack(name: String, credentials: PeerCredentials? = null): Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = credentials?.peerId, credentials = credentials)
        return Stack(name, registry, host, bridgeHost, side)
    }

    protected lateinit var transport: PeerTransport
        private set

    private val opened = mutableListOf<AutoCloseable>()

    /** Register [this] endpoint for closing after the test. */
    protected fun <T : AutoCloseable> T.closedAfter(): T = also { opened += it }

    @BeforeEach
    fun createTransport() {
        transport = transport()
    }

    @AfterEach
    fun closeEndpoints() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
    }

    /** A listener on a fresh listening stack, and a connection to it from a fresh dialling stack. */
    protected class Peered(val listening: Stack, val dialling: Stack, val listener: PeerListener, val connection: PeerConnection)

    protected fun peered(): Peered {
        val listening = stack("listener")
        val dialling = stack("dialler")
        val listener = transport.listen(listenAddress(), listening.side).closedAfter()
        val connection = transport.dial(listener.boundAddress, dialling.side).closedAfter()
        return Peered(listening, dialling, listener, connection)
    }

    /** Wait until a ref spawned on the listening stack is Remote on the dialling one — the link carries. */
    protected fun Peered.awaitCarried(ref: CellRef = listening.spawnCell()) {
        awaitUntil("${ref.id} spawned on the listener becomes Remote on the dialler", CARRY_MS) { dialling.seesRemote(ref) }
    }

    /** Fail if [condition] ever holds within [ms]. */
    protected fun neverWithin(what: String, ms: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (condition()) throw AssertionFailedError("expected never within ${ms}ms, but: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `(a) listen reports a bound address of the transport's scheme`() {
        val listener = transport.listen(listenAddress(), stack("listener").side).closedAfter()
        val bound = listener.boundAddress
        assertEquals(transport.scheme, bound.scheme, "bound address ${bound.text} has a foreign scheme")
        assertEquals(bound.text, transport.parseAddress(bound.text).text, "bound address does not round-trip")
    }

    @Test
    fun `(b) dial carries a spawned ref across`() {
        val p = peered()
        p.awaitCarried()
        assertTrue(p.connection.isCarrying, "a connection whose link carried reports isCarrying=false")
    }

    @Test
    fun `(c) a ref spawned after partition never crosses`() {
        val p = peered()
        p.awaitCarried()
        p.connection.partition()
        assertFalse(p.connection.isCarrying, "a partitioned connection reports isCarrying=true")
        val during = p.listening.spawnCell()
        neverWithin("${during.id}, spawned while partitioned, became Remote on the dialler", HOLD_MS) {
            p.dialling.seesRemote(during)
        }
    }

    @Test
    fun `(d) heal carries again`() {
        val p = peered()
        p.awaitCarried()
        p.connection.partition()
        val during = p.listening.spawnCell()
        p.connection.heal()
        assertTrue(p.connection.isCarrying, "heal() returned with isCarrying=false")
        p.awaitCarried(during) // the catch-up re-announces what was minted while severed
        p.awaitCarried() // and the healed link carries new refs
    }

    @Test
    fun `(e) a deliberate close arms no re-dial`() {
        val p = peered()
        p.awaitCarried()
        p.connection.close()
        assertFalse(p.connection.isCarrying, "a closed connection reports isCarrying=true")
        val after = p.listening.spawnCell()
        neverWithin("${after.id}, spawned after close(), became Remote on the dialler — something re-dialled", HOLD_MS) {
            p.dialling.seesRemote(after)
        }
        assertEquals(0, p.connection.stats.unadmittedOpens, "a deliberate close charged the refused-dial run")
    }

    @Test
    fun `(f) unsendable credentials refuse before any endpoint exists`() {
        val tooMany = FakeCredentials("overloaded", statements = maxHelloStatements + 1)
        val address = listenAddress()
        assertThrows(UnsendableHelloCredentialsException::class.java) {
            transport.listen(address, stack("overloaded", tooMany).side).closedAfter()
        }
        // nothing was bound: a well-configured side can listen at the same request
        val listening = stack("listener")
        val listener = transport.listen(address, listening.side).closedAfter()
        val before = listener.stats
        assertThrows(UnsendableHelloCredentialsException::class.java) {
            transport.dial(listener.boundAddress, stack("overloaded", tooMany).side).closedAfter()
        }
        assertEquals(before, listener.stats, "a refused dial reached the listener")
    }

    /**
     * (g) Ten partition/heal cycles leave each side's refs flat (gyvli-D3,
     * computenet-vzb): after every cycle has carried again, each side's
     * `localRefs()` and `remoteRefs()` sizes equal their sizes after the first
     * hello. A binding that leaves a superseded connection instance's cells
     * spawned grows by one local and one mirrored remote per side per heal
     * (vzb's loopback probe: 2/2 before the first heal, 12/12 after ten), and
     * fails here with both numbers in the message.
     */
    @Test
    fun `(g) ten partition-heal cycles leave refs flat`() {
        val p = peered()
        val carried = p.listening.spawnCell()
        p.awaitCarried(carried)
        // the dialler's cells cross the other way: wait for the listener's
        // mirrored count to settle too, so the baseline is a post-hello one
        val dialled = p.dialling.spawnCell()
        awaitUntil("${dialled.id} spawned on the dialler becomes Remote on the listener", CARRY_MS) {
            p.listening.seesRemote(dialled)
        }
        fun counts() = listOf(
            p.listening.registry.localRefs().size,
            p.listening.registry.remoteRefs().size,
            p.dialling.registry.localRefs().size,
            p.dialling.registry.remoteRefs().size,
        )
        val baseline = counts()

        repeat(CYCLES) { cycle ->
            p.connection.partition()
            p.connection.heal()
            p.awaitCarried(carried)
            awaitUntil("cycle $cycle: ${dialled.id} is Remote on the listener again", CARRY_MS) {
                p.listening.seesRemote(dialled)
            }
        }

        // a heal's catch-up lands asynchronously: give it the carry bound to
        // settle, then report the numbers whatever they are
        runCatching { awaitUntil("refs back at their post-hello counts", CARRY_MS) { counts() == baseline } }
        assertEquals(
            baseline,
            counts(),
            "[listener local, listener remote, dialler local, dialler remote] after $CYCLES partition/heal " +
                "cycles vs after the first hello",
        )
    }

    /**
     * A refused dial abandons after the policy's limit of unadmitted opens. A
     * named hook: a loopback cannot be refused, so the socket bindings
     * (ws, iroh) override it.
     *
     * An override of this hook must repeat `@Test`: JUnit 5 does not carry
     * the annotation onto an overriding method, so an unannotated override
     * silently removes the case — one test fewer, and the run stays green.
     */
    @Test
    open fun `a refused dial abandons after the refused-dial limit`() {
        Assumptions.abort<Unit>("owned by the ws and iroh binding tasks of feature computenet-gyvli")
    }

    /**
     * Credentials carrying [statements] placeholder statements. Never signs
     * anything real: case (f) needs only to be refused on the count. Its
     * [peerId] is resolved from its key through the interim binding, as
     * [PeerCredentials.peerId]'s KDoc requires of an implementation.
     */
    protected class FakeCredentials(name: String, statements: Int) : PeerCredentials {
        override val keyId: KeyId = KeyId(name)
        override val peerId: PeerId =
            (PeerIdentityBinding.Interim.resolve(keyId, emptyList()) as IdentityResolution.Bound).peer
        override val publicKey: ByteArray = ByteArray(0)

        override fun sign(message: ByteArray): ByteArray = ByteArray(0)

        override val statements: List<IdentityStatement> = List(statements) { i ->
            IdentityStatement(peerId, keyId, IssuerId("issuer-$i"), i.toLong(), 0, Long.MAX_VALUE, ByteArray(0))
        }
    }

    companion object {
        /** How long a carrying link may take to move an announced ref. */
        const val CARRY_MS: Long = 5_000

        /** How long a severed or closed link must hold. */
        const val HOLD_MS: Long = 2_000

        /** Partition/heal cycles case (g) runs (gyvli-D3's "ten"). */
        const val CYCLES: Int = 10
    }
}
