package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [DotModel] at model level — `ORA2 §MODEL-01` .. `ORA2 §MODEL-07`, `ORA2 §MODEL-12`.
 *
 * These are **not** the differential tests. BS-1/BS-3/BS-5's kernel halves — a real `OrMapCell`
 * mesh compared against this model — land with the sweep task. What is proven here is that the
 * model itself has the semantics those tests will rely on, because a differential run against a
 * model that got reset-remove wrong would report agreement about a shared mistake.
 *
 * Every case states its own dot order explicitly ([DotOrder.ranked]): at model level there is no
 * kernel instance to be isomorphic to, so the order is an input, and what is under test is what
 * the model does with it.
 */
class DotModelTest {

    private val a = SourceId("A")
    private val b = SourceId("B")
    private val c = SourceId("C")
    private val w = WriterId("w")

    /** A → rank 0, B → 1, C → 2: A loses every counter tie, C wins every one. */
    private val order = DotOrder.ranked(a, b, c)
    private val model = DotModel(order)

    private fun put(key: Any?, value: Any?) = ScriptEvent.Put(w, key, value)
    private fun remove(key: Any?) = ScriptEvent.RemoveKey(w, key)

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-01, ORA2 §MODEL-03, ORA2 §MODEL-04
    // -----------------------------------------------------------------------

    @Test
    fun `a single instance's puts and removes fold to its membership and values`() {
        val script = Script.of(a, put("k1", "v1"), put("k2", "v2"), put("k1", "v3"), remove("k2"))

        val state = model.stateOf(script, a)

        model.membership(state) shouldBe setOf("k1")
        model.value(state, "k1") shouldBe "v3"
        withClue("[24-TMAP-02]: a key whose only dots are tombstoned is ABSENT, not null-valued") {
            model.value(state, "k2") shouldBe null
        }
        model.evaluate(script) shouldBe ModelState.MapState(mapOf("k1" to "v3"))
    }

    @Test
    fun `a re-put by one instance covers its own earlier dot rather than leaving two live`() {
        val script = Script.of(a, put("k", "first"), put("k", "second"))

        val state = model.stateOf(script, a)

        // re-put over a live dot: retract del-dot 2, put-dot 3 (9sm.8-D5) — a re-put consumes
        // TWO counters, not one.
        state.liveDots("k").keys shouldBe setOf(ModelDot(3, a))
        withClue("the covered dot is TOMBSTONED, not deleted — that is what makes merge idempotent") {
            state.puts.getValue("k").keys shouldBe setOf(ModelDot(1, a), ModelDot(3, a))
            withClue("dot 1 is the covered put; dot 2 is the retract's OWN del-dot") {
                state.dels.getValue("k") shouldBe setOf(ModelDot(1, a), ModelDot(2, a))
            }
        }
    }

    /**
     * `ORA2 §MODEL-04` / BS-1. Neither instance has observed the other, so both dots are live at
     * the converged state and the **order** decides — not arrival, not slice position.
     */
    @Test
    fun `concurrent puts of one key both stay live and the dot order picks the value`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "fromA"), put("k", "fromA2"))),
                SourceScript(b, listOf(put("k", "fromB"))),
            ),
        )

        val converged = model.converged(script)

        withClue("both instances' dots survive: neither remove nor re-put observed the other") {
            // A's re-put consumes two counters (9sm.8-D5): retract del-dot 2, put-dot 3.
            converged.liveDots("k").keys shouldBe setOf(ModelDot(3, a), ModelDot(1, b))
        }
        withClue("counter 3 beats counter 1 before rank is ever consulted [24-TMAP-03]") {
            model.value(converged, "k") shouldBe "fromA2"
        }
    }

    /**
     * `ORA2 §MODEL-04`, BS-2's model half: a genuine counter **tie**, separated only by the
     * harness-supplied instance rank. This is the case `ORA2 §GEN-04` requires the generator to
     * produce rather than reach by luck, and the reason the model takes an order at all.
     */
    @Test
    fun `a counter tie is broken by instance rank and only by instance rank`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "fromA"))),
                SourceScript(b, listOf(put("k", "fromB"))),
            ),
        )

        val converged = model.converged(script)
        converged.liveDots("k").keys shouldBe setOf(ModelDot(1, a), ModelDot(1, b))

        withClue("A=0, B=1: the higher rank wins the tie") {
            model.value(converged, "k") shouldBe "fromB"
        }
        withClue("reverse the stated order and the SAME state resolves the other way") {
            DotModel(DotOrder.ranked(b, a)).value(converged, "k") shouldBe "fromA"
        }
    }

    @Test
    fun `slice order does not decide a tie — only the stated rank does`() {
        val fromA = SourceScript(a, listOf(put("k", "fromA")))
        val fromB = SourceScript(b, listOf(put("k", "fromB")))

        val oneWay = model.value(model.converged(Script(listOf(fromA, fromB))), "k")
        val otherWay = model.value(model.converged(Script(listOf(fromB, fromA))), "k")

        withClue("ORA2 §MODEL-04: no arrival order participates") {
            oneWay shouldBe "fromB"
            otherWay shouldBe "fromB"
        }
    }

    @Test
    fun `an unranked instance fails by name rather than defaulting to a plausible order`() {
        val script = Script(listOf(SourceScript(c, listOf(put("k", "v")))))
        val partial = DotModel(DotOrder.ranked(a, b))

        val failure = shouldThrow<IllegalStateException> { partial.value(partial.converged(script), "k") }

        failure.message!! shouldContain "No dot-order rank for source 'C'"
        failure.message!! shouldContain "ORA2 §MODEL-12"
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-05 reset-remove vs remove-all
    // -----------------------------------------------------------------------

    /**
     * BS-3, at model level: B removes having observed only A's **first** dot. A's second,
     * unobserved, dot survives — the key is present with `v2`. This is the exact case a
     * remove-all mutant (`ORA2 §CTL-03`) gets wrong, and the assertion below is what makes the
     * difference observable.
     */
    @Test
    fun `reset-remove tombstones only the dots the removing instance observed — add-wins`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"), put("k", "v2"))),
                SourceScript(
                    b,
                    listOf(remove("k")),
                    // B heard A's first event and nothing after it.
                    deliveries = listOf(Delivery(afterEvents = 0, from = a, throughEvents = 1)),
                ),
            ),
        )

        val converged = model.converged(script)

        withClue("[24-TMAP-04]: the unobserved dot survives the concurrent remove") {
            model.membership(converged) shouldBe setOf("k")
            model.value(converged, "k") shouldBe "v2"
            // A's own re-put (v1 -> v2) consumes two counters (9sm.8-D5): retract del-dot 2,
            // put-dot 3 — A's live dot is 3, not 2.
            converged.liveDots("k").keys shouldBe setOf(ModelDot(3, a))
        }
        withClue("what B DID observe is genuinely tombstoned — the remove is not a no-op") {
            // ModelDot(1, a) is A's covered first put, tombstoned twice over: by A's own
            // re-put retract del-dot ModelDot(2, a) (9sm.8-D5, folded in at A's convergence),
            // and by B's own del-dot ModelDot(1, b), minted for B's effective remove and
            // covering exactly what B observed (dot 1).
            converged.dels.getValue("k") shouldBe setOf(ModelDot(1, a), ModelDot(2, a), ModelDot(1, b))
        }
    }

    /**
     * The discriminating twin of the test above: with the delivery widened so B observes
     * **both** of A's dots, the same script leaves the key absent. Reset-remove and remove-all
     * differ only in what was observed, so a pair of cases that differ only in the delivery is
     * the honest way to show the model implements the former.
     */
    @Test
    fun `the same remove having observed both dots leaves the key absent`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"), put("k", "v2"))),
                SourceScript(b, listOf(remove("k")), listOf(Delivery(0, a, throughEvents = 2))),
            ),
        )

        val converged = model.converged(script)

        model.membership(converged).shouldBeEmpty()
        model.value(converged, "k") shouldBe null
    }

    @Test
    fun `a remove of a key with no live dot is a no-op`() {
        val script = Script.of(a, remove("never-put"), put("k", "v"), remove("k"), remove("k"))

        val state = model.stateOf(script, a)

        state.puts.keys shouldBe setOf("k")
        withClue("the second remove observed nothing live and left no trace") {
            // the FIRST remove(k) is effective (observes put(k)'s dot 1) and mints its own
            // del-dot 2 (9sm.8-D5); the SECOND remove(k) is a no-op — dot 1 is already covered,
            // so it mints nothing and consumes no counter.
            state.dels shouldBe mapOf("k" to setOf(ModelDot(1, a), ModelDot(2, a)))
        }
    }

    @Test
    fun `a re-put after an observed remove revives the key`() {
        val script = Script.of(a, put("k", "v1"), remove("k"), put("k", "v2"))

        val state = model.stateOf(script, a)

        model.membership(state) shouldBe setOf("k")
        model.value(state, "k") shouldBe "v2"
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-02 merge is commutative, associative, idempotent
    // -----------------------------------------------------------------------

    private fun sampleStates(): Triple<DotState, DotState, DotState> {
        val one = DotState.EMPTY.put("k", ModelDot(1, a), "a1").put("j", ModelDot(2, a), "a2")
        val two = DotState.EMPTY.put("k", ModelDot(1, b), "b1")
        // an effective remove now mints its own del-dot (9sm.8-D5); ModelDot(2, c) here is that
        // dot, hand-supplied since this state is built directly rather than through a script.
        val three = DotState.EMPTY.put("k", ModelDot(1, c), "c1").resetRemove("k", ModelDot(2, c))
        return Triple(one, two, three)
    }

    @Test
    fun `merge is commutative`() {
        val (one, two, _) = sampleStates()

        one.merge(two) shouldBe two.merge(one)
    }

    @Test
    fun `merge is associative`() {
        val (one, two, three) = sampleStates()

        one.merge(two).merge(three) shouldBe one.merge(two.merge(three))
    }

    @Test
    fun `merge is idempotent — a duplicate delivery changes nothing`() {
        val (one, two, three) = sampleStates()
        val merged = one.merge(two).merge(three)

        merged.merge(two) shouldBe merged
        merged.merge(merged) shouldBe merged
    }

    /**
     * `ORA2 §MODEL-02`'s point, not merely its letter: a tombstone that arrives **before** the
     * put it covers still covers it. This is why the state keeps both halves instead of deleting
     * covered dots — deleting would let the late put resurrect the key.
     */
    @Test
    fun `a tombstone merged before the put it covers still covers it`() {
        val putState = DotState.EMPTY.put("k", ModelDot(1, a), "v")
        val tombstoneOnly = DotState(dels = mapOf("k" to setOf(ModelDot(1, a))))

        val early = tombstoneOnly.merge(putState)
        val late = putState.merge(tombstoneOnly)

        early shouldBe late
        early.liveDots("k") shouldBe emptyMap<ModelDot, Any?>()
        early.membership().shouldBeEmpty()
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-06 observation advances only from deliveries
    // -----------------------------------------------------------------------

    @Test
    fun `without a delivery an instance observes nothing of its peer`() {
        val withoutDelivery = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"))),
                SourceScript(b, listOf(remove("k"))),
            ),
        )

        val converged = model.converged(withoutDelivery)

        withClue("B's remove observed nothing, so it covers nothing — A's dot is untouched") {
            model.membership(converged) shouldBe setOf("k")
            converged.dels shouldBe emptyMap<Any?, Set<ModelDot>>()
        }
    }

    @Test
    fun `delivery is transitive across a chain of instances`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"))),
                SourceScript(b, listOf(), listOf(Delivery(0, a, 1))),
                SourceScript(c, listOf(remove("k")), listOf(Delivery(0, b, 0))),
            ),
        )

        withClue("C heard A's dot through B and removed it") {
            model.membership(model.converged(script)).shouldBeEmpty()
        }
    }

    @Test
    fun `a cyclic delivery is refused by name rather than folded to something plausible`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v")), listOf(Delivery(1, b, 1))),
                SourceScript(b, listOf(put("k", "w")), listOf(Delivery(1, a, 1))),
            ),
        )

        val failure = shouldThrow<DotModel.CyclicDeliveryException> { model.converged(script) }

        failure.message!! shouldContain "Cyclic gossip deliveries"
    }

    @Test
    fun `a delivery naming more events than the sender has is refused at script construction`() {
        val failure = shouldThrow<IllegalArgumentException> {
            Script(
                listOf(
                    SourceScript(a, listOf(put("k", "v"))),
                    SourceScript(b, listOf(), listOf(Delivery(0, a, 5))),
                ),
            )
        }

        failure.message!! shouldContain "whose log holds only 1"
    }

    @Test
    fun `an instance cannot be delivered its own emissions`() {
        val failure = shouldThrow<IllegalArgumentException> {
            Script(listOf(SourceScript(a, listOf(put("k", "v")), listOf(Delivery(1, a, 1)))))
        }

        failure.message!! shouldContain "cannot be delivered its own emissions"
    }

    // -----------------------------------------------------------------------
    // Prefixes and convergence
    // -----------------------------------------------------------------------

    @Test
    fun `an instance's state at a prefix reflects only what it had applied by then`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"), put("k", "v2"))),
                SourceScript(b, listOf(put("j", "b1"), put("j2", "b2")), listOf(Delivery(2, a, 2))),
            ),
        )

        withClue("before its own first event, B holds nothing") {
            model.stateOf(script, b, prefix = 0) shouldBe DotState.EMPTY
        }
        withClue("after one event, B holds its own dot and has not yet heard A") {
            model.membership(model.stateOf(script, b, prefix = 1)) shouldBe setOf("j")
        }
        withClue("the delivery stated at afterEvents=2 lands as B passes that position") {
            model.membership(model.stateOf(script, b, prefix = 2)) shouldBe setOf("j", "j2", "k")
        }
    }

    /**
     * `ORA2 §CONV-02`'s model half: an instance that has heard everything reads the **converged**
     * table — not merely a table that agrees with some peer. B hears A, C hears B, so both hold
     * every dot; A never hears anyone and is deliberately left behind, which is what makes the
     * assertion about convergence rather than about the script being trivially uniform.
     */
    @Test
    fun `an instance that has heard everything reads the converged table`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "fromA"))),
                SourceScript(b, listOf(put("k", "fromB")), listOf(Delivery(0, a, 1))),
                SourceScript(c, emptyList(), listOf(Delivery(0, b, 1))),
            ),
        )

        val perInstance = model.perInstance(script)
        val converged = model.converged(script)

        perInstance.getValue(b) shouldBe converged
        perInstance.getValue(c) shouldBe converged
        withClue("A heard nothing and holds only its own dot") {
            perInstance.getValue(a) shouldNotBe converged
        }
        withClue("B's put observed A's dot, so only B's dot is live at the converged state") {
            model.entries(converged) shouldBe ModelState.MapState(mapOf("k" to "fromB"))
            // B's put observed A's dot as already live at k (the delivery lands before B's own
            // event runs), so B's put is itself a "re-put over a live dot": retract del-dot 1,
            // put-dot 2 (9sm.8-D5) — B's own live dot is 2, not 1.
            converged.liveDots("k").keys shouldBe setOf(ModelDot(2, b))
        }
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-07 purity
    // -----------------------------------------------------------------------

    @Test
    fun `evaluating one script twice yields equal results and leaves the script unchanged`() {
        fun build() = Script(
            listOf(
                SourceScript(a, listOf(put("k", "v1"), remove("k"), put("k", "v2"))),
                SourceScript(b, listOf(put("k", "vb"), put("j", "vj")), listOf(Delivery(1, a, 3))),
            ),
        )

        val subject = build()
        val untouchedTwin = build()

        val first = model.evaluate(subject)
        val second = model.evaluate(subject)

        first shouldBe second
        withClue("ORA2 §MODEL-07: evaluation must not mutate the script") {
            subject shouldBe untouchedTwin
        }
        withClue("a model with real content, so equality is not trivially satisfied") {
            first shouldNotBe ModelState.EMPTY_MAP
        }
    }

    @Test
    fun `a dot counter is 1-based per instance and refuses a zero`() {
        // put(k1) mints counter 1 (no live dot at k1 yet); remove(k1) is EFFECTIVE (k1's dot 1
        // is live) and mints its own del-dot at counter 2 (9sm.8-D5); put(k2) mints counter 3 —
        // a fresh key, no live dot, one counter.
        val script = Script.of(a, put("k1", "v"), remove("k1"), put("k2", "v"))

        val state = model.stateOf(script, a)
        state.puts.getValue("k2").keys shouldBe setOf(ModelDot(3, a))
        withClue("an EFFECTIVE remove now advances the counter too — it mints its own del-dot") {
            state.dels.getValue("k1") shouldBe setOf(ModelDot(1, a), ModelDot(2, a))
        }
        withClue("a dot counter is 1-based and refuses a zero") {
            shouldThrow<IllegalArgumentException> { ModelDot(0, a) }
        }
    }

    @Test
    fun `a delivery does not advance the receiving instance's dot counter`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(put("k", "a1"), put("k", "a2"), put("k", "a3"))),
                SourceScript(b, listOf(put("j", "b1")), listOf(Delivery(0, a, 3))),
            ),
        )

        withClue("B's first put mints counter 1 however many of A's dots it absorbed first") {
            model.stateOf(script, b).puts.getValue("j").keys shouldBe setOf(ModelDot(1, b))
        }
    }
}
