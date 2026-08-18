package civictech.oracle.model

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Observed-remove membership on the script (`[ORA1-MODEL-04]`, `[ORA1-MODEL-05]`; spec
 * `[24-SET-01]`, `[24-SET-03]`), including the epic's named behaviours BS-2 and BS-3.
 *
 * These are **model-level** tests: script in, membership out, no kernel cell executed
 * (`[ORA1-MODEL-01]`). The differential comparison against a live `SetCell` is the runner
 * feature's (computenet-4ru.8); what is checked here is that the reference itself says the
 * right thing, because a wrong reference makes every later comparison worthless.
 *
 * ## The two mutations these tests were built to catch
 *
 * [Membership]'s rule has two independent halves, and each is pinned by exactly one of BS-2
 * and BS-3 — which is why both tests are needed and neither subsumes the other. Re-measured
 * by the task review, 2026-08-18, with `./gradlew :oracle:test --tests
 * 'civictech.oracle.model.MembershipSemanticsTest' --rerun --no-build-cache`, one mutation at
 * a time; **10 tests ran under each**:
 *
 * - Making `Membership.observes` return `true` unconditionally (every writer observes
 *   everything) — the observation half — reddens **3**: `BS-2 a remove by a writer that never
 *   observed the add …`, `one uncovered add among several keeps the element live`, and
 *   `an observe does not reach an add issued after it`, each `expected:<["x"]> but was:<[]>`.
 *   BS-3 stays green.
 * - Changing the final liveness fold from `issued.any { !it.covered }` to
 *   `issued.all { !it.covered }` (an element dies if *any* of its adds was covered) reddens
 *   **2**: `BS-3 an element re-added after an observed remove …` and `one uncovered add among
 *   several keeps the element live`, again `expected:<["x"]> but was:<[]>`. BS-2 stays green.
 *
 * `an unobserved remove of an element nobody ever added is a no-op` survives **both**, and
 * deliberately: with no add on the log there is nothing for either half of the rule to cover,
 * so that case pins the absence of a crash rather than the rule. (An earlier revision of this
 * KDoc counted it among the first mutation's casualties; re-running says it is not one.)
 *
 * Both mutations were checked to remove the *only* thing the assertion depends on: the
 * assertions below are on the returned element set itself, not on a message that some second
 * code path could still satisfy.
 */
class MembershipSemanticsTest {

    private val a = WriterId("A")
    private val b = WriterId("B")
    private val source = SourceId("s")

    /**
     * **BS-2 — an unobserved remove is a no-op** (`[ORA1-MODEL-05]`, `[24-SET-03]`).
     *
     * Two writers into one modelled `SetCell`: A adds "x", B — which never observed that add
     * — removes "x". Add-wins is a *consequence*, not a configured bias: B's remove retracts
     * only what B had observed, and it had observed nothing, so it retracts nothing.
     */
    @Test
    fun `BS-2 a remove by a writer that never observed the add leaves the element live`() {
        val script = Script.of(
            source,
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Remove(b, "x"),
        )

        val live = SetSourceModel.evaluate(script.slice(source))

        withClue("B never observed A's add, so its remove retracts nothing [ORA1-MODEL-05][24-SET-03]") {
            live shouldBe ModelState.SetState("x")
        }
    }

    /**
     * The other side of BS-2: once B *has* observed the add, its remove does cover it. This
     * is what makes the BS-2 assertion above a statement about observation rather than about
     * writers being unable to remove each other's elements at all.
     */
    @Test
    fun `a remove by a writer that has observed the add covers it`() {
        val script = Script.of(
            source,
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Observe(b),
            ScriptEvent.Remove(b, "x"),
        )

        SetSourceModel.evaluate(script.slice(source)) shouldBe ModelState.SetState(emptySet())
    }

    /**
     * **BS-3 — re-add after remove resurrects** (`[ORA1-MODEL-03]`, `[ORA1-MODEL-04]`,
     * `[24-SET-01]`).
     *
     * add("x"), an observed remove("x"), add("x") again. The kernel gets this by minting a
     * fresh tag the earlier remove never saw; the model tracks membership only and agrees
     * *without modelling that tag* — which is the whole point of the requirement.
     */
    @Test
    fun `BS-3 an element re-added after an observed remove is live again with no tag modelled`() {
        val script = Script.of(
            source,
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Remove(a, "x"), // a writer observes its own adds at issue time
            ScriptEvent.Add(a, "x"),
        )

        val live = SetSourceModel.evaluate(script.slice(source))

        withClue("the remove precedes the second add and so cannot cover it [ORA1-MODEL-04][24-SET-01]") {
            live shouldBe ModelState.SetState("x")
        }
    }

    @Test
    fun `a writer observes its own adds at issue time so its own remove covers them`() {
        val script = Script.of(source, ScriptEvent.Add(a, "x"), ScriptEvent.Remove(a, "x"))

        SetSourceModel.evaluate(script.slice(source)) shouldBe ModelState.SetState(emptySet())
    }

    @Test
    fun `an unobserved remove of an element nobody ever added is a no-op`() {
        val script = Script.of(source, ScriptEvent.Remove(b, "x"))

        Membership.live(script.slice(source)).shouldBeEmpty()
    }

    /**
     * An [ScriptEvent.Observe] only reaches adds that precede it: it says "I have seen
     * everything up to *here*", not "I will see everything".
     */
    @Test
    fun `an observe does not reach an add issued after it`() {
        val script = Script.of(
            source,
            ScriptEvent.Observe(b),
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Remove(b, "x"),
        )

        Membership.live(script.slice(source)) shouldBe setOf("x")
    }

    /**
     * Concurrent add wins: A's add is covered by B's observed remove, but A's *later*
     * unobserved add is not — one uncovered add is enough for liveness.
     */
    @Test
    fun `one uncovered add among several keeps the element live`() {
        val script = Script.of(
            source,
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Observe(b),
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Remove(b, "x"),
        )

        withClue("B observed only the first add; the second is concurrent with its remove [24-SET-03]") {
            Membership.live(script.slice(source)) shouldBe setOf("x")
        }
    }

    @Test
    fun `an element removed after every one of its adds was observed is dead`() {
        val script = Script.of(
            source,
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Add(a, "y"),
            ScriptEvent.Observe(b),
            ScriptEvent.Remove(b, "x"),
        )

        Membership.live(script.slice(source)) shouldBe setOf("y")
    }

    @Test
    fun `membership ignores events that belong to other source families`() {
        val script = Script.of(
            source,
            ScriptEvent.Increment(a, 3),
            ScriptEvent.Add(a, "x"),
            ScriptEvent.Put(a, "k", "v"),
        )

        Membership.live(script.slice(source)) shouldBe setOf("x")
    }

    @Test
    fun `a source the script never drives folds to the empty set rather than failing`() {
        SetSourceModel.evaluate(Script.EMPTY.slice(SourceId("absent"))) shouldBe ModelState.SetState(emptySet())
    }
}
