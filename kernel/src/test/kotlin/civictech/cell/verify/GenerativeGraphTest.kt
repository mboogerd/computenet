package civictech.cell.verify

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.CollectorCell
import civictech.cell.data.CountCell
import civictech.cell.data.FilterCell
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.data.tagFold
import civictech.cell.graph.CellHandle
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.graph
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.CounterDelta

/**
 * The G-31 harness: seeded random pipelines from the data-cell vocabulary,
 * emitted as [GraphSpec]s (built on one view host, replayed verbatim onto the
 * other), driven with random op scripts, a mid-run late joiner, and a mid-run
 * host migration. The invariant suite runs on every generated graph:
 * cross-view convergence, incremental == batch recompute, late == early,
 * non-negative count (an [InvariantCell]), and no dead letters anywhere.
 */
class GenerativeGraphTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    class CounterCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals = mutableListOf<CounterDelta>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    arrivals += value
                }
            })
        }
    }

    companion object {
        /** Referenced statically from factory lambdas, so specs capture only the index. */
        val PREDICATES: List<(String) -> Boolean> = listOf(
            { it <= "e" },
            { it in setOf("a", "c", "e", "g", "i") },
            { it >= "c" },
        )
    }

    private data class ViewSide(
        val early: CollectorCell,
        var host: ManagedHost,
        val refs: Map<String, CellRef>,
        val terminal: CellRef,
    )

    private data class Run(
        val left: ViewSide,
        val right: ViewSide,
        val late: CollectorCell,
        val counts: CounterCollectorCell,
        val invariantViolations: List<Violation>,
        val letters: List<DeadLetter>,
        val expected: Set<String>,
    )

    private fun runGenerated(seed: Long, ops: Int): Run {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val rnd = Random(seed)

        val hostW = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostL = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostR = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostL2 = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val letters = mutableListOf<DeadLetter>()
        listOf(hostW, hostL, hostR, hostL2).forEach { host ->
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    letters += value
                }
            }, PortRef.generate()))
        }

        // ---- generate a random pipeline shape: union → 0..2 filters → count
        val filterIdxs = (0 until rnd.nextInt(3)).map { rnd.nextInt(PREDICATES.size) }

        fun buildView(host: ManagedHost): Pair<GraphSpec, Map<String, CellRef>> {
            val refs = mutableMapOf<String, CellRef>()
            val spec = graph(host.managementInlet) {
                var tail: CellHandle = spawn("union") { ref -> UnionSetCell<String>(ref = ref) }
                refs[tail.name] = tail.ref
                filterIdxs.forEachIndexed { i, idx ->
                    val filter = spawn("filter$i") { ref -> FilterCell<String>(ref = ref) { s -> PREDICATES[idx](s) } }
                    tail linkTo filter
                    refs[filter.name] = filter.ref
                    tail = filter
                }
                val count = spawn("count") { ref -> CountCell<String>(ref = ref) }
                tail linkTo count
                refs[count.name] = count.ref
            }
            return spec to refs
        }

        // build on L; replay the SAME spec onto R — identical topology, fresh cells
        val (spec, refsL) = buildView(hostL)
        val refsR = spec.applyTo(hostR.managementInlet)

        val terminalName = if (filterIdxs.isEmpty()) "union" else "filter${filterIdxs.size - 1}"
        val left = ViewSide(CollectorCell(), hostL, refsL, refsL.getValue(terminalName))
        val right = ViewSide(CollectorCell(), hostR, refsR, refsR.getValue(terminalName))

        // early collectors + L-side counter collector + non-negative invariant, all as cells
        listOf(left, right).forEach { side ->
            side.host.managementInlet.call.spawn(side.early)
            side.host.managementInlet.call.connect(side.terminal, "outlet", side.early.ref, "inlet")
        }
        val counts = CounterCollectorCell()
        hostL.managementInlet.call.spawn(counts)
        hostL.managementInlet.call.connect(refsL.getValue("count"), "outlet", counts.ref, "inlet")

        val nonNegative = InvariantCell<CounterDelta, Long>(
            "non-negative count", 0L,
            fold = { total, delta -> total + delta.amount },
            check = { total, _ -> if (total < 0) "count went negative: $total" else null },
        )
        val violations = mutableListOf<Violation>()
        nonNegative.violations.subscribe(Use.fixed(object : Propagate<Violation> {
            override fun propagate(value: Violation) {
                violations += value
            }
        }, PortRef.generate()))
        hostL.managementInlet.call.spawn(nonNegative)
        hostL.managementInlet.call.connect(refsL.getValue("count"), "outlet", nonNegative.ref, "inlet")

        // ---- writers on their own host, streaming to both unions via registry proxies
        val writers = (0 until 2).map { SetCell<String>() }
        writers.forEach { writer ->
            hostW.managementInlet.call.spawn(writer)
            listOf(refsL, refsR).forEach { refs ->
                val union = (HostedCellProxy.create(refs.getValue("union"), registry, DeltaInletProxy::class.java)
                        as DeltaInletProxy).inlet.call
                writer.outlet.subscribe(Use.fixed(union, PortRef.generate()))
            }
        }
        val writerApis = writers.map {
            (HostedCellProxy.create(it.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        }

        // ---- drive: random ops with a late join and a migration landing mid-stream
        val domain = ('a'..'j').map { it.toString() }
        val held = writers.map { mutableSetOf<String>() }
        val late = CollectorCell()
        val joinAt = 1 + rnd.nextInt(ops / 2)
        val moveAt = ops / 2 + rnd.nextInt(ops / 2 - 1)
        var currentL = hostL

        for (n in 1..ops) {
            val w = rnd.nextInt(writers.size)
            val element = domain[rnd.nextInt(domain.size)]
            if (rnd.nextInt(10) < 7 || element !in held[w]) {
                writerApis[w].add(element); held[w] += element
            } else {
                writerApis[w].remove(element); held[w] -= element
            }
            if (n == joinAt) {
                controller.runToIdle() // co-hosted connect needs the cells settled
                currentL.managementInlet.call.spawn(late)
                currentL.managementInlet.call.connect(left.terminal, "outlet", late.ref, "inlet")
            }
            if (n == moveAt) {
                currentL.managementInlet.call.migrate(hostL2.managementInlet)
                currentL = hostL2
            }
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        val expected = held.flatten().toSet()
            .filter { e -> filterIdxs.all { PREDICATES[it](e) } }.toSet()
        return Run(left, right, late, counts, violations, letters, expected)
    }

    @Test
    fun `every generated graph satisfies the invariant suite on every seed`() {
        for (seed in 0L until 100L) {
            val run = runGenerated(seed, ops = 40)

            // cross-view convergence + incremental == batch recompute
            tagFold(run.left.early.arrivals) shouldBe run.expected
            tagFold(run.right.early.arrivals) shouldBe run.expected
            run.counts.arrivals.sumOf { it.amount } shouldBe run.expected.size.toLong()

            // a late joiner caught up through mid-stream linking (and a migration)
            tagFold(run.late.arrivals) shouldBe run.expected

            run.invariantViolations.shouldBeEmpty()
            run.letters.shouldBeEmpty()
        }
    }

    @Test
    fun `control - arrival-order application diverges across views on at least one seed`() {
        fun naiveFold(deltas: List<SetDelta<String>>): Set<String> {
            val present = mutableSetOf<String>()
            deltas.forEach { present += it.adds.keys; present -= it.dels.keys }
            return present
        }

        var diverged = 0
        for (seed in 0L until 30L) {
            val run = runGenerated(seed, ops = 40)
            val views = listOf(naiveFold(run.left.early.arrivals), naiveFold(run.right.early.arrivals))
            if (views.toSet().size > 1 || views.any { it != run.expected }) diverged++
        }
        // if this fails the harness interleaving is too weak to expose order bias
        (diverged > 0).shouldBeTrue()
    }
}
