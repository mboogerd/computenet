package civictech.oracle.corpus

import civictech.oracle.model.Aggregates
import civictech.oracle.model.CountModel
import civictech.oracle.model.CounterSourceModel
import civictech.oracle.model.ElementCombiner
import civictech.oracle.model.ElementExpansion
import civictech.oracle.model.ElementKey
import civictech.oracle.model.ElementPredicate
import civictech.oracle.model.FilterModel
import civictech.oracle.model.FlatMapSetModel
import civictech.oracle.model.GroupByModel
import civictech.oracle.model.IntersectSetModel
import civictech.oracle.model.JoinSetModel
import civictech.oracle.model.KeyedSetSourceModel
import civictech.oracle.model.LongSelector
import civictech.oracle.model.MapCellSourceModel
import civictech.oracle.model.ModelNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.NodeId
import civictech.oracle.model.PnCounterSourceModel
import civictech.oracle.model.PresenceCountModel
import civictech.oracle.model.QuorumSetModel
import civictech.oracle.model.ReferenceModel
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SemiJoinModel
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.SingleInstanceOrMapModel
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.UnionSetModel
import civictech.oracle.model.WriterId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Epic computenet-4ru §8's corpus cross-check (computenet-4ru.10.3): at least the operator
 * behaviours covered by hand-authored `concord/corpus/24-data-cells` yaml scenarios must
 * produce the same answers under the oracle's [ReferenceModel], so a disagreement between the
 * two harnesses (`concord` vs `:oracle`) is caught at build time rather than in review.
 *
 * ## Shape
 *
 * Each `24-OP-*`/`24-REPLAY-*`/`24-TMAP-*` id below either:
 *
 * (a) has a `@Test` that re-states its yaml's `graph:`/`script:`/`checks:` as a hand-built
 *     [ReferenceModel] and asserts [ReferenceModel.eval] agrees with the yaml's `final-view`
 *     expectation — every such test's backtick name embeds `<id>.yaml` literally, which
 *     [completeness guard][the completeness test below] cross-references so a case can never
 *     silently lose its citation while staying counted as covering the file; or
 * (b) is named in [OUT_OF_VOCABULARY] with a written reason the scenario is outside what
 *     `ORA1 §MODEL-02`'s registered vocabulary can honestly model — cross-referencing the
 *     `ORA1 §HONEST-02` exclusion ledger in
 *     `oracle/src/main/kotlin/civictech/oracle/model/MapCellModel.kt` where the exclusion is
 *     already recorded there, and stating the reason directly here where it is not.
 *
 * Transcription, not YAML parsing (the feature-level decision, computenet-4ru.10's own KDoc):
 * `:oracle` carries no YAML dependency and its own `ModuleDependencyTest` bars one on
 * `:concord`. Every case below is checked BY HAND against the cited yaml's `script:`/`checks:`
 * text and, where the corresponding kernel binding is not the same class the oracle's
 * `CoreOperators` catalog registers under a similar-sounding id (`24-OP-JOIN-01`,
 * `24-OP-LOOKUP-01`), against `concord/src/main/kotlin/civictech/concord/driver/kernel/
 * KernelCatalog.kt`'s actual binding, cited at the case.
 *
 * If the model had genuinely disagreed with a corpus scenario, that would be a finding to
 * report and park (`ORA1 §HONEST-02`'s never-modelled-approximately rule) rather than a
 * transcription to bend until green — every case below reproduces its yaml's expectation.
 */
class CorpusCrossCheckTest {

    private val writer = WriterId("w")

    /** The pair's first component — the canonical join/group key of a `(key, value)` stream. */
    private val firstOfPair = ElementKey { element -> (element as Pair<*, *>).first }

    /** The pair's second component as a `Long` — the width `sumOf`/`maxOf` selectors need here. */
    private val secondAsLong = object : LongSelector {
        override fun selectLong(element: Any?): Long = ((element as Pair<*, *>).second as Number).toLong()
        override fun toString(): String = "secondAsLong"
    }

    // =========================================================================================
    // The completeness guard
    // =========================================================================================

    /**
     * Every `24-data-cells` yaml id this file cross-checks with a case. Read back by the
     * completeness test below against the real directory listing — never the other way round.
     */
    private val CROSS_CHECKED: Set<String> = setOf(
        "24-OP-COUNT-01",
        "24-OP-COUNTER-01",
        "24-OP-FILTER-01",
        "24-OP-FLATMAP-01",
        "24-OP-GROUPBY-01",
        "24-OP-GROUPBY-02",
        "24-OP-INTERSECT-01",
        "24-OP-JOIN-01",
        "24-OP-KEYEDSET-01",
        "24-OP-LOOKUP-01",
        "24-OP-MAP-01",
        "24-OP-MAPFN-01",
        "24-OP-PARTITION-01",
        "24-OP-PNCOUNTER-01",
        "24-OP-PRESENCE-01",
        "24-OP-QUORUM-01",
        "24-OP-SEMIJOIN-01",
        "24-OP-SET-01",
        "24-OP-UNION-01",
        "24-OP-UNION-02",
        "24-OP-WINDOW-01",
        "24-OP-WINDOW-02",
        "24-TMAP-LWW-01",
        "24-TMAP-PRESENCE-01",
        "24-TMAP-RESET-01",
    )

    /**
     * Every `24-data-cells` yaml id NOT cross-checked, with a written reason it is outside
     * `ORA1 §MODEL-02`'s registered vocabulary — verified against the cited kernel/ledger
     * source, never an approximation offered so this test passes.
     */
    private val OUT_OF_VOCABULARY: Map<String, String> = mapOf(
        "24-BOUND-01" to (
            "A bounded/paginated `read-state` walk over a cell's live state (page size, page " +
                "stamps, no-dupes-across-pages) is an observation-instrument property, not a " +
                "batch fold: ReferenceModel.eval produces one whole terminal ModelState, never a " +
                "paginated cursor, so there is nothing in this vocabulary a page-boundary defect " +
                "could show up against."
            ),
        "24-BOUND-02" to (
            "The limit sweep over the same bounded-read facility as 24-BOUND-01 — same reason: " +
                "no model of a paginated read exists or is needed for a batch reference."
            ),
        "24-GEN-01" to (
            "`kind: generative` — no fixed `graph:`/`script:`/`final-view` to transcribe. " +
                "`civictech.concord.generator.ScenarioGenerator` synthesizes a new pipeline and " +
                "SimulationController schedule per instance index; there is no single " +
                "hand-authored case here, only a property (incremental == batch, views converge) " +
                "checked over randomly generated graphs — this feature's generator counterpart is " +
                "computenet-4ru.6, not a corpus scenario this cross-check restates."
            ),
        "24-OP-COMBINE-01" to (
            "The PLAIN scalar `combine-latest fn: sum` (two independent counter/VALUE sources " +
                "folded to one sum) binds, per concord's own KernelCatalog.kt, to " +
                "`ScalarSumCombineCell` — a different kernel cell from the oracle's registered " +
                "`combineLatest` entry (`CombineLatestCell`, the keyed MAP-shaped OUTER combine, " +
                "`ShapeRule.binary(SCALAR_MAP, SCALAR_MAP, SCALAR_MAP)` in CoreOperators.kt). " +
                "Neither `ScalarSumCombineCell` nor any scalar-sum ReferenceOp is registered under " +
                "CoreOperators.Ids.ALL; modelling this scenario as CombineLatestModel would be " +
                "checking the wrong cell, not the one this yaml actually drives."
            ),
        "24-OP-COMBINE-02" to (
            "The wave-coalescing `glitch-free: true` form binds to `CoalescingCombineCell`, which " +
                "the ORA1 §HONEST-02 ledger in MapCellModel.kt excludes by name: its whole reason " +
                "to exist is a wave-completion fold over open/closed edges, per-edge watermarks and " +
                "a restart-observable dropped version buffer that Script/ScriptEvent has no way to " +
                "name, so a batch reference cannot honestly certify its quiescent total."
            ),
        "24-OP-LIST-01" to (
            "`ListCell`/`ListDelta` is excluded by the ORA1 §HONEST-02 ledger in MapCellModel.kt: " +
                "its edits are index-addressed, not key-addressed, so even MapCell's single-writer " +
                "FIFO restriction would leave the script silent on the one thing that decides the " +
                "result — whether a later index-addressed edit is stated against positions before " +
                "or after an earlier edit shifted them."
            ),
        "24-REPLAY-01" to (
            "A durable/crash/journal-replay scenario (`journal-set-source`, a `journal` " +
                "controller, `despawn` as the crash+recover handle). Script/ScriptEvent has no " +
                "crash, no journal, no baseline-install event to drive, and QuorumSetModel's own " +
                "KDoc already records that recovery deliveries and topology churn are outside what " +
                "a static-arm-count batch reference defines. Durability/replication/crash-restart " +
                "is CHA1/CHA3's scope (epic computenet-4ru §6's OrMapCell note), not ORA1's."
            ),
        "24-TMAP-MERGE-01" to (
            "`profile: dist`, two `ormap-source` replicas of one logical id gossiping " +
                "TaggedMapDeltas, with convergence-only checks (`replicas-converge`, " +
                "`views-converge`) and deliberately NO `final-view` golden. Two independent " +
                "reasons it is not this file's to transcribe, both verified against source. " +
                "(1) The catalog binding: `civictech.oracle.bind.TaggedOperators` registers " +
                "`orMap` as `SingleInstanceOrMapModel`, whose `evaluate` REFUSES BY NAME any " +
                "SourceScript carrying deliveries (TaggedKeyedModels.kt: DotModel's " +
                "cross-instance merge needs the peer instance's own event log, which one " +
                "SourceScript does not carry) — so the registered source model cannot evaluate " +
                "a two-replica slice at all, and a single-instance transcription would be " +
                "checking a different scenario. (2) Even reaching past the registration to " +
                "`DotModel` directly — which IS multi-instance and does define " +
                "`converged`/`perInstance` — would not restate THIS yaml: DotModel's only " +
                "causality input is an explicit `Delivery(from, afterEvents, throughEvents)`, " +
                "and the yaml states no interleaving whatsoever. It hands the delivery order to " +
                "concord's schedule sweep precisely because [24-TMAP-01]'s law is " +
                "order-insensitivity; picking one delivery schedule here would be inventing a " +
                "trace the scenario refuses to pin, i.e. the approximation ORA1 §HONEST-02 " +
                "forbids. The replicated-mesh differential is the sweep/runner's own " +
                "(`OracleSweep`, `TaggedControlsTest`'s ORA2 §CTL-04 missing-gossip control), " +
                "which drives DotModel over a multi-instance Script with stated deliveries — " +
                "not this yaml-transcription file."
            ),
    )

    /** Walks up from the Gradle Test task's working directory (`:oracle`'s project dir) to the repo root. */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /**
     * The completeness guard ORA1 §MODEL-02's cross-check clause needs: every real
     * `24-data-cells` yaml, enumerated from the repository tree at test time — never a
     * hardcoded count — is either cross-checked by name or excluded with a reason. A future
     * hand-authored yaml over a modelled operator, or a case whose citation is deleted while its
     * id stays listed, reddens this test.
     */
    @Test
    fun `every 24-data-cells corpus yaml is cross-checked or listed with a written out-of-vocabulary reason`() {
        val corpusDir = File(repoRoot(), "concord/corpus/24-data-cells")
        withClue("corpus directory must exist and be a directory: $corpusDir") {
            corpusDir.isDirectory shouldBe true
        }
        val ids = corpusDir.listFiles { file -> file.extension == "yaml" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: error("listFiles returned null for $corpusDir — not a readable directory")

        withClue("non-vacuity: a broken directory listing would silently check nothing") {
            ids.size shouldBe 33
        }

        val unaccounted = ids - CROSS_CHECKED - OUT_OF_VOCABULARY.keys
        withClue(
            "yaml files neither cross-checked by a case nor listed with an out-of-vocabulary " +
                "reason (the completeness guard this test exists for): $unaccounted",
        ) {
            unaccounted.shouldBeEmpty()
        }

        val overlap = CROSS_CHECKED intersect OUT_OF_VOCABULARY.keys
        withClue("an id cannot be both cross-checked AND excluded: $overlap") {
            overlap.shouldBeEmpty()
        }

        val stale = (CROSS_CHECKED + OUT_OF_VOCABULARY.keys) - ids
        withClue(
            "ids bookkept here that no longer correspond to a real corpus file — stale " +
                "bookkeeping that should shrink, not a fact about the corpus: $stale",
        ) {
            stale.shouldBeEmpty()
        }

        OUT_OF_VOCABULARY.forEach { (id, reason) ->
            withClue("$id's out-of-vocabulary reason must be a real written reason, not empty") {
                reason.isBlank() shouldBe false
            }
        }

        // Self-citation: every cross-checked id must literally name its own yaml path inside
        // THIS file's own source, so the citation cannot silently rot away from the id it backs.
        val ownSource = File(
            repoRoot(),
            "oracle/src/test/kotlin/civictech/oracle/corpus/CorpusCrossCheckTest.kt",
        ).readText()
        val uncited = CROSS_CHECKED.filterNot { id -> ownSource.contains("$id.yaml") }
        withClue(
            "cross-checked ids whose source yaml path ('<id>.yaml') is not literally cited " +
                "anywhere in this test file: $uncited",
        ) {
            uncited.shouldBeEmpty()
        }
    }

    // =========================================================================================
    // Sources
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-SET-01.yaml */
    @Test
    fun `24-OP-SET-01_yaml - set source emits only effective deltas, a never-added remove is a no-op`() {
        val a = SourceId("a")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("a"), a, SetSourceModel))
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, "apple"),
            ScriptEvent.Add(writer, "pear"),
            ScriptEvent.Remove(writer, "apple"),
            ScriptEvent.Remove(writer, "plum"), // never added — the no-op case 24-SET-01 requires
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("pear"))
    }

    /** concord/corpus/24-data-cells/24-OP-COUNTER-01.yaml */
    @Test
    fun `24-OP-COUNTER-01_yaml - counter merges concurrent writers by addition`() {
        val n = SourceId("n")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("n"), n, CounterSourceModel))
        val script = Script.of(
            n,
            ScriptEvent.Increment(writer, 5), // writer 1
            ScriptEvent.Increment(writer, 37), // writer 2
            ScriptEvent.Decrement(writer, 6), // writer 1
            ScriptEvent.Increment(writer, 1), // writer 2, five unit steps
            ScriptEvent.Increment(writer, 1),
            ScriptEvent.Increment(writer, 1),
            ScriptEvent.Increment(writer, 1),
            ScriptEvent.Increment(writer, 1),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.ScalarState(41L))
    }

    /** concord/corpus/24-data-cells/24-OP-PNCOUNTER-01.yaml */
    @Test
    fun `24-OP-PNCOUNTER-01_yaml - pn-counter folds per-source increments and decrements to a net total`() {
        val pn = SourceId("pn")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("pn"), pn, PnCounterSourceModel))
        val script = Script.of(pn, ScriptEvent.Increment(writer, 64), ScriptEvent.Decrement(writer, 22))

        model.eval(script) shouldBe mapOf("v" to ModelState.ScalarState(42L))
    }

    /** concord/corpus/24-data-cells/24-OP-KEYEDSET-01.yaml */
    @Test
    fun `24-OP-KEYEDSET-01_yaml - keyed-set upsert folds to the current set of held elements`() {
        val ks = SourceId("ks")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("ks"), ks, KeyedSetSourceModel))
        val script = Script.of(
            ks,
            ScriptEvent.Put(writer, "k1", "x"),
            ScriptEvent.Put(writer, "k1", "y"), // LWW: k1 now y
            ScriptEvent.Put(writer, "k2", "z"),
            ScriptEvent.RemoveKey(writer, "k1"),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("z"))
    }

    /** concord/corpus/24-data-cells/24-OP-MAP-01.yaml */
    @Test
    fun `24-OP-MAP-01_yaml - map source is last-writer-wins per key, single-stream semantics`() {
        val m = SourceId("m")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("m"), m, MapCellSourceModel))
        val script = Script.of(
            m,
            ScriptEvent.Put(writer, "k1", 1),
            ScriptEvent.Put(writer, "k1", 2),
            ScriptEvent.Put(writer, "k2", 9),
            ScriptEvent.RemoveKey(writer, "k2"),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>("k1" to 2)))
    }

    /**
     * concord/corpus/24-data-cells/24-TMAP-PRESENCE-01.yaml
     *
     * `[24-TMAP-02]` add-wins presence, in the half a single stream can state: after a `remove`
     * covers k1's only live dot, k1 is ABSENT from the folded map — not present carrying null,
     * not an empty entry. [ModelState.MapState] compares the whole table, so a key that
     * reappeared under any rendering fails this assertion rather than passing unnoticed, which
     * is the same instrument the yaml's whole-map `final-view` uses.
     */
    @Test
    fun `24-TMAP-PRESENCE-01_yaml - a removed OR-map key is absent from the fold, not present-with-null`() {
        val om = SourceId("om")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("om"), om, SingleInstanceOrMapModel))
        val script = Script.of(
            om,
            ScriptEvent.Put(writer, "k1", "alpha"),
            ScriptEvent.Put(writer, "k2", "beta"),
            ScriptEvent.RemoveKey(writer, "k1"), // covers k1's only live dot ⇒ no live dot ⇒ absent
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>("k2" to "beta")))
    }

    /**
     * concord/corpus/24-data-cells/24-TMAP-LWW-01.yaml
     *
     * `[24-TMAP-03]`: a key exposes its live dot maximal under `(counter, sourceId)`, never a
     * wall clock. The model's independence here is the point — [DotModel] mints its own
     * `ModelDot(counter, source)` from the script position of each put and never reads a kernel
     * `Timestamp`, and with one source the sourceId component is never consulted, so "the later
     * put in file order wins" is derived from dot order rather than assumed from arrival order.
     */
    @Test
    fun `24-TMAP-LWW-01_yaml - an OR-map key exposes its greatest-dot value, the later put on one stream`() {
        val om = SourceId("om")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("om"), om, SingleInstanceOrMapModel))
        val script = Script.of(
            om,
            ScriptEvent.Put(writer, "k1", "first"),
            ScriptEvent.Put(writer, "k1", "second"), // greater dot at k1
            ScriptEvent.Put(writer, "k2", "other"),
        )

        model.eval(script) shouldBe mapOf(
            "v" to ModelState.MapState(mapOf<Any?, Any?>("k1" to "second", "k2" to "other")),
        )
    }

    /**
     * concord/corpus/24-data-cells/24-TMAP-RESET-01.yaml
     *
     * `[24-TMAP-04]` reset-remove: `remove(k)` tombstones exactly the dots live at k when it
     * ran, so the re-put's FRESH dot — minted after the remove and therefore unobserved by it —
     * survives and k1 is live again, while the unrelated k0 is untouched (a reset-remove is
     * per-key, not a map-wide reset). The yaml's own header records that on one stream this does
     * not discriminate a remove that covered only the newest dot; the model reproduces the same
     * script under the same limitation rather than reaching for a stronger claim.
     */
    @Test
    fun `24-TMAP-RESET-01_yaml - an OR-map remove covers the dots it observed, a later put's fresh dot survives`() {
        val om = SourceId("om")
        val model = ReferenceModel.terminal("v", ModelNode.Source(NodeId("om"), om, SingleInstanceOrMapModel))
        val script = Script.of(
            om,
            ScriptEvent.Put(writer, "k0", "untouched"),
            ScriptEvent.Put(writer, "k1", "first"),
            ScriptEvent.Put(writer, "k1", "second"), // the put's own local reset-remove covers `first`
            ScriptEvent.RemoveKey(writer, "k1"), // covers k1's one live dot
            ScriptEvent.Put(writer, "k1", "revived"), // fresh dot, unobserved by the remove above
        )

        model.eval(script) shouldBe mapOf(
            "v" to ModelState.MapState(mapOf<Any?, Any?>("k0" to "untouched", "k1" to "revived")),
        )
    }

    // =========================================================================================
    // Unary operators
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-FILTER-01.yaml */
    @Test
    fun `24-OP-FILTER-01_yaml - filter passes only elements satisfying the predicate`() {
        val even = ElementPredicate { element -> (element as Int) % 2 == 0 }
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("f"), FilterModel(even), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, 1),
            ScriptEvent.Add(writer, 2),
            ScriptEvent.Add(writer, 3),
            ScriptEvent.Add(writer, 4),
            ScriptEvent.Add(writer, 5),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState(2, 4))
    }

    /** concord/corpus/24-data-cells/24-OP-FLATMAP-01.yaml */
    @Test
    fun `24-OP-FLATMAP-01_yaml - flatmap expands each element into a set, unioning colliding preimages' tags`() {
        val identityListExpansion = ElementExpansion { element -> (element as List<*>).map { it } }
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("fm"), FlatMapSetModel(identityListExpansion), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, listOf(1, 2)),
            ScriptEvent.Add(writer, listOf(2, 3)),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState(1, 2, 3))
    }

    /**
     * concord/corpus/24-data-cells/24-OP-MAPFN-01.yaml
     *
     * "Map applies a pure transform element-wise" (`fn: add(10)`) binds to the same
     * `FlatMapSetCell` family as `flatmap` (the yaml's own mapping note; `mapSet` in
     * `CoreOperators` is exactly `FlatMapSetModel` with a singleton-image expansion).
     */
    @Test
    fun `24-OP-MAPFN-01_yaml - map applies a pure transform element-wise, a singleton-image flatmap`() {
        val addTen = ElementExpansion { element -> listOf((element as Int) + 10) }
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("m"), FlatMapSetModel(addTen), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(a, ScriptEvent.Add(writer, 1), ScriptEvent.Add(writer, 2), ScriptEvent.Add(writer, 3))

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState(11, 12, 13))
    }

    /** concord/corpus/24-data-cells/24-OP-COUNT-01.yaml */
    @Test
    fun `24-OP-COUNT-01_yaml - count emits the current distinct membership size`() {
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("cnt"), CountModel, NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, "x"),
            ScriptEvent.Add(writer, "y"),
            ScriptEvent.Add(writer, "z"),
            ScriptEvent.Remove(writer, "y"),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.ScalarState(2L))
    }

    // =========================================================================================
    // Fan-in operators
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-UNION-01.yaml */
    @Test
    fun `24-OP-UNION-01_yaml - union of two set sources equals batch union`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("u"), UnionSetModel, NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(
                        ScriptEvent.Add(writer, "apple"),
                        ScriptEvent.Add(writer, "plum"),
                        ScriptEvent.Remove(writer, "apple"),
                    ),
                ),
                SourceScript(b, listOf(ScriptEvent.Add(writer, "pear"))),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("pear", "plum"))
    }

    /** concord/corpus/24-data-cells/24-OP-UNION-02.yaml */
    @Test
    fun `24-OP-UNION-02_yaml - union converges the same regardless of which inlet each source lands on`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("u"), UnionSetModel, NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(
                        ScriptEvent.Add(writer, "apple"),
                        ScriptEvent.Add(writer, "plum"),
                        ScriptEvent.Add(writer, "kiwi"),
                        ScriptEvent.Remove(writer, "apple"),
                        // only retracts a's own kiwi tag — b's independently-minted kiwi survives
                        ScriptEvent.Remove(writer, "kiwi"),
                    ),
                ),
                SourceScript(b, listOf(ScriptEvent.Add(writer, "pear"), ScriptEvent.Add(writer, "kiwi"))),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("pear", "plum", "kiwi"))
    }

    /** concord/corpus/24-data-cells/24-OP-PRESENCE-01.yaml */
    @Test
    fun `24-OP-PRESENCE-01_yaml - presence-count emits, per element, how many live source links assert it`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("pc"), PresenceCountModel, NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(
                        ScriptEvent.Add(writer, "p"),
                        ScriptEvent.Add(writer, "q"),
                        ScriptEvent.Remove(writer, "q"),
                    ),
                ),
                SourceScript(b, listOf(ScriptEvent.Add(writer, "p"))),
            ),
        )

        // PresenceCountModel's counts are `Int` (mirroring PresenceCountCell's MapDelta<E, Int>).
        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>("p" to 2)))
    }

    /** concord/corpus/24-data-cells/24-OP-QUORUM-01.yaml */
    @Test
    fun `24-OP-QUORUM-01_yaml - quorum-set admits an element once k of n live sources assert it`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val cc = SourceId("cc")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("q"), QuorumSetModel { 2 }, NodeId("a"), NodeId("b"), NodeId("cc")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
            ModelNode.Source(NodeId("cc"), cc, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(a, listOf(ScriptEvent.Add(writer, 1), ScriptEvent.Add(writer, 2))),
                SourceScript(b, listOf(ScriptEvent.Add(writer, 2), ScriptEvent.Add(writer, 3))),
                SourceScript(cc, listOf(ScriptEvent.Add(writer, 2))),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState(2))
    }

    // =========================================================================================
    // Binary set / keyed-join operators
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-INTERSECT-01.yaml */
    @Test
    fun `24-OP-INTERSECT-01_yaml - intersect emits exactly the elements live on both sides`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("s"), IntersectSetModel, NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(
                        ScriptEvent.Add(writer, "x"),
                        ScriptEvent.Add(writer, "y"),
                        ScriptEvent.Add(writer, "z"),
                        ScriptEvent.Remove(writer, "y"),
                    ),
                ),
                SourceScript(
                    b,
                    listOf(
                        ScriptEvent.Add(writer, "y"),
                        ScriptEvent.Add(writer, "z"),
                        ScriptEvent.Add(writer, "w"),
                    ),
                ),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("z"))
    }

    /**
     * concord/corpus/24-data-cells/24-OP-JOIN-01.yaml
     *
     * The concrete kernel binding this yaml's `type: join` cell actually reaches — per the
     * yaml's own mapping note AND `concord`'s `KernelCatalog.kt` (`"join" -> JoinSetCell(...,
     * combine = { a, b -> listOf(keyOf(a), valueOf(a), valueOf(b)) })`) — is the relational
     * equi-join `JoinSetCell`, matching `JoinSetModel` (`CoreOperators.Ids.JOIN_SET`), NOT
     * `CoreOperators.Ids.JOIN` (`JoinCell`, the unrelated map-shaped LWW dictionary join). The
     * combine below is transcribed verbatim from that binding: `[key, leftValue, rightValue]`.
     */
    @Test
    fun `24-OP-JOIN-01_yaml - relational equi-join emits one pair per live matching row, many-to-many`() {
        val combine = ElementCombiner { left, right ->
            val l = left as Pair<*, *>
            val r = right as Pair<*, *>
            listOf(l.first, l.second, r.second)
        }
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("j"), JoinSetModel(firstOfPair, firstOfPair, combine), NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(ScriptEvent.Add(writer, "k1" to "L1"), ScriptEvent.Add(writer, "k2" to "L2")),
                ),
                SourceScript(
                    b,
                    listOf(
                        ScriptEvent.Add(writer, "k1" to "R1"),
                        ScriptEvent.Add(writer, "k2" to "R2a"),
                        ScriptEvent.Add(writer, "k2" to "R2b"),
                        ScriptEvent.Remove(writer, "k2" to "R2a"),
                    ),
                ),
            ),
        )

        model.eval(script) shouldBe mapOf(
            "v" to ModelState.SetState(listOf("k1", "L1", "R1"), listOf("k2", "L2", "R2b")),
        )
    }

    /**
     * concord/corpus/24-data-cells/24-OP-LOOKUP-01.yaml
     *
     * Per the yaml's own mapping note AND `KernelCatalog.kt` (`"lookup-join" -> JoinSetCell(...,
     * combine = { a, b -> listOf(a, valueOf(b)) })`), concord's `lookup-join` ALSO binds to
     * `JoinSetCell`, not to `CoreOperators.Ids.LOOKUP_JOIN` (`LookupJoinCell`, the unrelated
     * MAP-shaped fact/dimension join `CoreOperators` registers under that near-identical name).
     * `JoinSetModel` with the enrich combine below is the honest transcription target.
     */
    @Test
    fun `24-OP-LOOKUP-01_yaml - lookup-join enriches each left element, dropping unmatched rows`() {
        val enrich = ElementCombiner { left, right -> listOf(left, (right as Pair<*, *>).second) }
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("lj"), JoinSetModel(firstOfPair, firstOfPair, enrich), NodeId("a"), NodeId("b")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(ScriptEvent.Add(writer, "k1" to "v1"), ScriptEvent.Add(writer, "k2" to "v2")),
                ),
                SourceScript(b, listOf(ScriptEvent.Add(writer, "k1" to "d1"))),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState(listOf("k1" to "v1", "d1")))
    }

    /** concord/corpus/24-data-cells/24-OP-SEMIJOIN-01.yaml */
    @Test
    fun `24-OP-SEMIJOIN-01_yaml - semi-join keeps left rows whose key is present on the right, re-entering after a removal`() {
        val a = SourceId("a")
        val b = SourceId("b")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(
                NodeId("sj"),
                SemiJoinModel(firstOfPair, firstOfPair, negated = false),
                NodeId("a"),
                NodeId("b"),
            ),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
            ModelNode.Source(NodeId("b"), b, SetSourceModel),
        )
        val script = Script(
            listOf(
                SourceScript(
                    a,
                    listOf(ScriptEvent.Add(writer, "k1" to "x"), ScriptEvent.Add(writer, "k2" to "y")),
                ),
                SourceScript(
                    b,
                    listOf(
                        ScriptEvent.Add(writer, "k1" to "z"),
                        ScriptEvent.Remove(writer, "k1" to "z"),
                        ScriptEvent.Add(writer, "k1" to "z2"),
                    ),
                ),
            ),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.SetState("k1" to "x"))
    }

    // =========================================================================================
    // Grouped aggregation, and partition (a group-by twin — see the cited kernel KDoc)
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-GROUPBY-01.yaml (default aggregator is `count`, per KernelCatalog.kt). */
    @Test
    fun `24-OP-GROUPBY-01_yaml - group-by folds a tagged set stream into per-key counts, default aggregator`() {
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("g"), GroupByModel(firstOfPair, Aggregates.count()), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, "a" to 1),
            ScriptEvent.Add(writer, "a" to 2),
            ScriptEvent.Add(writer, "b" to 3),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>("a" to 2L, "b" to 1L)))
    }

    /** concord/corpus/24-data-cells/24-OP-GROUPBY-02.yaml */
    @Test
    fun `24-OP-GROUPBY-02_yaml - group-by with a max aggregator removes a group on its last retraction`() {
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("g"), GroupByModel(firstOfPair, Aggregates.maxOf(secondAsLong)), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, "a" to 5),
            ScriptEvent.Add(writer, "a" to 2),
            ScriptEvent.Add(writer, "b" to 3),
            ScriptEvent.Add(writer, "b" to 9),
            ScriptEvent.Remove(writer, "a" to 5),
            ScriptEvent.Remove(writer, "a" to 2),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>("b" to 9L)))
    }

    /**
     * concord/corpus/24-data-cells/24-OP-PARTITION-01.yaml
     *
     * `PartitionedCell`'s own KDoc (kernel/src/main/kotlin/civictech/cell/partition/
     * PartitionedCell.kt): "from outside the composite is indistinguishable from a single
     * `GroupByCell` (same `GroupByApi` shape)" — a `PartitionedCell` is a composite over
     * key-disjoint `GroupByCell` shards whose merged view is exactly the unpartitioned
     * group-by's. The oracle therefore has no separate `partition` ReferenceOp to register:
     * the honest transcription is the SAME `GroupByModel`, applied twice over the same source,
     * which is exactly what the yaml's own check (`views-converge` between the group-by twin
     * and the partitioned twin) asserts.
     */
    @Test
    fun `24-OP-PARTITION-01_yaml - a partitioned cell's converged view equals its unpartitioned group-by twin`() {
        val a = SourceId("a")
        val model = ReferenceModel(
            nodes = listOf(
                ModelNode.Source(NodeId("a"), a, SetSourceModel),
                ModelNode.Operator(NodeId("g"), GroupByModel(firstOfPair, Aggregates.count()), NodeId("a")),
                ModelNode.Operator(NodeId("p"), GroupByModel(firstOfPair, Aggregates.count()), NodeId("a")),
            ),
            terminals = mapOf("vg" to NodeId("g"), "vp" to NodeId("p")),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, "x" to 1),
            ScriptEvent.Add(writer, "x" to 2),
            ScriptEvent.Add(writer, "y" to 3),
            ScriptEvent.Add(writer, "y" to 4),
            ScriptEvent.Add(writer, "z" to 5),
        )

        val result = model.eval(script)
        val expected = ModelState.MapState(mapOf<Any?, Any?>("x" to 2L, "y" to 2L, "z" to 1L))
        withClue("the partitioned twin's converged view must equal the plain group-by's") {
            result shouldBe mapOf("vg" to expected, "vp" to expected)
        }
    }

    // =========================================================================================
    // Windows — key derivation, transcribed as flatMapSet (window-key expansion) then groupBy
    // (sum), per the ORA1 §HONEST-02 ledger's own note in MapCellModel.kt: "windowing-as-
    // key-derivation is already fully expressible with the registered flatMapSet/groupBy*
    // entries". Both expansions are transcribed verbatim from
    // `kernel/src/main/kotlin/civictech/cell/data/Windows.kt` (`Tumbling`/`Sliding`), not
    // invented: `tumbling(size).invoke(at) = floorDiv(at, size) * size`, `sliding(size,
    // slide).invoke(at)` = every multiple-of-`slide` window start containing `at`.
    // =========================================================================================

    /** concord/corpus/24-data-cells/24-OP-WINDOW-01.yaml (tumbling, size 10). */
    @Test
    fun `24-OP-WINDOW-01_yaml - tumbling window folds event time into a composite group key, windows never close`() {
        val tumbling = ElementExpansion { element ->
            val (time, value) = element as Pair<*, *>
            listOf((Math.floorDiv(time as Long, 10L) * 10L) to value)
        }
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("w"), GroupByModel(firstOfPair, Aggregates.sumOf(secondAsLong)), NodeId("fm")),
            ModelNode.Operator(NodeId("fm"), FlatMapSetModel(tumbling), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, 3L to 5L),
            ScriptEvent.Add(writer, 17L to 7L),
            ScriptEvent.Add(writer, 8L to 2L),
            ScriptEvent.Remove(writer, 3L to 5L),
        )

        model.eval(script) shouldBe mapOf("v" to ModelState.MapState(mapOf<Any?, Any?>(0L to 2L, 10L to 7L)))
    }

    /** concord/corpus/24-data-cells/24-OP-WINDOW-02.yaml (sliding, size 10, slide 5). */
    @Test
    fun `24-OP-WINDOW-02_yaml - sliding window expands each event into every window it falls in`() {
        val sliding = ElementExpansion { element ->
            val (time, value) = element as Pair<*, *>
            val at = time as Long
            val starts = mutableListOf<Long>()
            var start = Math.floorDiv(at, 5L) * 5L
            while (start + 10L > at) {
                starts += start
                start -= 5L
            }
            starts.reversed().map { it to value }
        }
        val a = SourceId("a")
        val model = ReferenceModel.terminal(
            "v",
            ModelNode.Operator(NodeId("w"), GroupByModel(firstOfPair, Aggregates.sumOf(secondAsLong)), NodeId("fm")),
            ModelNode.Operator(NodeId("fm"), FlatMapSetModel(sliding), NodeId("a")),
            ModelNode.Source(NodeId("a"), a, SetSourceModel),
        )
        val script = Script.of(
            a,
            ScriptEvent.Add(writer, 3L to 5L),
            ScriptEvent.Add(writer, 12L to 7L),
            ScriptEvent.Add(writer, 22L to 9L),
            ScriptEvent.Remove(writer, 12L to 7L),
        )

        model.eval(script) shouldBe mapOf(
            "v" to ModelState.MapState(mapOf<Any?, Any?>(-5L to 5L, 0L to 5L, 15L to 9L, 20L to 9L)),
        )
    }
}
