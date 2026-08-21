package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.Replicable
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.link.LinkResult
import civictech.cell.link.refusedSliceCount
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * 96 §E1.3 (E1-REPL): the OR-map joins the mergeable class. `SetCell`'s
 * replication story, dot-shaped — this is `ReplicatedSessionTest`'s three-peer
 * session and `SetConvergenceTest`'s control discipline applied to
 * [OrMapCell]/[TaggedMapDelta], where a key has a *value* and not merely
 * presence, so convergence must be asserted over both.
 *
 * ## computenet-4ru.1.7: kernel-fold subsumption judgment — nothing slimmed
 *
 * Checked against `civictech.oracle.tagged.ConvergenceCheckTest`,
 * `ConvergenceSweepTest`, `TaggedControlsTest` and `TaggedScenariosTest`
 * (computenet-4ru.1.4/1.6, now landed). No test below is annotated or slimmed
 * as subsumed — every one either exercises a property ORA2's oracle does not
 * model at all, or exercises it under conditions the oracle's own measured
 * evidence says it does not reach:
 *
 * - **The mid-run partition/heal in "three replicas converge... under 100
 *   seeds"** is explicitly out of ORA2's scope by design (feature computenet-4ru.1's D6 — the
 *   ORA1 epic's own D6 is a different decision, this
 *   feature's own bead: "Deliberately no faults... partition/reorder/
 *   duplicate/crash/journal/restart is CHA1's rig"). No oracle sweep injects
 *   a partition, so nothing here can be attributed to one.
 * - **Diamond dedup/re-emission, pull baseline, scoped pull, partial-interest
 *   mesh, superseded-source dot resurrection, and pull-merge re-baseline**
 *   are wire-protocol/replication-MECHANISM assertions — `Peering`,
 *   `DeadLetter`, `LinkResult` interest scoping, `MessageContext.baseline`
 *   stamping. ORA2's script model drives sources through
 *   `civictech.oracle.gen.CaseDelivery` (an abstract "replica X absorbed
 *   replica Y's prefix" statement) and never touches `Peering`/pull/interest
 *   at all — there is no oracle-side equivalent to name for any of these.
 * - **The 100-seed convergence sweep's own concurrency**, even where a real
 *   oracle sweep exists (`ConvergenceSweepTest`), is measured — not assumed —
 *   to realize at most 1 live dot per key across the default 40-seed range
 *   (computenet-4ru.1.7's own dispatch note, re-verified against the landed
 *   sweep). A kernel test asserting convergence under GENUINE concurrent
 *   same-key puts cannot be called subsumed by a sweep with no concurrency to
 *   check it against — the exact defect this bead's subsumption clause exists
 *   to prevent (removing an assertion with no oracle-side equivalent).
 *
 * Per this bead's own instruction: kept, not subsumed, because none of the
 * above is demonstrably covered without weakening what this file already
 * asserts.
 *
 * What is proven here:
 *
 * - **Convergence** (`[24-TMAP-01..04]`): 100 seeded three-peer meshes with a
 *   mid-run partition and heal, concurrent same-key puts and concurrent
 *   put/remove in the schedule — at idle every replica agrees on membership
 *   *and* on every key's value, and on the value the whole emission stream
 *   merges to.
 * - **Echo termination + diamond dedup**: a delta arriving over the second path
 *   of a diamond folds once and re-emits nothing.
 * - **Re-origination (C-10 / 93 I-14 Rule S4)**: a re-emission carries the
 *   *replica's own* wave source while the dots inside it are byte-identical.
 * - **Pull baseline** (spec 20/21 §Pull): since-filtered, tombstones included,
 *   `MessageContext.baseline`-stamped, to the requester alone; and a late
 *   joiner converging with a removed key still removed.
 * - **Dead-source fencing** (`[24-TAG-02]`): after a `ReBaseline` supersedes a
 *   source, its straggler dots never resurrect a key — across a snapshot carry
 *   too.
 * - **Both divergence controls** 96 §E1.3 names: a filter-less `applyRemote`
 *   explodes in re-emission volume, and an untagged arrival-order fold of the
 *   very same traffic diverges.
 */
class OrMapConvergenceTest {

    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    @Suppress("UNCHECKED_CAST")
    private val propagateTaggedMapDelta =
        (Propagate::class.java as Class<Propagate<TaggedMapDelta<String, String>>>)

    /** One broadcast emission with the wave context it rode (the re-origination assertion's subject). */
    private data class Emission(val delta: TaggedMapDelta<String, String>, val ctx: MessageContext?)

    /**
     * Record a replica's *broadcast* emissions. A plain `subscribe` installs no
     * link, so it fires no catch-up; targeted deliveries (`baselineTo`, the
     * on-link catch-up) bypass consumer fan-out and are deliberately not seen
     * here — this list is exactly what the mesh gossips.
     */
    private fun record(cell: OrMapCell<String, String>): MutableList<Emission> {
        val out = mutableListOf<Emission>()
        cell.outlet.subscribe(
            Use.fixed(
                Propagate<TaggedMapDelta<String, String>> { out += Emission(it, CurrentContext.get()) },
                PortRef.generate(),
            )
        )
        return out
    }

    private fun fold(deltas: List<TaggedMapDelta<String, String>>): TaggedMapDelta<String, String> =
        deltas.fold(TaggedMapDelta()) { acc, d -> acc.merge(d) }

    /** Every replica's `(membership, value-per-key)` view — the convergence subject. */
    private fun views(replicas: List<OrMapCell<String, String>>): List<Map<String, String?>> =
        replicas.map { replica -> replica.membership().associateWith { replica.value(it) } }

    // =====================================================================
    // the seeded three-peer session (mirrors ReplicatedSessionTest)
    // =====================================================================

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
        val deadLetters = mutableListOf<DeadLetter>()

        init {
            listOf(host, bridgeHost).forEach { h ->
                h.deadLetterOutlet.subscribe(
                    Use.fixed(Propagate<DeadLetter> { deadLetters += it }, PortRef.generate())
                )
            }
        }
    }

    private data class Session(
        val views: List<Map<String, String?>>,
        val deadLetters: List<DeadLetter>,
        val emissions: List<Emission>,
    )

    /**
     * Three peers, each writing only to its local replica, gossip the only
     * coordination; peer 0 drops off the network at op 15 and (when [heal])
     * rejoins at op 30. The schedule deliberately plants the two hard cases
     * inside the partition window, where the writers cannot have observed each
     * other: a same-key put on every replica (one LWW-by-dot-order winner must
     * emerge everywhere) and a put racing a remove (add-wins, reset-remove).
     */
    private fun runSession(seed: Long, heal: Boolean): Session {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(3) { Peer(controller) }
        val pq = Peering.loopback(peers[0].side, peers[1].side)
        val pr = Peering.loopback(peers[0].side, peers[2].side)
        Peering.loopback(peers[1].side, peers[2].side)

        // derived from the seed, not random: the dot source is derived from the
        // ref, so every (counter, sourceId) tie-break is reproducible per seed
        val logicalId = UUID(seed, ORMAP_SALT)
        val replicas = peers.mapIndexed { i, peer ->
            OrMapCell<String, String>(CellRef(logicalId, i.toLong()))
                .also { peer.replication.replicate(it, peer.host) }
        }
        controller.runToIdle()
        val emissions = replicas.map { record(it) }
        val ops = replicas.mapIndexed { i, replica ->
            (HostedCellProxy.create(replica.ref, peers[i].registry, OrMapInletProxy::class.java)
                    as OrMapInletProxy).inlet.call
        }

        val keys = listOf("milk", "eggs", "bread")
        for (op in 1..40) {
            when (op) {
                15 -> { pq.partition(); pr.partition() }
                20 -> ops.forEachIndexed { i, o -> o.put("milk", "concurrent-$i") } // same key, three writers
                22 -> { ops[0].put("eggs", "raced-put"); ops[1].remove("eggs") }    // put vs remove
                30 -> if (heal) { pq.heal(); pr.heal() }
            }
            val who = rnd.nextInt(3)
            val key = keys[rnd.nextInt(keys.size)]
            if (rnd.nextInt(10) < 7) ops[who].put(key, "w$who-op$op") else ops[who].remove(key)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return Session(views(replicas), peers.flatMap { it.deadLetters }, emissions.flatten())
    }

    @Test
    fun `three replicas converge on membership and values under 100 seeds with a partition and heal`() {
        forEachSeed(0L until 100L) { seed ->
            val session = runSession(seed, heal = true)
            withClue("seed $seed") {
                // every replica agrees — on which keys are present AND on each value
                session.views.toSet().size shouldBe 1
                // …and they agree on the right thing: the merge of every dot the
                // session produced, computed independently of the replicas' folds
                val merged = fold(session.emissions.map { it.delta })
                session.views[0] shouldBe merged.membership().associateWith { merged.value(it) }
                session.deadLetters.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `control - without the heal the partitioned replica diverges on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            if (runSession(seed, heal = false).views.toSet().size > 1) diverged++
        }
        // if this fails the harness is too weak to detect divergence — add ops
        (diverged > 0).shouldBeTrue()
    }

    @Test
    fun `control - the same gossip traffic applied untagged, in arrival order, diverges on at least one seed`() {
        val diverged = mutableListOf<Long>()
        for (seed in 0L until 100L) {
            val deltas = runSession(seed, heal = true).emissions.map { it.delta }
            val rnd = Random(seed xor 0x5EED)
            val untaggedViews = (0 until 4).map {
                val schedule = ArrayList(deltas)
                Collections.shuffle(schedule, rnd)
                untaggedFold(schedule)
            }
            // the same schedules under the dot algebra are order-blind
            val taggedViews = (0 until 4).map {
                val schedule = ArrayList(deltas)
                Collections.shuffle(schedule, rnd)
                fold(schedule).let { m -> m.membership().associateWith { m.value(it) } }
            }
            withClue("seed $seed") { taggedViews.toSet().size shouldBe 1 }
            if (untaggedViews.toSet().size > 1) diverged += seed
        }
        println("untagged arrival-order control diverged on ${diverged.size} seed(s): ${diverged.take(10)}")
        diverged.isNotEmpty().shouldBeTrue()
    }

    /**
     * The pre-dot semantics (control): apply each delta by key alone — a
     * tombstone retracts the key, a put sets it — with dels before puts (the
     * charitable reading, spec 20/24 §Tagged maps decided point 2), so any
     * divergence comes from *arrival order across deltas* and not from a
     * strawman intra-delta rule.
     */
    private fun untaggedFold(deltas: List<TaggedMapDelta<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        deltas.forEach { delta ->
            delta.dels.keys.forEach { out.remove(it) }
            delta.puts.forEach { (key, dots) -> dots.values.lastOrNull()?.let { out[key] = it } }
        }
        return out
    }

    // =====================================================================
    // echo termination, diamond dedup, re-origination
    // =====================================================================

    /** A full gossip mesh of [count] replicas behind one registry — every pair links. */
    private class Mesh(seed: Long, count: Int, val logicalId: UUID = UUID(0xE1, 0x3)) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hosts = List(count) { ManagedHost(scheduler = controller.scheduler(), registry = registry) }
        val replication = Replication(registry)
        val replicas = mutableListOf<OrMapCell<String, String>>()

        fun start(interests: List<Interest>? = null): List<OrMapCell<String, String>> {
            val cells = List(hosts.size) { i -> OrMapCell<String, String>(CellRef(logicalId, i.toLong())) }
            interests?.forEachIndexed { i, interest -> registry.setInterest(cells[i].ref, interest) }
            cells.forEachIndexed { i, cell -> replication.replicate(cell, hosts[i]) }
            replicas += cells
            controller.runToIdle()
            return cells
        }

        /** Spawn one more replica into the running mesh (the late joiner). */
        fun join(host: ManagedHost = hosts.last()): OrMapCell<String, String> =
            OrMapCell<String, String>(CellRef(logicalId, replicas.size.toLong() + 100L))
                .also { replication.replicate(it, host); replicas += it; controller.runToIdle() }

        fun ops(cell: OrMapCell<String, String>): MapOps<String, String> =
            (HostedCellProxy.create(cell.ref, registry, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call
    }

    @Test
    fun `a delta reaching a replica over both paths of a diamond folds once and re-emits once`() {
        val mesh = Mesh(seed = 3, count = 3)
        val (a, b, c) = mesh.start().let { Triple(it[0], it[1], it[2]) }
        val streams = listOf(a, b, c).map { record(it) }

        mesh.ops(a).put("milk", "2L")
        mesh.controller.runToIdle()

        // a emitted its local put; b and c each absorbed it and re-emitted the
        // novelty exactly once — the copy arriving over the *other* mesh path
        // (a→b→c and a→c→b) carries no new dot and dies there, and a's own dot
        // coming back to a dies too. Without the filter this count grows without
        // bound (see the divergence control below).
        streams.map { it.size } shouldBe listOf(1, 1, 1)
        listOf(a, b, c).forEach {
            it.membership() shouldBe setOf("milk")
            it.value("milk") shouldBe "2L"
        }

        // re-delivering the very same delta a second time changes nothing —
        // not the state, and not the re-emission volume
        b.deltaInlet.call.propagate(streams[0].single().delta)
        mesh.controller.runToIdle()
        streams.map { it.size } shouldBe listOf(1, 1, 1)
        b.membership() shouldBe setOf("milk")
        b.value("milk") shouldBe "2L"
    }

    @Test
    fun `a re-emission originates the replica's own wave while the dots travel byte-identical`() {
        val mesh = Mesh(seed = 4, count = 2)
        val (a, b) = mesh.start().let { it[0] to it[1] }
        val fromA = record(a)
        val fromB = record(b)

        mesh.ops(a).put("eggs", "6")
        mesh.controller.runToIdle()

        val origin = fromA.single()
        val relay = fromB.single()

        // C-10 / 93 I-14 Rule S4: the relay is a fresh origination under b's own
        // outlet epoch, never a forwarding of a's wave
        relay.ctx.shouldNotBeNull()
        relay.ctx!!.timestamp.sourceId shouldBe b.outlet.waveState().sourceId
        (relay.ctx.timestamp.sourceId == origin.ctx!!.timestamp.sourceId) shouldBe false

        // …while the dots inside are byte-identical — [24-TAG-01]: relayed state
        // preserves its tags, a cell never re-mints them for state it received
        relay.delta.puts shouldBe origin.delta.puts
        val dot = origin.delta.puts.getValue("eggs").keys.single()
        dot.sourceId shouldBe UUID.nameUUIDFromBytes("or-map-tags:${a.ref.id}:${a.ref.instanceId}".toByteArray())
        b.state().puts.getValue("eggs").keys.single() shouldBe dot
    }

    // =====================================================================
    // baseline / late join
    // =====================================================================

    @Test
    fun `a late-joining replica converges through catch-up, tombstones included`() {
        val mesh = Mesh(seed = 5, count = 2)
        val (a, b) = mesh.start().let { it[0] to it[1] }
        mesh.ops(a).put("milk", "2L")
        mesh.ops(b).put("eggs", "6")
        mesh.ops(a).put("bread", "1")
        mesh.controller.runToIdle()
        mesh.ops(b).remove("bread") // removed BEFORE the joiner exists
        mesh.controller.runToIdle()

        val late = mesh.join()

        late.membership() shouldBe setOf("milk", "eggs")
        late.value("milk") shouldBe "2L"
        late.value("eggs") shouldBe "6"
        late.value("bread") shouldBe null // the tombstone rode the catch-up
        // and it holds the tombstone itself, so it cannot resurrect the key
        // when a straggler carrying bread's dot arrives later
        late.state().dels.getValue("bread").isNotEmpty() shouldBe true
        views(listOf(a, b, late)).toSet().size shouldBe 1
    }

    @Test
    fun `the pull baseline replies since-filtered, tombstones included, to the requester alone`() {
        val producer = OrMapCell<String, String>()
        // isolate the pull path from the on-link push (the co-hosted fast path)
        producer.outlet.linking.onLinkedListeners.clear()
        val bystander = record(producer)

        producer.inlet.call.put("milk", "2L")
        producer.inlet.call.put("eggs", "6")
        producer.inlet.call.remove("eggs")
        bystander.size shouldBe 3

        val probe = FanInlet(propagateTaggedMapDelta)
        val replies = mutableListOf<Emission>()
        probe.serve(Propagate<TaggedMapDelta<String, String>> { replies += Emission(it, CurrentContext.get()) })
        @Suppress("UNCHECKED_CAST")
        val link = (producer.outlet.linkTo(probe as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
                as LinkResult.Connected).link

        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, null))

        replies.size shouldBe 1
        val baseline = replies.single()
        baseline.ctx!!.baseline.shouldNotBeNull() // stamped as a catch-up baseline, not a live wave
        baseline.delta.membership() shouldBe setOf("milk")
        baseline.delta.value("milk") shouldBe "2L"
        baseline.delta.value("eggs") shouldBe null
        baseline.delta.dels.getValue("eggs").isNotEmpty() shouldBe true // tombstones included
        bystander.size shouldBe 3 // to the requester alone — never broadcast

        // incremental: only the dots the reported frontier has not observed
        val frontier = baseline.ctx.baseline!!
        producer.inlet.call.put("bread", "1")
        // the probe is a linked consumer, so it sees the ordinary live wave too
        // — and that one is NOT baseline-stamped, unlike the replies around it
        replies.size shouldBe 2
        replies[1].ctx!!.baseline.shouldBeNull()

        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, frontier))

        replies.size shouldBe 3
        replies[2].delta.puts.keys shouldBe setOf("bread")
        replies[2].delta.dels.isEmpty() shouldBe true
        replies[2].ctx!!.baseline.shouldNotBeNull()

        // a pull that has nothing beyond the frontier answers nothing at all
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, replies[2].ctx!!.baseline!!))
        replies.size shouldBe 3
    }

    @Test
    fun `a scoped pull returns only the requester's slice`() {
        val producer = OrMapCell<String, String>()
        producer.outlet.linking.onLinkedListeners.clear()
        producer.inlet.call.put("milk", "2L")
        producer.inlet.call.put("eggs", "6")
        producer.inlet.call.remove("eggs")

        val probe = FanInlet(propagateTaggedMapDelta)
        val replies = mutableListOf<TaggedMapDelta<String, String>>()
        probe.serve(Propagate<TaggedMapDelta<String, String>> { replies += it })
        @Suppress("UNCHECKED_CAST")
        val link = (producer.outlet.linkTo(probe as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
                as LinkResult.Connected).link

        val milkOnly = Interest.Slots(setOf(Interest.Slots.slotOf("milk", 8)), 8)
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, null, milkOnly))

        replies.size shouldBe 1
        replies.single().keys() shouldBe setOf("milk")
    }

    // =====================================================================
    // interest-scoped mesh — the Scoped decision, exercised
    // =====================================================================

    @Test
    fun `a partial-interest mesh slices the delta instead of refusing it`() {
        val refusedBefore = refusedSliceCount.get()
        val mesh = Mesh(seed = 6, count = 2, logicalId = UUID(0xE1, 0x31))
        // b wants only the slot "milk" hashes to; a wants everything
        val milkSlot = Interest.Slots.slotOf("milk", 8)
        val (a, b) = mesh.start(listOf(Interest.Total, Interest.Slots(setOf(milkSlot), 8))).let { it[0] to it[1] }

        mesh.ops(a).put("milk", "2L")
        mesh.ops(a).put("eggs", "6")
        mesh.controller.runToIdle()

        a.membership() shouldBe setOf("milk", "eggs")
        // the out-of-interest key never crossed — and the delta was SLICED, not
        // dropped whole (`sliceTo` refuses a non-Scoped delta on a non-Total
        // link; TaggedMapDelta implements Scoped precisely so this mesh is legal)
        b.membership() shouldBe setOf("milk")
        b.value("milk") shouldBe "2L"
        refusedSliceCount.get() shouldBe refusedBefore
    }

    // =====================================================================
    // ReBaseline dead-source fencing
    // =====================================================================

    /** A stand-in peer that emits hand-built deltas, plainly or as a `ReBaseline`. */
    private class DeltaSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<TaggedMapDelta<String, String>>>())

        fun emit(delta: TaggedMapDelta<String, String>) = outlet.originate { propagate(delta) }

        fun reBaseline(supersedes: Set<UUID>, supersede: Boolean, delta: TaggedMapDelta<String, String>) =
            outlet.reBaseline(supersedes, supersede) { propagate(delta) }
    }

    @Test
    fun `a superseded source's dots never resurrect a key`() {
        val dead = UUID(0xDEAD, 1)
        val healthy = UUID(0x11, 1)
        val replica = OrMapCell<String, String>()
        val source = DeltaSource()
        source.outlet.subscribe(replica.deltaInlet)
        val emitted = record(replica)

        source.emit(
            TaggedMapDelta(
                puts = mapOf(
                    "milk" to mapOf(Timestamp(dead, 1) to "from-dead"),
                    "eggs" to mapOf(Timestamp(healthy, 1) to "from-healthy"),
                )
            )
        )
        replica.membership() shouldBe setOf("milk", "eggs")

        // the producer RESTARTs: its re-baseline supersedes `dead` and re-asserts
        // only "eggs" (spec 20/24 §Tag continuity, [24-TAG-02])
        source.reBaseline(
            setOf(dead),
            supersede = true,
            delta = TaggedMapDelta(puts = mapOf("eggs" to mapOf(Timestamp(healthy, 1) to "from-healthy"))),
        )

        // (a) the un-reasserted dot of the dead source is retracted…
        replica.membership() shouldBe setOf("eggs")
        replica.value("milk") shouldBe null
        // …as a TOMBSTONE, re-emitted so peers converge without seeing the notice
        emitted.last().delta.dels.getValue("milk") shouldBe setOf(Timestamp(dead, 1))
        // and the notice genuinely does NOT ride along. This is the synchronous
        // outlet→deltaInlet hop, where the sender's `reBaseline { … }` frame is
        // still on the stack: `originate` clears CurrentContext but a freshly
        // minted context reads PendingReBaseline, so without applyRemote's
        // explicit clear the re-emission would carry `supersede = true` over a
        // novelty-only delta — the translation applyReBaseline exists to avoid.
        emitted.last().ctx!!.reBaseline.shouldBeNull()

        // (c) a straggler carrying the dead source's dots — the one already
        // absorbed and a later one never seen — resurrects nothing, and is
        // silent: there is no new information to re-emit
        val emittedBefore = emitted.size
        source.emit(
            TaggedMapDelta(
                puts = mapOf(
                    "milk" to mapOf(Timestamp(dead, 1) to "from-dead", Timestamp(dead, 9) to "long-lost"),
                )
            )
        )
        replica.membership() shouldBe setOf("eggs")
        replica.value("milk") shouldBe null
        emitted.size shouldBe emittedBefore

        // the fence is source-scoped: a healthy writer still reaches the key
        source.emit(TaggedMapDelta(puts = mapOf("milk" to mapOf(Timestamp(healthy, 5) to "alive-again"))))
        replica.value("milk") shouldBe "alive-again"

        // and it survives the checkpoint carry Replication.rebind uses
        val promoted = OrMapCell<String, String>()
        promoted.restore(replica.snapshot())
        promoted.deltaInlet.call.propagate(
            TaggedMapDelta(puts = mapOf("milk" to mapOf(Timestamp(dead, 12) to "still-dead")))
        )
        promoted.value("milk") shouldBe "alive-again"
    }

    @Test
    fun `a pull-merge re-baseline retracts nothing and fences nothing`() {
        val other = UUID(0xBEEF, 1)
        val replica = OrMapCell<String, String>()
        val source = DeltaSource()
        source.outlet.subscribe(replica.deltaInlet)

        source.emit(TaggedMapDelta(puts = mapOf("milk" to mapOf(Timestamp(other, 1) to "v1"))))
        // supersede = false is forward idempotent merge only (93 I-22 R5)
        source.reBaseline(setOf(other), supersede = false, delta = TaggedMapDelta())
        replica.membership() shouldBe setOf("milk")

        source.emit(TaggedMapDelta(puts = mapOf("milk" to mapOf(Timestamp(other, 2) to "v2"))))
        replica.value("milk") shouldBe "v2" // the lane was never fenced
    }

    // =====================================================================
    // divergence control (a): applyRemote without the new-dots filter
    // =====================================================================

    /**
     * 96 §E1.3's first named control: the same cell with the novelty filter
     * *removed*. Its state still converges (the merge is idempotent) — what
     * breaks is termination: every arrival is re-broadcast whole, so a single
     * injected delta circulates the mesh forever. [CAP] stops the run so the
     * test reports an explosion instead of hanging; reaching it *is* the
     * failure signal.
     */
    private class EchoingOrMapCell(override val ref: CellRef) : Cell, Replicable<TaggedMapDelta<String, String>> {
        override val outlet = registerPort("outlet", FanOutlet.create<Propagate<TaggedMapDelta<String, String>>>())
        override val deltaInlet =
            registerPort("deltaInlet", FanInlet.create<Propagate<TaggedMapDelta<String, String>>>())

        var state = TaggedMapDelta<String, String>()
            private set
        var emissions = 0
            private set

        init {
            deltaInlet.serve(Propagate<TaggedMapDelta<String, String>> { delta ->
                state = state.merge(delta) // converges…
                if (emissions < CAP) {
                    emissions++
                    outlet.originate { propagate(delta) } // …but never stops talking
                }
            })
        }

        companion object {
            const val CAP = 300
        }
    }

    @Test
    fun `control - an applyRemote without the new-dot filter re-emits without bound`() {
        val controller = SimulationController(7)
        val registry = LocationRegistry()
        val hosts = List(3) { ManagedHost(scheduler = controller.scheduler(), registry = registry) }
        val replication = Replication(registry)
        val logicalId = UUID(0xE1, 0x3C)
        val cells = List(3) { i -> EchoingOrMapCell(CellRef(logicalId, i.toLong())) }
        cells.forEachIndexed { i, cell -> replication.replicate(cell, hosts[i]) }
        controller.runToIdle()

        val dot = Timestamp(UUID(0x5, 0x5), 1)
        cells[0].deltaInlet.call.propagate(TaggedMapDelta(puts = mapOf("milk" to mapOf(dot to "2L"))))
        // bounded drain: the mesh cannot quiesce, so cap the steps rather than
        // waiting on the controller's own livelock budget
        repeat(4000) { controller.step() }

        // one injected delta, hundreds of re-emissions: the echo never died
        cells.sumOf { it.emissions } shouldBe EchoingOrMapCell.CAP * 3
        cells.forEach { it.state.value("milk") shouldBe "2L" } // state converged all the same

        // the filtered cell under the identical injection: three folds, and quiet
        val mesh = Mesh(seed = 7, count = 3, logicalId = UUID(0xE1, 0x3D))
        val filtered = mesh.start()
        val streams = filtered.map { record(it) }
        filtered[0].deltaInlet.call.propagate(TaggedMapDelta(puts = mapOf("milk" to mapOf(dot to "2L"))))
        mesh.controller.runToIdle() // quiesces — asserted by runToIdle's own budget
        streams.sumOf { it.size } shouldBe 3
        filtered.forEach { it.value("milk") shouldBe "2L" }
    }

    private companion object {
        /** A fixed logical-id salt, so a session's refs are a pure function of its seed. */
        const val ORMAP_SALT = 0x04A9L
    }
}
