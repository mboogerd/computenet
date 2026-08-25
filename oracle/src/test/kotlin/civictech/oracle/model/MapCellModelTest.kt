package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * `MapCell`'s batch denotation (`ORA1 §MODEL-08`, `ORA1 §MODEL-09`; spec `[24-OP-MAP-01]`):
 * defined only for a single-writer FIFO script slice, last put per key wins in script order,
 * a removal deletes the key, and a multi-writer slice fails loudly rather than guessing.
 *
 * Model-level throughout, per ORA1 §MODEL-01: a [SourceScript] goes in, a [ModelState] comes
 * out, no kernel cell runs.
 */
class MapCellModelTest {

    private val w = WriterId("w")
    private val source = SourceId("s")

    private fun sliceOf(vararg events: ScriptEvent) = SourceScript(source, events.toList())

    @Test
    fun `a single put is bound as the map's one entry`() {
        val slice = sliceOf(ScriptEvent.Put(w, "k", "v"))

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k" to "v"))
    }

    @Test
    fun `the last put per key wins in script order`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "first"),
            ScriptEvent.Put(w, "k", "second"),
            ScriptEvent.Put(w, "k", "third"),
        )

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k" to "third"))
    }

    @Test
    fun `removeKey deletes the key and removing an unbound key is a no-op`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "v"),
            ScriptEvent.RemoveKey(w, "k"),
            ScriptEvent.RemoveKey(w, "never-bound"),
        )

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.EMPTY_MAP
    }

    @Test
    fun `a key removed then re-put is bound again to the new value`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "first"),
            ScriptEvent.RemoveKey(w, "k"),
            ScriptEvent.Put(w, "k", "second"),
        )

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k" to "second"))
    }

    @Test
    fun `several keys are independent`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "a", 1),
            ScriptEvent.Put(w, "b", 2),
            ScriptEvent.RemoveKey(w, "a"),
        )

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("b" to 2))
    }

    @Test
    fun `non-map events in the slice are ignored`() {
        val slice = sliceOf(
            ScriptEvent.Put(w, "k", "v"),
            ScriptEvent.Add(w, "unrelated-set-event"),
            ScriptEvent.Increment(w, 5L),
        )

        MapCellSourceModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k" to "v"))
    }

    /**
     * `ORA1 §MODEL-08`'s operative clause: a slice with more than one distinct writer id
     * among its `Put`/`RemoveKey` events has no defined expected value, so evaluation must
     * fail loudly and by name — never return a plausible-looking guess.
     *
     * Mutation-checked 2026-08-18: replacing `MapCellSourceModel.evaluate`'s `writers.size >
     * 1` guard with `if (false)` (so it evaluates the slice as if the guard had never fired)
     * makes THIS test, `` `a slice whose two writers only ever touch removeKey still fails
     * loudly` ``, and `` `the named exception is an IllegalStateException so generic
     * model-failure handling still catches it` `` all fail with
     * `AssertionError: Expected exception civictech.oracle.model.MapCellSourceModel
     * .MultiWriterMapSliceException but no exception was thrown` (the model instead silently
     * returns the last-writer-wins-per-key map — the "plausible but undefined" value this test
     * exists to reject) — 3 of 10 `MapCellModelTest` tests red, 7 still green. Restored
     * immediately after; `git diff --stat
     * oracle/src/main/kotlin/civictech/oracle/model/MapCellModel.kt` showed no diff, and
     * `./gradlew :oracle:test --tests 'civictech.oracle.model.MapCellModelTest' --rerun
     * --no-build-cache` was green (10/10) again afterwards.
     */
    @Test
    fun `a slice with more than one writer fails loudly by name rather than guessing a winner`() {
        val slice = sliceOf(
            ScriptEvent.Put(WriterId("A"), "a", "a1"),
            ScriptEvent.Put(WriterId("B"), "b", "b1"),
            ScriptEvent.Put(WriterId("A"), "a", "a2"),
            ScriptEvent.Put(WriterId("B"), "b", "b2"),
        )

        val failure = shouldThrow<MapCellSourceModel.MultiWriterMapSliceException> {
            MapCellSourceModel.evaluate(slice)
        }

        withClue("the failure names the requirement and the offending source") {
            failure.message!! shouldContain "ORA1-MODEL-08"
            failure.message!! shouldContain "s"
        }
    }

    @Test
    fun `a slice whose two writers only ever touch removeKey still fails loudly`() {
        val slice = sliceOf(
            ScriptEvent.Put(WriterId("A"), "k", "v"),
            ScriptEvent.RemoveKey(WriterId("B"), "k"),
        )

        shouldThrow<MapCellSourceModel.MultiWriterMapSliceException> {
            MapCellSourceModel.evaluate(slice)
        }
    }

    @Test
    fun `an empty slice folds to the empty map`() {
        MapCellSourceModel.evaluate(sliceOf()) shouldBe ModelState.EMPTY_MAP
    }

    @Test
    fun `the named exception is an IllegalStateException so generic model-failure handling still catches it`() {
        val slice = sliceOf(
            ScriptEvent.Put(WriterId("A"), "a", 1),
            ScriptEvent.Put(WriterId("B"), "b", 2),
        )

        shouldThrow<IllegalStateException> { MapCellSourceModel.evaluate(slice) }
    }
}
