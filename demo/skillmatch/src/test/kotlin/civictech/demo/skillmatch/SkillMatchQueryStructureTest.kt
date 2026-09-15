package civictech.demo.skillmatch

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.LookupJoinCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.query.run.CompiledQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The STRUCTURAL half of BS-11 / [QRY1-API-04] for skillmatch (computenet-cab.7.5, cab.7-D5/D6):
 * the [GraphSpec] [SkillMatchQuery] compiles to, compared cell-for-cell and link-for-link with the
 * one [SkillPipeline.buildWithSpec] records.
 *
 * **This test pins the residual between the compiled and the hand-wired graph; it does NOT claim
 * [QRY1-API-04] is met as literally written.** The residual is measured, not accepted: whether
 * it satisfies the requirement is the human decision computenet-cab.7.10 (under feature
 * computenet-cab.7), and the evidence is recorded as doc/demo-findings.md F-20.
 *
 * Method. Hand-wired handles map to compiled handles through the compiled SYMBOL TABLE, never by
 * guessing names: the two sources through [CompiledQuery.sourceHandles], each relational output
 * through [CompiledQuery.outputHandles] under the same name. For every mapped pair the cell
 * CLASS each [SpawnStep.factory] builds must be equal (factories themselves are incomparable —
 * lambdas over demo types on one side, data classes over `Row` on the other; that is what the
 * extensional half of BS-11 is for), and the hand-wired [ConnectStep]s, renamed through the
 * map, must equal the compiled ones, port names included. Whatever falls outside is the
 * residual, which must equal [PINNED] exactly — a new difference or a vanished one both fail.
 *
 * Residual (measured at this commit):
 * - R1, hand-wired only: `qualification` (LookupJoinCell) and `market` (CombineLatestCell) and
 *   their four links — expression-valued enrichments ([QRY1-LANG-01] terms are variables or
 *   constants; a non-root aggregate feeds nothing).
 * - R2: hand-wired `gap.right` reads `candSkills` directly; the query needs a `candHas(S)`
 *   projection (`not candSkills(C, S)` is unsafe under [QRY1-LANG-07]), so the compiled graph
 *   has one extra FlatMapSetCell and routes `gap.right` from it.
 */
class SkillMatchQueryStructureTest {

    /** The difference between the two specs after renaming through the symbol table. */
    data class Residual(
        val handWiredOnlySpawns: Map<String, KClass<*>>,
        val handWiredOnlyLinks: Set<ConnectStep>,
        val compiledOnlySpawns: Map<String, KClass<*>>,
        val compiledOnlyLinks: Set<ConnectStep>,
        val classMismatches: Map<String, Pair<KClass<*>, KClass<*>>>,
    )

    @Test
    fun `compiled skillmatch graph equals the hand-wired one up to the pinned residual`() {
        val compiled = SkillMatchQuery.compile()
        val map = handleMap(compiled)
        val problems = discrepancies(residual(handWiredSpec(), compiled.spec, map), map)
        assertTrue(problems.isEmpty(), "structural comparison failed:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the symbol table maps every relational hand-wired cell`() {
        val compiled = SkillMatchQuery.compile()
        val map = handleMap(compiled)
        assertEquals(PINNED_HANDLE_MAP, map, "compiled handles moved; re-measure the residual")
        assertEquals(map.size, map.values.toSet().size, "two hand-wired cells map to one compiled handle: $map")
        val hand = handWiredSpec().spawnClasses()
        val comp = compiled.spec.spawnClasses()
        assertEquals(MAPPED_CLASSES, map.keys.associateWith { hand.getValue(it) })
        assertEquals(MAPPED_CLASSES, map.mapValues { (_, c) -> comp.getValue(c) })
    }

    @Test
    fun `negative control - a removed compiled ConnectStep fails the comparison naming it`() {
        val compiled = SkillMatchQuery.compile()
        val removed = compiled.spec.steps.filterIsInstance<ConnectStep>()
            .first { it.to == compiled.outputHandles.getValue("matchCounts") }
        val damaged = GraphSpec(compiled.spec.steps - removed)

        val map = handleMap(compiled)
        val problems = discrepancies(residual(handWiredSpec(), damaged, map), map)

        assertTrue(problems.isNotEmpty(), "removing $removed went undetected")
        assertTrue(
            problems.any { removed.toString() in it },
            "the failure does not name the removed step $removed:\n" + problems.joinToString("\n"),
        )
    }

    private fun handWiredSpec(): GraphSpec {
        val host = ManagedHost(scheduler = SimulationController(SEED).scheduler())
        return SkillPipeline.buildWithSpec(host).second
    }

    private fun handleMap(compiled: CompiledQuery): Map<String, String> =
        SOURCES.associateWith { compiled.sourceHandles.getValue(it) } +
            RELATIONAL_CELLS.associateWith { compiled.outputHandles.getValue(it) }

    private fun residual(handWired: GraphSpec, compiled: GraphSpec, map: Map<String, String>): Residual {
        val handSpawns = handWired.spawnClasses()
        val compiledSpawns = compiled.spawnClasses()
        require(handSpawns.keys.intersect(compiledSpawns.keys).isEmpty()) {
            "hand-wired and compiled handle namespaces overlap: ${handSpawns.keys.intersect(compiledSpawns.keys)}"
        }
        val missing = map.filter { (hand, comp) -> hand !in handSpawns || comp !in compiledSpawns }
        require(missing.isEmpty()) { "mapped handles absent from a spec: $missing" }

        val rename = { h: String -> map[h] ?: h }
        val handLinks = handWired.steps.filterIsInstance<ConnectStep>()
            .associateBy { it.copy(from = rename(it.from), to = rename(it.to)) }
        val compiledLinks = compiled.steps.filterIsInstance<ConnectStep>().toSet()

        return Residual(
            handWiredOnlySpawns = handSpawns.filterKeys { it !in map.keys },
            handWiredOnlyLinks = handLinks.filterKeys { it !in compiledLinks }.values.toSet(),
            compiledOnlySpawns = compiledSpawns.filterKeys { it !in map.values },
            compiledOnlyLinks = compiledLinks - handLinks.keys,
            classMismatches = map.mapNotNull { (hand, comp) ->
                val pair = handSpawns.getValue(hand) to compiledSpawns.getValue(comp)
                if (pair.first == pair.second) null else hand to pair
            }.toMap(),
        )
    }

    /**
     * Every way [actual] differs from [PINNED], one line each, naming the step. A hand-wired link
     * absent from the compiled graph is reported in its renamed (compiled-namespace) form too, so
     * a removed compiled [ConnectStep] is named verbatim.
     */
    private fun discrepancies(actual: Residual, map: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        actual.classMismatches.forEach { (h, p) ->
            out += "cell class differs for '$h': hand-wired ${p.first.simpleName}, compiled ${p.second.simpleName}"
        }
        fun <T> compare(what: String, got: Set<T>, pinned: Set<T>, show: (T) -> String = { it.toString() }) {
            (got - pinned).forEach { out += "unexpected $what: ${show(it)}" }
            (pinned - got).forEach { out += "pinned $what no longer differs: ${show(it)}" }
        }
        compare("hand-wired-only spawn", actual.handWiredOnlySpawns.entries.map { it.toPair() }.toSet(),
            PINNED.handWiredOnlySpawns.entries.map { it.toPair() }.toSet()) { "${it.first}: ${it.second.simpleName}" }
        compare("compiled-only spawn", actual.compiledOnlySpawns.entries.map { it.toPair() }.toSet(),
            PINNED.compiledOnlySpawns.entries.map { it.toPair() }.toSet()) { "${it.first}: ${it.second.simpleName}" }
        compare("hand-wired-only link", actual.handWiredOnlyLinks, PINNED.handWiredOnlyLinks) { hand ->
            "$hand (renamed: ${hand.copy(from = map[hand.from] ?: hand.from, to = map[hand.to] ?: hand.to)})"
        }
        compare("compiled-only link", actual.compiledOnlyLinks, PINNED.compiledOnlyLinks)
        return out
    }

    private fun GraphSpec.spawnClasses(): Map<String, KClass<*>> =
        steps.filterIsInstance<SpawnStep>().associate { it.handle to it.factory.create(CellRef(UUID.randomUUID()))::class }

    companion object {
        private const val SEED = 0L

        private val SOURCES = listOf("candSkills", "jobSkills")

        /** The hand-wired cells the language reaches, each an output root of the same name. */
        private val RELATIONAL_CELLS = listOf("matches", "matchCounts", "required", "gap", "supply", "demand")

        /**
         * The symbol table's handles at this commit (numbering from [QRY1-LOWER-11]'s shared
         * lowering: a shared node keeps its first sorted root's handle, so `matches` lives under
         * `matchCounts/`). The comparison maps through the live symbol table; this literal only
         * pins it, so a renumbering fails loudly instead of silently re-keying [PINNED].
         */
        private val PINNED_HANDLE_MAP = mapOf(
            "candSkills" to "src:candSkills",
            "jobSkills" to "src:jobSkills",
            "matches" to "matchCounts/1:join",
            "matchCounts" to "matchCounts/0:groupaggregate",
            "required" to "required/0:groupaggregate",
            "gap" to "gap/0:semijoin",
            "supply" to "supply/0:groupaggregate",
            "demand" to "demand/0:groupaggregate",
        )

        private const val CAND_HAS = "candHas/0:project"

        val PINNED = Residual(
            // R1
            handWiredOnlySpawns = mapOf(
                "qualification" to LookupJoinCell::class,
                "market" to CombineLatestCell::class,
            ),
            handWiredOnlyLinks = setOf(
                ConnectStep("matchCounts", "outlet", "qualification", "fact"),
                ConnectStep("required", "outlet", "qualification", "dimension"),
                ConnectStep("supply", "outlet", "market", "left"),
                ConnectStep("demand", "outlet", "market", "right"),
                // R2
                ConnectStep("candSkills", "outlet", "gap", "right"),
            ),
            // R2
            compiledOnlySpawns = mapOf(CAND_HAS to FlatMapSetCell::class),
            compiledOnlyLinks = setOf(
                ConnectStep("src:candSkills", "outlet", CAND_HAS, "inlet"),
                ConnectStep(CAND_HAS, "outlet", "gap/0:semijoin", "right"),
            ),
            classMismatches = emptyMap(),
        )

        /** The cell class of every mapped pair, equal on both sides. */
        private val MAPPED_CLASSES: Map<String, KClass<*>> = mapOf(
            "candSkills" to SetCell::class,
            "jobSkills" to SetCell::class,
            "matches" to JoinSetCell::class,
            "matchCounts" to GroupByCell::class,
            "required" to GroupByCell::class,
            "gap" to SemiJoinCell::class,
            "supply" to GroupByCell::class,
            "demand" to GroupByCell::class,
        )
    }
}
