package civictech.bench.tpch

import civictech.bench.RunEnvironment
import civictech.bench.tpch.queries.Queries
import civictech.bench.tpch.queries.MaintainedQuery
import civictech.cell.durability.Journal

/** Independent evaluation seam used by the run protocol and its failure tests. */
fun interface TpchReferenceEvaluator {
    fun evaluate(query: TpchQuery, tables: TpchTables, params: Any): ViewResult
}

/** Test seams for the protocol; production defaults preserve the public run path. */
class TpchRunHooks(
    val referenceFactory: () -> TpchReferenceEvaluator = {
        TpchReferenceEvaluator(TpchReference::evaluate)
    },
    val refreshFactory: (RefreshPlan, TpchTables) -> List<RefreshBatch> = RefreshStream::generate,
    val comparisonObserved: (Int, Comparison) -> Unit = { _, _ -> },
    /** Stamps the stream-level GrowthNote; receives the outlet balance TpchRun derived. */
    val growthClassifier: (List<RetainedState>, RefreshPlan, Boolean) -> List<RetainedState> =
        StateAccounting::withGrowth,
    /** Cumulative applied-element counters that form the reported SIM maintenance work. */
    val emissionCounters: (MaintainedQuery) -> Map<String, Long> = MaintainedQuery::emissionCounters,
    val rigHooks: TpchRigHooks = TpchRigHooks(),
)

/** LOAD → ATTACH → per-refresh compare → fresh-reference drift protocol. */
object TpchRun {

    /**
     * Open the pre-loaded, attached run used by JMH and the retained-state probe.
     * Setup work is quiesced before this returns; [TpchRefreshSession.applyPrepared]
     * therefore times one refresh and its drain only.
     */
    fun openRefreshSession(
        query: TpchQuery,
        seed: Long,
        sf: Double,
        drive: Drive,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        ordersPerRefresh: Int = (sf * 1_500.0).toInt().coerceAtLeast(1),
    ): TpchRefreshSession {
        val builder = requireNotNull(Queries.builderFor(query)) {
            "${query.name} has no hand-assembled query graph"
        }
        val rig = TpchRig.open(drive, seed, journal, dispatchBatch)
        try {
            rig.beginPhase()
            val base = BaseRelations.spawn(rig)
            base.load(TpchGenerator.generate(seed, sf))
            rig.completePhase("load")

            rig.beginPhase()
            val maintained = builder.attach(rig.host.managementInlet, base)
            rig.completePhase("attach")
            val session = TpchRefreshSession(
                query = query,
                seed = seed,
                sf = sf,
                ordersPerRefresh = ordersPerRefresh,
                rig = rig,
                base = base,
                maintained = maintained,
                referenceParams = builder.referenceParams,
            )
            session.validate()
            return session
        } catch (failure: Throwable) {
            rig.close()
            throw failure
        }
    }

    fun run(
        query: TpchQuery,
        seed: Long,
        sf: Double = 0.001,
        plan: RefreshPlan = RefreshPlan.alternating(seed = seed, sf = sf, batches = 20),
        drive: Drive,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        env: RunEnvironment,
        rowPayload: Class<*> = Any::class.java,
        hooks: TpchRunHooks = TpchRunHooks(),
    ): TpchRecord {
        require(plan.seed == seed) { "refresh-plan seed ${plan.seed} does not match run seed $seed" }
        require(plan.sf == sf) { "refresh-plan sf ${plan.sf} does not match run sf $sf" }
        require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }

        // Builder construction deliberately precedes every host-producing action.
        val builder = Queries.builderFor(query, rowPayload)
            ?: return TpchRecord.Blocked(
                query = query,
                cause = query.blockedCause ?: "${query.name} hand graph is not assembled yet",
            )
        val baseTables = TpchGenerator.generate(seed = seed, sf = sf)
        val refreshes = hooks.refreshFactory(plan, baseTables)
        // Derived from the generated stream, never declared by a caller: a declaration the
        // stream contradicts would silently change the per-record GrowthNote.
        val outletBalancedStream = StateAccounting.isOutletBalanced(refreshes)
        require(refreshes.size == plan.batchCount) {
            "refresh factory returned ${refreshes.size} batches for plan count ${plan.batchCount}"
        }

        val rig = TpchRig.open(
            drive = drive,
            seed = seed,
            journal = journal,
            dispatchBatch = dispatchBatch,
            hooks = hooks.rigHooks,
        )
        try {
            rig.beginPhase()
            val base = BaseRelations.spawn(rig)
            base.load(baseTables)
            val loadFigure = rig.completePhase("load")

            rig.beginPhase()
            val maintained = builder.attach(rig.host.managementInlet, base)
            rig.completePhase("attach")

            val rawStateSeries = mutableListOf(StateAccounting.capture(maintained))
            val emissions = mutableListOf<EmissionObservation>()

            fun header(): RunHeader {
                val completedPlan = plan.copy(batches = plan.batches.take(rawStateSeries.size - 1))
                val stateSeries = hooks.growthClassifier(rawStateSeries, completedPlan, outletBalancedStream)
                return RunHeader(
                    seed = seed,
                    sf = sf,
                    query = query,
                    refreshPlan = plan,
                    drive = drive,
                    scheduler = rig.schedulerLabel,
                    loadMode = loadModeLabel(journal, dispatchBatch),
                    env = env,
                    retainedState = stateSeries.last(),
                    stateSeries = stateSeries,
                    generatorDeviations = TpchGenerator.deviations,
                    incremental = IncrementalityAccounting.measure(emissions),
                )
            }

            val streamingReference = hooks.referenceFactory()
            compare(
                batchIndex = 0,
                expected = streamingReference.evaluate(query, base.logicalTables.snapshot(), builder.referenceParams),
                actual = maintained.read(),
                hooks = hooks,
            ).failureRowsOrNull()?.let { differences ->
                return TpchRecord.Failed(header(), batchIndex = 0, differingRows = differences)
            }

            val rf1 = mutableListOf<BatchFigure>()
            val rf2 = mutableListOf<BatchFigure>()
            refreshes.forEachIndexed { zeroBasedIndex, batch ->
                val batchIndex = zeroBasedIndex + 1
                val kind = when (batch) {
                    is RefreshBatch.Rf1 -> RefreshKind.RF1
                    is RefreshBatch.Rf2 -> RefreshKind.RF2
                }
                require(kind == plan.batches[zeroBasedIndex]) {
                    "refresh batch $batchIndex is $kind but plan says ${plan.batches[zeroBasedIndex]}"
                }

                val countersBefore = hooks.emissionCounters(maintained)
                val deletedRows = if (batch is RefreshBatch.Rf2) {
                    batch.orderkeys.sumOf { orderKey ->
                        1L + base.logicalTables.lineitemsFor(orderKey).size.toLong()
                    }
                } else {
                    0L
                }
                rig.beginPhase()
                base.apply(batch)
                val quiescenceFigure = rig.completePhase(kind.name.lowercase() + "-$batchIndex")
                val countersAfter = hooks.emissionCounters(maintained)
                val emittedElements = IncrementalityAccounting.delta(countersBefore, countersAfter)
                emissions += EmissionObservation(
                    batchIndex = batchIndex,
                    kind = kind,
                    emittedElements = emittedElements,
                    upstreamRowsDeleted = deletedRows,
                )
                rawStateSeries += StateAccounting.capture(maintained)
                val batchFigure = BatchFigure(
                    kind = kind,
                    index = batchIndex,
                    figure = maintenanceFigure(drive, quiescenceFigure, emittedElements),
                )
                if (kind == RefreshKind.RF1) rf1 += batchFigure else rf2 += batchFigure

                compare(
                    batchIndex = batchIndex,
                    expected = streamingReference.evaluate(
                        query,
                        base.logicalTables.snapshot(),
                        builder.referenceParams,
                    ),
                    actual = maintained.read(),
                    hooks = hooks,
                ).failureRowsOrNull()?.let { differences ->
                    return TpchRecord.Failed(header(), batchIndex, differences)
                }

                if (batch is RefreshBatch.Rf2) {
                    val stale = batch.orderkeys.flatMap(maintained::rowsReferencing)
                    if (stale.isNotEmpty()) {
                        return TpchRecord.Failed(
                            header = header(),
                            batchIndex = batchIndex,
                            differingRows = stale.map { (outlet, _) ->
                                RowDiff(
                                    key = listOf(batch.orderkeys.firstOrNull() ?: -1L, outlet),
                                    column = "rowsReferencing",
                                    expected = 0L,
                                    actual = stale.count { it.first == outlet }.toLong(),
                                )
                            }.distinct(),
                        )
                    }
                }
            }

            // A second factory call is load-bearing: the terminal drift check must
            // not share evaluator instance state with per-batch comparisons.
            val freshReference = hooks.referenceFactory()
            val finalIndex = plan.batchCount + 1
            val finalComparison = compare(
                batchIndex = finalIndex,
                expected = freshReference.evaluate(query, base.logicalTables.snapshot(), builder.referenceParams),
                actual = maintained.read(),
                hooks = hooks,
            )
            return when (finalComparison) {
                Comparison.Equal -> TpchRecord.Verified(
                    header = header(),
                    load = loadFigure,
                    rf1 = rf1,
                    rf2 = rf2,
                    finalDrift = Comparison.Equal,
                )

                is Comparison.Differs -> TpchRecord.Failed(
                    header = header(),
                    batchIndex = finalIndex,
                    differingRows = finalComparison.rows,
                )
            }
        } finally {
            rig.close()
        }
    }

    private fun compare(
        batchIndex: Int,
        expected: ViewResult,
        actual: ViewResult,
        hooks: TpchRunHooks,
    ): Comparison = ViewComparison.compare(expected, actual).also { comparison ->
        hooks.comparisonObserved(batchIndex, comparison)
    }

    private fun Comparison.failureRowsOrNull(): List<RowDiff>? = when (this) {
        Comparison.Equal -> null
        is Comparison.Differs -> rows
    }

    /**
     * SIM's scheduler count is query-independent refresh-ingress instrumentation. Report
     * the observable query propagation volume instead: every delta element applied by a
     * materialized query-outlet collector during this quiescent refresh interval. REAL
     * retains its elapsed-time figure; JMH is its primary measurement path.
     */
    private fun maintenanceFigure(
        drive: Drive,
        quiescenceFigure: Figure,
        emittedElements: Map<String, Long>,
    ): Figure = when (drive) {
        Drive.SIM -> {
            check(quiescenceFigure is Figure.Work) {
                "SIM quiescence must produce scheduler work, was $quiescenceFigure"
            }
            Figure.Work(
                steps = emittedElements.values.fold(0L) { total, count -> Math.addExact(total, count) },
                unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS,
            )
        }

        Drive.REAL -> {
            check(quiescenceFigure is Figure.Duration) {
                "REAL quiescence must produce elapsed time, was $quiescenceFigure"
            }
            quiescenceFigure
        }
    }
}

/** Default-lane proof that every JMH parameter value resolves before a fork starts. */
object TpchBenchmarkParameters {
    val queryNames: List<String> = TpchQuery.entries
        .filter { it.tier == TpchTier.HAND_1 }
        .map(TpchQuery::name)
    val scaleFactors: List<String> = listOf("0.001", "0.01")
    val loadModes: List<String> = LoadModes.names

    fun query(value: String): TpchQuery {
        val query = runCatching { TpchQuery.valueOf(value) }
            .getOrElse { throw IllegalArgumentException("unknown TPC-H query '$value'", it) }
        require(query.tier == TpchTier.HAND_1) { "$value is blocked, not a benchmark query" }
        return query
    }

    fun scaleFactor(value: String): Double = value.toDoubleOrNull()?.also { sf ->
        require(sf in scaleFactors.map(String::toDouble)) { "unsupported TPC-H scale factor '$value'" }
    } ?: throw IllegalArgumentException("invalid TPC-H scale factor '$value'")
}

/** Rows touched by one refresh invocation, exposed as JMH auxiliary counters. */
data class RefreshRows(
    val inserted: Long,
    val deleted: Long,
)

/** Reusable loaded graph with untimed batch preparation and one timed apply/drain half. */
class TpchRefreshSession internal constructor(
    private val query: TpchQuery,
    private val seed: Long,
    private val sf: Double,
    private val ordersPerRefresh: Int,
    private val rig: TpchRig,
    private val base: BaseRelations,
    private val maintained: MaintainedQuery,
    private val referenceParams: Any,
) : AutoCloseable {

    private var sequence = 0L
    private var pending: RefreshBatch? = null
    private var undo: RefreshBatch? = null
    private var appliedSinceValidation = false

    /**
     * Prepare one batch. JMH restores the preceding invocation outside the timed body;
     * the balanced growth probe passes `restorePrevious = false` to retain its stream.
     */
    fun prepare(
        kind: RefreshKind,
        restorePrevious: Boolean = true,
    ) {
        check(pending == null) { "a refresh batch is already prepared" }
        if (appliedSinceValidation) validate()
        val pairedRf2 = if (!restorePrevious && kind == RefreshKind.RF2) {
            undo as? RefreshBatch.Rf2
        } else {
            null
        }
        if (restorePrevious) {
            undo?.let { compensation ->
                rig.beginPhase()
                base.apply(compensation)
                rig.completePhase("restore")
            }
        }
        undo = null
        appliedSinceValidation = false
        if (pairedRf2 != null) {
            pending = pairedRf2
            return
        }
        val plan = RefreshPlan(
            seed = seed + sequence++,
            sf = sf,
            ordersPerRefresh = ordersPerRefresh,
            batches = listOf(kind),
        )
        pending = RefreshStream.generate(plan, base.logicalTables.snapshot()).single()
    }

    /** Apply exactly the prepared RF1 or RF2 and drain the host to quiescence. */
    fun applyPrepared(expected: RefreshKind): RefreshRows {
        val batch = checkNotNull(pending) { "prepare($expected) must precede applyPrepared" }
        val actual = when (batch) {
            is RefreshBatch.Rf1 -> RefreshKind.RF1
            is RefreshBatch.Rf2 -> RefreshKind.RF2
        }
        check(actual == expected) { "prepared $actual but benchmark requested $expected" }
        undo = when (batch) {
            is RefreshBatch.Rf1 -> RefreshBatch.Rf2(batch.orders.map(Orders::orderKey).sorted())
            is RefreshBatch.Rf2 -> RefreshBatch.Rf1(
                orders = batch.orderkeys.map { checkNotNull(base.logicalTables.order(it)) },
                lineitems = batch.orderkeys.flatMap(base.logicalTables::lineitemsFor),
            )
        }
        val rows = when (batch) {
            is RefreshBatch.Rf1 -> RefreshRows(
                inserted = batch.orders.size.toLong() + batch.lineitems.size,
                deleted = 0,
            )
            is RefreshBatch.Rf2 -> RefreshRows(
                inserted = 0,
                deleted = batch.orderkeys.sumOf { key ->
                    1L + base.logicalTables.lineitemsFor(key).size.toLong()
                },
            )
        }
        rig.beginPhase()
        base.apply(batch)
        rig.completePhase(actual.name.lowercase())
        pending = null
        appliedSinceValidation = true
        return rows
    }

    fun retainedState(): RetainedState = StateAccounting.capture(maintained)

    internal fun validate() {
        val comparison = ViewComparison.compare(
            expected = TpchReference.evaluate(query, base.logicalTables.snapshot(), referenceParams),
            actual = maintained.read(),
        )
        check(comparison == Comparison.Equal) {
            val count = (comparison as? Comparison.Differs)?.rows?.size ?: 0
            "$query refresh session differs from the reference in $count row(s)"
        }
        appliedSinceValidation = false
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
