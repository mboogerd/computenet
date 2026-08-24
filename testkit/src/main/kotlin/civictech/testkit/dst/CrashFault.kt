package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * When, relative to the target host's own activity, the crash lands ([CHA1-17], [CHA1-18]).
 *
 * The three are not degrees of severity — they are three different bugs. A host that survives
 * a crash at quiescence proves only that its checkpoint restores; the interesting losses are
 * the ones a mid-drain or mid-wave crash exposes, where accepted-but-unapplied work and
 * half-delivered waves are precisely what a journal exists to protect and what a replay can
 * double-apply.
 *
 * Each mode is a **precondition on the activation step**, checked against the graph's
 * [CrashWitness] rather than assumed — see [CrashFault] for what happens when the precondition
 * does not hold, and [CrashWitness] for why the graph has to supply the observation.
 */
enum class CrashMode {

    /**
     * Crash while the host has accepted work it has not applied — `pendingWork() > 0`.
     *
     * The write-ahead journal's whole reason for existing: an invocation is journaled at
     * *acceptance*, before staging, so a crash here loses queued tasks whose frames are
     * already on disk. Recovery must reproduce exactly those, once each.
     */
    MID_DRAIN,

    /**
     * Crash while at least one wave is partially delivered — `partialWaves() > 0`.
     *
     * The glitch-freedom case: one arm of a fan-out has been applied and another has not, so a
     * naive recovery either loses the undelivered arm or re-fires the delivered one. **Fails
     * the run at setup if no such wave existed at the activation step** ([CHA1-18]), because a
     * MID_WAVE crash that landed at quiescence tested something nobody asked about and would
     * otherwise report `PASSED`.
     */
    MID_WAVE,

    /**
     * Crash with the host idle — `pendingWork() == 0` and `partialWaves() == 0`.
     *
     * The control the other two are read against: everything accepted has been applied, so a
     * correct recovery is state-restoring only, and any *divergence* here is a defect in
     * checkpoint/restore rather than in in-flight accounting.
     */
    AT_QUIESCENCE,
}

/**
 * A [CrashMode] precondition did not hold at the activation step ([CHA1-18]).
 *
 * Thrown from [Fault.onStep], which [DstRun] deliberately lets propagate: "an adversary that
 * cannot apply itself is a broken experiment, not a failed property". So this is **not** a
 * `FAILED` outcome and never a report — a run whose MID_WAVE crash had no partially delivered
 * wave to land in has no verdict to offer, and reporting `PASSED` for it would be the exact
 * false assurance [CHA1-18] exists to prevent.
 */
class CrashPreconditionUnmet(
    val faultId: String,
    val host: String,
    val mode: CrashMode,
    val step: Int,
    detail: String,
) : IllegalStateException(
    "crash fault \"$faultId\" on host \"$host\" is configured $mode at step $step, " +
        "but $detail. The crash did NOT fire and the run is aborted rather than reported: " +
        "a $mode crash that landed elsewhere tested something else. " +
        "Either move the activation step, or configure the mode the run actually reaches.",
)

/**
 * A crash fault needs an observation the graph never declared ([CHA1-18], [CrashWitness]).
 *
 * Thrown from [Fault.install] — before the first step, in the same spirit as
 * [UnknownFaultTargetException] — so a plan that could never have checked its own precondition
 * fails at setup instead of firing on an unchecked claim.
 */
class MissingCrashWitness(
    val faultId: String,
    val host: String,
    val mode: CrashMode,
    known: Set<String>,
) : IllegalStateException(
    "crash fault \"$faultId\" is configured $mode on host \"$host\", which requires the graph " +
        "to witness partial wave delivery, and no such witness is declared. " +
        "The graph builder declares one with " +
        "CrashWitnesses.declare(world, \"$host\", CrashWitness.partialWaves { ... }); " +
        "hosts with a witness: ${known.sorted()}. " +
        "The rig cannot observe in-flight waves itself — see CrashWitness.",
)

/**
 * Discard a host and rebuild it from its surviving journal ([CHA1-17], [CHA1-18]).
 *
 * At the activation step, in one step hook and in this order:
 *
 *  1. the [mode]'s precondition is checked against the graph's [CrashWitness] — an unmet
 *     precondition aborts the run ([CrashPreconditionUnmet]) rather than firing anyway;
 *  2. the target [host]'s scheduler is shut down and the host discarded: **in-flight tasks,
 *     queues and links go with it** ([HostSlot.crash]);
 *  3. the graph's own deterministic rebuild function runs, re-spawning the same cells at the
 *     **same `CellRef`s** (see [HostRebuild] — the rig does not derive those, and says so);
 *  4. if [journal] names one, the rebuilt host `recoverFrom`s it.
 *
 * ## The rebuild function is the graph's, not the fault's
 *
 * Epic §2.1 sketches this fault as `CrashFault(host, atStep, mode, rebuild)`, with the rebuild
 * function as a constructor parameter. It is a field of the **host slot** instead
 * ([HostSlots.declare]), and the fault names the host, for two reasons that both point the
 * same way: a `Fault` is contractually a *value* — "immutable configuration, serialisable
 * field-for-field" — which a rebuild lambda is not, and would make [FaultPlan] unwritable to a
 * replay artifact ([CHA1-31]); and the rig core already publishes the caller-supplied rebuild
 * slot as seam 2 precisely so that a crash fault consumes it rather than duplicating it. The
 * acceptance criterion — rebuild "via the caller-supplied deterministic rebuild function" — is
 * satisfied either way; only the declaration point differs.
 *
 * ## Recovery is a plan parameter, and its absence is the control
 *
 * [journal] `null` means **rebuild without recovering**: the [CHA1-63] diverging control, and
 * the only way to demonstrate that the journal is what saves the data rather than the rebuild.
 * A control run is `crash.copy(journal = null)`, which keeps the seed, the graph, the mode and
 * the activation step identical — the difference between the two runs is exactly one field.
 *
 * ## What "discarded" does and does not cover
 *
 * [HostSlot.crash] shuts the old scheduler down, which clears its queue, and abandons both the
 * scheduler and the host. Two residuals are worth knowing rather than discovering:
 *
 *  - A task that had already **suspended** on the old scheduler (🟣 hosts) is not cleared by
 *    `shutdown()`; its parked resumption can still run after the crash. Every self-test here
 *    uses `HostColor.BLOCKING`, where tasks never actually suspend, so the case does not arise
 *    in them — a consumer crashing a suspending host should treat it as a known limit of the
 *    seam, not as graph behaviour.
 *  - The abandoned scheduler stays registered with `SimulationController` forever
 *    (`schedulers` is private and has no removal API — probed, not assumed). It is empty, so
 *    it never wins a step and cannot perturb the interleaving; what it costs is a slot in the
 *    controller's per-step `filter`.
 */
data class CrashFault(
    override val id: String,
    val host: String,
    val atStep: Int,
    val mode: CrashMode,
    /** The declared journal the rebuilt host recovers from; `null` is the no-recovery control. */
    val journal: String? = null,
) : Fault {

    init {
        require(atStep >= 0) { "a crash activates at a step index, got atStep=$atStep" }
    }

    /** Guards against a re-fire if a consumer installs the same fault twice. */
    private var crashed = false

    /** Resolved at install so a MID_WAVE plan with no witness fails before the run ([CHA1-18]). */
    private var witness: CrashWitness? = null

    override val targets: List<FaultTarget>
        get() = listOf(FaultTarget.Host(host)) + (journal?.let { listOf(FaultTarget.Journal(it)) } ?: emptyList())

    override fun describe(): String {
        val recovery = journal?.let { "recovers from journal \"$it\"" }
            ?: "does NOT recover — the no-journal control ([CHA1-63])"
        return "crash(host=$host, $mode, step=$atStep): in-flight tasks, queues and links " +
            "discarded, host rebuilt at the same CellRefs by the graph's rebuild function, " +
            "then $recovery${unwitnessedSuffix()}"
    }

    override fun install(world: DstWorld) {
        val declared = CrashWitnesses.find(world, host)
        if (mode == CrashMode.MID_WAVE && declared?.witnessesPartialWaves != true) {
            throw MissingCrashWitness(id, host, mode, CrashWitnesses.names(world))
        }
        witness = declared
        // Resolved now so a plan naming a journal the graph never declared, or a host with no
        // slot, fails at setup rather than several hundred steps in, having already been
        // reported as applied.
        world.hosts.require(host)
        journal?.let { world.journals.view(it) }
    }

    override fun onStep(world: DstWorld, step: Int) {
        if (crashed || step != atStep) return
        // Re-read the witness: a rebuild function may re-declare it per generation, and a
        // witness closed over generation-0 state would report on a host already discarded.
        val current = CrashWitnesses.find(world, host) ?: witness
        checkPrecondition(current, step)
        crashed = true
        val slot = world.hosts.require(host)
        val rebuilt = slot.crash(id) // traces + counts this fault firing ([CHA1-24])
        journal?.let {
            rebuilt.recoverFrom(world.journals.view(it))
            // A plain trace event, not a second `fault(...)`: recovery is part of one firing,
            // and counting it twice would make an inert-fault check read a crash as two.
            world.trace.emit(host = host, port = "recoverFrom($it)")
        }
    }

    private fun checkPrecondition(witness: CrashWitness?, step: Int) {
        val pending = witness?.takeIf { it.witnessesPendingWork }?.pendingWork()
        val partial = witness?.takeIf { it.witnessesPartialWaves }?.partialWaves()
        when (mode) {
            CrashMode.MID_DRAIN ->
                if (pending != null && pending == 0) {
                    unmet(step, "the graph's witness reports no pending work on that host (pendingWork() == 0)")
                }

            CrashMode.MID_WAVE ->
                if (partial == null || partial == 0) {
                    unmet(step, "the graph's witness reports no partially delivered wave (partialWaves() == 0)")
                }

            CrashMode.AT_QUIESCENCE -> {
                if (pending != null && pending > 0) {
                    unmet(step, "the graph's witness reports $pending unit(s) of pending work on that host")
                }
                if (partial != null && partial > 0) {
                    unmet(step, "the graph's witness reports $partial partially delivered wave(s)")
                }
            }
        }
    }

    private fun unmet(step: Int, detail: String): Nothing =
        throw CrashPreconditionUnmet(id, host, mode, step, detail)

    /**
     * Names, in the report, every part of this fault's mode that nothing checked. A witness is
     * mandatory only for [CrashMode.MID_WAVE] ([CHA1-18]); for the other two an undeclared
     * observation leaves the mode as the caller's assertion, which the report says out loud
     * rather than passing off as an observation.
     */
    private fun unwitnessedSuffix(): String {
        val w = witness
        val unchecked = when (mode) {
            CrashMode.MID_DRAIN -> w?.witnessesPendingWork != true
            CrashMode.MID_WAVE -> false
            CrashMode.AT_QUIESCENCE -> w?.witnessesPendingWork != true && w?.witnessesPartialWaves != true
        }
        return if (unchecked) " [unwitnessed: $mode is the caller's assertion, not an observation]" else ""
    }

    companion object {

        /** The `kind` a [CrashFault] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "dst-crash"

        /**
         * This class's [FaultCodec], registered the moment the class is loaded ([CHA1-31]).
         *
         * ## Why it lives in the companion object
         *
         * A companion object's property initialisers run in the *outer* class's static
         * initialiser, so constructing any `CrashFault` — or reading `CrashFault.CODEC` — has
         * already registered this codec. Naming `CrashFault.KIND` does **not**: it is a
         * `const val`, which the compiler inlines into the referencing file's constant pool as
         * a string literal, so it loads nothing (verified in bytecode; it is what made
         * `FaultCodecRoundTripTest.everyLandedFaultClassRegistersACodec_CHA1_31` order-dependent).
         * Code that must force registration reads `CODEC`. That is what makes the **encode**
         * path unconditional:
         * `FaultCodecs.encode(fault)` cannot be reached without a `CrashFault` instance, and an
         * instance cannot exist without the class being loaded.
         *
         * The **decode** path has the load requirement every by-name registry here has, and it
         * is the same one [GraphRegistry] and [CheckRegistry] carry: a JVM that has never
         * touched `CrashFault` has not registered `"dst-crash"`, so reading an artifact naming
         * it fails with [FaultCodecs]' own message listing the registered kinds. A replay
         * harness already has to load the code that registers the artifact's graph and check;
         * loading its fault classes is the same obligation, not a new one.
         *
         * Public so a suite that had to [FaultCodecs.unregister] this kind can put it back —
         * re-registration is by value, and the static initialiser will not run twice.
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is CrashFault },
            encode = { fault ->
                val crash = fault as CrashFault
                buildJsonObject {
                    put("host", crash.host)
                    put("atStep", crash.atStep)
                    put("mode", crash.mode.name)
                    put("journal", JsonPrimitive(crash.journal))
                }
            },
            decode = { id, params -> decodeFrom(id, params) },
        )

        /**
         * Rebuild from a record's parameters. Flat primitives only, and deliberately so:
         * [ReductionStrategies.numericParamToward] reaches a parameter by *name* on the
         * record's top-level `params`, so a nested `{"window": {...}}` would be invisible to
         * the shrinker's semantics-aware strategy. `atStep` is the numeric knob here.
         */
        private fun decodeFrom(id: String, params: JsonObject): CrashFault = CrashFault(
            id = id,
            host = params.getValue("host").jsonPrimitive.content,
            atStep = params.getValue("atStep").jsonPrimitive.int,
            mode = CrashMode.valueOf(params.getValue("mode").jsonPrimitive.content),
            journal = params["journal"]?.jsonPrimitive?.contentOrNull,
        )

        /** Crash while the host has accepted-but-unapplied work. See [CrashMode.MID_DRAIN]. */
        fun midDrain(id: String, host: String, atStep: Int, journal: String? = null): CrashFault =
            CrashFault(id, host, atStep, CrashMode.MID_DRAIN, journal)

        /**
         * Crash with a wave partially delivered. See [CrashMode.MID_WAVE]; the graph must have
         * declared a [CrashWitness] that witnesses partial delivery, or install fails.
         */
        fun midWave(id: String, host: String, atStep: Int, journal: String? = null): CrashFault =
            CrashFault(id, host, atStep, CrashMode.MID_WAVE, journal)

        /** Crash with the host idle. See [CrashMode.AT_QUIESCENCE]. */
        fun atQuiescence(id: String, host: String, atStep: Int, journal: String? = null): CrashFault =
            CrashFault(id, host, atStep, CrashMode.AT_QUIESCENCE, journal)
    }
}
