package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The batch denotation of each operator in computenet-4ru.5.1's slice of `[ORA1-MODEL-02]`:
 * `SetCell`, `KeyedSetCell`, `CounterCell`, `PnCounterCell`, `FilterCell`,
 * `FlatMapSetCell`/`mapSet`, `UnionSetCell`, `CountCell`, `PresenceCountCell`,
 * `QuorumSetCell`.
 *
 * Model-level throughout: a script or a set of input states goes in, a [ModelState] comes
 * out, and no kernel cell is executed (`[ORA1-MODEL-01]`).
 */
class SetOperatorModelTest {

    private val w = WriterId("w")
    private val source = SourceId("s")

    private fun sliceOf(vararg events: ScriptEvent) = SourceScript(source, events.toList())

    private fun setOfElements(vararg elements: Any?) = ModelState.SetState(elements.toSet())

    // -- sources ------------------------------------------------------------

    @Test
    fun `KeyedSetCell binds the latest element per key and a re-put retracts the previous one`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "first"),
            ScriptEvent.Put(w, "k", "second"),
        )

        KeyedSetSourceModel.liveBindings(slice) shouldBe mapOf<Any?, Any?>("k" to "second")
        withClue("a batch fold has no intermediate state, so re-put atomicity needs no modelling") {
            KeyedSetSourceModel.evaluate(slice) shouldBe setOfElements("second")
        }
    }

    @Test
    fun `KeyedSetCell removing a key drops its element and removing an unbound key is a no-op`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "v"),
            ScriptEvent.RemoveKey(w, "k"),
            ScriptEvent.RemoveKey(w, "never-bound"),
        )

        KeyedSetSourceModel.evaluate(slice) shouldBe setOfElements()
    }

    /**
     * The distinct-projection case the cell's own KDoc calls out: two keys holding the same
     * element yield ONE live element, and it survives until the last key drops it.
     */
    @Test
    fun `KeyedSetCell two keys holding one element keep it live until the last key drops it`() {
        val bothBound = sliceOf(
            ScriptEvent.Put(w, "k1", "shared"),
            ScriptEvent.Put(w, "k2", "shared"),
        )
        val oneDropped = SourceScript(source, bothBound.events + ScriptEvent.RemoveKey(w, "k1"))
        val bothDropped = SourceScript(source, oneDropped.events + ScriptEvent.RemoveKey(w, "k2"))

        KeyedSetSourceModel.evaluate(bothBound) shouldBe setOfElements("shared")
        KeyedSetSourceModel.evaluate(oneDropped) shouldBe setOfElements("shared")
        KeyedSetSourceModel.evaluate(bothDropped) shouldBe setOfElements()
    }

    @Test
    fun `CounterCell and PnCounterCell fold to increments minus decrements`() {
        val slice = sliceOf(
            ScriptEvent.Increment(w, 5),
            ScriptEvent.Decrement(w, 2),
            ScriptEvent.Increment(w, 1),
        )

        CounterSourceModel.evaluate(slice) shouldBe ModelState.ScalarState(4L)
        PnCounterSourceModel.evaluate(slice) shouldBe ModelState.ScalarState(4L)
    }

    @Test
    fun `an undriven counter reads zero`() {
        CounterSourceModel.evaluate(sliceOf()) shouldBe ModelState.ScalarState(0L)
    }

    // -- unary operators ----------------------------------------------------

    @Test
    fun `FilterCell keeps exactly the live elements satisfying the predicate`() {
        val keepShort = FilterModel { element -> element.toString().length <= 2 }

        keepShort.evaluate(listOf(setOfElements("a", "bb", "ccc"))) shouldBe setOfElements("a", "bb")
    }

    @Test
    fun `FlatMapSetCell unions the images and a collision stays live while any preimage does`() {
        val firstCharacter = FlatMapSetModel { element -> listOf(element.toString().take(1)) }

        withClue("'ax' and 'ay' collide on 'a' — the model's union is [24-OP-FLATMAP-01]'s observable") {
            firstCharacter.evaluate(listOf(setOfElements("ax", "ay", "bz"))) shouldBe setOfElements("a", "b")
        }
        withClue("dropping one of two preimages leaves the output live") {
            firstCharacter.evaluate(listOf(setOfElements("ay", "bz"))) shouldBe setOfElements("a", "b")
        }
        withClue("dropping the last preimage kills the output") {
            firstCharacter.evaluate(listOf(setOfElements("bz"))) shouldBe setOfElements("b")
        }
    }

    @Test
    fun `mapSet is FlatMapSetCell with a singleton image`() {
        val toText = FlatMapSetModel { element -> listOf(element.toString()) }

        toText.evaluate(listOf(setOfElements(1, 2))) shouldBe setOfElements("1", "2")
    }

    @Test
    fun `CountCell counts distinct live elements`() {
        CountModel.evaluate(listOf(setOfElements("a", "b", "c"))) shouldBe ModelState.ScalarState(3)
        CountModel.evaluate(listOf(setOfElements())) shouldBe ModelState.ScalarState(0)
    }

    @Test
    fun `a unary model rejects the wrong number of inputs by name`() {
        val failure = shouldThrow<IllegalArgumentException> {
            CountModel.evaluate(listOf(setOfElements("a"), setOfElements("b")))
        }
        failure.message!! shouldContain "CountModel is unary"
    }

    @Test
    fun `an operator handed the wrong state shape says which shape it got`() {
        val failure = shouldThrow<IllegalStateException> {
            CountModel.evaluate(listOf(ModelState.ScalarState(1L)))
        }
        failure.message!! shouldContain "expects a set-shaped input"
    }

    // -- fan-in operators ---------------------------------------------------

    @Test
    fun `UnionSetCell unions every arm at any arity`() {
        UnionSetModel.evaluate(
            listOf(setOfElements("a", "b"), setOfElements("b", "c"), setOfElements()),
        ) shouldBe setOfElements("a", "b", "c")

        withClue("the empty union is the empty set") {
            UnionSetModel.evaluate(emptyList()) shouldBe setOfElements()
        }
    }

    /**
     * The fan-in shape: an element asserted by 2 of 3 arms counts 2, one asserted by all
     * three counts 3, and an element no arm asserts is ABSENT rather than present with 0 —
     * the group-death rule the cell shares with `GroupByCell`.
     */
    @Test
    fun `PresenceCountCell counts the distinct arms asserting each element and omits absent ones`() {
        val arms = listOf(
            setOfElements("all", "two"),
            setOfElements("all", "two"),
            setOfElements("all", "one"),
        )

        PresenceCountModel.evaluate(arms) shouldBe ModelState.MapState(
            mapOf<Any?, Any?>("all" to 3, "two" to 2, "one" to 1),
        )
    }

    @Test
    fun `PresenceCountCell an element dropped by every arm leaves the map rather than reading zero`() {
        PresenceCountModel.evaluate(listOf(setOfElements(), setOfElements())) shouldBe ModelState.EMPTY_MAP
    }

    /**
     * The threshold boundary, on the epic's 2-of-3 configuration: with `threshold = 2` an
     * element asserted by exactly 2 arms is in and one asserted by 1 is out; raising the
     * threshold to 3 drops the 2-arm element too.
     */
    @Test
    fun `QuorumSetCell admits exactly the elements meeting the threshold at the boundary`() {
        val arms = listOf(
            setOfElements("all", "two"),
            setOfElements("all", "two"),
            setOfElements("all", "one"),
        )

        QuorumSetModel { 2 }.evaluate(arms) shouldBe setOfElements("all", "two")
        QuorumSetModel { 3 }.evaluate(arms) shouldBe setOfElements("all")
        QuorumSetModel { 1 }.evaluate(arms) shouldBe setOfElements("all", "two", "one")
    }

    @Test
    fun `QuorumSetCell reads the arm count so the whole quorum family is one threshold lambda`() {
        val arms = listOf(setOfElements("x"), setOfElements("x"), setOfElements("y"))

        withClue("intersection: every arm must assert it") {
            QuorumSetModel { n -> n }.evaluate(arms) shouldBe setOfElements()
        }
        withClue("majority of 3 is 2") {
            QuorumSetModel { n -> n / 2 + 1 }.evaluate(arms) shouldBe setOfElements("x")
        }
        withClue("union: any arm suffices") {
            QuorumSetModel { 1 }.evaluate(arms) shouldBe setOfElements("x", "y")
        }
    }
}
