package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 96 §E1.4, feature computenet-j2x.1, task computenet-j2x.1.3 — **BS-1**
 * verbatim: two replicas of an [OrMapCell]`<String, `[PnCounterDelta]`>` in a
 * seeded [SimulationController] mesh, replica A puts +3 and replica B
 * concurrently puts +5 at key `"k"`; once the mesh idles, `value("k")` on
 * both replicas is the merged +8 (`[KE1-02]`, `[KE1-05]`, `[KE1-09]`).
 *
 * Sibling of [OrMapConvergenceTest] (E1-REPL's mesh idioms — `Mesh`,
 * `record`, `HostedCellProxy` inlet routing, `forEachSeed` — read that file
 * first) rather than a replacement: that suite exercises the `String`-valued,
 * non-mergeable path exhaustively; this one is scoped to the one thing it
 * cannot exercise — genuine concurrent same-key puts on a [PnCounterDelta]
 * (`MergeablePayload`) value, which the fold in [TaggedMapDelta.value] and
 * [OrMapCell.value] (landed in computenet-j2x.1.1) now resolves by folding
 * every live dot instead of picking one under [TaggedMapDelta.DOT_ORDER].
 *
 * **This task is test-only** (its `metadata.files` claim is this one file):
 * the non-vacuousness route is therefore not a production mutation-check but
 * an **in-test control** — a fold-less LWW read computed from public API
 * (`state()`, `TaggedMapDelta.liveDots`, `TaggedMapDelta.DOT_ORDER`) over the
 * very same converged state the real assertion reads. That control must trip
 * on every seed: it is the pre-E1.4 read the fold replaced, so it always
 * lands on one writer's contribution (+3 or +5), never the fold's +8.
 */
class OrMapEmbeddedConvergenceTest {

    interface CounterInletProxy {
        val inlet: Use<MapOps<String, PnCounterDelta>>
    }

    /** A two-peer full mesh of `OrMapCell<String, PnCounterDelta>` replicas, seeded. */
    private class Mesh(seed: Long) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hosts = List(2) { ManagedHost(scheduler = controller.scheduler(), registry = registry) }
        val replication = Replication(registry)

        // derived from the seed, not random, mirroring OrMapConvergenceTest's
        // ORMAP_SALT idiom — every (counter, sourceId) tie-break stays
        // reproducible per seed.
        private val logicalId = UUID(seed, EMBEDDED_SALT)

        fun start(): List<OrMapCell<String, PnCounterDelta>> {
            val cells = List(hosts.size) { i -> OrMapCell<String, PnCounterDelta>(CellRef(logicalId, i.toLong())) }
            cells.forEachIndexed { i, cell -> replication.replicate(cell, hosts[i]) }
            controller.runToIdle()
            return cells
        }

        fun ops(cell: OrMapCell<String, PnCounterDelta>): MapOps<String, PnCounterDelta> =
            (HostedCellProxy.create(cell.ref, registry, CounterInletProxy::class.java) as CounterInletProxy).inlet.call
    }

    /** Record a replica's broadcast emissions — exactly what the mesh gossips. */
    private fun record(cell: OrMapCell<String, PnCounterDelta>): MutableList<TaggedMapDelta<String, PnCounterDelta>> {
        val out = mutableListOf<TaggedMapDelta<String, PnCounterDelta>>()
        cell.outlet.subscribe(
            Use.fixed(
                Propagate<TaggedMapDelta<String, PnCounterDelta>> { out += it },
                PortRef.generate(),
            )
        )
        return out
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMapIndexed { i, item ->
            permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(item) + it }
        }

    @Test
    fun `BS-1 - two replicas concurrently put mergeable values and converge on the folded total`() {
        forEachSeed(0L until 100L) { seed ->
            val mesh = Mesh(seed)
            val (a, b) = mesh.start().let { it[0] to it[1] }
            val emissionsA = record(a)
            val emissionsB = record(b)

            val sourceA = UUID(seed, 0xA0AL)
            val sourceB = UUID(seed, 0xB0BL)

            // concurrent: both writers act before either has observed the
            // other's put — same op, no controller step between them, exactly
            // OrMapConvergenceTest's "same key, three writers" idiom (op 20).
            mesh.ops(a).put("k", PnCounterDelta(incs = mapOf(sourceA to 3L)))
            mesh.ops(b).put("k", PnCounterDelta(incs = mapOf(sourceB to 5L)))
            mesh.controller.runToIdle()

            val expected = PnCounterDelta(incs = mapOf(sourceA to 3L, sourceB to 5L))

            withClue("seed $seed") {
                // ---- convergence: [KE1-09] ----
                a.value("k") shouldBe expected
                b.value("k") shouldBe expected
                a.value("k")!!.incs.values.sum() shouldBe 8L

                // values() agrees across replicas too — [KE1-06]
                val perDotValues = setOf(
                    PnCounterDelta(incs = mapOf(sourceA to 3L)),
                    PnCounterDelta(incs = mapOf(sourceB to 5L)),
                )
                a.values("k") shouldBe perDotValues
                b.values("k") shouldBe perDotValues

                // ---- the control: fold-less LWW read must NOT reproduce +8 ----
                // computed entirely from public API over the SAME converged
                // state the assertions above just read — this is the
                // pre-E1.4 rule ([24-TMAP-03]) the fold in value()/values()
                // replaced. A passing (i.e. also-8) control here would mean
                // this test cannot discriminate the fold from the LWW pick.
                val liveDots = a.state().liveDots("k")
                val lwwDot = liveDots.keys.maxWith(TaggedMapDelta.DOT_ORDER)
                val lwwTotal = liveDots.getValue(lwwDot).incs.values.sum()
                lwwTotal shouldNotBe 8L
                (lwwTotal == 3L || lwwTotal == 5L) shouldBe true
                // and both replicas' state agree on which dot that is —
                // otherwise the control itself would be seed-dependent noise
                // rather than a stable non-vacuousness check.
                val lwwDotOnB = b.state().liveDots("k").keys.maxWith(TaggedMapDelta.DOT_ORDER)
                lwwDotOnB shouldBe lwwDot

                // ---- arrival-order independence, mesh half: [KE1-05] ----
                // fold the very deltas the mesh gossiped, in every order and
                // duplicated, independently of the replicas' own folds.
                val allEmitted = emissionsA + emissionsB
                permutations(allEmitted).forEach { order ->
                    val folded = order.fold(TaggedMapDelta<String, PnCounterDelta>()) { acc, d -> acc.merge(d) }
                    folded.value("k") shouldBe expected
                }
                val duplicated =
                    (allEmitted + allEmitted).fold(TaggedMapDelta<String, PnCounterDelta>()) { acc, d -> acc.merge(d) }
                duplicated.value("k") shouldBe expected

                // ---- echo termination survives: [KE1-38] ----
                // re-deliver an already-held delta (one of a's own emissions,
                // which by now b has already absorbed) — nothing new to fold,
                // nothing re-emitted, bounded.
                val emittedBeforeA = emissionsA.size
                val emittedBeforeB = emissionsB.size
                b.deltaInlet.call.propagate(emissionsA.last())
                mesh.controller.runToIdle()
                emissionsA.size shouldBe emittedBeforeA
                emissionsB.size shouldBe emittedBeforeB
                a.value("k") shouldBe expected
                b.value("k") shouldBe expected
            }
        }
    }

    private companion object {
        /** A fixed logical-id salt, so a session's refs are a pure function of its seed. */
        const val EMBEDDED_SALT = 0x0EB1L
    }
}
