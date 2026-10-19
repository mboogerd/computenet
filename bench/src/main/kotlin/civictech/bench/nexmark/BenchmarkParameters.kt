package civictech.bench.nexmark

import civictech.bench.graphalytics.LoadModes

/** Default-lane proof that every Nexmark JMH parameter value resolves before a fork. */
object NexmarkBenchmarkParameters {
    val queryNames: List<String> = listOf("Q1", "Q2", "Q3", "Q7", "Q8")
    val scales: List<String> = listOf("10000", "100000")
    val loadModes: List<String> = LoadModes.names
    val policies: List<String> = listOf("none", "lateness-5000")

    fun query(value: String): NexmarkQuery {
        val query = runCatching { NexmarkQuery.valueOf(value) }
            .getOrElse { throw IllegalArgumentException("unknown Nexmark query '$value'", it) }
        require(query.tier == Tier.EXPRESSIBLE) {
            "$value is not expressible: ${query.blockedCause}"
        }
        return query
    }

    fun scale(value: String): NexmarkScale = value.toIntOrNull()?.let { events ->
        require(value in scales) { "unsupported Nexmark scale '$value'" }
        NexmarkScale(events = events, eventsPerSecond = EVENTS_PER_SECOND)
    } ?: throw IllegalArgumentException("invalid Nexmark scale '$value'")

    fun policy(value: String): LatenessPolicy? = when (value) {
        "none" -> null
        "lateness-5000" -> LatenessPolicy(lateness = 5_000L)
        else -> throw IllegalArgumentException("unknown Nexmark policy '$value'")
    }

    fun policyFor(query: String, value: String): LatenessPolicy? =
        policyFor(query(query), value)

    fun policyFor(query: NexmarkQuery, value: String): LatenessPolicy? {
        val resolved = policy(value)
        require(resolved == null || query.windowed) {
            "policy '$value' is not valid for non-windowed query ${query.name}"
        }
        return resolved
    }

    private const val EVENTS_PER_SECOND = 1_000
}
