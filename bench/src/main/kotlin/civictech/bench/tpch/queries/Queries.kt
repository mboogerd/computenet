package civictech.bench.tpch.queries

import civictech.bench.tpch.TpchQuery

/** Registry for the hand-assembled query graphs landed so far. */
object Queries {
    fun builderFor(
        query: TpchQuery,
        rowPayload: Class<*> = Any::class.java,
    ): QueryGraphBuilder? = when (query) {
        TpchQuery.Q01 -> if (rowPayload == Any::class.java) Q01Builder() else Q01Builder(rowPayload = rowPayload)
        TpchQuery.Q03 -> if (rowPayload == Any::class.java) Q03Builder() else Q03Builder(rowPayload = rowPayload)
        TpchQuery.Q04 -> if (rowPayload == Any::class.java) Q04Builder() else Q04Builder(rowPayload = rowPayload)
        TpchQuery.Q05 -> if (rowPayload == Any::class.java) Q05Builder() else Q05Builder(rowPayload = rowPayload)
        TpchQuery.Q06 -> if (rowPayload == Any::class.java) Q06Builder() else Q06Builder(rowPayload = rowPayload)
        TpchQuery.Q07 -> if (rowPayload == Any::class.java) Q07Builder() else Q07Builder(rowPayload = rowPayload)
        TpchQuery.Q08 -> if (rowPayload == Any::class.java) Q08Builder() else Q08Builder(rowPayload = rowPayload)
        TpchQuery.Q09 -> if (rowPayload == Any::class.java) Q09Builder() else Q09Builder(rowPayload = rowPayload)
        TpchQuery.Q10 -> if (rowPayload == Any::class.java) Q10Builder() else Q10Builder(rowPayload = rowPayload)
        TpchQuery.Q12 -> if (rowPayload == Any::class.java) Q12Builder() else Q12Builder(rowPayload = rowPayload)
        TpchQuery.Q14 -> if (rowPayload == Any::class.java) Q14Builder() else Q14Builder(rowPayload = rowPayload)
        TpchQuery.Q16 -> if (rowPayload == Any::class.java) Q16Builder() else Q16Builder(rowPayload = rowPayload)
        TpchQuery.Q19 -> if (rowPayload == Any::class.java) Q19Builder() else Q19Builder(rowPayload = rowPayload)
        else -> null
    }
}
