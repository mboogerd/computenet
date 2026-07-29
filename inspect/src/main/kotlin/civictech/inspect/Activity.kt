package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.ManagedHost.LifecycleTransition
import java.util.concurrent.ConcurrentHashMap

/**
 * V2 — the activity feed (`doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V2-BE.md`):
 * `GET /api/inspect/activity` plus the `activity` SSE event.
 *
 * ### What it is, and what it replaces
 *
 * Until V2 a lifecycle change was announced as the cell's *current* state and
 * then forgotten, and it was **sampled**: a 1 Hz tick walked every known node
 * and recomputed [Heat] to catch transitions that are individually rare
 * (`InspectorServer`'s retired `"lifecycleChanged"` tick). V2-KERNEL added
 * [ManagedHost.onLifecycle], so both halves of that change at once:
 *
 * - the transition **pushes** from the host that performed it, on the rare path
 *   that caused it, instead of being looked for once a second on every cell;
 * - it is **retained** as a timestamped [ActivityEntry] in a bounded
 *   [RingBuffer], so a client can render "passivated at 14:02:11, woken at
 *   14:02:40, restarted twice since" rather than only "suspended".
 *
 * ### Five kinds, three sources
 *
 * | kind | Source |
 * |---|---|
 * | [ActivityEntry.PASSIVATED] | [LifecycleTransition.SUSPENDED] — explicit `suspend` or `SupervisionPolicy.SUSPEND`; the kernel does not distinguish them at this seam and neither does this feed |
 * | [ActivityEntry.ACTIVATED] | [LifecycleTransition.RESUMED] (per cell) or [LifecycleTransition.HOST_RESUMED] (`resumeHost`, once per cell the host holds) |
 * | [ActivityEntry.DRAINED] | [LifecycleTransition.DRAINED] — one entry per cell the host held when its drain completed (a migrate drains too, so it reports here as well) |
 * | [ActivityEntry.WOKEN] | [Waker.wake] — the inspector's own single causal act, recorded from the HTTP thread that served the `POST` |
 * | [ActivityEntry.RESTARTED] | [Errors]' 2 s generation poll ([Errors.pollRestarts]) — a restart has no push seam, and V2-KERNEL deliberately did not add one (`generationOf` already makes it countable) |
 *
 * A wake produces **both** a `woken` and, moments later, an `activated` for the
 * same ref. That is intended and neither is suppressed: one is "a user asked",
 * the other is "the kernel did".
 *
 * ### Threading
 *
 * [onTransition] runs **synchronously on the host's own scheduler thread**, at
 * the transition point, after the state change is already visible. Everything
 * it does is therefore sized for that thread: one `clock()` read, one string
 * encode, one `synchronized` [RingBuffer.add], and a hand-off to the SSE
 * broadcaster's bounded drop-oldest queues through [InspectorModel]. Nothing
 * blocks, nothing does I/O, nothing waits on a socket — the same budget
 * [Errors.onDeadLetterReceived] already spends on a host thread for dead
 * letters, and the same monitor ([InspectorModel]'s) that the registry's
 * publish/link hooks have always taken from host threads. No new contention
 * class, and no lock is held across anything that can enter a host.
 *
 * Exceptions are contained here as well as in the kernel: [ManagedHost] already
 * catches a throwing listener, but a listener that half-recorded an entry
 * before throwing would still be an observer becoming a participant — so the
 * whole body is guarded at this boundary too.
 *
 * ### Bounds (P2/P6)
 *
 * Nothing here is per message: every call site is a rare lifecycle transition
 * or a 2 s metadata poll. Nothing here touches a cell, subscribes to an outlet
 * or raises attention — [ManagedHost.onLifecycle] is a notification, and
 * `GET /activity` serves a ring snapshot. The ring is capped at
 * [RING_CAPACITY] for the whole process; the only other retained state is one
 * deregistration handle per *host* (never per cell), keyed by the host's own
 * ref, and every one of them is released by [close].
 */
internal class Activity(
    private val registry: LocationRegistry,
    hosts: Collection<ManagedHost>,
    /**
     * Is this ref a cell the view actually serves? Filters two things out of
     * the feed: cells that are not published here at all, and the inspector's
     * own `ObserveCell` sinks — an instrument is not a subject
     * (`InspectorModel.instruments`), so a drain that deactivates one must not
     * show up as activity on a graph the user is watching.
     */
    private val knows: (CellRef) -> Boolean,
    /** Non-blocking sink for one captured entry — the `activity` SSE emission point. */
    private val onEntry: (ActivityEntry) -> Unit,
    /**
     * The cell whose two-valued `Node.lifecycle` may just have moved. Push
     * replacement for the retired 1 Hz sweep: [InspectorModel] recomputes the
     * one ref and emits `lifecycle` only if the announced value actually
     * changed, which is what keeps "exactly one event per real transition" true
     * now that a `resumeHost` is visible both here and on the publish hook.
     */
    private val onLifecycle: (CellRef) -> Unit,
    ringCapacity: Int = RING_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val ring = RingBuffer<ActivityEntry>(ringCapacity)

    /**
     * One [ManagedHost.onLifecycle] handle per watched host, keyed by the
     * host's own [ManagedHost.ref] rather than by the host object — identity
     * semantics without depending on [ManagedHost] never gaining an `equals`.
     * Bounded by the number of hosts in the process, not by cells.
     */
    private val handles = ConcurrentHashMap<CellRef, AutoCloseable>()

    /** Set by [close] so a publish racing teardown cannot re-attach a listener. */
    @Volatile
    private var closed = false

    init {
        hosts.forEach(::watch)
    }

    /** `GET /api/inspect/activity` — the ring's contents, oldest first. */
    fun snapshot(): ActivitySnapshot = ActivitySnapshot(ring.snapshot())

    /**
     * Subscribe to [host]'s transitions, once. Idempotent per host: a repeated
     * call — the ordinary case, since every local publish offers its host here
     * — neither registers a second listener nor duplicates an entry.
     */
    fun watch(host: ManagedHost) {
        if (closed) return
        handles.computeIfAbsent(host.ref) { host.onLifecycle(::onTransition) }
    }

    /**
     * Catch-up for hosts already holding published cells at construction, and
     * the reason a `hosts` map that names only some of them is still fully
     * covered.
     *
     * The declared [InspectorServer] `hosts` map is a *naming* map (`Node.host`
     * labels); the set of hosts whose cells this view shows is whatever
     * [LocationRegistry.locate] answers — including a host the application
     * never named, and including a **child host** (`ManagedHost`'s `childHosts`
     * is private, so it is not enumerable directly, but a child's published
     * cells locate to it exactly like any other host's). [Heat] has always read
     * those hosts; now the listener reaches them too, which is what lets the
     * poll retire without losing a transition.
     */
    fun watchLocatedHosts() = registry.localRefs().forEach(::watchHostOf)

    /** Attach to whichever host [ref] was just published on. Idempotent (see [watch]). */
    fun watchHostOf(ref: CellRef) {
        registry.locate(ref)?.let(::watch)
    }

    /**
     * [ActivityEntry.WOKEN] for every cell one `POST /graph/{id}/wake` resumed
     * (see [WakeReport.woken]) — the user's causal act, recorded as such and
     * distinct from the [ActivityEntry.ACTIVATED] the kernel reports for the
     * same cells immediately after. A wake that found nothing cold records
     * nothing.
     */
    fun woken(refs: Collection<CellRef>) = refs.forEach { record(it, ActivityEntry.WOKEN) }

    /**
     * [ActivityEntry.RESTARTED], from [Errors]' observed generation increase —
     * the one kind with no push seam. The row is already built from primitives,
     * so this only re-shapes it; [RestartRow.atMs] is reused rather than
     * re-clocked so the error lane and the activity feed timestamp one restart
     * identically.
     */
    fun restarted(row: RestartRow) {
        val ref = InspectorServer.decodeRef(row.ref) ?: return
        if (!knows(ref)) return
        add(ActivityEntry(row.ref, ActivityEntry.RESTARTED, row.atMs, row.generation))
    }

    override fun close() {
        closed = true
        handles.values.forEach { runCatching { it.close() } }
        handles.clear()
    }

    /**
     * The kernel listener body. Runs on the transitioning host's scheduler
     * thread — see the class doc's §Threading for what that permits.
     */
    private fun onTransition(ref: CellRef, transition: LifecycleTransition) {
        runCatching {
            if (!knows(ref)) return@runCatching
            record(ref, kindOf(transition))
            onLifecycle(ref)
        }
    }

    private fun record(ref: CellRef, kind: String) {
        if (!knows(ref)) return
        add(ActivityEntry(InspectorServer.encodeRef(ref), kind, clock()))
    }

    private fun add(entry: ActivityEntry) {
        ring.add(entry)
        onEntry(entry)
    }

    companion object {
        /** Same bound and same reason as [Errors]' two rings: 200 rows for the whole process. */
        const val RING_CAPACITY = 200

        private fun kindOf(transition: LifecycleTransition): String = when (transition) {
            LifecycleTransition.SUSPENDED -> ActivityEntry.PASSIVATED
            // one vocabulary for "it is running again", whether one cell was
            // resumed or its whole host was: the client's question is when the
            // cell came back, and `WakeReport.hosts` is where the wider blast
            // radius of a `resumeHost` is reported
            LifecycleTransition.RESUMED, LifecycleTransition.HOST_RESUMED -> ActivityEntry.ACTIVATED
            LifecycleTransition.DRAINED -> ActivityEntry.DRAINED
        }
    }
}
