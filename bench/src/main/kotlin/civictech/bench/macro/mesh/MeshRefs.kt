package civictech.bench.macro.mesh

import civictech.cell.CellRef
import civictech.testkit.dst.StableRefs

/**
 * Deterministic references for cells in a benchmark mesh.
 *
 * This is a pure function of [name], [index], and [hostCount]. The derivation
 * is the `[BEN2-14]` contract, so callers can recreate a peer's reference
 * without retaining construction state.
 */
object MeshRefs {
    /** Derive the reference for [name] on peer [index] in a mesh of [hostCount]. */
    fun ref(name: String, index: Int, hostCount: Int): CellRef =
        StableRefs("bench:$hostCount", index.toLong()).ref(name)
}
