package civictech.iroh.discover

import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.iroh.LinkDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure per-key state machine (`computenet-ktn1l.2`, feature
 * `computenet-ktn1l`).
 *
 * **No sidecar, no threads, no `Thread.sleep`, no real clock.** Time is a
 * `var now` the test moves by hand and the table reads through its injected
 * `clock`; every transition is driven by a method call. That is not a
 * convenience — it is the property the table was designed for (ktn1l-D18), so
 * a suite that needed a sidecar to reach a state would mean the design had
 * leaked. The one exception is the counter burst in the last test, which is
 * about concurrency and says so.
 *
 * The exception is deliberate on the other side too: the tie-break and the
 * supersession are proven HERE as decisions of a function, and proven again
 * over two nodes by task `.4`. Both are needed — this one pins the rule, that
 * one pins that the rule is what the wire actually runs.
 */
class PeerTableTest {

    /** A 32-byte endpoint id whose first byte is [first] — the real length, so `compareUnsigned` sees what production sees. */
    private fun key(first: Int): NodeKey = NodeKey(ByteArray(32).also { it[0] = first.toByte() })

    private fun bytes(first: Int): ByteArray = ByteArray(32).also { it[0] = first.toByte() }

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    // ---- 1. self ----

    @Test
    fun `observing this node's own key answers Self and retains nothing`() {
        var now = 0L
        val own = bytes(0x01)
        val table = PeerTable(own, maxRetained = 3) { now }

        now = 10
        assertEquals(Observation.Self, table.observe(NodeKey(own), listOf("addr"), now))
        assertEquals(0, table.keysRetained)
        assertEquals(emptyList(), table.snapshot())
    }

    @Test
    fun `the own key is compared by content, not by array identity`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }

        // A DIFFERENT ByteArray instance holding the same bytes: reference
        // equality would retain it and dial this node's own endpoint.
        assertEquals(Observation.Self, table.observe(NodeKey(bytes(0x01)), listOf("addr"), 1))
    }

    // ---- 2. a second sighting, per state ----

    @Test
    fun `a second sighting of a Retained key is Dialable again and refreshes addresses and lastSeen`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }
        val k = key(0x02)

        assertEquals(Observation.Dialable, table.observe(k, listOf("a1"), 10))
        assertEquals(Observation.Dialable, table.observe(k, listOf("a1", "a2"), 20))

        val view = table.snapshot().single()
        assertEquals(listOf("a1", "a2"), view.addresses)
        assertEquals(20, view.lastSeen)
    }

    @Test
    fun `a second sighting of a Dialling, Peered, Superseded, Abandoned or CONFIGURED key is Suppressed`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 16) { now }

        val dialling = key(0x02)
        table.observe(dialling, listOf("a"), 1)
        table.markDialling(dialling, attempt = 0)
        assertEquals(
            PeerState.Dialling(attempt = 0, since = 0),
            (table.observe(dialling, listOf("a"), 2) as Observation.Suppressed).state,
        )

        val peered = key(0x03)
        table.observe(peered, listOf("a"), 1)
        table.linkUp(peered, LinkDirection.OUTBOUND, linkId = 7, source = EntrySource.DISCOVERED)
        table.admitted(peered, linkId = 7, peer = alice)
        assertTrue((table.observe(peered, listOf("a"), 2) as Observation.Suppressed).state is PeerState.Peered)

        // Superseded is reached the only way it can be: a hello on a new key
        // resolving to an identity already peered under an old one.
        val rotated = key(0x04)
        table.judge(rotated, LinkDirection.INBOUND, linkId = 8, resolved = alice, now = 3)
        assertTrue((table.observe(peered, listOf("a"), 4) as Observation.Suppressed).state is PeerState.Superseded)

        val abandoned = key(0x05)
        table.observe(abandoned, listOf("a"), 1)
        table.abandon(abandoned, DenialReason.NOT_ADMITTED)
        assertTrue((table.observe(abandoned, listOf("a"), 2) as Observation.Suppressed).state is PeerState.Abandoned)

        // A configured peering has an owner outside this table: discovery must
        // not dial it however often the LAN advertises it ([DSC2-DIAL-07]).
        val configured = key(0x06)
        table.linkUp(configured, LinkDirection.OUTBOUND, linkId = 9, source = EntrySource.CONFIGURED)
        assertTrue(table.observe(configured, listOf("a"), 2) is Observation.Suppressed)
    }

    @Test
    fun `a re-appearing Expired key is Dialable again`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }
        val k = key(0x02)

        table.observe(k, listOf("a"), 1)
        now = 5
        assertTrue(table.expire(k))
        assertTrue(table.stateOf(k) is PeerState.Expired)

        assertEquals(Observation.Dialable, table.observe(k, listOf("a"), 9))
        assertEquals(listOf(k), table.nextDue(now = 9, maxInFlight = 4))
    }

    // ---- 3. the bound ----

    @Test
    fun `a full table evicts the oldest lastSeen entry to retain a new key`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }

        table.observe(key(0x02), listOf("a"), 1)
        table.observe(key(0x03), listOf("a"), 2)
        table.observe(key(0x04), listOf("a"), 3)

        assertEquals(Observation.Evicted(key(0x02)), table.observe(key(0x05), listOf("a"), 4))
        assertEquals(3, table.keysRetained)
        assertEquals(setOf(key(0x03).hex, key(0x04).hex, key(0x05).hex), table.snapshot().map { it.keyHex }.toSet())
    }

    @Test
    fun `a peered entry is never the eviction victim even when it is the oldest`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }
        val oldest = key(0x02)

        table.observe(oldest, listOf("a"), 1)
        table.linkUp(oldest, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(oldest, linkId = 1, peer = alice)
        table.observe(key(0x03), listOf("a"), 2)
        table.observe(key(0x04), listOf("a"), 3)

        assertEquals(Observation.Evicted(key(0x03)), table.observe(key(0x05), listOf("a"), 4))
        assertTrue(table.stateOf(oldest) is PeerState.Peered)
    }

    @Test
    fun `a table of nothing but peered entries rejects a new key and does not grow`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 3) { now }

        listOf(key(0x02), key(0x03), key(0x04)).forEachIndexed { i, k ->
            table.observe(k, listOf("a"), i.toLong())
            table.linkUp(k, LinkDirection.OUTBOUND, linkId = i.toLong(), source = EntrySource.DISCOVERED)
            table.admitted(k, linkId = i.toLong(), peer = PeerId("peer$i"))
        }

        assertEquals(Observation.Rejected, table.observe(key(0x05), listOf("a"), 9))
        assertEquals(3, table.keysRetained)
    }

    // ---- 4. the tie-break rule ----

    @Test
    fun `loserDirection gives the smaller id the outbound link and both sides the same physical link`() {
        assertEquals(LinkDirection.INBOUND, PeerTable.loserDirection(bytes(0x00), bytes(0x01)))
        assertEquals(LinkDirection.OUTBOUND, PeerTable.loserDirection(bytes(0x01), bytes(0x00)))
    }

    @Test
    fun `loserDirection compares UNSIGNED — 0x7f is smaller than 0x80, where a signed compare disagrees`() {
        // The one example that separates the two comparisons. Under Kotlin's
        // signed Byte order 0x80 is negative and would sort BEFORE 0x7f, so
        // both sides would believe they were the smaller one and both would
        // close their outbound link, leaving no peering at all.
        assertEquals(LinkDirection.INBOUND, PeerTable.loserDirection(bytes(0x7f), bytes(0x80)))
        assertEquals(LinkDirection.OUTBOUND, PeerTable.loserDirection(bytes(0x80), bytes(0x7f)))
    }

    @Test
    fun `loserDirection throws on equal ids rather than inventing an answer`() {
        assertFailsWith<IllegalArgumentException> { PeerTable.loserDirection(bytes(0x05), bytes(0x05)) }
    }

    // ---- 5. judge ----

    @Test
    fun `judge admits a hello with no other link and closes nothing`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        assertEquals(
            Judgement.Admit(close = null, closeLinkId = null),
            table.judge(k, LinkDirection.INBOUND, linkId = 1, resolved = alice, now = 5),
        )
        assertEquals(PeerState.Peered(LinkDirection.INBOUND, 1, alice, since = 5), table.stateOf(k))
    }

    @Test
    fun `own smaller — an inbound hello while the outbound is up is the tie-break loser`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)

        assertEquals(Judgement.CloseQuietly, table.judge(k, LinkDirection.INBOUND, linkId = 2, resolved = alice, now = 5))
        // The table is untouched: the inbound link is the caller's to close,
        // and the outbound one is still the peering-to-be.
        assertFalse(table.stateOf(k) is PeerState.Peered)
    }

    @Test
    fun `own smaller — an outbound hello while the inbound is up wins and names the inbound link to close`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.linkUp(k, LinkDirection.INBOUND, linkId = 1, source = EntrySource.ACCEPTED)

        assertEquals(
            Judgement.Admit(close = k, closeLinkId = 1),
            table.judge(k, LinkDirection.OUTBOUND, linkId = 2, resolved = alice, now = 5),
        )
        assertEquals(PeerState.Peered(LinkDirection.OUTBOUND, 2, alice, since = 5), table.stateOf(k))
    }

    @Test
    fun `own larger — the mirror image, where the outbound hello loses and the inbound one wins`() {
        var now = 0L
        val loser = PeerTable(bytes(0x02), maxRetained = 8) { now }
        val k = key(0x01)

        loser.linkUp(k, LinkDirection.INBOUND, linkId = 1, source = EntrySource.ACCEPTED)
        assertEquals(Judgement.CloseQuietly, loser.judge(k, LinkDirection.OUTBOUND, linkId = 2, resolved = alice, now = 5))

        val winner = PeerTable(bytes(0x02), maxRetained = 8) { now }
        winner.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        assertEquals(
            Judgement.Admit(close = k, closeLinkId = 1),
            winner.judge(k, LinkDirection.INBOUND, linkId = 2, resolved = alice, now = 5),
        )
    }

    @Test
    fun `a hello on a new key resolving to an already-peered identity supersedes the old key for good`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val old = key(0x02)
        val fresh = key(0x03)

        table.observe(old, listOf("a"), 1)
        table.linkUp(old, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(old, linkId = 1, peer = alice)

        now = 7
        assertEquals(Judgement.Supersede(old), table.judge(fresh, LinkDirection.INBOUND, linkId = 2, resolved = alice, now = 7))
        assertEquals(PeerState.Superseded(byKey = fresh, since = 7), table.stateOf(old))
        assertEquals("Superseded(by=${fresh.short})", table.snapshot().single { it.keyHex == old.hex }.state)

        // The whole point of Superseded: the old key does not come back. Even
        // after its link drops it is never dialable again (F3-D6).
        assertEquals(DownOutcome.NoRedial, table.linkDown(old, linkId = 1, now = 8))
        assertEquals(emptyList(), table.nextDue(now = 9_999, maxInFlight = 8))
    }

    @Test
    fun `a hello resolving this key to another identity than its live link is refused as IDENTITY_MISMATCH`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(k, linkId = 1, peer = alice)
        val before = table.stateOf(k)

        assertEquals(
            Judgement.Refuse(DenialReason.IDENTITY_MISMATCH, live = alice),
            table.judge(k, LinkDirection.INBOUND, linkId = 2, resolved = bob, now = 7),
        )
        assertEquals(before, table.stateOf(k), "the live link is untouched; only the newer one is refused")
        assertEquals(1, table.keysRetained)
    }

    @Test
    fun `a mismatch takes precedence over the tie-break it also satisfies`() {
        var now = 0L
        // own (0x01) < peer (0x02), so an INBOUND hello is also the tie-break
        // loser: both rules fire on this one hello and they disagree about
        // WHY the link goes away. IDENTITY_MISMATCH wins, because the
        // tie-break's premise — that these two links are the same peer — is
        // exactly what has failed (ktn1l-D18).
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(k, linkId = 1, peer = alice)

        assertEquals(
            Judgement.Refuse(DenialReason.IDENTITY_MISMATCH, live = alice),
            table.judge(k, LinkDirection.INBOUND, linkId = 2, resolved = bob, now = 7),
        )
    }

    // ---- 7. expiry, backoff and the in-flight bound ----

    @Test
    fun `expire moves a keyless entry to Expired and leaves a peered one alone`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val retained = key(0x02)
        val peered = key(0x03)

        table.observe(retained, listOf("a"), 1)
        table.observe(peered, listOf("a"), 1)
        table.linkUp(peered, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(peered, linkId = 1, peer = alice)

        now = 4
        assertTrue(table.expire(retained), "retries for a key with no link must be cancelled")
        assertTrue(table.stateOf(retained) is PeerState.Expired)

        assertFalse(table.expire(peered), "a key with a live link keeps it ([DSC2-DIAL-06])")
        assertTrue(table.stateOf(peered) is PeerState.Peered)

        assertEquals(DownOutcome.NoRedial, table.linkDown(retained, linkId = 99, now = 5))
        assertTrue(table.stateOf(retained) is PeerState.Expired)
    }

    @Test
    fun `an unplanned drop on a discovered peering returns the key to the dialable set at once`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.observe(k, listOf("a"), 1)
        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(k, linkId = 1, peer = alice)

        assertEquals(DownOutcome.Redial, table.linkDown(k, linkId = 1, now = 30))
        assertEquals(listOf(k), table.nextDue(now = 30, maxInFlight = 4))
        assertNull(table.snapshot().single().attributedPeer, "a dropped link's attribution does not outlive it")
    }

    @Test
    fun `an abandoned key does not come back when its link drops`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.observe(k, listOf("a"), 1)
        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(k, linkId = 1, peer = alice)
        table.abandon(k, DenialReason.NOT_ADMITTED)

        assertEquals(DownOutcome.NoRedial, table.linkDown(k, linkId = 1, now = 30))
        assertTrue(table.stateOf(k) is PeerState.Abandoned)
        assertEquals(emptyList(), table.nextDue(now = 9_999, maxInFlight = 8))
    }

    @Test
    fun `a configured peering's drop is its owner's to redial, never this policy's`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        // A configured peering never enters through observe: it is linked by
        // whoever configured it, and its reconnects are theirs ([DSC2-DIAL-07]).
        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.CONFIGURED)
        table.admitted(k, linkId = 1, peer = alice)

        assertEquals(DownOutcome.NoRedial, table.linkDown(k, linkId = 1, now = 30))
        assertEquals(emptyList(), table.nextDue(now = 9_999, maxInFlight = 8))
        assertEquals("CONFIGURED", table.snapshot().single().source)
    }

    @Test
    fun `the tie-break loser's drop is not a redial, and does not strip the winner's attribution`() {
        var now = 0L
        // own (0x01) < peer (0x02): INBOUND is the loser, so the OUTBOUND hello
        // wins and the inbound link is closed quietly. That close arrives back
        // here as a linkDown for a key that is STILL linked — the one case the
        // acceptance's NoRedial list does not enumerate, and the one that would
        // re-dial a peer this node is already peered with if it answered
        // Redial ([DSC2-DIAL-01]).
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)

        table.linkUp(k, LinkDirection.INBOUND, linkId = 1, source = EntrySource.ACCEPTED)
        assertEquals(
            Judgement.Admit(close = k, closeLinkId = 1),
            table.judge(k, LinkDirection.OUTBOUND, linkId = 2, resolved = alice, now = 5),
        )

        assertEquals(DownOutcome.NoRedial, table.linkDown(k, linkId = 1, now = 6))
        assertEquals(PeerState.Peered(LinkDirection.OUTBOUND, 2, alice, since = 5), table.stateOf(k))
        assertEquals("alice", table.snapshot().single().attributedPeer, "the surviving link keeps its attribution")
        assertEquals(emptyList(), table.nextDue(now = 6, maxInFlight = 8))

        // Only when the LAST link drops does the key become dialable again.
        assertEquals(DownOutcome.Redial, table.linkDown(k, linkId = 2, now = 7))
        assertNull(table.snapshot().single().attributedPeer)
        assertEquals(listOf(k), table.nextDue(now = 7, maxInFlight = 8))
    }

    @Test
    fun `dialFailed advances dueAt on the injected schedule and nextDue respects maxInFlight`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)
        val schedule = { attempt: Int -> 100L * (attempt + 1) }

        table.observe(k, listOf("a"), 0)
        assertTrue(table.markDialling(k, attempt = 0))
        assertEquals(100L, table.dialFailed(k, now = 0, schedule = schedule))
        assertTrue(table.markDialling(k, attempt = 1))
        assertEquals(300L, table.dialFailed(k, now = 100, schedule = schedule))
        assertTrue(table.markDialling(k, attempt = 2))
        assertEquals(600L, table.dialFailed(k, now = 300, schedule = schedule))

        // Two keys due, a budget of one.
        val other = key(0x03)
        table.observe(other, listOf("a"), 700)
        assertEquals(1, table.nextDue(now = 700, maxInFlight = 1).size)
        assertEquals(2, table.nextDue(now = 700, maxInFlight = 4).size)

        // The budget is maxInFlight MINUS what is already dialling.
        table.markDialling(other, attempt = 0)
        assertEquals(emptyList(), table.nextDue(now = 700, maxInFlight = 1))
    }

    // ---- 8. counters and the view ----

    @Test
    fun `every counter is monotonic under concurrent increments`() {
        val counters = DiscoveryCounters()
        val perThread = 5_000

        // The one place this suite uses threads, and it is about the counters'
        // thread safety, not about the table. No sleeps: the threads are
        // started and joined.
        val threads = (0 until 4).map {
            Thread { repeat(perThread) { counters.all.forEach { c -> c.increment() } } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        counters.all.forEach { assertEquals((4 * perThread).toLong(), it.count, it.name) }
    }

    @Test
    fun `refusedBy names exactly the reasons recorded and counts them apart`() {
        val counters = DiscoveryCounters()

        counters.refused(DenialReason.IDENTITY_MISMATCH)
        counters.refused(DenialReason.IDENTITY_MISMATCH)
        counters.refused(DenialReason.UNVOUCHED)

        assertEquals(
            mapOf(DenialReason.IDENTITY_MISMATCH to 2L, DenialReason.UNVOUCHED to 1L),
            counters.refusedBy(),
        )
        assertEquals(3L, counters.hellosRefused)
    }

    @Test
    fun `keysRetained is a gauge over the table and falls when an entry is evicted`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 2) { now }
        val counters = DiscoveryCounters(keysRetained = { table.keysRetained })

        assertEquals(0, counters.keysRetained())
        table.observe(key(0x02), listOf("a"), 1)
        table.observe(key(0x03), listOf("a"), 2)
        assertEquals(2, counters.keysRetained())
        table.observe(key(0x04), listOf("a"), 3)
        assertEquals(2, counters.keysRetained(), "the gauge is bounded by maxRetained, like the table")
    }

    @Test
    fun `the view and the counters carry public material only — hex, addresses, names`() {
        var now = 0L
        val table = PeerTable(bytes(0x01), maxRetained = 8) { now }
        val k = key(0x02)
        val counters = DiscoveryCounters(keysRetained = { table.keysRetained })

        table.observe(k, listOf("192.0.2.1:4000"), 1)
        table.linkUp(k, LinkDirection.OUTBOUND, linkId = 1, source = EntrySource.DISCOVERED)
        table.admitted(k, linkId = 1, peer = alice)
        counters.refused(DenialReason.IDENTITY_MISMATCH)

        val view = table.snapshot().single()
        assertEquals(k.hex, view.keyHex)
        assertEquals(64, view.keyHex.length)
        assertEquals("alice", view.attributedPeer, "the view carries the stamped name, not an identity object")
        assertEquals("Peered(OUTBOUND)", view.state)
        assertEquals("DISCOVERED", view.source)

        // Nothing rendered anywhere may look like key material or a statement.
        // The stamped PeerId's own name is the one identity string allowed.
        val rendered = listOf(view.toString(), table.snapshot().toString(), counters.toString(), k.toString())
        rendered.forEach { text ->
            listOf("credential", "statement", "privateKey", "secret", "BEGIN").forEach { forbidden ->
                assertFalse(text.contains(forbidden, ignoreCase = true), "$forbidden appeared in: $text")
            }
        }
    }

    @Test
    fun `NodeKey renders the short form in logs and the full public hex in the view`() {
        val k = key(0xab)
        assertEquals(k.hex.take(8), k.short)
        assertEquals("NodeKey(${k.short})", k.toString())
        assertEquals(k, NodeKey(k.bytes.copyOf()))
        assertEquals(k.hashCode(), NodeKey(k.bytes.copyOf()).hashCode())
    }
}
