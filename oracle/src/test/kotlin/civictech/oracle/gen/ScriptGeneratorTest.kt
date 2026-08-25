package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.model.MapCellSourceModel
import civictech.oracle.model.Membership
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.oracle.model.SourceScript
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.random.Random

/**
 * [ScriptGenerator]: the observed/unobserved remove bias (`ORA1 §GEN-06`), the construct-correct
 * single-writer guarantee for order-dependent sources (`ORA1 §MODEL-09`), per-source-kind event
 * legality, the three script-side `ORA1 §GEN-04` knobs, and determinism.
 *
 * Every fixture here is a hand-built [CaseTopology]: this suite has no dependency on the graph
 * generator, so a failure names the script generator and nothing else. The `OperatorCatalog` is
 * never registered into either — [ScriptGenerator] reads catalog *ids* through [SourceKind], not
 * the process-wide registry — so this suite leaves no shared state behind for a sibling's tests.
 */
class ScriptGeneratorTest {

    // --- fixtures -------------------------------------------------------------------------

    private fun sourceNode(handle: String, catalogId: String) =
        TopologyNode(handle = handle, catalogId = catalogId, inputs = emptyList(), source = SourceId(handle))

    /** A topology of the given source kinds, all feeding one `count` operator read by one terminal. */
    private fun topologyOf(vararg kinds: Pair<String, String>): CaseTopology {
        val nodes = kinds.map { (handle, catalogId) -> sourceNode(handle, catalogId) } +
            TopologyNode("agg", CoreOperators.Ids.COUNT, kinds.map { it.first }, null)
        return CaseTopology(
            nodes = nodes,
            terminals = listOf(TerminalSpec(name = "terminal", handle = "agg")),
            placement = nodes.associate { it.handle to 0 },
        )
    }

    private fun config(
        scriptLength: Int = 200,
        addRemoveRatio: Double = 0.5,
        unobservedRemoveRatio: Double = 0.3,
        elementDomainSize: Int = 64,
        writerCount: Int = 2,
        vocabulary: List<String> = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.COUNT),
    ) = GeneratorConfig(
        depthRange = 1..2,
        sourceCount = 2,
        vocabulary = vocabulary,
        elementDomainSize = elementDomainSize,
        scriptLength = scriptLength,
        addRemoveRatio = addRemoveRatio,
        unobservedRemoveRatio = unobservedRemoveRatio,
        terminalCount = 1,
        writerCount = writerCount,
    )

    private fun generate(topology: CaseTopology, config: GeneratorConfig, seed: Long): GeneratedScript =
        ScriptGenerator(config, topology, Random(seed)).generate()

    private fun ops(script: CaseScript): List<CaseStep.Op> = script.steps.filterIsInstance<CaseStep.Op>()

    // --- Ex/unobserved bias ORA1 §GEN-06 -------------------------------------------------

    /**
     * Ex/unobserved bias, the feature's own example: at `unobservedRemoveRatio = 0.3` and
     * `scriptLength = 200`, about 30% of removes name an element the removing writer never
     * added nor observed — and the audit says which.
     *
     * The tolerance is stated twice on purpose: the aggregate over 25 seeds is the tight
     * measurement (the fraction is a binomial over ~1500 removes there), and one named seed is
     * checked at the feature's own +/-10 percentage points so the example holds for a single
     * 200-step case as written. Both are seeded, so neither can flake — a failure is a real
     * change in the distribution.
     */
    @Test
    fun `about 30 percent of removes are deliberately unobserved`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET, "s1" to CoreOperators.Ids.SET)
        val config = config(unobservedRemoveRatio = 0.3)

        var removes = 0
        var unobserved = 0
        (1L..25L).forEach { seed ->
            val generated = generate(topology, config, seed)
            removes += generated.removeAudit.size
            unobserved += generated.removeAudit.count { !it.observed }
        }
        removes shouldExceed 500
        val aggregate = unobserved.toDouble() / removes
        withClue("aggregate unobserved fraction over 25 seeds was $aggregate ($unobserved/$removes)") {
            abs(aggregate - 0.3) shouldBeUnder 0.05
        }

        val single = generate(topology, config, 42L)
        val singleFraction = single.removeAudit.count { !it.observed }.toDouble() / single.removeAudit.size
        withClue("seed 42's unobserved fraction was $singleFraction over ${single.removeAudit.size} removes") {
            abs(singleFraction - 0.3) shouldBeUnder 0.10
        }
    }

    /**
     * `computenet-qcm1`: **no unobserved remove names an element that is live at that point in
     * its source's slice.** A remove of a non-live element is a no-op on both sides of the
     * differential — the model covers no uncovered add, and the kernel's `SetCell` finds no live
     * tag to retract — whereas a remove of a LIVE element the removing writer never observed
     * takes effect in the kernel and is a model no-op, which is a false Mismatch by construction.
     *
     * Liveness is read from [Membership.live] over the **prior** events of that source's slice —
     * the model's own definition, called rather than re-implemented, so this cannot drift from
     * the notion the differential runner compares against. The fixture is the one the bead
     * measured on (two set sources, `writerCount = 2`, domain 64, length 200, ratio 0.3,
     * seeds 1..25), where the unrestricted draw produced 20 live-element removes out of 699.
     */
    @Test
    fun `no unobserved remove names an element live at that point in its slice`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET, "s1" to CoreOperators.Ids.SET)
        val config = config(unobservedRemoveRatio = 0.3)

        var unobservedRemoves = 0
        var namingLiveElement = 0
        val offenders = mutableListOf<String>()
        (1L..25L).forEach { seed ->
            val generated = generate(topology, config, seed)
            val steps = generated.script.steps
            generated.removeAudit.filterNot { it.observed }.forEach { record ->
                val op = steps[record.stepIndex] as CaseStep.Op
                val event = op.event as? ScriptEvent.Remove ?: return@forEach
                unobservedRemoves++
                if (event.element in Membership.live(priorEvents(steps, record.stepIndex, op.source))) {
                    namingLiveElement++
                    if (offenders.size < 5) offenders += "seed $seed step ${record.stepIndex} ${event.element}"
                }
            }
        }

        // Not a vacuous zero: the fixture really does generate a large unobserved population.
        unobservedRemoves shouldExceed 500
        withClue(
            "unobservedRemoves=$unobservedRemoves namingLiveElement=$namingLiveElement " +
                "first offenders: $offenders",
        ) {
            namingLiveElement shouldBe 0
        }
    }

    /**
     * `computenet-i3vo`: **no emitted remove leaves its element live** — the constraint stated
     * over *every* remove rather than over the unobserved flavour alone.
     *
     * The predicate is the one `WavePrefixTest.kernelEffectiveModelInertRemoves` names: the
     * element is live in [Membership] over the source's prior slice *and still live* once the
     * remove is folded in. Such a step takes effect in the kernel — `SetCell.inletHandler.remove`
     * retracts `liveTags(element)` without consulting the removing writer's causal history — and
     * is a no-op in the model, so it is a Mismatch manufactured by the generator.
     *
     * The test's own reading of "leaves it live" folds [Membership] over `prior + event`, which is
     * equivalent to the criterion's "not live before, OR not live after": a remove cannot make a
     * dead element live, so live-after implies live-before.
     *
     * `computenet-qcm1` had already established this for `emitUnobservedRemove` (the test below);
     * what this one adds is the **observed** population, which is where the residual sat — a
     * writer removing an element it added itself that another writer also added (the *direct*
     * branch), audited `observed = true`, which is exactly why `unobservedRemoveRatio = 0.0`
     * could not clear it.
     *
     * The fixture carries a keyed source as well, so the sweep really is over every remove the
     * generator emits. A `RemoveKey` satisfies the constraint vacuously — [Membership.live]
     * ignores `Put`/`RemoveKey` entirely, so a key is never live in it — and it is counted here
     * rather than skipped so the claim is over the whole audit and not a filtered part of it.
     *
     * Measured against the unfixed generator (2026-08-21, Darwin arm64), this fixture over
     * seeds 1..25 produced 20 offending removes out of 2300 (1546 of them observed) — **every
     * one of them audited `observed = true`**, which is the bead's claim reproduced. It is 0 now.
     */
    @Test
    fun `no emitted remove leaves its element live in the model`() {
        val topology = topologyOf(
            "s0" to CoreOperators.Ids.SET,
            "s1" to CoreOperators.Ids.SET,
            "k0" to CoreOperators.Ids.KEYED_SET,
        )
        val config = config(
            unobservedRemoveRatio = 0.3,
            vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.KEYED_SET),
        )

        var removes = 0
        var observedRemoves = 0
        var leftLive = 0
        val offenders = mutableListOf<String>()
        (1L..25L).forEach { seed ->
            val generated = generate(topology, config, seed)
            val steps = generated.script.steps
            generated.removeAudit.forEach { record ->
                val op = steps[record.stepIndex] as CaseStep.Op
                removes++
                if (record.observed) observedRemoves++
                val event = op.event as? ScriptEvent.Remove ?: return@forEach
                val prior = priorEvents(steps, record.stepIndex, op.source)
                if (event.element in Membership.live(prior) && event.element in Membership.live(prior + event)) {
                    leftLive++
                    if (offenders.size < 5) {
                        offenders += "seed $seed step ${record.stepIndex} ${event.writer.id} removes ${event.element}" +
                            " (observed=${record.observed})"
                    }
                }
            }
        }

        // Not a vacuous zero, and not a zero carried by the unobserved half alone: the OBSERVED
        // population is the one this bead is about, and it has to be large here.
        removes shouldExceed 500
        observedRemoves shouldExceed 500
        withClue(
            "removes=$removes observedRemoves=$observedRemoves leftLive=$leftLive " +
                "first offenders: $offenders",
        ) {
            leftLive shouldBe 0
        }
    }

    /**
     * The audit is not a claim about the generator's intent but about the script it emitted:
     * re-deriving each remove's classification from the script alone — did this writer add the
     * element earlier in this source's slice, or does an `Observe` by it sit between somebody
     * else's add and this remove (`Membership`'s rule) — agrees on every record.
     *
     * This is also where the "observed removes are genuinely observed **at their position**"
     * criterion is checked: the re-derivation only looks *earlier* in the slice.
     */
    @Test
    fun `re-deriving each remove's classification from the script matches the audit exactly`() {
        val topology = topologyOf(
            "s0" to CoreOperators.Ids.SET,
            "s1" to CoreOperators.Ids.SET,
            "k0" to CoreOperators.Ids.KEYED_SET,
        )
        val config = config(unobservedRemoveRatio = 0.3, vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.KEYED_SET))

        (1L..30L).forEach { seed ->
            val generated = generate(topology, config, seed)
            val steps = generated.script.steps

            // Every remove in the script is audited, exactly once, at its own step index.
            val removeIndices = steps.withIndex()
                .filter { (_, step) -> step is CaseStep.Op && step.event.isRemove() }
                .map { it.index }
            generated.removeAudit.map { it.stepIndex } shouldContainExactly removeIndices

            generated.removeAudit.forEach { record ->
                val derived = deriveObserved(steps, record.stepIndex)
                withClue("seed $seed, step ${record.stepIndex} (${steps[record.stepIndex]}): audit said observed=${record.observed}") {
                    derived shouldBe record.observed
                }
            }
        }
    }

    /** Both observed flavours occur in a population: a writer removing its own add, and one that `Observe`s first. */
    @Test
    fun `observed removes cover both self-added and explicitly observed cross-writer elements`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET)
        val config = config(unobservedRemoveRatio = 0.3)

        var selfRemoves = 0
        var crossRemoves = 0
        (1L..20L).forEach { seed ->
            val generated = generate(topology, config, seed)
            val steps = generated.script.steps
            generated.removeAudit.filter { it.observed }.forEach { record ->
                val op = steps[record.stepIndex] as CaseStep.Op
                val event = op.event as ScriptEvent.Remove
                val addedItself = steps.take(record.stepIndex)
                    .filterIsInstance<CaseStep.Op>()
                    .filter { it.source == op.source }
                    .any { it.event.let { e -> e is ScriptEvent.Add && e.writer == event.writer && e.element == event.element } }
                if (addedItself) selfRemoves++ else crossRemoves++
            }
        }
        selfRemoves shouldExceed 0
        crossRemoves shouldExceed 0
    }

    // --- Ex/rejection ORA1 §MODEL-09 -----------------------------------------------------

    /**
     * Ex/rejection, the feature's own example: with the order-dependent `map` source in the
     * topology and `writerCount = 2`, every one of 200 generated scripts places all of that
     * source's events under one writer — while the set source beside it uses both writers
     * somewhere in the same population, so the single-writer result is a property of the
     * order-dependent source and not of a generator that only ever uses one writer.
     */
    @Test
    fun `every generated script places the order-dependent map source under exactly one writer`() {
        val topology = topologyOf(
            "m0" to CoreOperators.Ids.MAP,
            "s0" to CoreOperators.Ids.SET,
        )
        val config = config(
            scriptLength = 60,
            elementDomainSize = 16,
            writerCount = 2,
            vocabulary = listOf(CoreOperators.Ids.MAP, CoreOperators.Ids.SET),
        )

        val setWriters = mutableSetOf<WriterId>()
        val mapWriterChoices = mutableSetOf<WriterId>()
        (1L..200L).forEach { seed ->
            val generated = generate(topology, config, seed)
            val mapWriters = ops(generated.script)
                .filter { it.source == SourceId("m0") }
                .mapTo(LinkedHashSet()) { it.event.writer }
            withClue("seed $seed drove the map source with ${mapWriters.map { it.id }}") {
                (mapWriters.size <= 1) shouldBe true
            }
            mapWriterChoices += mapWriters
            setWriters += ops(generated.script).filter { it.source == SourceId("s0") }.map { it.event.writer }
        }
        // Not a generator that simply never uses a second writer.
        setWriters.size shouldBe 2
        // Nor one that always picks the same writer for the map source.
        mapWriterChoices.size shouldBe 2
    }

    /**
     * The consequence that matters: the reference model evaluates a generated map slice without
     * throwing [MapCellSourceModel.MultiWriterMapSliceException] — the exception being the thing
     * `ORA1 §MODEL-09`'s generation-time guarantee exists to make unreachable. The same model
     * does throw on a hand-built two-writer slice, so the green result above is the generator's
     * doing and not a dormant check.
     */
    @Test
    fun `the reference model evaluates every generated map slice without throwing`() {
        val topology = topologyOf("m0" to CoreOperators.Ids.MAP, "s0" to CoreOperators.Ids.SET)
        val config = config(
            scriptLength = 60,
            elementDomainSize = 16,
            writerCount = 3,
            vocabulary = listOf(CoreOperators.Ids.MAP, CoreOperators.Ids.SET),
        )

        (1L..50L).forEach { seed ->
            val slice = generate(topology, config, seed).script.toScript().slice(SourceId("m0"))
            MapCellSourceModel.evaluate(slice)
        }

        val twoWriters = SourceScript(
            SourceId("m0"),
            listOf(
                ScriptEvent.Put(WriterId("w0"), "k00", "e00"),
                ScriptEvent.Put(WriterId("w1"), "k00", "e01"),
            ),
        )
        assertThrows<MapCellSourceModel.MultiWriterMapSliceException> { MapCellSourceModel.evaluate(twoWriters) }
    }

    // --- per-kind event legality ----------------------------------------------------------

    /** An event a source cell could not execute is never emitted for it. */
    @Test
    fun `each source kind receives only the events its cell can execute`() {
        val topology = topologyOf(
            "s0" to CoreOperators.Ids.SET,
            "k0" to CoreOperators.Ids.KEYED_SET,
            "m0" to CoreOperators.Ids.MAP,
            "c0" to CoreOperators.Ids.COUNTER,
            "p0" to CoreOperators.Ids.PN_COUNTER,
        )
        val config = config(
            scriptLength = 150,
            elementDomainSize = 16,
            vocabulary = listOf(
                CoreOperators.Ids.SET,
                CoreOperators.Ids.KEYED_SET,
                CoreOperators.Ids.MAP,
                CoreOperators.Ids.COUNTER,
                CoreOperators.Ids.PN_COUNTER,
            ),
        )
        val seen = mutableMapOf<String, MutableSet<String>>()

        (1L..40L).forEach { seed ->
            ops(generate(topology, config, seed).script).forEach { op ->
                seen.getOrPut(op.source.id) { mutableSetOf() } += op.event::class.simpleName!!
            }
        }

        seen.getValue("s0") shouldBe setOf("Add", "Remove", "Observe")
        seen.getValue("k0") shouldBe setOf("Put", "RemoveKey")
        seen.getValue("m0") shouldBe setOf("Put", "RemoveKey")
        seen.getValue("c0") shouldBe setOf("Increment", "Decrement")
        seen.getValue("p0") shouldBe setOf("Increment", "Decrement")
    }

    /** A topology node carrying a `SourceId` but naming an operator fails loudly, not silently. */
    @Test
    fun `a source node naming a non-source catalog id is rejected`() {
        val topology = CaseTopology(
            nodes = listOf(sourceNode("bad", CoreOperators.Ids.FILTER)),
            terminals = listOf(TerminalSpec("terminal", "bad")),
            placement = mapOf("bad" to 0),
        )
        val failure = assertThrows<IllegalArgumentException> { ScriptGenerator(config(), topology, Random(1L)) }
        failure.message!!.contains(CoreOperators.Ids.FILTER) shouldBe true
    }

    // --- knobs ORA1 §GEN-04 --------------------------------------------------------------

    /** `scriptLength` is the exact number of op steps, and this task emits no barriers. */
    @Test
    fun `scriptLength is the exact op-step count`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET, "k0" to CoreOperators.Ids.KEYED_SET)
        listOf(1, 7, 50, 233).forEach { length ->
            (1L..5L).forEach { seed ->
                val script = generate(
                    topology,
                    config(scriptLength = length, vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.KEYED_SET)),
                    seed,
                ).script
                withClue("scriptLength=$length, seed=$seed") {
                    script.steps.size shouldBe length
                    script.steps.count { it is CaseStep.Op } shouldBe length
                    script.steps.none { it is CaseStep.Barrier } shouldBe true
                }
            }
        }
    }

    /**
     * `addRemoveRatio` is the fraction of element events that are adds. The measured fraction
     * sits slightly *above* the knob at low settings, because a remove with no candidate at all
     * (nothing has been added yet at the top of a script) falls back to an add rather than being
     * resampled — a bounded, documented skew, not a retry loop.
     */
    @Test
    fun `addRemoveRatio drives the add to remove proportion`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET, "s1" to CoreOperators.Ids.SET)

        fun addFraction(ratio: Double): Double {
            var adds = 0
            var removes = 0
            (1L..25L).forEach { seed ->
                ops(generate(topology, config(addRemoveRatio = ratio), seed).script).forEach { op ->
                    when {
                        op.event is ScriptEvent.Add -> adds++
                        op.event.isRemove() -> removes++
                    }
                }
            }
            return adds.toDouble() / (adds + removes)
        }

        val low = addFraction(0.2)
        val mid = addFraction(0.5)
        val high = addFraction(0.8)
        withClue("measured add fractions: 0.2 -> $low, 0.5 -> $mid, 0.8 -> $high") {
            abs(low - 0.2) shouldBeUnder 0.10
            abs(mid - 0.5) shouldBeUnder 0.05
            abs(high - 0.8) shouldBeUnder 0.05
            (mid - low) shouldExceed 0.0
            (high - mid) shouldExceed 0.0
        }
    }

    /** `elementDomainSize` bounds the distinct element values, and a long enough script exercises all of them. */
    @Test
    fun `elementDomainSize bounds and is exercised by the generated element values`() {
        val topology = topologyOf("s0" to CoreOperators.Ids.SET)
        listOf(2, 4, 12).forEach { size ->
            val distinct = mutableSetOf<Any?>()
            (1L..5L).forEach { seed ->
                ops(generate(topology, config(scriptLength = 300, elementDomainSize = size), seed).script).forEach { op ->
                    when (val event = op.event) {
                        is ScriptEvent.Add -> distinct += event.element
                        is ScriptEvent.Remove -> distinct += event.element
                        else -> Unit
                    }
                }
            }
            withClue("elementDomainSize=$size produced $distinct") {
                distinct.size shouldBe size
                distinct shouldBe ElementDomains.elements(size).toSet()
            }
        }
    }

    // --- projection and determinism -------------------------------------------------------

    /** `toScript()` keeps each source's events, in order, and adds nothing. */
    @Test
    fun `toScript projects each source's events in order`() {
        val topology = topologyOf(
            "s0" to CoreOperators.Ids.SET,
            "s1" to CoreOperators.Ids.SET,
            "c0" to CoreOperators.Ids.COUNTER,
        )
        val config = config(vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.COUNTER))

        (1L..10L).forEach { seed ->
            val script = generate(topology, config, seed).script
            val projected = script.toScript()
            projected.sources().toSet() shouldBe ops(script).map { it.source }.toSet()
            projected.slices.forEach { slice ->
                slice.events shouldContainExactly ops(script).filter { it.source == slice.source }.map { it.event }
            }
            projected.slices.sumOf { it.events.size } shouldBe ops(script).size
        }
    }

    /** `ORA1 §GEN-01`: equal (topology, config, seed) produce structurally equal scripts and audits. */
    @Test
    fun `equal topology config and seed produce equal scripts`() {
        val topology = topologyOf(
            "s0" to CoreOperators.Ids.SET,
            "m0" to CoreOperators.Ids.MAP,
            "c0" to CoreOperators.Ids.COUNTER,
        )
        val config = config(
            vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.MAP, CoreOperators.Ids.COUNTER),
        )

        (1L..5L).forEach { seed ->
            val first = generate(topology, config, seed)
            val second = generate(topology, config, seed)
            first.script shouldBe second.script
            first.removeAudit shouldContainExactly second.removeAudit
        }
        // …and a different seed genuinely produces a different script.
        (generate(topology, config, 1L).script == generate(topology, config, 2L).script) shouldBe false
    }

    // --- helpers --------------------------------------------------------------------------

    private fun ScriptEvent.isRemove(): Boolean = this is ScriptEvent.Remove || this is ScriptEvent.RemoveKey

    /** [source]'s slice up to (not including) step [index] — the "prior slice" liveness is read on. */
    private fun priorEvents(steps: List<CaseStep>, index: Int, source: SourceId): List<ScriptEvent> =
        steps.take(index).filterIsInstance<CaseStep.Op>().filter { it.source == source }.map { it.event }

    /**
     * Whether the remove at [index] was genuinely observed, derived from the script alone.
     *
     * Set family: the removing writer added the element earlier in this source's slice, or an
     * `Observe` by that writer sits strictly between somebody's earlier add of it and this
     * remove — `Membership`'s observation rule, re-implemented here rather than called, so the
     * audit is checked against the rule and not against the generator's own bookkeeping.
     *
     * Keyed family: the removing writer put that key earlier in the slice (a keyed source has
     * no `Observe`).
     */
    private fun deriveObserved(steps: List<CaseStep>, index: Int): Boolean {
        val op = steps[index] as CaseStep.Op
        val prior = steps.take(index).filterIsInstance<CaseStep.Op>().filter { it.source == op.source }.map { it.event }
        return when (val event = op.event) {
            is ScriptEvent.Remove -> {
                if (prior.any { it is ScriptEvent.Add && it.writer == event.writer && it.element == event.element }) {
                    true
                } else {
                    prior.withIndex().any { (addPosition, candidate) ->
                        candidate is ScriptEvent.Add && candidate.element == event.element &&
                            (addPosition + 1 until prior.size).any { later ->
                                prior[later].let { it is ScriptEvent.Observe && it.writer == event.writer }
                            }
                    }
                }
            }

            is ScriptEvent.RemoveKey ->
                prior.any { it is ScriptEvent.Put && it.writer == event.writer && it.key == event.key }

            else -> error("step $index is not a remove: $op")
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T =
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }

    private infix fun <T : Comparable<T>> T.shouldBeUnder(bound: T) {
        if (this >= bound) throw AssertionError("$this should be < $bound")
    }

    private infix fun <T : Comparable<T>> T.shouldExceed(bound: T) {
        if (this <= bound) throw AssertionError("$this should be > $bound")
    }
}
