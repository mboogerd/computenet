package civictech.bench.tpch

import civictech.bench.RunEnvironment
import civictech.bench.tpch.queries.Queries
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
    val rigHooks: TpchRigHooks = TpchRigHooks(),
)

/** LOAD → ATTACH → per-refresh compare → fresh-reference drift protocol. */
object TpchRun {

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

            fun header(): RunHeader = RunHeader(
                seed = seed,
                sf = sf,
                query = query,
                refreshPlan = plan,
                drive = drive,
                scheduler = rig.schedulerLabel,
                loadMode = loadModeLabel(journal, dispatchBatch),
                env = env,
                retainedState = RetainedState(
                    liveRows = maintained.liveRows(),
                    foldKeys = emptyMap(),
                    growth = GrowthNote.Flat,
                ),
                generatorDeviations = TpchGenerator.deviations,
            )

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

                rig.beginPhase()
                base.apply(batch)
                val figure = rig.completePhase(kind.name.lowercase() + "-$batchIndex")
                val batchFigure = BatchFigure(kind, batchIndex, figure)
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
}
