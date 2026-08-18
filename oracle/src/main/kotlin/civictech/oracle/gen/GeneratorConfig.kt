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
    }

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
    }
}
