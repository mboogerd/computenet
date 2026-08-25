package civictech.testkit.dst.churn

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.testkit.dst.DstCheck
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Harness-side probes over the delivered-watermark companion the churn mesh gossips alongside
 * its data replicas ([CHA3-30], [CHA3-31], [CHA3-41], [CHA3-42]) — factored out of
 * `DepartureGatesTest` so the sweep/findings task can reuse them without re-deriving the
 * row-state reads.
 *
 * ## Why every read goes through [MeshPeer.replication]`.watermarkOf`
 *
 * That is the SAME accessor `MemberDepartureFrontierTest` and `DeliveredWatermarkTest` read the
 * lattice through (`kernel/src/test/kotlin/civictech/cell/replication/`), and the coverage rule
 * [stabilityCovers] mirrors `MemberDepartureFrontierTest`'s `ReplicaFrontier` lambda
 * byte-for-byte: `slot in closed || rows[slot]?.get(source) >= counter`, read off the same
 * `WatermarkCell.rows()`/`closed()`. That test IS the PN-0c control seam BS-11 reaches through
 * ([CHA3-72]); a second, independently-derived implementation of its rule would be free to
 * disagree with it silently, which would make a green [stabilityCovers] read prove nothing about
 * the kernel behaviour it is supposed to be reading.
 *
 * A [WatermarkCell] is keyed per **replica slot**, not per peer name — [WatermarkCell.slotId] of
 * a [MeshPeer]'s own `ref` is the key every lane below is read by, exactly as the kernel test
 * derives it.
 *
 * ## What [originOf] reads, and what it deliberately does NOT
 *
 * [originOf] reads the ADD tag [MemberDepartureFrontierTest]'s own `originTags` lambda reads —
 * `SetDelta<String>.adds[element]`, a per-op [Timestamp] that SURVIVES relay (spec 40/42
 * §Delivered watermarks: the origin tag is what a downstream frontier settles waves against).
 *
 * It is deliberately **not** [FanOutlet.waveState] — measured directly here before settling on
 * the delta read: `waveState().sourceId` names a per-outlet-EPOCH identity a replica mints for
 * ITS OWN re-emissions, discarded on relay (`civictech.cell.data.delta.DeliveryTracking`'s own
 * KDoc states this plainly: "a replica re-originates a peer's delta under its own outlet source,
 * discarding the origin before the tap runs"). A companion's row for a second, live, gossiping
 * peer never advanced for a wave read off `waveState()` in this file's own development — only
 * the per-op origin tag converges the way `[CHA3-41]`'s "stability advances past the closed row"
 * needs.
 *
 * This is also why the churn mesh under test here is built [MeshPayload.SET]: a `SetDelta` names
 * its per-op tags publicly (`adds`/`dels`); `PnCounterCell`'s per-op origin is not exposed the
 * same way outside `:kernel`.
 */
object StabilityObservables {

    /** [observer]'s own delivered-watermark companion, or a loud failure if it tracks none. */
    private fun companionOf(observer: MeshPeer): WatermarkCell =
        observer.replication.watermarkOf(observer.ref.id)
            ?: error(
                "peer \"${observer.name}\" tracks no delivered-watermark companion for its own " +
                    "logical id ${observer.ref.id} — a replica must exist before its companion does",
            )

    /**
     * The companion [WatermarkCell] ref [subject]'s row lives under — `Replication.watermarkRef`
     * reproduced here rather than called, because it is `internal` to `:kernel`
     * (`Replication.kt:98`, verified) and therefore unreachable from `:testkit`: the exact wall
     * `linkCountAmong` hit for computenet-umx.2.2/2.3, one symbol over.
     *
     * The derivation is a documented CONTRACT even though the function that computes it is
     * `internal` — `Replication.watermarkRef`'s own KDoc: "derived from the data id and sharing
     * the data replica's `instanceId`" — and its literal body (verified at the same line) is
     * `CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}"), dataRef.instanceId)`. Recorded
     * here rather than only in a review comment: a change to that formula would desync this
     * helper from the kernel's own one silently, with no compiler error on either side.
     */
    private fun watermarkRefOf(dataRef: CellRef): CellRef =
        CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}".toByteArray()), dataRef.instanceId)

    /** [WatermarkCell.slotId] of [subject]'s own row — the key every lane above is keyed by. */
    private fun slotOf(subject: MeshPeer): UUID = WatermarkCell.slotId(watermarkRefOf(subject.ref))

    /**
     * True while [observer]'s companion carries [subject]'s row PN-19 SUSPENDED and not CLOSED —
     * [CHA3-30]/[CHA3-31]'s membership-gated suspend arm (BS-9), read from the gossiped lattice
     * rather than assumed from the local eviction verdict.
     */
    fun rowSuspended(observer: MeshPeer, subject: MeshPeer): Boolean {
        val companion = companionOf(observer)
        val slot = slotOf(subject)
        return slot in companion.suspended() && slot !in companion.closed()
    }

    /** True while [observer]'s companion carries [subject]'s row CLOSED (PN-0c, clean departure). */
    fun rowClosed(observer: MeshPeer, subject: MeshPeer): Boolean = slotOf(subject) in companionOf(observer).closed()

    // ------------------------------------------------------------------------- origin tracking

    private val watched: MutableSet<MeshPeer> = Collections.newSetFromMap(WeakHashMap())
    private val elementOrigin: MutableMap<MeshPeer, MutableMap<Any?, Timestamp>> = WeakHashMap()

    /**
     * Start recording the ADD tag of every element [subject]'s own outlet makes visible
     * ([originOf]). Idempotent — a subject already watched is left alone. Call this **before**
     * the write whose origin [originOf] must read back: the tap only sees emissions after it
     * attaches, exactly like every other tap in this rig.
     */
    @Suppress("UNCHECKED_CAST")
    fun watch(subject: MeshPeer) {
        if (!watched.add(subject)) return
        val cell = subject.replica ?: error("peer \"${subject.name}\" holds no replica to watch")
        val perSubject = elementOrigin.getOrPut(subject) { mutableMapOf() }
        (cell.outlet as FanOutlet<Propagate<SetDelta<String>>>).tap(
            Use.fixed(
                Propagate<SetDelta<String>> { delta ->
                    delta.adds.forEach { (element, tags) ->
                        tags.maxByOrNull { it.counter }?.let { perSubject[element] = it }
                    }
                },
                PortRef.generate(),
            ),
        )
    }

    /**
     * The ADD tag of [element], as observed on [subject]'s own outlet since [watch] attached.
     * `MeshPeer.write`'s own naming (`"<peer>-<ordinal>"`) is what a caller passes here — see
     * `DepartureGatesTest` for the pattern.
     */
    fun originOf(subject: MeshPeer, element: String): Timestamp {
        watch(subject)
        return elementOrigin[subject]?.get(element)
            ?: error(
                "peer \"${subject.name}\" has not observed element \"$element\" on its own outlet " +
                    "since it was watched — watch() before the write, not after",
            )
    }

    // ---------------------------------------------------------------------------- coverage

    /**
     * Every one of [members] whose row, as [observer]'s companion currently has it, neither
     * covers [origin] (delivered through its counter) nor is closed. Empty means [origin] is
     * currently stable across the whole of [members] ([stabilityCovers]).
     */
    private fun uncoveredMembers(observer: MeshPeer, members: List<MeshPeer>, origin: Timestamp): List<MeshPeer> {
        val companion = companionOf(observer)
        val rows = companion.rows()
        val closed = companion.closed()
        return members.filter { member ->
            val slot = slotOf(member)
            slot !in closed && (rows[slot]?.get(origin.sourceId) ?: Long.MIN_VALUE) < origin.counter
        }
    }

    /**
     * BS-10/[CHA3-41]/[CHA3-42]'s stability read: does [observer]'s companion cover [origin] for
     * EVERY one of [members] — either that member's row has DELIVERED through [origin]'s
     * counter, or its row is CLOSED (PN-0c's release, `[42-REPL-06]`)?
     *
     * A [CHA3-31] SUSPENDED row (not closed) does **not** satisfy this — the point of BS-9/BS-10:
     * only a clean, closed departure or a still-delivering live member unblocks stability, so a
     * partition-suspended or crashed-and-never-closed member correctly freezes it.
     */
    fun stabilityCovers(observer: MeshPeer, members: List<MeshPeer>, origin: Timestamp): Boolean =
        uncoveredMembers(observer, members, origin).isEmpty()

    /**
     * BS-11/[CHA3-72]: the harness-side DETECTOR for the PN-0c wedge. Fails the run — naming the
     * still-open row(s) in [ChurnCheckFailure.detail] behind the fixed [ChurnCheckFailure]
     * identity — when [origin] is not [stabilityCovers]ed across [memberNames] as read from
     * [observerName]'s companion. An `EVICT_NO_CLOSE` departure leaves its row open forever, so a
     * suite that attaches this check to such a run gets a RED result rather than a silently
     * tolerated wedge; a suite that attaches it to a clean-evict or still-live run gets a PASS.
     *
     * Exposed as a [DstCheck] — rather than only the boolean [stabilityCovers] — because the
     * controls task consumes this exact divergence result ([CHA3-72]'s own text: "expose this
     * control's divergence result so it can consume it").
     */
    fun stabilityCoversCheck(observerName: String, memberNames: List<String>, origin: Timestamp): DstCheck =
        DstCheck { world ->
            val observer = MeshPeers.require(world, observerName)
            val members = memberNames.map { MeshPeers.require(world, it) }
            val uncovered = uncoveredMembers(observer, members, origin)
            if (uncovered.isNotEmpty()) {
                throw ChurnCheckFailure(
                    "stability failed to advance past an unclosed row",
                    detail = "observer=$observerName origin=(source=${origin.sourceId}, counter=${origin.counter}) " +
                        "uncoveredRows=${uncovered.map { it.name }}",
                )
            }
        }
}
