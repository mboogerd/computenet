package civictech.testkit.dst

import civictech.cell.CellRef
import java.util.UUID
import java.util.WeakHashMap

/**
 * What a graph builder must supply before a [CrashFault] can be aimed at one of its hosts,
 * and the honest reason the rig cannot supply it itself (epic computenet-umx §9 risk 5).
 *
 * ## Crash faults require a caller-supplied deterministic rebuild function
 *
 * A crash is not "restart the process". [CHA1-17] is specific: the target host's in-flight
 * tasks, queues and links are discarded, and the host is rebuilt **with the same `CellRef`s**
 * so that the surviving journal's frames still address the cells they were accepted for. The
 * rig cannot derive those refs for an arbitrary graph — a `CellRef` defaults to a fresh random
 * UUID per construction, and nothing in a `GraphBuilder` tells the rig which of the cells it
 * spawned are "the same cell" across a rebuild. **So the graph declares its own rebuild
 * function** ([HostSlots.declare]) and the fault is reduced to calling [HostSlot.crash].
 *
 * That is a stated limitation, not a gap someone forgot to close: **the rig does not pretend
 * to rebuild arbitrary graphs.** A rebuild function that mints fresh refs compiles, runs, and
 * produces a post-crash graph the journal cannot address — every replayed frame dead-letters
 * against an unknown cell, the run still quiesces, and the report says `PASSED`. Nothing in
 * the rig can detect that for you; [StableRefs] exists so the deterministic case is the easy
 * one to write.
 *
 * ## Two things a rebuild function owes its graph
 *
 *  1. **Same refs, same order.** Same [HostBuildContext] name, same cells, same
 *     `CellRef`s, same links, in the same order — [StableRefs] is the mechanical way.
 *  2. **Re-declare, do not re-declare-fresh.** [DstWorld.cells] rejects a duplicate name, so
 *     a rebuild re-points an existing name with `world.cells.redeclare(name, ref)` (or
 *     [StableRefs.declaredIn], which does it for you). A rebuild that calls `declare` twice
 *     fails on generation 1 with "cell is already declared" — a confusing way to learn this.
 *
 * ## What is deliberately not here
 *
 * Recovery. `recoverFrom(journal)` is [CrashFault]'s, after the rebuild returns, because
 * *which* journal a rebuilt host recovers from — and from which prefix — is exactly what a
 * journal or frontier fault varies (a sibling task). A rebuild function that recovers on its
 * own is legal and takes that choice away from the plan.
 */
object HostRebuild {

    /**
     * A namespace for [StableRefs], usually the host name. Two namespaces never collide: the
     * ref is derived from `"$namespace/$name"`, so `"peerA/fold"` and `"peerB/fold"` are
     * different cells and `"peerA/fold"` is the *same* cell in every generation, in every
     * run, on every machine.
     */
    fun refs(namespace: String): StableRefs = StableRefs(namespace)
}

/**
 * Deterministic `CellRef`s by name, stable across host rebuilds ([CHA1-17]).
 *
 * The refs are *derived*, not remembered: `ref("fold")` returns the same `CellRef` whether it
 * is the first call of generation 0 or the first call of generation 7, and whether or not the
 * same [StableRefs] instance is still alive. That is what makes a rebuild function safe to
 * close over nothing — the usual way a hand-rolled crash test goes wrong is a rebuild that
 * captures a ref from a `var` the crash already replaced.
 *
 * Derivation is `UUID.nameUUIDFromBytes` (MD5 of the name) over `"$namespace/$name"`. A
 * cryptographic-quality hash is not needed and would be misleading: this is a naming scheme,
 * not a security boundary, and the property being bought is *reproducibility*, including
 * across JVMs so that a replayed artifact addresses the same cells as the run that recorded
 * it ([CHA1-31]).
 *
 * @param instanceId the `CellRef.instanceId` every derived ref carries. Replicas of one
 *   logical cell differ only in this field (`CellRef(logicalId, replicaIndex)`), so a
 *   three-replica graph declares three [StableRefs] over the *same* namespace-and-name pairs
 *   with instance ids 0, 1, 2 — and a crash of replica 1 rebuilds `CellRef(logicalId, 1)`,
 *   which is what its peers' links and its own journal both name.
 */
class StableRefs(val namespace: String, val instanceId: Long = 0) {

    /** The deterministic ref for [name] within this namespace. */
    fun ref(name: String): CellRef =
        CellRef(UUID.nameUUIDFromBytes("$namespace/$name".toByteArray(Charsets.UTF_8)), instanceId)

    /**
     * [ref], and (re)declare it under [name] in [DstWorld.cells] so a fault can target it and
     * the trace digest names it stably. Safe to call in every generation: it re-points the
     * name rather than rejecting the second declaration.
     *
     * The declared name is [name], not `"$namespace/$name"` — the namespace scopes the *ref
     * derivation*, while the trace and `FaultTarget.Cell` want the short graph-level name.
     * Two hosts declaring the same cell name would therefore collide in the vocabulary while
     * remaining different cells; name them apart (`"fold-a"`, `"fold-b"`) when that matters.
     */
    fun declaredIn(world: DstWorld, name: String): CellRef = world.cells.redeclare(name, ref(name))
}

/**
 * What a graph knows about its own hosts that the rig cannot see: whether the target host is
 * mid-drain, and whether a wave is partially delivered right now ([CHA1-18]).
 *
 * ## Why this is graph-supplied, like [HostSlot]'s rebuild function and [LinkControl]
 *
 * [CrashMode.MID_WAVE] must fire "only while at least one wave is partially delivered". That
 * is a statement about kernel state the rig has no access to from `:testkit`:
 * `SimulationController.schedulers` is private with no size or liveness accessor,
 * `HostScheduler` publishes no `hasWork`, and the in-flight wave bookkeeping lives inside
 * `FanOutlet`/`ManagedHost` internals. A step hook can therefore observe *nothing* about
 * pending work — and the rule the epic gives for this exact situation is "do not guess
 * silently".
 *
 * So the graph says. A builder that fans one emission out to two arms knows when one arm has
 * run and the other has not; a builder that counts accepted-minus-applied ops knows when its
 * host is mid-drain. Both are two counters and a subtraction, and both are honest, where a
 * rig-side heuristic ("we are not at the last step, so presumably something is in flight")
 * would be a guess dressed as an observation.
 *
 * Kernel state that *is* published can be wrapped directly:
 * `AlignedCompositeCell.bufferedWaves` is precisely "waves held awaiting the shared frontier",
 * i.e. partially delivered — see [partialWaves].
 *
 * ## The contract
 *
 * Both methods are read **inside a step hook, between controller steps**, so they must be
 * pure observations: no submission, no stepping, no mutation of graph state. They are called
 * at most once per crash fault per run.
 */
interface CrashWitness {

    /**
     * Work this host has accepted and not yet applied — frames staged, tasks queued,
     * invocations in flight. `> 0` is "mid-drain" ([CrashMode.MID_DRAIN]).
     *
     * Default `0` is deliberately the *unwitnessed* answer, not a claim of quiescence: a
     * witness that implements only [partialWaves] gets MID_WAVE checked and says nothing
     * about the other two modes. [CrashFault] reads
     * [CrashWitness.witnessesPendingWork] to tell the two apart.
     */
    fun pendingWork(): Int = 0

    /**
     * Waves emitted whose delivery to their subscribers is incomplete — some arm of a
     * fan-out has run and another has not. `> 0` is "mid-wave" ([CrashMode.MID_WAVE]).
     */
    fun partialWaves(): Int = 0

    /** False when [pendingWork] is the interface default rather than something this graph observes. */
    val witnessesPendingWork: Boolean get() = true

    /** False when [partialWaves] is the interface default rather than something this graph observes. */
    val witnessesPartialWaves: Boolean get() = true

    companion object {

        /**
         * A witness over two counters the graph maintains: work accepted-minus-applied, and
         * waves emitted-minus-fully-delivered.
         */
        fun of(pendingWork: () -> Int, partialWaves: () -> Int): CrashWitness =
            object : CrashWitness {
                override fun pendingWork(): Int = pendingWork()
                override fun partialWaves(): Int = partialWaves()
            }

        /**
         * A witness of partial delivery only — the [CrashMode.MID_WAVE] precondition and
         * nothing else. [pendingWork] stays the interface default and is reported as
         * unwitnessed, so a MID_DRAIN fault against this host is honest about being the
         * caller's assertion rather than an observation.
         */
        fun partialWaves(count: () -> Int): CrashWitness =
            object : CrashWitness {
                override fun partialWaves(): Int = count()
                override val witnessesPendingWork: Boolean get() = false
            }

        /**
         * A witness of pending work only — the [CrashMode.MID_DRAIN] and
         * [CrashMode.AT_QUIESCENCE] preconditions. [partialWaves] stays unwitnessed, so
         * MID_WAVE against this host fails at install ([CHA1-18]) rather than firing on an
         * unchecked claim.
         */
        fun pendingWork(count: () -> Int): CrashWitness =
            object : CrashWitness {
                override fun pendingWork(): Int = count()
                override val witnessesPartialWaves: Boolean get() = false
            }
    }
}

/**
 * The graph's declared [CrashWitness]es, one per host name.
 *
 * **Why this is not a seventh seam on [DstWorld].** Same argument as [LinkControls], which
 * this deliberately mirrors: the rig core publishes six seams and a fault reaches the graph
 * through those and nothing else, on pain of `DstWorld` growing a special case per fault
 * class. A witness is not a frame transform, not a host rebuild, not a journal decoration and
 * not something a step hook can *reach* — it is a fact the builder knows and the world does
 * not — so the declaration lives beside the fault that reads it.
 *
 * That is a **finding about the seams, recorded rather than worked around**: if the kernel
 * ever publishes per-host pending-work and in-flight-wave counts (it publishes neither today —
 * `SimulationController.schedulers` is private and `HostScheduler` has no `hasWork`), this
 * registry becomes redundant and MID_WAVE's precondition becomes a rig-side observation.
 * Until then a graph that wants a witnessed crash declares one:
 *
 * ```kotlin
 * CrashWitnesses.declare(world, "durable", CrashWitness.partialWaves { emitted - fullyDelivered })
 * ```
 *
 * Entries are keyed by identity on a [WeakHashMap], so a world declared by one test cannot be
 * seen by another and nothing outlives the run that declared it.
 */
object CrashWitnesses {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, CrashWitness>>()

    /**
     * Declare [host]'s witness. The host must already be declared on [DstWorld.hosts] — a
     * witness for a name no host carries could never be read, and finding that out at
     * declaration time is the same fail-fast [CHA1-23] applies to fault targets.
     *
     * A rebuild function may call this in every generation; the later declaration replaces
     * the earlier one, because a witness that closes over generation-0 state would report on
     * a host the crash already discarded.
     */
    @Synchronized
    fun declare(world: DstWorld, host: String, witness: CrashWitness): CrashWitness {
        require(world.hosts.find(host) != null) {
            "cannot declare a crash witness for undeclared host \"$host\"; " +
                "known hosts: ${world.hosts.names().sorted()}"
        }
        byWorld.getOrPut(world) { linkedMapOf() }[host] = witness
        return witness
    }

    @Synchronized
    fun names(world: DstWorld): Set<String> = byWorld[world]?.keys?.toSet() ?: emptySet()

    @Synchronized
    fun find(world: DstWorld, host: String): CrashWitness? = byWorld[world]?.get(host)
}
