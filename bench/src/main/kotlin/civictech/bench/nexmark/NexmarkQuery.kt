package civictech.bench.nexmark

enum class Tier {
    EXPRESSIBLE,
    NOT_EXPRESSIBLE,
}

enum class Relation {
    PERSONS,
    AUCTIONS,
    BIDS,
}

/** The eight Nexmark queries in catalogue order. */
enum class NexmarkQuery(
    val tier: Tier,
    val windowed: Boolean,
    val deviations: List<String>,
    val blockedCause: String?,
    val drivingRelation: Relation,
) {
    Q1(
        Tier.EXPRESSIBLE,
        windowed = false,
        deviations = querySetSemantics("Q1 is a set of transformed bids"),
        blockedCause = null,
        drivingRelation = Relation.BIDS,
    ),
    Q2(
        Tier.EXPRESSIBLE,
        windowed = false,
        deviations = querySetSemantics("Q2 uses the fixed auction skip of 123"),
        blockedCause = null,
        drivingRelation = Relation.BIDS,
    ),
    Q3(
        Tier.EXPRESSIBLE,
        windowed = false,
        deviations = querySetSemantics("Q3 uses a relational equi-join over filtered auctions and persons"),
        blockedCause = null,
        drivingRelation = Relation.AUCTIONS,
    ),
    Q4(
        Tier.NOT_EXPRESSIBLE,
        windowed = false,
        deviations = queryBlockedDeviations("Q4 has no graph result because the catalogue records the aggregate wall"),
        blockedCause = queryAggregateWall("nested max-per-auction followed by average-per-category"),
        drivingRelation = Relation.BIDS,
    ),
    Q5(
        Tier.NOT_EXPRESSIBLE,
        windowed = true,
        deviations = queryBlockedDeviations("Q5's sliding-window count is retained as a catalogue row only"),
        blockedCause = queryAggregateWall("windowed counts followed by the maximum-count selection"),
        drivingRelation = Relation.BIDS,
    ),
    Q6(
        Tier.NOT_EXPRESSIBLE,
        windowed = false,
        deviations = queryBlockedDeviations("Q6 is not lowered because its last-per-seller result is arrival-order"),
        blockedCause = queryAggregateWall(
            "winning bid per auction followed by the last-ten-per-seller aggregate; " +
                "Arrival-order aggregates (first/last/scan) are excluded",
        ),
        drivingRelation = Relation.BIDS,
    ),
    Q7(
        Tier.EXPRESSIBLE,
        windowed = true,
        deviations = querySetSemantics(
            "Q7 emits one bid per window under price DESC and synthetic bid-id ASC; Beam emits every tied maximum",
        ),
        blockedCause = null,
        drivingRelation = Relation.BIDS,
    ),
    Q8(
        Tier.EXPRESSIBLE,
        windowed = true,
        deviations = querySetSemantics("Q8 observes current tumbling-window contents at quiescence"),
        blockedCause = null,
        drivingRelation = Relation.AUCTIONS,
    ),
    ;

    init {
        require((tier == Tier.NOT_EXPRESSIBLE) == (blockedCause != null)) {
            "non-expressible queries must have a cause and expressible queries must not"
        }
        require(deviations.isNotEmpty()) { "every Nexmark query must record a deviation" }
    }

}

private fun querySetSemantics(specific: String): List<String> = listOf(
    "set semantics over value-equal rows; Beam emits bags",
    specific,
)

private fun queryBlockedDeviations(specific: String): List<String> = listOf(
    "catalogue row only: no reference result or graph is built for a blocked query",
    specific,
)

private fun queryAggregateWall(detail: String): String =
    "blocked — aggregate output as a relation: GroupByCell emits MapDelta and no kernel " +
        "relation operator consumes it; $detail"
