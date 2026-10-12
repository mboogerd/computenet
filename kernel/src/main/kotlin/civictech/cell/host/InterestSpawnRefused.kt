package civictech.cell.host

/** A keyed family refused an interest arm that does not enumerate a bounded key set. */
class InterestSpawnRefused(
    val namespace: String,
    val arm: String,
) : IllegalArgumentException("family '$namespace' cannot spawn from interest arm '$arm'")
