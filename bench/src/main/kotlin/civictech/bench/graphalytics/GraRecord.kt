package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment

/** A dataset identity captured in a benchmark record. */
data class DatasetFacts(
    val name: String,
    val declaredVertices: Int,
    val declaredEdges: Int,
    val sourceCommit: String,
) {
    init {
        require(name.isNotBlank()) { "dataset name must not be blank" }
        require(declaredVertices >= 0) { "declaredVertices must be non-negative" }
        require(declaredEdges >= 0) { "declaredEdges must be non-negative" }
        require(sourceCommit.isNotBlank()) { "sourceCommit must not be blank" }
    }
}

/** The durability and ingress labels supplied by the run protocol. */
data class LoadModeLabel(
    val durability: String,
    val ingress: String,
)

/** A phase figure whose unit is determined by the run's [Drive]. */
sealed interface Figure {
    /**
     * Deterministic simulation work; wall-clock nanoseconds are intentionally absent.
     *
     * [dispersionSteps] is `null` for a single run, which observes no run-to-run
     * dispersion; only [summarise] over repeated runs sets it.
     */
    data class Work(
        val steps: Long,
        val dispersionSteps: Long? = null,
    ) : Figure {
        init {
            require(steps >= 0) { "simulation work steps must be non-negative" }
            require(dispersionSteps == null || dispersionSteps >= 0) {
                "simulation work dispersionSteps must be non-negative"
            }
        }
    }

    /**
     * Real elapsed time and its non-negative dispersion.
     *
     * [dispersionNanos] is `null` for a single run, which observes no run-to-run
     * dispersion; only [summarise] over repeated runs sets it.
     */
    data class Duration(
        val nanos: Long,
        val dispersionNanos: Long? = null,
    ) : Figure {
        init {
            require(nanos >= 0) { "duration nanos must be non-negative" }
            require(dispersionNanos == null || dispersionNanos >= 0) {
                "duration dispersionNanos must be non-negative"
            }
        }
    }
}

/** Iteration accounting shared by the non-iterative slice and its follow-on. */
sealed interface Iterations {
    data object NonIterative : Iterations

    data class Executed(
        val count: Int,
        val messagesPerIteration: List<Long>,
    ) : Iterations {
        init {
            require(count >= 0) { "iteration count must be non-negative" }
            require(messagesPerIteration.size == count) {
                "messagesPerIteration must have one value per iteration"
            }
            require(messagesPerIteration.all { it >= 0 }) {
                "messagesPerIteration must be non-negative"
            }
        }
    }

    data class IterationCut(
        val count: Int,
        val residual: Double,
    ) : Iterations {
        init {
            require(count >= 0) { "iteration count must be non-negative" }
            require(residual.isFinite() && residual >= 0.0) {
                "iteration residual must be finite and non-negative"
            }
        }
    }

    data class NonConvergence(
        val bound: Int,
        val residual: Double,
    ) : Iterations {
        init {
            require(bound >= 0) { "iteration bound must be non-negative" }
            require(residual.isFinite() && residual >= 0.0) {
                "iteration residual must be finite and non-negative"
            }
        }
    }
}

/** Whether retained-state growth is meaningful for the record's algorithm. */
sealed interface GrowthNote {
    data object NotApplicableNonIterative : GrowthNote
    data object Bounded : GrowthNote
    data class Growing(val structure: String) : GrowthNote {
        init {
            require(structure.isNotBlank()) { "growth structure must not be blank" }
        }
    }
}

/** Structural retained-state counts recorded after the run's phases. */
data class RetainedState(
    val edgeRows: Long,
    val neighbourRows: Long,
    val candidateRows: Long,
    val triangleRows: Long,
    val foldKeys: Map<String, Long>,
    val growthAcrossIterations: GrowthNote,
) {
    init {
        require(edgeRows >= 0) { "edgeRows must be non-negative" }
        require(neighbourRows >= 0) { "neighbourRows must be non-negative" }
        require(candidateRows >= 0) { "candidateRows must be non-negative" }
        require(triangleRows >= 0) { "triangleRows must be non-negative" }
        require(foldKeys.values.all { it >= 0 }) {
            "fold key counts must be non-negative"
        }
    }
}

/** The complete run context shared by records that describe an executed run. */
data class RunHeader(
    val specVersion: String = SPEC_VERSION,
    val dataset: DatasetFacts,
    val algorithm: Algorithm,
    val parameters: Map<String, String>,
    val drive: Drive,
    val scheduler: String,
    val loadMode: LoadModeLabel,
    val env: RunEnvironment,
    val iterations: Iterations,
    val retainedState: RetainedState,
    val recovered: Boolean? = null,
) {
    init {
        require(specVersion == SPEC_VERSION) {
            "specVersion must be '$SPEC_VERSION', was '$specVersion'"
        }
        require(scheduler.isNotBlank()) { "scheduler must not be blank" }
    }
}

/**
 * Relative variance above this threshold cannot be reported as a representative mean.
 *
 * The 0.10 boundary intentionally tolerates ordinary run-to-run measurement noise,
 * while treating a spread of more than 10% as material enough that a single mean could
 * hide meaningfully different performance.
 */
const val VARIANCE_THRESHOLD = 0.10

/** The mutually exclusive states a Graphalytics run can occupy. */
enum class Status {
    validated,
    blocked,
    not_expressible,
    failed,
    measurement_only,
    unreportable,
}

/** A record of an executed or deliberately unexecuted algorithm run. */
sealed interface GraRecord {
    val specVersion: String
        get() = SPEC_VERSION

    val status: Status

    data class Validated(
        val header: RunHeader,
        val load: Figure,
        val compute: Figure,
        val validation: Validation.Passed,
    ) : GraRecord {
        override val status: Status
            get() = Status.validated
    }

    data class Failed(
        val header: RunHeader,
        val validation: Validation.Failed,
    ) : GraRecord {
        override val status: Status
            get() = Status.failed
    }

    data class MeasurementOnly(
        val header: RunHeader,
        val load: Figure,
        val compute: Figure,
    ) : GraRecord {
        override val status: Status
            get() = Status.measurement_only
    }

    data class Unreportable(
        val header: RunHeader,
        val variance: Double,
        val threshold: Double = VARIANCE_THRESHOLD,
    ) : GraRecord {
        init {
            require(variance.isFinite() && variance >= 0.0) {
                "variance must be finite and non-negative"
            }
            require(threshold == VARIANCE_THRESHOLD) {
                "threshold must be VARIANCE_THRESHOLD ($VARIANCE_THRESHOLD)"
            }
            require(variance > threshold) {
                "variance must exceed threshold ($threshold)"
            }
        }

        override val status: Status
            get() = Status.unreportable
    }

    data class Blocked(
        val algorithm: Algorithm,
        val cause: String,
    ) : GraRecord {
        init {
            require(cause.isNotBlank()) { "blocked cause must not be blank" }
        }

        override val status: Status
            get() = Status.blocked
    }

    data class NotExpressible(
        val algorithm: Algorithm,
        val cause: String,
    ) : GraRecord {
        init {
            require(cause.isNotBlank()) { "not-expressible cause must not be blank" }
        }

        override val status: Status
            get() = Status.not_expressible
    }
}

/** One algorithm's row in the explicit six-algorithm coverage report. */
data class CoverageRow(
    val algorithm: Algorithm,
    val status: Status,
    val cause: String?,
)

/** A coverage report that cannot omit or duplicate an algorithm. */
class CoverageTable(val rows: List<CoverageRow>) {
    init {
        val expectedAlgorithms = Algorithm.entries.toSet()
        require(rows.size == expectedAlgorithms.size) {
            "CoverageTable requires exactly ${expectedAlgorithms.size} rows, found ${rows.size}"
        }
        require(rows.map { it.algorithm }.toSet() == expectedAlgorithms) {
            "CoverageTable requires exactly one row per Algorithm entry"
        }
        rows.filter { it.status != Status.validated }.forEach { row ->
            require(!row.cause.isNullOrBlank()) {
                "CoverageTable requires a cause for ${row.algorithm} with status ${row.status}"
            }
        }
    }

    /** Render the rows without collapsing a failure state into a timing result. */
    fun render(): String = buildString {
        appendLine("| Algorithm | Status | Cause |")
        appendLine("| --- | --- | --- |")
        rows.forEach { row ->
            append('|')
            append(' ')
            append(row.algorithm.name)
            append(" | ")
            append(row.status.name)
            append(" | ")
            append(row.cause.orEmpty())
            appendLine(" |")
        }
    }
}
