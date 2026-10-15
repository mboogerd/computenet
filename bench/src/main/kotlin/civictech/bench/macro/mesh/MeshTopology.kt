package civictech.bench.macro.mesh

/**
 * The peer-level topologies supported by the macro benchmark harness.
 *
 * The returned pairs are ordered with the lower peer index first and are the
 * complete set of undirected loopback connections for this topology.
 */
enum class MeshTopology {
    FULL_MESH,
    STAR,
    ;

    /** Return the loopback pairs for a mesh containing [hostCount] peers. */
    fun edges(hostCount: Int): List<Pair<Int, Int>> {
        require(hostCount >= 1) { "hostCount must be at least 1" }
        return when (this) {
            FULL_MESH -> buildList {
                for (i in 0 until hostCount) {
                    for (j in i + 1 until hostCount) add(i to j)
                }
            }
            STAR -> (1 until hostCount).map { 0 to it }
        }
    }
}
