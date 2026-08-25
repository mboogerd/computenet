package civictech.bench.micro

/**
 * The per-invocation batch cycle `OperatorThroughputBenchmark` drives: the untimed half
 * ([prepare]) and the timed half ([applyPending]), split at exactly the line JMH's
 * `@Setup(Level.Invocation)` draws (computenet-y7hc, `[BEN1-18]`, `[BEN1-28]`).
 *
 * ## Why this is a fixture and not four lines in the benchmark
 *
 * The property this type exists to hold — *every timed invocation starts from the same
 * live state* — is not observable from `:bench:test`, because `bench/build.gradle.kts`
 * wires the test source set against `main`'s output and **not** against `src/jmh`, so no
 * JUnit test can see an `@Benchmark` class at all. Putting the cycle here puts the
 * invariant somewhere it can be pinned; `BoundedInvocationStateTest` is that pin. The
 * benchmark keeps only the JMH wiring.
 *
 * ## What "bounded" means here, exactly
 *
 * Under [Direction.RETRACT] the cycle has always been self-balancing: [prepare] applies a
 * covering insert batch and quiesces, [applyPending] retracts exactly that batch, and the
 * graph ends each invocation at the live state it started it in. Under
 * [Direction.INSERT] it was not: [prepare] only generated a fresh batch, so each timed
 * body added a batch's worth of membership that nothing ever removed and the operators'
 * live `TagState` grew monotonically across every invocation of an iteration — the
 * measured body of invocation 40 running against 40 batches of live state, invocation 1's
 * against none.
 *
 * INSERT now carries the mirror image of RETRACT's covering insert: [prepare] retracts
 * the batch the **previous** timed body inserted, before generating the next one. Both
 * directions therefore begin every timed invocation from the same live state, and the
 * compensating work sits on the `@Setup` side of the line where JMH does not count it.
 *
 * Two limits, stated here rather than left to be rediscovered:
 *
 * - **Live** state is what is bounded, not the tag maps. The observed-remove algebra
 *   keeps a tombstone per retracted tag, so total map size still grows within an
 *   iteration — under INSERT exactly as it already did under RETRACT. `Level.Iteration`
 *   rebuilding the graph is still what bounds *that*.
 * - This is a structural claim about the harness, not an empirical one about the
 *   numbers. Nothing here demonstrates that `[BEN1-28]`'s INSERT/RETRACT dispersion
 *   asymmetry is resolved; that is a re-measured sweep on a quiesced host
 *   (computenet-x9e.14) and no result may be read as settling it until that lands.
 *
 * Not thread-safe, and not meant to be: a JMH `@State(Scope.Thread)` owns one.
 */
class InvocationCycle(
    private val graph: MicroGraph,
    private val stream: DeltaStream,
    private val direction: Direction,
    private val batchSize: Int = ThroughputReport.DELTAS_PER_BATCH,
) {

    init {
        require(batchSize >= 0) { "batchSize must be >= 0, was $batchSize" }
    }

    /**
     * Under [Direction.INSERT], the batch the previous timed body inserted and the next
     * [prepare] therefore has to retract. `null` before the first invocation — the only
     * invocation whose setup does no compensating work, because there is nothing yet to
     * compensate for.
     */
    private var outstanding: DeltaBatch? = null

    private var prepared: DeltaBatch? = null

    /** The batch the next [applyPending] applies. Available only after [prepare]. */
    val pending: DeltaBatch
        get() = checkNotNull(prepared) { "prepare() has not run yet" }

    /**
     * The untimed half: return the graph to its per-invocation baseline live state and
     * generate the batch the timed body will apply.
     *
     * Generation cannot happen inside the timed body — `DeltaStream` allocates and
     * shuffles, and re-applying one cached batch instead would measure the dedup-absorb
     * path (`Deltas.kt`'s "tag churn" note) rather than the operator's work. The
     * state-restoring work cannot happen there either, for the same reason RETRACT's
     * covering insert never did: it is real work, and counting it would put a whole extra
     * batch inside a number reported as one batch's throughput.
     */
    fun prepare() {
        val inserts = stream.insert(batchSize)
        prepared = when (direction) {
            Direction.INSERT -> {
                // The mirror of RETRACT's covering insert below: undo the previous timed
                // body before the next one starts, so both directions time a batch
                // against the same live state every invocation.
                outstanding?.let { graph.applyAndQuiesce(Deltas.retract(it)) }
                outstanding = inserts
                inserts
            }

            Direction.RETRACT -> {
                graph.applyAndQuiesce(inserts)
                Deltas.retract(inserts)
            }
        }
    }

    /**
     * The timed half: apply the prepared batch, drive the graph to quiescence, and return
     * the collector's arrival count so the propagation is observably consumed rather than
     * eligible for elimination.
     */
    fun applyPending(): Long {
        graph.applyAndQuiesce(pending)
        return graph.arrivals
    }
}
