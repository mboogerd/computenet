package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.mesh.TopologyRefusal
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TypedCellFactory
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkRole
import civictech.cell.proxy.Invocation
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.CountDownLatch

class PointAccountingTest {

    @Test
    fun singleHostChainCountsOneExactLocalDeliveryPerInput() {
        // A one-host generated CHAIN has one externally scheduled source delivery per
        // input. Every cell-to-cell hop stays synchronous inside that task (the same
        // unit MacroCalibrationBenchmark.realLocalDispatch measures), so three inputs
        // are exactly three local deliveries; setup has already left the count window.
        val at = coordinate(hostCount = 1).copy(cardinality = 3, batchSize = 3)

        val outcome = SweepRunner().run(at).shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.counts.messages shouldBe 3L
        outcome.counts.messagesNotMeasured shouldBe null
    }

    @Test
    fun okPointEchoesItsCoordinateAndRecordsCounts() {
        // Seed 7 places the eight chain nodes on both peers (1,1,1,0,1,0,0,1).
        val at = coordinate()

        val outcome = SweepRunner().run(at).shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        outcome.subject shouldBe CostSubject.PROPAGATION
        outcome.wall shouldBe null
        checkNotNull(outcome.counts.messages) shouldBeGreaterThan 0L
        outcome.counts.messagesNotMeasured shouldBe null
        (outcome.counts.simSteps >= 4) shouldBe true
        // The placement crosses peers, but seed 7's second filter is `value >= 250`.
        // Required inputs 0..19 therefore stop before the first cross-host edge.
        outcome.counts.crossHostFrames shouldBe 0L
        outcome.counts.journalRecords shouldBe 0L
    }

    @Test
    fun nonReplicatedCrossHostSimPointCountsExactDeliveriesFramesAndBytes() {
        // Seed 0 places the CHAIN across both peers and routes its inputs over
        // the first cross-host edge (observed: 26 frames for this coordinate).
        // Pinned exactly, not `> 0`: a routed frame dropped on the edge stops the
        // downstream hops, which lowers the count (observed 20) but not to zero.
        val at = coordinate(shapeSeed = 0)

        val outcome = SweepRunner().run(at).shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        // Twenty source-host deliveries plus the 26 routed target-host deliveries.
        // The controller executes 72 total tasks here, so this exact assertion rejects
        // the old `simSteps` upper bound as a substitute for a message count.
        outcome.counts.messages shouldBe 46L
        outcome.counts.crossHostFrames shouldBe 26L
        outcome.counts.wireBytes shouldBeGreaterThan 0L
    }

    @Test
    fun setupActionThrowIsFailedWithItsCauseInsteadOfEscaping() {
        val at = coordinate()
        val outcome = SweepRunner(
            setupHook = SetupHook { mesh ->
                mesh.world.controller.scheduler().submit(0) {
                    throw IllegalStateException("bench: setup action throw")
                }
            },
        ).run(at).shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.at shouldBe at
        outcome.subject shouldBe CostSubject.PROPAGATION
        outcome.cause shouldContain IllegalStateException::class.java.name
        outcome.cause shouldContain "bench: setup action throw"
        outcome.deadLetters shouldBe emptyList()
    }

    @Test
    fun setupActionThrowOfAnotherTypeIsFailedInsteadOfEscaping() {
        val at = coordinate()
        val outcome = SweepRunner(
            setupHook = SetupHook { mesh ->
                mesh.world.controller.scheduler().submit(0) {
                    throw UnsupportedOperationException("bench: setup action throw")
                }
            },
        ).run(at).shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.cause shouldContain UnsupportedOperationException::class.java.name
        outcome.cause shouldContain "bench: setup action throw"
    }

    @Test
    fun setupBudgetExhaustionRemainsNonConvergent() {
        val at = coordinate()
        val outcome = SweepRunner(
            setupHook = SetupHook { mesh ->
                val scheduler = mesh.world.controller.scheduler()
                lateinit var action: suspend () -> Unit
                action = { scheduler.submit(0, action) }
                scheduler.submit(0, action)
            },
        ).run(at)

        outcome shouldBe PointOutcome.NonConvergent(
            at,
            CostSubject.PROPAGATION,
            "setup:simSteps=${civictech.cell.host.SimulationController.DEFAULT_BUDGET}",
        )
    }

    @Test
    fun budgetExhaustionIsNonConvergentAndDoesNotThrow() {
        val at = coordinate()

        SweepRunner(budget = PointBudget(simSteps = 1)).run(at) shouldBe
            PointOutcome.NonConvergent(at, CostSubject.PROPAGATION, "simSteps=1")
    }

    @Test
    fun outOfMemoryDuringTheDriveLoopIsUnschedulable() {
        val at = coordinate()

        val outcome = SweepRunner(
            hook = PointHook { _, _ -> throw OutOfMemoryError("simulated") },
        ).run(at).shouldBeInstanceOf<PointOutcome.Unschedulable>()

        outcome.reason shouldBe "java.lang.OutOfMemoryError: simulated"
        outcome.observedLimit.startsWith("maxHeap=") shouldBe true
    }

    @Test
    fun outOfMemoryDuringApplyIsUnschedulable() {
        val at = coordinate()

        val outcome = SweepRunner(
            setupHook = SetupHook { throw OutOfMemoryError("simulated") },
        ).run(at).shouldBeInstanceOf<PointOutcome.Unschedulable>()

        outcome.reason shouldBe "java.lang.OutOfMemoryError: simulated"
        outcome.observedLimit.startsWith("maxHeap=") shouldBe true
    }

    @Test
    fun deadLetterFailsThePoint() {
        val at = coordinate()
        val runner = SweepRunner(
            hook = PointHook { context, _ ->
                val mesh = context.shouldBeInstanceOf<PointContext.Sim>().mesh
                val method = Runnable::class.java.methods.single { it.name == "run" }
                mesh.peers[0].host.routerInlet.call.route(
                    CellRef(UUID.randomUUID()),
                    "inlet",
                    Invocation.of(method, emptyArray()),
                )
            },
        )

        val outcome = runner.run(at).shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.at shouldBe at
        outcome.subject shouldBe CostSubject.PROPAGATION
        outcome.cause shouldBe "dead letter"
        outcome.deadLetters.size shouldBe 1
    }

    @Test
    fun unschedulableMeshIsReportedNotRetried() {
        val at = coordinate(hostCount = 5)
        val build = MeshBuild.Unschedulable(
            hostCount = 5,
            reason = "java.lang.OutOfMemoryError: simulated",
            observedLimit = 3,
        )

        unschedulableOutcome(at, build) shouldBe PointOutcome.Unschedulable(
            at = at,
            subject = CostSubject.PROPAGATION,
            reason = "java.lang.OutOfMemoryError: simulated",
            observedLimit = "peers=3",
        )
    }

    @Test
    fun starSpokeToSpokeCoordinateRelaysThroughHub() {
        // Seed 0 puts the final CHAIN edge between spokes 1 and 2. STAR now
        // relays that edge through peer 0, so this coordinate is schedulable.
        // FULL_MESH has a direct loopback for every pair and STAR has a hub
        // relay for every spoke pair; therefore no generated coordinate in
        // the current topology set reaches the mesh-topology refusal branch.
        val at = coordinate(shapeSeed = 0, hostCount = 3)

        val outcome = SweepRunner(topology = MeshTopology.STAR).run(at)
            .shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        outcome.counts.crossHostFrames shouldBeGreaterThan 0L
    }

    @Test
    fun benchMeshTopologyRefusalMapsToUnschedulable() {
        val at = coordinate(shapeSeed = 0, hostCount = 3)
        val outcome = SweepRunner(
            topology = MeshTopology.STAR,
            meshBuilder = { hostCount, seed, topology, journalFor, replication ->
                BenchMesh.build(
                    hostCount = hostCount,
                    seed = seed,
                    topology = topology,
                    journalFor = journalFor,
                    replication = replication,
                    includeEdge = { first, second -> first != 0 || second != 2 },
                )
            },
        ).run(at).shouldBeInstanceOf<PointOutcome.Unschedulable>()

        outcome.reason shouldContain TopologyRefusal::class.java.name
        outcome.observedLimit shouldBe "topology=STAR"
    }

    @Test
    fun unrelatedCrossHostRefusalRemainsFailed() {
        val at = coordinate()
        val outcome = SweepRunner(
            setupHook = SetupHook { mesh ->
                mesh.apply(
                    GraphSpec(
                        listOf(
                            SpawnStep(
                                "source",
                                TypedCellFactory<SetCell<Long>> { ref -> SetCell(ref) },
                                placement = "host0",
                            ),
                            SpawnStep(
                                "sink",
                                TypedCellFactory<FilterCell<Long>> { ref -> FilterCell(ref) { true } },
                                placement = "host1",
                            ),
                            ConnectStep(
                                "source",
                                "outlet",
                                "sink",
                                "inlet",
                                LinkOptions(role = LinkRole.Observe),
                            ),
                        ),
                    ),
                )
            },
        ).run(at).shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.cause shouldContain IllegalArgumentException::class.java.name
        outcome.cause shouldContain LinkOptions::class.java.simpleName
    }

    @Test
    fun replicatedSourcesAreWrittenAtPeerZero() {
        val at = coordinate(
            shape = "FAN_IN_LATTICE",
            hostCount = 3,
            fanOut = 2,
            replication = true,
        )

        val outcome = SweepRunner().run(at).shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        outcome.counts.crossHostFrames shouldBeGreaterThan 0L
    }

    @Test
    fun realPointRecordsWallClockWithADispersionFigure() {
        val at = coordinate(hostCount = 1, drive = Drive.REAL)

        val outcome = SweepRunner(real = RealOptions(trials = 2, env = realEnvironment))
            .run(at)
            .shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        outcome.subject shouldBe CostSubject.PROPAGATION
        outcome.counts.simSteps shouldBe 0L
        outcome.counts.crossHostFrames shouldBe 0L
        outcome.counts.wireBytes shouldBe 0L
        val wall = requireNotNull(outcome.wall)
        wall.drive shouldBe Drive.REAL
        wall.unit shouldBe "ms/point"
        wall.env shouldBe realEnvironment
        (wall.dispersion >= 0.0) shouldBe true
        (wall.value >= 0.0) shouldBe true
    }

    @Test
    fun schedulerLoopThrowFailsTheRealPoint() {
        val at = coordinate(hostCount = 1, drive = Drive.REAL)
        val capturedErr = ByteArrayOutputStream()
        val originalErr = System.err
        try {
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
            val outcome = SweepRunner(
                real = RealOptions(trials = 1, env = realEnvironment),
                hook = PointHook { context, _ ->
                    val world = context.shouldBeInstanceOf<PointContext.Real>().world
                    world.scheduler.submit(0) {
                        throw IllegalStateException("bench: injected scheduler-loop throw")
                    }
                },
            ).run(at).shouldBeInstanceOf<PointOutcome.Failed>()

            outcome.cause shouldContain IllegalStateException::class.java.name
            outcome.cause shouldContain "injected scheduler-loop throw"
            outcome.deadLetters shouldBe emptyList()
            awaitUntil("the scheduler backstop prints the injected failure", timeoutMs = 5_000) {
                capturedErr.toString(Charsets.UTF_8).contains("injected scheduler-loop throw")
            }
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun deadLetterFailsTheRealPoint() {
        val at = coordinate(hostCount = 1, drive = Drive.REAL)
        val runner = SweepRunner(
            real = RealOptions(trials = 1, env = realEnvironment),
            hook = PointHook { context, _ ->
                val world = context.shouldBeInstanceOf<PointContext.Real>().world
                val method = Runnable::class.java.methods.single { it.name == "run" }
                world.host.routerInlet.call.route(
                    CellRef(UUID.randomUUID()),
                    "inlet",
                    Invocation.of(method, emptyArray()),
                )
            },
        )

        val outcome = runner.run(at).shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.cause shouldBe "dead letter"
        outcome.deadLetters.size shouldBe 1
    }

    @Test
    fun realBudgetExhaustionIsNonConvergent() {
        val at = coordinate(hostCount = 1, drive = Drive.REAL)
        val release = CountDownLatch(1)
        try {
            val outcome = SweepRunner(
                budget = PointBudget(realMillis = 200),
                real = RealOptions(trials = 1, env = realEnvironment),
                hook = PointHook { context, _ ->
                    context.shouldBeInstanceOf<PointContext.Real>().world.scheduler.submit(0) {
                        release.await()
                    }
                },
            ).run(at)

            outcome shouldBe PointOutcome.NonConvergent(
                at,
                CostSubject.PROPAGATION,
                "realMillis=200",
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun multiHostOrReplicatedRealIsUnschedulable() {
        val coordinates = listOf(
            coordinate(hostCount = 3, drive = Drive.REAL),
            coordinate(hostCount = 1, replication = true, drive = Drive.REAL),
        )

        coordinates.forEach { at ->
            val outcome = SweepRunner(real = RealOptions(trials = 1, env = realEnvironment))
                .run(at)
                .shouldBeInstanceOf<PointOutcome.Unschedulable>()

            outcome.at shouldBe at
            outcome.reason shouldContain "[BEN2-13]"
            outcome.observedLimit shouldBe "hostCount=1, replication=false"
        }
    }

    @Test
    fun realDriveRequiresRealOptions() {
        val failure = shouldThrow<IllegalArgumentException> {
            SweepRunner().run(coordinate(hostCount = 1, drive = Drive.REAL))
        }

        failure.message shouldContain "REAL drive needs RealOptions"
    }

    private fun coordinate(
        shape: String = "CHAIN",
        shapeSeed: Long = 7,
        hostCount: Int = 2,
        fanOut: Int = 1,
        replication: Boolean = false,
        durability: Boolean = false,
        drive: Drive = Drive.SIM,
    ): Coordinate = Coordinate(
        shape = shape,
        shapeSeed = shapeSeed,
        cardinality = 20,
        batchSize = 5,
        hostCount = hostCount,
        fanOut = fanOut,
        replication = replication,
        durability = durability,
        drive = drive,
    )

    private val realEnvironment = RunEnvironment(
        jvmVendor = "test-vendor",
        jvmVersion = "test-version",
        heapSettings = "test-heap",
        cpuModel = "test-cpu",
        coreCount = 1,
        os = "test-os",
        jmhMode = "PointAccountingTest fixture; not JMH",
        forkCount = 1,
        warmupIterations = 1,
        measurementIterations = 1,
        harnessCommitSha = "test",
    )
}
