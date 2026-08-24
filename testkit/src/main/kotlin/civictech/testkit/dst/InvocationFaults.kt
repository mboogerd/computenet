package civictech.testkit.dst

import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import java.util.WeakHashMap

/**
 * Transforms an invocation on its way into a sink. The invocation-plane counterpart of
 * [FrameInterposer], and deliberately the same shape: it is handed one invocation and the step
 * it is in flight during, and returns the invocations to deliver in its place — the invocation
 * unchanged, none (drop), or the same one several times (duplicate).
 *
 * **Unlike an edge, this seam owns its sink.** [InvocationPoint] delivers what an interposer
 * returns, so the "an edge is a transform, not a transport" limit of [FrameInterposer] does not
 * apply here: a held invocation *could* be released later by something other than a following
 * invocation. Nothing in CHA1 uses that — intra-host reorder is deferred by epic risk 1
 * recommendation (a) — but the asymmetry is worth stating, because it is why a *frame* reorder
 * has to document stranding and an invocation one would not.
 */
fun interface InvocationInterposer {
    fun apply(invocation: HostedPortInvocation, step: Int): List<HostedPortInvocation>
}

/**
 * The composable primitives an invocation-plane fault is assembled from, mirroring
 * [FrameInterposers] one-for-one so that a fault class can offer the same behaviour on both
 * planes without two implementations of the behaviour itself.
 */
object InvocationInterposers {

    /** Deliver every invocation unchanged. */
    val pass: InvocationInterposer = InvocationInterposer { invocation, _ -> listOf(invocation) }

    /**
     * Re-deliver each invocation [copies] extra times with probability [probability], while the
     * step is inside [window] ([CHA1-16]).
     *
     * `HostedPortInvocation` is a data class and is treated as immutable by the kernel, so the
     * duplicate is the *same* instance rather than a copy — which is the invocation-plane
     * meaning of "byte-identical": nothing was re-encoded, re-stamped or re-timestamped between
     * the two deliveries. (On the frame plane [FrameInterposers.duplicating] copies the array,
     * because a `ByteArray` is mutable and sharing one would let a decoder corrupt its own
     * second delivery.)
     *
     * The original is delivered first, then its copies.
     */
    fun duplicating(
        copies: Int = 1,
        probability: Double = 1.0,
        rng: java.util.Random,
        window: StepWindow = StepWindow.ALWAYS,
        onDuplicate: (Int) -> Unit = {},
    ): InvocationInterposer {
        require(copies >= 1) { "a duplicate makes at least one extra copy, got copies=$copies" }
        require(probability > 0.0 && probability <= 1.0) {
            "probability is a per-invocation chance in (0, 1], got $probability"
        }
        return InvocationInterposer { invocation, step ->
            if (!window.contains(step)) {
                listOf(invocation)
            } else if (probability < 1.0 && rng.nextDouble() >= probability) {
                listOf(invocation)
            } else {
                onDuplicate(copies)
                List(copies + 1) { invocation }
            }
        }
    }

    /** Run [stages] in order, innermost first, exactly as [FrameInterposers.chain] does. */
    fun chain(vararg stages: InvocationInterposer): InvocationInterposer =
        if (stages.isEmpty()) pass
        else InvocationInterposer { invocation, step ->
            stages.fold(listOf(invocation)) { xs, stage -> xs.flatMap { stage.apply(it, step) } }
        }
}

/**
 * One named, decorable point on the invocation plane: an [InvocationSink] the graph builder
 * hands to `LocationRegistry.publish(ref, sink, peer)` or `HostedCellProxy.create(ref, sink, …)`
 * in place of the real one, so a fault can act on invocations **before** they are encoded into
 * frames.
 *
 * Why this plane exists at all, given [DstWorld.edges] already covers frames: a graph that is
 * not bridged has no frames. A same-host proxy, a registry delivery, a replication fan-out
 * inside one process — all of those are invocations and none of them is a `ByteArray`. A
 * duplicate fault that only worked on the frame plane would silently do nothing to half the
 * graphs in the repo.
 *
 * Interposers chain in registration order, innermost first, and every registration returns an
 * [AutoCloseable] that removes it — the same contract as [Edge.intercept], for the same reason.
 */
class InvocationPoint internal constructor(
    val name: String,
    private val downstream: InvocationSink,
    private val world: DstWorld,
) : InvocationSink {

    private val interposers = mutableListOf<InvocationInterposer>()

    fun intercept(interposer: InvocationInterposer): AutoCloseable {
        interposers += interposer
        return AutoCloseable { interposers -= interposer }
    }

    /** True while anything is intercepting this point — for a report, never for control flow. */
    val intercepted: Boolean get() = interposers.isNotEmpty()

    /**
     * Apply the chain and deliver what it returns to the real sink, tracing one untagged event
     * per invocation that *entered* — the same accounting [Edge.deliver] does, so a suite can
     * count arrivals on this plane the way it counts frames on the other.
     */
    override fun deliver(invocation: HostedPortInvocation) {
        world.trace.emit(cell = world.cells.nameOf(invocation.cellRef), port = name)
        var current = listOf(invocation)
        interposers.forEach { interposer ->
            current = current.flatMap { interposer.apply(it, world.step) }
        }
        current.forEach(downstream::deliver)
    }

    override fun toString(): String = "InvocationPoint($name)"
}

/**
 * The graph's declared invocation points, keyed by world.
 *
 * **Why this is not a seventh seam on [DstWorld].** Same reason [LinkControls] is not: the rig
 * core publishes six seams and `Fault`'s contract is that a fault reaches the graph through
 * those, on pain of `DstWorld` growing a special case per fault class. An invocation point is
 * something only the *graph builder* can create — it has to be threaded into the exact
 * `publish`/`HostedCellProxy.create` call the graph makes, which the rig cannot derive from a
 * name — so it is declared here, beside the faults that read it.
 *
 * That is a finding about the seams, recorded rather than worked around: if a later task gives
 * `DstWorld` an invocation-plane registry, this one becomes redundant and the faults keep their
 * shape. Entries are held on a [WeakHashMap] keyed by world identity, so nothing outlives the
 * run that declared it and two concurrent tests cannot see each other's points.
 *
 * ```kotlin
 * val point = InvocationPoints.declare(world, "a->registry", InvocationSink(registry::deliver))
 * LocationRegistry().publish(ref, point)      // the graph hands over the point, not the sink
 * ```
 */
object InvocationPoints {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, InvocationPoint>>()

    /** Declare [name] as a decorable stand-in for [sink]. Returns the sink to wire in. */
    @Synchronized
    fun declare(world: DstWorld, name: String, sink: InvocationSink): InvocationPoint {
        val points = byWorld.getOrPut(world) { linkedMapOf() }
        require(name !in points) { "invocation point \"$name\" is already declared" }
        return InvocationPoint(name, sink, world).also { points[name] = it }
    }

    @Synchronized
    fun names(world: DstWorld): Set<String> = byWorld[world]?.keys?.toSet() ?: emptySet()

    @Synchronized
    fun find(world: DstWorld, name: String): InvocationPoint? = byWorld[world]?.get(name)

    /**
     * The point named [name], or a loud failure listing what the graph did declare.
     *
     * Resolved at a fault's `install`, i.e. before the first step, for the reason
     * [LinkControls.require] is: a plan naming a point no graph declared is a broken
     * experiment, and a broken experiment must not first be discovered several hundred steps
     * in, having already been reported as applied.
     */
    @Synchronized
    fun require(world: DstWorld, name: String): InvocationPoint =
        find(world, name) ?: throw IllegalStateException(
            "no invocation point \"$name\" is declared, so an invocation-plane fault cannot reach it. " +
                "The graph builder declares one with InvocationPoints.declare(world, \"$name\", sink) and hands " +
                "the result to LocationRegistry.publish / HostedCellProxy.create; " +
                "declared points: ${names(world).sorted()}",
        )
}
