package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.SourceModel
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Epic computenet-4ru §4.1's `[ORA1-MODEL-02]` vocabulary, pinned mechanically against
 * [OperatorCatalog]'s registrations — the feature's own "checkable by reading the catalog
 * registrations" clause made into a test rather than left as a promise in prose.
 *
 * [ora1Model02Vocabulary] below is spelled out by hand against the requirement text, entry by
 * entry, rather than read back from [CoreOperators.Ids.ALL]. Reading it back would make this
 * test verify `CoreOperators` against itself: an `ALL` that quietly dropped an id, or that
 * never had it, would still pass. Referencing [CoreOperators.Ids]' named constants (rather
 * than repeating string literals) still avoids a typo drifting the two lists apart, per those
 * constants' own KDoc.
 */
class VocabularyCompletenessTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    /**
     * `[ORA1-MODEL-02]`'s full list: `SetCell`, `KeyedSetCell`, `MapCell`, `CounterCell`,
     * `PnCounterCell`, `FilterCell`, `FlatMapSetCell`/`mapSet`, `UnionSetCell`,
     * `IntersectSetCell`, `CountCell`, `PresenceCountCell`, `QuorumSetCell`, `GroupByCell`
     * over all seven `Aggregators` families, `JoinSetCell`, `SemiJoinCell` in both polarities,
     * `JoinCell`, `CombineLatestCell`, `LookupJoinCell` — 26 entries in total.
     */
    private val ora1Model02Vocabulary: List<String> = listOf(
        CoreOperators.Ids.SET,
        CoreOperators.Ids.KEYED_SET,
        CoreOperators.Ids.MAP,
        CoreOperators.Ids.COUNTER,
        CoreOperators.Ids.PN_COUNTER,
        CoreOperators.Ids.FILTER,
        CoreOperators.Ids.FLAT_MAP_SET,
        CoreOperators.Ids.MAP_SET,
        CoreOperators.Ids.UNION,
        CoreOperators.Ids.INTERSECT,
        CoreOperators.Ids.COUNT,
        CoreOperators.Ids.PRESENCE_COUNT,
        CoreOperators.Ids.QUORUM_SET,
        CoreOperators.Ids.GROUP_BY_COUNT,
        CoreOperators.Ids.GROUP_BY_SUM,
        CoreOperators.Ids.GROUP_BY_AVG,
        CoreOperators.Ids.GROUP_BY_MIN,
        CoreOperators.Ids.GROUP_BY_MAX,
        CoreOperators.Ids.GROUP_BY_TOP_K,
        CoreOperators.Ids.GROUP_BY_COLLECT_TO_SET,
        CoreOperators.Ids.JOIN_SET,
        CoreOperators.Ids.SEMI_JOIN,
        CoreOperators.Ids.ANTI_JOIN,
        CoreOperators.Ids.JOIN,
        CoreOperators.Ids.COMBINE_LATEST,
        CoreOperators.Ids.LOOKUP_JOIN,
    )

    @Test
    fun `the vocabulary list itself has 26 entries, both SemiJoin polarities and all seven GroupBy aggregators`() {
        ora1Model02Vocabulary.size shouldBe 26
        withClue("both SemiJoin polarities are named separately") {
            (CoreOperators.Ids.SEMI_JOIN in ora1Model02Vocabulary) shouldBe true
            (CoreOperators.Ids.ANTI_JOIN in ora1Model02Vocabulary) shouldBe true
        }
        withClue("all seven Aggregators families") {
            CoreOperators.Ids.GROUP_BY_AGGREGATES.size shouldBe 7
            CoreOperators.Ids.GROUP_BY_AGGREGATES.forEach { id -> (id in ora1Model02Vocabulary) shouldBe true }
        }
    }

    /**
     * The mechanical check: every named id is registered as a **full** [OperatorCatalog.Entry]
     * — a non-null kernel [CellFactory] and a non-null, evaluable model
     * ([SourceModel] or [OperatorModel]). [OperatorCatalog.register]'s own paired-registration
     * guard ([ORA1-API-02]) means an id present at all is already fully bound; what this adds
     * is that the id is present, and that the bound model is one [ReferenceModel][civictech
     * .oracle.model.ReferenceModel] can actually evaluate.
     */
    @Test
    fun `every ORA1-MODEL-02 vocabulary entry is a full paired OperatorCatalog entry`() {
        val missing = ora1Model02Vocabulary.filterNot { it in OperatorCatalog }
        withClue("vocabulary entries absent from OperatorCatalog entirely: $missing") {
            missing.shouldBeEmpty()
        }

        ora1Model02Vocabulary.forEach { id ->
            val entry = OperatorCatalog.entry(id).shouldNotBeNull()
            withClue("$id: kernel cell factory") { entry.kernel.shouldBeInstanceOf<CellFactory>() }
            withClue("$id: model must be evaluable (a SourceModel or an OperatorModel)") {
                (entry.model is SourceModel || entry.model is OperatorModel) shouldBe true
            }
        }
    }
}
