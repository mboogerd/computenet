package civictech.oracle.gen

import civictech.oracle.bind.OperatorCatalog
import java.io.Serializable

/**
 * The knobs a case generation run is configured by ([ORA1-GEN-04]): pipeline depth range,
 * source count, operator vocabulary, element domain size, op-script length, add/remove ratio,
 * unobserved-remove ratio, and terminal count — the eight the requirement names — plus
 * [writerCount], [lateJoiner] ([ORA1-GEN-09]) and [hostCount] ([ORA1-GEN-10]) for the
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
     * [replicaCount] distinct `ManagedHost`s sharing one `LocationRegistry` (`[ORA2-GEN-03]`).
     *
     * `1` — the default — is ORA1's shape exactly: no replication, no gossip, and a generated
     * case byte-identical to what the same `(seed, config)` produced before this field existed.
     *
     * Greater than 1 is ORA2's **writer dimension**: in the dot algebra a writer *is* a replica
     * instance (`civictech.oracle.model.ModelDot` is `(counter, SourceId)`, minted per instance,
     * mirroring `OrMapCell`'s per-instance `dotSource`), so "two writers" and "two replicas" are
     * one knob and not two. `[ORA2-GEN-01]`'s "only where the vocabulary contains only convergent
     * cells" is enforced by [validateReplication] below.
     */
    val replicaCount: Int = 1,
    /**
     * The **configured** fraction of writes that should be issued genuinely concurrently —
     * neither replica having absorbed the other's prior write to that key at issue time
     * (`[ORA2-GEN-02]`).
     *
     * Configuring it is not achieving it: a script whose gossip happens to precede every write
     * realises none of it. The ACHIEVED ratio is therefore measured per case and carried on
     * [GeneratedCase.replication]; `ConcurrencyAudit.achieved` beside this number is what D4
     * calls red when it is ~0 against a high configured value.
     */
    val concurrencyRatio: Double = 0.0,
    /**
     * Fraction of keyed writes biased onto an **already-populated** key rather than a fresh one
     * (`[ORA2-GEN-05]`): the re-put and reset-remove cases, which are where `OrMapCell`'s atomic
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
                "distinct ManagedHosts ([ORA2-GEN-03]); hostCount is $hostCount"
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
     *   diagnose. This is [ORA1-GEN-08]'s enforcement for the wholly-absent case: a half-bound
     *   id cannot exist in the catalog at all (`OperatorCatalog`'s own paired-registration
     *   guarantee, `[ORA1-API-02]`), so what remains for the generator to police is an id that
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
     * `[ORA2-GEN-01]` / `[ORA2-DIFF-12]` / BS-14: a replicated sweep admits **only convergent**
     * cells, and an order-dependent untagged one is rejected here — at configuration time,
     * before a single case is generated — with a message naming the cell and the reason.
     *
     * The rejection is on [replicaCount], not on [writerCount], and the distinction is the whole
     * content of the rule rather than a technicality. [writerCount] is ORA1's *intra-slice*
     * writer notion, and `[ORA1-MODEL-09]` already handles an order-dependent source under it by
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
                "writer pinning can supply one. [ORA2-GEN-01]/[ORA2-DIFF-12] (BS-14): a " +
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
         * The default replicated sweep configuration (`[ORA2-GEN-01]`..`[ORA2-GEN-05]`): three
         * `orMap` replicas of one logical source on three hosts, joined with a second unreplicated
         * `orMap` source, writing into a deliberately small key domain at high configured
         * concurrency.
         *
         * The key domain is small **on purpose** and it is what makes the sweep able to exercise
         * anything: two replicas can only write concurrently to *one key* if they draw the same
         * key, and a counter tie additionally needs them to draw it at the same own-put counter.
         * A wide domain would leave every knob configured and nothing achieved — which is exactly
         * the failure D4 calls red, and which this file would then be the cause of.
         */
        fun replicatedSweep(): GeneratorConfig = GeneratorConfig(
            depthRange = 1..1,
            sourceCount = 2,
            vocabulary = listOf("orMap", "join"),
            elementDomainSize = 6,
            scriptLength = 60,
            addRemoveRatio = 0.8,
            unobservedRemoveRatio = 0.2,
            terminalCount = 1,
            writerCount = 1,
            hostCount = 3,
            replicaCount = 3,
            concurrencyRatio = 0.9,
            populatedKeyBias = 0.7,
        )

        /**
         * The untagged, order-dependent data cells BS-14 names. `map` is `CoreOperators.Ids.MAP`;
         * `list` is spelled literally because `ListCell` is registered nowhere (`MapCellModel.kt`'s
         * `[ORA1-HONEST-02]` ledger says why) and so has no id constant to reference.
         */
        val ORDER_DEPENDENT_UNTAGGED: Set<String> = setOf("map", "list")
    }
}
