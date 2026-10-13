package civictech.economy.adversarial

import civictech.cell.ClaimClass
import civictech.economy.LedgerSnapshot

/** Render this snapshot as the stable markdown table consumed by the ECO1 findings entry. */
fun LedgerSnapshot.findingsRow(): String = buildString {
    appendLine("| ClaimClass | admitted | denied |")
    appendLine("| --- | ---: | --- |")
    ClaimClass.entries.forEach { claimClass ->
        val refused = denied[claimClass]
            .orEmpty()
            .entries
            .sortedBy { it.key.name }
            .joinToString(", ") { (reason, count) -> "${reason.name}=$count" }
            .ifEmpty { "-" }
        appendLine("| ${claimClass.name} | ${admitted[claimClass] ?: 0L} | $refused |")
    }
    append("buckets: $bucketCount scope: $scope")
}
