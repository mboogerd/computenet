package civictech.oracle.gen

import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.model.SourceId
import java.io.Serializable
import kotlin.random.Random

/**
 * The knobs a case generation run is configured by (ORA1 §GEN-04): pipeline depth range,
 * source count, operator vocabulary, element domain size, op-script length, add/remove ratio,
 * unobserved-remove ratio, and terminal count — the eight the requirement names — plus
 * [writerCount], [lateJoiner] (ORA1 §GEN-09) and [hostCount] (ORA1 §GEN-10) for the
 * multi-writer and multi-host extensions the same feature covers.
 *
 * A plain `Serializable` data class rather than a builder: a config is itself part of a
 * recorded case's identity (`GeneratedCase.controllerSeed` is a pure function of the seed
 * alone, but the *case* is a pure function of `(seed, config)`), so it has to survive the same
 * JVM boundary the case does.
 *
 * Numeric sanity is validated eagerly in [init] — a config with a negative source count or an
 * out-of-range ratio is a configuration bug, not a generation-time surprise. Catalog
 * membership of [vocabulary] is validated separately, by [validated] / [validateAgainstCatalog]
 * below: it needs [OperatorCatalog] to be populated, which a config's own construction cannot
 * assume (registration is a call the caller controls, per `CoreOperators`' own KDoc).
 */
data class GeneratorConfig(
    /** How many operators sit between a source and a terminal, inclusive bounds. */
    val depthRange: IntRange,
    /** How many source cells a generated case drives. */
    val sourceCount: Int,
    /** Catalog ids [OperatorCatalog] may draw operators from. */
    val vocabulary: List<String>,
    /** How many distinct elements the generated element domain holds. */
    val elementDomainSize: Int,
    /** How many op-script steps a generated case emits. */
    val scriptLength: Int,
    /**
     * Fraction of generated element events that are **adds** (`Add`, `Put`, `Increment`), the
     * remainder being removes (`Remove`, `RemoveKey`, `Decrement`) — the epic's "add/remove
     * ratio" knob, and the reading [ScriptGenerator] implements. Distinct from
     * [unobservedRemoveRatio], which biases *which element* a remove names, not how many
     * removes there are.
     */
    val addRemoveRatio: Double,
    /** Fraction of removes deliberately left unobserved by the removing writer. */
    val unobservedRemoveRatio: Double,
    /** How many terminals a generated case observes. */
    val terminalCount: Int,
    /** How many distinct writers a generated case's script may name. */
    val writerCount: Int = 2,
    /** Whether the case links a second terminal after a mid-script quiesce barrier. */
    val lateJoiner: Boolean = false,
    /** How many `ManagedHost`s the generated spec places cells across. */
    val hostCount: Int = 1,
    /**
     * How many replicas of ONE logical source cell a generated case places, across
     * [replicaCount] distinct `ManagedHost`s sharing one `LocationRegistry` (`ORA2 §GEN-03`).
     *
     * `1` — the default — is ORA1's shape exactly: no replication, no gossip, and a generated
     * case byte-identical to what the same `(seed, config)` produced before this field existed.
     *
     * Greater than 1 is ORA2's **writer dimension**: in the dot algebra a writer *is* a replica
     * instance (`civictech.oracle.model.ModelDot` is `(counter, SourceId)`, minted per instance,
     * mirroring `OrMapCell`'s per-instance `dotSource`), so "two writers" and "two replicas" are
     * one knob and not two. `ORA2 §GEN-01`'s "only where the vocabulary contains only convergent
     * cells" is enforced by [validateReplication] below.
     */
    val replicaCount: Int = 1,
    /**
     * The **configured** fraction of writes that should be issued genuinely concurrently —
     * neither replica having absorbed the other's prior write to that key at issue time
     * (`ORA2 §GEN-02`).
     *
     * Configuring it is not achieving it: a script whose gossip happens to precede every write
     * realises none of it. The ACHIEVED ratio is therefore measured per case and carried on
     * [GeneratedCase.replication]; `ConcurrencyAudit.achieved` beside this number is what D4
     * calls red when it is ~0 against a high configured value.
     */
    val concurrencyRatio: Double = 0.0,
    /**
     * Fraction of keyed writes biased onto an **already-populated** key rather than a fresh one
     * (`ORA2 §GEN-05`): the re-put and reset-remove cases, which are where `OrMapCell`'s atomic
     * retract-then-add and its reset-remove tombstoning are actually exercised. A script that
     * only ever puts fresh keys never mints a second dot at one key and never tombstones one.
     */
    val populatedKeyBias: Double = 0.5,
) : Serializable {

    init {
        require(!depthRange.isEmpty()) { "depthRange must not be empty: $depthRange" }
        require(depthRange.first >= 0) { "depthRange must not start below 0: $depthRange" }
        require(sourceCount > 0) { "sourceCount must be positive: $sourceCount" }
        require(vocabulary.isNotEmpty()) { "vocabulary must not be empty" }
        require(elementDomainSize > 0) { "elementDomainSize must be positive: $elementDomainSize" }
        require(scriptLength > 0) { "scriptLength must be positive: $scriptLength" }
        require(addRemoveRatio in 0.0..1.0) { "addRemoveRatio must be in 0.0..1.0: $addRemoveRatio" }
        require(unobservedRemoveRatio in 0.0..1.0) {
            "unobservedRemoveRatio must be in 0.0..1.0: $unobservedRemoveRatio"
        }
        require(terminalCount > 0) { "terminalCount must be positive: $terminalCount" }
        require(writerCount > 0) { "writerCount must be positive: $writerCount" }
        require(hostCount > 0) { "hostCount must be positive: $hostCount" }
        require(replicaCount > 0) { "replicaCount must be positive: $replicaCount" }
        require(concurrencyRatio in 0.0..1.0) { "concurrencyRatio must be in 0.0..1.0: $concurrencyRatio" }
        require(populatedKeyBias in 0.0..1.0) { "populatedKeyBias must be in 0.0..1.0: $populatedKeyBias" }
        require(replicaCount == 1 || hostCount >= replicaCount) {
            "replicaCount $replicaCount needs at least that many hosts to place replicas on " +
                "distinct ManagedHosts (ORA2 §GEN-03); hostCount is $hostCount"
        }
    }

    /** Whether this config asks for a replicated, multi-writer case at all. */
    val replicated: Boolean get() = replicaCount > 1

    /**
     * Validates [vocabulary] against [OperatorCatalog]'s currently registered ids and returns
     * `this` unchanged, so the call composes at a construction site: `GeneratorConfig(...).validated()`.
     *
     * @throws IllegalArgumentException naming **every** vocabulary id absent from the catalog,
     *   not just the first — a config with two bogus ids should not require two failed runs to
     *   diagnose. This is ORA1 §GEN-08's enforcement for the wholly-absent case: a half-bound
     *   id cannot exist in the catalog at all (`OperatorCatalog`'s own paired-registration
     *   guarantee, `ORA1 §API-02`), so what remains for the generator to police is an id that
     *   never made it into the catalog in the first place.
     */
    fun validated(): GeneratorConfig {
        validateAgainstCatalog()
        return this
    }

    /**
     * Throws [IllegalArgumentException] naming every id in [vocabulary] that is not currently
     * registered in [OperatorCatalog]. Does nothing if every id resolves.
     */
    fun validateAgainstCatalog() {
        val unknown = vocabulary.filterNot { it in OperatorCatalog }
        require(unknown.isEmpty()) {
            "GeneratorConfig.vocabulary names ids absent from OperatorCatalog: ${unknown.sorted()}"
        }
        validateReplication()
    }

    /**
     * `ORA2 §GEN-01` / `ORA2 §DIFF-12` / BS-14: a replicated sweep admits **only convergent**
     * cells, and an order-dependent untagged one is rejected here — at configuration time,
     * before a single case is generated — with a message naming the cell and the reason.
     *
     * The rejection is on [replicaCount], not on [writerCount], and the distinction is the whole
     * content of the rule rather than a technicality. [writerCount] is ORA1's *intra-slice*
     * writer notion, and `ORA1 §MODEL-09` already handles an order-dependent source under it by
     * construction — `ScriptGenerator` pins such a source to one `WriterId` before any event
     * exists, so an ORA1 sweep naming `map` with `writerCount = 2` stays legal and unchanged.
     * What cannot be rescued that way is **replication**: two instances of a `MapCell` accepting
     * writes on two hosts have no order-independent answer to converge on at all, and no
     * generation-time pinning can invent one. Hence the gate is exactly where the impossibility
     * is.
     *
     * `list` is named in the message even though `ListCell` is registered in no catalog (so
     * [validateAgainstCatalog] above would already have rejected it as unknown): naming it keeps
     * the reason readable for the cell the requirement names, rather than reporting it as a typo.
     */
    fun validateReplication() {
        if (!replicated) return
        val orderDependent = vocabulary.filter { it in ORDER_DEPENDENT_UNTAGGED }
        require(orderDependent.isEmpty()) {
            "GeneratorConfig places replicaCount=$replicaCount replicas but its vocabulary names " +
                "order-dependent, untagged cell(s) ${orderDependent.sorted()}: MapCell/MapDelta " +
                "(and ListCell/ListDelta) resolve concurrent writes by arrival order at one " +
                "instance, not by a convergent merge, so two replicas accepting concurrent " +
                "writes have no order-independent state to converge on and no generation-time " +
                "writer pinning can supply one. ORA2 §GEN-01/ORA2 §DIFF-12 (BS-14): a " +
                "replicated sweep admits only convergent cells."
        }
    }

    companion object {
        /**
         * BS-2's "default sweep range": the seeds a replicated sweep is expected to cover, and
         * the range over which `MultiWriterGenerationTest` requires at least one counter tie —
         * failing the CONFIGURATION, loudly, when the tie-break `[24-TMAP-03]` defines turns out
         * to be unexercisable by it.
         */
        val REPLICATED_SWEEP_SEEDS: LongRange = 1L..40L

        /**
         * The default replicated sweep configuration (`ORA2 §GEN-01`..`ORA2 §GEN-05`): three
         * `orMap` replicas of one logical source on three hosts, joined with a second unreplicated
         * `orMap` source, writing into a deliberately small key domain at high configured
         * concurrency.
         *
         * The key domain is small **on purpose** and it is what makes the sweep able to exercise
         * anything: two replicas can only write concurrently to *one key* if they draw the same
         * key, and a counter tie additionally needs them to draw it at the same own-put counter.
         * A wide domain would leave every knob configured and nothing achieved — which is exactly
         * the failure D4 calls red, and which this file would then be the cause of.
         *
         * `vocabulary = listOf("orMap")` and `depthRange = 0..0`, not `listOf("orMap", "join")` /
         * `1..1` as originally written (computenet-880k). Both sources are `orMap`; there is no
         * operator layer at all — see [replicatedOrMapMeshCase]'s KDoc for why `join` was there
         * and why it had to go, and why this is a config change rather than a `GraphGenerator`
         * one. Consumed by [replicatedOrMapMeshCase], not by [CaseGenerator] directly: a
         * `depthRange` of `0..0` with an operator-free vocabulary is not something
         * `GraphGenerator` was ever asked to generate (`ORA1 §GEN-03` still requires an operator
         * for every OTHER config), so a plain `CaseGenerator(replicatedSweep()).generate(seed)`
         * throws — deliberately, since the invariant it enforces is real for every case that
         * actually executes a graph. `terminalCount` is `sourceCount`, reflecting that every
         * source is now literally its own terminal.
         */
        fun replicatedSweep(): GeneratorConfig = GeneratorConfig(
            depthRange = 0..0,
            sourceCount = 2,
            vocabulary = listOf(TaggedOperators.Ids.OR_MAP),
            elementDomainSize = 6,
            scriptLength = 60,
            addRemoveRatio = 0.8,
            unobservedRemoveRatio = 0.2,
            terminalCount = 2,
            writerCount = 1,
            hostCount = 3,
            replicaCount = 3,
            concurrencyRatio = 0.9,
            populatedKeyBias = 0.7,
        )

        /**
         * Builds a [GeneratedCase] for a replicated, `orMap`-only mesh — [GeneratorConfig.sourceCount]
         * `orMap` sources, the first (`source-0`) replicated across [GeneratorConfig.replicaCount]
         * distinct hosts — **without** routing through [GraphGenerator] (computenet-880k).
         *
         * ## Why this exists, and why it is not a `GraphGenerator` relaxation
         *
         * [GraphGenerator] requires at least one operator between every source and terminal
         * (`ORA1 §GEN-03`), deliberately and unconditionally: `Builder.chooseTerminals` refuses
         * to leave a source stranded on the frontier ("no script ever observes... an island"),
         * `Builder.choosePlacement` forces a genuinely cross-host edge for `hostCount > 1`
         * (`ORA1 §GEN-10`), and `Builder.chooseLateTerminal` assumes an operator node exists.
         * All three are real guarantees for every OTHER case this generator produces, and a
         * replicated `orMap` mesh cannot supply what they need: `TaggedOperators` registers
         * exactly one id (`Ids.OR_MAP`, no consumer of [civictech.oracle.model.ElementShape.TaggedMapOf]
         * exists anywhere in the catalog — verified against `TaggedOperators`' own KDoc, which
         * explains at length why `KeyedSetCell`/`MergeableGroupByCell`/the PN-counter duplicate
         * are not registrable through this seam either), so there is no operator this config
         * could legitimately name. Weakening `ORA1 §GEN-03` globally to admit a source-only case
         * would touch all three guarantees above for every consumer of [GraphGenerator], to serve
         * a need only this one dimension has — a bigger and riskier change than the tests that
         * need it actually require.
         *
         * `GeneratorConfig.replicatedSweep()` used to paper over exactly this by naming `join` in
         * its vocabulary as scaffolding: before computenet-880k, `orMap`'s `ShapeRule` was
         * (wrongly) `ElementShape.MapOf(Scalar, Scalar)` — byte-identical to `join`'s expected
         * input — so `GraphGenerator` treated the pairing as legal and built the edge, which is
         * the SAME shape-collision bug that bead exists to close (`JoinCell` is typed to
         * `MapDelta`, not `TaggedMapDelta`; wiring the edge for real is a kernel type violation).
         * `ConvergenceSweepTest`'s own KDoc already documented the tell: "the unreplicated `join`
         * arm's events are generated but irrelevant... never wired to a `JoinCell`." None of
         * `ConvergenceSweepTest`, `MultiWriterGenerationTest` or `TaggedControlsTest` ever reads
         * [GeneratedCase.spec] — only [GeneratedCase.script], [GeneratedCase.replication] and
         * [GeneratedCase.topology.replicaPlacement] — so `join` was always incidental scaffolding
         * to satisfy a check these tests' own properties do not need, not a graph edge any of
         * them exercised. Once the shape bug that scaffolding depended on is fixed,
         * `GraphGenerator` correctly refuses it, honestly, and this function is what replaces the
         * scaffolding: it builds exactly the [CaseTopology]/[ReplicaPlan]/script shape
         * [ScriptGenerator] needs — which has never depended on an operator node existing at all,
         * only on which [TopologyNode]s carry a non-null `source` — and lowers the (edge-free,
         * every node an `orMap` source) topology through [GraphGenerator.lower] unchanged, so
         * [GeneratedCase.spec] is still a real, applicable, catalog-sourced `GraphSpec` — just one
         * with no connect steps, which is the honest lowering of a graph with no legal edges to
         * draw.
         *
         * ## Determinism and placement
         *
         * One `Random(seed)` is threaded through placement, replica-host selection and
         * [ScriptGenerator] in that order, mirroring [CaseGenerator.generate]'s own
         * placement-then-script order so equal `(seed, config)` pairs are still reproducible
         * (`ORA1 §GEN-01`) — this is simply a DIFFERENT case than a hypothetical join-consuming
         * one would have produced, not a weaker determinism guarantee. Every node independently
         * draws a host ordinal from `0 until hostCount`: [GraphGenerator]'s forced-cross-host-edge
         * placement rule (`ORA1 §GEN-10`) has no analogue here because there are no edges to
         * force one onto, so host diversity for the replicated node comes entirely from
         * [ReplicaPlan.hosts]' own distinctness requirement, which this function still honors.
         *
         * @throws IllegalArgumentException if [GeneratorConfig.vocabulary] is not exactly
         *   `[TaggedOperators.Ids.OR_MAP]`, or [GeneratorConfig.replicated] is false — this
         *   function is for the one shape it exists to serve, not a general-purpose escape hatch.
         */
        fun replicatedOrMapMeshCase(config: GeneratorConfig, seed: Long): GeneratedCase {
            require(config.vocabulary == listOf(TaggedOperators.Ids.OR_MAP)) {
                "replicatedOrMapMeshCase is for vocabulary=[orMap] exactly; got ${config.vocabulary}"
            }
            require(config.replicated) {
                "replicatedOrMapMeshCase needs replicaCount > 1 (nothing to replicate otherwise); " +
                    "got replicaCount=${config.replicaCount}"
            }

            val rng = Random(seed)
            val nodes = (0 until config.sourceCount).map { i ->
                TopologyNode(
                    handle = "source-$i",
                    catalogId = TaggedOperators.Ids.OR_MAP,
                    inputs = emptyList(),
                    source = SourceId("source-$i"),
                )
            }
            val replicatedHandle = nodes.first().handle
            val replicatedSourceId = nodes.first().source!!

            // Placement first, then the replica draw overwrites the replicated handle's ordinal —
            // the same relative order `GraphGenerator.Builder.build` uses (choosePlacement, then
            // chooseReplicaPlan(placement)).
            val placement = nodes.associate { it.handle to rng.nextInt(config.hostCount) }.toMutableMap()

            val ordinals = (0 until config.hostCount).toMutableList()
            val hosts = ArrayList<Int>(config.replicaCount)
            repeat(config.replicaCount) { hosts += ordinals.removeAt(rng.nextInt(ordinals.size)) }
            placement[replicatedHandle] = hosts.first()

            val plan = ReplicaPlan(
                handle = replicatedHandle,
                replicas = (0 until config.replicaCount).map { SourceId("${replicatedSourceId.id}#r$it") },
                writers = (0 until config.replicaCount).map { "w$it" },
                hosts = hosts,
            )

            val topology = CaseTopology(
                nodes = nodes,
                terminals = nodes.map { TerminalSpec("terminal-${it.handle}", it.handle) },
                placement = placement,
                replicaPlacement = mapOf(plan.handle to plan.hosts),
            )

            val generated = ScriptGenerator(config, topology, rng, plan).generate()
            return GeneratedCase(
                seed = seed,
                topology = topology,
                spec = GraphGenerator.lower(topology),
                script = generated.script,
                removeAudit = generated.removeAudit,
                replication = generated.replication,
            )
        }

        /**
         * The untagged, order-dependent data cells BS-14 names. `map` is `CoreOperators.Ids.MAP`;
         * `list` is spelled literally because `ListCell` is registered nowhere (`MapCellModel.kt`'s
         * `ORA1 §HONEST-02` ledger says why) and so has no id constant to reference.
         */
        val ORDER_DEPENDENT_UNTAGGED: Set<String> = setOf("map", "list")
    }
}
