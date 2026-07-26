package civictech.cell

/** Payloads declaring the associative merge used by a saturated intake. */
interface MergeablePayload {
    fun mergeWith(other: MergeablePayload): MergeablePayload
}
