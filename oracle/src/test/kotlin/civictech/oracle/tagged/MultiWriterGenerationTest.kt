package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.ConcurrencyAudit
import civictech.oracle.gen.DotOrders
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
import civictech.oracle.model.ScriptEvent
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * The multi-writer / multi-replica generator dimensions (computenet-4ru.1.3):
 * `[ORA2-GEN-01]`..`[ORA2-GEN-05]`, `[ORA2-GEN-07]`, `[ORA2-DIFF-12]`, BS-2, BS-14, and the
 * harness half of `[ORA2-MODEL-12]`.
 *
 * Three of these are deliberately *sweep-level* assertions rather than per-case ones, because the
 * requirements are: the achieved concurrency ratio has to be **reported** and non-trivial (D4),
 * a counter tie has to occur somewhere in the default sweep range or the CONFIGURATION fails
 * (BS-2), and the gossip a replicated sweep states has to be foldable at all (no
 * `CyclicDeliveryException`). A knob that is merely accepted by `GeneratorConfig` satisfies none
 * of them.
 */
class MultiWriterGenerationTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        TaggedOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    private fun sweep(config: GeneratorConfig = GeneratorConfig.replicatedSweep()): List<GeneratedCase> {
        val generator = CaseGenerator(config)
        return GeneratorConfig.REPLICATED_SWEEP_SEEDS.map { generator.generate(it) }
    }

    // -- [ORA2-GEN-01] / [ORA2-DIFF-12] / BS-14 ------------------------------------------------

    @Test
    fun `a replicated sweep naming MapCell is rejected at generation time, naming the cell and the reason`() {
        val thrown = assertThrows<IllegalArgumentException> {
            CaseGenerator(
                GeneratorConfig.replicatedSweep().copy(vocabulary = listOf("orMap", "map", "join")),
            )
        }
        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "map"
            thrown.message.orEmpty() shouldContain "MapCell/MapDelta"
            thrown.message.orEmpty() shouldContain "no order-independent state to converge on"
            thrown.message.orEmpty() shouldContain "BS-14"
        }
    }

    @Test
    fun `an ORA1 single-instance sweep may still name MapCell under two writers`() {
        // The rejection is on replication, not on writerCount: [ORA1-MODEL-09] already pins an
        // order-dependent source to one writer by construction, and that path is untouched.
        val config = GeneratorConfig(
            depthRange = 1..1,
            sourceCount = 2,
            vocabulary = listOf("map", "join"),
            elementDomainSize = 6,
            scriptLength = 20,
            addRemoveRatio = 0.7,
            unobservedRemoveRatio = 0.2,
            terminalCount = 1,
            writerCount = 2,
        )
        CaseGenerator(config).generate(1L).replication shouldBe null
    }

    // -- [ORA2-GEN-03]: replica placement ------------------------------------------------------

    @Test
    fun `every replicated case places its replicas on distinct hosts and states its writer mapping`() {
        val config = GeneratorConfig.replicatedSweep()
        sweep(config).forEach { case ->
            val audit = case.replication ?: error("seed ${case.seed} generated no replication audit")
            val plan = audit.plan
            withClue("seed ${case.seed}: $plan") {
                plan.replicas shouldHaveAtLeastSize 2
                plan.replicas.size shouldBe config.replicaCount
                plan.hosts.distinct().size shouldBe config.replicaCount
                plan.hosts.forEach { it shouldBeGreaterThan -1 }
                // [ORA2-MODEL-12]'s harness half: one writer per replica, a bijection, stated here.
                plan.writers.distinct().size shouldBe plan.replicas.size
                case.topology.replicaPlacement[plan.handle] shouldContainExactly plan.hosts
            }
            // Every replica actually accepted writes: a placement nothing drives is not a mesh.
            val drivenBy = case.script.steps.filterIsInstance<CaseStep.Op>().map { it.source }.toSet()
            withClue("seed ${case.seed}: replicas driven = $drivenBy") {
                plan.replicas.count { it in drivenBy } shouldBeGreaterThan 1
            }
        }
    }

    // -- [ORA2-GEN-02] + D4: the ACHIEVED ratio, reported ---------------------------------------

    @Test
    fun `the achieved concurrency ratio is measured and reported, not merely configured`() {
        val config = GeneratorConfig.replicatedSweep()
        val cases = sweep(config)
        val aggregate = cases
            .map { it.replication!!.concurrency }
            .reduce(ConcurrencyAudit::plus)

        // Reported, not merely asserted: the achieved ratio is on the test's own output, so a
        // sweep whose concurrency quietly collapsed is visible in a passing log too.
        println("[ORA2-GEN-02] default replicated sweep over ${GeneratorConfig.REPLICATED_SWEEP_SEEDS}: $aggregate")
        println("[ORA2-GEN-04] counter ties: " + cases.sumOf { it.replication!!.counterTieKeys.size } + " keys over ${cases.size} cases")
        withClue("default replicated sweep: $aggregate") {
            aggregate.configured shouldBe config.concurrencyRatio
            aggregate.comparableWrites shouldBeGreaterThan ConcurrencyAudit.MIN_COMPARABLE
            aggregate.concurrentWrites shouldBeGreaterThan 0
            // D4: a configured-high / achieved-~0 sweep is red. This is the assertion that makes
            // the knob's *effect* the thing under test rather than its acceptance.
            aggregate.shortfall shouldBe false
            aggregate.achieved shouldBeGreaterThan 0.1 * config.concurrencyRatio
        }
    }

    @Test
    fun `a sweep that gossips before every write reports its shortfall rather than passing quietly`() {
        // concurrencyRatio 0.0 means a gossip round is considered before every single write, so
        // hardly any write can be causally unordered. The point is not that the number is low —
        // it is that the number EXISTS and says so.
        val quiet = sweep(GeneratorConfig.replicatedSweep().copy(concurrencyRatio = 1.0))
        val loud = sweep(GeneratorConfig.replicatedSweep().copy(concurrencyRatio = 0.0))

        val quietRatio = quiet.map { it.replication!!.concurrency }.reduce(ConcurrencyAudit::plus).achieved
        val serialised = loud.map { it.replication!!.concurrency }.reduce(ConcurrencyAudit::plus)

        withClue("gossip-before-every-write sweep: $serialised, all-concurrent sweep achieved=$quietRatio") {
            quietRatio shouldBeGreaterThan serialised.achieved
        }
    }

    // -- [ORA2-GEN-04] / BS-2: the counter tie, or the configuration fails ----------------------

    @Test
    fun `the default sweep range exercises at least one counter tie broken only by instance rank`() {
        val cases = sweep()
        val tying = cases.filter { it.replication!!.counterTieKeys.isNotEmpty() }
        withClue(
            "BS-2: no case in seeds ${GeneratorConfig.REPLICATED_SWEEP_SEEDS} produced two live dots " +
                "sharing a counter at one key, so [24-TMAP-03]'s sourceId tie-break is unexercisable " +
                "by this sweep CONFIGURATION and the sweep proves nothing about it. " +
                "Per-case ties: " + cases.map { it.seed to it.replication!!.counterTieKeys.size },
        ) {
            tying.size shouldBeGreaterThan 0
        }

        // And the tie is real in the reference the runner will compare against, not only in the
        // audit's own bookkeeping: re-fold one tying case and find the shared counter.
        val case = tying.first()
        val order = DotOrder.ranked(case.replication!!.plan.replicas)
        val state = DotModel(order).converged(case.script.toScript())
        val tieKey = case.replication!!.counterTieKeys.first()
        val live = state.liveDots(tieKey).keys
        withClue("seed ${case.seed} key=$tieKey live dots=$live") {
            live.groupBy { it.counter }.values.count { it.size > 1 } shouldBeGreaterThan 0
            // Separated only by instance rank: same counter, different source.
            val tied = live.groupBy { it.counter }.values.first { it.size > 1 }
            tied.map { it.source }.distinct().size shouldBe tied.size
        }
    }

    // -- [ORA2-GEN-05]: the re-put / reset-remove bias ------------------------------------------

    @Test
    fun `writes are biased onto already-populated keys, so re-puts and reset-removes actually occur`() {
        val cases = sweep()
        var reputs = 0
        var resetRemoves = 0
        cases.forEach { case ->
            val replicas = case.replication!!.plan.replicas.toSet()
            val seen = HashMap<Any?, Int>()
            case.script.steps.filterIsInstance<CaseStep.Op>()
                .filter { it.source in replicas }
                .forEach { step ->
                    when (val event = step.event) {
                        is ScriptEvent.Put -> {
                            if ((seen[event.key] ?: 0) > 0) reputs += 1
                            seen[event.key] = (seen[event.key] ?: 0) + 1
                        }
                        is ScriptEvent.RemoveKey -> {
                            if ((seen[event.key] ?: 0) > 0) resetRemoves += 1
                        }
                        else -> Unit
                    }
                }
        }
        withClue("re-puts=$reputs reset-removes=$resetRemoves over ${cases.size} cases") {
            reputs shouldBeGreaterThan 0
            resetRemoves shouldBeGreaterThan 0
        }
    }

    // -- Deliveries: statable, foldable, acyclic by construction --------------------------------

    @Test
    fun `every generated mesh script states gossip the dot model can fold`() {
        val cases = sweep()
        cases.forEach { case ->
            val script = case.script.toScript()
            val order = DotOrder.ranked(case.replication!!.plan.replicas)
            withClue("seed ${case.seed}: ${case.script.deliveries.size} deliveries") {
                case.script.deliveries.size shouldBeGreaterThan 0
                // Folds at all: a cyclic delivery graph throws by name here (DotModel refuses it),
                // which is the loud failure the positional representation makes unconstructable.
                DotModel(order).converged(script)
                // And the derived counts land inside every slice's log, which `Script`'s own init
                // checks — so reaching this line is that check passing too.
                script.slices.flatMap { it.deliveries }.size shouldBe case.script.deliveries.size
            }
        }
    }

    // -- [ORA2-GEN-07]: determinism -------------------------------------------------------------

    @Test
    fun `(seed, config) reproduces an identical replicated case`() {
        val config = GeneratorConfig.replicatedSweep()
        GeneratorConfig.REPLICATED_SWEEP_SEEDS.forEach { seed ->
            val first = CaseGenerator(config).generate(seed)
            val second = CaseGenerator(config).generate(seed)
            withClue("seed $seed") {
                first shouldBe second
                first.script.deliveries shouldContainExactly second.script.deliveries
            }
        }
    }

    // -- [ORA2-MODEL-12]: the derivation expectation, pinned against the kernel ------------------

    @Test
    fun `DotOrders derives the kernel's own dot-source identity`() {
        val ref = CellRef(UUID.randomUUID(), 7L)
        DotOrders.dotSourceOf(ref) shouldBe
            UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}".toByteArray())
    }

    @Test
    fun `DotOrders ranks replicas by the kernel comparator, not by slice order or UUID text`() {
        val plan = sweep().first().replication!!.plan
        val refs = plan.replicas.mapIndexed { i, s -> s to CellRef(UUID.randomUUID(), i.toLong()) }.toMap()
        val order = DotOrders.of(refs)
        val expected = refs.entries.sortedBy { DotOrders.dotSourceOf(it.value) }.map { it.key }
        expected.forEachIndexed { rank, source ->
            withClue("$source expected rank $rank") {
                order shouldBe DotOrder.ranked(expected)
            }
        }
    }
}
