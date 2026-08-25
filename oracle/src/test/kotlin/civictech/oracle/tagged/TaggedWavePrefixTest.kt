package civictech.oracle.tagged

import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.WavePrefixOption
import civictech.oracle.run.WavePrefixOracle
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BS-9 — `ORA2 §DIFF-07`: `run/WavePrefixOracle.kt` applied UNCHANGED to a tagged terminal.
 *
 * ## Why this is a bare `orMap` source, not a fan-in downstream of one
 *
 * The feature's BS-9 `Given` reads "a tagged map feeding a glitch-free consumer through two
 * paths" — the [WavePrefixTest] diamond shape (`set` fanning into `filter`+`flatMapSet`,
 * reconverging at `union`). That shape is NOT constructible for `orMap` today: every registered
 * operator that consumes `civictech.oracle.bind.ShapeRule`'s `MapOf` shape
 * (`join`/`combineLatest`/`lookupJoin` in `civictech.oracle.bind.CoreOperators`) is typed to the
 * kernel's plain `civictech.cell.data.delta.MapDelta`, not `civictech.cell.data.delta.TaggedMapDelta`
 * — wiring an `OrMapCell` outlet into one would be a genuine kernel type violation, the same
 * finding `civictech.oracle.tagged.TaggedSweepTest` and `civictech.oracle.tagged.ConvergenceSweepTest`
 * record independently. `civictech.oracle.bind.TaggedOperators`'s own file KDoc confirms `orMap` is
 * registered as a SOURCE ONLY, with no tagged-aware downstream operator existing in the kernel yet
 * (that is `96 §E1.5`'s `UntagCell`/`TaggedMapView`, explicitly out of this feature's scope). So
 * there is no fan-in a generated case could legally place downstream of a tagged terminal today.
 *
 * What IS constructible, and is the strongest demonstration available against today's kernel, is
 * a bare `orMap` source observed directly as its own terminal — single-source, single-host, so
 * [WavePrefixOracle.appliesTo] admits it, and every intermediate observation walks through
 * [DifferentialRunner]'s real per-step observer, [WavePrefixOracle.Checker] unchanged. It is
 * honestly narrower than the diamond shape (no fan-in to tear, so there is no glitch this
 * particular case COULD exhibit) — the non-vacuity assertions below exist so a reader can see
 * exactly how much the case does cover (every wave observed, the floor never regressing, the
 * terminal reaching the final prefix) rather than trust the green outcome alone.
 */
class TaggedWavePrefixTest {

    private val writer = WriterId("w")
    private val source = SourceId("s")

    @BeforeEach
    fun registerCatalog() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        TaggedOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel

    /** A single `orMap` source, observed directly as its own terminal. */
    private fun bareOrMapCase(script: CaseScript, seed: Long = 9L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(TopologyNode("src", TaggedOperators.Ids.OR_MAP, emptyList(), source)),
            terminals = listOf(TerminalSpec("tagged", "src")),
            placement = mapOf("src" to 0),
        ),
        spec = GraphSpec(listOf(SpawnStep("src", factory(TaggedOperators.Ids.OR_MAP)))),
        script = script,
        removeAudit = emptyList(),
    )

    /** Five puts/removes over two keys, chosen so consecutive prefixes actually differ. */
    private fun orMapScript() = CaseScript(
        listOf(
            CaseStep.Op(source, ScriptEvent.Put(writer, "k1", "v1")),
            CaseStep.Op(source, ScriptEvent.Put(writer, "k2", "v2")),
            CaseStep.Op(source, ScriptEvent.Put(writer, "k1", "v1b")), // re-put: retract + add, one wave
            CaseStep.Op(source, ScriptEvent.RemoveKey(writer, "k2")),
            CaseStep.Op(source, ScriptEvent.Put(writer, "k2", "v2b")),
        ),
    )

    @Test
    fun `BS-9 every intermediate observation of a tagged terminal matches a wave prefix`() {
        val case = bareOrMapCase(orMapScript())
        var checker: WavePrefixOracle.Checker? = null
        var settled: Map<String, ModelState>? = null

        val outcome = DifferentialRunner.run(
            case = case,
            wavePrefix = WavePrefixOption.ALWAYS,
            onWavePrefixChecker = { checker = it },
        )
        withClue("outcome=$outcome") { outcome shouldBe RunOutcome.Success }

        // non-vacuity: the check actually ran, took real observations, and the terminal reached
        // the final prefix rather than stalling on an early one.
        val live = checker.shouldNotBeNull()
        withClue("the case must be eligible and selected, so the check actually ran") {
            WavePrefixOracle.appliesTo(case) shouldBe true
        }
        withClue("one prefix per Op plus the empty-input prefix") {
            live.prefixes.size shouldBe orMapScript().steps.size + 1
        }
        withClue("observations are taken per productive scheduler step; five ops cannot be zero") {
            live.observations shouldBeGreaterThanOrEqualTo 5
        }
        withClue("the terminal must have reached the LAST prefix, not stalled on an early one") {
            live.floorOf("tagged") shouldBe live.prefixes.lastIndex
        }

        // and the terminal genuinely holds the settled value — read at a trailing Barrier, the
        // only live read the run API exposes.
        DifferentialRunner.run(
            case = bareOrMapCase(CaseScript(orMapScript().steps + CaseStep.Barrier)),
            wavePrefix = WavePrefixOption.ALWAYS,
        ) { settled = it } shouldBe RunOutcome.Success
        settled.shouldNotBeNull().getValue("tagged").shouldBeInstanceOf<ModelState.MapState>()
            .entries shouldBe mapOf("k1" to "v1b", "k2" to "v2b")
    }

    // ------------------------------------------------------- discrimination control

    /** [orMapScript]'s prefix list, for feeding the checker fabricated observation streams. */
    private fun orMapPrefixes(): Pair<WavePrefixOracle.Checker, List<ModelState>> {
        val case = bareOrMapCase(orMapScript())
        val model = civictech.oracle.run.CaseExecution.referenceModelFor(case.topology)
        val checker = WavePrefixOracle.checker(case, "marker", civictech.oracle.run.Reference(model::eval))
        return checker to checker.prefixes.map { it.getValue("tagged") }
    }

    @Test
    fun `a legal observation stream over the tagged terminal is accepted`() {
        val (checker, prefixes) = orMapPrefixes()
        prefixes.indices.forEach { index ->
            withClue("prefix $index must be admissible") {
                checker.observeTerminal("tagged", prefixes[index]).shouldBeNull()
            }
        }
        checker.floorOf("tagged") shouldBe prefixes.lastIndex
    }

    @Test
    fun `a state matching no prefix at all is rejected`() {
        val (checker, prefixes) = orMapPrefixes()
        checker.observeTerminal("tagged", prefixes[1]).shouldBeNull()
        // a value no prefix of this script ever holds: three keys, this script only ever names two
        val impossible = ModelState.MapState(mapOf("k1" to "v1", "k2" to "v2", "k3" to "v3"))
        withClue("must genuinely be no prefix, or this control proves nothing") {
            prefixes.contains(impossible) shouldBe false
        }
        val violation = checker.observeTerminal("tagged", impossible).shouldNotBeNull()
        violation.kind shouldBe RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
    }
}
