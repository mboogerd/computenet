package civictech.testkit.dst

/**
 * Builds the graph under test into a freshly constructed [DstWorld].
 *
 * Called exactly once per run, before any fault is installed and before the first step: every
 * name a fault can target ([DstWorld.edges], [DstWorld.hosts], [DstWorld.journals],
 * [DstWorld.cells]) must be declared by the time this returns, or [CHA1-23]'s validation
 * cannot see it.
 *
 * A builder must be **deterministic given the world's seed** — same seed, same graph, same
 * declarations, in the same order. Anything random comes from [DstWorld.rng].
 */
fun interface GraphBuilder {
    fun build(world: DstWorld)
}

/**
 * A graph builder as a *value with a stable id* ([CHA1-06]): a replay artifact names the
 * [id], not the test method that happened to construct the graph, so replay does not require
 * the original source of randomness or the original call site.
 *
 * The id is the artifact's whole handle on the graph, so treat it as a published name:
 * renaming one orphans every artifact that recorded it. Prefix it with the suite it belongs
 * to (`exchange-composition`, `bridged-diamond`) rather than naming it after a test method.
 */
data class GraphSpec(val id: String, val builder: GraphBuilder) {
    init {
        require(id.isNotBlank()) { "a graph spec needs a non-blank id — artifacts name graphs by it" }
    }
}

/**
 * Maps a [GraphSpec.id] back to its builder, so a replay artifact read from disk can find the
 * graph it names ([CHA1-06]).
 *
 * Deliberately a plain mutable registry rather than a service loader: a consumer suite
 * registers its graphs in a companion initialiser or a `@BeforeAll`, and replay of an
 * artifact whose graph was never registered fails with [unknown], naming the id and the
 * registered set — the same fail-loudly-with-alternatives shape as
 * [UnknownFaultTargetException]. The artifact *format* is a later task; this is only the
 * lookup it will need.
 */
object GraphRegistry {
    private val specs = linkedMapOf<String, GraphSpec>()

    /** Register (or re-register, idempotently) a graph. Returns the spec for chaining. */
    fun register(spec: GraphSpec): GraphSpec {
        val existing = specs[spec.id]
        require(existing == null || existing.builder === spec.builder) {
            "graph id \"${spec.id}\" is already registered to a different builder"
        }
        specs[spec.id] = spec
        return spec
    }

    fun register(id: String, builder: GraphBuilder): GraphSpec = register(GraphSpec(id, builder))

    fun ids(): Set<String> = specs.keys.toSet()

    fun find(id: String): GraphSpec? = specs[id]

    fun require(id: String): GraphSpec = specs[id] ?: throw unknown(id)

    /** Visible for suites that register per-test graphs and must not leak them into others. */
    fun unregister(id: String) {
        specs -= id
    }

    private fun unknown(id: String) =
        IllegalArgumentException("unknown graph id \"$id\"; registered graphs: ${specs.keys.sorted()}")
}
