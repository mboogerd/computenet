package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.link.Interest
import java.util.concurrent.CompletableFuture

/**
 * The result of recording one registry interest declaration.
 *
 * [spawned] completes after every listener that owned the declaration has
 * finished its induced spawns. It is already complete with an empty set when
 * no listener owned the declaration.
 */
data class InterestAdmission(
    val ref: CellRef,
    val interest: Interest,
    val spawned: CompletableFuture<Set<CellRef>>,
)
