package civictech.testkit.dst

import civictech.cell.durability.Journal
import civictech.cell.host.SimulationController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Restart a host from an **arbitrary journal prefix**, optionally with its processed-frontier
 * rolled back independently of that prefix ([CHA1-21], [CHA1-22]).
 *
 * ## Why one fault class for two knobs
 *
 * [prefix] and [keepFrontierAdvances] are orthogonal axes of the same event — the restart — and
 * [CHA1-22] asks for the frontier rollback to be applied *independently of the prefix*. Two
 * fault classes would have had to crash the host twice to combine them, which is a third thing
 * neither axis was asking about. So one class, two nullable knobs, and [describe] says which
 * were in force.
 *
 *  - `prefix = null` recovers from the whole log; `prefix = k` recovers from records `[0, k)`
 *    for any `k in 0..R` ([CHA1-21]). `k = 0` is the empty-journal restart, `k = R` the
 *    ordinary one, and every value between is a crash landing mid-log.
 *  - `keepFrontierAdvances = null` leaves the frontier alone; `= n` keeps the first `n`
 *    `RECORD_FRONTIER` records and drops the rest, rolling the processed-frontier back to where
 *    it stood after those `n` advances ([CHA1-22]).
 *
 * ## What the rollback knob can and cannot express — read [FrontierRollbackJournal]
 *
 * The rollback point is chosen by **counting frontier advances**, not by naming a
 * `(sourceId, counter)`: `FrontierRecord` is private to `HostDurability` and its payload is
 * undecodable from `:testkit`, so only the record's tag byte is readable. That is the reported
 * structural limit of [CHA1-22] from outside the kernel, stated in full on
 * [FrontierRollbackJournal], and it is why BS-11 reports what the code does rather than
 * rendering a verdict.
 *
 * ## The restart itself is the consumer's rebuild function
 *
 * `HostSlot.crash` discards the scheduler and re-runs the graph builder's own build lambda, so
 * what comes back after the restart is whatever that lambda builds — the rig cannot re-derive an
 * arbitrary graph's `CellRef`s (the seam's documented limitation). This fault contributes the
 * two things the rig *can* own: the crash, and which slice of the journal the rebuilt host is
 * then recovered from.
 *
 * Recovery runs immediately after the rebuild, inside [onStep], so it is synchronous with the
 * step it was scheduled for and every replayed frame enters the intake before the step runs.
 */
data class RestartAtFrontierFault(
    override val id: String,
    val host: String,
    val journal: String,
    val atStep: Int,
    val prefix: Int? = null,
    val keepFrontierAdvances: Int? = null,
) : Fault {

    init {
        require(atStep >= 0) { "a restart fires at a controller step index; got atStep=$atStep" }
        require(prefix == null || prefix >= 0) { "a journal prefix is a record count; got prefix=$prefix" }
        require(keepFrontierAdvances == null || keepFrontierAdvances >= 0) {
            "keepFrontierAdvances counts frontier advances to retain; got $keepFrontierAdvances"
        }
    }

    /**
     * What the restart's recovery did ([CHA1-20]). Per-run state, null until [atStep] is
     * reached — a fault value is immutable configuration and this is the one thing the run
     * writes back onto it, which is why a plan must not reuse a fault instance across runs.
     */
    var lastRecovery: RecoveryAttempt? = null
        private set

    /** How many records the log held at the restart, before any prefix was taken. */
    var recordsAtRestart: Int? = null
        private set

    override val targets: List<FaultTarget>
        get() = listOf(FaultTarget.Host(host), FaultTarget.Journal(journal))

    override fun describe(): String = buildString {
        append("restart(host=$host, journal=$journal, at step $atStep")
        append(", prefix=${prefix?.let { "$it record(s)" } ?: "whole log"}")
        if (keepFrontierAdvances != null) append(", frontier rolled back to $keepFrontierAdvances advance(s)")
        append(")")
        lastRecovery?.let { append(": ${it.traceTag()}") }
    }

    override fun onStep(world: DstWorld, step: Int) {
        if (step != atStep) return

        // The record count comes off the UNDECORATED log, so `recordsAtRestart` reports what the
        // host actually wrote; recovery reads the DECORATED view, so a JournalFault in the same
        // plan composes with this restart instead of being bypassed by it. Measured: taking the
        // base for both made a `truncateTail` fault in the same plan a silent no-op, since the
        // mutation lives on the view and nothing else ever calls `replay`.
        recordsAtRestart = world.journals.base(journal).replay().size

        val rebuilt = world.hosts.require(host).crash(id)
        val view = recoveryView(world.journals.view(journal))
        val attempt = JournalRecovery.attempt(rebuilt, view)
        lastRecovery = attempt

        // [CHA1-20]: the RecoveryIncomplete's recordIndex and total reach DstReport through the
        // trace, which the report carries verbatim. No new report field, and no swallowing: a
        // consumer's check reads `lastRecovery` and rethrows the exception if it wants the
        // failure in `failingCheck.error` as well.
        world.trace.fault(id, host = host, port = attempt.traceTag())
    }

    /**
     * The journal slice this restart recovers from: the prefix applied first, the frontier
     * rollback applied to what the prefix left. Composed in that order so the two knobs are
     * independent — rolling back to `n` advances means `n` of the advances *the prefix
     * retained*, which is the only reading under which `prefix = 0` and a rollback do not
     * contradict each other.
     *
     * [source] is the graph's decorated view, so any [JournalFault] in the same plan has already
     * rewritten the record list these two views then slice. `prefix = k` is therefore `k` records
     * of the *mutated* log — the composition a plan holding both faults asked for, and the reason
     * a prefix sweep runs with no journal fault in its plan.
     */
    private fun recoveryView(source: Journal): Journal {
        val prefixed = prefix?.let { PrefixJournal(source, it) } ?: source
        return keepFrontierAdvances?.let { FrontierRollbackJournal(prefixed, it) } ?: prefixed
    }

    companion object {

        /** The `kind` a [RestartAtFrontierFault] is written under. A published name. */
        const val KIND: String = "dst-restart-at-frontier"

        /**
         * This class's [FaultCodec], registered when the class is loaded — see
         * [CrashFault.CODEC] for why the companion object is the registration point.
         *
         * Only the five constructor parameters are written, which is the whole configuration:
         * [lastRecovery] and [recordsAtRestart] are per-*run* observations written back onto
         * the instance, not configuration, and an artifact that carried them would be claiming
         * a restart's result as part of the plan that produced it. The report and the trace
         * carry that result already — see [PrefixRestartEntry.recoveryTag], which reads it off
         * the trace precisely because a decoded fault has no `lastRecovery`.
         *
         * `prefix` and `keepFrontierAdvances` are nullable and are written as JSON `null` when
         * absent rather than omitted, so an artifact says out loud that the knob was not in
         * force. Both are also the two numeric knobs a shrinker can walk toward zero, which is
         * why they are top-level primitives — see [PartitionFault.CODEC].
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is RestartAtFrontierFault },
            encode = { fault ->
                val restart = fault as RestartAtFrontierFault
                buildJsonObject {
                    put("host", restart.host)
                    put("journal", restart.journal)
                    put("atStep", restart.atStep)
                    put("prefix", JsonPrimitive(restart.prefix))
                    put("keepFrontierAdvances", JsonPrimitive(restart.keepFrontierAdvances))
                }
            },
            decode = { id, params -> decodeFrom(id, params) },
        )

        private fun decodeFrom(id: String, params: JsonObject): RestartAtFrontierFault =
            RestartAtFrontierFault(
                id = id,
                host = params.getValue("host").jsonPrimitive.content,
                journal = params.getValue("journal").jsonPrimitive.content,
                atStep = params.getValue("atStep").jsonPrimitive.int,
                prefix = params["prefix"]?.jsonPrimitive?.intOrNull,
                keepFrontierAdvances = params["keepFrontierAdvances"]?.jsonPrimitive?.intOrNull,
            )

        /** Restart [host] from the first [k] records of [journal] ([CHA1-21]). */
        fun atPrefix(id: String, host: String, journal: String, atStep: Int, k: Int) =
            RestartAtFrontierFault(id, host, journal, atStep, prefix = k)

        /** Restart [host] from the whole log with the frontier rolled back ([CHA1-22]). */
        fun withFrontierRolledBack(
            id: String,
            host: String,
            journal: String,
            atStep: Int,
            keepFrontierAdvances: Int,
        ) = RestartAtFrontierFault(id, host, journal, atStep, keepFrontierAdvances = keepFrontierAdvances)
    }
}

/**
 * One prefix `k`'s result inside a [PrefixRestartSweepReport].
 *
 * [error] and a failing [report] are kept apart for the same reason [SweepEntry] keeps them
 * apart: a run that could not be *executed* (a broken graph, an unknown target, an exception out
 * of `install`) is a broken experiment, while a run that executed and failed its check is a
 * finding. Both count as failures; only one of them is about the property.
 */
data class PrefixRestartEntry(
    val k: Int,
    val report: DstReport?,
    val error: Throwable?,
) {
    /**
     * The `recovery-complete@n` / `recovery-incomplete@i/R` tag the restart traced, if it fired.
     *
     * Read off the **trace** rather than off the fault instance, deliberately: the trace is what
     * `DstReport` carries and what an artifact records, so a `RecoveryIncomplete`'s record index
     * and total survive into the report without a new report field ([CHA1-20]). The
     * `RecoveryIncomplete` object itself is reachable only from
     * [RestartAtFrontierFault.lastRecovery], on the live fault instance, which an artifact read
     * back from disk does not have.
     */
    val recoveryTag: String?
        get() = report?.trace?.lastOrNull { it.faultTag != null && it.port?.startsWith("recovery-") == true }?.port

    val failed: Boolean get() = error != null || report?.outcome == DstOutcome.FAILED

    val exhausted: Boolean get() = report?.outcome == DstOutcome.BUDGET_EXHAUSTED

    val message: String
        get() = error?.message ?: report?.failingCheck?.message ?: report?.outcome?.name ?: "no report"

    val cause: Throwable? get() = error ?: report?.failingCheck?.error
}

/**
 * What a restart sweep over `k in 0..records` found ([CHA1-21], BS-10).
 *
 * **Every `k` in the range has an entry**, enforced in `init` for the same reason
 * [DstSweepReport] enforces its seed range: a sweep that skipped a prefix cannot produce a
 * report at all, so "restart from *any* prefix" cannot be quietly narrowed to the prefixes that
 * happened to pass.
 *
 * [failingPrefixes] is the number BS-10 exists to produce, and it is recorded here — the failing
 * `k` — because that is where a consumer reads it. Each failing `k` also has its own
 * [DstReport], whose `appliedFaults[].description` names the prefix ([RestartAtFrontierFault.describe]),
 * so the `k` survives into any artifact written from that report without this type or
 * `DstArtifact` needing a field for it.
 */
data class PrefixRestartSweepReport(
    val graphId: String,
    val host: String,
    val journal: String,
    val seed: Long,
    val records: Int,
    val entries: List<PrefixRestartEntry>,
) {
    init {
        val executed = entries.map { it.k }
        require(executed == (0..records).toList()) {
            "[CHA1-21]: a restart sweep must cover every prefix in 0..$records, in order; " +
                "got ${executed.size} entries " +
                (if (executed.isEmpty()) "(none)" else "spanning ${executed.first()}..${executed.last()}")
        }
    }

    val total: Int get() = entries.size

    val failures: List<PrefixRestartEntry> get() = entries.filter { it.failed }

    val exhausted: List<PrefixRestartEntry> get() = entries.filter { it.exhausted }

    /** The `k`s whose run failed its check or could not be executed — BS-10's finding. */
    val failingPrefixes: List<Int> get() = failures.map { it.k }

    /** The `k`s whose recovery aborted partway, with the tag naming record index and total. */
    val incompleteRecoveries: Map<Int, String>
        get() = entries.mapNotNull { e ->
            e.recoveryTag?.takeIf { it.startsWith("recovery-incomplete") }?.let { e.k to it }
        }.toMap()

    fun summary(): String = buildString {
        append("DST prefix-restart sweep graph=$graphId host=$host journal=$journal seed=$seed ")
        append("prefixes=0..$records (executed $total); failed on ${failures.size} of $total")
        if (failingPrefixes.isNotEmpty()) append("; failing k=${failingPrefixes}")
        if (incompleteRecoveries.isNotEmpty()) append("; incomplete recoveries=${incompleteRecoveries}")
        if (exhausted.isNotEmpty()) append("; budget exhausted on ${exhausted.size} (no verdict claimed)")
    }

    /**
     * Fail naming the first bad prefix, with its own throwable as the cause so an IDE's
     * jump-to-failure lands on the real assertion — [DstSweepReport.assertAllPassed]'s shape,
     * over prefixes instead of seeds. A `BUDGET_EXHAUSTED` prefix fails the sweep too: it
     * disproved nothing, and must not read as a pass.
     */
    fun assertAllPassed() {
        val bad = entries.filter { it.failed || it.exhausted }
        if (bad.isEmpty()) return
        val first = bad.first()
        throw AssertionError(
            "failed on ${bad.size} of $total prefixes; first: k=${first.k} — ${first.message} [${summary()}]",
            first.cause,
        )
    }
}

/**
 * Restart the same graph from **every** journal prefix `k in 0..records` and collect the lot
 * ([CHA1-21], BS-10).
 *
 * Every `k` runs regardless of earlier failures, for [DstSweepReport]'s reason: the number this
 * produces is *which* prefixes fail, and "k=7 only" and "every k above 3" are different
 * findings that a fail-fast loop cannot tell apart.
 *
 * @param records how many records the log holds — see [journalRecordCount], which drives the
 *   same graph on the same seed with no faults to measure it. Passed in rather than measured
 *   here so a caller can sweep a deliberately wider or narrower range and have the report say
 *   so.
 * @param atStep the step the restart fires at. It must be a step the run actually reaches, and
 *   one by which the journal already holds its records — a restart scheduled after the graph
 *   quiesces never fires, and the report marks the fault inert ([CHA1-24]).
 * @param keepFrontierAdvances applied at every `k` if non-null, which is how [CHA1-22]'s
 *   independence claim is exercised across the whole prefix range.
 */
fun prefixRestartSweep(
    graph: GraphSpec,
    seed: Long,
    host: String,
    journal: String,
    records: Int,
    atStep: Int,
    budget: Int = SimulationController.DEFAULT_BUDGET,
    check: DstCheck = DstCheck.none,
    keepFrontierAdvances: Int? = null,
): PrefixRestartSweepReport {
    require(records >= 0) { "a journal record count cannot be negative; got $records" }

    val entries = (0..records).map { k ->
        val fault = RestartAtFrontierFault(
            id = "restart-at-$k",
            host = host,
            journal = journal,
            atStep = atStep,
            prefix = k,
            keepFrontierAdvances = keepFrontierAdvances,
        )
        val run = DstRun(graph, FaultPlan.of(seed, fault), budget, check)
        val report = runCatching { run.execute() }
        PrefixRestartEntry(k, report.getOrNull(), report.exceptionOrNull())
    }

    return PrefixRestartSweepReport(graph.id, host, journal, seed, records, entries)
}

/**
 * How many records [journal] holds after driving [graph] on [seed] with no faults — the `R` a
 * [prefixRestartSweep] walks `0..R`.
 *
 * A separate fault-free drive rather than a field on [DstReport]: the record count is a property
 * of the *graph and seed*, the sweep needs it before its first run, and measuring it inside a
 * run would make the sweep's range depend on a run that the sweep is itself perturbing.
 *
 * @return the record count, and the tag histogram — which is what tells a caller whether the log
 *   it is about to sweep contains any frontier advances to roll back at all.
 */
fun journalRecordCount(
    graph: GraphSpec,
    seed: Long,
    journal: String,
    budget: Int = SimulationController.DEFAULT_BUDGET,
): JournalCensus {
    val world = DstWorld(seed)
    graph.builder.build(world)
    var steps = 0
    while (steps < budget) {
        world.beginStep(steps)
        if (!world.controller.step()) break
        steps++
    }
    world.endRun()
    val records = world.journals.base(journal).replay()
    return JournalCensus(records.size, JournalRecords.tagHistogram(records), steps)
}

/**
 * What a fault-free drive left in one journal: how many records, of which tags, after how many
 * steps. [frontierAdvances] is the one a [CHA1-22] rollback needs — a log with none has no
 * frontier to roll back, and a test asserting a rollback against it would be vacuous.
 */
data class JournalCensus(
    val records: Int,
    val tags: Map<Byte, Int>,
    val steps: Int,
) {
    val frameRecords: Int get() = tags[JournalRecords.FRAME] ?: 0

    val frontierAdvances: Int get() = tags[JournalRecords.FRONTIER] ?: 0

    val checkpoints: Int get() = tags[JournalRecords.CHECKPOINT] ?: 0

    /** True when every tag observed is one the kernel is known to write — see [JournalRecords]. */
    val allTagsKnown: Boolean get() = tags.keys.all { it in JournalRecords.KNOWN }

    override fun toString(): String =
        "JournalCensus($records records in $steps steps: frames=$frameRecords, " +
            "frontier=$frontierAdvances, checkpoints=$checkpoints, tags=$tags)"
}
