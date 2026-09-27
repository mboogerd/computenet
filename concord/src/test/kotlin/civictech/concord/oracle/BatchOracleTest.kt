package civictech.concord.oracle

import civictech.concord.oracle.Fx.apply
import civictech.concord.oracle.Fx.cell
import civictech.concord.oracle.Fx.corpus
import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.link
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.quiesce
import civictech.concord.oracle.Fx.s
import civictech.concord.oracle.Fx.scenario
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.WindowKind
import civictech.concord.schema.WindowSpec
import civictech.concord.value.Value
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Hand-computed fixtures for the batch oracle: one small input multiset per
 * operator/source with a known expected fold. Sets are rendered as sorted lists
 * (order-independence, Concord P2).
 */
class BatchOracleTest {

    // --- sources ------------------------------------------------------------

    @Test
    fun `set-source folds add-wins in file order`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("v", "set-view")),
            links = listOf(link("a", "v")),
            script = listOf(
                apply("a", "add", s("apple")),
                apply("a", "add", s("plum")),
                apply("a", "remove", s("apple")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(s("plum"))
    }

    @Test
    fun `counter-source folds increments minus decrements with times`() {
        val sc = scenario(
            cells = listOf(cell("n", "counter-source"), cell("v", "value-view")),
            links = listOf(link("n", "v")),
            script = listOf(apply("n", "increment", times = 50), apply("n", "decrement", times = 8)),
        )
        BatchOracle(sc).view("v") shouldBe i(42)
    }

    @Test
    fun `list-source keeps positional order`() {
        val sc = scenario(
            cells = listOf(cell("l", "list-source"), cell("v", "value-view")),
            links = listOf(link("l", "v")),
            script = listOf(apply("l", "append", s("a")), apply("l", "append", s("b")), apply("l", "append", s("c"))),
        )
        BatchOracle(sc).view("v") shouldBe list(s("a"), s("b"), s("c"))
    }

    @Test
    fun `map-source is last-writer-wins per key`() {
        val sc = scenario(
            cells = listOf(cell("m", "map-source"), cell("v", "map-view")),
            links = listOf(link("m", "v")),
            script = listOf(
                apply("m", "put", list(s("k1"), i(1))),
                apply("m", "put", list(s("k1"), i(2))), // overwrites k1
                apply("m", "put", list(s("k2"), i(9))),
                apply("m", "remove", s("k2")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("k1" to i(2))
    }

    @Test
    fun `ormap-source folds put-remove to the current key-value map`() {
        // Single-stream projection of the OR-map's dot algebra (BatchOracle's
        // `ormap-source` arm): one replica observes every dot it minted, so a `put`
        // covers the key's previous dot and a `remove` covers all of them — file-order
        // LWW with removal. k2 is written twice before its remove, so a fold that
        // dropped only the LAST dot would leave k2 present at 9.
        val sc = scenario(
            cells = listOf(cell("om", "ormap-source"), cell("v", "tagged-map-view")),
            links = listOf(link("om", "v")),
            script = listOf(
                apply("om", "put", list(s("k1"), i(1))),
                apply("om", "put", list(s("k1"), i(2))), // overwrites k1
                apply("om", "put", list(s("k2"), i(9))),
                apply("om", "put", list(s("k2"), i(10))),
                apply("om", "remove", s("k2")),
                apply("om", "put", list(s("k3"), s("z"))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("k1" to i(2), "k3" to s("z"))
    }

    // --- operators ----------------------------------------------------------

    @Test
    fun `union equals batch union of both source sets`() {
        // The 24-OP-UNION-01 pilot, computed by the oracle.
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("u", "union"), cell("v", "set-view"),
            ),
            links = listOf(link("a", "u", "left"), link("b", "u", "right"), link("u", "v")),
            script = listOf(
                apply("a", "add", s("apple")),
                apply("b", "add", s("pear")),
                apply("a", "add", s("plum")),
                apply("a", "remove", s("apple")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(s("pear"), s("plum"))
    }

    @Test
    fun `union is interleaving-independent`() {
        fun union(vararg script: civictech.concord.schema.Step) = BatchOracle(
            scenario(
                cells = listOf(
                    cell("a", "set-source"), cell("b", "set-source"), cell("u", "union"), cell("v", "set-view"),
                ),
                links = listOf(link("a", "u", "left"), link("b", "u", "right"), link("u", "v")),
                script = script.toList(),
            ),
        ).view("v")

        val forward = union(apply("a", "add", s("x")), apply("b", "add", s("y")), apply("a", "add", s("z")))
        val shuffled = union(apply("b", "add", s("y")), apply("a", "add", s("z")), apply("a", "add", s("x")))
        forward shouldBe shuffled
        forward shouldBe list(s("x"), s("y"), s("z"))
    }

    @Test
    fun `intersect equals batch intersection`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("x", "intersect"), cell("v", "set-view"),
            ),
            links = listOf(link("a", "x", "left"), link("b", "x", "right"), link("x", "v")),
            script = listOf(
                apply("a", "add", i(1)), apply("a", "add", i(2)), apply("a", "add", i(3)),
                apply("b", "add", i(2)), apply("b", "add", i(3)), apply("b", "add", i(4)),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(i(2), i(3))
    }

    @Test
    fun `filter passes only elements satisfying the predicate`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("f", "filter", fn = "even"), cell("v", "set-view")),
            links = listOf(link("a", "f"), link("f", "v")),
            script = (1..5).map { apply("a", "add", i(it.toLong())) },
        )
        BatchOracle(sc).view("v") shouldBe list(i(2), i(4))
    }

    @Test
    fun `map applies a transform element-wise over a set`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("m", "map", fn = "add(10)"), cell("v", "set-view")),
            links = listOf(link("a", "m"), link("m", "v")),
            script = listOf(apply("a", "add", i(1)), apply("a", "add", i(2)), apply("a", "add", i(3))),
        )
        BatchOracle(sc).view("v") shouldBe list(i(11), i(12), i(13))
    }

    @Test
    fun `map identity over a counter arm passes the scalar through`() {
        val sc = scenario(
            cells = listOf(cell("n", "counter-source"), cell("m", "map", fn = "identity"), cell("v", "value-view")),
            links = listOf(link("n", "m"), link("m", "v")),
            script = listOf(apply("n", "increment", times = 7)),
        )
        BatchOracle(sc).view("v") shouldBe i(7)
    }

    @Test
    fun `flatmap expands list elements folded into a set`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("fm", "flatmap", fn = "identity"), cell("v", "set-view")),
            links = listOf(link("a", "fm"), link("fm", "v")),
            script = listOf(apply("a", "add", list(i(1), i(2))), apply("a", "add", list(i(2), i(3)))),
        )
        BatchOracle(sc).view("v") shouldBe list(i(1), i(2), i(3))
    }

    @Test
    fun `combine-latest sums the latest of each inlet (glitch-free diamond)`() {
        // The 22-GF-DIAMOND-01 shape: n forked through two identity arms into a summing join.
        val sc = scenario(
            cells = listOf(
                cell("n", "counter-source"),
                cell("l", "map", fn = "identity"),
                cell("r", "map", fn = "identity"),
                cell("s", "combine-latest", fn = "sum"),
                cell("v", "value-view"),
            ),
            links = listOf(
                link("n", "l"), link("n", "r"),
                link("l", "s", "left"), link("r", "s", "right"), link("s", "v"),
            ),
            script = listOf(apply("n", "increment", times = 50)),
        )
        BatchOracle(sc).view("v") shouldBe i(100)
    }

    @Test
    fun `count is the cardinality of the set`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("c", "count"), cell("v", "value-view")),
            links = listOf(link("a", "c"), link("c", "v")),
            script = listOf(apply("a", "add", s("x")), apply("a", "add", s("y")), apply("a", "add", s("x"))),
        )
        BatchOracle(sc).view("v") shouldBe i(2)
    }

    @Test
    fun `group-by partitions by key and folds each group with count (v1 default)`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("g", "group-by", fn = "key-of"), cell("v", "count-view")),
            links = listOf(link("a", "g"), link("g", "v")),
            script = listOf(
                apply("a", "add", list(s("a"), i(1))),
                apply("a", "add", list(s("a"), i(2))),
                apply("a", "add", list(s("b"), i(3))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("a" to i(2), "b" to i(1))
    }

    @Test
    fun `join inner-joins two keyed streams on the shared key`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("j", "join", fn = "key-of"), cell("v", "set-view"),
            ),
            links = listOf(link("a", "j", "left"), link("b", "j", "right"), link("j", "v")),
            script = listOf(
                apply("a", "add", list(s("k1"), s("L1"))),
                apply("a", "add", list(s("k2"), s("L2"))),
                apply("b", "add", list(s("k1"), s("R1"))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(list(s("k1"), s("L1"), s("R1")))
    }

    @Test
    fun `semi-join keeps left elements whose key is present on the right`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("sj", "semi-join", fn = "key-of"), cell("v", "set-view"),
            ),
            links = listOf(link("a", "sj", "left"), link("b", "sj", "right"), link("sj", "v")),
            script = listOf(
                apply("a", "add", list(s("k1"), s("x"))),
                apply("a", "add", list(s("k2"), s("y"))),
                apply("b", "add", list(s("k1"), s("z"))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(list(s("k1"), s("x")))
    }

    @Test
    fun `counter increment amount comes from value (or a unit step)`() {
        val sc = scenario(
            cells = listOf(cell("n", "counter-source"), cell("v", "value-view")),
            links = listOf(link("n", "v")),
            // value:50 is a single +50; a bare unit step then adds 1 more.
            script = listOf(apply("n", "increment", i(50)), apply("n", "increment")),
        )
        BatchOracle(sc).view("v") shouldBe i(51)
    }

    @Test
    fun `lookup-join enriches each left element with the matched right value`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("lj", "lookup-join", fn = "key-of"), cell("v", "set-view"),
            ),
            links = listOf(link("a", "lj", "left"), link("b", "lj", "right"), link("lj", "v")),
            script = listOf(
                apply("a", "add", list(s("k1"), s("v1"))),
                apply("a", "add", list(s("k2"), s("v2"))), // no right match -> dropped (inner)
                apply("b", "add", list(s("k1"), s("d1"))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(list(list(s("k1"), s("v1")), s("d1")))
    }

    @Test
    fun `group-by sum folds the group's value components`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("g", "group-by", fn = "key-of", agg = "sum"), cell("v", "count-view")),
            links = listOf(link("a", "g"), link("g", "v")),
            script = listOf(
                apply("a", "add", list(s("a"), i(1))),
                apply("a", "add", list(s("a"), i(2))),
                apply("a", "add", list(s("b"), i(3))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("a" to i(3), "b" to i(3))
    }

    @Test
    fun `group-by max folds the group's largest value`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("g", "group-by", fn = "key-of", agg = "max"), cell("v", "count-view")),
            links = listOf(link("a", "g"), link("g", "v")),
            script = listOf(
                apply("a", "add", list(s("a"), i(1))),
                apply("a", "add", list(s("a"), i(9))),
                apply("a", "add", list(s("b"), i(3))),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("a" to i(9), "b" to i(3))
    }

    @Test
    fun `partition folds identically to its unpartitioned group-by twin`() {
        fun countByKey(type: String) = BatchOracle(
            scenario(
                cells = listOf(cell("a", "set-source"), cell("g", type, fn = "key-of"), cell("v", "count-view")),
                links = listOf(link("a", "g"), link("g", "v")),
                script = listOf(
                    apply("a", "add", list(s("a"), i(1))),
                    apply("a", "add", list(s("a"), i(2))),
                    apply("a", "add", list(s("b"), i(3))),
                ),
            ),
        ).view("v")
        countByKey("partition") shouldBe countByKey("group-by")
        countByKey("partition") shouldBe map("a" to i(2), "b" to i(1))
    }

    // --- window (M11.6 "windowing = key derivation", 24-OP-WINDOW-01/02) ----

    @Test
    fun `window tumbling folds each event into its composite bucket key (default count)`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"),
                cell("w", "window", window = WindowSpec(kind = WindowKind.TUMBLING, size = 10)),
                cell("v", "count-view"),
            ),
            links = listOf(link("a", "w"), link("w", "v")),
            script = listOf(
                apply("a", "add", list(i(3), s("x"))),
                apply("a", "add", list(i(9), s("y"))),
                apply("a", "add", list(i(17), s("z"))),
            ),
        )
        // bucket(at) = floorDiv(at, 10) * 10 — 3 and 9 share bucket 0, 17 falls in bucket 10.
        BatchOracle(sc).view("v") shouldBe map("0" to i(2), "10" to i(1))
    }

    @Test
    fun `window tumbling sum updates on late elements and retractions (windows never close)`() {
        // Same fixture as kernel WindowingTest's "tumbling window sums update on
        // late elements and retractions" — the oracle must agree with the real
        // kernel binding element for element.
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"),
                cell("w", "window", agg = "sum", window = WindowSpec(kind = WindowKind.TUMBLING, size = 10)),
                cell("v", "count-view"),
            ),
            links = listOf(link("a", "w"), link("w", "v")),
            script = listOf(
                apply("a", "add", list(i(3), i(5))),
                apply("a", "add", list(i(17), i(7))),
                apply("a", "add", list(i(8), i(2))), // late element: an ordinary add, windows never close
                apply("a", "remove", list(i(3), i(5))), // a retraction flows into the window aggregate
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("0" to i(2), "10" to i(7))
    }

    @Test
    fun `window sliding expands each event into every window it falls in, sum aggregator`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"),
                cell("w", "window", agg = "sum", window = WindowSpec(kind = WindowKind.SLIDING, size = 10, slide = 5)),
                cell("v", "count-view"),
            ),
            links = listOf(link("a", "w"), link("w", "v")),
            script = listOf(
                apply("a", "add", list(i(3), i(5))), // Windows.sliding(10,5)(3) = [-5, 0]
                apply("a", "add", list(i(12), i(7))), // Windows.sliding(10,5)(12) = [5, 10]
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("-5" to i(5), "0" to i(5), "5" to i(7), "10" to i(7))
    }

    @Test
    fun `quorum-set admits elements met by k of n live sources`() {
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("c", "set-source"),
                cell("q", "quorum-set", k = 2), cell("v", "set-view"),
            ),
            links = listOf(link("a", "q"), link("b", "q"), link("c", "q"), link("q", "v")),
            script = listOf(
                apply("a", "add", i(1)), apply("a", "add", i(2)),
                apply("b", "add", i(2)), apply("b", "add", i(3)),
                apply("c", "add", i(2)),
            ),
        )
        // 2 is asserted by all three sources (>=2); 1 and 3 by one each (<2).
        BatchOracle(sc).view("v") shouldBe list(i(2))
    }

    @Test
    fun `keyed-set folds keyed upserts to the current elements`() {
        val sc = scenario(
            cells = listOf(cell("ks", "keyed-set"), cell("v", "set-view")),
            links = listOf(link("ks", "v")),
            script = listOf(
                apply("ks", "put", list(s("k1"), s("x"))),
                apply("ks", "put", list(s("k1"), s("y"))), // last-writer-wins: k1 -> y
                apply("ks", "put", list(s("k2"), s("z"))),
                apply("ks", "remove", s("k1")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(s("z"))
    }

    @Test
    fun `list-source honours insert set and remove-at by index`() {
        val sc = scenario(
            cells = listOf(cell("l", "list-source"), cell("v", "list-view")),
            links = listOf(link("l", "v")),
            script = listOf(
                apply("l", "append", s("a")),
                apply("l", "append", s("c")),
                apply("l", "insert", list(i(1), s("b"))), // [a, b, c]
                apply("l", "set", list(i(0), s("A"))),     // [A, b, c]
                apply("l", "remove-at", i(2)),             // [A, b]
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(s("A"), s("b"))
    }

    @Test
    fun `presence-count folds per-element live-source-link counts, not scalar cardinality`() {
        // The 24-OP-PRESENCE-01 pilot's shape: two set sources fan into one
        // presence-count. Hand-computed: "p" is currently asserted by BOTH
        // sources (a added it and never withdrew it; b added it) -> count 2.
        // "q" was asserted then withdrawn by "a" and never asserted by "b" ->
        // its live-source-link count drops to 0 -> group-death, absent from
        // the map entirely (not emitted as a zero entry).
        val sc = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("p", "presence-count"), cell("v", "map-view"),
            ),
            links = listOf(link("a", "p"), link("b", "p"), link("p", "v")),
            script = listOf(
                apply("a", "add", s("p")),
                apply("a", "add", s("q")),
                apply("b", "add", s("p")),
                apply("a", "remove", s("q")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("p" to i(2))
    }

    @Test
    fun `presence-count group-death removes an element once every asserting link withdraws it`() {
        // Single source, two adds/one remaining after a remove: the element
        // that both sources drop entirely never appears; the one still
        // asserted by its one live link counts 1.
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("p", "presence-count"), cell("v", "map-view")),
            links = listOf(link("a", "p"), link("p", "v")),
            script = listOf(apply("a", "add", s("x")), apply("a", "add", s("y")), apply("a", "remove", s("x"))),
        )
        BatchOracle(sc).view("v") shouldBe map("y" to i(1))
    }

    @Test
    fun `all view values enumerates every view cell`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("v1", "set-view"), cell("v2", "count-view")),
            links = listOf(link("a", "v1"), link("a", "v2")),
            script = listOf(apply("a", "add", s("x")), apply("a", "add", s("y"))),
        )
        val all = BatchOracle(sc).allViewValues()
        all.keys shouldBe setOf("v1", "v2")
        all["v1"] shouldBe list(s("x"), s("y"))
    }

    @Test
    fun `late-connected topology is folded (connect step adds a link)`() {
        val sc = scenario(
            cells = listOf(cell("a", "set-source"), cell("v", "set-view")),
            links = emptyList(),
            script = listOf(
                civictech.concord.schema.ConnectStep(from = "a", to = "v"),
                apply("a", "add", s("x")),
            ),
        )
        BatchOracle(sc).view("v") shouldBe list(s("x"))
    }

    // --- lateness (spec 24 §Lateness and waterlines, t4od7-D7) ---------------

    private fun wlScenario(lateness: Long, script: List<civictech.concord.schema.Step>, vararg sources: String) = scenario(
        cells = sources.map { cell(it, "set-source") } +
            listOf(cell("wl", "waterline", lateness = lateness), cell("f", "value-view")),
        links = sources.map { link(it, "wl") } + link("wl", "f"),
        script = script,
    )

    private fun pair(at: Long, tag: String) = list(i(at), s(tag))

    @Test
    fun `waterline floor is the running max of min-over-sources, and a low joiner leaves it unchanged (24-WL-20)`() {
        // The spec's own worked example, lateness 5: A 40, C 2, A 100, C 38, C 45 -> 40.
        val script = listOf(
            apply("a", "add", pair(40, "a1")),
            apply("c", "add", pair(2, "c1")),   // candidate -3 < 35: unchanged
            apply("a", "add", pair(100, "a2")), // candidate -3: unchanged
            apply("c", "add", pair(38, "c2")),  // candidate 33 < 35: unchanged
            apply("c", "add", pair(45, "c3")),  // candidate 40: the first rise C takes part in
        )
        BatchOracle(wlScenario(5, script, "a", "c")).view("f") shouldBe i(40)
        // After A 40 alone the floor is 35, and C's low join does not pull it back.
        BatchOracle(wlScenario(5, script.take(2), "a", "c")).view("f") shouldBe i(35)
    }

    @Test
    fun `waterline reads 0 before any source contributes, and a remove never lowers a maximum`() {
        BatchOracle(wlScenario(5, emptyList(), "a")).view("f") shouldBe i(0)
        val script = listOf(apply("a", "add", pair(50, "x")), apply("a", "remove", pair(50, "x")))
        BatchOracle(wlScenario(5, script, "a")).view("f") shouldBe i(45)
    }

    @Test
    fun `disconnecting a contributing source retires it, and the floor rises to the remaining source's promise (24-WL-12)`() {
        val script = listOf(
            apply("b", "add", pair(20, "b1")),
            quiesce(),
            apply("a", "add", pair(60, "a1")), // min(60, 20) - 0 = 20
            quiesce(),
            civictech.concord.schema.DisconnectStep(from = "b", to = "wl"),
        )
        BatchOracle(wlScenario(0, script.dropLast(1), "a", "b")).view("f") shouldBe i(20)
        BatchOracle(wlScenario(0, script, "a", "b")).view("f") shouldBe i(60)
    }

    @Test
    fun `restarting a rebaseline-source retires its epoch, the fresh epoch contributes from scratch (24-WL-13)`() {
        val sc = scenario(
            cells = listOf(
                cell("r", "rebaseline-source"), cell("a", "set-source"),
                cell("wl", "waterline", lateness = 0), cell("f", "value-view"),
            ),
            links = listOf(link("r", "wl"), link("a", "wl"), link("wl", "f")),
            script = listOf(
                apply("r", "add", pair(10, "r1")),
                apply("a", "add", pair(50, "a1")), // min(10, 50) = 10
                quiesce(),
                civictech.concord.schema.RestartStep(on = "r"), // r's stale 10 no longer gates: 50
                quiesce(),
                apply("r", "add", pair(30, "r2")), // fresh epoch joins below the floor: unchanged ([24-WL-20])
            ),
        )
        BatchOracle(sc).view("f") shouldBe i(50)
    }

    private fun windowScenario(script: List<civictech.concord.schema.Step>) = scenario(
        cells = listOf(
            cell("a", "set-source"),
            cell("wl", "waterline", lateness = 2),
            cell("w", "window", window = WindowSpec(kind = WindowKind.TUMBLING, size = 10), lateness = 2),
            cell("v", "count-view"),
            cell("l", "set-view"),
        ),
        links = listOf(
            link("a", "wl"), link("a", "w"), link("wl", "w", inlet = "waterline"),
            link("w", "v"), link("w", "l", outlet = "late"),
        ),
        script = script,
    )

    @Test
    fun `an add below the floor as of the preceding quiesce is late-dropped onto the late outlet, never into the window`() {
        val sc = windowScenario(
            listOf(
                apply("a", "add", pair(15, "z")), // floor 13
                quiesce(),
                apply("a", "add", pair(4, "q")),  // 4 < 13: late
                apply("a", "add", pair(14, "y")), // 14 >= 13: admitted into window 10
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("10" to i(2))
        BatchOracle(sc).view("l") shouldBe list(pair(4, "q"))
    }

    @Test
    fun `an add between the preceding quiesce's floor and its block's floor is refused, naming the step`() {
        val sc = windowScenario(
            listOf(
                apply("a", "add", pair(15, "z")), // floor 13 — not yet certainly delivered below
                apply("a", "add", pair(12, "q")), // 12 < 13 with no quiesce between: order-dependent
            ),
        )
        val refusal = assertThrows<OracleUnsupported> { BatchOracle(sc).view("v") }.message!!
        refusal shouldContain "script step 2"
        refusal shouldContain "insert a `quiesce` before this add"
        assertThrows<OracleUnsupported> { BatchOracle(sc).view("l") }.message!! shouldContain "script step 2"
        // With the quiesce the kernel has certainly delivered floor 13 first: late.
        val settled = windowScenario(listOf(sc.script[0], quiesce(), sc.script[1]))
        BatchOracle(settled).view("v") shouldBe map("10" to i(1))
        BatchOracle(settled).view("l") shouldBe list(pair(12, "q"))
    }

    @Test
    fun `an order-dependent add whose window the final floor evicts folds either way, but not on the late outlet`() {
        val sc = windowScenario(
            listOf(
                apply("a", "add", pair(9, "y")),  // floor 7, not yet certainly delivered below
                apply("a", "add", pair(3, "x")),  // 3 < 7 with no quiesce between: order-dependent
                quiesce(),
                apply("a", "add", pair(30, "z")), // floor 28 evicts window 0 whichever way [3, x] went
                quiesce(),
            ),
        )
        BatchOracle(sc).view("v") shouldBe map("30" to i(1))
        assertThrows<OracleUnsupported> { BatchOracle(sc).view("l") }.message!! shouldContain "script step 2"
    }

    @Test
    fun `an add is never late on a floor that only adds applied after it raise (one host, FIFO)`() {
        // [3, x] is applied before [11, y]: 11's floor (9) is queued behind 3's delivery.
        val sc = windowScenario(listOf(apply("a", "add", pair(3, "x")), apply("a", "add", pair(11, "y"))))
        BatchOracle(sc).view("v") shouldBe map("0" to i(1), "10" to i(1))
        BatchOracle(sc).view("l") shouldBe list()
    }

    @Test
    fun `24-WL-LATE-01's script folds to its golden count view (24-WL-10 window restriction)`() {
        val sc = corpus("24-data-cells/24-WL-LATE-01.yaml")
        BatchOracle(sc).view("v") shouldBe map("20" to i(3))
        BatchOracle(sc).view("l") shouldBe list(pair(4, "late"), pair(19, "late2"), pair(22, "w"))
    }

    @Test
    fun `24-WL-JOIN-01's script folds to its golden pairs and late rows (24-WL-10 join restriction)`() {
        val sc = corpus("24-data-cells/24-WL-JOIN-01.yaml")
        BatchOracle(sc).view("v") shouldBe list(list(s("k2"), i(30), i(31)))
        BatchOracle(sc).view("ll") shouldBe list(list(s("k1"), i(20)))
        BatchOracle(sc).view("lr") shouldBe list(list(s("k2"), i(15)))
    }

    @Test
    fun `semi-join and intersect with lateness drop rows strictly below the final floor`() {
        fun sc(type: String, rows: (Long, String) -> Value) = scenario(
            cells = listOf(
                cell("a", "set-source"), cell("b", "set-source"), cell("t", "set-source"),
                cell("wl", "waterline", lateness = 0),
                cell("j", type, fn = "key-of", lateness = 0),
                cell("v", "set-view"),
            ),
            links = listOf(
                link("a", "j", inlet = "left"), link("b", "j", inlet = "right"),
                link("t", "wl"), link("wl", "j", inlet = "waterline"), link("j", "v"),
            ),
            script = listOf(
                apply("a", "add", rows(10, "k1")), apply("b", "add", rows(10, "k1")),
                apply("a", "add", rows(30, "k2")), apply("b", "add", rows(30, "k2")),
                quiesce(),
                apply("t", "add", pair(20, "t")), // floor 20: the k1 rows (10) are evicted
                quiesce(),
            ),
        )
        BatchOracle(sc("semi-join") { at, k -> list(s(k), i(at)) }).view("v") shouldBe list(list(s("k2"), i(30)))
        BatchOracle(sc("intersect") { at, k -> list(i(at), s(k)) }).view("v") shouldBe list(list(i(30), s("k2")))
    }

    @Test
    fun `every 24-WL corpus golden on a lateness cone equals the oracle's fold`() {
        // A cross-check of the model against every waterline scenario the feature landed,
        // including the ones that do not (yet) carry incremental-equals-batch themselves.
        val dir = java.io.File("corpus/24-data-cells")
        val files = dir.listFiles { f -> f.name.startsWith("24-WL-") && f.name.endsWith(".yaml") }!!.sortedBy { it.name }
        files.size shouldBe 10
        var compared = 0
        for (f in files) {
            val sc = corpus("24-data-cells/${f.name}")
            for (check in sc.checks.filterIsInstance<civictech.concord.schema.FinalView>()) {
                withClue("${sc.id} ${check.view}") {
                    Values.equalForView(BatchOracle(sc).view(check.view), check.expected, viewType(sc, check.view)) shouldBe true
                }
                compared++
            }
        }
        compared shouldBe 16
    }

    private fun viewType(sc: civictech.concord.schema.Scenario, id: String) =
        sc.graph!!.cells.single { it.id == id }.type

    @Test
    fun `lateness outside the model is refused, never folded unfiltered`() {
        // sliding + lateness (the catalog refuses it too)
        val sliding = windowScenario(listOf(apply("a", "add", pair(15, "z")))).let { sc ->
            sc.copy(graph = sc.graph!!.copy(cells = sc.graph!!.cells.map {
                if (it.id == "w") it.copy(window = WindowSpec(kind = WindowKind.SLIDING, size = 10, slide = 5)) else it
            }))
        }
        assertThrows<OracleUnsupported> { BatchOracle(sliding).view("v") }.message!! shouldContain "sliding"
        // a non-identity operator between a source and the waterline
        val mapped = scenario(
            cells = listOf(cell("a", "set-source"), cell("m", "map", fn = "key-of"),
                cell("wl", "waterline", lateness = 0), cell("f", "value-view")),
            links = listOf(link("a", "m"), link("m", "wl"), link("wl", "f")),
            script = listOf(apply("a", "add", pair(1, "x"))),
        )
        assertThrows<OracleUnsupported> { BatchOracle(mapped).view("f") }.message!! shouldContain "identity"
        // a branch whose cone reaches no lateness still folds
        val sc = windowScenario(listOf(apply("a", "add", pair(15, "z"))))
        val plain = sc.copy(graph = sc.graph!!.copy(
            cells = sc.graph!!.cells + cell("plain", "set-view"),
            links = sc.graph!!.links + link("a", "plain"),
        ))
        BatchOracle(plain).view("plain") shouldBe list(pair(15, "z"))
    }

    @Test
    fun `equal integer golden is not equal to a real`() {
        // Guards the IntVal/RealVal distinction the value model insists on.
        (i(100) == Value.RealVal(100.0)) shouldBe false
    }
}
