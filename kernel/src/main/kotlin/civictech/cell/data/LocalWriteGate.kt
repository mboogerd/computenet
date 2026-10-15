package civictech.cell.data

/**
 * Pre-mutation admission seam for local writes to a replicable data cell.
 *
 * The gate deliberately knows only the operation and affected element. The
 * replication adapter owns principal lookup and denial accounting; data cells
 * only guarantee that a refusal happens before they mint a tag or dot.
 */
fun interface LocalWriteGate {
    fun admits(op: String, element: Any?): Boolean
}
