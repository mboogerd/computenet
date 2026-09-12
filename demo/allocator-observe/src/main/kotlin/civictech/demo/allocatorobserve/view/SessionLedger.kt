package civictech.demo.allocatorobserve.view

import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.demo.allocatorobserve.SpendRecord
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.TreeMap

/** Nanoseconds per hour, the one conversion factor every hours-arithmetic read below uses. */
private const val NANOS_PER_HOUR: Double = 3.6e12

/** Why [sessionOf] could not turn a [SpendRecord] into a [SpendSession] (fpml.3-D6). */
enum class UnattributableReason {
    UNPARSEABLE_STARTED,
    UNPARSEABLE_ENDED,
    ENDED_BEFORE_STARTED,
}

/** The outcome of [sessionOf]: a total function over every possible [SpendRecord]. */
sealed interface SessionParse {
    data class Valid(val session: SpendSession) : SessionParse

    data class Unattributable(val reason: UnattributableReason) : SessionParse
}

/**
 * A [SpendRecord] with its `started`/`ended` strings parsed to instants
 * (fpml.3-D6). Carries the source [record] so a live-but-unattributable
 * record can be recovered for [SessionLedger.unattributable] and so removal
 * can find its way back to the exact bucket it was filed under.
 */
data class SpendSession(
    val project: String,
    val started: Instant,
    val ended: Instant,
    val record: SpendRecord,
)

/**
 * Parses [record] into a [SpendSession], total over every [SpendRecord]
 * (fpml.3-D6). Only [DateTimeParseException] from [Instant.parse] is caught —
 * any other exception is a bug, not an unattributable record. `ended ==
 * started` is a valid zero-length session.
 */
fun sessionOf(record: SpendRecord): SessionParse {
    val started =
        try {
            Instant.parse(record.started)
        } catch (e: DateTimeParseException) {
            return SessionParse.Unattributable(UnattributableReason.UNPARSEABLE_STARTED)
        }
    val ended =
        try {
            Instant.parse(record.ended)
        } catch (e: DateTimeParseException) {
            return SessionParse.Unattributable(UnattributableReason.UNPARSEABLE_ENDED)
        }
    if (ended.isBefore(started)) {
        return SessionParse.Unattributable(UnattributableReason.ENDED_BEFORE_STARTED)
    }
    return SessionParse.Valid(SpendSession(record.project, started, ended, record))
}

/**
 * A consumer-side incremental fold over `SetDelta<SpendRecord>`
 * (`civictech.cell.data.delta.SetDelta`, F1's `SpendLogIngester.records`
 * outlet shape) into per-project session hours, owning the feature's rule 1
 * at the ledger level and the "computed from deltas, no full re-fold on each
 * record" half of rule 3 (`computenet-fpml.3` acceptance criteria).
 *
 * ## The membership fold
 *
 * [apply] folds add-tags and del-tags per record exactly as
 * `demo/beadsmirror/.../ready/ReadySetCell`'s `edgeAdds`/`edgeDels`/
 * `isEdgeLive` do for edges (copied by example, not by import — this module
 * does not depend on `:demo:beadsmirror`, fpml.1-D3): a record is live iff it
 * has an add-tag not covered by a del (`SetDelta`'s own KDoc, spec 20/21
 * effective-only semantics). Only a **membership flip** — absent to live, or
 * live to absent — inserts or removes the session: a second add-tag on an
 * already-live record, or a del carrying tags this ledger never observed,
 * changes the tag bookkeeping but touches no index and inserts or removes
 * nothing.
 *
 * ## Indexing, and why it is keyed by `ended`
 *
 * Live sessions are indexed per project by
 * `TreeMap<Instant /* ended */, MutableList<SpendSession>>`, sessions sharing
 * an `ended` instant sharing a bucket. Every read below asks "hours in
 * `[from, to)`", and the only sessions that can possibly overlap such a range
 * are those with `ended > from` — `tailMap(from, false)` finds exactly that
 * prefix of the index, then each candidate is checked against `started < to`.
 * A read therefore costs O(sessions ending after `from`), i.e. O(window),
 * **never** O(all records) — the property [sessionsVisited] instruments and
 * the acceptance criteria pin by count.
 *
 * Live records that parsed to [SessionParse.Unattributable] are tracked
 * separately in [unattributable]: a function of *current membership*, so a
 * re-baseline that removes such a record removes it from this set too — see
 * that property's KDoc for why this is deliberately unlike
 * `SpendIngestFailures`'s process-lifetime counters.
 *
 * ## Hours arithmetic
 *
 * Hours are represented as `Double`, computed as
 * `Duration.between(a, b).toNanos() / 3.6e12` in every read — [hoursBetween]
 * and nowhere else — so the arithmetic is order-independent up to floating
 * point.
 *
 * ## Threading
 *
 * Not thread-safe: a single writer, exactly like `MapView`. Publication for
 * concurrent readers is task 3's concern (fpml.3-D5's snapshot/publish
 * idiom), not this class's.
 */
class SessionLedger {

    /** Add-tags seen per record, mirroring `ReadySetCell.edgeAdds`. */
    private val recordAdds = mutableMapOf<SpendRecord, MutableSet<Timestamp>>()

    /** Tombstoned tags per record, mirroring `ReadySetCell.edgeDels`. */
    private val recordDels = mutableMapOf<SpendRecord, MutableSet<Timestamp>>()

    /** Live sessions per project, keyed by `ended` — see the class KDoc. */
    private val byProject = mutableMapOf<String, TreeMap<Instant, MutableList<SpendSession>>>()

    /** Live session count per project — what makes [projects] an index read. */
    private val liveCountByProject = mutableMapOf<String, Int>()

    /** The live session a record currently contributes, if it parsed as [SessionParse.Valid]. */
    private val liveSessionByRecord = mutableMapOf<SpendRecord, SpendSession>()

    /** The reason a live record parsed as [SessionParse.Unattributable]. */
    private val liveUnattributableByRecord = mutableMapOf<SpendRecord, UnattributableReason>()

    /**
     * Per-session examinations by [hoursBetween], since this ledger was
     * built — the count-based incrementality instrument (AGENTS.md forbids
     * timing-based assertions; `ReadySetCell.evaluationCount` is the
     * precedent). [apply] never touches this: folding a delta is index
     * maintenance, not a read, and does not visit any session.
     */
    var sessionsVisited: Long = 0L
        private set

    private fun isLive(record: SpendRecord): Boolean {
        val adds = recordAdds[record] ?: return false
        val covered = recordDels[record] ?: return adds.isNotEmpty()
        return adds.any { it !in covered }
    }

    /**
     * Folds one [SetDelta] of spend records in. Tag bookkeeping is updated
     * for every add/del the delta carries; a record's session is inserted or
     * removed only when that record's liveness actually flips — see the
     * class KDoc.
     */
    fun apply(delta: SetDelta<SpendRecord>) {
        val touched = LinkedHashSet<SpendRecord>(delta.adds.keys)
        touched += delta.dels.keys
        if (touched.isEmpty()) return

        val wasLive = touched.associateWith(::isLive)

        delta.adds.forEach { (record, tags) -> recordAdds.getOrPut(record) { LinkedHashSet() } += tags }
        delta.dels.forEach { (record, tags) -> recordDels.getOrPut(record) { LinkedHashSet() } += tags }

        touched.forEach { record ->
            val nowLive = isLive(record)
            if (nowLive == wasLive.getValue(record)) return@forEach
            if (nowLive) insert(record) else remove(record)
        }
    }

    private fun insert(record: SpendRecord) {
        when (val parse = sessionOf(record)) {
            is SessionParse.Valid -> {
                val session = parse.session
                byProject
                    .getOrPut(session.project) { TreeMap() }
                    .getOrPut(session.ended) { mutableListOf() } += session
                liveSessionByRecord[record] = session
                liveCountByProject[session.project] = (liveCountByProject[session.project] ?: 0) + 1
            }

            is SessionParse.Unattributable -> liveUnattributableByRecord[record] = parse.reason
        }
    }

    private fun remove(record: SpendRecord) {
        liveSessionByRecord.remove(record)?.let { session ->
            val buckets = byProject[session.project]
            val bucket = buckets?.get(session.ended)
            bucket?.remove(session)
            if (bucket != null && bucket.isEmpty()) buckets.remove(session.ended)
            if (buckets != null && buckets.isEmpty()) byProject.remove(session.project)

            val remaining = (liveCountByProject[session.project] ?: 1) - 1
            if (remaining <= 0) liveCountByProject.remove(session.project) else liveCountByProject[session.project] = remaining
        }
        liveUnattributableByRecord.remove(record)
    }

    /**
     * Sum, in hours, of overlap-attributed session time for [project] over
     * `[from, to)` (fpml.3-D2 generalized to every boundary, not only the
     * feature's window/month ones): each live session contributes
     * `overlap([max(started,from), min(ended,to)))` converted to hours. An
     * empty or unknown project yields `0.0`. Requires `from <= to`.
     *
     * Visits only sessions ending after `from` ([TreeMap.tailMap]) — see the
     * class KDoc's Indexing section — incrementing [sessionsVisited] once per
     * session examined, whether or not it actually overlaps `[from, to)`.
     */
    fun hoursBetween(project: String, from: Instant, to: Instant): Double {
        require(!from.isAfter(to)) { "hoursBetween requires from <= to, got from=$from to=$to" }
        val buckets = byProject[project] ?: return 0.0

        var totalNanos = 0.0
        for (sessions in buckets.tailMap(from, false).values) {
            for (session in sessions) {
                sessionsVisited++
                if (session.started.isBefore(to)) {
                    val overlapStart = if (session.started.isAfter(from)) session.started else from
                    val overlapEnd = if (session.ended.isBefore(to)) session.ended else to
                    if (overlapEnd.isAfter(overlapStart)) {
                        totalNanos += Duration.between(overlapStart, overlapEnd).toNanos().toDouble()
                    }
                }
            }
        }
        return totalNanos / NANOS_PER_HOUR
    }

    /** Projects with at least one live session. */
    fun projects(): Set<String> = liveCountByProject.keys.toSet()

    /**
     * The LIVE unattributable records — a function of current membership, not
     * a process-lifetime counter like `SpendIngestFailures` (contrast that
     * class's KDoc): a re-baseline that removes an unparseable record removes
     * it from here too. Contributes 0h to every [hoursBetween] read.
     */
    val unattributable: Set<SpendRecord> get() = liveUnattributableByRecord.keys.toSet()

    /** [unattributable], grouped by [UnattributableReason]. */
    fun unattributableByReason(): Map<UnattributableReason, Long> =
        liveUnattributableByRecord.values
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value.toLong() }
}
