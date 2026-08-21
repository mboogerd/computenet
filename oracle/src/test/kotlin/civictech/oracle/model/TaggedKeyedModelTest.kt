package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [KeyedReputModel] (`[ORA2-MODEL-08]`), [MergeableGroupByModel] (`[ORA2-MODEL-09]`) and
 * [PnCounterConvergenceModel] (`[ORA2-MODEL-10]`) at model level.
 *
 * As in [DotModelTest], these are the *model's* halves of BS-10, BS-11 and BS-12; the kernel
 * halves — the same properties driven through real cells — land with the sweep task.
 */
class TaggedKeyedModelTest {

    private val a = SourceId("A")
    private val b = SourceId("B")
    private val c = SourceId("C")
    private val w = WriterId("w")
    private val v = WriterId("v")

    // -----------------------------------------------------------------------
    // [ORA2-MODEL-08] — KeyedSetCell per-key re-put atomicity
    // -----------------------------------------------------------------------

    /**
     * BS-10 at model level: a re-put key binds **exactly one** element at every prefix — never
     * two (the retract has not landed), never zero (the add has not). Walking every prefix is the
     * point; asserting only the final table would pass against a model that tore in the middle.
     */
    @Test
    fun `BS-10 a re-put key binds exactly one element at every script prefix`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e1"),
                ScriptEvent.Put(w, "k", "e2"),
                ScriptEvent.Put(v, "k", "e3"),
                ScriptEvent.Put(w, "k", "e4"),
            ),
        )

        val prefixes = KeyedReputModel.bindingsAtEachPrefix(slice)

        prefixes.size shouldBe slice.events.size + 1
        withClue("before the first put the key is simply absent") {
            prefixes.first().containsKey("k") shouldBe false
        }
        prefixes.drop(1).forEachIndexed { index, bindings ->
            withClue("prefix ${index + 1}: $bindings") {
                bindings.keys shouldBe setOf("k")
                bindings.getValue("k") shouldBe listOf("e1", "e2", "e3", "e4")[index]
            }
        }
    }

    @Test
    fun `a removed key is absent, and re-putting it binds one element again`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e1"),
                ScriptEvent.RemoveKey(w, "k"),
                ScriptEvent.Put(w, "k", "e2"),
            ),
        )

        val prefixes = KeyedReputModel.bindingsAtEachPrefix(slice)

        prefixes[1] shouldBe mapOf("k" to "e1")
        withClue("[24-SET-03]: the removed key is ABSENT, not present with a stale element") {
            prefixes[2] shouldBe emptyMap()
        }
        prefixes[3] shouldBe mapOf("k" to "e2")
    }

    @Test
    fun `two keys binding the same element stay two keys — the atomicity observable keeps the key`() {
        val slice = SourceScript(a, listOf(ScriptEvent.Put(w, "k1", "same"), ScriptEvent.Put(w, "k2", "same")))

        withClue("KeyedSetSourceModel's element set would collapse these to one; this model must not") {
            KeyedReputModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k1" to "same", "k2" to "same"))
        }
    }

    @Test
    fun `re-putting the identical element and removing an unheld key are both no-ops`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e"),
                ScriptEvent.Put(w, "k", "e"),
                ScriptEvent.RemoveKey(w, "never-put"),
            ),
        )

        KeyedReputModel.bindingsAtEachPrefix(slice).drop(1).forEach { it shouldBe mapOf("k" to "e") }
    }

    // -----------------------------------------------------------------------
    // [ORA2-MODEL-09] — MergeableGroupByCell grows and never retracts
    // -----------------------------------------------------------------------

    /** Group by string length; accumulate each element as `1L`; merge by addition. */
    private val countingGroupBy = MergeableGroupByModel(
        keyOf = { element -> element.toString().length },
        accumulate = { 1L },
        merge = { left, right -> (left as Long) + (right as Long) },
    )

    /**
     * BS-11 at model level: removals feed through and the aggregate **does not shrink**. The
     * assertion is over every prefix, so a model that retracted at any point would fail even if
     * its final answer happened to agree.
     */
    @Test
    fun `BS-11 removals do not retract — the aggregate never shrinks`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Add(w, "xx"),
                ScriptEvent.Add(w, "yy"),
                ScriptEvent.Remove(w, "xx"),
                ScriptEvent.Remove(w, "yy"),
            ),
        )

        val prefixes = countingGroupBy.aggregatesAtEachPrefix(slice)

        prefixes[2] shouldBe mapOf(2 to 2L)
        withClue("[ORA2-MODEL-09]: the absence of retraction IS the specification") {
            prefixes[3] shouldBe mapOf(2 to 2L)
            prefixes[4] shouldBe mapOf(2 to 2L)
        }
        prefixes.zipWithNext().forEach { (earlier, later) ->
            withClue("monotone: $earlier then $later") {
                earlier.forEach { (key, value) ->
                    (later.getValue(key) as Long) shouldBeGreaterThanOrEqual (value as Long)
                }
            }
        }
    }

    /**
     * The contrast that makes the previous test a *statement* rather than an omission:
     * [GroupByModel], the retracting sibling, gives a different answer for the same script — the
     * key dies with its last live element (`[24-OP-GROUPBY-02]`). ORA2 asserts the divergence
     * explicitly rather than leaving it implicit.
     */
    @Test
    fun `BS-11 the retracting group-by disagrees, and that disagreement is the specified difference`() {
        val slice = SourceScript(
            a,
            listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Add(w, "yy"), ScriptEvent.Remove(w, "xx")),
        )
        val retracting = GroupByModel(ElementKey { it.toString().length }, Aggregates.count())

        val mergeable = countingGroupBy.evaluate(slice)
        val nonMergeable = retracting.evaluate(listOf(SetSourceModel.evaluate(slice)))

        mergeable shouldBe ModelState.MapState(mapOf(2 to 2L))
        withClue("GroupByCell recomputes from live membership; MergeableGroupByCell cannot un-merge") {
            nonMergeable shouldBe ModelState.MapState(mapOf(2 to 1L))
        }
    }

    @Test
    fun `a re-add folds again, because the cell folds every arriving add`() {
        val slice = SourceScript(
            a,
            listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Remove(w, "xx"), ScriptEvent.Add(w, "xx")),
        )

        withClue("per add event, not per distinct element — a counted accumulator can tell") {
            countingGroupBy.evaluate(slice) shouldBe ModelState.MapState(mapOf(2 to 2L))
        }
    }

    @Test
    fun `an idempotent accumulator is unaffected by the re-add the counted one sees`() {
        val maxGroupBy = MergeableGroupByModel(
            keyOf = { element -> element.toString().length },
            accumulate = { element -> element.toString() },
            merge = { left, right -> maxOf(left as String, right as String) },
        )
        val slice = SourceScript(a, listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Add(w, "xx")))

        maxGroupBy.evaluate(slice) shouldBe ModelState.MapState(mapOf(2 to "xx"))
    }

    // -----------------------------------------------------------------------
    // [ORA2-MODEL-10] — PnCounterCell per-source totals, pointwise max
    // -----------------------------------------------------------------------

    /**
     * BS-12 at model level: three replicas increment concurrently, and the converged reading is
     * the per-source pointwise-max sum — every source counted exactly once.
     */
    @Test
    fun `BS-12 concurrent replicas converge on the per-source pointwise-max total`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(ScriptEvent.Increment(w, 5), ScriptEvent.Increment(v, 3))),
                SourceScript(b, listOf(ScriptEvent.Increment(w, 10), ScriptEvent.Decrement(w, 4))),
                SourceScript(c, listOf(ScriptEvent.Decrement(w, 1))),
            ),
        )

        val converged = PnCounterConvergenceModel.converged(script)

        withClue("two writers into one instance share its slot — one cumulative total, not two") {
            converged.incs shouldBe mapOf(a to 8L, b to 10L)
        }
        converged.decs shouldBe mapOf(b to 4L, c to 1L)
        converged.total() shouldBe 13L
        PnCounterConvergenceModel.evaluate(script) shouldBe ModelState.ScalarState(13L)
    }

    @Test
    fun `pointwise max is commutative, associative and idempotent`() {
        val one = PnCounterState(incs = mapOf(a to 5L), decs = mapOf(a to 1L))
        val two = PnCounterState(incs = mapOf(a to 9L, b to 2L))
        val three = PnCounterState(decs = mapOf(b to 7L))

        one.merge(two) shouldBe two.merge(one)
        one.merge(two).merge(three) shouldBe one.merge(two.merge(three))
        withClue("idempotence is what terminates a mesh echo — the duplicate changes nothing") {
            one.merge(two).merge(two) shouldBe one.merge(two)
        }
    }

    /**
     * The property that distinguishes `PnCounterCell` from `CounterCell`, and the reason the
     * convergence model exists beside [PnCounterSourceModel]: hearing one source's total twice
     * must not double it. Plain addition — `CounterDelta`'s merge — would read 10 here.
     */
    @Test
    fun `a total heard twice is absorbed, not added`() {
        val once = PnCounterState(incs = mapOf(a to 5L))

        once.merge(once).total() shouldBe 5L
        withClue("a stale, smaller total loses to the fresher one rather than overwriting it") {
            once.merge(PnCounterState(incs = mapOf(a to 3L))).total() shouldBe 5L
            PnCounterState(incs = mapOf(a to 3L)).merge(once).total() shouldBe 5L
        }
    }

    @Test
    fun `a negative amount is refused by name rather than folded into a plausible number`() {
        val slice = SourceScript(a, listOf(ScriptEvent.Increment(w, -5)))

        val failure = shouldThrow<PnCounterConvergenceModel.NonMonotonicAmountException> {
            PnCounterConvergenceModel.fold(slice)
        }

        failure.message!! shouldContain "refuses a negative increment"
    }

    @Test
    fun `an instance the script never drives contributes nothing`() {
        PnCounterConvergenceModel.fold(SourceScript(a, emptyList())) shouldBe PnCounterState.EMPTY
        PnCounterConvergenceModel.converged(Script.EMPTY).total() shouldBe 0L
    }

    // -----------------------------------------------------------------------
    // [ORA2-MODEL-07] purity, for all three
    // -----------------------------------------------------------------------

    @Test
    fun `each model evaluates one script twice to equal results and leaves it unchanged`() {
        fun keyed() = SourceScript(a, listOf(ScriptEvent.Put(w, "k", "e1"), ScriptEvent.Put(w, "k", "e2")))
        fun grouped() = SourceScript(a, listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Remove(w, "xx")))
        fun counted() = Script(
            listOf(
                SourceScript(a, listOf(ScriptEvent.Increment(w, 4))),
                SourceScript(b, listOf(ScriptEvent.Decrement(w, 1))),
            ),
        )

        val keyedSubject = keyed()
        val groupedSubject = grouped()
        val countedSubject = counted()

        KeyedReputModel.evaluate(keyedSubject) shouldBe KeyedReputModel.evaluate(keyedSubject)
        countingGroupBy.evaluate(groupedSubject) shouldBe countingGroupBy.evaluate(groupedSubject)
        PnCounterConvergenceModel.evaluate(countedSubject) shouldBe
            PnCounterConvergenceModel.evaluate(countedSubject)

        withClue("[ORA2-MODEL-07]: evaluation must not mutate the script") {
            keyedSubject shouldBe keyed()
            groupedSubject shouldBe grouped()
            countedSubject shouldBe counted()
        }
    }
}
