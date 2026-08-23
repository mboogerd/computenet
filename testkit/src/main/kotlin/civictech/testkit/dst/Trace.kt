package civictech.testkit.dst

import java.security.MessageDigest

/**
 * One observed point in a run ([CHA1-05]): *what happened, at which controller step*.
 *
 * The tuple is the epic's, verbatim — `(step, host, cellRef, port, faultTag)` — with one
 * deliberate substitution: [cell] is the rig's **declared name** for a cell, not its
 * `CellRef`. A `CellRef` is a fresh random `UUID` per construction (`ManagedHost`'s default
 * `ref`), so digesting it would make every digest run-local and [CHA1-04]/BS-2 unfalsifiable.
 * [DstWorld.cells] is where a graph builder declares the mapping; an undeclared ref falls
 * back to its UUID text, which is honest but means *that* run's digest only compares to
 * itself. Declare the cells you trace.
 *
 * @property step the [civictech.cell.host.SimulationController] step index during which the
 *   event was observed — never a wall clock ([CHA1-02]).
 * @property host declared host name, or null for events not attributable to one host.
 * @property port a port or **edge** name; the frame plane stamps the edge name here.
 * @property faultTag the id of the fault that caused this event, or null for ordinary
 *   observation. A trace with no non-null `faultTag` is a fault-free trace.
 */
data class TraceEvent(
    val step: Int,
    val host: String? = null,
    val cell: String? = null,
    val port: String? = null,
    val faultTag: String? = null,
) {
    /** The digest's canonical rendering of one event. Stable; changing it changes every digest. */
    fun canonical(): String = "$step|${host ?: "-"}|${cell ?: "-"}|${port ?: "-"}|${faultTag ?: "-"}"
}

/**
 * A stable hash over an ordered [TraceEvent] stream ([CHA1-05]), so "same seed ⇒ same run"
 * is a checkable assertion rather than a belief.
 *
 * Stability is *within a commit*, not across them (epic §9 risk 6): the digest is a function
 * of what the graph and the kernel scheduler actually did, so an unrelated kernel scheduling
 * change moves it — which is the point. Never pin a literal digest into a test; compare two
 * runs.
 */
data class TraceDigest(val hex: String) {

    override fun toString(): String = "sha256:${hex.take(16)}"

    companion object {
        val EMPTY: TraceDigest = of(emptyList())

        fun of(events: List<TraceEvent>): TraceDigest {
            val md = MessageDigest.getInstance("SHA-256")
            events.forEach { md.update((it.canonical() + "\n").toByteArray(Charsets.UTF_8)) }
            return TraceDigest(md.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

/**
 * **Seam 5 of 6 — trace and fault-tag emission.** The one API through which anything in a
 * run says "this happened": graph wiring, the rig's own observation, and every fault class.
 *
 * The step index is *not* a parameter — the world stamps it from the step it is currently
 * driving. That is what makes [CHA1-02] structural rather than a convention: a fault cannot
 * emit a wall-clock timestamp because there is nowhere to put one.
 */
interface TraceSink {

    /** Ordinary observation: no fault involved. */
    fun emit(host: String? = null, cell: String? = null, port: String? = null)

    /**
     * Fault [faultId] fired, *now*, on the named seam. Records a [TraceEvent] with
     * `faultTag = faultId` **and** counts the firing for [CHA1-24] — one call, both effects,
     * so a fault cannot be traced without being counted or counted without being traced.
     * A fault whose id never reaches this method is reported inert.
     */
    fun fault(faultId: String, host: String? = null, cell: String? = null, port: String? = null)
}

/** Accumulates [TraceEvent]s in observation order and reduces them to a [TraceDigest]. */
class TraceRecorder {
    private val recorded = mutableListOf<TraceEvent>()

    fun record(event: TraceEvent) {
        recorded += event
    }

    fun events(): List<TraceEvent> = recorded.toList()

    fun digest(): TraceDigest = TraceDigest.of(recorded)
}

/**
 * [CHA1-33]: the digest-equality self-check, callable from any consumer suite.
 *
 * Runs the same `(seed, plan)` [runs] times and fails loudly on the first divergence, naming
 * both digests and the first differing event — a bare `assertEquals` on two hashes tells the
 * reader nothing about *where* the runs parted.
 */
object TraceDigests {

    fun assertSameDigest(runs: Int = 2, label: String = "run", run: (Int) -> List<TraceEvent>) {
        require(runs >= 2) { "a determinism self-check needs at least two runs, got $runs" }
        val first = run(0)
        val firstDigest = TraceDigest.of(first)
        for (i in 1 until runs) {
            val next = run(i)
            val nextDigest = TraceDigest.of(next)
            if (nextDigest != firstDigest) {
                throw AssertionError(
                    "$label is not deterministic: run 0 digest ${firstDigest.hex} != run $i digest ${nextDigest.hex}; " +
                        divergence(first, next),
                )
            }
        }
    }

    /** Human-readable description of where two traces first differ. */
    fun divergence(a: List<TraceEvent>, b: List<TraceEvent>): String {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) return "first divergence at event $i: ${a[i].canonical()} vs ${b[i].canonical()}"
        }
        return if (a.size == b.size) {
            "traces are equal event-for-event (digest mismatch would be a hashing defect)"
        } else {
            "traces agree on the first $n events; lengths differ (${a.size} vs ${b.size})"
        }
    }
}
