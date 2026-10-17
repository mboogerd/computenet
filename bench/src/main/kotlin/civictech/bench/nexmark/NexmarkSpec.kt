package civictech.bench.nexmark

/** Constants and lineage for the benchmark's Beam-shaped Nexmark slice. */
object NexmarkSpec {
    const val SPEC_REVISION =
        "Apache Beam Nexmark (sdks/java/testing/nexmark) at " +
            "2be52540457e3887080a67641240b243ea6dad45, inspected 2026-10-17"

    const val FRAMING =
        "continuous-query maintenance over a generated event prefix on kernel operator cells; " +
            "not Beam's or Flink's pipeline runtime; set semantics; no published-figure comparison"

    const val WINDOW_MS = 10_000L
    const val AUCTION_SKIP = 123L

    const val FIRST_PERSON_ID = 1_000L
    const val FIRST_AUCTION_ID = 1_000L
    const val FIRST_CATEGORY_ID = 10L

    const val PERSON_PROPORTION = 1
    const val AUCTION_PROPORTION = 3
    const val BID_PROPORTION = 46
    const val PROPORTION_DENOMINATOR = 50

    val US_STATES: Set<String> = setOf("AZ", "CA", "ID", "OR", "WA", "WY")
}
