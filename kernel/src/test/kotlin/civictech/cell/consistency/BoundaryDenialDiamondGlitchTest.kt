package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.host.CellError
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.DisclosurePolicy
import civictech.cell.membrane.ProjectionId
import civictech.cell.membrane.ProjectionRegistry
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * BS-13 / `[SEC1-28]` (`computenet-usd.3.2`, feature `computenet-usd.3`): the
 * reconvergent (diamond) graph where **one arm crosses a disclosure boundary
 * that denies mid-wave and the other arm does not** — the epic's §9 open
 * question 1 shape, two disclosure regimes meeting at one reconvergence point.
 *
 * ```
 *   source ──> "B" arm (unprojected) ──────────────────────> GlitchFreeCell ──> ReconvergencePoint
 *          └─> membrane: Project(redact, suppress on n%3) ──┘        (the named observation point)
 * ```
 *
 * Arm B is disclosed in full; arm M crosses a membrane whose
 * [DisclosurePolicy.Project] **redacts** every delta it lets through
 * (`M:secret-n` -> `M:n`) and **suppresses** the deltas of every third wave
 * outright. So each released wave is a meeting of two regimes, and every third
 * wave is a mid-wave denial on one of them.
 *
 * ## What this file asserts, and — deliberately — what it does not
 *
 * `[SEC1-28]` says the reconvergence point "never observes a mixed
 * pre/post-denial combination". The landed reconvergence point forms **no
 * combination**: [GlitchFreeCell] is a grouping pass-through that replays a
 * completed wave's buffered invocations one at a time (`WaveFrontier.flushReady`
 * — the structural fact [civictech.cell.data.op.CoalescingCombineCell]'s KDoc
 * records as the reason a coalescing operator cannot compose the frontier
 * policy). So the requirement's literal subject has no referent here, and this
 * file asserts the strongest properties that **are** genuinely checkable at the
 * observation point:
 *
 * 1. **wave-contiguity** — the arrival log partitions into exactly one
 *    contiguous run per wave, in strictly increasing counter order: no wave's
 *    contributions are ever split by another wave's;
 * 2. **no stale substitution** — a wave's run carries contributions *of that
 *    wave only*. On a denied wave it carries the surviving arm alone; the
 *    denied arm's pre-denial value is never paired into it;
 * 3. **the flagged RE-SCOPE release, exactly once per denied wave** — one
 *    [GlitchViolation] naming `DEAD_LETTERED` and that wave's [Timestamp], and
 *    no `SupervisionPolicy` restart (`[SEC1-29]`, BS-14);
 * 4. **the regimes really differ** — no unredacted value is ever observable at
 *    the reconvergence point, so arm M is genuinely under another regime than
 *    arm B rather than nominally so.
 *
 * The `control - ...` test is what keeps (1)/(2) from being vacuous: the same
 * seeds, the same scheduler, the same denials, without the frontier — and the
 * unprotected observation point does tear.
 *
 * ## The residual, measured rather than glossed (see `concord/corpus/DISPUTES.md`)
 *
 * `mixedPairs` below **measures** the thing `[SEC1-28]` actually names, one hop
 * out: any consumer that folds the two arms into a single value (the fold every
 * combining consumer performs, staged here inside [ReconvergencePoint]) holds a
 * pre/post-denial mixture — at a denied wave it retains the denied arm's
 * previous value beside the surviving arm's fresh one, and *within every wave*
 * it passes through a torn intermediate between the group's two arrivals.
 * Neither is refused, flagged or tagged by anything in the landed model.
 *
 * That measurement is asserted here so it cannot rot silently, and
 * `[SEC1-28]` is filed UNVERIFIED in `concord/corpus/DISPUTES.md` with the
 * reasoning. If an assertion in `the fold one hop past the join does form the
 * mixed combination...` ever fails, something started refusing the mixture and
 * the dispute must be revisited — it is a pin on a known gap, never a claim
 * that the gap is desirable.
 *
 * Determinism: one [SimWorld]-owned `SimulationController` drives three hosts,
 * so the two arms are scheduled independently and seed-driven partial draining
 * supplies the reorder; quiescence is asserted under [SimWorld]'s step budget,
 * never a wall-clock sleep. Every assertion is a semantic outcome (which waves
 * released carrying which contributions), never a scheduling order. Seeds are
 * swept `0 until 40` and are never rebased on a failure.
 */
class BoundaryDenialDiamondGlitchTest {

    /** Every third wave's delta is suppressed at the boundary. */
    private fun isDenied(n: Int) = n % 3 == 0

    /**
     * **12 on purpose: the LAST wave must be a denied one.** Measured by
     * mutation (deleting the `stallDeniedEdges` call from
     * [CompositeCell]'s disclosure-suppression branch): every denied wave but
     * the last still released, because a *later* wave's monotone watermark
     * retroactively completes an earlier one on that edge
     * ([WaveFrontier.advanceWatermark], the effect `GlitchFreeStallTest` names).
     * Only wave 12 — denied with nothing after it — actually went missing, and
     * that single group is what turned the mutation red. Change `waves` to a
     * value `isDenied` does not select and this file stops testing the
     * classification at all.
     */
    private val waves = 12
    private val seeds = 0L until 40L

    /** One arrival at the reconvergence point: which arm, which wave, verbatim. */
    private data class Obs(val label: String, val n: Int, val raw: String, val ts: Timestamp)

    /**
     * The last-value-per-arm fold, sampled after every arrival — what a
     * *combining* consumer of the reconvergence point holds at that instant.
     */
    private data class Fold(val ts: Timestamp, val b: Int?, val m: Int?) {
        val mixed: Boolean get() = b != null && m != null && b != m
    }

    // ------------------------------------------------------------------
    // Graph pieces
    // ------------------------------------------------------------------

    /** Mints a fresh wave per emission, fanning to both arms. */
    private class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())

        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    /** The unprojected arm: reactive `Int -> "B:n"`, preserving the incoming wave. */
    private class PlainArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("B:$n") }
        }
    }

    /**
     * The organelle behind the projecting membrane. It emits the **unredacted**
     * value; what leaves the membrane is whatever the disclosure filter makes
     * of it, which is how "two regimes" is more than a label here.
     */
    private class SecretArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("M:secret-$n") }
        }
    }

    /** Seam 3 `PORT_API` outbound: the arm's emissions cross a projecting boundary. */
    private class ProjectingArmMembrane(
        projection: ProjectionId,
        val organelle: SecretArm = SecretArm(),
    ) : CompositeCell() {
        val exposedInlet = flatten("exposedInlet", "inlet", organelle.inlet)
        val exposedOutlet = mediateOutlet(
            "exposedOutlet",
            "outlet",
            organelle.outlet,
            BoundaryPolicy(disclosure = DisclosurePolicy.Project(projection)),
        )
    }

    /**
     * **The named observation point.** Everything `[SEC1-28]` speaks about is
     * read here: the per-arrival log, and — separately — the last-value-per-arm
     * [Fold] a combining consumer would hold after each arrival.
     */
    private class ReconvergencePoint(
        private val log: MutableList<Obs>,
        private val folds: MutableList<Fold>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        private val last = mutableMapOf<String, Int>()

        init {
            inlet.onEach { s ->
                val ts = CurrentContext.get()!!.timestamp
                val label = s.substringBefore(':')
                val n = s.substringAfter(':').removePrefix("secret-").toInt()
                log += Obs(label, n, s, ts)
                last[label] = n
                folds += Fold(ts, last["B"], last["M"])
            }
        }
    }

    private interface ArmInlet {
        val inlet: Use<Propagate<Int>>
    }

    private interface MembraneInlet {
        val exposedInlet: Use<Propagate<Int>>
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    private class Run(
        val log: List<Obs>,
        val folds: List<Fold>,
        val violations: List<CellError>,
        val denialCount: Long,
        val restarts: Long,
    ) {
        /** Maximal contiguous runs of one wave, in arrival order. */
        val groups: List<Pair<Timestamp, List<String>>> = buildList {
            log.forEach { o ->
                val tail = lastOrNull()
                if (tail != null && tail.first == o.ts) {
                    set(size - 1, tail.first to (tail.second + "${o.label}:${o.n}"))
                } else {
                    add(o.ts to listOf("${o.label}:${o.n}"))
                }
            }
        }
    }

    private fun runDiamond(seed: Long, projection: ProjectionId, protected: Boolean): Run {
        val world = SimWorld(seed = seed)
        // Each arm on its own host: the two arms are scheduled independently,
        // which is the reorder an unprotected reconvergence point tears on.
        val hostB = ManagedHost(scheduler = world.controller.scheduler(), registry = world.registry)
        val hostM = ManagedHost(scheduler = world.controller.scheduler(), registry = world.registry)

        val log = mutableListOf<Obs>()
        val folds = mutableListOf<Fold>()
        val violations = mutableListOf<CellError>()

        val source = SourceCell()
        val plainArm = PlainArm()
        val membrane = ProjectingArmMembrane(projection)
        val observer = ReconvergencePoint(log, folds)
        val join = GlitchFreeCell(
            @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>),
            mode = GlitchFreeCell.WaveMode.WAIT,
        )

        world.host.managementInlet.call.spawn(source)
        hostB.managementInlet.call.spawn(plainArm)
        hostM.managementInlet.call.spawn(membrane)
        world.host.managementInlet.call.spawn(observer)
        if (protected) world.host.managementInlet.call.spawn(join)
        world.runToIdle()

        // The fork: both arms are fed over their own host's queue, so a wave's
        // two contributions are produced on independently scheduled tasks.
        source.outlet.subscribe(Use.fixed(hostB.lookup<ArmInlet>(plainArm.ref)!!.inlet.call, PortRef.generate()))
        source.outlet.subscribe(
            Use.fixed(hostM.lookup<MembraneInlet>(membrane.ref)!!.exposedInlet.call, PortRef.generate()),
        )

        if (protected) {
            join.errorOutlet.subscribe(
                Use.fixed(
                    object : Propagate<CellError> {
                        override fun propagate(value: CellError) {
                            violations += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            @Suppress("UNCHECKED_CAST")
            val target = join.inlet as LinkFrom<Propagate<String>>
            (plainArm.outlet.linkTo(target) is LinkResult.Connected).shouldBeTrue()
            (membrane.exposedOutlet.linkTo(target) is LinkResult.Connected).shouldBeTrue()
            join.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        } else {
            // Control: both arms reconverge on the observation point with no
            // frontier between them.
            plainArm.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
            membrane.exposedOutlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        }
        world.runToIdle()

        val rnd = Random(seed xor 0x5eed)
        for (n in 1..waves) {
            source.emit(n)
            repeat(rnd.nextInt(4)) { world.controller.step() } // partial, seed-randomized draining
        }
        world.runToIdle()

        return Run(
            log = log.toList(),
            folds = folds.toList(),
            violations = violations.toList(),
            denialCount = membrane.boundaryDenials["exposedOutlet"]!!.denialCount,
            restarts = world.host.supervisionAccounting().restarts +
                hostB.supervisionAccounting().restarts +
                hostM.supervisionAccounting().restarts,
        )
    }

    // ------------------------------------------------------------------
    // BS-13: what IS checkable at the reconvergence point.
    // ------------------------------------------------------------------

    @Test
    fun `BS-13 a diamond whose projecting arm denies mid-wave reconverges wave-atomically, with no stale substitution`() {
        val projection = registerRedactingProjection()
        val denied = (1..waves).filter { isDenied(it) }

        for (seed in seeds) {
            val run = runDiamond(seed, projection, protected = true)

            withClue(seed, run.groups) {
                // (1) wave-contiguity: exactly one contiguous run per wave, in
                // strictly increasing counter order. A wave split across two
                // runs — B:4, M:3, M:4 — fails here, which is the interleaving
                // the control below produces without the frontier.
                run.groups.size shouldBe waves
                run.groups.map { it.first.counter } shouldBe (1L..waves.toLong()).toList()
                run.groups.map { it.first.sourceId }.distinct().size shouldBe 1

                // (2) no stale substitution: a wave's run carries that wave's
                // contributions only. On a denied wave the surviving arm rides
                // alone — the denied arm's pre-denial value is never paired in.
                run.groups.forEachIndexed { i, (_, contributions) ->
                    val n = i + 1
                    contributions.toSet() shouldBe
                        if (isDenied(n)) setOf("B:$n") else setOf("B:$n", "M:$n")
                }

                // (4) the two arms really are under different regimes: nothing
                // unredacted is observable at the reconvergence point.
                run.log.none { it.raw.contains("secret") }.shouldBeTrue()
            }

            // (3) the denied wave's release is the flagged RE-SCOPE release,
            // exactly once — and a denial is not a fault.
            val sourceId = run.log.first().ts.sourceId
            run.violations.size shouldBe denied.size
            run.violations.forEachIndexed { i, violation ->
                violation.cause.shouldBeInstanceOf<GlitchViolation>()
                violation.cause.message!! shouldContain "DEAD_LETTERED"
                violation.cause.message!! shouldContain Timestamp(sourceId, denied[i].toLong()).toString()
            }
            run.denialCount shouldBe denied.size.toLong()
            run.restarts shouldBe 0L
        }
    }

    /**
     * The harness check: without the frontier, the same graph and the same
     * seeds do tear at the observation point. Without this, the contiguity and
     * no-substitution assertions above would be "no glitch was observed",
     * which is not a check (epic `computenet-usd` verifiability note).
     */
    @Test
    fun `control - the unprotected diamond tears at the reconvergence point on at least one seed`() {
        val projection = registerRedactingProjection()
        var torn = 0

        for (seed in seeds) {
            val run = runDiamond(seed, projection, protected = false)
            // The *full* property the protected test asserts — contiguity (1)
            // AND no stale substitution (2) — evaluated on the same log shape.
            val intact = run.groups.size == waves &&
                run.groups.map { it.first.counter } == (1L..waves.toLong()).toList() &&
                run.groups.withIndex().all { (i, group) ->
                    val n = i + 1
                    group.second.toSet() == if (isDenied(n)) setOf("B:$n") else setOf("B:$n", "M:$n")
                }
            if (!intact) torn++
        }

        // If this ever reaches 0 the harness stopped producing reorder and the
        // test above is asserting nothing — tune the interleaving, never the
        // assertion.
        (torn > 0).shouldBeTrue()
    }

    // ------------------------------------------------------------------
    // The residual [SEC1-28] names, measured (see concord/corpus/DISPUTES.md).
    // ------------------------------------------------------------------

    /**
     * The half of `[SEC1-28]` that does **not** hold, pinned as a measurement.
     *
     * The reconvergence point forms no combination, so the requirement's
     * "mixed pre/post-denial combination" can only appear in a consumer that
     * folds the arms. It does, twice over:
     *
     * - **at a denied wave**, the fold retains the denied arm's *previous*
     *   value beside the surviving arm's fresh one — literally a pre/post-denial
     *   pair, and the shape stance (ii) calls a modelling error;
     * - **within every wave**, between the group's two arrivals, the fold is
     *   torn across consecutive waves — so the denial is not even what creates
     *   the hazard; it is the ordinary partial-fold transient of a grouping
     *   join.
     *
     * Nothing in the landed model refuses, flags or tags either. A failure here
     * means something now does, and `[SEC1-28]`'s DISPUTES entry is stale.
     */
    @Test
    fun `the fold one hop past the join does form the mixed combination nothing refuses`() {
        val projection = registerRedactingProjection()

        for (seed in seeds) {
            val run = runDiamond(seed, projection, protected = true)

            withClue(seed, run.folds) {
                for (n in 2..waves) {
                    val inWave = run.folds.filter { it.ts.counter == n.toLong() }
                    inWave.isNotEmpty().shouldBeTrue()

                    // Every wave passes through a torn fold: the grouping join
                    // releases arm-by-arm, so a combining consumer sees one
                    // arm at wave n beside the other still at an earlier wave.
                    inWave.any { it.mixed }.shouldBeTrue()

                    if (isDenied(n)) {
                        // And at a denied wave the mixture is what is LEFT
                        // standing when the wave ends: the surviving arm at n,
                        // the denied arm at its pre-denial value.
                        inWave.last() shouldBe Fold(inWave.last().ts, n, n - 1)
                    } else {
                        // A permitted wave settles consistent — the transient
                        // above is transient, which is exactly why the residual
                        // is about the denied case.
                        inWave.last() shouldBe Fold(inWave.last().ts, n, n)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** `M:secret-n` -> `M:n`, and every third wave suppressed outright. */
    private fun registerRedactingProjection(): ProjectionId {
        val id = ProjectionId("boundary-denial-diamond-${UUID.randomUUID()}")
        ProjectionRegistry.register(id) { delta ->
            val s = delta as String
            val n = s.substringAfter("secret-").toInt()
            if (isDenied(n)) null else "M:$n"
        }
        return id
    }

    private inline fun <T> withClue(vararg clue: Any?, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("clue=${clue.toList()} :: ${e.message}", e)
        }
}
