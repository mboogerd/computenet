package civictech.bench.tpch

/** The implementation tier reached by a query on the hand-assembled route. */
enum class TpchTier {
    HAND_1,
    BLOCKED_2,
}

/**
 * The standard TPC-H queries covered by the benchmark slice.
 *
 * The nine [BLOCKED_2] entries remain explicit coverage rows. Their causes name
 * the exact aggregate-output-as-relation wall instead of allowing absence from a
 * run to look like a successful or merely unobserved query.
 */
enum class TpchQuery(
    val tier: TpchTier,
    val blockedCause: String? = null,
) {
    Q01(TpchTier.HAND_1),
    Q02(TpchTier.BLOCKED_2, blockedCause("Q2: scalar MIN per part")),
    Q03(TpchTier.HAND_1),
    Q04(TpchTier.HAND_1),
    Q05(TpchTier.HAND_1),
    Q06(TpchTier.HAND_1),
    Q07(TpchTier.HAND_1),
    Q08(TpchTier.HAND_1),
    Q09(TpchTier.HAND_1),
    Q10(TpchTier.HAND_1),
    Q11(TpchTier.BLOCKED_2, blockedCause("Q11: scalar SUM threshold")),
    Q12(TpchTier.HAND_1),
    Q13(TpchTier.BLOCKED_2, blockedCause("Q13: count of per-customer counts, plus the outer join")),
    Q14(TpchTier.HAND_1),
    Q15(TpchTier.BLOCKED_2, blockedCause("Q15: MAX over grouped revenue")),
    Q16(TpchTier.HAND_1),
    Q17(TpchTier.BLOCKED_2, blockedCause("Q17: per-part AVG threshold")),
    Q18(TpchTier.BLOCKED_2, blockedCause("Q18: IN over grouped SUM")),
    Q19(TpchTier.HAND_1),
    Q20(TpchTier.BLOCKED_2, blockedCause("Q20: grouped SUM threshold inside IN")),
    Q21(
        TpchTier.BLOCKED_2,
        blockedCause(
            "Q21: correlated EXISTS/NOT EXISTS with l_suppkey <>, " +
                "which needs a per-order distinct-supplier count",
        ),
    ),
    Q22(TpchTier.BLOCKED_2, blockedCause("Q22: scalar AVG threshold")),
    ;

    /** The route is deliberately fixed until the QRY1 follow-on is available. */
    val route: String = "hand"

    init {
        require((tier == TpchTier.BLOCKED_2) == (blockedCause != null)) {
            "blocked queries must have a cause and hand queries must not"
        }
    }
}

/** Short alias used by callers that describe the field as simply a tier. */
typealias Tier = TpchTier

private fun blockedCause(nesting: String): String =
    "blocked — [BB-TPCH-14]: aggregate output as a relation " +
        "(second-level aggregate / scalar-subquery threshold) — " +
        "GroupByCell emits MapDelta and no kernel relation operator consumes it " +
        "(kernel data/op: only JoinCell, CombineLatestCell, LookupJoinCell.fact " +
        "take a MapDelta inlet); on the QRY1 route the same wall is " +
        "Lowering.kt's non-root GroupAggregate refusal — $nesting"
