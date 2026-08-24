package civictech.testkit.dst

/**
 * One injectable adversary ([CHA1-10]).
 *
 * **Adding a fault class must never edit [DstWorld].** A `Fault` carries its own target, its
 * own activation window, and its own application logic; it reaches the graph only through the
 * six seams the world publishes ([DstWorld.edges], [DstWorld.hosts], [DstWorld.journals],
 * [DstWorld.steps], [DstWorld.trace], [DstWorld.deadLetters]). If a fault class you are
 * writing cannot be expressed through those seams, that is a finding about the seams — say so
 * rather than widening `DstWorld` with a special case for one fault.
 *
 * Lifecycle, in order, once per run:
 *  1. the graph is built (so every target name exists),
 *  2. every [targets] entry is validated ([CHA1-23]) — an unknown one aborts the run here,
 *  3. [install] is called, once,
 *  4. [onStep] is called before every controller step, with that step's index,
 *  5. the run drains; [DstWorld.trace] `fault(id, ...)` calls made anywhere in 3–4 are what
 *     the report counts ([CHA1-24]).
 *
 * Implementations are **values**: immutable configuration, serialisable field-for-field, with
 * any mutable per-run state created in [install]. That is what lets a [FaultPlan] be written
 * to a replay artifact and shrunk.
 */
sealed interface Fault {

    /**
     * Stable id, unique within a [FaultPlan] — the `faultTag` in the trace and the key of the
     * per-fault accounting in [DstReport]. Two faults sharing an id would merge their counts,
     * so [FaultPlan] rejects duplicates.
     */
    val id: String

    /** Every seam this fault names, for fail-fast validation ([CHA1-23]). */
    val targets: List<FaultTarget>
        get() = emptyList()

    /** One-line description for the report; defaults to the class name and id. */
    fun describe(): String = "${this::class.simpleName}($id)"

    /** Attach to the seams. Called once, after the graph is built and targets validated. */
    fun install(world: DstWorld) {}

    /**
     * Called before controller step [step] runs. Activation points are step indices, never
     * wall-clock ([CHA1-02]) — this method is the only activation clock a fault has.
     */
    fun onStep(world: DstWorld, step: Int) {}
}

/**
 * A fault assembled from lambdas rather than from a dedicated class.
 *
 * This is **not** a seventh fault class: the six of [CHA1-10] are named, configured, reported
 * value types and each gets its own file. `ScriptedFault` exists for the two cases a named
 * class would be wrong for — the rig's own self-tests, which must exercise the seams without
 * depending on any particular fault class, and a consumer's genuinely one-off adversary that
 * would never be reused. It is also the honest measure of whether the seams are sufficient:
 * anything the six classes need must be reachable from here first.
 *
 * `Fault` is a *sealed* interface, so it has one further consequence worth knowing: sealed
 * permits implementations only in this package **and this compilation unit**, which means a
 * consumer's test source set cannot implement `Fault` at all. `ScriptedFault` is how such a
 * consumer injects anything of its own.
 */
class ScriptedFault(
    override val id: String,
    override val targets: List<FaultTarget> = emptyList(),
    private val description: String = "ScriptedFault($id)",
    private val onInstall: (DstWorld) -> Unit = {},
    private val atStep: (DstWorld, Int) -> Unit = { _, _ -> },
) : Fault {
    override fun describe(): String = description
    override fun install(world: DstWorld) = onInstall(world)
    override fun onStep(world: DstWorld, step: Int) = atStep(world, step)
}

/**
 * A named seam a fault points at. The rig resolves it against what the graph actually
 * declared, before the first step ([CHA1-23], BS-12).
 */
sealed interface FaultTarget {

    val name: String

    /** Human word for the kind of seam, used in the naming error. */
    val kind: String

    /** Everything of this kind the graph under test declared. */
    fun knownIn(world: DstWorld): Set<String>

    data class Edge(override val name: String) : FaultTarget {
        override val kind: String get() = "edge"
        override fun knownIn(world: DstWorld): Set<String> = world.edges.names()
    }

    data class Host(override val name: String) : FaultTarget {
        override val kind: String get() = "host"
        override fun knownIn(world: DstWorld): Set<String> = world.hosts.names()
    }

    data class Cell(override val name: String) : FaultTarget {
        override val kind: String get() = "cell"
        override fun knownIn(world: DstWorld): Set<String> = world.cells.names()
    }

    data class Journal(override val name: String) : FaultTarget {
        override val kind: String get() = "journal"
        override fun knownIn(world: DstWorld): Set<String> = world.journals.names()
    }
}

/**
 * A fault named a seam the graph does not have ([CHA1-23], BS-12). Thrown *before the first
 * step*, so a run with a typo'd target never starts and can never be mistaken for a run in
 * which the fault silently applied to nothing.
 *
 * The message names the offending fault, the kind and name of the missing target, and the
 * whole known set — a naming error that does not print the alternatives just moves the search
 * to the reader.
 */
class UnknownFaultTargetException(
    val faultId: String,
    val target: FaultTarget,
    val known: Set<String>,
) : IllegalArgumentException(
    "fault \"$faultId\" targets unknown ${target.kind} \"${target.name}\"; " +
        "known ${target.kind}s: ${known.sorted()}",
)
