package civictech.oracle.tagged

import civictech.cell.graph.ConnectStep
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
 * BS-9 — `ORA2 §DIFF-07`: `run/WavePrefixOracle.kt` applied UNCHANGED to a tagged terminal, at
 * both shapes — a bare `orMap` source, and the two-path diamond BS-9 actually states.
 *
 * ## The diamond, and why it took two items to get here
 *
 * BS-9's `Given` reads "a tagged map feeding a glitch-free consumer through two paths" — the
 * [WavePrefixTest][civictech.oracle.run.WavePrefixTest] diamond shape (`set` fanning into
 * `filter`+`flatMapSet`, reconverging at `union`), rendered into the map vocabulary. That shape
 * was **not constructible** when this class was first written: `OrMapCell`'s outlet carries
 * `civictech.cell.data.delta.TaggedMapDelta`, every registered `MapOf`-consuming operator
 * (`join`/`combineLatest`/`lookupJoin` in `civictech.oracle.bind.CoreOperators`) is typed to the
 * plain `civictech.cell.data.delta.MapDelta`, and `orMap` itself is registered arity-0, so
 * nothing could legally sit downstream of a tagged terminal. That bound was filed in
 * `concord/corpus/DISPUTES.md` rather than papered over, and this class covered the strongest
 * thing that WAS constructible: a bare `orMap` source observed as its own terminal, honestly
 * narrower because with no fan-in there is no glitch the case could exhibit.
 *
 * `computenet-pez3` lifted the bound by registering `96 §E1.5`'s `UntagCell` under the catalog id
 * `untag` (`ShapeRule.unary(TaggedMapOf -> MapOf)`, with the independent reference model
 * `civictech.oracle.model.UntagModel`) — the first nonzero-arity entry that consumes a tagged
 * outlet, which makes `orMap -> untag -> join` a legal edge. `computenet-0zbq` built the diamond
 * over that bridge, and deleted the filing, which is what that entry's own `Resolves` clause
 * instructed. The closure is recorded in `civictech.oracle.run.OracleSweep`'s ledger KDoc and
 * pinned by `civictech.oracle.HonestyLedgerTest`.
 *
 * ## What each half covers, so the two are not read as one claim
 *
 * - **The bare source** (`BS-9 every intermediate observation of a tagged terminal ...`) —
 *   single-source, single-host, so [WavePrefixOracle.appliesTo] admits it, and every intermediate
 *   observation walks through [DifferentialRunner]'s real per-step observer with
 *   [WavePrefixOracle.Checker] unchanged. **No fan-in, so no tear is possible in this case**; its
 *   non-vacuity assertions exist so a reader can see exactly how much it does cover.
 * - **The diamond** (`BS-9 every intermediate observation of the tagged diamond ...`) — BS-9's own
 *   shape: one `orMap` source fanning out through two `untag` arms that reconverge at a `join`
 *   fan-in, the wave-prefix oracle observing that fan-in. Here a tear IS representable, and
 *   `a torn composite at the fan-in is rejected` is the control that shows the oracle rejects one,
 *   so the green above is a check that could have failed rather than a shape that cannot fail.
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

    // ------------------------------------------------------------ the BS-9 diamond

    /**
     * BS-9's stated shape, built: one `orMap` source fanning out through two `untag` arms that
     * reconverge at a `join` fan-in, observed at one terminal.
     *
     * ```
     *                  +-- uA (untag) --+
     *   src (orMap) ---+                +--- j (join) --- "joined"
     *                  +-- uB (untag) --+
     * ```
     *
     * `join` is the fan-in for [WavePrefixTest][civictech.oracle.run.WavePrefixTest]'s reason for
     * choosing `union` over `intersect`: its output value at a key is the PAIR
     * `(left, right)`, so both arms' contributions stay visible in the terminal's value and a
     * half-published wave — one arm refreshed, the other not — is a state no prefix contains.
     * That is the tear the bare-source case above cannot exhibit, and the
     * `a torn composite at the fan-in is rejected` control below is where it is demonstrated.
     *
     * Both arms are `untag`: it is the only registered entry that consumes a
     * [civictech.oracle.model.ElementShape.TaggedMapOf] outlet, and no registered unary operator
     * consumes the plain `MapOf` it produces, so a longer or a differently-shaped arm is not
     * available in today's vocabulary. The asymmetry that makes a tear observable comes from the
     * fan-in's pair values, not from the two arms computing different functions.
     */
    private fun taggedDiamondCase(script: CaseScript, seed: Long = 19L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", TaggedOperators.Ids.OR_MAP, emptyList(), source),
                TopologyNode("uA", TaggedOperators.Ids.UNTAG, listOf("src"), null),
                TopologyNode("uB", TaggedOperators.Ids.UNTAG, listOf("src"), null),
                TopologyNode("j", CoreOperators.Ids.JOIN, listOf("uA", "uB"), null),
            ),
            terminals = listOf(TerminalSpec("joined", "j")),
            placement = mapOf("src" to 0, "uA" to 0, "uB" to 0, "j" to 0),
        ),
        spec = GraphSpec(
            listOf(
                SpawnStep("src", factory(TaggedOperators.Ids.OR_MAP)),
                SpawnStep("uA", factory(TaggedOperators.Ids.UNTAG)),
                SpawnStep("uB", factory(TaggedOperators.Ids.UNTAG)),
                SpawnStep("j", factory(CoreOperators.Ids.JOIN)),
                ConnectStep("src", "outlet", "uA", "inlet"),
                ConnectStep("src", "outlet", "uB", "inlet"),
                ConnectStep("uA", "outlet", "j", "left"),
                ConnectStep("uB", "outlet", "j", "right"),
            ),
        ),
        script = script,
        removeAudit = emptyList(),
    )

    @Test
    fun `BS-9 every intermediate observation of the tagged diamond matches a wave prefix`() {
        val case = taggedDiamondCase(orMapScript())
        var checker: WavePrefixOracle.Checker? = null
        var settled: Map<String, ModelState>? = null

        val outcome = DifferentialRunner.run(
            case = case,
            wavePrefix = WavePrefixOption.ALWAYS,
            onWavePrefixChecker = { checker = it },
        )
        withClue("outcome=$outcome") { outcome shouldBe RunOutcome.Success }

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
            live.floorOf("joined") shouldBe live.prefixes.lastIndex
        }

        // the fan-in really does reconverge both arms: its value is the pair, so a wave that
        // reached one arm and not the other is a distinguishable state.
        val last = live.prefixes.last().getValue("joined").shouldBeInstanceOf<ModelState.MapState>()
        last.entries shouldBe mapOf("k1" to ("v1b" to "v1b"), "k2" to ("v2b" to "v2b"))

        DifferentialRunner.run(
            case = taggedDiamondCase(CaseScript(orMapScript().steps + CaseStep.Barrier)),
            wavePrefix = WavePrefixOption.ALWAYS,
        ) { settled = it } shouldBe RunOutcome.Success
        settled.shouldNotBeNull().getValue("joined").shouldBeInstanceOf<ModelState.MapState>()
            .entries shouldBe mapOf("k1" to ("v1b" to "v1b"), "k2" to ("v2b" to "v2b"))
    }

    /** [orMapScript]'s prefix list over the diamond terminal. */
    private fun diamondPrefixes(): Pair<WavePrefixOracle.Checker, List<ModelState>> {
        val case = taggedDiamondCase(orMapScript())
        val model = civictech.oracle.run.CaseExecution.referenceModelFor(case.topology)
        val checker = WavePrefixOracle.checker(case, "marker", civictech.oracle.run.Reference(model::eval))
        return checker to checker.prefixes.map { it.getValue("joined") }
    }

    @Test
    fun `a legal observation stream over the diamond terminal is accepted`() {
        val (checker, prefixes) = diamondPrefixes()
        prefixes.indices.forEach { index ->
            withClue("prefix $index must be admissible") {
                checker.observeTerminal("joined", prefixes[index]).shouldBeNull()
            }
        }
        checker.floorOf("joined") shouldBe prefixes.lastIndex
    }

    @Test
    fun `a torn composite at the fan-in is rejected`() {
        val (checker, prefixes) = diamondPrefixes()
        // the re-put of k1 (script step 3) is the wave the two arms can disagree about: the
        // left arm has published v1b while the right arm still holds v1.
        val torn = ModelState.MapState(mapOf("k1" to ("v1b" to "v1"), "k2" to ("v2" to "v2")))
        withClue("must genuinely be no prefix, or this control proves nothing") {
            prefixes.contains(torn) shouldBe false
        }
        val violation = checker.observeTerminal("joined", torn).shouldNotBeNull()
        violation.kind shouldBe RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
    }
}
