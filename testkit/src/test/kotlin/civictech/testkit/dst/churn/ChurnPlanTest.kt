package civictech.testkit.dst.churn

import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.FaultCodecs
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.PeerHandle
import civictech.testkit.dst.PeerHandles
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.RejoinEvent
import civictech.testkit.dst.UnknownFaultTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CHA3-01], [CHA3-02], [CHA3-05], [CHA3-06], [CHA3-46] and [CHA3-47]: the generator is a pure
 * function of `(seed, config)`, every knob it publishes is load-bearing, activation is a step
 * index and nothing else, and a plan naming a peer the graph never declared fails before the
 * run rather than during it.
 *
 * ## What "identical across JVM runs" is actually pinned by
 *
 * Two generations in one JVM prove the generator does not carry hidden per-call state, and
 * that is all they prove — a `hashCode`-ordered collection or an identity hash would give the
 * same answer twice in one process and a different one in the next. So
 * [aFixedSeedAndConfigEncodeToAPinnedPlan] pins the plan's **encoded** form as a literal: it
 * is a value written into this file, compared against what the generator produces, and it
 * would have to be edited by hand for the assertion to move. `java.util.Random`'s sequence is
 * specified rather than implementation-defined, so a golden is a legitimate cross-JVM
 * assertion here in a way it would not be for, say, a `HashMap` iteration order.
 */
class ChurnPlanTest {

    private companion object {

        /** Records what a churn event asked of a peer, so a firing can be observed. */
        class RecordingHandle : PeerHandle {
            val calls = mutableListOf<String>()
            override fun join() { calls += "join" }
            override fun rejoin() { calls += "rejoin" }
            override fun depart(mode: DepartureMode) { calls += "depart:$mode" }
            override fun reassign(interest: String, epoch: Long) { calls += "reassign:$interest@$epoch" }
        }

        /**
         * A graph that keeps the controller busy for [busyUntil] steps and declares a
         * [PeerHandle] per name in [peers].
         *
         * The busywork is not decoration: `DstRun.execute()` breaks its loop the first time
         * the controller has nothing to do, so a graph with no work quiesces at step 0 and no
         * step-indexed event could ever fire. Work is submitted from a step hook because the
         * rig owns the drive loop (`doc/dst-rig.md` §1 seam 4).
         */
        fun busyGraph(
            peers: List<String>,
            handles: MutableMap<String, RecordingHandle>,
            busyUntil: Int,
        ): GraphSpec = GraphSpec("churn-selftest-busy") { world ->
            lateinit var scheduler: HostScheduler
            world.hosts.declare("churn-host") { ctx ->
                scheduler = ctx.scheduler
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
            }
            peers.forEach { peer ->
                val handle = RecordingHandle()
                handles[peer] = handle
                PeerHandles.declare(world, peer, handle)
            }
            world.steps.onStep { w, step ->
                if (step < busyUntil) scheduler.submit(10) { w.trace.emit(host = "churn-host", port = "tick") }
            }
        }

        fun encoded(plan: ChurnPlan): String =
            plan.toFaultPlan().faults.joinToString("\n") { fault ->
                val record = FaultCodecs.encode(fault)
                "${record.id}|${record.kind}|${record.params}"
            }
    }

    // ------------------------------------------------------------- [CHA3-01] / [CHA3-06]

    /** [CHA3-01]: the same `(seed, config)` generates the same plan, value for value. */
    @Test
    fun sameSeedAndConfigGenerateTheSamePlan_CHA3_01() {
        val config = ChurnConfig(peerCount = 3..6, eventCount = 10)
        assertEquals(
            ChurnGenerator.generate(20260824L, config),
            ChurnGenerator.generate(20260824L, config),
            "the generator is not a pure function of (seed, config)",
        )
    }

    /**
     * [CHA3-01] across JVMs: the encoded plan for a pinned `(seed, config)` is a literal in
     * this file. See the class KDoc for why two in-JVM generations are not enough.
     */
    @Test
    fun aFixedSeedAndConfigEncodeToAPinnedPlan_CHA3_01() {
        val plan = ChurnGenerator.generate(
            seed = 7L,
            config = ChurnConfig(
                peerCount = 3..3,
                eventCount = 4,
                writeConcurrency = 0.5,
                partitionOverlap = 0.25,
                opScriptLength = 4,
                stepBudget = 60,
            ),
        )
        assertEquals(GOLDEN_PLAN, encoded(plan), "the pinned plan moved — see this test's KDoc before editing it")
        assertEquals(GOLDEN_WRITES, plan.writeSchedule.joinToString("\n") { "${it.ordinal}@${it.atStep}:${it.peer}" })
    }

    /** [CHA3-06]: a different seed on the same config is a different plan. */
    @Test
    fun aDifferentSeedGeneratesADifferentPlan_CHA3_06() {
        val config = ChurnConfig(eventCount = 12)
        val plans = (1L..12L).map { ChurnGenerator.generate(it, config) }
        assertTrue(
            plans.map { encoded(it) }.toSet().size > 1,
            "twelve seeds produced one plan — the seed is not reaching the generator",
        )
    }

    /** [CHA3-06]: streams are named, so adding peers does not re-roll the write script. */
    @Test
    fun eachRandomnessSourceIsItsOwnNamedStream_CHA3_06() {
        val narrow = ChurnGenerator.generate(99L, ChurnConfig(peerCount = 4..4, eventCount = 0, opScriptLength = 6))
        val wide = ChurnGenerator.generate(99L, ChurnConfig(peerCount = 4..4, eventCount = 9, opScriptLength = 6))
        assertEquals(
            narrow.writeSchedule.map { it.atStep },
            wide.writeSchedule.map { it.atStep },
            "generating membership events shifted the write script's steps: the streams are not independent",
        )
    }

    // ------------------------------------------------------------------------ [CHA3-02]

    /** [CHA3-02]: activation is a controller step index, inside the plan's own horizon. */
    @Test
    fun everyActivationIsAStepIndexWithinTheHorizon_CHA3_02() {
        val config = ChurnConfig(eventCount = 25, stepBudget = 80)
        repeat(40) { i ->
            val plan = ChurnGenerator.generate(i.toLong(), config)
            plan.events.forEach { event ->
                assertTrue(
                    event.atStep in 0 until config.stepBudget,
                    "${event.id} activates at ${event.atStep}, outside 0..<${config.stepBudget}",
                )
            }
            assertEquals(
                plan.events.map { it.atStep }.sorted(),
                plan.events.map { it.atStep },
                "events are not in activation order",
            )
        }
    }

    // ------------------------------------------------------------------------ [CHA3-05]

    /** [CHA3-05]: the peer-count range is honoured, and both endpoints are reachable. */
    @Test
    fun peerCountStaysInTheConfiguredRange_CHA3_05() {
        val config = ChurnConfig(peerCount = 2..5, eventCount = 1)
        val counts = (0L until 200L).map { ChurnGenerator.generate(it, config).peers.size }.toSet()
        assertTrue(counts.all { it in 2..5 }, "peer counts outside 2..5: ${counts.sorted()}")
        assertEquals(setOf(2, 3, 4, 5), counts, "some peer counts in the range are unreachable")
    }

    /** [CHA3-05]: exactly `eventCount` membership decisions, each with its own id prefix. */
    @Test
    fun eventCountDrivesTheNumberOfMembershipDecisions_CHA3_05() {
        val config = ChurnConfig(peerCount = 4..4, eventCount = 9)
        val plan = ChurnGenerator.generate(31L, config)
        val indices = plan.events.map { it.id.removePrefix("churn-").substringBefore('-').toInt() }.toSet()
        assertEquals((0 until 9).toSet(), indices, "the plan does not carry exactly eventCount decisions")
        assertTrue(plan.events.map { it.id }.toSet().size == plan.events.size, "duplicate event ids")
        // FaultPlan's own init rejects duplicate ids; compiling the plan proves it accepts this one.
        assertEquals(plan.events.size, plan.toFaultPlan().faults.size)
    }

    /** [CHA3-05]: a mode weighted 0 never appears; a mode weighted alone always does. */
    @Test
    fun departureWeightsSelectTheMode_CHA3_05() {
        val onlyCrash = ChurnConfig(
            peerCount = 4..4,
            eventCount = 30,
            departureWeights = mapOf(DepartureMode.CRASH_UNCLEAN to 1),
        )
        val departures = (0L until 20L)
            .flatMap { ChurnGenerator.generate(it, onlyCrash).events }
            .filterIsInstance<DepartEvent>()
        assertTrue(departures.isNotEmpty(), "no departure was generated at all — the fixture is vacuous")
        assertTrue(
            departures.all { it.mode == DepartureMode.CRASH_UNCLEAN },
            "a zero-weighted mode was drawn: ${departures.map { it.mode }.toSet()}",
        )

        val noCrash = onlyCrash.copy(
            departureWeights = DepartureMode.entries.associateWith { if (it == DepartureMode.CRASH_UNCLEAN) 0 else 1 },
        )
        val without = (0L until 20L)
            .flatMap { ChurnGenerator.generate(it, noCrash).events }
            .filterIsInstance<DepartEvent>()
        assertTrue(without.isNotEmpty())
        assertTrue(
            without.none { it.mode == DepartureMode.CRASH_UNCLEAN },
            "a mode weighted 0 was still drawn",
        )
    }

    /**
     * [CHA3-05]: the map's own iteration order must not decide anything.
     *
     * Two `Map`s that compare equal may iterate differently, and a `ChurnConfig` is a value —
     * so equal configs must generate equal plans. The generator walks `DepartureMode.entries`
     * for exactly this reason.
     */
    @Test
    fun departureWeightMapOrderDoesNotChangeThePlan_CHA3_01() {
        val forwards = DepartureMode.entries.associateWith { 1 }
        val backwards = linkedMapOf<DepartureMode, Int>().apply {
            DepartureMode.entries.reversed().forEach { put(it, 1) }
        }
        assertEquals(forwards, backwards, "the fixture's two maps are not equal, so it proves nothing")
        val config = ChurnConfig(peerCount = 4..4, eventCount = 20, departureWeights = forwards)
        assertEquals(
            encoded(ChurnGenerator.generate(5L, config)),
            encoded(ChurnGenerator.generate(5L, config.copy(departureWeights = backwards))),
            "map iteration order changed the plan",
        )
    }

    /** [CHA3-05]: op-script length and write-concurrency fraction both reach the schedule. */
    @Test
    fun opScriptLengthAndWriteConcurrencyReachTheSchedule_CHA3_05() {
        val serial = ChurnGenerator.generate(
            8L,
            ChurnConfig(peerCount = 3..3, opScriptLength = 12, writeConcurrency = 0.0, stepBudget = 400),
        )
        assertEquals(12, serial.writeSchedule.size)
        assertEquals(
            12,
            serial.writeSchedule.map { it.atStep }.toSet().size,
            "writeConcurrency=0.0 still placed two writes on one step",
        )

        val concurrent = ChurnGenerator.generate(
            8L,
            ChurnConfig(peerCount = 3..3, opScriptLength = 12, writeConcurrency = 1.0, stepBudget = 400),
        )
        assertEquals(12, concurrent.writeSchedule.size)
        assertEquals(
            1,
            concurrent.writeSchedule.map { it.atStep }.toSet().size,
            "writeConcurrency=1.0 did not place the whole script on one step",
        )
    }

    /**
     * [CHA3-05]: the partition-overlap probability decides whether churn happens *inside* an
     * open [DepartureMode.PARTITION_SUSPEND] window or waits it out.
     *
     * Observed without re-implementing the generator's placement: the gap from a
     * `PARTITION_SUSPEND` departure to the next later event is at least `suspendWindow` when
     * the probability is 0, and is sometimes shorter when it is 1.
     */
    @Test
    fun partitionOverlapDecidesWhetherChurnLandsInsideASuspension_CHA3_05() {
        val base = ChurnConfig(
            peerCount = 5..5,
            eventCount = 24,
            departureWeights = mapOf(DepartureMode.PARTITION_SUSPEND to 1),
            suspendWindow = 10,
            stepBudget = 500,
        )
        assertEquals(0, overlaps(base.copy(partitionOverlap = 0.0)), "an event landed inside a window at p=0.0")
        assertTrue(overlaps(base.copy(partitionOverlap = 1.0)) > 0, "no event landed inside a window at p=1.0")
    }

    /** Gaps shorter than `suspendWindow` after a PARTITION_SUSPEND departure, over 20 seeds. */
    private fun overlaps(config: ChurnConfig): Int = (0L until 20L).sumOf { seed ->
        val events = ChurnGenerator.generate(seed, config).events
        events.withIndex().count { (i, event) ->
            event is DepartEvent && event.mode == DepartureMode.PARTITION_SUSPEND &&
                events.drop(i + 1).firstOrNull { it.atStep > event.atStep }
                    ?.let { it.atStep - event.atStep < config.suspendWindow } == true
        }
    }

    // ------------------------------------------------------------- [CHA3-46] / [CHA3-47]

    /**
     * [CHA3-46], reusing [CHA1-23]: an event naming a peer the graph never declared aborts the
     * run **before the first step**, through the rig's own `FaultTarget` validation. No churn
     * code participates in that check beyond declaring [civictech.testkit.dst.PeerTarget].
     */
    @Test
    fun anEventNamingAnUnknownPeerFailsFast_CHA3_46() {
        val handles = mutableMapOf<String, RecordingHandle>()
        val graph = busyGraph(listOf("peer0", "peer1"), handles, busyUntil = 20)
        val plan = ChurnPlan(
            seed = 3L,
            config = ChurnConfig(peerCount = 1..1, eventCount = 1, stepBudget = 30),
            peers = listOf("ghost"),
            events = listOf(JoinEvent("churn-ghost", "ghost", 3)),
        )
        val failure = assertFailsWith<UnknownFaultTargetException> {
            DstRun(graph, plan.toFaultPlan(), budget = 30).execute()
        }
        assertEquals("peer", failure.target.kind)
        assertEquals("ghost", failure.target.name)
        assertTrue(handles.getValue("peer0").calls.isEmpty(), "the run applied something before validating")
        assertTrue(
            failure.message!!.contains("peer0") && failure.message!!.contains("peer1"),
            "the naming error must list the peers the graph did declare: ${failure.message}",
        )
    }

    /**
     * [CHA3-47], reusing [CHA1-24]: an event whose step the run never reaches is reported
     * `fired == 0`, and one that fires is counted — which is only true because every event
     * calls `world.trace.fault(...)` (`doc/dst-rig.md` §1 seam 5).
     */
    @Test
    fun anUnfiredEventIsMarkedInertAndAFiredOneIsCounted_CHA3_47() {
        val handles = mutableMapOf<String, RecordingHandle>()
        val graph = busyGraph(listOf("peer0"), handles, busyUntil = 10)
        val fires = JoinEvent("churn-fires", "peer0", 4)
        val never = DepartEvent("churn-never", "peer0", 40, DepartureMode.EVICT_CLEAN)
        val plan = ChurnPlan(
            seed = 11L,
            config = ChurnConfig(peerCount = 1..1, eventCount = 2, stepBudget = 60),
            peers = listOf("peer0"),
            events = listOf(fires, never),
        )

        val report = DstRun(graph, plan.toFaultPlan(), budget = 60).execute()

        assertEquals(listOf("churn-never"), report.inertFaults.map { it.id }, report.toString())
        val fired = report.appliedFaults.single { it.id == "churn-fires" }
        assertEquals(1, fired.fired)
        assertEquals(listOf(4), fired.activationSteps)
        assertEquals(listOf("join"), handles.getValue("peer0").calls)
    }

    /** Every event kind reaches its handle operation, and traces exactly one firing. */
    @Test
    fun everyEventKindReachesItsHandleAndTracesOnce_CHA3_47() {
        val handles = mutableMapOf<String, RecordingHandle>()
        val graph = busyGraph(listOf("peer0"), handles, busyUntil = 12)
        val events: List<ChurnEvent> = listOf(
            JoinEvent("e-join", "peer0", 1),
            ReassignEvent("e-reassign", "peer0", 2, "interest-0", 3L),
            DepartEvent("e-depart", "peer0", 3, DepartureMode.EVICT_NO_CLOSE),
            RejoinEvent("e-rejoin", "peer0", 4),
        )
        val plan = ChurnPlan(
            seed = 12L,
            config = ChurnConfig(peerCount = 1..1, eventCount = 4, stepBudget = 60),
            peers = listOf("peer0"),
            events = events,
        )

        val report = DstRun(graph, plan.toFaultPlan(), budget = 60).execute()

        assertTrue(report.inertFaults.isEmpty(), "some event never fired: ${report.inertFaults.map { it.id }}")
        assertEquals(
            listOf("join", "reassign:interest-0@3", "depart:EVICT_NO_CLOSE", "rejoin"),
            handles.getValue("peer0").calls,
        )
        assertTrue(report.appliedFaults.all { it.fired == 1 }, "an event fired more than once: ${report.appliedFaults}")
    }

    /** A plan whose events name a peer off the roster is refused at construction. */
    @Test
    fun aPlanCannotNameAPeerOffItsOwnRoster() {
        assertFailsWith<IllegalArgumentException> {
            ChurnPlan(
                seed = 1L,
                config = ChurnConfig(peerCount = 1..1, eventCount = 1, stepBudget = 10),
                peers = listOf("peer0"),
                events = listOf(JoinEvent("x", "peer9", 1)),
            )
        }
    }

    /** [CHA3-02]: a plan cannot schedule an event beyond its own activation horizon. */
    @Test
    fun aPlanCannotScheduleBeyondItsHorizon_CHA3_02() {
        assertFailsWith<IllegalArgumentException> {
            ChurnPlan(
                seed = 1L,
                config = ChurnConfig(peerCount = 1..1, eventCount = 1, stepBudget = 10),
                peers = listOf("peer0"),
                events = listOf(JoinEvent("x", "peer0", 10)),
            )
        }
    }
}

/**
 * The pinned encoding of `ChurnGenerator.generate(7L, ...)` — see
 * [ChurnPlanTest.aFixedSeedAndConfigEncodeToAPinnedPlan].
 *
 * **Regenerate this only when the generation algorithm is deliberately changed**, and say so
 * in the change: a moved golden with no algorithm change means the generator picked up a
 * source of randomness that is not the seed, which is the exact defect [CHA3-01] exists to
 * exclude.
 */
private val GOLDEN_PLAN: String = """
churn-0-join-peer2|churn-join|{"peer":"peer2","atStep":3}
churn-0-reassign-peer2|churn-reassign|{"peer":"peer2","atStep":3,"interest":"interest-2","epoch":1}
churn-1-join-peer1|churn-join|{"peer":"peer1","atStep":12}
churn-1-reassign-peer1|churn-reassign|{"peer":"peer1","atStep":12,"interest":"interest-1","epoch":2}
churn-2-join-peer0|churn-join|{"peer":"peer0","atStep":24}
churn-2-reassign-peer0|churn-reassign|{"peer":"peer0","atStep":24,"interest":"interest-0","epoch":3}
churn-3-depart-peer0|churn-depart|{"peer":"peer0","atStep":33,"mode":"PARTITION_SUSPEND"}
churn-3-reassign-peer1|churn-reassign|{"peer":"peer1","atStep":33,"interest":"interest-0","epoch":4}
""".trimIndent()

/** The pinned write schedule for the same `(seed, config)`. See [GOLDEN_PLAN]. */
private val GOLDEN_WRITES: String = """
0@1:peer0
1@2:peer1
2@2:peer1
3@2:peer0
""".trimIndent()
