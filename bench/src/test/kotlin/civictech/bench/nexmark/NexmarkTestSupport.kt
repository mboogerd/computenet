package civictech.bench.nexmark

import civictech.bench.RunEnvironment

fun nexmarkTestEnvironment(): RunEnvironment = RunEnvironment(
    jvmVendor = "test-vendor",
    jvmVersion = "21",
    heapSettings = "test-heap",
    cpuModel = "test-cpu",
    coreCount = 1,
    os = "test-os",
    jmhMode = "test",
    forkCount = 1,
    warmupIterations = 1,
    measurementIterations = 1,
    harnessCommitSha = "test-sha",
)

fun nexmarkTestStateSeries(): List<RetainedState> = listOf(
    RetainedState(
        liveRows = mapOf("outlet" to 1L),
        foldKeys = mapOf("fold" to 1L),
    ),
)

fun nexmarkTestHeader(
    query: NexmarkQuery = NexmarkQuery.Q1,
    seed: Long = 7L,
    scheduler: String = SIM_SCHEDULER,
    policy: LatenessPolicy? = null,
    stateLabel: StateLabel? = null,
    stateSeries: List<RetainedState> = nexmarkTestStateSeries(),
    peak: RetainedState = StateAccounting.peak(stateSeries),
    lateness: LatenessAccounting? = policy?.let { LatenessAccounting(0L, 0L, 0L, 0L) },
    maxFanOut: Int = 1,
    deviations: List<String> = listOf("test deviation"),
    specRevision: String = SPEC_REVISION,
    framing: String = FRAMING,
    comparedAtQuiescence: Boolean = true,
    variance: Double? = null,
): RunHeader {
    val label = stateLabel ?: when {
        policy != null -> StateLabel.Evicting(policy.lateness)
        query.windowed -> StateLabel.UNBOUNDED_STATE
        else -> StateLabel.NOT_WINDOWED
    }
    return RunHeader(
        seed = seed,
        scale = NexmarkScale(events = 2_000, eventsPerSecond = 50),
        generatorConfig = GeneratorConfig(),
        query = query,
        drive = Drive.SIM,
        scheduler = scheduler,
        loadMode = LoadModeLabel("volatile", "per-message"),
        env = nexmarkTestEnvironment(),
        policy = policy,
        stateLabel = label,
        stateSeries = stateSeries,
        peak = peak,
        lateness = lateness,
        maxFanOut = maxFanOut,
        deviations = deviations,
        specRevision = specRevision,
        framing = framing,
        comparedAtQuiescence = comparedAtQuiescence,
        variance = variance,
    )
}

fun verifiedNexmark(query: NexmarkQuery = NexmarkQuery.Q1, seed: Long = 7L): NexmarkRecord.Verified =
    NexmarkRecord.Verified(
        header = nexmarkTestHeader(query = query, seed = seed),
        insert = listOf(
            BatchFigure(
                phase = Phase.INSERT,
                index = 0,
                figure = Figure.Work(100L, unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS),
            ),
        ),
        retract = listOf(
            BatchFigure(
                phase = Phase.RETRACT,
                index = 0,
                figure = Figure.Work(50L, unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS),
            ),
        ),
        finalDrift = Comparison.Equal,
    )
