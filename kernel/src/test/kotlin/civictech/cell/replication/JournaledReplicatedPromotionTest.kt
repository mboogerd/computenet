package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.DeliveryTracking
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.StabilityReclaim
import civictech.cell.data.delta.TagLaneContinuity
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.graph
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * uwt8b-D2/D7..D11: the journal is part of a rolling replicated promotion's
 * COMMIT. Peer 0 crashes after its own commit while peers 1 and 2 still run the
 * incumbent. Recovery must materialize the recorded candidate under the reused
 * ref before ordinary gossip re-converges the mixed-version mesh.
 */
class JournaledReplicatedPromotionTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private enum class Mode { REUSE_REF, FRESH_REF }

    private companion object {
        val created = ConcurrentHashMap<CellRef, Cell>()
    }

    private object IncumbentFactory : TypedCellFactory<SetCell<String>> {
        override fun create(ref: CellRef): SetCell<String> = SetCell<String>(ref).also { created[ref] = it }
    }

    /** Class/version witness while retaining SetCell's exact replicated semantics. */
    private class CandidateSetCell(
        override val ref: CellRef,
        private val delegate: SetCell<String> = SetCell(ref),
    ) : Cell,
        Replicable<SetDelta<String>>,
        Stateful,
        DeliveryTracking by delegate,
        StabilityReclaim by delegate,
        TagLaneContinuity by delegate {
        val version: Int = 2
        val inlet = registerPort("inlet", delegate.inlet)
        override val outlet = registerPort("outlet", delegate.outlet)
        override val deltaInlet = registerPort("deltaInlet", delegate.deltaInlet)

        fun membership(): Set<String> = delegate.membership()

        override fun snapshot(): Serializable = delegate.snapshot()

        override fun restore(state: Serializable) = delegate.restore(state)
    }

    private object CandidateFactory : TypedCellFactory<CandidateSetCell> {
        override fun create(ref: CellRef): CandidateSetCell = CandidateSetCell(ref).also { created[ref] = it }
    }

    private class Peer(
        private val controller: SimulationController,
        val journal: InMemoryJournal = InMemoryJournal(),
    ) {
        val registry = LocationRegistry()
        lateinit var host: ManagedHost
            private set
        lateinit var bridgeHost: ManagedHost
            private set
        lateinit var side: Peering.Side
            private set
        lateinit var replication: Replication
            private set
        lateinit var context: ApplyContext
            private set
        private var hostScheduler: HostScheduler? = null
        private var bridgeScheduler: HostScheduler? = null

        init {
            rebuild()
        }

        /** Crash boundary: only [registry] and [journal] survive. */
        fun rebuild() {
            hostScheduler?.shutdown()
            bridgeScheduler?.shutdown()
            val nextHostScheduler = controller.scheduler()
            val nextBridgeScheduler = controller.scheduler()
            lateinit var nextContext: ApplyContext
            val nextReplication = Replication(registry)
            val nextHost = ManagedHost(
                scheduler = nextHostScheduler,
                registry = registry,
                journalFor = { ref -> nextContext.journalFor(ref) },
            )
            nextContext = ApplyContext(
                host = nextHost,
                replication = nextReplication,
                journals = mapOf("j" to journal),
                topology = journal,
            )
            hostScheduler = nextHostScheduler
            bridgeScheduler = nextBridgeScheduler
            host = nextHost
            bridgeHost = ManagedHost(scheduler = nextBridgeScheduler, registry = registry)
            side = Peering.Side(registry, bridgeHost)
            replication = nextReplication
            context = nextContext
        }
    }

    /** The origin add/del tags a released SetDelta invocation depends on. */
    private val originTags: (civictech.cell.proxy.Invocation) -> Collection<Timestamp> = { invocation ->
        (invocation.args.firstOrNull() as? SetDelta<*>)
            ?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    private fun reroute(
        outlet: FanOutlet<Propagate<SetDelta<String>>>,
        inletRef: PortRef,
        routed: Propagate<SetDelta<String>>,
    ) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    private data class Observation(val element: String, val allMembersDelivered: Boolean)

    private data class JournalFact(
        val event: TopoEvent.Promote,
        val checkpointBeforePromotion: Boolean,
    )

    private data class Run(
        val memberships: List<Set<String>>,
        val surfaced: List<Observation>,
        val universe: Set<String>,
        val recoveredCandidate: CandidateSetCell?,
        val liveFactoryWasCandidate: Boolean,
        val recoveredFactoryWasCandidate: Boolean,
        val peer0Journal: JournalFact?,
    )

    private fun membership(cell: Replicable<SetDelta<String>>): Set<String> = when (cell) {
        is SetCell<*> -> {
            @Suppress("UNCHECKED_CAST")
            (cell as SetCell<String>).membership()
        }

        is CandidateSetCell -> cell.membership()
        else -> error("unexpected replica class ${cell.javaClass.name}")
    }

    private fun replica(ref: CellRef): Replicable<SetDelta<String>> {
        @Suppress("UNCHECKED_CAST")
        return created.getValue(ref) as Replicable<SetDelta<String>>
    }

    private fun operations(peer: Peer, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, peer.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun decoded(journal: Journal): List<DecodedJournalRecord> =
        journal.replay().map(JournalRecords::decode)

    private fun journalFact(journal: Journal): JournalFact? {
        val records = decoded(journal)
        val promotions = records.withIndex().flatMap { (index, record) ->
            if (record is DecodedJournalRecord.Topology) {
                record.events.filterIsInstance<TopoEvent.Promote>().map { index to it }
            } else {
                emptyList()
            }
        }
        if (promotions.size != 1) return null
        val (promotionIndex, event) = promotions.single()
        return JournalFact(
            event = event,
            checkpointBeforePromotion = records.withIndex().any { (index, record) ->
                index < promotionIndex && record is DecodedJournalRecord.Checkpoint
            },
        )
    }

    private fun runRoll(seed: Long, mode: Mode, crashPeer0: Boolean): Run {
        val controller = SimulationController(seed)
        val random = Random(seed)
        val peers = List(3) { Peer(controller) }
        var peer0Links = listOf(
            Peering.loopback(peers[0].side, peers[1].side),
            Peering.loopback(peers[0].side, peers[2].side),
        )
        Peering.loopback(peers[1].side, peers[2].side)

        val logicalId = UUID.randomUUID()
        val refs = List(3) { index -> CellRef(logicalId, index.toLong()) }
        peers.forEachIndexed { index, peer ->
            graph(peer.context) {
                spawn(
                    "replica",
                    IdentityBinding.Exact(refs[index]),
                    replicated = true,
                    journalId = "j",
                    factory = IncumbentFactory,
                )
            }
        }
        controller.runToIdle()
        val current = refs.map(::replica).toMutableList()

        // The consumer lives on never-crashed peer 2 and draws its stable arm
        // there; only the replica objects at peers 0 and 1 roll beneath it.
        val glitchFree = GlitchFreeCell(
            @Suppress("UNCHECKED_CAST")
            (Propagate::class.java as Class<Propagate<SetDelta<String>>>),
        )
        peers[2].host.managementInlet.call.spawn(glitchFree)
        val routed = peers[2].host.lookup<SetDeltaInletProxy>(glitchFree.ref)!!.inlet.call
        @Suppress("UNCHECKED_CAST")
        val stableOutlet = current[2].outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST")
        val glitchInlet = glitchFree.inlet as LinkFrom<Propagate<SetDelta<String>>>
        (stableOutlet.linkTo(glitchInlet) is LinkResult.Connected).shouldBeTrue()
        reroute(stableOutlet, glitchFree.inlet.ref, routed)

        val observations = mutableListOf<Observation>()
        glitchFree.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            val element = delta.adds.keys.firstOrNull() ?: return@Propagate
            val liveByRef = current.associateBy { it.ref }
            val delivered = peers[2].registry.replicasOf(logicalId)
                .mapNotNull(liveByRef::get)
                .all { element in membership(it) }
            observations += Observation(element, delivered)
        }, PortRef.generate()))
        val frontier: ReplicaFrontier = peers[2].replication.replicaFrontier(logicalId)
        glitchFree.useReplicaFrontier(frontier, originTags)
        peers[2].replication.onWatermarkAdvance(logicalId) { glitchFree.recheck() }

        val ops = peers.mapIndexed { index, peer -> operations(peer, refs[index]) }.toMutableList()
        var recoveredCandidate: CandidateSetCell? = null
        var liveFactoryWasCandidate = true
        var recoveredFactoryWasCandidate = false

        fun promote(index: Int) {
            val peer = peers[index]
            val incumbent = current[index]
            when (mode) {
                Mode.REUSE_REF -> {
                    peer.context.promoteReplica(incumbent.ref, CandidateFactory)
                    current[index] = replica(incumbent.ref)
                    liveFactoryWasCandidate = liveFactoryWasCandidate &&
                        peer.context.live().spawns.getValue(incumbent.ref).factory.javaClass == CandidateFactory.javaClass
                }

                Mode.FRESH_REF -> {
                    // Sibling control (b), now on journal-selected hosts: a
                    // distinct ref re-mints both identity lanes. The journal
                    // binding cannot make that fresh identity equivalent.
                    val candidate = CandidateSetCell(CellRef(logicalId, 100L + index))
                    candidate.restore((incumbent as Stateful).snapshot())
                    peer.context.bind(candidate.ref, peer.journal)
                    peer.replication.replicate(candidate, peer.host)
                    peer.host.managementInlet.call.despawn(incumbent.ref)
                    current[index] = candidate
                    ops[index] = operations(peer, candidate.ref)
                }
            }
        }

        val universe = mutableSetOf<String>()
        val alphabet = listOf("apple", "banana", "cherry", "date", "elder", "fig", "grape")
        for (op in 1..40) {
            if (op == 12) promote(0)
            if (op == 18 && crashPeer0) {
                peer0Links.forEach { it.partition() }
                peers[0].rebuild()
                val recovery = peers[0].context.recover(peers[0].journal)
                controller.runToIdle()
                recovery.awaitApplied(30_000)
                recoveredCandidate = created.getValue(refs[0]) as? CandidateSetCell
                current[0] = replica(refs[0])
                recoveredFactoryWasCandidate =
                    peers[0].context.live().spawns.getValue(refs[0]).factory.javaClass == CandidateFactory.javaClass
                ops[0] = operations(peers[0], refs[0])
                peer0Links = listOf(
                    Peering.loopback(peers[0].side, peers[1].side),
                    Peering.loopback(peers[0].side, peers[2].side),
                )
                controller.runToIdle()

                // The first post-recovery local mint is deliberately forced on
                // the recovered candidate: a restarted tag counter would alias
                // an already-emitted ref-derived tag and fail final convergence.
                val recoveredMint = "recovered-mint-$seed"
                ops[0].add(recoveredMint)
                universe += recoveredMint
            }
            if (op == 26) promote(1)

            val writer = random.nextInt(3)
            val element = "$op-${alphabet[random.nextInt(alphabet.size)]}"
            ops[writer].add(element)
            universe += element
            repeat(random.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        return Run(
            memberships = current.map(::membership),
            surfaced = observations,
            universe = universe,
            recoveredCandidate = recoveredCandidate,
            liveFactoryWasCandidate = liveFactoryWasCandidate,
            recoveredFactoryWasCandidate = recoveredFactoryWasCandidate,
            peer0Journal = journalFact(peers[0].journal),
        )
    }

    @Test
    fun `a peer killed between rolling commits recovers the candidate and the mesh re-converges - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val run = runRoll(seed, Mode.REUSE_REF, crashPeer0 = true)
            run.memberships.forEach { it shouldBe run.universe }
            run.surfaced.forEach { it.allMembersDelivered.shouldBeTrue() }
            val recoveredCandidate = run.recoveredCandidate.shouldBeInstanceOf<CandidateSetCell>()
            recoveredCandidate.version shouldBe 2
            run.liveFactoryWasCandidate.shouldBeTrue()
            run.recoveredFactoryWasCandidate.shouldBeTrue()

            val fact = run.peer0Journal.shouldBeInstanceOf<JournalFact>()
            fact.checkpointBeforePromotion.shouldBeTrue()
            fact.event.incumbent shouldBe fact.event.candidate
            fact.event.replicated.shouldBeTrue()
            fact.event.candidateFactory.javaClass shouldBe CandidateFactory.javaClass
        }
    }

    private fun diverges(run: Run): Boolean =
        run.memberships.any { it != run.universe } || run.surfaced.any { !it.allMembersDelivered }

    @Test
    fun `control b - a fresh ref still diverges on the journaled mesh`() {
        val seed = (0L until 100L).firstOrNull { candidate ->
            diverges(runRoll(candidate, Mode.FRESH_REF, crashPeer0 = false))
        }
        (seed != null).shouldBeTrue()
    }
}
