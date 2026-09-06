package civictech.testkit.dst

import civictech.cell.host.SimulationController

/** The property a run is trying to break. Throws (any [Throwable]) to fail the run. */
fun interface DstCheck {
    fun verify(world: DstWorld)

    companion object {
        /** No property: the run is only being observed (determinism self-checks, baselines). */
        val none: DstCheck = DstCheck { }
    }
}

/**
 * One adversarial execution: a [graph], a [plan], a step [budget], and the [check] the run is
 * trying to break (epic §2.2's `DstRun(seed, plan, budget)`).
 *
 * **The seed is not a constructor parameter** — it is `plan.seed`, and [seed] reads it. One
 * run has one seed, in one place, feeding the controller's cross-host pick, every fault's
 * randomness and any workload randomness a graph builder asks [DstWorld.rng] for
 * ([CHA1-30]). A shrinker therefore cannot vary the seed by accident: there is no second
 * field to disagree with ([CHA1-35]).
 *
 * The rig drives [SimulationController.step] itself, in [execute]'s own loop, so that it can
 * count steps and fire faults at exact activation points ([CHA1-02]) and so that budget
 * exhaustion is an *outcome* rather than the exception `runToIdle` throws ([CHA1-03]). That
 * loop is otherwise `runToIdle`'s, step for step — [DstBaseline] exists to keep that claim
 * honest and falsifiable.
 */
class DstRun(
    val graph: GraphSpec,
    val plan: FaultPlan,
    val budget: Int = SimulationController.DEFAULT_BUDGET,
    val check: DstCheck = DstCheck.none,
) {
    /** The one run seed ([CHA1-30]). */
    val seed: Long get() = plan.seed

    /**
     * Build the graph, validate every fault target, install the plan, drain under [budget],
     * then run [check].
     *
     * Order matters and is part of the contract:
     *  - the graph is built **first**, so every target name exists to be validated against;
     *  - targets are validated **before** anything is installed, so a run with a typo'd target
     *    fails without having half-applied a plan ([CHA1-23], BS-12) — as an exception, not an
     *    outcome, because a run that never started has no verdict to report;
     *  - the check runs **only** on a quiesced run: `BUDGET_EXHAUSTED` claims nothing about the
     *    property ([CHA1-03]).
     *
     * An exception thrown by a fault's [Fault.install] or [Fault.onStep] propagates: an
     * adversary that cannot apply itself is a broken experiment, not a failed property.
     */
    fun execute(): DstReport {
        val world = DstWorld(plan.seed)
        graph.builder.build(world)

        plan.faults.forEach { fault ->
            fault.targets.forEach { target ->
                val known = target.knownIn(world)
                if (target.name !in known) throw UnknownFaultTargetException(fault.id, target, known)
            }
        }

        plan.faults.forEach { fault ->
            world.declareFault(fault.id)
            fault.install(world)
            world.steps.onStep { w, step -> fault.onStep(w, step) }
        }

        var steps = 0
        var quiesced = false
        while (steps < budget) {
            world.beginStep(steps)
            if (!world.controller.step()) {
                quiesced = true
                break
            }
            steps++
        }
        world.endRun()

        val failure = if (!quiesced) {
            null
        } else {
            runCatching { check.verify(world) }.exceptionOrNull()
                ?.let { FailingCheck(it.message ?: it::class.java.name, steps, it) }
        }

        val outcome = when {
            !quiesced -> DstOutcome.BUDGET_EXHAUSTED
            failure != null -> DstOutcome.FAILED
            else -> DstOutcome.PASSED
        }

        val activity = world.faultActivity()
        return DstReport(
            outcome = outcome,
            seed = plan.seed,
            graphId = graph.id,
            budget = budget,
            steps = steps,
            plan = plan,
            appliedFaults = plan.faults.map { fault ->
                val seen = activity[fault.id] ?: FaultActivity(0, emptyList())
                AppliedFault(fault.id, fault.describe(), seen.fired, seen.activationSteps)
            },
            traceDigest = world.traceDigest(),
            failingCheck = failure,
            deadLetters = world.deadLetters,
            trace = world.traceEvents(),
        )
    }

    /**
     * [CHA1-33]: run this exact `(seed, plan)` [runs] times and assert the trace digests are
     * equal, naming the divergence if they are not. Returns the first report.
     *
     * Available to every consumer suite, and cheap enough to be the first thing a new rig-driven
     * test asserts: a suite whose runs are not reproducible cannot support any of the claims the
     * rest of the rig makes.
     *
     * ## One graph shape is outside this guarantee: a peering that RE-OPENS mid-run
     *
     * **A graph whose run re-opens a `Peering.Loopback` — `heal()` after `partition()`, which on
     * the churn mesh is every `DepartureMode.PARTITION_SUSPEND` departure that is later rejoined
     * or healed — is not trace-reproducible, and this assertion will fail on it.** It is not a
     * defect in the plan, the seed fan-out or this rig's drive loop; it is unseeded entropy the
     * kernel's reconnect path reads, and `:testkit` cannot remove it. Full mechanism, the
     * measurement that established it, and the consequence for recorded seeds:
     * `doc/dst-rig.md` §4 "A peering that re-opens mid-run is outside the determinism contract"
     * (computenet-l0gd).
     *
     * In one line: `Peering.Loopback.heal()` re-runs `Peering.announceTo`'s catch-up sweep, which
     * iterates `LocationRegistry.localRefs()` and `localLinks()` — `ConcurrentHashMap` views keyed
     * by `CellRef`s and `TopologyLink` ids that the kernel mints with `UUID.randomUUID()`. The
     * announcement ORDER is therefore a fresh draw per run, and reordered announcements reorder
     * the gossip relinking they cause, which moves later trace events by a few controller steps.
     *
     * A consumer whose graph can draw that shape should assert what it can honestly assert — the
     * per-seed OUTCOME by repeated re-run of one recorded seed, which IS stable — rather than a
     * digest pin. `DstBaseline`/`DstReplay` inherit the same limitation for the same reason.
     */
    fun assertDeterministic(runs: Int = 2): DstReport {
        val reports = mutableListOf<DstReport>()
        TraceDigests.assertSameDigest(runs, "run(seed=$seed, graph=${graph.id})") { _ ->
            execute().also { reports += it }.trace
        }
        return reports.first()
    }
}

/** What a bare-controller drive of the same graph produced. See [DstBaseline]. */
data class BaselineRun(val steps: Int, val trace: List<TraceEvent>, val digest: TraceDigest)

/**
 * The fault-free control for [CHA1-04] / BS-2: the same graph, the same seed, driven by the
 * kernel controller with no plan, no faults and no step hooks.
 *
 * Two drives are offered, and the difference between them is the whole point:
 *
 *  - [run] drives `controller.step()` in the same loop `SimulationController.runToIdle` runs,
 *    keeping the step counter the trace stamps its events with. It is what a digest can be
 *    compared against.
 *  - [runToIdleSteps] drives `SimulationController.runToIdle` **literally** and returns only
 *    its step count.
 *
 * The reason for both: a trace event's step index can only come from a counter the driver
 * keeps, and `runToIdle` keeps its own privately — so a digest cannot be collected from the
 * literal kernel call without a kernel change, which this epic excludes. The limitation is
 * therefore stated rather than papered over: **digest equality is asserted against the
 * re-implemented loop ([run]), and that loop's equivalence to the kernel's is pinned
 * separately by step count ([runToIdleSteps])**. A drift in the rig's loop — a dropped step,
 * an off-by-one, a hook that injects work — moves the count and fails that pin. What the pair
 * cannot detect is a divergence that leaves the step *count* identical while changing which
 * host ran when; nothing short of a kernel-side trace hook can, and no such hook exists.
 */
object DstBaseline {

    /** Observed bare-controller drive: digest-comparable, loop re-implemented from `runToIdle`. */
    fun run(graph: GraphSpec, seed: Long, budget: Int = SimulationController.DEFAULT_BUDGET): BaselineRun {
        val world = DstWorld(seed)
        graph.builder.build(world)
        var steps = 0
        while (steps < budget) {
            world.beginStep(steps)
            if (!world.controller.step()) break
            steps++
        }
        world.endRun()
        return BaselineRun(steps, world.traceEvents(), world.traceDigest())
    }

    /** Literal `SimulationController.runToIdle` drive of the same graph; step count only. */
    fun runToIdleSteps(graph: GraphSpec, seed: Long, budget: Int = SimulationController.DEFAULT_BUDGET): Int {
        val world = DstWorld(seed)
        graph.builder.build(world)
        return world.controller.runToIdle(budget)
    }
}
