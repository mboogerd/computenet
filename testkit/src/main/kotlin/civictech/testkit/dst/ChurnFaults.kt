package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * The membership-churn event vocabulary ([CHA3-03]), as rig faults.
 *
 * ## Why these are `Fault`s and not a parallel event type
 *
 * [CHA3-07] forbids CHA3 from shipping a fault injector, an artifact format, a replay
 * mechanism or a shrinker of its own. The cheapest way to obey that is not to *reuse* the
 * rig's machinery but to *be* its input: a churn event is an immutable [Fault] with a
 * [FaultCodec], so [FaultPlan], [DstArtifact], [DstReplay] and [PlanShrinker] carry a churn
 * plan with no change at all. Nothing in this file is a mechanism; everything in it is a
 * value.
 *
 * That has one consequence worth stating up front, because it constrains every field below:
 * `doc/dst-rig.md` §2 ("A fault must be a value") — **no field may be a lambda, and every
 * field must survive a JSON round trip as a top-level primitive**. So an event names its peer
 * by string and resolves the behaviour through [PeerHandles], exactly the way [CrashFault]
 * names a host slot and [PartitionFault]'s `PARK` mode names a [LinkControl].
 */

/**
 * How a peer leaves the mesh ([CHA3-03]).
 *
 * Four arms rather than one, because "departure" is four different experiments and a rig that
 * collapsed them would report the same word for runs whose honest outcomes differ:
 * `Replication.evict` has two arms (the ordinary one and the `closeDepartedRow = false` PN-0c
 * seam), a partition-induced suspension is a departure the peer does not know about and can
 * return from, and a crash bypasses every one of those paths.
 */
enum class DepartureMode {

    /**
     * Orderly eviction with the departed row closed — the graceful path, where the mesh gets
     * to observe the departure and the departed stream is closed behind it.
     */
    EVICT_CLEAN,

    /**
     * Orderly eviction with the departed row left open (`closeDepartedRow = false`, the PN-0c
     * seam): the peer is gone, but its stream is still live as far as the convergence rule is
     * concerned. The interesting case for a reconvergence property, because it is the one
     * where a departure and a live divergence look alike.
     */
    EVICT_NO_CLOSE,

    /**
     * The peer's host is discarded without any eviction at all — no announcement, no drain,
     * no row closing. Whatever the mesh believed about it at that instant is what it keeps
     * believing until reconciliation says otherwise.
     */
    CRASH_UNCLEAN,

    /**
     * The peer is not evicted or crashed: it is *suspended* behind a partition, which the mesh
     * may or may not distinguish from a departure. The one mode a peer can come back from
     * without a rejoin, and therefore the one that can turn a "departed" verdict into a
     * false positive.
     */
    PARTITION_SUSPEND,
}

/**
 * What a churn event actually does to one named peer of the graph under test.
 *
 * **Supplied by the graph builder**, like [HostSlot]'s rebuild function and [LinkControl]'s
 * park primitive, and for the identical reason: joining, evicting and reassigning are the
 * graph's own wiring (which `Replication` handle, which `InstanceSet`, which `CellRef`), and
 * none of it is derivable from a peer *name* by a rig that did not build the graph. A churn
 * event names the peer; this interface is where the behaviour lives.
 *
 * Implementations are the **task-2 executor's**, not this file's. What this file fixes is the
 * shape: four operations, all of them value-parameterised, none of them handed a lambda.
 *
 * Tracing is **not** the handle's job — the event calls [TraceSink.fault] itself, so that a
 * handle implementation cannot accidentally leave an event reporting inert ([CHA3-47],
 * reusing [CHA1-24]).
 */
interface PeerHandle {

    /** The peer joins the mesh for the first time. */
    fun join()

    /** The peer returns after a departure. */
    fun rejoin()

    /** The peer leaves, by the given [mode]. See [DepartureMode]. */
    fun depart(mode: DepartureMode)

    /**
     * The peer is assigned the named [interest] at routing [epoch] — the
     * `InstanceSet.assign(ref, interest, epoch)` call, with the kernel's `Interest` value
     * resolved from [interest] by the graph that declared this handle.
     *
     * [interest] is a **name**, not an `Interest`: `Interest` is a polymorphic value type and
     * would not survive the flat-primitive codec rule (`doc/dst-rig.md` §2). The graph knows
     * which slice a name denotes; the plan only has to be able to say the same name twice.
     */
    fun reassign(interest: String, epoch: Long)
}

/**
 * The graph's declared peer handles, one per peer name ([CHA3-46]).
 *
 * **Why this is not a seventh seam on [DstWorld].** Same answer as [LinkControls], and it is
 * `doc/dst-rig.md` §1's closing rule: a fault whose mechanism is not a frame transform, a host
 * rebuild, a journal decoration or a step-indexed hook is a per-graph declaration the graph
 * builder supplies and the fault resolves by name. Churn is exactly that — the mesh handle a
 * join reaches is the graph's, not the rig's.
 *
 * Entries are keyed by identity on a [WeakHashMap], so a world declared by one test cannot be
 * seen by another and nothing outlives the run that declared it.
 */
object PeerHandles {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, PeerHandle>>()

    /** Declare [peer]'s handle. A second declaration of the same name is refused, not merged. */
    @Synchronized
    fun declare(world: DstWorld, peer: String, handle: PeerHandle): PeerHandle {
        require(peer.isNotBlank()) { "a peer handle needs a non-blank name — churn events name peers by it" }
        val handles = byWorld.getOrPut(world) { linkedMapOf() }
        require(peer !in handles) { "peer \"$peer\" already has a handle: ${handles[peer]}" }
        handles[peer] = handle
        return handle
    }

    @Synchronized
    fun names(world: DstWorld): Set<String> = byWorld[world]?.keys?.toSet() ?: emptySet()

    @Synchronized
    fun find(world: DstWorld, peer: String): PeerHandle? = byWorld[world]?.get(peer)

    /** The handle for [peer], or a loud failure naming what the graph did declare. */
    @Synchronized
    fun require(world: DstWorld, peer: String): PeerHandle =
        find(world, peer) ?: throw IllegalStateException(
            "peer \"$peer\" has no declared handle, so a churn event cannot be applied to it. " +
                "The graph builder declares one with PeerHandles.declare(world, \"$peer\", ...); " +
                "peers with a handle: ${names(world).sorted()}",
        )
}

/**
 * A churn event's target seam: a peer name, resolved against [PeerHandles] before the first
 * step ([CHA3-46], reusing [CHA1-23]).
 *
 * Declaring it here rather than as a fifth arm of [FaultTarget] is deliberate: `FaultTarget`
 * is sealed over the same package and module, so a new arm in this file costs nothing and
 * edits no file another task owns. The payoff is that [DstRun]'s existing validation loop —
 * which knows only `target.name !in target.knownIn(world)` — fails a churn plan naming an
 * unknown peer *before the run starts*, with no churn-specific code anywhere in the rig core.
 */
data class PeerTarget(override val name: String) : FaultTarget {
    override val kind: String get() = "peer"
    override fun knownIn(world: DstWorld): Set<String> = PeerHandles.names(world)
}

/**
 * One membership-churn event: a [peer], a step index, and nothing else that is not a
 * primitive.
 *
 * Activation is a **controller step index** ([CHA3-02], inheriting [CHA1-02]): [atStep] is the
 * only clock, there is no wall-clock field, and [Fault.onStep] is the only place an event can
 * act. An event whose [atStep] the run never reaches never fires, and the report says so
 * ([CHA3-47]) rather than the plan quietly meaning less than it says.
 *
 * ## The `fired` latch is per-**run** state, and every implementation resets it in `install`
 *
 * [Fault]'s contract is that an implementation is a value with "any mutable per-run state created
 * in [Fault.install]", and the once-only latch each event below carries is exactly that. It has to
 * be reset there rather than merely initialised at construction, because one [FaultPlan] is
 * executed more than once **by design**: `DstRun.assertDeterministic` runs the same plan object
 * twice and compares trace digests, and `PlanShrinker` re-runs a candidate plan for every
 * reduction it grades. A latch that survived a run would make the second execution fire
 * *nothing* — measured as 8 trace events against 0 by `ChurnMeshTest`'s determinism assertion,
 * `computenet-umx.2.2` — and a shrinker would then grade every candidate against an adversary
 * that had already spent itself on the first run.
 */
sealed interface ChurnEvent : Fault {

    /** The peer this event acts on. Resolved through [PeerHandles] at [atStep]. */
    val peer: String

    /** The controller step at which this event fires — the only activation clock ([CHA3-02]). */
    val atStep: Int

    override val targets: List<FaultTarget> get() = listOf(PeerTarget(peer))
}

/**
 * Fire [action] against [ChurnEvent.peer]'s handle and record the firing.
 *
 * The [TraceSink.fault] call is here, once, rather than in four event classes, because
 * `doc/dst-rig.md` §1 seam 5 is exactly the rule an event is most likely to forget: a fault
 * that fires without it is invisible to [CHA1-24]'s count and reports inert even though it
 * ran.
 */
private fun ChurnEvent.fire(world: DstWorld, action: (PeerHandle) -> Unit) {
    action(PeerHandles.require(world, peer))
    world.trace.fault(id, host = peer)
}

private fun requireStep(atStep: Int, what: String) {
    require(atStep >= 0) { "a $what activates at a step index, got atStep=$atStep" }
}

/** A peer joins the mesh for the first time ([CHA3-03]). */
data class JoinEvent(
    override val id: String,
    override val peer: String,
    override val atStep: Int,
) : ChurnEvent {

    init {
        requireStep(atStep, "join")
    }

    private var fired = false

    /** See [ChurnEvent]: the latch is per-run state and is reset here, not only at construction. */
    override fun install(world: DstWorld) {
        fired = false
    }

    override fun describe(): String = "churn.join(peer=$peer, step=$atStep)"

    override fun onStep(world: DstWorld, step: Int) {
        if (fired || step != atStep) return
        fired = true
        fire(world) { it.join() }
    }

    companion object {

        /** The `kind` a [JoinEvent] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "churn-join"

        /**
         * Registered when the class loads — see [CrashFault.CODEC] for why the companion
         * object is the registration point and what the decode path's load requirement is.
         * Params are flat top-level primitives; see [ChurnEvent] and `doc/dst-rig.md` §2.
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is JoinEvent },
            encode = { fault ->
                val event = fault as JoinEvent
                buildJsonObject {
                    put("peer", event.peer)
                    put("atStep", event.atStep)
                }
            },
            decode = { id, params ->
                JoinEvent(
                    id = id,
                    peer = params.getValue("peer").jsonPrimitive.content,
                    atStep = params.getValue("atStep").jsonPrimitive.int,
                )
            },
        )
    }
}

/** A previously departed peer returns ([CHA3-03]). */
data class RejoinEvent(
    override val id: String,
    override val peer: String,
    override val atStep: Int,
) : ChurnEvent {

    init {
        requireStep(atStep, "rejoin")
    }

    private var fired = false

    /** See [ChurnEvent]: the latch is per-run state and is reset here, not only at construction. */
    override fun install(world: DstWorld) {
        fired = false
    }

    override fun describe(): String = "churn.rejoin(peer=$peer, step=$atStep)"

    override fun onStep(world: DstWorld, step: Int) {
        if (fired || step != atStep) return
        fired = true
        fire(world) { it.rejoin() }
    }

    companion object {

        /** The `kind` a [RejoinEvent] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "churn-rejoin"

        /** See [JoinEvent.CODEC]. */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is RejoinEvent },
            encode = { fault ->
                val event = fault as RejoinEvent
                buildJsonObject {
                    put("peer", event.peer)
                    put("atStep", event.atStep)
                }
            },
            decode = { id, params ->
                RejoinEvent(
                    id = id,
                    peer = params.getValue("peer").jsonPrimitive.content,
                    atStep = params.getValue("atStep").jsonPrimitive.int,
                )
            },
        )
    }
}

/** A peer leaves the mesh, by one of the four [DepartureMode]s ([CHA3-03]). */
data class DepartEvent(
    override val id: String,
    override val peer: String,
    override val atStep: Int,
    val mode: DepartureMode,
) : ChurnEvent {

    init {
        requireStep(atStep, "departure")
    }

    private var fired = false

    /** See [ChurnEvent]: the latch is per-run state and is reset here, not only at construction. */
    override fun install(world: DstWorld) {
        fired = false
    }

    override fun describe(): String = "churn.depart(peer=$peer, $mode, step=$atStep)"

    override fun onStep(world: DstWorld, step: Int) {
        if (fired || step != atStep) return
        fired = true
        fire(world) { it.depart(mode) }
    }

    companion object {

        /** The `kind` a [DepartEvent] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "churn-depart"

        /** See [JoinEvent.CODEC]. [mode] is written as its enum name, a top-level primitive. */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is DepartEvent },
            encode = { fault ->
                val event = fault as DepartEvent
                buildJsonObject {
                    put("peer", event.peer)
                    put("atStep", event.atStep)
                    put("mode", event.mode.name)
                }
            },
            decode = { id, params ->
                DepartEvent(
                    id = id,
                    peer = params.getValue("peer").jsonPrimitive.content,
                    atStep = params.getValue("atStep").jsonPrimitive.int,
                    mode = DepartureMode.valueOf(params.getValue("mode").jsonPrimitive.content),
                )
            },
        )
    }
}

/**
 * A peer is assigned a named [interest] at routing [epoch] ([CHA3-03]).
 *
 * This is the interest/epoch reassignment CHA1 §6 says CHA3 must be able to express: the
 * kernel call it lands on is `InstanceSet.assign(ref, interest, epoch)`
 * (`kernel/src/main/kotlin/civictech/cell/replication/InstanceSet.kt`), reached through the
 * graph's [PeerHandle].
 *
 * [epoch] is a `Long` because the kernel's is; it is still a flat JSON number and therefore
 * still reachable by [ReductionStrategies.numericParamToward].
 */
data class ReassignEvent(
    override val id: String,
    override val peer: String,
    override val atStep: Int,
    val interest: String,
    val epoch: Long,
) : ChurnEvent {

    init {
        requireStep(atStep, "reassignment")
        require(interest.isNotBlank()) { "a reassignment names an interest; got a blank name" }
        require(epoch >= 0) { "a routing epoch is non-negative, got epoch=$epoch" }
    }

    private var fired = false

    /** See [ChurnEvent]: the latch is per-run state and is reset here, not only at construction. */
    override fun install(world: DstWorld) {
        fired = false
    }

    override fun describe(): String = "churn.reassign(peer=$peer, interest=$interest, epoch=$epoch, step=$atStep)"

    override fun onStep(world: DstWorld, step: Int) {
        if (fired || step != atStep) return
        fired = true
        fire(world) { it.reassign(interest, epoch) }
    }

    companion object {

        /** The `kind` a [ReassignEvent] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "churn-reassign"

        /**
         * See [JoinEvent.CODEC]. Four flat params — `peer`, `atStep`, `interest`, `epoch` —
         * and in particular `epoch` is **not** nested with `interest` into an "assignment"
         * object, even though the kernel groups them in `Assignment(interest, epoch)`. A
         * nested object round-trips perfectly and is invisible to
         * [ReductionStrategies.numericParamToward], which reads a parameter by name off
         * `FaultRecord.params` (`doc/dst-rig.md` §2). `epoch` is the numeric knob here; see
         * [civictech.testkit.dst.churn.ChurnReductions] for the direction and why.
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is ReassignEvent },
            encode = { fault ->
                val event = fault as ReassignEvent
                buildJsonObject {
                    put("peer", event.peer)
                    put("atStep", event.atStep)
                    put("interest", event.interest)
                    put("epoch", event.epoch)
                }
            },
            decode = { id, params ->
                ReassignEvent(
                    id = id,
                    peer = params.getValue("peer").jsonPrimitive.content,
                    atStep = params.getValue("atStep").jsonPrimitive.int,
                    interest = params.getValue("interest").jsonPrimitive.content,
                    epoch = params.getValue("epoch").jsonPrimitive.long,
                )
            },
        )
    }
}
