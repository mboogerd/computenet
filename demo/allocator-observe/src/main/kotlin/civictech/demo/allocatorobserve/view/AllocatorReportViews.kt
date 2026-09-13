package civictech.demo.allocatorobserve.view

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.port.streamTo
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The immutable R5/R6 snapshot a reader observes — everything
 * [AllocatorReportViews.publish] computed from one reading of the injected
 * clock. Every field is a value or an immutable container built during that
 * pass; nothing here aliases the fold.
 *
 * @param publishedAt the single clock reading the whole report was derived at
 *   (fpml.3-D7) — also the window's exclusive upper bound and [cap]'s `now`.
 * @param unattributable live unattributable record counts by reason
 *   (fpml.3-D6), so a reader can see that records were excluded and why.
 * @param unattributableRecords the live unattributable records themselves.
 *   These are the records that could not be parsed into sessions — never
 *   fabricated draw-exclusion records (fpml.3-D4).
 */
data class AllocatorReport(
    val publishedAt: Instant,
    val window: WindowReport,
    val cap: CapReport,
    val unattributable: Map<UnattributableReason, Long>,
    val unattributableRecords: Set<SpendRecord>,
)

/**
 * The assembly of feature `computenet-fpml.3`: a derived cell over F1's spend-record
 * [SetCell] and F2's declaration-history [SetCell] that folds both delta streams
 * privately and publishes ONE immutable [AllocatorReport] at an explicit
 * caller-supplied boundary.
 *
 * ## Why a caller-supplied boundary rather than a wave (fpml.3-D5)
 *
 * Feature rule 3 is that no reader ever observes a total mixing a half-applied
 * batch of records. The kernel's wave machinery cannot supply that here:
 * `SetCell`'s `SetOps` inlet propagates one `SetDelta` per element, and
 * `SpendLogIngester.fold` loops one add per record, so a poll's batch of N
 * records is N waves, not one. `GlitchFreeCell` groups a wave; it does not
 * combine several. F3 may not change ingest (that is F1's surface), so the
 * batch boundary has to come from the caller who knows where the batch ends —
 * the poll driver, which calls [publish] after each ingester poll returns.
 *
 * The consequence is the invariant this class actually enforces: **readers read
 * only [current], never the fold**. Between two [publish] calls a reader sees
 * the previous complete snapshot; it never sees the fold mid-pass. Recorded as
 * a kernel gap in doc/demo-findings.md F-19.
 *
 * ## Threading
 *
 * The fold ([ledger], the declaration membership maps) is plain unsynchronized
 * state mutated on whichever thread delivers to the inlets — one writer, the
 * convention `SessionLedger` and `ReadySetCell` both document. [current] is
 * safe from any thread: it reads only the `@Volatile` [published] reference,
 * which is swapped once per [publish] to an already-complete immutable value.
 * The volatile write of a reference to a never-again-mutated value is the
 * happens-before edge, so a concurrent reader sees the previous report or the
 * next one and never a mix. [publish] itself is a writer-thread operation.
 *
 * ## Incrementality
 *
 * Nothing re-folds the record set. A record delta costs the ledger's index
 * maintenance; a declaration delta costs a membership flip. [publish] reads
 * O(window) sessions out of the ledger's index (see `SessionLedger`'s
 * Indexing section) and rebuilds a [DeclarationTimeline] over the live
 * declaration events, which are a handful per month — O(events), never
 * O(records).
 *
 * ## Listener failure policy
 *
 * [publish] swaps [published] BEFORE notifying, so a throwing listener can
 * never corrupt or withhold the published report. Every listener is then
 * invoked exactly once per publish even if an earlier one throws; the first
 * throwable is rethrown to the caller afterwards with the rest attached as
 * suppressed. A failing SSE subscriber therefore neither silently disappears
 * nor starves its peers — failure accounting, not suppression (AGENTS.md).
 *
 * @param windowLength the rolling window's length; the window is
 *   `[now - windowLength, now)` evaluated at each [publish] (fpml.3-D7).
 * @param now the injected clock, read exactly once per [publish]. Never
 *   `Instant.now()` inside the fold.
 */
class AllocatorReportViews(
    private val windowLength: Duration,
    private val now: () -> Instant,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {

    init {
        require(!windowLength.isNegative) { "windowLength must be non-negative, got $windowLength" }
    }

    /** F1's `SpendLogIngester.records` outlet shape. */
    val recordsInlet: FanInlet<Propagate<SetDelta<SpendRecord>>> =
        registerPort("recordsInlet", FanInlet.create())

    /** F2's declaration-history cell outlet shape. */
    val declarationsInlet: FanInlet<Propagate<SetDelta<DeclarationEvent>>> =
        registerPort("declarationsInlet", FanInlet.create())

    /** The per-project session index — task 1's fold, fed one delta at a time. */
    private val ledger = SessionLedger()

    /** Add-tags seen per declaration event, mirroring the ledger's own tag fold. */
    private val declarationAdds = mutableMapOf<DeclarationEvent, MutableSet<Timestamp>>()

    /** Tombstoned tags per declaration event. */
    private val declarationDels = mutableMapOf<DeclarationEvent, MutableSet<Timestamp>>()

    /** The live declaration events — [DeclarationTimeline]'s input, rebuilt per publish. */
    private val liveDeclarations = LinkedHashSet<DeclarationEvent>()

    /** The published snapshot; see the class KDoc's Threading section. */
    @Volatile
    private var published: AllocatorReport? = null

    private val listeners = mutableListOf<(AllocatorReport) -> Unit>()

    init {
        recordsInlet.onEach(ledger::apply)
        declarationsInlet.onEach(::onDeclarations)
    }

    private fun isLive(event: DeclarationEvent): Boolean {
        val adds = declarationAdds[event] ?: return false
        val covered = declarationDels[event] ?: return adds.isNotEmpty()
        return adds.any { it !in covered }
    }

    /**
     * The declaration half of the fold: the same add-tag/del-tag membership
     * rule `SessionLedger.apply` uses (a record is live iff it has an add-tag
     * no del covers), written out here because `TagState` is internal to the
     * kernel and this module copies the idiom by example rather than
     * importing it (fpml.1-D3).
     */
    private fun onDeclarations(delta: SetDelta<DeclarationEvent>) {
        val touched = LinkedHashSet<DeclarationEvent>(delta.adds.keys)
        touched += delta.dels.keys
        if (touched.isEmpty()) return

        val wasLive = touched.associateWith(::isLive)

        delta.adds.forEach { (event, tags) -> declarationAdds.getOrPut(event) { LinkedHashSet() } += tags }
        delta.dels.forEach { (event, tags) -> declarationDels.getOrPut(event) { LinkedHashSet() } += tags }

        touched.forEach { event ->
            val nowLive = isLive(event)
            if (nowLive == wasLive.getValue(event)) return@forEach
            if (nowLive) liveDeclarations += event else liveDeclarations -= event
        }
    }

    /**
     * Registers a listener notified after each [publish], with the exact
     * report instance [current] returns from then on — F4's SSE seam. See the
     * class KDoc's listener failure policy.
     */
    fun onPublish(listener: (AllocatorReport) -> Unit) {
        listeners += listener
    }

    /**
     * The last published snapshot, or null before the first [publish].
     * Wait-free and safe from any thread.
     */
    fun current(): AllocatorReport? = published

    /**
     * Computes and publishes one report from the current fold state, then
     * notifies [onPublish] listeners. The caller supplies the batch boundary
     * (fpml.3-D5): everything folded before this call is in the report,
     * everything after it is not, and no reader can observe anything between.
     */
    fun publish(): AllocatorReport {
        val at = now()
        val window = TimeRange(at.minus(windowLength), at)
        // `liveDeclarations` is insertion-ordered (a `LinkedHashSet`), but
        // `DeclarationTimeline` establishes its own total order over its
        // input — by `observedAt`, then by declaration content for ties — so
        // that insertion order never leaks into which declaration is in
        // force. Same convention as the sorted `projects` set below, and for
        // the same reason (computenet-2ezv1).
        val timeline = DeclarationTimeline(liveDeclarations)

        val declaredProjects = liveDeclarations.flatMapTo(LinkedHashSet<String>()) { it.declaration.weights.keys }
        // Sorted, not insertion-ordered: the project set drives the iteration
        // order of every `Double` sum below (`windowReport`'s totals, and
        // `hoursToDate` here), and floating-point addition is not associative.
        // Insertion order is a function of the fold's HISTORY — which records
        // arrived first, whether a re-baseline re-added them — so leaving it
        // insertion-ordered would make two instances holding the same
        // membership report totals differing in the last ulp. A sorted set
        // makes the report a function of membership alone, which is what
        // restart equivalence and the F5 oracle comparison both need.
        val projects = (ledger.projects() + declaredProjects).toSortedSet()

        val monthStart =
            at.atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant()
        val hoursToDate = projects.sumOf { ledger.hoursBetween(it, monthStart, at) }

        val report =
            AllocatorReport(
                publishedAt = at,
                window = windowReport(window, timeline, projects, ledger::hoursBetween),
                cap = capReport(at, timeline, hoursToDate),
                unattributable = ledger.unattributableByReason(),
                unattributableRecords = ledger.unattributable,
            )

        published = report
        notify(report)
        return report
    }

    private fun notify(report: AllocatorReport) {
        var failure: Throwable? = null
        for (listener in listeners.toList()) {
            try {
                listener(report)
            } catch (t: Throwable) {
                val first = failure
                if (first == null) failure = t else first.addSuppressed(t)
            }
        }
        val thrown = failure
        if (thrown != null) throw thrown
    }

    companion object {
        /**
         * Builds the views and attaches them to both cells with
         * `FanOutlet.streamTo`, which installs the link AND fires the
         * outlet-side on-link hook — so a cell that is already populated
         * delivers its full tag state as one delta-from-empty and the views
         * fold it exactly as if they had been attached before the first add
         * (the restart-equivalence corollary). A plain `subscribe` does not
         * fire that hook, which is why it is not used here.
         *
         * The target of each stream is the inlet's `call` api rather than the
         * inlet itself, so it is not a kernel `Linked` port and `streamTo`'s
         * default negotiated handshake falls through to its link install
         * unchanged. No `negotiated = false` is needed.
         */
        fun derivedFrom(
            records: SetCell<SpendRecord>,
            declarations: SetCell<DeclarationEvent>,
            windowLength: Duration,
            now: () -> Instant,
            ref: CellRef = CellRef(UUID.randomUUID()),
        ): AllocatorReportViews {
            val views = AllocatorReportViews(windowLength, now, ref)
            records.outlet.streamTo(views.recordsInlet.call)
            declarations.outlet.streamTo(views.declarationsInlet.call)
            return views
        }
    }
}
