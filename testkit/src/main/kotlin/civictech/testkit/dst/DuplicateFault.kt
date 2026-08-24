package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which plane a [DuplicateFault] re-delivers on ([CHA1-16]).
 *
 * Both exist because a graph may have only one of them: an unbridged, single-process graph has
 * no frames at all, and a duplicate fault that only knew about frames would report itself
 * applied and fire zero times on half the graphs in this repository.
 */
enum class DuplicatePlane {

    /**
     * Re-deliver the encoded frame, on a named edge of [DstWorld.edges]. The copy is a distinct
     * `ByteArray` with identical bytes — see [FrameInterposers.duplicating].
     */
    FRAMES,

    /**
     * Re-deliver the invocation, at a named [InvocationPoint] the graph declared, *before* it
     * is encoded. See [InvocationInterposers.duplicating].
     */
    INVOCATIONS,
}

/**
 * Re-deliver a byte-identical copy of each message on one target, [copies] times, with
 * probability [probability] per message, for a window of controller steps ([CHA1-16]).
 *
 * ## What "byte-identical" means on each plane
 *
 * On [DuplicatePlane.FRAMES] it is literal: the duplicate is `frame.copyOf()`, so the receiver
 * decodes the same bytes twice from two distinct arrays. On [DuplicatePlane.INVOCATIONS] the
 * message is a `HostedPortInvocation`, which the kernel treats as immutable, so the duplicate
 * is the same instance — nothing is re-stamped, re-timestamped or re-encoded between the two
 * deliveries, which is the property under test.
 *
 * The original is always delivered first and the copies immediately after it, so this fault
 * changes *how many times* a message arrives and never *which message arrives first*. Reordering
 * is [ReorderFault]'s, and keeping the two separable is what lets a suite attribute a failure to
 * one of them.
 *
 * ## What a duplicate is a control for
 *
 * A CRDT, a wave frontier and a tag-set fold are all supposed to be idempotent under
 * redelivery, and "supposed to be" is the part a rig exists to check ([CHA1-62]/[CHA1-63], BS-6):
 * the converging run is the real graph absorbing every duplicate, and the diverging control is a
 * consumer that counts instead of folding, which double-counts and is caught. A control that
 * cannot be made to double-count proves nothing about the graph that did not.
 *
 * @property target an edge name when [plane] is [DuplicatePlane.FRAMES], an invocation-point
 *   name when it is [DuplicatePlane.INVOCATIONS].
 * @property copies extra deliveries per selected message: `copies = 1` means it arrives twice.
 * @property probability the per-message chance of being duplicated at all, in `(0, 1]`, drawn
 *   from the run seed. `1.0` duplicates every message in the window.
 * @property activation the step window; messages outside it are untouched and consume no
 *   randomness, so narrowing a window does not re-roll the decisions inside it.
 */
data class DuplicateFault(
    override val id: String,
    val target: String,
    val plane: DuplicatePlane = DuplicatePlane.FRAMES,
    val copies: Int = 1,
    val probability: Double = 1.0,
    val activation: StepWindow = StepWindow.ALWAYS,
) : Fault {

    init {
        require(copies >= 1) { "a duplicate makes at least one extra copy, got copies=$copies" }
        require(probability > 0.0 && probability <= 1.0) {
            "probability is a per-message chance in (0, 1], got $probability"
        }
    }

    private var installed: AutoCloseable? = null

    /**
     * Only a [DuplicatePlane.FRAMES] fault has a validatable target: an edge is declared on
     * [DstWorld.edges] and [FaultTarget.Edge] resolves against it ([CHA1-23]). An invocation
     * point is declared in [InvocationPoints], which is not one of the six seams
     * [FaultTarget] can name, so it is resolved loudly at [install] instead — the same
     * arrangement [PartitionFault] uses for a park control, and for the same reason.
     */
    override val targets: List<FaultTarget>
        get() = when (plane) {
            DuplicatePlane.FRAMES -> listOf(FaultTarget.Edge(target))
            DuplicatePlane.INVOCATIONS -> emptyList()
        }

    override fun describe(): String {
        val chance = if (probability >= 1.0) "every message" else "each message with p=$probability"
        val what = when (plane) {
            DuplicatePlane.FRAMES -> "frames on edge=$target"
            DuplicatePlane.INVOCATIONS -> "invocations at point=$target"
        }
        return "duplicate($what, $activation, $plane): $chance is re-delivered $copies more " +
            "time(s), byte-identical, original first"
    }

    override fun install(world: DstWorld) {
        val rng = world.rng("duplicate:$id")
        installed = when (plane) {
            DuplicatePlane.FRAMES -> world.edges.intercept(
                target,
                FrameInterposers.duplicating(
                    copies = copies,
                    probability = probability,
                    rng = rng,
                    window = activation,
                    onDuplicate = { world.trace.fault(id, port = target) },
                ),
            )

            DuplicatePlane.INVOCATIONS -> InvocationPoints.require(world, target).intercept(
                InvocationInterposers.duplicating(
                    copies = copies,
                    probability = probability,
                    rng = rng,
                    window = activation,
                    onDuplicate = { world.trace.fault(id, port = target) },
                ),
            )
        }
    }

    override fun onStep(world: DstWorld, step: Int) {
        // The interposer gates itself on the window per message, so opening needs no hook.
        // Deregistering at the healing step is safe here — unlike ReorderFault, this fault
        // holds nothing back, so removing it can lose nothing — and it is what makes
        // `Edge.intercepted` and the cost of the chain honest once the fault is done.
        if (activation.healedAt(step)) {
            installed?.close()
            installed = null
        }
    }

    companion object {

        /** Duplicate every frame on [edge] for `[from, until)`. */
        fun frames(
            id: String,
            edge: String,
            copies: Int = 1,
            probability: Double = 1.0,
            from: Int = 0,
            until: Int = Int.MAX_VALUE,
        ): DuplicateFault =
            DuplicateFault(id, edge, DuplicatePlane.FRAMES, copies, probability, StepWindow(from, until))

        /** Duplicate every invocation at the declared point [point] for `[from, until)`. */
        fun invocations(
            id: String,
            point: String,
            copies: Int = 1,
            probability: Double = 1.0,
            from: Int = 0,
            until: Int = Int.MAX_VALUE,
        ): DuplicateFault =
            DuplicateFault(id, point, DuplicatePlane.INVOCATIONS, copies, probability, StepWindow(from, until))

        /**
         * The artifact codec ([CHA1-31]), registered when this class is first loaded — see
         * [ReorderFault.CODEC] for why it lives here rather than in a central list, and for
         * the one residual limit (a JVM that only ever *decodes* this kind must touch the class
         * first).
         *
         * `copies`, `probability`, `from` and `until` are flat JSON primitives so that
         * `ReductionStrategies.numericParamToward` can reduce them — `probability` toward 0 and
         * `copies` toward 1 are the two reductions a shrinker will actually want here, and both
         * are non-integral/integral cases of the same strategy ([CHA1-38]).
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = "duplicate",
            owns = { it is DuplicateFault },
            encode = { fault ->
                val f = fault as DuplicateFault
                JsonObject(
                    mapOf(
                        "target" to JsonPrimitive(f.target),
                        "plane" to JsonPrimitive(f.plane.name),
                        "copies" to JsonPrimitive(f.copies),
                        "probability" to JsonPrimitive(f.probability),
                        "from" to JsonPrimitive(f.activation.from),
                        "until" to JsonPrimitive(f.activation.until),
                    ),
                )
            },
            decode = { id, params ->
                DuplicateFault(
                    id = id,
                    target = params.string("target"),
                    plane = DuplicatePlane.valueOf(params.string("plane")),
                    copies = params.getValue("copies").jsonPrimitive.int,
                    probability = params.getValue("probability").jsonPrimitive.double,
                    activation = StepWindow(
                        params.getValue("from").jsonPrimitive.int,
                        params.getValue("until").jsonPrimitive.int,
                    ),
                )
            },
        )
    }
}
