package civictech.concord.driver.kernel

import civictech.concord.driver.LinkResult
import civictech.concord.oracle.BatchOracle
import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.Graph
import civictech.concord.schema.Kind
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.Profile
import civictech.concord.schema.Scenario
import civictech.concord.schema.Step
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * End-to-end verification that the W3-0 catalog bindings **execute** against the
 * kernel: each fixture builds a small graph through the [KernelDriver], drives the
 * script, quiesces, and asserts `readView` equals a hand-computed golden — and,
 * for value graphs, that the [BatchOracle] agrees on the same golden (the
 * `incremental-equals-batch` contract the corpus authors depend on). This is the
 * driver-side twin of `BatchOracleTest`; the two together pin driver ≡ oracle.
 */
class KernelDriverBindingsTest {

    private val BUDGET = 5_000_000

    private fun c(id: String, type: String, fn: String? = null, agg: String? = null, k: Int? = null): CellSpec =
        CellSpec(id = id, type = type, fn = fn, agg = agg, k = k)

    private fun l(from: String, to: String, inlet: String? = null, outlet: String? = null): LinkSpec =
        LinkSpec(from = from, to = to, inlet = inlet, outlet = outlet)

    private fun ap(on: String, op: String, value: Value? = null, times: Int? = null): Step =
        ApplyStep(on = on, op = op, value = value, times = times)

    private fun sc(cells: List<CellSpec>, links: List<LinkSpec>, script: List<Step>): Scenario =
        Scenario(
            id = "DRV", title = "driver fixture", covers = listOf("X"),
            profile = Profile.CORE, kind = Kind.EXAMPLE,
            graph = Graph(cells = cells, links = links), script = script,
        )

    private fun paramsOf(cell: CellSpec): Map<String, Value> = buildMap {
        cell.of?.let { put("of", Value.StrVal(it)) }
        cell.fn?.let { put("fn", Value.StrVal(it)) }
        cell.agg?.let { put("agg", Value.StrVal(it)) }
        cell.k?.let { put("k", Value.IntVal(it.toLong())) }
    }

    private fun drive(scenario: Scenario): KernelDriver {
        val d = KernelDriver(0L)
        scenario.graph!!.cells.forEach { d.spawn("", it.id, it.type, paramsOf(it)) }
        scenario.graph!!.links.forEach { d.connect(it.from, it.to, it.inlet, it.outlet, it.role) }
        scenario.script.forEach { step ->
            if (step is ApplyStep) repeat(step.times ?: 1) { d.apply(step.on, step.op, step.value) }
        }
        d.quiesce(BUDGET)
        return d
    }

    /** driver `readView` and the batch oracle must both equal [golden]. */
    private fun bothAgree(scenario: Scenario, viewId: String, golden: Value) {
        drive(scenario).readView(viewId) shouldBe golden
        BatchOracle(scenario).view(viewId) shouldBe golden
    }

    // ---- operators ---------------------------------------------------------

    @Test fun `filter passes only even elements`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("f", "filter", fn = "even"), c("v", "set-view")),
            listOf(l("a", "f"), l("f", "v")),
            (1..5).map { ap("a", "add", i(it.toLong())) },
        ),
        "v", list(i(2), i(4)),
    )

    @Test fun `map applies add(10) element-wise`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("m", "map", fn = "add(10)"), c("v", "set-view")),
            listOf(l("a", "m"), l("m", "v")),
            listOf(ap("a", "add", i(1)), ap("a", "add", i(2)), ap("a", "add", i(3))),
        ),
        "v", list(i(11), i(12), i(13)),
    )

    @Test fun `flatmap expands list elements into a set`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("fm", "flatmap", fn = "identity"), c("v", "set-view")),
            listOf(l("a", "fm"), l("fm", "v")),
            listOf(ap("a", "add", list(i(1), i(2))), ap("a", "add", list(i(2), i(3)))),
        ),
        "v", list(i(1), i(2), i(3)),
    )

    @Test fun `join inner-joins on the shared key`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("b", "set-source"), c("j", "join", fn = "key-of"), c("v", "set-view")),
            listOf(l("a", "j", "left"), l("b", "j", "right"), l("j", "v")),
            listOf(
                ap("a", "add", list(s("k1"), s("L1"))),
                ap("a", "add", list(s("k2"), s("L2"))),
                ap("b", "add", list(s("k1"), s("R1"))),
            ),
        ),
        "v", list(list(s("k1"), s("L1"), s("R1"))),
    )

    @Test fun `semi-join keeps left rows whose key is on the right`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("b", "set-source"), c("sj", "semi-join", fn = "key-of"), c("v", "set-view")),
            listOf(l("a", "sj", "left"), l("b", "sj", "right"), l("sj", "v")),
            listOf(
                ap("a", "add", list(s("k1"), s("x"))),
                ap("a", "add", list(s("k2"), s("y"))),
                ap("b", "add", list(s("k1"), s("z"))),
            ),
        ),
        "v", list(list(s("k1"), s("x"))),
    )

    @Test fun `lookup-join enriches left with the matched right value`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("b", "set-source"), c("lj", "lookup-join", fn = "key-of"), c("v", "set-view")),
            listOf(l("a", "lj", "left"), l("b", "lj", "right"), l("lj", "v")),
            listOf(
                ap("a", "add", list(s("k1"), s("v1"))),
                ap("b", "add", list(s("k1"), s("d1"))),
            ),
        ),
        "v", list(list(list(s("k1"), s("v1")), s("d1"))),
    )

    @Test fun `group-by defaults to count`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("g", "group-by", fn = "key-of"), c("v", "count-view")),
            listOf(l("a", "g"), l("g", "v")),
            listOf(
                ap("a", "add", list(s("a"), i(1))),
                ap("a", "add", list(s("a"), i(2))),
                ap("a", "add", list(s("b"), i(3))),
            ),
        ),
        "v", map("a" to i(2), "b" to i(1)),
    )

    @Test fun `group-by sum folds the group's value components`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("g", "group-by", fn = "key-of", agg = "sum"), c("v", "count-view")),
            listOf(l("a", "g"), l("g", "v")),
            listOf(
                ap("a", "add", list(s("a"), i(1))),
                ap("a", "add", list(s("a"), i(2))),
                ap("a", "add", list(s("b"), i(3))),
            ),
        ),
        "v", map("a" to i(3), "b" to i(3)),
    )

    @Test fun `partition equals its unpartitioned group-by twin`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("p", "partition", fn = "key-of"), c("v", "count-view")),
            listOf(l("a", "p"), l("p", "v")),
            listOf(
                ap("a", "add", list(s("a"), i(1))),
                ap("a", "add", list(s("a"), i(2))),
                ap("a", "add", list(s("b"), i(3))),
            ),
        ),
        "v", map("a" to i(2), "b" to i(1)),
    )

    @Test fun `count is set cardinality`() = bothAgree(
        sc(
            listOf(c("a", "set-source"), c("cnt", "count"), c("v", "value-view")),
            listOf(l("a", "cnt"), l("cnt", "v")),
            listOf(ap("a", "add", s("x")), ap("a", "add", s("y")), ap("a", "add", s("x"))),
        ),
        "v", i(2),
    )

    @Test fun `quorum-set admits elements met by k of n sources`() = bothAgree(
        sc(
            listOf(
                c("a", "set-source"), c("b", "set-source"), c("cc", "set-source"),
                c("q", "quorum-set", k = 2), c("v", "set-view"),
            ),
            listOf(l("a", "q"), l("b", "q"), l("cc", "q"), l("q", "v")),
            listOf(
                ap("a", "add", i(1)), ap("a", "add", i(2)),
                ap("b", "add", i(2)), ap("b", "add", i(3)),
                ap("cc", "add", i(2)),
            ),
        ),
        "v", list(i(2)),
    )

    // ---- sources -----------------------------------------------------------

    @Test fun `map-source is last-writer-wins per key`() = bothAgree(
        sc(
            listOf(c("m", "map-source"), c("v", "map-view")),
            listOf(l("m", "v")),
            listOf(
                ap("m", "put", list(s("k1"), i(1))),
                ap("m", "put", list(s("k1"), i(2))),
                ap("m", "put", list(s("k2"), i(9))),
                ap("m", "remove", s("k2")),
            ),
        ),
        "v", map("k1" to i(2)),
    )

    // `ormap-source` (KE1-F4) folded through its `tagged-map-view`. On ONE stream the
    // dot algebra's reset-remove collapses to file-order LWW per key with `remove`
    // dropping the key — exactly the map-source shape above, but reached through
    // OrMapCell's TaggedMapDelta and TaggedMapView rather than MapDelta/MapView. That
    // the two agree is the point: the tagged binding must not weaken the uncontended
    // semantics. `k2` is put twice and removed, so its tombstone must cover BOTH dots.
    @Test fun `ormap-source folds to the current key-value map through a tagged-map-view`() = bothAgree(
        sc(
            listOf(c("om", "ormap-source"), c("v", "tagged-map-view")),
            listOf(l("om", "v")),
            listOf(
                ap("om", "put", list(s("k1"), i(1))),
                ap("om", "put", list(s("k1"), i(2))), // reset-remove: covers k1's first dot
                ap("om", "put", list(s("k2"), i(9))),
                ap("om", "put", list(s("k2"), i(10))),
                ap("om", "remove", s("k2")), // covers every live dot at k2
                ap("om", "put", list(s("k3"), s("z"))),
            ),
        ),
        "v", map("k1" to i(2), "k3" to s("z")),
    )

    @Test fun `list-source keeps positional order`() = bothAgree(
        sc(
            listOf(c("ls", "list-source"), c("v", "list-view")),
            listOf(l("ls", "v")),
            listOf(ap("ls", "append", s("a")), ap("ls", "append", s("b")), ap("ls", "append", s("c"))),
        ),
        "v", list(s("a"), s("b"), s("c")),
    )

    @Test fun `pn-counter folds increments minus decrements`() = bothAgree(
        sc(
            listOf(c("pn", "pn-counter"), c("v", "value-view")),
            listOf(l("pn", "v")),
            listOf(ap("pn", "increment", i(50)), ap("pn", "decrement", i(8))),
        ),
        "v", i(42),
    )

    @Test fun `keyed-set folds keyed upserts to current elements`() = bothAgree(
        sc(
            listOf(c("ks", "keyed-set"), c("v", "set-view")),
            listOf(l("ks", "v")),
            listOf(
                ap("ks", "put", list(s("k1"), s("x"))),
                ap("ks", "put", list(s("k1"), s("y"))), // LWW: k1 now y
                ap("ks", "put", list(s("k2"), s("z"))),
                ap("ks", "remove", s("k1")),
            ),
        ),
        "v", list(s("z")),
    )

    // ---- cycles (34-CYCLE) -------------------------------------------------

    @Test fun `a damped feedback loop is admitted and reaches its fixpoint`() {
        val scenario = sc(
            listOf(c("n", "counter-source"), c("fb", "feedback"), c("v", "value-view")),
            listOf(
                l("n", "fb"),
                l("fb", "fb", inlet = "feedbackInput", outlet = "loopOutlet"),
                l("fb", "v"),
            ),
            listOf(ap("n", "increment", i(64))),
        )
        val d = KernelDriver(0L)
        scenario.graph!!.cells.forEach { d.spawn("", it.id, it.type, paramsOf(it)) }
        val results = scenario.graph!!.links.map { d.connect(it.from, it.to, it.inlet, it.outlet, it.role) }
        results.forEach { it.shouldBeInstanceOf<LinkResult.Connected>() } // the closing edge is admitted
        d.apply("n", "increment", i(64))
        d.quiesce(BUDGET)
        d.readView("v") shouldBe i(127) // 64 + 32 + 16 + 8 + 4 + 2 + 1
        d.deadLetters() shouldBe emptyList()
    }

    @Test fun `a cycle with no damping witness is rejected at connect`() {
        val d = KernelDriver(0L)
        d.spawn("", "n", "counter-source", emptyMap())
        d.spawn("", "fb", "feedback-undamped", emptyMap())
        d.spawn("", "v", "value-view", emptyMap())
        d.connect("n", "fb")
        d.connect("fb", "v")
        val closing = d.connect("fb", "fb", inlet = "feedbackInput", outlet = "loopOutlet")
        closing.shouldBeInstanceOf<LinkResult.Rejected>()
    }

    // ---- lifecycle verbs ---------------------------------------------------

    @Test fun `disconnect by endpoints stops further delivery`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("a", "v")
        d.apply("a", "add", s("before"))
        d.quiesce(BUDGET)
        d.disconnectEndpoint("a", "v", null, null).shouldBeInstanceOf<LinkResult.Connected>()
        d.apply("a", "add", s("after"))
        d.quiesce(BUDGET)
        // the view retains what it had at unlink; the post-unlink add never arrives.
        d.readView("v") shouldBe list(s("before"))
        d.deadLetters() shouldBe emptyList()
    }

    // ---- restart / re-baseline (21-REBASE-01, D-C12) -----------------------

    @Test fun `restart reverts a rebaseline-source and retracts its un-reasserted adds downstream`() {
        val d = KernelDriver(0L)
        d.spawn("", "s", "rebaseline-source", emptyMap())
        d.spawn("", "u", "union", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("s", "u")
        d.connect("u", "v")
        d.apply("s", "add", s("alpha"))
        d.apply("s", "add", s("beta"))
        d.quiesce(BUDGET)
        d.readView("v") shouldBe list(s("alpha"), s("beta"))

        val epochBefore = d.wavePlane("s")
        d.restart("s")
        d.quiesce(BUDGET)

        // the un-reasserted pre-restart adds are retracted downstream, not merely
        // dropped at the source — the whole point of `[21-REBASE-01]`
        d.readView("v") shouldBe list()
        // and the outlet succeeded its emission epoch: no post-restart position
        // can alias a pre-restart one (spec 20/22 §Source identity)
        (d.wavePlane("s").positions.keys intersect epochBefore.positions.keys) shouldBe emptySet()

        // post-restart traffic folds under the fresh epoch
        d.apply("s", "add", s("gamma"))
        d.quiesce(BUDGET)
        d.readView("v") shouldBe list(s("gamma"))

        // the restart trigger is dead-lettered by design (30/31 rule 5 — every
        // policy dead-letters), which is exactly why 21-REBASE-01 omits the
        // `no-dead-letters` check rather than weakening it.
        d.deadLetters().size shouldBe 1
    }

    @Test fun `restarting a catalog cell with no restart binding fails loudly`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        // `set-source`'s tag source is replay-stable, so it cannot witness epoch
        // succession; the binding refuses rather than performing a bare restore.
        assertThrows<UnsupportedCatalogBinding> { d.restart("a") }
    }

    @Test fun `snapshot and restore round-trip a source's state`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("a", "v")
        d.apply("a", "add", s("x"))
        d.apply("a", "add", s("y"))
        d.quiesce(BUDGET)
        val blob = d.snapshot("a")
        d.apply("a", "remove", s("x"))
        d.quiesce(BUDGET)
        d.restore("", "a", blob) // roll the source back to {x, y}
        // a fresh consumer linked after restore catches up to the restored state
        d.spawn("", "v2", "set-view", emptyMap())
        d.connect("a", "v2")
        d.quiesce(BUDGET)
        d.readView("v2") shouldBe list(s("x"), s("y"))
    }
}
