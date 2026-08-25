package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.model.ScriptEvent
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [CaseGenerator]'s contract: determinism in process (`ORA1 §GEN-01`'s same-JVM half —
 * `Bs16ReproducibilityTest` owns the cross-JVM half), the pure controller-seed derivation
 * (`ORA1 §GEN-07`), the vocabulary knob and the whole-knob-set end-to-end reach
 * (`ORA1 §GEN-04`), and the facade's loud catalog-validation failure (`ORA1 §GEN-08`).
 *
 * [OperatorCatalog] is a process-wide mutable singleton shared with the sibling generator
 * tests, so every test here registers in `@BeforeEach` and empties it in `@AfterEach`.
 */
class CaseGeneratorTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    // -- ORA1 §GEN-01, in-process half --------------------------------------

    /**
     * The same `(seed, config)` twice is the same case, for a sweep well past the criterion's
     * 20 seeds — and two *different* seeds are different cases, which is what says the rng is
     * genuinely threaded rather than the generator being constant.
     */
    @Test
    fun `equal seeds regenerate equal cases and distinct seeds differ`() {
        val generator = CaseGenerator(defaultConfig())

        val cases = (0L until 25L).associateWith { seed ->
            val first = generator.generate(seed)
            val second = generator.generate(seed)
            withClue("seed $seed regenerated differently") { second shouldBe first }
            first
        }

        // Not "all 25 pairwise distinct" — a collision would be a legitimate (if astronomically
        // unlikely) draw. What must hold is that the population is not constant.
        withClue("every seed produced the same case: the rng is not threaded") {
            cases.values.distinct().size shouldNotBe 1
        }
        cases.getValue(0L) shouldNotBe cases.getValue(1L)
    }

    /**
     * A second [CaseGenerator] instance over an equal config agrees with the first: nothing is
     * cached in the facade, and construction order does not leak into a case.
     */
    @Test
    fun `two generator instances over an equal config agree`() {
        val a = CaseGenerator(defaultConfig())
        val b = CaseGenerator(defaultConfig())
        (0L until 25L).forEach { seed ->
            withClue("seed $seed") { b.generate(seed) shouldBe a.generate(seed) }
        }
    }

    /** The companion's one-shot form is the instance form, not a second code path. */
    @Test
    fun `the one-shot companion form agrees with the instance form`() {
        val config = defaultConfig()
        val generator = CaseGenerator(config)
        (0L until 5L).forEach { seed ->
            CaseGenerator.generate(seed, config) shouldBe generator.generate(seed)
        }
    }

    // -- ORA1 §GEN-07: the controller seed is a pure function of the case seed --

    /**
     * `GeneratedCase.controllerSeed` equals the derivation its own KDoc documents — one
     * splitmix64 step over the case seed — recomputed here independently, for a sweep of seeds
     * including the boundary values. Asserting the *documented function* rather than "it is
     * stable" is what makes it checkable that one seed identifies one (graph, script, schedule)
     * triple: the schedule's seed is derived, never carried.
     */
    @Test
    fun `controllerSeed is the documented splitmix64 of the case seed`() {
        val generator = CaseGenerator(defaultConfig())
        val seeds = (0L until 25L).toList() + listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 42L)

        seeds.forEach { seed ->
            val case = generator.generate(seed)
            withClue("seed $seed") { case.controllerSeed shouldBe splitmix64(seed) }
        }
    }

    /** The derivation depends on the seed alone: two regenerations agree, and the config is irrelevant. */
    @Test
    fun `controllerSeed is stable across regenerations and independent of the config`() {
        val wide = CaseGenerator(defaultConfig())
        val narrow = CaseGenerator(defaultConfig(scriptLength = 12, terminalCount = 1, sourceCount = 2))

        (0L until 25L).forEach { seed ->
            val a = wide.generate(seed).controllerSeed
            withClue("seed $seed") {
                wide.generate(seed).controllerSeed shouldBe a
                narrow.generate(seed).controllerSeed shouldBe a
            }
        }
    }

    /** A sweep's controller seeds are not the raw case seeds — the mix does something. */
    @Test
    fun `controllerSeed is decorrelated from consecutive case seeds`() {
        val generator = CaseGenerator(defaultConfig())
        val derived = (0L until 25L).map { generator.generate(it).controllerSeed }

        derived.distinct() shouldHaveSize derived.size
        withClue("controllerSeed is the identity on the case seed: consecutive sweeps would share a schedule") {
            derived.filterIndexed { i, s -> s == i.toLong() }.shouldBeEmpty()
        }
    }

    // -- ORA1 §GEN-04: the vocabulary knob ----------------------------------

    /**
     * Two **disjoint** vocabulary subsets, each self-sufficient (a source plus operators that
     * consume its shape), generate two populations whose spawned catalog ids stay inside their
     * own subset. Disjointness is what makes the assertion sharp: a generator ignoring the knob
     * and drawing from the whole catalog would leak an id of the other subset almost at once.
     */
    @Test
    fun `two disjoint vocabularies confine each population to its own catalog ids`() {
        val left = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.FILTER, CoreOperators.Ids.COUNT)
        val right = listOf(CoreOperators.Ids.KEYED_SET, CoreOperators.Ids.FLAT_MAP_SET, CoreOperators.Ids.MAP_SET, CoreOperators.Ids.UNION)
        withClue("the two subsets must be disjoint for this test to say anything") {
            left.intersect(right.toSet()).shouldBeEmpty()
        }

        val leftIds = idsAcrossSweep(left, sourceCount = 1)
        val rightIds = idsAcrossSweep(right, sourceCount = 2)

        withClue("left population leaked ids: $leftIds") { (leftIds - left.toSet()).shouldBeEmpty() }
        withClue("right population leaked ids: $rightIds") { (rightIds - right.toSet()).shouldBeEmpty() }

        // Non-vacuity: each population must actually be drawing from its vocabulary, not from
        // one lucky id that happens to sit in both assertions' safe zone.
        withClue("left population used only $leftIds") { (leftIds.size >= 2) shouldBe true }
        withClue("right population used only $rightIds") { (rightIds.size >= 2) shouldBe true }
        leftIds.intersect(rightIds).shouldBeEmpty()
    }

    // -- ORA1 §GEN-04: every knob reaches the facade -------------------------

    /**
     * One case, generated through the front door, carrying a **distinctive** value of every
     * [GeneratorConfig] knob at once — so a knob dropped on the way from the facade to either
     * generator reddens here rather than only in the generator's own test.
     *
     * The `addRemoveRatio = 0.0` / `unobservedRemoveRatio = 1.0` corner is deliberate: it makes
     * both ratios observable in one case without a statistical tolerance. Every emitted set
     * event is a `Remove`, and every one of them is audited unobserved — a writer that never
     * adds knows nothing, so an unobserved candidate always exists and the generator never
     * falls back to an add (`ScriptGenerator.emitSetRemove`).
     */
    @Test
    fun `a single case reflects a distinctive value of every knob`() {
        val vocabulary = listOf(
            CoreOperators.Ids.SET,
            CoreOperators.Ids.FILTER,
            CoreOperators.Ids.FLAT_MAP_SET,
            CoreOperators.Ids.UNION,
        )
        val config = GeneratorConfig(
            depthRange = 4..4,
            sourceCount = 3,
            vocabulary = vocabulary,
            elementDomainSize = 5,
            scriptLength = 50,
            addRemoveRatio = 0.0,
            unobservedRemoveRatio = 1.0,
            terminalCount = 2,
            writerCount = 3,
            lateJoiner = true,
            hostCount = 2,
        )

        val case = CaseGenerator(config).generate(11L)

        // sourceCount
        val sources = case.topology.nodes.filter { it.source != null }
        withClue("sourceCount") { sources shouldHaveSize 3 }

        // depthRange: operator handles are `op-<level>-<index>`, so the deepest level is the depth.
        val levels = case.topology.nodes.filter { it.source == null }
            .map { it.handle.removePrefix("op-").substringBefore('-').toInt() }
        withClue("depthRange, handles ${case.topology.nodes.map { it.handle }}") {
            levels.max() shouldBe 4
        }

        // vocabulary
        withClue("vocabulary") {
            (case.topology.nodes.map { it.catalogId }.toSet() - vocabulary.toSet()).shouldBeEmpty()
        }

        // terminalCount (the late terminal is one beyond it, by GraphGenerator's contract)
        withClue("terminalCount") { case.topology.terminals.filterNot { it.late } shouldHaveSize 2 }

        // lateJoiner: exactly one late terminal and exactly one barrier
        withClue("lateJoiner terminal") { case.topology.terminals.filter { it.late } shouldHaveSize 1 }
        withClue("lateJoiner barrier") {
            case.script.steps.count { it is CaseStep.Barrier } shouldBe 1
        }

        // hostCount
        val ordinals = case.topology.placement.values.toSet()
        withClue("hostCount, placement ${case.topology.placement}") {
            ordinals shouldBe setOf(0, 1)
        }

        // scriptLength counts Op steps; the barrier is the late-joiner extension on top.
        val ops = case.script.steps.filterIsInstance<CaseStep.Op>()
        withClue("scriptLength") { ops shouldHaveSize 50 }

        // addRemoveRatio = 0.0: no Add is ever chosen, and no fallback add is needed here.
        withClue("addRemoveRatio, events ${ops.map { it.event::class.simpleName }.distinct()}") {
            ops.map { it.event::class.simpleName }.distinct() shouldContainExactly listOf("Remove")
        }

        // unobservedRemoveRatio = 1.0: every remove is audited unobserved, and every remove is audited.
        withClue("unobservedRemoveRatio") {
            case.removeAudit shouldHaveSize 50
            case.removeAudit.filter { it.observed }.shouldBeEmpty()
        }

        // elementDomainSize
        val domain = ElementDomains.elements(5).toSet()
        val used = ops.map { (it.event as ScriptEvent.Remove).element }.toSet()
        withClue("elementDomainSize, used $used") { (used - domain).shouldBeEmpty() }

        // writerCount: all three writers of the configured pool are actually named at this seed.
        val writers = ops.map { it.event.writer.id }.toSet()
        withClue("writerCount, writers $writers") { writers shouldBe setOf("w0", "w1", "w2") }
    }

    // -- ORA1 §GEN-08: the facade's loud validation --------------------------

    /**
     * The types task's catalog validation is reachable through the front door, and reports at
     * construction — before a seed is ever drawn — naming every unregistered id.
     */
    @Test
    fun `constructing the facade with an unregistered vocabulary id fails naming the id`() {
        val failure = assertThrows<IllegalArgumentException> {
            CaseGenerator(defaultConfig().copy(vocabulary = listOf(CoreOperators.Ids.SET, "no-such-operator")))
        }
        failure.message!!.contains("no-such-operator") shouldBe true
    }

    /** Every absent id is named at once, not just the first. */
    @Test
    fun `the facade names every unregistered id, not only the first`() {
        val failure = assertThrows<IllegalArgumentException> {
            CaseGenerator(defaultConfig().copy(vocabulary = listOf("bogus-a", CoreOperators.Ids.SET, "bogus-b")))
        }
        withClue(failure.message!!) {
            failure.message!!.contains("bogus-a") shouldBe true
            failure.message!!.contains("bogus-b") shouldBe true
        }
    }

    /** The one-shot form validates too — it is the instance form, so it has to. */
    @Test
    fun `the one-shot form rejects an unregistered id as well`() {
        val failure = assertThrows<IllegalArgumentException> {
            CaseGenerator.generate(1L, defaultConfig().copy(vocabulary = listOf("also-bogus")))
        }
        failure.message!!.contains("also-bogus") shouldBe true
    }

    // -- helpers -------------------------------------------------------------

    /** Every catalog id spawned across a seed sweep over [vocabulary]. */
    private fun idsAcrossSweep(vocabulary: List<String>, sourceCount: Int): Set<String> {
        val generator = CaseGenerator(
            defaultConfig(sourceCount = sourceCount, terminalCount = 1).copy(vocabulary = vocabulary),
        )
        return (0L until 30L).flatMap { seed -> generator.generate(seed).topology.nodes.map { it.catalogId } }.toSet()
    }

    /**
     * The derivation `GeneratedCase.controllerSeed` documents, recomputed independently here so
     * the test asserts the *function* rather than whatever the production getter happens to
     * return: one splitmix64 (Steele, Lea & Flood 2014) step over the case seed.
     */
    private fun splitmix64(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    private companion object {
        fun defaultConfig(
            sourceCount: Int = 3,
            terminalCount: Int = 1,
            scriptLength: Int = 40,
        ) = GeneratorConfig(
            depthRange = 3..5,
            sourceCount = sourceCount,
            vocabulary = listOf(
                CoreOperators.Ids.SET,
                CoreOperators.Ids.KEYED_SET,
                CoreOperators.Ids.FILTER,
                CoreOperators.Ids.FLAT_MAP_SET,
                CoreOperators.Ids.MAP_SET,
                CoreOperators.Ids.COUNT,
                CoreOperators.Ids.UNION,
                CoreOperators.Ids.INTERSECT,
                CoreOperators.Ids.PRESENCE_COUNT,
                CoreOperators.Ids.QUORUM_SET,
            ),
            elementDomainSize = 6,
            scriptLength = scriptLength,
            addRemoveRatio = 0.5,
            unobservedRemoveRatio = 0.3,
            terminalCount = terminalCount,
        )
    }
}
