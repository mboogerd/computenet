package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Aggregators
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.KeyedSetOps
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.PnCounterOps
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.MergeableGroupByCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.verify.ReplicaConvergence
import civictech.oracle.model.Delivery
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
import civictech.oracle.model.KeyedReputModel
import civictech.oracle.model.MergeableGroupByModel
import civictech.oracle.model.PnCounterConvergenceModel
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.PnCounterTerminalFold
import civictech.oracle.run.TaggedMapTerminalFold
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The per-behaviour scenario tests owned by this task and not already landed by a sibling:
 * BS-3, BS-5, BS-10, BS-11, BS-12. (BS-1, BS-6, BS-7 land in
 * `civictech.oracle.tagged.ConvergenceCheckTest`, computenet-4ru.1.4; BS-2, BS-4, BS-8, BS-13,
 * BS-14 in `civictech.oracle.tagged.MultiWriterGenerationTest`/`TaggedControlsTest`,
 * computenet-4ru.1.3.) Each drives a REAL kernel cell — the model-only halves of BS-10/BS-11/
 * BS-12 already live in `civictech.oracle.model.TaggedKeyedModelTest`
 * (computenet-4ru.1.1), whose own KDoc says so: "the kernel halves... land with the sweep task."
 */
class TaggedScenariosTest {

    // =====================================================================
    // BS-3 — add-wins under concurrent put/remove [ORA2-DIFF-03]
    // =====================================================================

    /** The hosted `OrMapCell` inlet proxy shape. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<Any?, Any?>>
    }

    @Test
    fun `BS-3 add-wins under concurrent put and remove`() {
        // Writer A puts k=v1, then concurrently with B's remove puts k=v2 (unobserved by B). B
        // has only observed A's FIRST put when it removes. Add-wins: v2 survives.
        val logicalId = UUID(0xB5L, 3L)
        val world = SimWorld(seed = 3L)
        val replication = Replication(world.registry)
        val convergence = ReplicaConvergence<civictech.cell.data.delta.TaggedMapDelta<Any?, Any?>, civictech.cell.data.delta.TaggedMapDelta<Any?, Any?>>(
            world.registry,
            logicalId,
            civictech.cell.data.delta.TaggedMapDelta(),
        ) { acc, delta -> TaggedMapTerminalFold.merge(acc, delta) }

        val a = OrMapCell<Any?, Any?>(CellRef(logicalId, 0L))
        val b = OrMapCell<Any?, Any?>(CellRef(logicalId, 1L))
        replication.replicate(a, world.host)
        replication.replicate(b, world.host)
        convergence.attach(a)
        convergence.attach(b)
        world.runToIdle()

        @Suppress("UNCHECKED_CAST")
        fun opsFor(ref: CellRef) =
            (HostedCellProxy.create(ref, world.registry, OrMapInletProxy::class.java) as OrMapInletProxy)
                .inlet.call as MapOps<Any?, Any?>
        val opsA = opsFor(a.ref)
        val opsB = opsFor(b.ref)

        opsA.put("k", "v1")
        world.runToIdle() // A's v1 gossips to B
        opsB.remove("k") // B removes, having observed only v1
        opsA.put("k", "v2") // concurrent with B's remove: A never learns of it before putting v2
        world.runToIdle()

        val order = DotOrder.ranked(listOf(SourceId("a"), SourceId("b")))
        val script = Script(
            listOf(
                SourceScript(SourceId("a"), listOf(ScriptEvent.Put(WriterId("wa"), "k", "v1"), ScriptEvent.Put(WriterId("wa"), "k", "v2"))),
                SourceScript(
                    SourceId("b"),
                    listOf(ScriptEvent.RemoveKey(WriterId("wb"), "k")),
                    listOf(Delivery(afterEvents = 0, from = SourceId("a"), throughEvents = 1)),
                ),
            ),
        )
        val expected = DotModel(order).value(DotModel(order).converged(script), "k")
        withClue("the model's own precondition: add-wins keeps v2") { expected shouldBe "v2" }

        withClue("k must be present at both replicas with v2 — B's remove only tombstoned what it observed") {
            a.membership() shouldBe setOf("k")
            b.membership() shouldBe setOf("k")
            a.value("k") shouldBe "v2"
            b.value("k") shouldBe "v2"
        }
    }

    // =====================================================================
    // BS-5 — duplicate delivery is absorbed via a topological diamond [ORA2-DIFF-04]
    // =====================================================================

    @Test
    fun `BS-5 duplicate delivery through a topological diamond is absorbed`() {
        // A single OrMapCell's outlet connected TWICE to the same terminal — the ONLY sanctioned
        // duplication mechanism (topology, not fault injection): every delta this source emits
        // arrives at the fold twice.
        val world = SimWorld(seed = 5L)
        val src = OrMapCell<Any?, Any?>(CellRef(UUID.randomUUID()))
        val fold = TaggedMapTerminalFold<Any?, Any?>()
        world.host.managementInlet.call.spawn(src)
        world.host.managementInlet.call.spawn(fold)
        val link1 = world.host.managementInlet.call.connect(src.ref, "outlet", fold.ref, "inlet")
        val link2 = world.host.managementInlet.call.connect(src.ref, "outlet", fold.ref, "inlet")
        withClue("both links must actually be admitted, or duplication never happens") {
            (link1 is civictech.cell.link.LinkResult.Rejected) shouldBe false
            (link2 is civictech.cell.link.LinkResult.Rejected) shouldBe false
        }

        @Suppress("UNCHECKED_CAST")
        val ops = (HostedCellProxy.create(src.ref, world.registry, OrMapInletProxy::class.java) as OrMapInletProxy)
            .inlet.call as MapOps<Any?, Any?>
        ops.put("k1", "v1")
        ops.put("k2", "v2")
        ops.remove("k1")
        ops.put("k2", "v3")
        world.runToIdle()

        val slice = SourceScript(
            SourceId("s"),
            listOf(
                ScriptEvent.Put(WriterId("w"), "k1", "v1"),
                ScriptEvent.Put(WriterId("w"), "k2", "v2"),
                ScriptEvent.RemoveKey(WriterId("w"), "k1"),
                ScriptEvent.Put(WriterId("w"), "k2", "v3"),
            ),
        )
        val expected = DotModel(DotOrder.ranked(listOf(SourceId("s")))).evaluate(Script(listOf(slice)))

        withClue("every terminal fold equals the model's answer despite double delivery") {
            fold.current() shouldBe expected
        }
    }

    // =====================================================================
    // BS-10 — keyed re-put atomic at every prefix [ORA2-MODEL-08]
    // =====================================================================

    @Test
    fun `BS-10 KeyedSetCell re-put is atomic at every script prefix`() {
        val world = SimWorld(seed = 10L)
        val cell = KeyedSetCell<String, String>(CellRef(UUID.randomUUID()))
        world.host.managementInlet.call.spawn(cell)

        var deliveries = 0
        val terminal = object : Propagate<SetDelta<String>> {
            var elements: MutableSet<String> = mutableSetOf()
            override fun propagate(value: SetDelta<String>) {
                deliveries++
                elements -= value.dels.keys
                elements += value.adds.keys
            }
        }
        cell.outlet.subscribe(civictech.cell.port.Use.fixed(terminal, PortRef.generate()))

        @Suppress("UNCHECKED_CAST")
        val ops = (HostedCellProxy.create(cell.ref, world.registry, KeyedSetInletProxy::class.java) as KeyedSetInletProxy)
            .inlet.call as KeyedSetOps<String, String>

        val slice = SourceScript(
            SourceId("s"),
            listOf(
                ScriptEvent.Put(WriterId("w"), "k1", "a"),
                ScriptEvent.Put(WriterId("w"), "k1", "b"), // re-put: retract a, add b — ONE delta
                ScriptEvent.Put(WriterId("w"), "k2", "c"),
                ScriptEvent.RemoveKey(WriterId("w"), "k1"),
                ScriptEvent.Put(WriterId("w"), "k1", "d"),
                ScriptEvent.RemoveKey(WriterId("w"), "k3"), // absent key: a no-op
            ),
        )
        slice.events.forEachIndexed { index, event ->
            val before = deliveries
            when (event) {
                is ScriptEvent.Put -> ops.put(event.key as String, event.element as String)
                is ScriptEvent.RemoveKey -> ops.remove(event.key as String)
                else -> error("unreachable")
            }
            world.runToIdle()
            val expected = KeyedReputModel.bindingsAt(slice, index + 1).values.toSet()
            withClue("after event $index ($event): fold=${terminal.elements} model=$expected") {
                terminal.elements shouldBe expected
                // every EFFECTIVE op (re-put/first-put/observed-remove) ships exactly ONE atomic
                // delta — never a torn intermediate visible between a retract and its add.
                (deliveries - before) shouldBe if (KeyedReputModel.bindingsAt(slice, index) == KeyedReputModel.bindingsAt(slice, index + 1)) 0 else 1
            }
        }
    }

    interface KeyedSetInletProxy {
        val inlet: Use<KeyedSetOps<String, String>>
    }

    // =====================================================================
    // BS-11 — mergeable group-by is grow-only; GroupByCell's retraction diverges [ORA2-MODEL-09]
    // =====================================================================

    @Test
    fun `BS-11 MergeableGroupByCell does not retract, and GroupByCell's divergence is explicit`() {
        val world = SimWorld(seed = 11L)
        val source = SetCell<String>(CellRef(UUID.randomUUID()))
        val mergeable = MergeableGroupByCell<String, String, Long>(
            CellRef(UUID.randomUUID()),
            keyOf = { it },
            accumulate = { 1L },
            merge = { a, b -> a + b },
        )
        val retracting = GroupByCell<String, String, Long, Long>(
            CellRef(UUID.randomUUID()),
            keyFn = { it },
            aggregator = Aggregators.count(),
        )
        val retractingFold = civictech.oracle.run.MapTerminalFold<String, Long>()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(mergeable)
        world.host.managementInlet.call.spawn(retracting)
        world.host.managementInlet.call.spawn(retractingFold)
        world.host.managementInlet.call.connect(source.ref, "outlet", mergeable.ref, "inlet")
        world.host.managementInlet.call.connect(source.ref, "outlet", retracting.ref, "inlet")
        world.host.managementInlet.call.connect(retracting.ref, "outlet", retractingFold.ref, "inlet")

        @Suppress("UNCHECKED_CAST")
        val ops = (HostedCellProxy.create(source.ref, world.registry, SetInletProxy::class.java) as SetInletProxy)
            .inlet.call as SetOps<String>

        val slice = SourceScript(
            SourceId("s"),
            listOf(
                ScriptEvent.Add(WriterId("w"), "x"),
                ScriptEvent.Add(WriterId("w"), "x"),
                ScriptEvent.Remove(WriterId("w"), "x"),
            ),
        )
        ops.add("x")
        world.runToIdle()
        ops.add("x")
        world.runToIdle()
        ops.remove("x")
        world.runToIdle()

        val model = MergeableGroupByModel(keyOf = { it }, accumulate = { 1L }, merge = { a, b -> (a as Long) + (b as Long) })
        val expectedMergeable: Map<Any?, Any?> = model.aggregatesAt(slice, slice.events.size)

        withClue("MergeableGroupByCell: grow/merge only, the remove is not reflected") {
            (mergeable.aggregates() as Map<Any?, Any?>) shouldBe expectedMergeable
            mergeable.aggregates().getValue("x") shouldBeGreaterThan 0L
        }
        withClue("GroupByCell diverges explicitly: its retraction DOES remove the element") {
            retractingFold.current().entries.containsKey("x") shouldBe false
            retractingFold.current().entries.mapValues { it.value } shouldNotBe (mergeable.aggregates() as Map<Any?, Any?>)
        }
    }

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    // =====================================================================
    // BS-12 — PnCounter converges to the pointwise max [ORA2-MODEL-10] / [ORA2-CONV-01]
    // =====================================================================

    interface PnCounterInletProxy {
        val inlet: Use<PnCounterOps>
    }

    @Test
    fun `BS-12 PnCounter converges to the per-source pointwise-max total`() {
        val world = SimWorld(seed = 12L)
        val logicalId = UUID(0xB512L, 12L)
        val replication = Replication(world.registry)
        val convergence = ReplicaConvergence<PnCounterDelta, PnCounterDelta>(
            world.registry,
            logicalId,
            PnCounterDelta(),
        ) { acc, delta -> PnCounterTerminalFold.merge(acc, delta) }

        val replicas = (0 until 3).map { i -> PnCounterCell(CellRef(logicalId, i.toLong())) }
        replicas.forEach { cell ->
            replication.replicate(cell, world.host)
            convergence.attach(cell)
        }
        world.runToIdle()

        @Suppress("UNCHECKED_CAST")
        val ops = replicas.map { cell ->
            (HostedCellProxy.create(cell.ref, world.registry, PnCounterInletProxy::class.java) as PnCounterInletProxy).inlet.call
        }
        ops[0].increment(5)
        ops[1].increment(3)
        ops[2].decrement(2)
        ops[0].increment(1)
        world.runToIdle()

        val sourceIds = (0 until 3).map { SourceId("r$it") }
        val script = Script(
            listOf(
                SourceScript(sourceIds[0], listOf(ScriptEvent.Increment(WriterId("w0"), 5), ScriptEvent.Increment(WriterId("w0"), 1))),
                SourceScript(sourceIds[1], listOf(ScriptEvent.Increment(WriterId("w1"), 3))),
                SourceScript(sourceIds[2], listOf(ScriptEvent.Decrement(WriterId("w2"), 2))),
            ),
        )
        val expectedTotal = PnCounterConvergenceModel.converged(script).let { it.incs.values.sum() - it.decs.values.sum() }

        replicas.forEach { cell ->
            val folded = convergence.state(cell.ref)!!
            val total = folded.incs.values.sum() - folded.decs.values.sum()
            withClue("replica ${cell.ref}: total=$total expected=$expectedTotal") {
                total shouldBe expectedTotal
            }
        }
        withClue("the setup is genuinely concurrent: three distinct sources contributed") {
            expectedTotal shouldBe (5L + 1L + 3L - 2L)
        }
    }
}
