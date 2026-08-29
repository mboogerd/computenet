package civictech.concord.oracle

import civictech.concord.oracle.Fx.apply
import civictech.concord.oracle.Fx.cell
import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.link
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import civictech.concord.oracle.Fx.scenario
import civictech.concord.schema.WindowKind
import civictech.concord.schema.WindowSpec
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
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

    @Test
    fun `equal integer golden is not equal to a real`() {
        // Guards the IntVal/RealVal distinction the value model insists on.
        (i(100) == Value.RealVal(100.0)) shouldBe false
    }
}
