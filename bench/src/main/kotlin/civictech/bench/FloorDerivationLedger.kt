package civictech.bench

import civictech.bench.micro.ThroughputReport
import java.io.File
import kotlin.math.abs

/**
 * A refusal from the partial-derivation ledger (`computenet-3omz`).
 *
 * Its own type rather than a bare [IllegalArgumentException] because every one of this
 * file's refusals is a *safety* refusal — an incomplete row set, a fourth observation, a
 * second measuring JVM, a half-parsed file — and a caller that wants to distinguish
 * "this derivation is not finished" from "this argument is nonsense" must be able to.
 */
class FloorLedgerException(message: String) : IllegalArgumentException(message)

/**
 * How many observations of a row a complete derivation folds: one per required run.
 *
 * Aliased to [CLASS_FLOOR_MIN_RUNS] rather than restated, because row-set decomposition
 * does not change how many times a row is measured — it changes only which rows are
 * measured together. See [ClassFloorDerivation]'s "Decomposition" section.
 */
const val CLASS_FLOOR_OBSERVATIONS_PER_ROW: Int = CLASS_FLOOR_MIN_RUNS

/**
 * The fraction of the core count `run-series.sh`'s one-directional load gate admits, and
 * the same fraction `computenet-7v7m`'s `derive-run.sh` mirrored per run.
 *
 * Stated here so a [GateReading] can refuse an attested threshold that is not the gate's
 * rule, rather than recording whatever number the operator's shell happened to print.
 */
const val QUIESCED_LOAD_FACTOR: Double = 0.25

/**
 * The pre-registered row count of each benchmark class in `:bench`.
 *
 * **This table is a tripwire, and it is the reason a plan is checkable at all.** A
 * completeness check answers "has every planned row been measured three times", so a plan
 * built from a *filtered* enumeration — a `-lp` listing taken with the operator's row
 * filter still applied, a CSV from one unit mistaken for the class's universe — would
 * satisfy completeness vacuously, on a universe far too small, and render a floor that is
 * a maximum over a fraction of the class. Cross-checking the plan's size against a count
 * fixed in committed source is what makes that impossible.
 *
 * The counts are the product of a class's `@Benchmark` methods and its `@Param` enums'
 * constants, verified against those enums on 2026-08-26 and matching `computenet-akfa`'s
 * measured sizing:
 *
 * - `CellFootprintBenchmark` — 1 method (`realSnapshot`) x 7 `CellFamily` x 3 `Scale`
 *   = **21**. This is the class already derived (`computenet-7v7m`), whose three runs
 *   reported 21 rows each.
 * - `OperatorThroughputBenchmark` — 2 methods (`sim`, `real`) x 18 `Subject` x 2
 *   `Direction` = **72**.
 * - `FanOutScalingBenchmark` — 6 methods (`sim`, `real`, `simFixedState`,
 *   `realFixedState`, `simBatchFixedState`, `realBatchFixedState`) x 5 `FanDegree`
 *   = **30**.
 * - `BoundedReadBenchmark` — 2 methods (`realDirect`, `realHostedSnapshotOf`) x 3
 *   `SetScale` = **6**.
 *
 * A count here is a claim about the class's annotation configuration, so **adding a
 * `@Benchmark` method or an enum constant changes it**, and the plan for that class will
 * be refused until this table is updated in the same commit. That refusal is the point:
 * a row universe that grew without anyone noticing is exactly the case where a floor
 * derived from the old universe would be too low.
 *
 * **`SmokeBenchmark` is deliberately absent.** It carries only `@State`/`@BenchmarkMode`/
 * `@OutputTimeUnit` and relies on JMH's own defaults for `@Fork`/`@Warmup`/`@Measurement`
 * (see `SmokeBenchmark`'s KDoc — it is a discovery sentinel, not a derivation target), so
 * [UnitSizing.estimateRowSeconds] can never size a unit for it: reading those three
 * annotations by reflection finds nothing at method or class level and refuses
 * (`computenet-epxt`). Pre-registering a count for it here would let `plan` accept a class
 * `next` can never advance — plannable but not actually serviceable. Leaving it out of
 * this table makes `plan --class SmokeBenchmark` refuse too (`DerivationPlan`'s own "no
 * pre-registered row count" check), so the two subcommands agree: neither derives a floor
 * for it. Should `SmokeBenchmark` ever need deriving, the fix is adding its own
 * `@Fork`/`@Warmup`/`@Measurement` annotations, not registering it here first.
 */
val EXPECTED_PLAN_ROW_COUNTS: Map<String, Int> = mapOf(
    "CellFootprintBenchmark" to 21,
    "OperatorThroughputBenchmark" to 72,
    "FanOutScalingBenchmark" to 30,
    "BoundedReadBenchmark" to 6,
)

/**
 * One row of a benchmark class's sweep: a `@Benchmark` method and the `@Param` binding it
 * ran under. The unit JMH forks for, and therefore the unit a derivation measures.
 *
 * @param method the benchmark's simple method name — `ThroughputReport.JmhRow.method`.
 * @param params the row's `@Param` bindings. Blank-valued entries are dropped on
 *   construction: a JMH CSV covering more than one class carries a `Param:` column for
 *   every parameter any class in the file declares, and writes an empty cell where the
 *   parameter does not apply to the row. Keeping those empty cells would make the same
 *   row encode differently depending on what else was in the results file, which is
 *   precisely the way a "missing" row is manufactured out of a present one.
 */
data class RowKey(val method: String, val params: Map<String, String>) {

    init {
        require(method.isNotBlank()) { "a row's benchmark method must not be blank" }
    }

    /** Human-readable, and the form the completeness refusal names rows in. */
    fun describe(): String =
        if (params.isEmpty()) method
        else "$method[" + params.entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key}=${it.value}" } + "]"

    /** The persisted form. Parameters are sorted, so encoding is order-independent. */
    fun encode(): String = buildString {
        append(escapeField(method))
        params.entries.sortedBy { it.key }.forEach { (key, value) ->
            append(';')
            append(escapeField(key))
            append('=')
            append(escapeField(value))
        }
    }

    companion object {

        /** Drops blank-valued parameters — see this class's `params` documentation. */
        fun of(method: String, params: Map<String, String>): RowKey =
            RowKey(method, params.filterValues { it.isNotBlank() })

        /** The inverse of [encode]. Throws [FloorLedgerException] on anything else. */
        fun decode(encoded: String): RowKey {
            val segments = encoded.split(';')
            val method = unescapeField(segments.first(), encoded)
            val params = segments.drop(1).associate { segment ->
                val split = segment.indexOf('=')
                if (split < 0) {
                    throw FloorLedgerException(
                        "ledger row '$encoded' has a parameter segment '$segment' with no '='"
                    )
                }
                unescapeField(segment.take(split), encoded) to
                    unescapeField(segment.substring(split + 1), encoded)
            }
            return RowKey(method, params)
        }
    }
}

/**
 * The host-load attestation taken immediately before one measuring unit — the per-unit
 * copy of `run-series.sh`'s gate.
 *
 * Per unit, not per class, for the reason the whole ledger exists: units are spread over
 * hours or days, and a gate reading taken before the first one says nothing about the
 * host when the fourth ran.
 *
 * @param oneMinuteLoad the 1-minute load average read immediately before the unit.
 * @param cores the host's core count.
 * @param attestedThreshold the threshold the operator's runner actually applied. Recorded
 *   rather than only computed, and then checked against [threshold], so a runner that
 *   gated on some other number is visible instead of silently accepted.
 */
data class GateReading(
    val oneMinuteLoad: Double,
    val cores: Int,
    val attestedThreshold: Double,
) {

    /** The gate's own rule: [QUIESCED_LOAD_FACTOR] x [cores]. */
    val threshold: Double get() = cores * QUIESCED_LOAD_FACTOR

    init {
        require(cores > 0) { "cores must be positive, was $cores" }
        require(oneMinuteLoad.isFinite() && oneMinuteLoad >= 0.0) {
            "the 1-minute load average must be finite and non-negative, was $oneMinuteLoad"
        }
        require(attestedThreshold.isFinite()) {
            "the attested threshold must be finite, was $attestedThreshold"
        }
        // 0.005 is the rounding of `printf "%.2f"`, which is how both run-series.sh and
        // derive-run.sh compute the threshold. Anything wider is a different rule.
        require(abs(attestedThreshold - threshold) <= 0.005) {
            "the attested gate threshold $attestedThreshold is not " +
                "$QUIESCED_LOAD_FACTOR x $cores cores = $threshold; a unit gated on some " +
                "other number was not gated by run-series.sh's rule"
        }
        require(oneMinuteLoad <= attestedThreshold) {
            "REFUSED: 1-minute load $oneMinuteLoad is above the quiesced threshold " +
                "$attestedThreshold on $cores cores; the host was not quiesced when this " +
                "unit was measured, and a floor derived through interference is a floor " +
                "measuring the interference"
        }
    }
}

/**
 * One measuring unit: everything about a single JMH invocation except its rows.
 *
 * @param unitId the operator's label for the invocation, unique within a derivation. Two
 *   units may not share one — an ingest replayed under the same id would double-count
 *   its rows, and the fourth-observation refusal is not a substitute for noticing that.
 * @param measuringJvm the unit's own log's `# VM version:` banner line, verbatim. The
 *   single-JVM refusal compares these, so a paraphrase defeats it.
 * @param gate the load attestation taken immediately before the unit.
 * @param timestamp when the unit ran, ISO-8601. Recorded so a reader can see how far
 *   apart the units of one derivation actually were.
 */
data class UnitAttestation(
    val unitId: String,
    val measuringJvm: String,
    val gate: GateReading,
    val timestamp: String,
) {
    init {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        require(measuringJvm.isNotBlank()) {
            "measuringJvm must not be blank — it is the unit's own '# VM version:' " +
                "banner, and the single-JVM refusal has nothing to compare without it"
        }
        require(timestamp.isNotBlank()) { "timestamp must not be blank" }
    }
}

/** One row's relative dispersion as measured by one unit. */
data class RowObservation(
    val row: RowKey,
    val relativeDispersion: Double,
    val unitId: String,
) {
    init {
        require(relativeDispersion.isFinite() && relativeDispersion >= 0.0) {
            "relativeDispersion must be finite and non-negative, was $relativeDispersion"
        }
    }
}

/**
 * A benchmark class's full row universe, captured once when a derivation starts.
 *
 * @param benchmarkClass the class's SIMPLE name — the key `JmhRow.benchmarkClass` exposes
 *   and the key [EXPECTED_PLAN_ROW_COUNTS] is written in.
 * @param rows every row of the class. Must be distinct, and must number exactly what
 *   [EXPECTED_PLAN_ROW_COUNTS] pre-registers for the class.
 * @param enumerationProvenance how the row list was obtained, recorded verbatim — the
 *   command whose output it came from, so a later reader can tell an unfiltered
 *   enumeration from a filtered one without re-deriving it.
 * @param expectedRowCounts the tripwire table this plan is checked against. Defaults to
 *   [EXPECTED_PLAN_ROW_COUNTS]; the parameter exists for the same reason `noiseFloorFor`'s
 *   `floors` parameter does — so every branch is exercisable on synthetic classes without
 *   the live table being a test fixture.
 *
 * The count check lives in [init] rather than only in [of] deliberately: a check reachable
 * only through a factory is not a tripwire but a convention, and the primary constructor
 * and the generated `copy` are two ways past it. Every route that yields a
 * `DerivationPlan` — the constructor, `copy`, [of], and the plan [FloorDerivationLedger.load]
 * rebuilds — passes the same refusal, so no [FloorDerivationLedger] can exist over a row
 * universe nothing pre-registered.
 */
data class DerivationPlan(
    val benchmarkClass: String,
    val rows: List<RowKey>,
    val enumerationProvenance: String,
    val expectedRowCounts: Map<String, Int> = EXPECTED_PLAN_ROW_COUNTS,
) {
    init {
        require(benchmarkClass.isNotBlank()) { "benchmarkClass must not be blank" }
        require(enumerationProvenance.isNotBlank()) {
            "enumerationProvenance must not be blank — a plan that cannot say how it was " +
                "enumerated cannot be checked for having been enumerated under a filter"
        }
        val duplicated = rows.groupingBy { it }.eachCount().filterValues { it > 1 }
        require(duplicated.isEmpty()) {
            "a plan may not name one row twice, found " +
                duplicated.keys.map { it.describe() }.sorted()
        }
        val expected = expectedRowCounts[benchmarkClass] ?: throw FloorLedgerException(
            "no pre-registered row count exists for '$benchmarkClass'; the classes " +
                "with one are ${expectedRowCounts.keys.sorted()}. A derivation cannot " +
                "be checked for completeness against a universe nothing pre-registered"
        )
        if (rows.size != expected) {
            throw FloorLedgerException(
                "REFUSED: the enumerated plan for '$benchmarkClass' has ${rows.size} " +
                    "rows, but $expected are pre-registered. Either the enumeration " +
                    "ran under a row filter — in which case completeness would be " +
                    "satisfied on a universe too small, and the floor would be a " +
                    "maximum over a fraction of the class — or the class's methods or " +
                    "@Param enums changed, in which case EXPECTED_PLAN_ROW_COUNTS is " +
                    "what has to change first. Enumeration provenance: " +
                    enumerationProvenance
            )
        }
    }

    companion object {

        /**
         * [rows] as this class's plan, refused unless its size is the count
         * [expectedRowCounts] pre-registers.
         *
         * The named entry point, kept because it reads as a checked construction at the
         * call site; the check itself is the constructor's, so this is not the only way in.
         */
        fun of(
            benchmarkClass: String,
            rows: List<RowKey>,
            enumerationProvenance: String,
            expectedRowCounts: Map<String, Int> = EXPECTED_PLAN_ROW_COUNTS,
        ): DerivationPlan =
            DerivationPlan(benchmarkClass, rows, enumerationProvenance, expectedRowCounts)
    }
}

/**
 * A per-class floor derivation in progress, persisted to disk between measuring windows
 * (`computenet-3omz`).
 *
 * ## What this is for
 *
 * The pinned benchmark host is a laptop in daily interactive use, and the three
 * un-derived classes need roughly four hours of continuous gated measurement as
 * `ClassFloorDerivation` originally specified them. A procedure that only completes as
 * one uninterrupted stretch does not complete. This ledger lets a class's ROW SET be
 * split across several short quiesced windows: each window measures a subset of rows,
 * ingests its artifacts here, and the process may exit. Nothing already ingested is lost,
 * and the folded maximum is bit-identical to what the same observations would have
 * yielded measured all at once, because `max` is associative and commutative.
 *
 * ## What it refuses, and why each refusal is the safety property
 *
 * - **An incomplete row set.** [render] refuses until every planned row carries exactly
 *   [CLASS_FLOOR_OBSERVATIONS_PER_ROW] observations, and the refusal NAMES each
 *   outstanding row with its current count. A maximum over a subset can only be smaller
 *   than the maximum over the whole, so a floor rendered from a partial row set is
 *   systematically too LOW — the direction that admits rows the floor should have
 *   refused. This is the reason the ledger exists, not a nicety attached to it.
 * - **A plan that disagrees with [EXPECTED_PLAN_ROW_COUNTS].** Completeness over a
 *   filtered enumeration is vacuous; see that table.
 * - **More than one measuring JVM.** With units days apart the JDK on `PATH`, or the
 *   toolchain Gradle resolves, can change between them. That defect has already happened
 *   in compressed form: `computenet-ahn0`'s first derivation of `CellFootprintBenchmark`
 *   ran under JBR 25.0.2 rather than the toolchain's JDK 21, and re-deriving under 21
 *   moved the floor from 0.593 to 1.044. [render] refuses a row set spanning two banner
 *   strings, and [ingest] warns as soon as the second one arrives.
 * - **A fourth observation of a row.** Exactly three fold into the statistic; an ingest
 *   that would push a row past three is refused per row, so an operator who re-ran a unit
 *   with too wide a filter is told which rows to narrow rather than having them silently
 *   dropped or silently counted.
 * - **A half-parsed ledger file.** [load] refuses a file it cannot parse in full. A
 *   partially loaded ledger is an incomplete row set wearing a complete one's face.
 *
 * ## Persistence
 *
 * One human-readable, line-oriented file, [LEDGER_FILE_NAME], in a caller-supplied
 * directory — the ledger hard-codes no path, because a derivation's state belongs outside
 * the repository and the CLI is what decides where. The format is hand-rolled for the
 * reason `SeriesCsv` is: `:bench` depends on `:kernel` and `:testkit` only (`[BEN1-03]`),
 * and a checkpoint format is not worth a serialization dependency.
 */
class FloorDerivationLedger private constructor(
    val plan: DerivationPlan,
    private val directory: File,
    private val unitList: MutableList<UnitAttestation>,
    private val observationList: MutableList<RowObservation>,
) {

    /** The units ingested so far, in ingest order. */
    val units: List<UnitAttestation> get() = unitList.toList()

    /** Every observation ingested so far, in ingest order. */
    val observations: List<RowObservation> get() = observationList.toList()

    /** The file this ledger persists to. */
    val file: File get() = File(directory, LEDGER_FILE_NAME)

    /** How many observations each planned row carries, including the zeroes. */
    fun observationCounts(): Map<RowKey, Int> {
        val counts = observationList.groupingBy { it.row }.eachCount()
        return plan.rows.associateWith { counts[it] ?: 0 }
    }

    /** The planned rows not yet at [CLASS_FLOOR_OBSERVATIONS_PER_ROW], with their counts. */
    fun outstanding(): Map<RowKey, Int> =
        observationCounts().filterValues { it < CLASS_FLOOR_OBSERVATIONS_PER_ROW }

    /** Whether every planned row carries exactly [CLASS_FLOOR_OBSERVATIONS_PER_ROW]. */
    fun isComplete(): Boolean = outstanding().isEmpty()

    /** The distinct measuring-JVM banners across all ingested units, in ingest order. */
    fun measuringJvms(): List<String> = unitList.map { it.measuringJvm }.distinct()

    /**
     * Records one JMH invocation's rows against the plan and persists the result.
     *
     * @param unit the invocation's attestation — its banner, its gate reading, its time.
     * @param resultsCsv the invocation's `-rf csv` results file, parsed by
     *   `ThroughputReport.parseCsv`. Deliberately that parser and not a second one: it
     *   already locates columns by name, refuses an error column measured at a confidence
     *   other than 99.9%, and refuses a `NaN` error as too few samples rather than
     *   letting it through as a row with no dispersion.
     * @return warnings that do not refuse the ingest — today, the arrival of a second
     *   measuring JVM, which [render] will refuse but which is worth knowing about while
     *   there is still time to re-run the unit.
     * @throws FloorLedgerException if the unit id repeats, if the file carries a row from
     *   another class or a row the plan does not name, or if any row would exceed
     *   [CLASS_FLOOR_OBSERVATIONS_PER_ROW] observations.
     */
    fun ingest(unit: UnitAttestation, resultsCsv: String): List<String> {
        if (unitList.any { it.unitId == unit.unitId }) {
            throw FloorLedgerException(
                "unit '${unit.unitId}' has already been ingested into this derivation; a " +
                    "replayed ingest would double-count its rows"
            )
        }

        val rows = ThroughputReport.parseCsv(resultsCsv)

        val foreign = rows.map { it.benchmarkClass }.distinct()
            .filter { it != plan.benchmarkClass }
        if (foreign.isNotEmpty()) {
            throw FloorLedgerException(
                "unit '${unit.unitId}' carries rows from ${foreign.sorted()}, not only " +
                    "'${plan.benchmarkClass}'. A unit measures rows of the class being " +
                    "derived; a results file with foreign rows means the invocation's " +
                    "benchmark filter was wrong, and dropping them silently would hide that"
            )
        }

        val planned = plan.rows.toSet()
        val fresh = rows.map { row ->
            val key = RowKey.of(row.method, row.params)
            if (key !in planned) {
                throw FloorLedgerException(
                    "unit '${unit.unitId}' measured row ${key.describe()}, which " +
                        "'${plan.benchmarkClass}''s plan does not name. Either the plan " +
                        "was enumerated under a filter or the class changed since it was " +
                        "captured; either way the plan, not the ingest, is what is wrong"
                )
            }
            if (row.score == 0.0) {
                throw FloorLedgerException(
                    "unit '${unit.unitId}' reports a score of zero for ${key.describe()}, " +
                        "so its relative dispersion is not a number"
                )
            }
            RowObservation(key, abs(row.scoreError / row.score), unit.unitId)
        }

        val duplicatedInUnit = fresh.groupingBy { it.row }.eachCount().filterValues { it > 1 }
        if (duplicatedInUnit.isNotEmpty()) {
            throw FloorLedgerException(
                "unit '${unit.unitId}' carries ${duplicatedInUnit.entries.sortedBy { it.key.describe() }
                    .joinToString { "${it.key.describe()} x ${it.value}" }} — one " +
                    "invocation measures a row once, so this file is not one invocation's " +
                    "primary results"
            )
        }

        val existing = observationCounts()
        val overflowing = fresh.map { it.row }
            .filter { (existing[it] ?: 0) + 1 > CLASS_FLOOR_OBSERVATIONS_PER_ROW }
        if (overflowing.isNotEmpty()) {
            throw FloorLedgerException(
                "REFUSED: unit '${unit.unitId}' would take " +
                    overflowing.map { "${it.describe()} (already ${existing[it]})" }
                        .sorted() +
                    " past $CLASS_FLOOR_OBSERVATIONS_PER_ROW observations. Exactly " +
                    "$CLASS_FLOOR_OBSERVATIONS_PER_ROW fold into the statistic, and " +
                    "nothing is dropped to make room. Re-run this unit with a filter " +
                    "naming only the outstanding rows: " +
                    outstanding().keys.map { it.describe() }.sorted()
            )
        }

        val warnings = mutableListOf<String>()
        val known = measuringJvms()
        if (known.isNotEmpty() && unit.measuringJvm !in known) {
            warnings += "unit '${unit.unitId}' measured under '${unit.measuringJvm}', but " +
                "this derivation's earlier units measured under ${known.sorted()}. A row " +
                "set spanning two measuring JVMs will be REFUSED at render time — re-run " +
                "this unit under the JVM the others used, launched by absolute path."
        }

        unitList += unit
        observationList += fresh
        persist()
        return warnings
    }

    /**
     * The completed derivation as a [ClassNoiseFloor], or a refusal naming exactly what
     * stands in the way.
     *
     * The record's `observedMaxRelativeDispersion` is the maximum over every ingested
     * observation, which is bit-identical to the maximum a single whole-set computation
     * over the same numbers yields: `max` is associative and commutative, so no partition
     * of the row set into units can move it.
     *
     * @param derivedOn the ISO date to record. For a decomposed derivation this is a
     *   choice the operator makes and the findings entry must qualify — the units span
     *   days, and the record has one date field.
     */
    fun render(
        derivedOn: String,
        harnessCommitSha: String,
        jmhConfig: String,
    ): ClassNoiseFloor {
        val missing = outstanding()
        if (missing.isNotEmpty()) {
            throw FloorLedgerException(
                "REFUSED: '${plan.benchmarkClass}''s row set is incomplete — " +
                    "${missing.size} of ${plan.rows.size} rows are short of " +
                    "$CLASS_FLOOR_OBSERVATIONS_PER_ROW observations. A maximum over a " +
                    "subset can only be SMALLER than the maximum over the whole set, so a " +
                    "floor rendered now would be too low, and too low is the direction " +
                    "that admits rows the floor should refuse. Outstanding rows and their " +
                    "observation counts: " +
                    missing.entries
                        .map { "${it.key.describe()} = ${it.value}/$CLASS_FLOOR_OBSERVATIONS_PER_ROW" }
                        .sorted()
                        .joinToString("; ")
            )
        }

        val jvms = measuringJvms()
        if (jvms.size > 1) {
            throw FloorLedgerException(
                "REFUSED: '${plan.benchmarkClass}''s units span ${jvms.size} measuring " +
                    "JVMs — ${jvms.sorted()}. A floor is applied to rows measured under " +
                    "the toolchain JDK, and a derivation whose units ran under different " +
                    "runtimes describes neither. This is not hypothetical: computenet-ahn0 " +
                    "derived this same family of floors under JBR 25.0.2 and the number " +
                    "moved from 0.593 to 1.044 when re-derived under JDK 21. Re-run the " +
                    "units measured under the minority JVM"
            )
        }

        val observed = observationList.maxOf { it.relativeDispersion }
        return ClassNoiseFloor(
            benchmarkClass = plan.benchmarkClass,
            observedMaxRelativeDispersion = observed,
            runs = CLASS_FLOOR_OBSERVATIONS_PER_ROW,
            derivedOn = derivedOn,
            harnessCommitSha = harnessCommitSha,
            hostState = QUIESCED_HOST_STATE,
            jmhConfig = jmhConfig,
            measuringJvm = jvms.single(),
        )
    }

    /** A one-line progress summary, for a driver that reports between windows. */
    fun describeProgress(): String {
        val done = plan.rows.size - outstanding().size
        return "${plan.benchmarkClass}: $done/${plan.rows.size} rows complete at " +
            "$CLASS_FLOOR_OBSERVATIONS_PER_ROW observations, ${observationList.size} of " +
            "${plan.rows.size * CLASS_FLOOR_OBSERVATIONS_PER_ROW} observations ingested " +
            "over ${unitList.size} unit(s)"
    }

    private fun persist() {
        directory.mkdirs()
        val temporary = File(directory, "$LEDGER_FILE_NAME.tmp")
        temporary.writeText(renderLedger())
        // Rename rather than write in place: a window that dies mid-write must leave the
        // previous complete ledger, not a truncated one that `load` would then refuse.
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun renderLedger(): String = buildString {
        appendLine("$FORMAT_MARKER $FORMAT_VERSION")
        appendLine("class ${escapeField(plan.benchmarkClass)}")
        appendLine("provenance ${escapeField(plan.enumerationProvenance)}")
        plan.rows.forEach { appendLine("row ${it.encode()}") }
        unitList.forEach { unit ->
            appendLine(
                "unit " + listOf(
                    escapeField(unit.unitId),
                    escapeField(unit.timestamp),
                    unit.gate.oneMinuteLoad.toString(),
                    unit.gate.cores.toString(),
                    unit.gate.attestedThreshold.toString(),
                    escapeField(unit.measuringJvm),
                ).joinToString("|")
            )
        }
        observationList.forEach { observation ->
            appendLine(
                "obs " + listOf(
                    escapeField(observation.unitId),
                    observation.row.encode(),
                    // Double.toString round-trips exactly, so a reloaded ledger folds to
                    // the same maximum bit for bit.
                    observation.relativeDispersion.toString(),
                ).joinToString("|")
            )
        }
    }

    companion object {

        /** The ledger file's name within the caller's directory. */
        const val LEDGER_FILE_NAME: String = "ledger.txt"

        internal const val FORMAT_MARKER: String = "floor-derivation-ledger"
        internal const val FORMAT_VERSION: String = "v1"

        /**
         * Starts a derivation of [plan] in [directory], writing the plan immediately.
         *
         * Refuses over an existing ledger: silently replacing one would discard measured
         * observations, which is the one thing this file exists to prevent.
         */
        fun start(directory: File, plan: DerivationPlan): FloorDerivationLedger {
            val target = File(directory, LEDGER_FILE_NAME)
            if (target.exists()) {
                throw FloorLedgerException(
                    "a ledger already exists at ${target.absolutePath}; starting over " +
                        "would discard its observations. Load it, or move it aside " +
                        "deliberately"
                )
            }
            val ledger = FloorDerivationLedger(plan, directory, mutableListOf(), mutableListOf())
            ledger.persist()
            return ledger
        }

        /**
         * Reloads the derivation persisted in [directory].
         *
         * Parses the whole file or throws. There is no partial load: a ledger missing the
         * lines it could not parse is an incomplete row set that would pass for a
         * complete one, or a complete one short an observation nobody would look for.
         *
         * @param expectedRowCounts the tripwire table the reloaded plan is re-checked
         *   against, so a ledger written before the class changed is refused on load
         *   rather than rendered.
         */
        fun load(
            directory: File,
            expectedRowCounts: Map<String, Int> = EXPECTED_PLAN_ROW_COUNTS,
        ): FloorDerivationLedger {
            val target = File(directory, LEDGER_FILE_NAME)
            if (!target.isFile) {
                throw FloorLedgerException("no ledger at ${target.absolutePath}")
            }
            val lines = target.readText().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                throw FloorLedgerException("ledger ${target.absolutePath} is empty")
            }
            val header = lines.first()
            if (header != "$FORMAT_MARKER $FORMAT_VERSION") {
                throw FloorLedgerException(
                    "ledger ${target.absolutePath} does not start with " +
                        "'$FORMAT_MARKER $FORMAT_VERSION'; its first line is '$header'"
                )
            }

            var benchmarkClass: String? = null
            var provenance: String? = null
            val rows = mutableListOf<RowKey>()
            val units = mutableListOf<UnitAttestation>()
            val observations = mutableListOf<RowObservation>()

            lines.drop(1).forEachIndexed { offset, line ->
                val number = offset + 2
                val keyword = line.substringBefore(' ')
                val body = line.substringAfter(' ', missingDelimiterValue = "")
                fun malformed(why: String): Nothing = throw FloorLedgerException(
                    "ledger ${target.absolutePath} line $number is malformed ($why): $line"
                )
                // A field-level refusal — a dangling escape, a parameter segment with no
                // '=' — is the shape a write cut off mid-line leaves. It is re-thrown
                // with the file and line rather than propagating bare, so a truncated
                // ledger reads as a malformed FILE and not as a puzzling row.
                try {
                when (keyword) {
                    "class" -> {
                        if (benchmarkClass != null) malformed("a second 'class' line")
                        benchmarkClass = unescapeField(body, line)
                    }

                    "provenance" -> {
                        if (provenance != null) malformed("a second 'provenance' line")
                        provenance = unescapeField(body, line)
                    }

                    "row" -> rows += RowKey.decode(body)

                    "unit" -> {
                        val fields = body.split('|')
                        if (fields.size != 6) malformed("${fields.size} fields, expected 6")
                        val load = fields[2].toDoubleOrNull() ?: malformed("load '${fields[2]}'")
                        val cores = fields[3].toIntOrNull() ?: malformed("cores '${fields[3]}'")
                        val threshold = fields[4].toDoubleOrNull()
                            ?: malformed("threshold '${fields[4]}'")
                        units += UnitAttestation(
                            unitId = unescapeField(fields[0], line),
                            timestamp = unescapeField(fields[1], line),
                            gate = GateReading(load, cores, threshold),
                            measuringJvm = unescapeField(fields[5], line),
                        )
                    }

                    "obs" -> {
                        val fields = body.split('|')
                        if (fields.size != 3) malformed("${fields.size} fields, expected 3")
                        val dispersion = fields[2].toDoubleOrNull()
                            ?: malformed("dispersion '${fields[2]}'")
                        observations += RowObservation(
                            row = RowKey.decode(fields[1]),
                            relativeDispersion = dispersion,
                            unitId = unescapeField(fields[0], line),
                        )
                    }

                    else -> malformed("unknown keyword '$keyword'")
                }
                } catch (refusal: FloorLedgerException) {
                    if (refusal.message?.startsWith("ledger ${target.absolutePath} line ") == true) {
                        throw refusal
                    }
                    malformed(refusal.message ?: "unparseable")
                }
            }

            val resolvedClass = benchmarkClass
                ?: throw FloorLedgerException("ledger ${target.absolutePath} has no 'class' line")
            val resolvedProvenance = provenance
                ?: throw FloorLedgerException(
                    "ledger ${target.absolutePath} has no 'provenance' line"
                )
            val plan = DerivationPlan.of(
                benchmarkClass = resolvedClass,
                rows = rows,
                enumerationProvenance = resolvedProvenance,
                expectedRowCounts = expectedRowCounts,
            )

            val declared = units.map { it.unitId }.toSet()
            if (declared.size != units.size) {
                throw FloorLedgerException(
                    "ledger ${target.absolutePath} declares a unit id twice"
                )
            }
            val orphaned = observations.map { it.unitId }.distinct().filter { it !in declared }
            if (orphaned.isNotEmpty()) {
                throw FloorLedgerException(
                    "ledger ${target.absolutePath} carries observations from unit(s) " +
                        "${orphaned.sorted()} it declares no attestation for"
                )
            }
            val planned = plan.rows.toSet()
            val unplanned = observations.map { it.row }.distinct().filter { it !in planned }
            if (unplanned.isNotEmpty()) {
                throw FloorLedgerException(
                    "ledger ${target.absolutePath} carries observations of row(s) " +
                        "${unplanned.map { it.describe() }.sorted()} its plan does not name"
                )
            }
            val over = observations.groupingBy { it.row }.eachCount()
                .filterValues { it > CLASS_FLOOR_OBSERVATIONS_PER_ROW }
            if (over.isNotEmpty()) {
                throw FloorLedgerException(
                    "ledger ${target.absolutePath} carries more than " +
                        "$CLASS_FLOOR_OBSERVATIONS_PER_ROW observations of " +
                        over.keys.map { it.describe() }.sorted()
                )
            }

            return FloorDerivationLedger(plan, directory, units, observations)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Field escaping. The ledger is line-oriented and delimiter-separated, so every field that
// could contain a delimiter — a JVM banner has spaces, a provenance line has anything —
// is escaped on the way in. An UNKNOWN escape sequence is a parse refusal rather than a
// character passed through: a file this parser half-understands is exactly the "complete
// row set wearing a complete one's face" case that `load` must not produce.
// ---------------------------------------------------------------------------------------

private fun escapeField(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '|' -> append("\\p")
            ';' -> append("\\s")
            '=' -> append("\\q")
            ' ' -> append("\\_")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
}

private fun unescapeField(value: String, context: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character != '\\') {
            append(character)
            index++
            continue
        }
        if (index + 1 >= value.length) {
            throw FloorLedgerException(
                "ledger field '$value' ends in a dangling escape, in: $context"
            )
        }
        when (val escaped = value[index + 1]) {
            '\\' -> append('\\')
            'p' -> append('|')
            's' -> append(';')
            'q' -> append('=')
            '_' -> append(' ')
            'n' -> append('\n')
            'r' -> append('\r')
            else -> throw FloorLedgerException(
                "ledger field '$value' carries an unknown escape '\\$escaped', in: $context"
            )
        }
        index += 2
    }
}
