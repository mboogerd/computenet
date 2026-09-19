package civictech.demo.social

import civictech.cell.Cell
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ActorIngress
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.link
import civictech.cell.port.FanOutlet
import civictech.cell.port.streamTo
import civictech.testkit.forEachSeed
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.UUID

/**
 * `[SOC1-ATOM-01..03]` (feature `computenet-jadt6`, task `computenet-jadt6.1`,
 * designs jadt6-D1..D4) — **a verification, not a construction**. It asks what
 * wave id one [SocialGraph.addPost] actually carries on each of the three
 * cells it writes, and records the answer; it does not reshape `addPost` or
 * the kernel to make a nicer answer true.
 *
 * ## What it found (observed, `forEachSeed(0L until 20L)`, darwin/arm64)
 *
 * One `addPost` is **three waves, on every seed**, and the divergence is
 * structural rather than an interleaving: `SocialGraph.addPost` makes three
 * hosted inlet calls from the app thread, each stamping the ambient
 * [CurrentContext], which is `null` off the data path
 * (`kernel/.../host/HostedCellProxy.kt`). Each `SetCell` handler therefore
 * emits with no ambient context, so `FanOutlet` takes the **origination**
 * branch of spec 20/22 rule 1 ("an external event entering the graph … mints a
 * fresh timestamp from the emitting outlet's own monotonic counter") and mints
 * `Timestamp(sourceId, ++counter)` with a `sourceId` that is **per outlet**.
 * Three cells, three outlets, three source ids. `[SOC1-ATOM-01]`'s OR-branch —
 * "SHALL assert the observed divergence with the failing seed retained" — is
 * the branch this test takes, and the finding is `doc/demo-findings.md` F-22.
 *
 * Seed 0, verbatim:
 *
 * ```
 * authored  Timestamp(d30a69fb-…, counter=1)
 * message   Timestamp(c5d27980-…, counter=1)
 * forum     Timestamp(d6c43cba-…, counter=2)
 * ```
 *
 * The forum's `counter=2` is the same rule seen from the other side: that
 * outlet had already minted wave 1 for the `ForumFact.Info` of its own
 * creation, so the counters are per outlet too, not a shared sequence.
 *
 * The counter-experiment ([`three cells under ActorIngress drive`]) wraps the
 * same `addPost` **test-side** in [ActorIngress.drive], which installs one
 * `MessageContext` for the block; every hosted call inside then carries it and
 * the same three outlets take the reactive (transparent-flow, rule 2) branch,
 * copying that one timestamp. The two cases share one probe implementation and
 * assert opposite outcomes, so neither is vacuous: a probe that recorded
 * nothing, or that recorded a constant, would fail one of them. That is this
 * task's mutation check, reached without editing a file outside its claim.
 *
 * ## The glitch-free path
 *
 * `[SOC1-ATOM-03]` is checked on a **test-side** [GlitchFreeCell] fed by
 * `manage.link` (a `Consume`-role link) from the three contributing outlets —
 * never `streamTo`, which installs an `Observe`-role link that
 * `WaveFrontier` excludes from the frontier and which would therefore gate
 * nothing. Observed: under plain `addPost` the cell releases **nothing** (each
 * source's wave waits on the two sibling edges that never carry that source —
 * the "phantom expected edge" of spec 20/22), and under
 * `ActorIngress.drive` it releases exactly three invocations, contiguous,
 * carrying one timestamp. `GlitchFreeCell` GROUPS a wave, it does not COMBINE
 * one, so that contiguity is the whole of what `[SOC1-ATOM-03]` can honestly
 * assert here — never "no torn state".
 *
 * **Limit of this file, stated where it is made:** everything above is
 * measured on the in-process `SimulationController` host with `journalDir =
 * null`. It says nothing about the same graph under recovery, across a wire
 * boundary, or under `SocialApp`'s real HTTP ingress — and nothing here
 * proposes adopting [ActorIngress] in the app; that decision, and its
 * consequence for every journaled frame (`[24-DUR-06]`), is left to epic
 * `computenet-07k` (F-22, "what a single-wave ingress would need").
 */
class SocialAtomicityTest {

    /** One downstream invocation seen by a probe: which outlet it came from, and its wave id. */
    private data class WaveRecord(val label: String, val timestamp: Timestamp)

    /** The probed post's ids, kept disjoint from every id [Rig.applyPrefix] can mint. */
    private companion object {
        const val AUTHOR = 1L
        const val FORUM = 10L
        const val POST = 100L
    }

    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        val pipeline = SnbPipeline.build(host, journalDir = null)
        val graph = SocialGraph(host, pipeline)
        val manage = host.managementInlet.call

        /** Written from the host/sim thread the probes run on, read from the test thread. */
        val records: MutableList<WaveRecord> = Collections.synchronizedList(mutableListOf())

        /**
         * A seeded, always-valid prefix of 0-10 SNB events applied before the
         * probed `addPost`, so the scheduler reaches it from a different state
         * on every seed. Uses person ids 2-6, forum ids 20-24 and message ids
         * from 1000 — none of which can collide with [AUTHOR]/[FORUM]/[POST].
         */
        fun applyPrefix(seed: Long) {
            val rnd = Random(seed)
            val persons = mutableListOf<Long>()
            val forums = mutableListOf<Long>()
            var nextMessage = 1000L
            repeat(rnd.nextInt(11)) {
                when (rnd.nextInt(4)) {
                    0 -> {
                        val id = 2L + rnd.nextInt(5)
                        graph.addPerson(Person(id, "p$id", "l$id"))
                        if (id !in persons) persons += id
                    }
                    1 -> if (persons.isNotEmpty()) {
                        val id = 20L + rnd.nextInt(5)
                        graph.addForum(Forum(id, "f$id", persons[rnd.nextInt(persons.size)]))
                        if (id !in forums) forums += id
                    }
                    2 -> if (persons.isNotEmpty() && forums.isNotEmpty()) {
                        graph.joinForum(
                            persons[rnd.nextInt(persons.size)],
                            forums[rnd.nextInt(forums.size)],
                            0,
                        )
                    }
                    else -> if (persons.isNotEmpty() && forums.isNotEmpty()) {
                        graph.addPost(
                            Message(
                                nextMessage++,
                                persons[rnd.nextInt(persons.size)],
                                0L,
                                "x",
                                forumId = forums[rnd.nextInt(forums.size)],
                            ),
                        )
                    }
                }
            }
            graph.addPerson(Person(AUTHOR, "probe", "author"))
            graph.addForum(Forum(FORUM, "probe-forum", AUTHOR))
            controller.runToIdle()
        }

        /**
         * The outlet of the cell [family] holds for [id], as the payload-erased
         * `FanOutlet<Propagate<SetDelta<Any>>>` every family's outlet already is
         * at runtime (`SetApi<E>.outlet: Subscribe<Propagate<SetDelta<E>>>`).
         * `getOrSpawn` is idempotent, so spawning the probed cells here is
         * exactly what [SocialGraph] would do a moment later.
         */
        @Suppress("UNCHECKED_CAST")
        fun outletOf(family: KeyedCells<Long>, id: Long): FanOutlet<Propagate<SetDelta<Any>>> {
            val cell: Cell = family.getOrSpawn(id)
            return (cell as SetCell<Any>).outlet as FanOutlet<Propagate<SetDelta<Any>>>
        }

        /** The three outlets one [SocialGraph.addPost] writes, in call order. */
        fun contributingOutlets(): List<Pair<String, FanOutlet<Propagate<SetDelta<Any>>>>> = listOf(
            "authored" to outletOf(pipeline.families.authored, AUTHOR),
            "message" to outletOf(pipeline.families.message, POST),
            "forum" to outletOf(pipeline.families.forum, FORUM),
        )

        /**
         * The ONE probe implementation both ingress cases use (jadt6-D1/D2):
         * an `Observe`-role `streamTo` subscriber that reads the wave id off
         * the ambient context `FanOutlet` installs around every subscriber
         * call. Catch-up baselines are skipped — spec 20/22 admits them to no
         * completeness set, and they fire at link time, not on the probed post.
         */
        fun probe(label: String, outlet: FanOutlet<Propagate<SetDelta<Any>>>, into: MutableList<WaveRecord>) {
            outlet.streamTo(
                Propagate<SetDelta<Any>> {
                    val ctx = CurrentContext.get() ?: return@Propagate
                    if (ctx.baseline != null) return@Propagate
                    into += WaveRecord(label, ctx.timestamp)
                },
            )
        }

        fun probedPost() = Message(POST, AUTHOR, 7L, "probed post", forumId = FORUM)
    }

    /**
     * Runs one seed end to end: prefix, probes attached and quiesced, then the
     * probed `addPost` applied through [ingress] (identity, or
     * `ActorIngress.drive`). Returns the records for that post alone.
     */
    private fun runOnePost(seed: Long, ingress: (() -> Unit) -> Unit): List<WaveRecord> {
        val rig = Rig(seed)
        rig.applyPrefix(seed)
        rig.contributingOutlets().forEach { (label, outlet) -> rig.probe(label, outlet, rig.records) }
        rig.controller.runToIdle()
        rig.records.clear()

        ingress { rig.graph.addPost(rig.probedPost()) }
        rig.controller.runToIdle()
        return rig.records.toList()
    }

    // --- [SOC1-ATOM-01] ------------------------------------------------------

    /**
     * F-22: the divergence, asserted as observed. Three records, three DISTINCT
     * `sourceId`s — one per contributing outlet — on every seed of the sweep.
     */
    @Test
    fun `SOC1-ATOM-01 F-22 one addPost lands as three waves, one per contributing outlet`() {
        forEachSeed(0L until 20L) { seed ->
            val records = runOnePost(seed) { it() }

            val clue = "seed=$seed records=$records"
            withSeed(clue) { records.map { it.label }.toSet() shouldBe setOf("authored", "message", "forum") }
            withSeed(clue) { records.size shouldBe 3 }
            // The finding: three sourceIds, i.e. three originating waves, not one.
            withSeed(clue) { records.map { it.timestamp.sourceId }.toSet().size shouldBe 3 }
        }
    }

    /**
     * The counter-experiment (jadt6-D2). The same probes, the same `addPost`,
     * wrapped test-side in [ActorIngress.drive] — `SocialGraph` is untouched.
     * All three records then carry ONE timestamp: the actor's lane position.
     */
    @Test
    fun `SOC1-ATOM-01 the same addPost under ActorIngress drive lands as one wave`() {
        forEachSeed(0L until 20L) { seed ->
            val actor = ActorIngress(UUID.randomUUID())
            val records = runOnePost(seed) { block -> actor.drive(block) }

            val clue = "seed=$seed records=$records"
            withSeed(clue) { records.map { it.label }.toSet() shouldBe setOf("authored", "message", "forum") }
            withSeed(clue) { records.size shouldBe 3 }
            withSeed(clue) { records.map { it.timestamp }.toSet().size shouldBe 1 }
        }
    }

    // --- [SOC1-ATOM-03] ------------------------------------------------------

    /**
     * The Consume-linked [GlitchFreeCell] path, under both ingress shapes.
     * Asserts the observed release count per ingress and, for any wave with
     * more than one release, that the releases are contiguous and carry one
     * timestamp.
     */
    @Test
    fun `SOC1-ATOM-03 a Consume-linked GlitchFreeCell releases nothing under plain ingress and one contiguous wave under drive`() {
        forEachSeed(0L until 20L) { seed ->
            val plain = runGlitchFree(seed) { it() }
            withSeed("seed=$seed plain=$plain") {
                // Phantom expected edges: each source's wave waits forever on the
                // two sibling edges that structurally never carry that source.
                plain.size shouldBe 0
            }

            val actor = ActorIngress(UUID.randomUUID())
            val driven = runGlitchFree(seed) { block -> actor.drive(block) }
            val clue = "seed=$seed driven=$driven"
            withSeed(clue) { driven.size shouldBe 3 }
            withSeed(clue) { driven.map { it.timestamp }.toSet().size shouldBe 1 }
            withSeed(clue) { contiguousByWave(driven) shouldBe true }
        }
    }

    private fun runGlitchFree(seed: Long, ingress: (() -> Unit) -> Unit): List<WaveRecord> {
        val rig = Rig(seed)
        rig.applyPrefix(seed)

        @Suppress("UNCHECKED_CAST")
        val gf = GlitchFreeCell(Propagate::class.java as Class<Propagate<SetDelta<Any>>>)
        rig.manage.spawn(gf)
        rig.contributingOutlets().forEach { (_, outlet) ->
            val result = rig.manage.link(outlet, gf.inlet)
            // [SOC1-ATOM-03]'s "IF the link is refused" arm: a refusal is the
            // uncheckable-with-reason outcome, recorded verbatim, never worked
            // around with a kernel change.
            check(result is civictech.cell.link.LinkResult.Connected) {
                "manage.link refused the Consume link into the GlitchFreeCell: $result"
            }
        }
        // The probe on the glitch-free outlet labels by the delta's element type:
        // the pass-through rewrites sourcePort, so the element is how the three
        // contributing inputs are told apart.
        gf.outlet.streamTo(
            Propagate<SetDelta<Any>> { delta ->
                val ctx = CurrentContext.get() ?: return@Propagate
                if (ctx.baseline != null) return@Propagate
                val element = (delta.adds.keys + delta.dels.keys).firstOrNull()
                rig.records += WaveRecord(element?.javaClass?.simpleName ?: "empty", ctx.timestamp)
            },
        )
        rig.controller.runToIdle()
        rig.records.clear()

        ingress { rig.graph.addPost(rig.probedPost()) }
        rig.controller.runToIdle()
        return rig.records.toList()
    }

    /** True when every wave id's releases occupy one unbroken run of [records]. */
    private fun contiguousByWave(records: List<WaveRecord>): Boolean =
        records.map { it.timestamp }
            .let { stamps -> stamps.distinct().all { wave -> isRun(stamps.indices.filter { stamps[it] == wave }) } }

    private fun isRun(indices: List<Int>): Boolean =
        indices.isEmpty() || indices.last() - indices.first() == indices.size - 1

    /** Keeps the seed in the assertion message, per `[SOC1-ATOM-01]`. */
    private fun withSeed(clue: String, assertion: () -> Unit) {
        try {
            assertion()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }
    }
}
