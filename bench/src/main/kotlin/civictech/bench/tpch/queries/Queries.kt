package civictech.bench.tpch.queries

import civictech.bench.tpch.TpchQuery

/** Registry for the hand-assembled query graphs landed so far. */
object Queries {
    fun builderFor(
        query: TpchQuery,
        rowPayload: Class<*> = Any::class.java,
    ): QueryGraphBuilder? = when (query) {
        TpchQuery.Q01 -> Q01Builder(rowPayload = rowPayload)
        TpchQuery.Q03 -> Q03Builder(rowPayload = rowPayload)
        TpchQuery.Q05 -> Q05Builder(rowPayload = rowPayload)
        TpchQuery.Q06 -> Q06Builder(rowPayload = rowPayload)
        TpchQuery.Q07 -> Q07Builder(rowPayload = rowPayload)
        TpchQuery.Q08 -> Q08Builder(rowPayload = rowPayload)
        TpchQuery.Q09 -> Q09Builder(rowPayload = rowPayload)
        TpchQuery.Q16 -> Q16Builder(rowPayload = rowPayload)
        else -> null
    }
}
