package civictech.bench.macro.rebaseline

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
import civictech.bench.macro.Counters
import civictech.bench.macro.PointBudget
import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.mesh.SetDeltaInletProxy
import civictech.bench.macro.unschedulableOutcome
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.ReBaselineEmitting
import civictech.cell.ReBaselineNotice
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID

/** Commands exposed by the pre-loaded probe source ([BEN2-36], BS-12/BS-13, j27rc-D7). */
interface ReBaselineSourceOps {
    fun emitAll()
    fun poison()
}

private interface ReBaselineSourceProxy {
    val inlet: Use<ReBaselineSourceOps>
}

/**
 * A pre-loaded authoritative source that can be RESTARTed and re-assert its checkpoint
 * ([BEN2-36], BS-12/BS-13, j27rc-D7).
 *
 * The initial tags are minted before hosting so the host's spawn-time checkpoint contains the
 * complete cardinality and the fast probe can pin construction-time epoch continuity.
 */
class ReBaselineSourceCell(
    override val ref: CellRef,
    preload: Long,
) : Cell, Stateful, ReBaselineEmitting {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort(
        "inlet",
        FanInlet(ReBaselineSourceOps::class.java),
    )

    private val adds = mutableMapOf<Long, MutableSet<Timestamp>>()
    private var counter = 0L

    init {
        require(preload >= 0) { "preload must be non-negative" }
        for (element in 0L until preload) {
            adds[element] = mutableSetOf(Timestamp(outlet.waveState().sourceId, ++counter))
        }
        inlet.serve(object : ReBaselineSourceOps {
            override fun emitAll() {
                outlet.originate { propagate(SetDelta(adds = wholeState())) }
            }

            override fun poison() {
                throw IllegalStateException("poison")
            }
        })
    }

    /** The source epoch used by the outlet's next spontaneous emission. */
    val sourceId: UUID get() = outlet.waveState().sourceId

    override fun snapshot(): Serializable = HashMap(
        adds.mapValues { (_, tags) -> HashSet(tags) },
    )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        adds.clear()
        (state as Map<Long, Set<Timestamp>>).forEach { (element, tags) ->
            adds[element] = tags.toMutableSet()
        }
    }

    override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
        outlet.reBaseline(supersedes, supersede) {
            propagate(SetDelta(adds = wholeState()))
        }
    }

    private fun wholeState(): Map<Long, Set<Timestamp>> =
        adds.mapValues { (_, tags) -> tags.toSet() }
}

/**
 * One delivery observed at a harness tap ([BEN2-36], BS-12/BS-13, j27rc-D8/D9).
 * [tagSourceIds] exists so the fast test can prove the pre-restart state retained the source's
 * construction-time epoch rather than merely counting an opaque payload.
 */
data class TapDelivery(
    val notice: ReBaselineNotice?,
    val elements: Int,
    val tags: Int,
    val tagSourceIds: Set<UUID>,
)

/**
 * Harness shim that records re-baseline deliveries and folds them through a real
 * [UnionSetCell] ([BEN2-36], BS-12/BS-13, j27rc-D8/D9).
 *
 * The forward is synchronous so [CurrentContext]'s [ReBaselineNotice] reaches the kernel fold.
 * A shim is necessary because `TagState` is internal and a pre-loaded union emits nothing when
 * a re-baseline only re-asserts tags it already holds.
 */
class ReBaselineTapCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val mirrorMembership: Boolean = true,
) : Cell {
    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort(
        "inlet",
        FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Long>>>),
    )

    private val union = UnionSetCell<Long>()
    private val recorded = mutableListOf<TapDelivery>()
    private val live = mutableMapOf<Long, MutableSet<Timestamp>>()
    private var netElements = 0L

    init {
        union.outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<Long>> {
                    override fun propagate(value: SetDelta<Long>) {
                        netElements += value.adds.size - value.dels.size
                        if (!mirrorMembership) return
                        value.adds.forEach { (element, tags) ->
                            live.getOrPut(element) { mutableSetOf() } += tags
                        }
                        value.dels.forEach { (element, tags) ->
                            live[element]?.let { current ->
                                current -= tags
                                if (current.isEmpty()) live.remove(element)
                            }
                        }
                    }
                },
                PortRef.generate(),
            ),
        )
        inlet.serve(object : Propagate<SetDelta<Long>> {
            override fun propagate(value: SetDelta<Long>) {
                recorded += TapDelivery(
                    notice = CurrentContext.get()?.reBaseline,
                    elements = value.adds.size,
                    tags = value.adds.values.sumOf { it.size },
                    tagSourceIds = value.adds.values.flatten().mapTo(linkedSetOf()) { it.sourceId },
                )
                union.inlet.call.propagate(value)
            }
        })
    }

    /** All deliveries, including the unmarked setup delivery. */
    val deliveries: List<TapDelivery> get() = recorded.toList()

    /** Deliveries carrying the RESTART supersession notice. */
    val noticeDeliveries: List<TapDelivery> get() = recorded.filter { it.notice != null }

    /** Reconstructs the live membership forwarded by the wrapped kernel union. */
    fun membership(): Set<Long> {
        check(mirrorMembership) { "membership() needs mirrorMembership = true; use netElements()" }
        return live.keys.toSet()
    }

    /**
     * Elements the kernel union has emitted, net of removals: adds minus dels, counted per
     * delta entry, with no per-element state kept. Equals `membership().size` for an add-only
     * stream such as the pre-load (one delta, distinct elements), which is all the REAL rig
     * uses it for. It exists so the 1e5 x 256 REAL corner does not hold a SECOND C-sized
     * element-to-tag map per tap beside the union's own (computenet-p2h90: that mirror was
     * about half the retained heap and sits outside the timed window).
     */
    fun netElements(): Long = netElements
}

/**
 * Live objects exposed only while one probe attempt is running, for fast semantic assertions
 * ([BEN2-36], BS-12/BS-13, j27rc-D11).
 */
data class ReBaselineRig(
    val mesh: BenchMesh,
    val sourceRef: CellRef,
    val source: ReBaselineSourceCell,
    val taps: List<ReBaselineTapCell>,
)

/**
 * A classified re-baseline point plus harness-side work counts ([BEN2-36], [BEN2-41],
 * BS-12/BS-13, j27rc-D9/D11). The three extra counts are non-null only for [PointOutcome.Ok].
 */
data class ReBaselineOutcome(
    val outcome: PointOutcome,
    val reassertedElements: Long?,
    val reassertedTags: Long?,
    val notices: Int?,
) {
    init {
        val extras = listOf(reassertedElements, reassertedTags, notices)
        require(
            if (outcome is PointOutcome.Ok) extras.all { it != null } else extras.all { it == null },
        ) { "re-baseline work counts must be present exactly for Ok outcomes" }
    }
}

/**
 * Drives the supervision-RESTART re-baseline path over a deterministic peer mesh
 * ([BEN2-36], [BEN2-41], BS-12/BS-13, j27rc-D7..D11).
 *
 * `StructuralCounts.messages` counts re-baseline deliveries observed at the harness taps, not
 * bridge-internal dispatches. [ReBaselineTapCell] is the explicit observation shim because the
 * kernel tag fold is internal and an idempotent re-assertion has no downstream delta to count.
 */
object ReBaselineProbe {
    const val LOCAL_TAPS = "REBASELINE_LOCAL_TAPS"
    const val REMOTE_TAPS = "REBASELINE_REMOTE_TAPS"

    /** Builds the stable coordinate used by the SIM study (j27rc-D10). */
    fun coordinate(
        variant: String,
        cardinality: Long,
        fanOut: Int,
        hostCount: Int,
    ): Coordinate {
        require(variant == LOCAL_TAPS || variant == REMOTE_TAPS) {
            "unknown re-baseline variant '$variant'"
        }
        require(variant != REMOTE_TAPS || hostCount >= 2) {
            "remote taps require at least two hosts"
        }
        return Coordinate(
            shape = variant,
            shapeSeed = 0,
            cardinality = cardinality,
            batchSize = 1,
            hostCount = hostCount,
            fanOut = fanOut,
            replication = false,
            durability = false,
            drive = Drive.SIM,
        )
    }

    /**
     * Runs one point. Successful attempts remain private until the optional determinism pair
     * agrees on structural counts and all harness-side counts (j27rc-D11).
     */
    fun run(
        at: Coordinate,
        budget: PointBudget = PointBudget(),
        determinismCheck: Boolean = true,
        beforePoison: (ReBaselineRig) -> Unit = {},
        inspect: (ReBaselineRig) -> Unit = {},
    ): ReBaselineOutcome {
        validate(at)
        val first = attempt(at, budget, beforePoison, inspect)
        val firstOk = first.outcome as? PointOutcome.Ok ?: return first
        if (!determinismCheck) return first

        val second = attempt(at, budget, beforePoison, inspect)
        val secondOk = second.outcome as? PointOutcome.Ok ?: return second
        return if (
            firstOk.counts == secondOk.counts &&
            first.reassertedElements == second.reassertedElements &&
            first.reassertedTags == second.reassertedTags &&
            first.notices == second.notices
        ) {
            first
        } else {
            excluded(
                PointOutcome.NonDeterministic(
                    at = at,
                    subject = CostSubject.REBASELINE,
                    first = firstOk.counts,
                    second = secondOk.counts,
                ),
            )
        }
    }

    private fun attempt(
        at: Coordinate,
        budget: PointBudget,
        beforePoison: (ReBaselineRig) -> Unit,
        inspect: (ReBaselineRig) -> Unit,
    ): ReBaselineOutcome {
        var mesh: BenchMesh? = null
        return try {
            val build = BenchMesh.build(
                hostCount = at.hostCount,
                seed = 0L,
                topology = MeshTopology.FULL_MESH,
                journalFor = { null },
                replication = false,
            )
            if (build is MeshBuild.Unschedulable) {
                return excluded(
                    unschedulableOutcome(at, build).copy(subject = CostSubject.REBASELINE),
                )
            }
            mesh = (build as MeshBuild.Built).mesh
            val prepared = prepare(at, mesh)
            if (mesh.deadLetters.isNotEmpty()) {
                return excluded(failed(at, "dead letter", mesh))
            }
            measure(at, budget, prepared, beforePoison, inspect)
        } catch (error: OutOfMemoryError) {
            excluded(outOfMemory(at, error))
        } catch (error: Throwable) {
            if (error is IllegalStateException && error.message == setupBudgetExhaustionMessage()) {
                excluded(
                    PointOutcome.NonConvergent(
                        at = at,
                        subject = CostSubject.REBASELINE,
                        budget = "setup:simSteps=${SimulationController.DEFAULT_BUDGET}",
                    ),
                )
            } else {
                excluded(failed(at, describe(error), mesh))
            }
        }
    }

    private fun prepare(at: Coordinate, mesh: BenchMesh): Prepared {
        val sourceRef = MeshRefs.ref("rebaseline-source", 0, at.hostCount)
        val source = ReBaselineSourceCell(sourceRef, at.cardinality)
        mesh.peers[0].host.managementInlet.call.spawn(source)

        val taps = (0 until at.fanOut).map { index ->
            val peer = if (at.shape == LOCAL_TAPS) 0 else 1 + (index % (at.hostCount - 1))
            ReBaselineTapCell(MeshRefs.ref("rebaseline-tap-$index", peer, at.hostCount)).also {
                mesh.peers[peer].host.managementInlet.call.spawn(it)
            }
        }
        mesh.runToIdle()

        taps.forEachIndexed { index, tap ->
            val peer = if (at.shape == LOCAL_TAPS) 0 else 1 + (index % (at.hostCount - 1))
            if (peer == 0) {
                val result = mesh.peers[0].host.managementInlet.call.connect(
                    sourceRef,
                    "outlet",
                    tap.ref,
                    "inlet",
                    LinkOptions.DEFAULT,
                )
                if (result is LinkResult.Rejected) {
                    error(
                        "link rebaseline-source.outlet -> ${tap.ref}.inlet rejected: ${result.reason}",
                    )
                }
            } else {
                val routed = (
                    HostedCellProxy.create(
                        tap.ref,
                        mesh.peers[0].registry,
                        SetDeltaInletProxy::class.java,
                    ) as SetDeltaInletProxy
                ).inlet.call
                mesh.peers[0].host.managementInlet.call.connect(
                    sourceRef,
                    "outlet",
                    Use.fixed(routed, PortRef.generate()),
                )
            }
        }

        mesh.peers[0].host.managementInlet.call.supervise(sourceRef, SupervisionPolicy.RESTART)
        val sourceOps = (
            HostedCellProxy.create(
                sourceRef,
                mesh.peers[0].host,
                ReBaselineSourceProxy::class.java,
            ) as ReBaselineSourceProxy
        ).inlet.call
        sourceOps.emitAll()
        mesh.runToIdle()
        return Prepared(ReBaselineRig(mesh, sourceRef, source, taps), sourceOps)
    }

    private fun measure(
        at: Coordinate,
        budget: PointBudget,
        prepared: Prepared,
        beforePoison: (ReBaselineRig) -> Unit,
        inspect: (ReBaselineRig) -> Unit,
    ): ReBaselineOutcome {
        val rig = prepared.rig
        val (counters, registration) = Counters.installOn(rig.mesh.world, emptyList())
        val start = counters.snapshot()
        val deadLettersBefore = rig.mesh.deadLetters.size
        var simSteps = 0L
        var exhausted = false
        var outOfMemory: OutOfMemoryError? = null
        var failure: Throwable? = null
        try {
            beforePoison(rig)
            prepared.sourceOps.poison()
            while (simSteps < budget.simSteps.toLong()) {
                if (!rig.mesh.world.controller.step()) break
                simSteps++
            }
            if (simSteps == budget.simSteps.toLong() && rig.mesh.world.controller.step()) {
                exhausted = true
            }
            if (!exhausted && rig.taps.sumOf { it.noticeDeliveries.size } == at.fanOut) {
                inspect(rig)
            }
        } catch (error: OutOfMemoryError) {
            outOfMemory = error
        } catch (error: Throwable) {
            failure = error
        } finally {
            registration.close()
        }

        outOfMemory?.let { return excluded(outOfMemory(at, it)) }
        val deadLetters = rig.mesh.deadLetters
        val newDeadLetters = deadLetters.drop(deadLettersBefore)
        val unexpectedDeadLetter = newDeadLetters.size > 1 ||
            newDeadLetters.any { it.cause?.message?.contains("poison") != true }
        val notices = rig.taps.sumOf { it.noticeDeliveries.size }
        return when {
            failure != null -> excluded(failed(at, describe(failure), rig.mesh))
            unexpectedDeadLetter -> excluded(failed(at, "dead letter", rig.mesh))
            exhausted -> excluded(
                PointOutcome.NonConvergent(
                    at = at,
                    subject = CostSubject.REBASELINE,
                    budget = "simSteps=${budget.simSteps}",
                ),
            )
            newDeadLetters.size != 1 -> excluded(
                failed(at, "expected one poison dead letter, observed ${newDeadLetters.size}", rig.mesh),
            )
            notices != at.fanOut -> excluded(
                failed(at, "re-baseline reached $notices of ${at.fanOut} taps", rig.mesh),
            )
            else -> {
                val end = counters.snapshot()
                ReBaselineOutcome(
                    outcome = PointOutcome.Ok(
                        at = at,
                        subject = CostSubject.REBASELINE,
                        counts = StructuralCounts(
                            messages = notices.toLong(),
                            messagesNotMeasured = null,
                            crossHostFrames = end.frames - start.frames,
                            wireBytes = end.bytes - start.bytes,
                            journalRecords = 0,
                            simSteps = simSteps,
                        ),
                        wall = null,
                    ),
                    reassertedElements = rig.taps.sumOf { tap ->
                        tap.noticeDeliveries.sumOf { it.elements.toLong() }
                    },
                    reassertedTags = rig.taps.sumOf { tap ->
                        tap.noticeDeliveries.sumOf { it.tags.toLong() }
                    },
                    notices = notices,
                )
            }
        }
    }

    private fun validate(at: Coordinate) {
        require(at.shape == LOCAL_TAPS || at.shape == REMOTE_TAPS) {
            "unsupported re-baseline shape '${at.shape}'"
        }
        require(at.shape != REMOTE_TAPS || at.hostCount >= 2) {
            "remote taps require at least two hosts"
        }
        require(at.drive == Drive.SIM) { "re-baseline mesh probe requires SIM drive" }
        require(at.shapeSeed == 0L) { "re-baseline mesh probe requires shapeSeed=0" }
        require(at.batchSize == 1) { "re-baseline mesh probe requires batchSize=1" }
        require(!at.replication) { "re-baseline mesh probe does not replicate the source" }
        require(!at.durability) { "re-baseline mesh probe does not install journals" }
        require(at.cardinality >= 0) { "cardinality must be non-negative" }
        require(at.fanOut >= 0) { "fanOut must be non-negative" }
    }

    private fun failed(at: Coordinate, cause: String, mesh: BenchMesh?): PointOutcome.Failed =
        PointOutcome.Failed(
            at = at,
            subject = CostSubject.REBASELINE,
            cause = cause,
            deadLetters = mesh?.deadLetters?.map { it.toString() }.orEmpty(),
        )

    private fun outOfMemory(at: Coordinate, error: OutOfMemoryError): PointOutcome.Unschedulable =
        PointOutcome.Unschedulable(
            at = at,
            subject = CostSubject.REBASELINE,
            reason = describe(error),
            observedLimit = "maxHeap=${Runtime.getRuntime().maxMemory()}",
        )

    private fun excluded(outcome: PointOutcome): ReBaselineOutcome =
        ReBaselineOutcome(outcome, null, null, null)

    private fun describe(error: Throwable): String =
        "${error.javaClass.name}: ${error.message}"

    private fun setupBudgetExhaustionMessage(): String =
        "simulation did not quiesce within ${SimulationController.DEFAULT_BUDGET} steps — likely livelock"

    private data class Prepared(
        val rig: ReBaselineRig,
        val sourceOps: ReBaselineSourceOps,
    )
}
