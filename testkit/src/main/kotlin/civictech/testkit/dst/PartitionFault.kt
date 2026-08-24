package civictech.testkit.dst

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.wire.Peering
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * What a partition *does* to the traffic it stops ([CHA1-12]).
 *
 * The distinction is not a detail of the injector: it is the difference between a fault the
 * system is expected to survive and one it is not, and a rig that collapsed the two would
 * report "partitioned" for two runs whose only honest outcomes differ.
 */
enum class PartitionMode {

    /**
     * **Frames are destroyed.** Nothing buffers them, nothing replays them, and healing only
     * stops the destruction — it does not resend what was lost. A graph with no post-heal
     * anti-entropy therefore *stays* divergent, which is what makes drop the diverging control
     * of [CHA1-63].
     *
     * Applied on the frame plane, through [DstWorld.edges].
     */
    DROP,

    /**
     * **Traffic stops without being destroyed.** Nothing that was in flight is lost, and a
     * correct graph converges after the heal with no repair protocol of its own — which is
     * what makes park the *converging* half of the [CHA1-62]/[CHA1-63] pair.
     *
     * **How the heal restores the traffic is the [LinkControl]'s, not this mode's**, and the
     * two primitives the kernel offers differ exactly there:
     * `LocationRegistry.hold`/`release` ([LinkControl.holding]) parks deliveries in arrival
     * order and *drains* them on release, while `Peering.Loopback.partition()`/`heal()`
     * ([LinkControl.severing]) severs the peering and re-announces on a fresh connection,
     * replaying no buffer at all. Both leave a converging graph converged; only the first
     * literally replays. That difference is why [PartitionFault.describe] reports the
     * declared control's own [LinkControl.scope] instead of asserting a replay for every park
     * ([CHA1-12] "labelled in the report").
     *
     * Neither primitive is reachable from the frame plane — a [FrameInterposer] can drop a
     * frame but cannot hold one and inject it later (see [FrameInterposer]'s known limit) —
     * so park is applied through a [LinkControl] the graph builder declares, not through
     * [DstWorld.edges].
     */
    PARK,
}

/**
 * The park primitive for one named edge: whatever the graph under test can use to stop
 * traffic *without losing it*, and to release it again.
 *
 * **Supplied by the graph builder, like [HostSlot]'s rebuild function and for the same
 * reason.** The kernel offers two park primitives with different reach —
 * `Peering.Loopback.partition()`/`heal()` severs a whole peering, and
 * `LocationRegistry.hold`/`release` parks deliveries to one ref — and neither can be derived
 * from an edge name by a rig that does not know how the graph was wired. So the graph
 * declares one per edge, and [PartitionFault] in [PartitionMode.PARK] is reduced to calling
 * it.
 *
 * [scope] is the honest description of **what this control actually stops, and what its heal
 * actually does**, and it is reported verbatim by [PartitionFault.describe] ([CHA1-12]
 * "labelled in the report").
 *
 * It carries both halves because a park report cannot be honest without either:
 *  - **Reach and direction can disagree.** An edge is one direction by construction, while
 *    `Loopback.partition()` severs both. A control whose scope is wider than its edge is
 *    legal — it is what the kernel offers — but it must say so, or a report will claim a
 *    one-way partition that was not one.
 *  - **Only one of the two primitives replays.** [holding] drains a buffer on release;
 *    [severing] re-announces on a fresh connection and replays nothing. `describe` used to
 *    print [PartitionMode.PARK]'s spec sentence ("traffic parks and replays on heal") for
 *    both, which made the report claim a replay for every severing-backed park that never
 *    happened. The claim now lives here, in the scope of the control that can support it.
 */
class LinkControl(
    val scope: String,
    private val onPark: () -> Unit,
    private val onHeal: () -> Unit,
) {
    fun park() = onPark()

    fun heal() = onHeal()

    override fun toString(): String = "LinkControl($scope)"

    companion object {

        /**
         * Park by holding deliveries to [ref] on [registry] — the kernel's per-ref flip-window
         * buffer (`LocationRegistry.hold`/`release`), which parks in arrival order and drains
         * in park order on release.
         *
         * **Directional, with one caveat worth stating**: it stops everything addressed to
         * [ref] on that registry, which is the peer's inbound traffic *and* that peer's own
         * local writes to the same cell. Those local writes are delayed, never lost, so
         * convergence after the heal is unaffected — but a test that counts "writes applied
         * during the window" must expect zero from both sides, not just from across the link.
         */
        fun holding(
            registry: LocationRegistry,
            ref: CellRef,
            scope: String = "deliveries to $ref on one registry — parked in arrival order " +
                "and replayed on heal, in park order",
        ): LinkControl = LinkControl(scope, { registry.hold(ref) }, { registry.release(ref) })

        /**
         * Park by severing the whole peering — `Peering.Loopback.partition()`, healed by
         * `heal()`, the repo's only pre-existing shared fault primitive.
         *
         * **Bidirectional**, and the default [scope] says so: a heal is a fresh connection
         * instance with a full catch-up on both sides, so what converges afterwards converges
         * because the peering re-announced, not because a buffer drained.
         */
        fun severing(
            loopback: Peering.Loopback,
            scope: String = "the whole loopback peering — park severs both directions, and " +
                "heal re-announces on a fresh connection rather than replaying a buffer",
        ): LinkControl = LinkControl(scope, loopback::partition, loopback::heal)
    }
}

/**
 * The graph's declared park primitives, one per edge name ([PartitionMode.PARK]).
 *
 * **Why this is not a seventh seam on [DstWorld].** The rig core publishes six seams and
 * `Fault`'s contract is that a fault reaches the graph through those and nothing else, on
 * pain of `DstWorld` growing a special case per fault class. Park genuinely does not fit any
 * of them: it is not a frame transform ([DstWorld.edges] hands its result back to the graph
 * builder and owns no sink, so it cannot hold a frame and inject it later), not a host
 * rebuild, not a journal decoration, and not something a step hook can reach. Rather than
 * widen `DstWorld` — which the rig core owns and this task does not — the declaration lives
 * here, beside the only fault that reads it, keyed by the world it belongs to.
 *
 * That is a **finding about the seams, recorded rather than worked around**: if a later task
 * gives `Edges.declare` a delivery sink and an injection call, a park could be expressed as
 * an interposer and this registry becomes redundant. Until then a graph that wants park
 * declares it:
 *
 * ```kotlin
 * val loop = Peering.loopback(a.side, b.side, interposeAToB = ..., interposeBToA = ...)
 * LinkControls.declare(world, "a->b", LinkControl.severing(loop))
 * ```
 *
 * Entries are keyed by identity on a [WeakHashMap], so a world declared by one test cannot be
 * seen by another and nothing outlives the run that declared it.
 */
object LinkControls {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, LinkControl>>()

    /**
     * Declare [edge]'s park primitive. The edge must already be declared on [DstWorld.edges] —
     * a control on a name no edge carries could never fire, and finding that out at install
     * time is the whole point of [CHA1-23].
     */
    @Synchronized
    fun declare(world: DstWorld, edge: String, control: LinkControl): LinkControl {
        require(world.edges.find(edge) != null) {
            "cannot declare a park control for undeclared edge \"$edge\"; " +
                "known edges: ${world.edges.names().sorted()}"
        }
        val controls = byWorld.getOrPut(world) { linkedMapOf() }
        require(edge !in controls) { "edge \"$edge\" already has a park control: ${controls[edge]}" }
        controls[edge] = control
        return control
    }

    @Synchronized
    fun names(world: DstWorld): Set<String> = byWorld[world]?.keys?.toSet() ?: emptySet()

    @Synchronized
    fun find(world: DstWorld, edge: String): LinkControl? = byWorld[world]?.get(edge)

    /** The control for [edge], or a loud failure naming what the graph did declare. */
    @Synchronized
    fun require(world: DstWorld, edge: String): LinkControl =
        find(world, edge) ?: throw IllegalStateException(
            "edge \"$edge\" has no park control, so PartitionMode.PARK cannot be applied to it. " +
                "The graph builder declares one with LinkControls.declare(world, \"$edge\", ...); " +
                "edges with a control: ${names(world).sorted()}",
        )
}

/**
 * Stop traffic on one named edge, one way, for a window of controller steps
 * ([CHA1-10]..[CHA1-13]).
 *
 * ## One edge is one direction
 *
 * [DstWorld.edges] declares each direction of a wire as its own named edge, so a **one-way**
 * partition ([CHA1-13], BS-5) needs no direction parameter here: it is a fault targeting
 * `"a->b"` and not `"b->a"`. Two faults, one per name, partition both ways. What direction a
 * given fault actually achieves in [PartitionMode.PARK] additionally depends on the reach of
 * the [LinkControl] the graph declared, which is why [describe] prints that control's scope.
 *
 * ## Park and drop are different faults, not two settings of one
 *
 * See [PartitionMode]. They are one class because they are configured, targeted, validated and
 * reported identically, and because a suite that wants the [CHA1-62]/[CHA1-63] pair wants the
 * *same* run under both — but they reach the graph through different seams and they leave the
 * system in different states, and [describe] labels which one fired.
 *
 * ## What it costs when the window never closes
 *
 * The healing step is a step the run must actually reach: [onStep] is the only clock a fault
 * has, so a run that quiesces before [StepWindow.until] ends still partitioned. That is not
 * silently repaired — a suite asserting convergence after a heal should assert the heal
 * happened, by checking that `activationSteps` in the report contains both endpoints.
 */
data class PartitionFault(
    override val id: String,
    val edge: String,
    val mode: PartitionMode,
    val window: StepWindow,
) : Fault {

    /** Removes the drop interposer at the healing step; null outside a [PartitionMode.DROP] window. */
    private var installed: AutoCloseable? = null

    /** Resolved at install so a missing park primitive fails before the run rather than during it. */
    private var control: LinkControl? = null

    override val targets: List<FaultTarget> get() = listOf(FaultTarget.Edge(edge))

    override fun describe(): String = when (mode) {
        PartitionMode.DROP ->
            "partition(edge=$edge, DROP, ${window}): frames on this edge are destroyed; " +
                "healing stops the destruction and replays nothing"

        // The mode's guarantee is only that nothing is destroyed; what the heal actually
        // restores is the declared control's, so the label reports that control's own scope
        // rather than repeating [PartitionMode.PARK]'s spec sentence. See [LinkControl.scope].
        PartitionMode.PARK ->
            "partition(edge=$edge, PARK, ${window}): traffic stops without being destroyed; " +
                "via ${control?.scope ?: "an undeclared control, so nothing fired"}"
    }

    override fun install(world: DstWorld) {
        when (mode) {
            PartitionMode.DROP ->
                installed = world.edges.intercept(
                    edge,
                    FrameInterposers.windowed(
                        window,
                        FrameInterposers.tracing(world, id, edge, FrameInterposers.drop()),
                    ),
                )

            // Resolved now, not at the opening step: a plan naming an edge with no park
            // primitive is a broken experiment, and a broken experiment must not first be
            // discovered several hundred steps in, having already been reported as applied.
            PartitionMode.PARK -> control = LinkControls.require(world, edge)
        }
    }

    override fun onStep(world: DstWorld, step: Int) {
        when (mode) {
            // The interposer gates itself on the window per frame, so opening needs no hook;
            // deregistering at the healing step is what makes `Edge.intercepted` and the cost
            // of the chain honest once the fault is done.
            PartitionMode.DROP -> if (window.healedAt(step)) {
                installed?.close()
                installed = null
            }

            PartitionMode.PARK -> {
                val link = control ?: return
                if (step == window.from) {
                    link.park()
                    world.trace.fault(id, port = edge)
                } else if (step == window.until) {
                    link.heal()
                    world.trace.fault(id, port = edge)
                }
            }
        }
    }

    companion object {

        /** The `kind` a [PartitionFault] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "dst-partition"

        /**
         * This class's [FaultCodec], registered when the class is loaded — see
         * [CrashFault.CODEC] for why the companion object is the registration point and what
         * the decode path's load requirement is.
         *
         * ## The window is encoded flat, and that is load-bearing
         *
         * [StepWindow] is written as two top-level parameters, `from` and `until`, not as a
         * nested object. [ReductionStrategies.numericParamToward] reads a parameter by name off
         * `FaultRecord.params`, so `until` toward `from` — the epic's own "shorten the partition
         * window" reduction — is only reachable if `until` is a top-level primitive. A nested
         * `{"window": {"from": ..., "until": ...}}` would round-trip perfectly and leave the
         * shrinker with nothing to shrink.
         *
         * A candidate with `until <= from` is refused by [StepWindow]'s own `require`, which is
         * exactly the "codec refuses these parameters" case
         * [ReductionStrategies.numericParamToward] documents as silently skipped: an
         * unbuildable window is not a plan, so it is never run.
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is PartitionFault },
            encode = { fault ->
                val partition = fault as PartitionFault
                buildJsonObject {
                    put("edge", partition.edge)
                    put("mode", partition.mode.name)
                    put("from", partition.window.from)
                    put("until", partition.window.until)
                }
            },
            decode = { id, params -> decodeFrom(id, params) },
        )

        private fun decodeFrom(id: String, params: JsonObject): PartitionFault = PartitionFault(
            id = id,
            edge = params.getValue("edge").jsonPrimitive.content,
            mode = PartitionMode.valueOf(params.getValue("mode").jsonPrimitive.content),
            window = StepWindow(
                from = params.getValue("from").jsonPrimitive.int,
                until = params.getValue("until").jsonPrimitive.int,
            ),
        )

        /** Destroy every frame on [edge] for `[from, until)`. See [PartitionMode.DROP]. */
        fun drop(id: String, edge: String, from: Int, until: Int = Int.MAX_VALUE): PartitionFault =
            PartitionFault(id, edge, PartitionMode.DROP, StepWindow(from, until))

        /**
         * Park traffic on [edge] for `[from, until)`, releasing the control at [until].
         * Whether that release replays a buffer or re-announces is the declared
         * [LinkControl]'s — see [PartitionMode.PARK] — and the graph must have declared one
         * for [edge].
         */
        fun park(id: String, edge: String, from: Int, until: Int): PartitionFault =
            PartitionFault(id, edge, PartitionMode.PARK, StepWindow(from, until))
    }
}
