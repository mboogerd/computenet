package civictech.agora

import civictech.agora.cell.CredenceUpdate
import civictech.agora.cell.ClaimApi
import civictech.agora.cell.InfluenceDelta
import civictech.agora.cell.Polarity
import civictech.agora.cell.StanceDelta
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.UnlinkStep
import civictech.cell.host.HostScheduler
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.testkit.awaitDrained
import java.util.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurabilityTest {

    @Test
    fun `a torn edge removal is completed during recovery`() {
        val q = 1e-3
        val a = CellRef(UUID.nameUUIDFromBytes("torn-removal:a".toByteArray()))
        val b = CellRef(UUID.nameUUIDFromBytes("torn-removal:b".toByteArray()))
        val c = CellRef(UUID.nameUUIDFromBytes("torn-removal:c".toByteArray()))
        val edge = CellRef(UUID.nameUUIDFromBytes("torn-removal:edge".toByteArray()))
        val cascaded = CellRef(UUID.nameUUIDFromBytes("torn-removal:cascaded".toByteArray()))

        data class World(
            val controller: SimulationController,
            val host: ManagedHost,
            val context: ApplyContext,
            val service: AgoraService,
        )

        fun world(journal: InMemoryJournal): World {
            val controller = SimulationController(23L)
            val registry = LocationRegistry()
            val host = ManagedHost(
                scheduler = controller.scheduler(),
                registry = registry,
                attention = civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
                journal = journal,
            )
            val context = ApplyContext(host, journals = mapOf("host" to journal), topology = journal)
            return World(controller, host, context, AgoraService(host, registry, quiescence = q, context = context))
        }

        fun build(journal: InMemoryJournal): World = world(journal).also { built ->
            built.service.createClaim("A", a)
            built.service.createClaim("B", b)
            built.service.createClaim("C", c)
            built.service.createEdge(a, b, Polarity.ATTACK, edge)
            built.service.createEdge(edge, c, Polarity.SUPPORT, cascaded)
            built.service.setStance(a, "author", 0.9)
            built.service.setStance(edge, "author", 0.8)
            built.service.setStance(cascaded, "author", 0.7)
            built.controller.runToIdle()
        }

        val completed = build(InMemoryJournal())
        completed.service.remove(edge)
        completed.controller.runToIdle()
        val expected = completed.service.graph().associateBy { it.ref }

        val tornJournal = InMemoryJournal()
        val torn = build(tornJournal)
        GraphSpec(
            listOf(
                UnlinkStep("claim:${a.id}", "credenceOutlet", "edge:${edge.id}", "sourceInlet"),
                UnlinkStep("edge:${edge.id}", "credenceOutlet", "edge:${cascaded.id}", "sourceInlet"),
            ),
        ).apply(torn.context)

        val recovered = world(tornJournal)
        recovered.context.recover(tornJournal)
        recovered.controller.runToIdle()
        recovered.service.rebuildIndex()
        recovered.controller.runToIdle()
        val actual = recovered.service.graph().associateBy { it.ref }

        assertEquals(expected.keys, actual.keys, "recovered topology differs from a completed removal")
        assertEquals(
            expected.mapValues { it.value.info },
            actual.mapValues { it.value.info },
            "recovered node infos differ from a completed removal",
        )
        expected.forEach { (ref, node) ->
            assertTrue(
                abs(node.credence - actual.getValue(ref).credence) <= 25 * q,
                "node $ref: completed removal ${node.credence} vs recovered ${actual.getValue(ref).credence}",
            )
        }
        val removed = setOf(edge, cascaded)
        assertTrue(recovered.context.live().spawns.keys.none { it in removed }, "torn edge spawns survived recovery")
        removed.forEach { ref ->
            assertEquals(null, recovered.host.lookup(ref, ClaimApi::class.java), "recovered host retained $ref")
        }
    }

    /** The K2 seam: agora deltas cross the codec via the ServiceLoader contribution. */
    @Test
    fun `agora deltas round-trip through the wire codec`() {
        val method = Propagate::class.java.methods.single { it.name == "propagate" }
        listOf(
            StanceDelta("u1", 0.7),
            InfluenceDelta(CellRef(UUID.randomUUID()), Polarity.ATTACK, 0.42, 0.1),
            InfluenceDelta(CellRef(UUID.randomUUID()), Polarity.SUPPORT, null, 0.25),
            CredenceUpdate(CellRef(UUID.randomUUID()), 0.9, 0.4),
        ).forEach { delta ->
            val sent = HostedPortInvocation(
                cellRef = CellRef(UUID.randomUUID()),
                portName = "influenceInlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(method, arrayOf(delta)),
            )
            val received = WireCodec.decode(WireCodec.encode(sent))
            assertEquals(delta, received.invocation.args.single())
        }
    }

    /**
     * kill -9 durability (the demo CrashRestart idiom, in-process): the journaled
     * topology rebuilds the graph under recorded refs before frame replay.
     */
    @Test
    fun `topology journal rebuilds the same graph after a crash`() {
        val q = 1e-3
        // One in-memory journal shared across all three worlds plays "the disk"
        // (the kernel durability idiom: no filesystem in the deterministic sim).
        // The cyclic graph converges by ~5k journaled propagate rounds, and a
        // FileJournal fsync per round put 18s of pure disk sync in this test;
        // the FileJournal-on-real-disk path stays covered by the live-scheduler
        // twin below and by JournalCompatibilityTest (on-disk format).
        val journal = InMemoryJournal()

        data class World(
            val controller: SimulationController,
            val host: ManagedHost,
            val context: ApplyContext,
            val service: AgoraService,
        )
        fun world(): World {
            val controller = SimulationController(11L)
            val registry = LocationRegistry()
            val host = ManagedHost(
                scheduler = controller.scheduler(),
                registry = registry,
                attention = civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
                journal = journal,
            )
            val context = ApplyContext(host, journals = mapOf("host" to journal), topology = journal)
            val service = AgoraService(host, registry, quiescence = q, context = context)
            return World(controller, host, context, service)
        }

        // phase 1: build, churn, quiesce — then vanish without a shutdown
        val (c1, _, _, s1) = world()
        val a = s1.createClaim("A")
        val b = s1.createClaim("B")
        val e1 = s1.createEdge(a, b, Polarity.ATTACK)
        val e2 = s1.createEdge(b, e1, Polarity.ATTACK) // edge-on-edge, closes a cycle
        s1.setStance(a, "u1", 0.9)
        s1.setStance(b, "u2", 0.8)
        s1.setStance(e1, "u1", 0.7) // edges are claims: stance on the relation
        val doomed = s1.createClaim("doomed")
        val doomedEdge = s1.createEdge(doomed, a, Polarity.SUPPORT)
        c1.runToIdle()
        s1.remove(doomed) // retraction must survive the crash too
        c1.runToIdle()
        val before = s1.graph().associate { it.ref to it.credence }
        val beforeInfos = s1.graph().associate { it.ref to it.info }

        // phases 2 and 3: recovery must be stable across REPEATED restarts —
        // a rebuild that appends to (or a checkpoint that races) the journal
        // shows up as second-restart drift
        repeat(2) { phase ->
            val (controller, host, context, service) = world()
            context.recover(journal)
            controller.runToIdle()
            service.rebuildIndex()
            val after = service.graph().associate { it.ref to it.credence }
            val afterInfos = service.graph().associate { it.ref to it.info }
            assertEquals(before.keys, after.keys, "restart ${phase + 2}: recovered topology differs")
            assertEquals(beforeInfos, afterInfos, "restart ${phase + 2}: recovered node infos differ")
            val removed = setOf(doomed, doomedEdge)
            val live = context.live()
            assertTrue(
                live.spawns.keys.none { it in removed },
                "restart ${phase + 2}: removed cells survived in the topology fold",
            )
            assertTrue(
                live.links.values.none { it.from in removed || it.to in removed },
                "restart ${phase + 2}: links touching removed cells survived in the topology fold",
            )
            removed.forEach { ref ->
                assertEquals(null, host.lookup(ref, ClaimApi::class.java), "restart ${phase + 2}: host retained $ref")
            }
            before.forEach { (ref, credence) ->
                assertTrue(
                    abs(credence - after.getValue(ref)) <= 25 * q,
                    "restart ${phase + 2}, node $ref: before-crash $credence vs recovered ${after.getValue(ref)}"
                )
            }
        }
    }

    /**
     * The same crash-recovery on the PRODUCTION scheduler (virtual threads):
     * replay staging races live re-dispatch there, which is exactly what the
     * deterministic twin above cannot see. Regression test for the
     * rebuild-baseline clobber (catch-ups must stay suppressed during
     * structure replay).
     */
    @Test
    fun `crash recovery converges on the live scheduler too`() {
        val q = 1e-3
        val dir = kotlin.io.path.createTempDirectory("agora-live-durability").toFile()
        val journalFile = java.io.File(dir, "host.journal")

        // Held per world: `ManagedHost.checkpoint` keys a cell's state to the
        // exact `Journal` instance passed to the host's constructor
        // (`journalSelector(cellRef) === journal`, HostDurability.checkpoint),
        // so checkpointing this host must reuse this instance rather than a
        // fresh `FileJournal(journalFile)` on the same path.
        data class World(
            val scheduler: HostScheduler,
            val host: ManagedHost,
            val context: ApplyContext,
            val service: AgoraService,
            val journal: FileJournal,
        )
        fun world(name: String): World {
            val registry = LocationRegistry()
            // the production scheduler, held explicitly: `awaitSettled` needs a
            // handle to fence against (ManagedHost mints exactly this otherwise)
            val scheduler = VirtualThreadScheduler("agora-live-durability-$name")
            val journal = FileJournal(journalFile)
            val host = ManagedHost(
                scheduler = scheduler,
                registry = registry,
                attention = civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
                journal = journal,
            )
            val context = ApplyContext(host, journals = mapOf("host" to journal), topology = journal)
            return World(
                scheduler,
                host,
                context,
                AgoraService(host, registry, quiescence = q, context = context),
                journal,
            )
        }

        /**
         * Read the graph once the host is genuinely done — the live twin of the
         * deterministic test's `runToIdle()`.
         *
         * This used to poll `graph()` every 150ms inside a 5s deadline and
         * return the first sample equal to its predecessor. Two equal samples
         * are not convergence: a host starved of CPU also fails to advance for
         * 300ms, so the detector returned mid-convergence snapshots under load
         * and settled ones on an idle machine (computenet-dqy.24). Both arms of
         * the comparison were exposed — a premature `before` baseline, or a
         * premature `after` — which is what produced a 0.1125 gap against a
         * 0.025 tolerance: one arm settled, the other did not.
         *
         * [awaitDrained] replaces the guess with the fact: it blocks until the
         * host's queue actually empties (civictech.cell.host.Quiescence,
         * computenet-q5jzk) — a fence, not a sample, so one drain and one read
         * is the whole proof; there is nothing left to compare against.
         */
        fun awaitSettled(scheduler: HostScheduler, service: AgoraService, what: String): Map<CellRef, Double> {
            scheduler.awaitDrained(what)
            return service.graph().associate { it.ref to it.credence }
        }

        val (s1Scheduler, _, _, s1, _) = world("pre-crash")
        val a = s1.createClaim("A")
        val b = s1.createClaim("B")
        val e1 = s1.createEdge(b, a, Polarity.ATTACK)
        s1.setStance(b, "m", 0.95)
        val c = s1.createClaim("C")
        s1.createEdge(c, e1, Polarity.ATTACK)
        s1.setStance(c, "a", 0.9)
        val before = awaitSettled(s1Scheduler, s1, "pre-crash graph settles")
        val beforeInfos = s1.graph().associate { it.ref to it.info }
        // kill -9: the crashed host stops running. Only legal now that the
        // baseline is a proven-quiescent read — a live predecessor sharing the
        // journal file with the recovering host is exactly the interference the
        // old detector could wave through.
        s1Scheduler.shutdown()

        repeat(2) { phase ->
            val (scheduler, host, context, service, journal) = world("restart-${phase + 2}")
            context.recover(journal).awaitApplied(30_000)
            // Keep link catch-up suppressed through both frame replay and the
            // compacting checkpoint; rebuildIndex flips it only afterwards.
            if (phase == 0) host.checkpoint(journal)
            service.rebuildIndex()
            val after = service.graph().associate { it.ref to it.credence }
            val afterInfos = service.graph().associate { it.ref to it.info }
            assertEquals(before.keys, after.keys, "restart ${phase + 2}: recovered topology differs")
            assertEquals(beforeInfos, afterInfos, "restart ${phase + 2}: recovered node infos differ")
            before.forEach { (ref, credence) ->
                assertTrue(
                    abs(credence - after.getValue(ref)) <= 25 * q,
                    "restart ${phase + 2}, node $ref: before-crash $credence vs recovered ${after.getValue(ref)}"
                )
            }
            // Q4: a checkpoint taken right after the fence must be safe — the
            // next restart still recovers the pre-checkpoint credences, proving
            // the compacted journal did not race the still-staged replay.
            // this restart is done and proven quiescent; the next one replays the
            // same journal file, so leave nothing behind that could still write
            scheduler.shutdown()
        }
    }
}
