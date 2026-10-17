package civictech.bench.nexmark.queries

import civictech.bench.nexmark.Bid
import civictech.bench.nexmark.Event
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery

/** Registry for hand-assembled Nexmark graphs landed so far. */
object Queries {
    fun builderFor(
        query: NexmarkQuery,
        policy: LatenessPolicy?,
        eventPayload: Class<*> = Any::class.java,
    ): NexmarkQueryBuilder? = when (query) {
        NexmarkQuery.Q1 -> Q1Builder(
            policy = policy,
            eventPayload = if (eventPayload == Any::class.java) Bid::class.java else eventPayload,
        )

        NexmarkQuery.Q2 -> Q2Builder(
            policy = policy,
            eventPayload = if (eventPayload == Any::class.java) Bid::class.java else eventPayload,
        )

        NexmarkQuery.Q7 -> Q7Builder(
            policy = policy,
            eventPayload = if (eventPayload == Any::class.java) Bid::class.java else eventPayload,
        )

        NexmarkQuery.Q8 -> Q8Builder(
            policy = policy,
            eventPayload = if (eventPayload == Any::class.java) Event::class.java else eventPayload,
        )

        NexmarkQuery.Q3,
        NexmarkQuery.Q4,
        NexmarkQuery.Q5,
        NexmarkQuery.Q6,
        -> null
    }
}
