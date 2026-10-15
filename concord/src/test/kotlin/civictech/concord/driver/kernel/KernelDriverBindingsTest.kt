package civictech.concord.driver.kernel

import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell
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
        // the `window:` / `lateness` lowering, as CorpusRunner.params does it
        cell.window?.let { w ->
            put(
                "window",
                Value.MapVal(
                    buildMap {
                        put("kind", Value.StrVal(if (w.kind == civictech.concord.schema.WindowKind.TUMBLING) "tumbling" else "sliding"))
                        put("size", Value.IntVal(w.size))
                        w.slide?.let { put("slide", Value.IntVal(it)) }
                    },
                ),
            )
        }
        cell.lateness?.let { put("lateness", Value.IntVal(it)) }
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

    @Test fun `declare-interest threads the family spawn-on-interest flag`() {
        fun family(spawnOnInterest: Boolean): Map<String, Value> = mapOf(
            "family" to map(
                "keys" to s("long"),
                "spawn-on-interest" to Value.BoolVal(spawnOnInterest),
            ),
        )
        val bounded = map("ranges" to list(list(i(2), i(4))))

        val enabled = KernelDriver(0L)
        enabled.spawn("h1", "f", "set-source", family(spawnOnInterest = true))
        enabled.declareInterest("f", bounded)
        enabled.familyKeys("f") shouldBe listOf(2L, 3L)

        // An unbounded declaration is recorded but refused by the family. It
        // neither adds a member nor becomes a dead letter, and the dedicated
        // family refusal observation accounts it.
        enabled.declareInterest("f", map("total" to Value.BoolVal(true)))
        enabled.familyKeys("f") shouldBe listOf(2L, 3L)
        enabled.interestRefusalCount("f") shouldBe 1L
        enabled.deadLetters() shouldBe emptyList()

        val disabled = KernelDriver(0L)
        disabled.spawn("h1", "f", "set-source", family(spawnOnInterest = false))
        disabled.declareInterest("f", bounded)
        disabled.familyKeys("f") shouldBe emptyList()
        disabled.interestRefusalCount("f") shouldBe 0L
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

    // ---- lateness (computenet-t4od7.1, spec 24 §Lateness and waterlines) ----
    //
    // These drive the kernel side and assert the final view directly; the oracle's
    // lateness model (computenet-t4od7.2) is pinned against the driver by the last test
    // here and, per operator, by BatchOracleTest.

    private fun lat(l: Long): Map<String, Value> = mapOf("lateness" to i(l))

    private fun tumbling(size: Long, lateness: Long? = null): Map<String, Value> = buildMap {
        put("window", map("kind" to s("tumbling"), "size" to i(size)))
        lateness?.let { put("lateness", i(it)) }
    }

    private fun KernelDriver.link(from: String, to: String, inlet: String? = null, outlet: String? = null) =
        connect(from, to, inlet, outlet, null).shouldBeInstanceOf<LinkResult.Connected>()

    /** A private `Windows.Lateness` field of a built operator (the ctor params are not exposed). */
    private fun latenessField(cell: Any, name: String): Windows.Lateness<*>? =
        cell.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(cell) as Windows.Lateness<*>?

    private fun roundTrip(x: java.io.Serializable): Any? {
        val bytes = java.io.ByteArrayOutputStream().also { java.io.ObjectOutputStream(it).use { o -> o.writeObject(x) } }
        return java.io.ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() }
    }

    @Test fun `waterline binds a WaterlineCell whose timeFn is a named serializable object reading the head`() {
        val cell = KernelCatalog.build("waterline", lat(5)).cell.shouldBeInstanceOf<WaterlineCell<*>>()
        cell.lateness.lateness shouldBe 5L
        cell.lateness.timeFn shouldBe EventTimeOfPair
        // [24-WL-01]: a named object, so it survives serialization as itself
        roundTrip(cell.lateness) shouldBe cell.lateness
        EventTimeOfPair(listOf(20L, "x")) shouldBe 20L
        // a waterline without `lateness` has nothing to subtract: refused, not defaulted
        assertThrows<UnsupportedCatalogBinding> { KernelCatalog.build("waterline", emptyMap()) }
    }

    @Test fun `a value-view over a two-source waterline reads the min-over-sources floor`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "b", "set-source", emptyMap())
        d.spawn("", "wl", "waterline", lat(5))
        d.spawn("", "v", "value-view", emptyMap())
        d.link("a", "wl"); d.link("b", "wl"); d.link("wl", "v")
        d.quiesce(BUDGET)
        d.readView("v") shouldBe i(0) // no source contributed: the view's zero
        d.apply("a", "add", list(i(20), s("x")))
        d.quiesce(BUDGET)
        d.readView("v") shouldBe i(15)
        d.apply("b", "add", list(i(50), s("y")))
        d.quiesce(BUDGET)
        // [24-WL-02]: min over sources (20, 50) − 5 = 15; max-over-sources would read 45
        d.readView("v") shouldBe i(15)
        d.deadLetters() shouldBe emptyList()
    }

    @Test fun `a tumbling window with lateness evicts passed windows and routes a below-floor add to late`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "wl", "waterline", lat(2))
        d.spawn("", "w", "window", tumbling(10, lateness = 2))
        d.spawn("", "v", "count-view", emptyMap())
        d.spawn("", "l", "set-view", emptyMap())
        // the three explicit links (t4od7-D1) plus the ordinary data path
        d.link("a", "wl")
        d.link("a", "w")
        d.link("wl", "w", inlet = "waterline")
        d.link("w", "v")
        d.link("w", "l", outlet = "late")
        for (e in listOf(list(i(3), s("x")), list(i(12), s("y")), list(i(15), s("z")))) {
            d.apply("a", "add", e)
            d.quiesce(BUDGET)
        }
        // floor = 15 − 2 = 13: window [0,10) ends at 10 <= 13 and is evicted; window
        // [10,20) ends at 20 and stays. (keyTime = start would also evict [10,20).)
        d.readView("v") shouldBe map("10" to i(2))
        d.apply("a", "add", list(i(4), s("q"))) // at 4 < floor 13: late
        d.quiesce(BUDGET)
        d.readView("v") shouldBe map("10" to i(2))
        d.readView("l") shouldBe list(list(i(4), s("q")))
        d.deadLetters() shouldBe emptyList()
        KernelCatalog.build("window", tumbling(10, lateness = 2)).cell.shouldBeInstanceOf<GroupByCell<*, *, *, *>>()
    }

    @Test fun `a tumbling window without lateness never closes (24-WL-11)`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "w", "window", tumbling(10))
        d.spawn("", "v", "count-view", emptyMap())
        d.link("a", "w"); d.link("w", "v")
        listOf(list(i(3), s("x")), list(i(15), s("z")), list(i(4), s("q"))).forEach { d.apply("a", "add", it) }
        d.quiesce(BUDGET)
        d.readView("v") shouldBe map("0" to i(2), "10" to i(1))
    }

    @Test fun `sliding window with lateness is refused naming WindowSlidingCell`() {
        val params = mapOf(
            "window" to map("kind" to s("sliding"), "size" to i(10), "slide" to i(5)),
            "lateness" to i(2),
        )
        assertThrows<UnsupportedCatalogBinding> { KernelCatalog.build("window", params) }
            .message!!.contains("WindowSlidingCell") shouldBe true
    }

    @Test fun `lateness on any other catalog type is refused`() {
        for (type in listOf("union", "group-by", "lookup-join", "set-view", "set-source", "filter")) {
            val params = lat(2) + (if (type == "filter") mapOf("fn" to s("even")) else emptyMap())
            assertThrows<UnsupportedCatalogBinding>("lateness on $type") { KernelCatalog.build(type, params) }
        }
        assertThrows<UnsupportedCatalogBinding> { KernelCatalog.build("waterline", mapOf("lateness" to i(-1))) }
    }

    @Test fun `the join family declares the same lateness on both inlets with the decided row time`() {
        val join = KernelCatalog.build("join", lat(3) + ("fn" to s("key-of"))).cell.shouldBeInstanceOf<JoinSetCell<*, *, *, *>>()
        val semi = KernelCatalog.build("semi-join", lat(3) + ("fn" to s("key-of"))).cell.shouldBeInstanceOf<SemiJoinCell<*, *, *>>()
        val inter = KernelCatalog.build("intersect", lat(3)).cell.shouldBeInstanceOf<IntersectSetCell<*>>()
        for (c in listOf(join, semi)) for (side in listOf("leftLateness", "rightLateness")) {
            latenessField(c, side) shouldBe Windows.Lateness(EventTimeOfRow, 3L)
        }
        for (side in listOf("leftLateness", "rightLateness")) {
            latenessField(inter, side) shouldBe Windows.Lateness(EventTimeOfPair, 3L)
        }
        // row time: an integer value, else the head of a list value
        EventTimeOfRow(listOf("k", 40L)) shouldBe 40L
        EventTimeOfRow(listOf("k", listOf(41L, "p"))) shouldBe 41L
        roundTrip(EventTimeOfRow) shouldBe EventTimeOfRow
        // without lateness nothing is declared ([24-WL-11])
        latenessField(KernelCatalog.build("join", mapOf("fn" to s("key-of"))).cell, "leftLateness") shouldBe null
    }

    @Test fun `a join with lateness routes a below-floor left row to lateLeft`() {
        val d = KernelDriver(0L)
        d.spawn("", "t", "set-source", emptyMap()) // [at, tick] elements drive the floor
        d.spawn("", "wl", "waterline", lat(0))
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "b", "set-source", emptyMap())
        d.spawn("", "j", "join", lat(0) + ("fn" to s("key-of")))
        d.spawn("", "v", "set-view", emptyMap())
        d.spawn("", "ll", "set-view", emptyMap())
        d.link("t", "wl")
        d.link("wl", "j", inlet = "waterline")
        d.link("a", "j", inlet = "left")
        d.link("b", "j", inlet = "right")
        d.link("j", "v")
        d.link("j", "ll", outlet = "lateLeft")
        d.apply("t", "add", list(i(100), s("tick")))
        d.quiesce(BUDGET)
        d.apply("a", "add", list(s("k1"), i(50)))                   // [k, at]: 50 < 100, late
        d.apply("a", "add", list(s("k2"), list(i(90), s("p"))))     // [k, [at, payload]]: 90 < 100, late
        d.apply("a", "add", list(s("k1"), i(120)))                  // admitted
        d.apply("b", "add", list(s("k1"), i(130)))                  // admitted
        d.quiesce(BUDGET)
        d.readView("v") shouldBe list(list(s("k1"), i(120), i(130)))
        (d.readView("ll") as Value.ListVal).items.toSet() shouldBe
            setOf(list(s("k1"), i(50)), list(s("k2"), list(i(90), s("p"))))
        d.deadLetters() shouldBe emptyList()
    }

    @Test fun `the batch oracle folds a lateness scenario late-filtered and eviction-restricted, never unfiltered`() {
        val sc = sc(
            listOf(
                c("a", "set-source"),
                c("wl", "waterline").copy(lateness = 2),
                CellSpec(
                    id = "w", type = "window",
                    window = civictech.concord.schema.WindowSpec(civictech.concord.schema.WindowKind.TUMBLING, 10),
                    lateness = 2,
                ),
                c("v", "count-view"),
            ),
            listOf(l("a", "wl"), l("a", "w"), l("wl", "w", inlet = "waterline"), l("w", "v")),
            listOf(ap("a", "add", list(i(15), s("z"))), ap("a", "add", list(i(4), s("q")))),
        )
        // computenet-t4od7.2: the oracle models lateness ([24-WL-10]). [4, q] trails [15, z]'s
        // floor (13) with no quiesce between, so it may be admitted or late-dropped, but its
        // window (0, end 10) is evicted by the final floor either way; the unfiltered fold
        // would keep window 0. The driver's read and the oracle agree on the filtered batch.
        bothAgree(sc, "v", map("10" to i(1)))
    }
}
