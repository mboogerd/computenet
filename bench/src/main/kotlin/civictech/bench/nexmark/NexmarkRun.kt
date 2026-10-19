package civictech.bench.nexmark

import civictech.bench.RunEnvironment
import civictech.bench.nexmark.queries.MaintainedQuery
import civictech.bench.nexmark.queries.Queries
import civictech.cell.durability.Journal
import java.util.Collections
import java.util.Random
import kotlin.math.floor

/** Seeded fraction of the driving relation removed after the insert stream. */
data class RetractionPlan(
    val fraction: Double,
    val seed: Long? = null,
) {
    init {
        require(fraction.isFinite() && fraction in 0.0..1.0) {
            "retraction fraction must be in 0.0..1.0"
        }
    }
}

/** Independent evaluation seam used by the run protocol and failure tests. */
fun interface NexmarkReferenceEvaluator {
    fun evaluate(
        query: NexmarkQuery,
        batches: List<Batch>,
        policy: LatenessPolicy?,
    ): ReferenceAnswer
}

/** Test seams for protocol ordering, comparisons, and SIM work accounting. */
class NexmarkRunHooks(
    val referenceFactory: () -> NexmarkReferenceEvaluator = {
        NexmarkReferenceEvaluator(NexmarkReference::evaluate)
    },
    val comparisonObserved: (Int, Comparison) -> Unit = { _, _ -> },
    val resultObserved: (Int, NexmarkResult) -> Unit = { _, _ -> },
    val rigHooks: NexmarkRigHooks = NexmarkRigHooks(),
    val emissionCounters: (MaintainedQuery) -> Map<String, Long> = MaintainedQuery::emissionCounters,
)

/** ATTACH -> insert batches -> seeded retractions -> fresh-reference drift. */
object NexmarkRun {
    fun run(
        query: NexmarkQuery,
        seed: Long,
        scale: NexmarkScale,
        config: GeneratorConfig = GeneratorConfig(),
        policy: LatenessPolicy? = null,
        drive: Drive,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        batchSize: Int = 100,
        compareEvery: Int = 1,
        retraction: RetractionPlan = RetractionPlan(0.5),
        stateCeiling: Long? = null,
        generationPlacement: GenerationPlacement = GenerationPlacement.OUTSIDE,
        /** Exact input batches for lateness/retraction fixtures; null generates from [scale]. */
        batches: List<Batch>? = null,
        env: RunEnvironment,
        eventPayload: Class<*> = Any::class.java,
        hooks: NexmarkRunHooks = NexmarkRunHooks(),
    ): NexmarkRecord {
        require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }
        require(batchSize >= 1) { "batchSize must be >= 1, was $batchSize" }
        require(compareEvery >= 1) { "compareEvery must be >= 1, was $compareEvery" }
        require(stateCeiling == null || stateCeiling >= 0) { "stateCeiling must be non-negative" }
        require(batches == null || generationPlacement == GenerationPlacement.OUTSIDE) {
            "pre-built batches already place generation outside the measured path"
        }

        // Construction deliberately precedes generation and every host-producing action.
        val builder = Queries.builderFor(query, policy, eventPayload)
        if (builder == null) {
            query.blockedCause?.let { return NexmarkRecord.NotExpressible(query, it) }
            error("${query.name} hand graph is not assembled yet")
        }

        var events: List<Event>? = if (
            generationPlacement == GenerationPlacement.OUTSIDE && batches == null
        ) {
            NexmarkGenerator.generate(seed, scale, config)
        } else {
            null
        }
        var generationNanos = 0L
        var measuredNanos = 0L

        val rig = NexmarkRig.open(drive, seed, journal, dispatchBatch, hooks.rigHooks)
        try {
            rig.beginPhase()
            val streams = EventStreams.spawn(rig)
            val maintained = builder.attach(rig.host.managementInlet, streams)
            rig.completePhase("attach")

            val stateSeries = mutableListOf(captureState(maintained))
            val referenceBatches = mutableListOf<Batch>()
            val insertFigures = mutableListOf<BatchFigure>()
            val retractFigures = mutableListOf<BatchFigure>()

            fun header(): RunHeader {
                val series = stateSeries.toList()
                return RunHeader(
                    seed = seed,
                    scale = scale,
                    generatorConfig = config,
                    query = query,
                    drive = drive,
                    scheduler = rig.schedulerLabel,
                    loadMode = loadModeLabel(journal, dispatchBatch),
                    env = env,
                    policy = policy,
                    stateLabel = when {
                        policy != null -> StateLabel.Evicting(policy.lateness)
                        query.windowed -> StateLabel.UNBOUNDED_STATE
                        else -> StateLabel.NOT_WINDOWED
                    },
                    stateSeries = series,
                    peak = StateAccounting.peak(series),
                    lateness = maintained.lateness(),
                    maxFanOut = maintained.maxFanOut,
                    deviations = (query.deviations + NexmarkGenerator.deviations).distinct(),
                )
            }

            fun failedAt(
                batchIndex: Int,
                evaluator: NexmarkReferenceEvaluator,
            ): NexmarkRecord.Failed? {
                val observed = maintained.read()
                val comparison = ResultComparison.compare(
                    evaluator.evaluate(query, referenceBatches.toList(), policy).result,
                    observed,
                )
                hooks.resultObserved(batchIndex, observed)
                hooks.comparisonObserved(batchIndex, comparison)
                return when (comparison) {
                    Comparison.Equal -> null
                    is Comparison.Differs -> NexmarkRecord.Failed(
                        header = header(),
                        batchIndex = batchIndex,
                        differingRows = comparison.rows,
                    )
                }
            }

            fun stateBoundAt(batchIndex: Int): NexmarkRecord.StateBound? {
                val ceiling = stateCeiling ?: return null
                val exceeded = stateSeries.last().liveRows.entries
                    .sortedBy(Map.Entry<String, Long>::key)
                    .firstOrNull { it.value > ceiling }
                    ?: return null
                return NexmarkRecord.StateBound(
                    header = header(),
                    outlet = exceeded.key,
                    ceiling = ceiling,
                    batchIndex = batchIndex,
                )
            }

            val streamingReference = hooks.referenceFactory()
            var globalBatchIndex = 0

            var firstInsideCounters: Map<String, Long>? = null
            if (generationPlacement == GenerationPlacement.INSIDE) {
                firstInsideCounters = hooks.emissionCounters(maintained)
                rig.beginPhase()
                val generationStarted = System.nanoTime()
                events = NexmarkGenerator.generate(seed, scale, config)
                generationNanos = System.nanoTime() - generationStarted
            }

            val insertBatches = batches?.map { batch -> batch.copy(ops = batch.ops.toList()) }
                ?: checkNotNull(events).chunked(batchSize).mapIndexed { index, chunk ->
                    Batch(index + 1, chunk.map(StreamOp::Add))
                }
            if (generationPlacement == GenerationPlacement.INSIDE && insertBatches.isEmpty()) {
                measuredNanos += durationNanos(rig.completePhase("insert-0"))
            }

            insertBatches.forEachIndexed { zeroBasedIndex, batch ->
                val phaseIndex = zeroBasedIndex + 1
                val countersBefore = if (phaseIndex == 1 && firstInsideCounters != null) {
                    firstInsideCounters!!
                } else {
                    hooks.emissionCounters(maintained).also { rig.beginPhase() }
                }
                val ops = batch.ops
                streams.apply(ops)
                val quiescence = rig.completePhase("insert-$phaseIndex")
                measuredNanos = Math.addExact(measuredNanos, durationNanos(quiescence))
                advanceClock(policy, streams, rig, phaseIndex)
                val emitted = counterDelta(countersBefore, hooks.emissionCounters(maintained))
                stateSeries += captureState(maintained)
                globalBatchIndex++
                referenceBatches += Batch(globalBatchIndex, ops)
                insertFigures += BatchFigure(
                    phase = Phase.INSERT,
                    index = phaseIndex,
                    figure = maintenanceFigure(drive, quiescence, emitted),
                )

                stateBoundAt(globalBatchIndex)?.let { return it }
                if (phaseIndex % compareEvery == 0 || phaseIndex == insertBatches.size) {
                    failedAt(globalBatchIndex, streamingReference)?.let { return it }
                }
            }

            val retractionCandidates = events ?: insertBatches.flatMap { batch ->
                batch.ops.mapNotNull { op -> (op as? StreamOp.Add)?.event }
            }
            val removals = selectRetractions(
                retractionCandidates,
                query.drivingRelation,
                retraction,
                runSeed = seed,
            )
            val retractChunks = removals.chunked(batchSize)
            retractChunks.forEachIndexed { zeroBasedIndex, chunk ->
                val phaseIndex = zeroBasedIndex + 1
                val countersBefore = hooks.emissionCounters(maintained)
                rig.beginPhase()
                val ops = chunk.map(StreamOp::Remove)
                streams.apply(ops)
                val quiescence = rig.completePhase("retract-$phaseIndex")
                measuredNanos = Math.addExact(measuredNanos, durationNanos(quiescence))
                advanceClock(policy, streams, rig, phaseIndex)
                val emitted = counterDelta(countersBefore, hooks.emissionCounters(maintained))
                stateSeries += captureState(maintained)
                globalBatchIndex++
                referenceBatches += Batch(globalBatchIndex, ops)
                retractFigures += BatchFigure(
                    phase = Phase.RETRACT,
                    index = phaseIndex,
                    figure = maintenanceFigure(drive, quiescence, emitted),
                )

                stateBoundAt(globalBatchIndex)?.let { return it }
                if (phaseIndex % compareEvery == 0 || phaseIndex == retractChunks.size) {
                    failedAt(globalBatchIndex, streamingReference)?.let { return it }
                }
            }

            // A second factory call is load-bearing: terminal drift is re-entrant.
            val terminalIndex = globalBatchIndex + 1
            failedAt(terminalIndex, hooks.referenceFactory())?.let { return it }

            if (generationPlacement == GenerationPlacement.INSIDE) {
                val share = when (drive) {
                    Drive.SIM -> 1.0
                    Drive.REAL -> if (measuredNanos <= 0L) 1.0 else {
                        (generationNanos.toDouble() / measuredNanos.toDouble()).coerceIn(0.0, 1.0)
                    }
                }.coerceAtLeast(Double.MIN_VALUE)
                return NexmarkRecord.Unreportable(
                    header = header(),
                    cause = NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath(share),
                )
            }

            return NexmarkRecord.Verified(
                header = header(),
                insert = insertFigures,
                retract = retractFigures,
                finalDrift = Comparison.Equal,
            )
        } finally {
            rig.close()
        }
    }

    /** Open the reusable, untimed-prepare session consumed by the JMH task. */
    fun openSession(
        query: NexmarkQuery,
        seed: Long,
        scale: NexmarkScale,
        policy: LatenessPolicy?,
        drive: Drive,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        batchSize: Int = 100,
    ): NexmarkSession {
        require(batchSize >= 1) { "batchSize must be >= 1, was $batchSize" }
        val builder = Queries.builderFor(query, policy, Any::class.java)
        if (builder == null) {
            query.blockedCause?.let { throw IllegalArgumentException("${query.name} is not expressible: $it") }
            error("${query.name} hand graph is not assembled yet")
        }
        val events = NexmarkGenerator.generate(seed, scale)
        val rig = NexmarkRig.open(drive, seed, journal, dispatchBatch)
        try {
            rig.beginPhase()
            val streams = EventStreams.spawn(rig)
            val maintained = builder.attach(rig.host.managementInlet, streams)
            rig.completePhase("attach")
            return NexmarkSession(
                query = query,
                seed = seed,
                policy = policy,
                batchSize = batchSize,
                rig = rig,
                streams = streams,
                maintained = maintained,
                events = events,
                loadMode = loadModeLabel(journal, dispatchBatch),
            )
        } catch (failure: Throwable) {
            rig.close()
            throw failure
        }
    }

    private fun captureState(maintained: MaintainedQuery): RetainedState = RetainedState(
        liveRows = maintained.liveRows(),
        foldKeys = emptyMap(),
    )

    private fun advanceClock(
        policy: LatenessPolicy?,
        streams: EventStreams,
        rig: NexmarkRig,
        phaseIndex: Int,
    ) {
        if (policy == null) return
        val at = checkNotNull(streams.maxEventTimeApplied) {
            "a lateness-aware batch must have an applied event-time prefix"
        }
        rig.beginPhase()
        streams.tick(at)
        rig.completePhase("clock-$phaseIndex")
    }

    private fun counterDelta(
        before: Map<String, Long>,
        after: Map<String, Long>,
    ): Map<String, Long> = (before.keys + after.keys).associateWith { outlet ->
        val delta = Math.subtractExact(after[outlet] ?: 0L, before[outlet] ?: 0L)
        check(delta >= 0L) { "collector counter '$outlet' moved backwards" }
        delta
    }

    private fun maintenanceFigure(
        drive: Drive,
        quiescence: Figure,
        emitted: Map<String, Long>,
    ): Figure = when (drive) {
        Drive.SIM -> {
            check(quiescence is Figure.Work) { "SIM quiescence must report work" }
            Figure.Work(
                steps = emitted.values.fold(0L, Math::addExact),
                unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS,
            )
        }

        Drive.REAL -> {
            check(quiescence is Figure.Duration) { "REAL quiescence must report duration" }
            quiescence
        }
    }

    private fun durationNanos(figure: Figure): Long = when (figure) {
        is Figure.Duration -> figure.nanos
        is Figure.Work -> 0L
    }
}

/** Reusable graph with generation/slicing outside the timed apply half. */
class NexmarkSession internal constructor(
    private val query: NexmarkQuery,
    private val seed: Long,
    private val policy: LatenessPolicy?,
    private val batchSize: Int,
    private val rig: NexmarkRig,
    private val streams: EventStreams,
    private val maintained: MaintainedQuery,
    private val events: List<Event>,
    val loadMode: LoadModeLabel,
) : AutoCloseable {
    private data class Prepared(val phase: Phase, val ops: List<StreamOp>)

    private val batches = mutableListOf<Batch>()
    private val live = linkedSetOf<Event>()
    private var insertCursor = 0
    private var sequence = 0L
    private var pending: Prepared? = null
    private var undo: List<StreamOp>? = null
    private var appliedSinceValidation = false

    fun prepare(phase: Phase) {
        check(pending == null) { "a Nexmark batch is already prepared" }
        if (appliedSinceValidation) {
            validate()
            undo?.let { restore -> applyOutsideMeasurement("restore", restore) }
        }
        undo = null
        appliedSinceValidation = false

        if (phase == Phase.RETRACT && live.none { it.inRelation(query.drivingRelation) }) {
            events.chunked(batchSize).forEachIndexed { index, chunk ->
                applyOutsideMeasurement("prime-${index + 1}", chunk.map(StreamOp::Add))
            }
        }

        val ops = when (phase) {
            Phase.INSERT -> {
                if (insertCursor >= events.size) insertCursor = 0
                val chunk = events.drop(insertCursor).take(batchSize)
                check(chunk.isNotEmpty()) { "the session has no generated events to insert" }
                insertCursor += chunk.size
                chunk.map(StreamOp::Add)
            }

            Phase.RETRACT -> {
                val candidates = live.filter { it.inRelation(query.drivingRelation) }.toMutableList()
                Collections.shuffle(candidates, Random(seed + sequence++))
                candidates.take(batchSize).also {
                    check(it.isNotEmpty()) { "the session has no live driving events to retract" }
                }.map(StreamOp::Remove)
            }
        }
        pending = Prepared(phase, ops)
    }

    fun applyPrepared(phase: Phase): Int {
        val prepared = checkNotNull(pending) { "prepare($phase) must precede applyPrepared" }
        check(prepared.phase == phase) { "prepared ${prepared.phase} but requested $phase" }
        rig.beginPhase()
        streams.apply(prepared.ops)
        rig.completePhase(phase.name.lowercase())
        advanceClock(phase.name.lowercase())
        foldLive(prepared.ops)
        batches += Batch(batches.size + 1, prepared.ops)
        undo = prepared.ops.asReversed().map { op ->
            when (op) {
                is StreamOp.Add -> StreamOp.Remove(op.event)
                is StreamOp.Remove -> StreamOp.Add(op.event)
            }
        }
        pending = null
        appliedSinceValidation = true
        return prepared.ops.size
    }

    fun validate() {
        val comparison = ResultComparison.compare(
            NexmarkReference.evaluate(query, batches, policy).result,
            maintained.read(),
        )
        check(comparison == Comparison.Equal) {
            val count = (comparison as? Comparison.Differs)?.rows?.size ?: 0
            "$query session differs from the reference in $count row(s)"
        }
        appliedSinceValidation = false
    }

    private fun applyOutsideMeasurement(label: String, ops: List<StreamOp>) {
        if (ops.isEmpty()) return
        rig.beginPhase()
        streams.apply(ops)
        rig.completePhase(label)
        advanceClock(label)
        foldLive(ops)
        batches += Batch(batches.size + 1, ops)
    }

    private fun advanceClock(label: String) {
        if (policy == null) return
        val at = checkNotNull(streams.maxEventTimeApplied)
        rig.beginPhase()
        streams.tick(at)
        rig.completePhase("clock-$label")
    }

    private fun foldLive(ops: List<StreamOp>) {
        ops.forEach { op ->
            when (op) {
                is StreamOp.Add -> live += op.event
                is StreamOp.Remove -> live -= op.event
            }
        }
    }

    override fun close() {
        try {
            if (appliedSinceValidation) validate()
        } finally {
            pending = null
            rig.close()
        }
    }
}

private fun selectRetractions(
    events: List<Event>,
    relation: Relation,
    plan: RetractionPlan,
    runSeed: Long,
): List<Event> {
    val candidates = events.filter { it.inRelation(relation) }.toMutableList()
    Collections.shuffle(candidates, Random(plan.seed ?: runSeed))
    val count = floor(candidates.size.toDouble() * plan.fraction).toInt()
    return candidates.take(count)
}

private fun Event.inRelation(relation: Relation): Boolean = when (relation) {
    Relation.PERSONS -> this is Person
    Relation.AUCTIONS -> this is Auction
    Relation.BIDS -> this is Bid
}
