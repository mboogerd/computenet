package civictech.economy

import civictech.cell.ClaimClass

/**
 * The operator's only view of an applied [EconomicPolicy] at startup: every budget parameter,
 * the fixed k-hosts statement (`[ECO1-PAR-04]`), and the warnings [startupRecord] derives from
 * the policy (F2 T4, `kwhw6-D11`; epic §4.1 `[ECO1-BUD-09]`, §4.4 `[ECO1-PAR-04]`; "The
 * inequality this epic exists to make expressible", "No global accounting — the honest
 * consequence").
 *
 * `StartupRecord` is plain data: [startupRecord] builds it and its [warnings] with no I/O; only
 * [emit] performs the (also side-effect-only, non-throwing) printing.
 *
 * @property label [EconomicPolicy.label], forwarded.
 * @property fitted [EconomicPolicy.fitted], forwarded.
 * @property prices [EconomicPolicy.prices], forwarded.
 * @property capacities [EconomicPolicy.capacities], forwarded.
 * @property refill [EconomicPolicy.refill], forwarded.
 * @property retention [EconomicPolicy.retention], forwarded.
 * @property checkpoint [EconomicPolicy.checkpoint], forwarded.
 * @property issuers [EconomicPolicy.issuers], forwarded.
 * @property unvouchedBootstrap [EconomicPolicy.unvouchedBootstrap], forwarded.
 * @property warnings every condition [startupRecord] found worth flagging to the operator, in a
 *   fixed order: the unvouched-bootstrap warning (`[ECO1-MINT-10r]`) before the unfitted-policy
 *   warning (`[ECO1-BUD-09]`), when both apply.
 */
data class StartupRecord(
    val label: String,
    val fitted: Boolean,
    val prices: Map<ClaimClass, Long>,
    val capacities: Map<ClaimClass, Long>,
    val refill: Map<ClaimClass, EconomicPolicy.Refill>,
    val retention: EconomicPolicy.Retention,
    val checkpoint: EconomicPolicy.Checkpoint?,
    val issuers: Map<String, EconomicPolicy.IssuerBudget>,
    val unvouchedBootstrap: Map<ClaimClass, Long>,
    val warnings: List<String>,
) {
    /**
     * ONE multi-line, deterministic-order rendering of every field above: classes in
     * [ClaimClass] declaration order, issuers sorted by name, [K_HOSTS_STATEMENT] verbatim as
     * its own line exactly once (`[ECO1-PAR-04]`).
     */
    override fun toString(): String {
        val classes = ClaimClass.entries
        val lines = mutableListOf<String>()
        lines += "StartupRecord for '$label':"
        lines += if (fitted) "fitted: true" else "fitted: false (placeholder)"
        lines += "prices: " + classes.joinToString(", ") { "${it.name}=${prices[it] ?: 0}" }
        lines += "capacities: " + classes.joinToString(", ") { "${it.name}=${capacities[it] ?: 0}" }
        lines += "refill: " + classes.joinToString(", ") { c ->
            val r = refill[c]
            "${c.name}=" + if (r == null) "none" else "${r.tokensPerInterval}/${r.intervalNanos}ns"
        }
        lines += "retention: idleNanos=${retention.idleNanos}, maxBuckets=${retention.maxBuckets}, " +
            "recentKeys=${retention.recentKeys}"
        lines += "checkpoint: " + if (checkpoint == null) {
            "none"
        } else {
            "cadenceNanos=${checkpoint.cadenceNanos}, stalenessBoundNanos=${checkpoint.stalenessBoundNanos}"
        }
        if (issuers.isEmpty()) {
            lines += "issuers: none"
        } else {
            issuers.toSortedMap().forEach { (name, row) ->
                val bootstrap = classes.mapNotNull { c -> row.bootstrap[c]?.let { "${c.name}=$it" } }
                    .joinToString(", ")
                val cap = row.aggregateHoldCap?.toString() ?: "none"
                lines += "issuer $name: bootstrap {$bootstrap}, aggregateHoldCap $cap"
            }
        }
        lines += if (unvouchedBootstrap.isEmpty()) {
            "unvouchedBootstrap: {} (zero for every class)"
        } else {
            "unvouchedBootstrap: " + classes.mapNotNull { c ->
                unvouchedBootstrap[c]?.let { "${c.name}=$it" }
            }.joinToString(", ")
        }
        lines += K_HOSTS_STATEMENT
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * `[ECO1-PAR-04]`'s fixed text: a Principal's budget is per-host, never globally
         * accounted, so its aggregate across k independent hosts is bounded by k times its
         * per-host budget and nothing tighter is claimed.
         */
        const val K_HOSTS_STATEMENT: String =
            "a Principal's aggregate across k independent hosts is bounded by k × its per-host budget; " +
                "no global bound is claimed"
    }
}

/**
 * Builds the [StartupRecord] for this applied policy, deriving [StartupRecord.warnings] from it.
 * Pure: two calls on the same [AppliedPolicy] return equal records, and this performs no I/O —
 * printing is [StartupRecord.emit]'s job.
 *
 * Warning order when both apply (`[ECO1-MINT-10r]` before `[ECO1-BUD-09]`):
 * 1. any class in [EconomicPolicy.unvouchedBootstrap] with a value `> 0` warns that the budget
 *    system is unbounded by identity cost on that side (key-derived identities are free to mint,
 *    `[DSC1-NV-02]`);
 * 2. an unfitted policy ([AppliedPolicy.fitted] `false`) warns that its numbers are a PLC2
 *    (`computenet-z1w`) placeholder.
 */
fun AppliedPolicy.startupRecord(): StartupRecord {
    val classes = ClaimClass.entries
    val policy = this.policy
    val warnings = mutableListOf<String>()

    val unvouchedNonZero = classes.filter { c -> (policy.unvouchedBootstrap[c] ?: 0) > 0 }
    if (unvouchedNonZero.isNotEmpty()) {
        val grants = unvouchedNonZero.joinToString(",") { c -> "${c.name}=${policy.unvouchedBootstrap[c]}" }
        warnings += "WARNING: unvouchedBootstrap grants $grants; the budget system on this side is " +
            "unbounded by identity cost (key-derived identities are free to mint, [DSC1-NV-02])"
    }

    if (!fitted) {
        warnings += "WARNING: economic policy '$label' is an unfitted placeholder; its prices, " +
            "capacities and refills are not fitted by PLC2 (computenet-z1w)"
    }

    return StartupRecord(
        label = label,
        fitted = fitted,
        prices = policy.prices,
        capacities = policy.capacities,
        refill = policy.refill,
        retention = policy.retention,
        checkpoint = policy.checkpoint,
        issuers = policy.issuers,
        unvouchedBootstrap = policy.unvouchedBootstrap,
        warnings = warnings,
    )
}

/**
 * Prints this record for startup: [StartupRecord.toString] first, then each
 * [StartupRecord.warnings] entry in order, via [warn]. Never throws — a throwing [warn] is
 * caught and swallowed so startup proceeds (`[ECO1-MINT-10r]` "startup SHALL proceed"). The
 * default `warn` is `System.err.println`, not a clock read (`kwhw6-D12`, `NoWallClockTest`).
 */
fun StartupRecord.emit(warn: (String) -> Unit = { System.err.println(it) }) {
    fun safeWarn(line: String) {
        try {
            warn(line)
        } catch (_: Exception) {
            // Swallowed by design: a failing sink must not stop startup, and must not stop the
            // rest of the record or later warnings from being attempted.
        }
    }
    safeWarn(toString())
    warnings.forEach(::safeWarn)
}
