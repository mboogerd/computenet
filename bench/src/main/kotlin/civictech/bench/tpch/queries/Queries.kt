package civictech.bench.tpch.queries

import civictech.bench.tpch.TpchQuery

/** Registry for the hand-assembled query graphs landed so far. */
object Queries {
    fun builderFor(
        query: TpchQuery,
        rowPayload: Class<*> = Any::class.java,
    ): QueryGraphBuilder? = when (query) {
        TpchQuery.Q01 -> Q01Builder(rowPayload = rowPayload)
        else -> null
    }
}
