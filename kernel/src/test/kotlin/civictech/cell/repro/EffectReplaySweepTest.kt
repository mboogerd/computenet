package civictech.cell.repro

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Owned
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.DstArtifact
import civictech.testkit.dst.DstArtifacts
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstFailureDetail
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.ExclusiveLedger
import civictech.testkit.dst.ExclusiveLedgers
import civictech.testkit.dst.FailureReport
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JournalFault
import civictech.testkit.dst.PrefixRestartSweepReport
import civictech.testkit.dst.RestartAtFrontierFault
import civictech.testkit.dst.TrackedExclusive
import civictech.testkit.dst.journalRecordCount
import civictech.testkit.dst.prefixRestartSweep
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ================================================================================================
// The graph and its per-world registries. A rig consumer supplies these; none of it is a fault
// injector, journal decorator, crash harness, artifact format or shrinker ([CHA2-04]).
// ================================================================================================

/** One served invocation at an `Effectful` sink: its wave position and the value it carried. */
internal data class Applied(val sourceId: UUID?, val counter: Long?, val value: Int) {
    val position: Pair<UUID?, Long?> get() = sourceId to counter

    override fun toString(): String = "($sourceId,$counter)=$value"
}

/**
 * The external effect logs, keyed weakly by [DstWorld].
 *
 * A [DstCheck] registered in `CheckRegistry` is a **value**, resolved by id from an artifact in a
 * fresh JVM, and a sweep builds one world per run — so a check closing over one graph instance's
 * log would grade prefix `k = 7` against prefix `k = 3`'s effects, and a replay would grade
 * against a log the replaying JVM never wrote. Keying by world is the pattern `doc/dst-rig.md` §4
 * prescribes for exactly this ("a small per-graph registry keyed by `DstWorld` … populated by the
 * graph builder, read by the fault or the check"), and the same one `ExclusiveLedgers` uses.
 */
internal object EffectLogs {
    private val byWorld = WeakHashMap<DstWorld, MutableList<Applied>>()

    fun declare(world: DstWorld, log: MutableList<Applied>): MutableList<Applied> {
        byWorld[world] = log
        return log
    }

    fun require(world: DstWorld): List<Applied> = byWorld[world]?.toList()
        ?: throw IllegalStateException(
            "no effect log for this world; the graph builder declares one with EffectLogs.declare(world, log)",
        )
}

/**
 * A failed C-9 sweep assertion, split the way `doc/dst-rig.md` §3 requires.
 *
 * The message is a **fixed string** naming the invariant that broke — byte-identical across every
 * run of the same failure mode, which is what `PlanShrinker`'s default predicate
 * (`FailurePredicate.sameFailingCheck`) needs in order to accept a genuine reduction. Everything
 * that moves with the run — which positions duplicated, how many, the whole log — is in [detail],
 * which `FailureReport` renders and the shrinker never reads. computenet-umx.4 landed that split
 * in the rig; this is a consumer holding to it.
 */
internal class C9AssertionFailure(
    identity: String,
    private val detail: String,
) : AssertionError(identity), DstFailureDetail {
    override fun detail(): String = detail
}

/**
 * A durable, effectful graph whose external effect log records **wave positions**, not values.
 *
 * `EffectReplayReproTest`'s fixtures log the `Int` the sink acted on, which answers "was this
 * value applied twice". `[CHA2-11]` asks a sharper question — whether a `(sourceId, counter)` was
 * *acted on* more than once — and only a log of positions can answer it: two distinct emissions
 * can legitimately carry the same value, while two deliveries of one position never legitimately
 * both fire.
 *
 * The shape is `civictech.testkit.dst`'s own `DurableEffectGraph` (the rig's self-test fixture),
 * re-expressed here rather than shared. That class is `internal` to `:testkit`'s **test** source
 * set and is not on `:kernel`'s classpath at all; and the evidence lane keeps its own fixtures on
 * purpose (`computenet-umx.1.3`/`.1.4`'s citation discipline), so a change to a rig self-test
 * cannot silently reshape a reproduction, or vice versa.
 *
 * ## `[CHA2-26]`: the rig's exclusive accounting is enabled, and what it can reach
 *
 * Every run of this graph declares an [ExclusiveLedger] and mints one tracked `Owned` per
 * emission, and every check in this suite composes `ExclusiveLedgers.check()`. So `[CHA1-53]`'s
 * accounting is live for every run in this file rather than being replaced by a bespoke
 * assertion — that is `[CHA2-26]` in its strict form.
 *
 * **The limit, stated where it is load-bearing.** The exclusive leg is a *volatile* (off-host)
 * sink, not the journaled one. A journaled frame is Java-serialised
 * (`HostDurability.kt`: `ObjectOutputStream(it).use { out -> out.writeObject(record) }`), and
 * neither `Owned` nor [TrackedExclusive] is `Serializable`, so an exclusive payload cannot ride a
 * write-ahead journal at all. What the accounting therefore covers is every exclusive this graph
 * mints; what it cannot cover is an exclusive crossing a durability boundary, because no such
 * payload is constructible. That is a property of the kernel's journal encoding, not a gap in the
 * rig — and it is why this suite does not claim to retire the C-11 siblings' bespoke-assertion
 * deviation on the durable plane, only to run its own sweeps under the rig's accounting.
 *
 * ## Registration
 *
 * The [spec] is built **once** and registered in `GraphRegistry` at construction, so a failing
 * run's artifact names a graph a replaying JVM can resolve (`[CHA1-06]`, `[CHA1-32]`).
 * Re-registering the same id with a different builder is refused by the registry, which is why
 * every instance below has its own id and is constructed exactly once, in [C9SweepRegistrar].
 */
internal class DurableEffectSweepGraph(val id: String, val emits: Int = EMITS) {

    /**
     * The external effect target: every invocation the `Effectful` sink actually served, in order.
     *
     * It lives outside every cell instance and outside the host, so it survives the restart the
     * run performs — the only way "acted on twice across a crash" is observable at all
     * (`EffectfulRecoveryTest`/`[KFX-24]`'s shape). Cleared per **build**, so one `DstRun` sees
     * clean state and a sweep leaves only its last run's log behind; per-run observations come
     * from each run's `DstReport` or from the check, which runs before the next build.
     */
    val effects: MutableList<Applied> = mutableListOf()

    /** The last built world's ledger, for a caller that wants to read the balance directly. */
    var ledger: ExclusiveLedger? = null
        private set

    lateinit var sinkRef: CellRef
        private set

    /**
     * The step a restart fires at: inside the traffic window, near its end.
     *
     * A fault's only clock is the step hook, fired before a step the run must actually reach, and
     * `DstRun`'s loop ends the moment the controller finds no work — so a restart scheduled after
     * the graph quiesces never fires and is reported inert (`[CHA1-24]`). Traffic therefore starts
     * at step 0 (see [FIRST_EMIT]) and the restart lands two steps before the last emission, which
     * also means the emissions after it exercise the rebuilt host.
     */
    val restartStep: Int get() = FIRST_EMIT + emits - 2

    val lastEmitStep: Int get() = FIRST_EMIT + emits - 1

    val spec: GraphSpec = GraphRegistry.register(GraphSpec(id) { world -> build(world) })

    private fun build(world: DstWorld) {
        effects.clear()
        EffectLogs.declare(world, effects)

        val rng = world.rng("c9-effect-sweep")
        sinkRef = CellRef(UUID(rng.nextLong(), rng.nextLong()))
        val journal = world.journals.declare(JOURNAL)
        val source = SourceCell(CellRef(UUID(rng.nextLong(), rng.nextLong())))

        // [CHA2-26] / [CHA1-53]: the rig's accounting, declared per world so a sweep's runs cannot
        // grade one prefix's payloads against another's (ExclusiveLedgers' own reason).
        val exclusives = ExclusiveLedgers.declare(world)
        ledger = exclusives
        source.exclusives.subscribe(
            Use.fixed(
                object : Consumer<Owned<TrackedExclusive>> {
                    override fun provide(input: Owned<TrackedExclusive>) {
                        exclusives.consume(input, "volatile-exclusive-sink")
                    }
                },
                PortRef.generate(),
            ),
        )

        var link: PortRef? = null
        world.hosts.declare(HOST) { ctx ->
            val host = ManagedHost(
                scheduler = ctx.scheduler,
                registry = ctx.registry,
                journalFor = { journal },
            )
            host.managementInlet.call.spawn(PositionLoggingSink(sinkRef, effects))
            // Rewire at every generation: the pre-restart proxy routes into a host whose scheduler
            // is gone, so without this every post-restart assertion would be vacuous.
            link?.let { source.values.unsubscribe(it) }
            val port = PortRef.generate()
            val proxy = HostedCellProxy.create(sinkRef, host, SinkProxy::class.java) as SinkProxy
            source.values.subscribe(Use.fixed(proxy.inlet.call, port))
            link = port
            host
        }

        world.cells.declare(SINK, sinkRef)

        world.steps.onStep { _, step ->
            if (step in FIRST_EMIT..lastEmitStep) {
                val n = step - FIRST_EMIT
                source.emit(n)
                source.emitExclusive(exclusives.mintOwned("payload-$n", origin = "source@step$step"))
            }
        }
    }

    companion object {
        /** How many emissions the graph makes, so `R` is a property of the seed alone. */
        const val EMITS = 8

        /**
         * The first emitting step, and it **must** be 0: the graph build queues no work, so a
         * graph that waits until step 3 quiesces at step 0 with an empty trace and every fault
         * inert. Measured by the rig's own fixture, and inherited here.
         */
        const val FIRST_EMIT = 0

        const val HOST = "durable"
        const val JOURNAL = "sink-journal"
        const val SINK = "sink"
    }

    /** Outside the host, so it survives the host's restart. Two outlets, two planes. */
    class SourceCell(override val ref: CellRef) : Cell {
        val values = registerPort("values", FanOutlet.create<Consumer<Int>>())
        val exclusives = registerPort("exclusives", FanOutlet.create<Consumer<Owned<TrackedExclusive>>>())

        fun emit(n: Int) = values.call.provide(n)

        fun emitExclusive(payload: Owned<TrackedExclusive>) = exclusives.call.provide(payload)
    }

    /**
     * The effect boundary. Records the **position** of every invocation it serves, so a second
     * delivery of one `(sourceId, counter)` shows up as a duplicate entry rather than hiding
     * behind an equal value.
     */
    class PositionLoggingSink(
        override val ref: CellRef,
        private val acted: MutableList<Applied>,
    ) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(
                object : Consumer<Int> {
                    override fun provide(input: Int) {
                        val ctx = CurrentContext.get()
                        acted += Applied(ctx?.timestamp?.sourceId, ctx?.timestamp?.counter, input)
                    }
                },
            )
        }
    }

    interface SinkProxy {
        val inlet: Use<Consumer<Int>>
    }
}

/**
 * Every graph and check this suite runs, registered under stable ids so that a failing run's
 * artifact can be replayed in a fresh JVM (`[CHA1-06]`, `[CHA1-32]`, `[CHA1-51]`).
 *
 * This object's **name** is what a rendered replay command passes to `--register`:
 * `DstReplayCli` does `Class.forName(name, true, loader)`, and a Kotlin `object`'s initialiser is
 * where that lands. Every graph is therefore constructed here rather than per test — a graph
 * constructed inside a test method would never exist in the replaying JVM, and the replay would
 * fail with "unknown graph id" instead of reproducing anything.
 *
 * The check is world-resolved (see [EffectLogs]), which is what lets one registered check id grade
 * every graph below.
 */
internal object C9SweepRegistrar {

    const val CHECK_ID: String = "c9-at-most-once-per-position"

    val census = DurableEffectSweepGraph("c9-sweep-census")
    val prefixSweep = DurableEffectSweepGraph("c9-prefix-restart-sweep")
    val rollbackControl = DurableEffectSweepGraph("c9-frontier-rollback-control")
    val rolledBack = DurableEffectSweepGraph("c9-frontier-rollback")
    val tornTail = DurableEffectSweepGraph("c9-torn-tail")
    val corrupted = DurableEffectSweepGraph("c9-corrupt-record")
    val corruptedControl = DurableEffectSweepGraph("c9-corrupt-record-control")

    /**
     * `[CHA2-11]`'s property — no `(sourceId, counter)` acted on more than once — **composed with**
     * `[CHA1-53]`'s exclusive-payload accounting (`[CHA2-26]`).
     *
     * Composed rather than run as two checks because `DstRun` grades one check per run: an
     * exclusive lost during a fault-injected run must fail the same run the C-9 property is being
     * measured on, not a separate one.
     */
    val atMostOncePerPosition: DstCheck = CheckRegistry.register(CHECK_ID) { world ->
        val log = EffectLogs.require(world)
        val duplicated = log.groupingBy { it.position }.eachCount().filter { it.value > 1 }
        if (duplicated.isNotEmpty()) {
            throw C9AssertionFailure(
                identity = C9_AT_MOST_ONCE_IDENTITY,
                detail = "duplicated positions: " +
                    duplicated.entries.joinToString { "${it.key} fired ${it.value}x" } +
                    "; whole effect log (${log.size} entries): $log",
            )
        }
        ExclusiveLedgers.check().verify(world)
    }

    /**
     * The failing check's identity string, held as a constant because it is compared, not merely
     * printed: `PlanShrinker` accepts a reduction only when this string is unchanged.
     */
    const val C9_AT_MOST_ONCE_IDENTITY: String =
        "[CHA2-11]: an Effectful sink acted on a (sourceId, counter) more than once across a restart"

    /** `--register` argument for a rendered replay command (`[CHA1-51]`). */
    val REGISTRARS: List<String> = listOf(C9SweepRegistrar::class.java.name)

    /** Forces this object's initialiser from a test that only needs the side effect. */
    fun ensureRegistered() = Unit
}

/** BS-2's reproduction id, the token `@ExpectedFailure` matches on. Never a formatted message. */
private const val C9_PREFIX_RESTART_REFIRE = "CHA2-BS-2-prefix-restart-refire"

/**
 * **C-9 effect replay under CHA1's rig: BS-2, BS-3, BS-6 and BS-17** (`[CHA2-11]`, `[CHA2-12]`,
 * `[CHA2-15]`, `[CHA2-47]`, and the strict form of `[CHA2-26]`).
 *
 * Where `EffectReplayReproTest` pins three hand-built crash constructions, this suite sweeps the
 * axis a hand-built test cannot: **every** journal prefix a crash could have landed on, plus the
 * two journal mutations `[CHA1-19]` provides that change what recovery reads. Everything is driven
 * by `civictech.testkit.dst` — CHA1's rig, whose consumer contract is `doc/dst-rig.md` — and
 * nothing here is a second copy of it (`[CHA2-04]`): no fault class, no journal decorator, no
 * crash harness, no artifact format, no shrinker. What this file adds is a graph
 * ([DurableEffectSweepGraph]) and the property the runs are graded against, which is exactly what
 * a rig consumer is supposed to supply.
 *
 * All of `[CHA1-19]`..`[CHA1-22]`, `[CHA1-30]`..`[CHA1-40]` and `[CHA1-50]`..`[CHA1-53]` were
 * verified present in `testkit/src/main/kotlin/civictech/testkit/dst/` before this suite was
 * written — the gate the bead's own description sets.
 *
 * ## The verdicts, recorded rather than presumed
 *
 * `doc/evidence-lane-findings.md` → "`computenet-umx.1.6` — rig-gated C-9 sweeps" carries the
 * reasoning, the transcripts and the replay instructions. In one line each:
 *
 * - **BS-2 (`[CHA2-11]`) — FAILS, and is annotated as an expected failure.** At seed 101 the
 *   at-most-once property breaks at 6 of 17 prefixes: every odd `k` inside the log the host had
 *   written by the restart step. The frame for counter `c` sits at record `2(c-1)` and its
 *   frontier advance at `2(c-1)+1`, so an odd prefix is precisely a crash landing *between* an
 *   effect firing and the record saying it had fired — and replay re-fires it. Owner
 *   `computenet-xxeo`.
 * - **BS-3 (`[CHA2-12]`) — the deferred rollback verdict: the re-delivered invocations DO
 *   re-fire.** Recorded from the run, not assumed, and BS-2 is what makes it more than a
 *   curiosity: an ordinary truncation reaches the same state, so the rollback is not an
 *   artificial injury.
 * - **BS-6 (`[CHA2-15]`) — both halves hold.** A torn tail converges: recovery completes, exactly
 *   the torn record is missing, and no effect fires for it. A corrupted record diverges:
 *   `RecoveryIncomplete(recordIndex = i, total = R)`, and no effect fires for any record at or
 *   beyond `i`.
 * - **BS-17 (`[CHA2-47]`) — every seed is a pinned constant** in [Seeds], never replaced,
 *   narrowed or reordered, and each reproduction records its artifact path and a copy-pasteable
 *   replay command (`[CHA1-50]`, `[CHA1-51]`).
 *
 * ## `[CHA2-50]`, `[CHA2-51]`
 *
 * No kernel `main` change: BS-2's defect is filed as `computenet-xxeo` and left failing under
 * `@ExpectedFailure`, which is the disposition the feature prescribes. No concord scenario and no
 * corpus schema change; `concord/corpus/DISPUTES.md`'s G-59/C-9 boundary entry is **extended**
 * with this suite's reproduction ids and nothing else.
 */
class EffectReplaySweepTest {

    // ==============================================================================================
    // BS-17 / [CHA2-47]: the pinned seeds. One constant per reproduction, never re-rolled.
    // ==============================================================================================

    /**
     * Every seed this suite runs, pinned (`[CHA2-47]`, `[CHA1-35]`, `[CHA1-38]`).
     *
     * A seed here is a **discovered** value, not a tuning knob: it is recorded once and is never
     * replaced, narrowed or reordered to make the suite green. BS-2's seed in particular found a
     * real residual on its first authoring run and is now `computenet-xxeo`'s acceptance seed — if
     * a kernel change makes it reproduce something different, the honest response is a new finding
     * under the same seed, not a different seed.
     */
    private object Seeds {
        /** BS-2's prefix sweep, and `computenet-xxeo`'s acceptance seed. */
        const val PREFIX_SWEEP = 101L

        /** BS-3's frontier rollback, and its un-rolled control on the same seed. */
        const val FRONTIER_ROLLBACK = 202L

        /** BS-6's torn tail. */
        const val TORN_TAIL = 303L

        /** BS-6's corrupted interior record, and its uncorrupted control on the same seed. */
        const val CORRUPT_RECORD = 404L
    }

    /**
     * BS-3's rollback point: keep the first three frontier advances and drop the rest.
     *
     * See the BS-3 test's KDoc for why this is a *count* rather than the bead's `(s, 3)` —
     * `[CHA1-22]` as landed cannot name a `(sourceId, counter)` from outside `:kernel`.
     */
    private val rollbackKeepsAdvances = 3

    /** The interior journal record BS-6's diverging half corrupts. */
    private val corruptIndex = 1

    // ==============================================================================================
    // The fixture's own pin: a log worth sweeping, and the record layout every claim below rests on.
    // ==============================================================================================

    /**
     * The census the sweeps' ranges come from, asserted so a later change that quietly shrinks the
     * log fails **here** rather than silently weakening every sweep below it.
     *
     * A log with three records makes "every prefix in `0..R`" a claim about three cases, which is
     * not a sweep; a log with no frontier advance makes BS-3's rollback vacuous. The strict
     * frame/frontier alternation is pinned too, because BS-2's finding is stated in terms of it —
     * "odd prefixes fail" is only meaningful while the frame for counter `c` sits at record
     * `2(c - 1)` and its advance at `2(c - 1) + 1`.
     */
    @Test
    fun `the durable fixture writes a log worth sweeping, frame and frontier strictly alternating`() {
        val census = journalRecordCount(
            C9SweepRegistrar.census.spec,
            seed = Seeds.PREFIX_SWEEP,
            journal = DurableEffectSweepGraph.JOURNAL,
        )
        println("[census] seed=${Seeds.PREFIX_SWEEP} $census")

        assertTrue(census.records >= 8, "a prefix sweep needs interior prefixes, got $census")
        assertTrue(census.allTagsKnown, "unknown journal record tag: $census")
        assertEquals(
            DurableEffectSweepGraph.EMITS,
            census.frameRecords,
            "one journaled frame per emission: $census",
        )
        assertEquals(
            DurableEffectSweepGraph.EMITS,
            census.frontierAdvances,
            "one Effectful frontier advance per delivery — BS-2's odd/even reading depends on it: $census",
        )
        assertEquals(
            census.frameRecords + census.frontierAdvances,
            census.records,
            "frames and advances are the whole log; a third record kind would move BS-2's indices: $census",
        )
        assertTrue(
            census.frontierAdvances > rollbackKeepsAdvances,
            "BS-3 rolls back to $rollbackKeepsAdvances advances and needs more than that to drop: $census",
        )
    }

    // ==============================================================================================
    // BS-2 ([CHA2-11]) — the arbitrary-prefix restart sweep.
    // ==============================================================================================

    /**
     * **BS-2 — a restart from every journal prefix `k in 0..R` must act on each
     * `(sourceId, counter)` at most once. IT DOES NOT.**
     *
     * `k` is where the crash landed in the write-ahead log. `k = R` is the ordinary restart every
     * durability test performs; `k = 0` is a host that comes back with nothing; the interior `k`s
     * are the cases nobody writes by hand — and they are where the property breaks.
     *
     * ## The observed finding, at pinned seed [Seeds.PREFIX_SWEEP]
     *
     * `failed on 6 of 17 prefixes; failing k = [1, 3, 5, 7, 9, 11]` — every **odd** prefix inside
     * the log the host had written by the restart step. Each failure is a single duplicated
     * position, always the frame whose frontier advance the prefix cut off: `k=1` re-fires
     * `(s,1)`, `k=3` re-fires `(s,2)`, and so on to `k=11` re-firing `(s,6)`. Every even prefix
     * passes, and recovery reports `recovery-complete@k` in all six failures — this is a clean
     * replay, not a damaged one.
     *
     * The mechanism follows from the record layout the census test pins: `ManagedHost` journals a
     * hosted frame at intake, delivers it on a later scheduler task, and journals the `Effectful`
     * frontier advance beside the delivery. The effect therefore fires *between* two journal
     * records, and an odd prefix is exactly a crash inside that window — the frame is durable, the
     * "already acted on" advance is not, `HostDurability.alreadyProcessed` says no, and the
     * external effect fires a second time.
     *
     * ## Why this is an `@ExpectedFailure` and not a fixed kernel
     *
     * `[CHA2-50]`: this is the evidence lane, and a reproduction's deliverable is the failing test
     * plus the filed record — the fix belongs to KFX. The record is **`computenet-xxeo`**, which
     * owns both the decision (is `[24-DUR-05]` at-least-once, at-most-once, or something that
     * commits the effect with its dedupe record?) and the change. The annotation fails the build
     * the moment the body passes (`[CHA2-44]`), so the fix landing flips this test red and cannot
     * be missed.
     *
     * Nothing here is softened to reach that state: the sweep asserts the unweakened `[CHA2-11]`
     * property, over the full `0..R` range on the pinned seed.
     * `PrefixRestartSweepReport`'s `init` refuses a report that does not cover its whole declared
     * range, so the sweep cannot be narrowed to the prefixes that passed even by a caller that
     * wanted to, and every `k` runs regardless of earlier failures (`[CHA1-39]`'s reason: "k=7
     * only" and "every k above 3" are different findings a fail-fast loop cannot tell apart).
     */
    @Test
    @ExpectedFailure(
        signature = C9_PREFIX_RESTART_REFIRE,
        reason = "a crash between an Effectful delivery's journaled frame and its frontier advance re-fires the effect on replay",
        owner = "computenet-xxeo",
        filedAs = "doc/evidence-lane-findings.md -> computenet-umx.1.6 - rig-gated C-9 sweeps",
    )
    fun `BS-2 a restart from every journal prefix acts on each source counter position at most once`() =
        withSignature(C9_PREFIX_RESTART_REFIRE) {
            val graph = C9SweepRegistrar.prefixSweep
            val census = journalRecordCount(
                graph.spec,
                seed = Seeds.PREFIX_SWEEP,
                journal = DurableEffectSweepGraph.JOURNAL,
            )

            val sweep = prefixRestartSweep(
                graph = graph.spec,
                seed = Seeds.PREFIX_SWEEP,
                host = DurableEffectSweepGraph.HOST,
                journal = DurableEffectSweepGraph.JOURNAL,
                records = census.records,
                atStep = graph.restartStep,
                check = C9SweepRegistrar.atMostOncePerPosition,
            )

            println("[BS-2] ${sweep.summary()}")
            assertEquals((0..census.records).toList(), sweep.entries.map { it.k }, "every k in 0..R, in order")

            // A broken experiment is not a finding, and must not be mistaken for one. These three
            // are asserted OUTSIDE the recorded failure: if the sweep ever stops being executable,
            // that is a new defect and must redden the build rather than be absorbed by the
            // annotation ([CHA2-43]).
            val broken = sweep.entries.filter { it.error != null }
            check(broken.isEmpty()) {
                "a restart must be executable at every prefix; broken experiments at " +
                    "k=${broken.map { it.k }}: ${broken.firstOrNull()?.message}"
            }
            check(sweep.exhausted.isEmpty()) {
                "no prefix may leave the run unquiesced — an unquiesced run claims nothing: " +
                    "k=${sweep.exhausted.map { it.k }}"
            }
            sweep.entries.forEach { entry ->
                val report = requireNotNull(entry.report) { "k=${entry.k} produced no report" }
                check(report.appliedFaults.single().fired > 0) {
                    "the restart was inert at k=${entry.k}, so nothing was tested there: ${report.summary()}"
                }
            }

            // BS-17: every failing k is pinned WITH its own artifact and replay command, written
            // BEFORE the assertion so the evidence exists whatever the assertion then does.
            recordPrefixFailures(sweep)

            sweep.assertAllPassed()
        }

    /**
     * BS-17's reporting half for a prefix sweep (`[CHA1-50]`, `[CHA1-51]`).
     *
     * `prefixRestartSweep` walks prefixes rather than seeds, so it does not write artifacts the way
     * `dstSweep` does. This writes one per failing `k` from that run's own report and prints the
     * full `FailureReport` — plan with activation steps, dead letters, artifact path, replay
     * command — to stdout, so a failure's evidence survives in the test log after the worktree is
     * gone.
     */
    private fun recordPrefixFailures(sweep: PrefixRestartSweepReport) {
        sweep.failures.forEach { entry ->
            val report = entry.report ?: return@forEach
            println("[BS-2 FAILING k=${entry.k}]\n${renderFailure(report, "c9-prefix-restart-k${entry.k}")}")
        }
    }

    // ==============================================================================================
    // BS-3 ([CHA2-12]) — the frontier-rollback verdict CHA1's BS-11 defers to CHA2.
    // ==============================================================================================

    /**
     * **BS-3 — an `Effectful` inlet's processed-frontier rolled back, independently of the journal
     * prefix: what the kernel actually does.**
     *
     * The construction `[CHA2-12]` specifies: the sink applies a run of invocations, the frontier
     * is rolled back to an earlier position, the host is restarted from the **whole** journal, and
     * the invocations past the rollback point are re-delivered. It asks whether they *re-fire*, and
     * it is explicit that the suite must **record** the answer rather than assume it — this is the
     * verdict CHA1's BS-11 deliberately deferred here.
     *
     * ## How the rollback point is expressed, and why it is not literally `(s, 3)`
     *
     * The bead's prose says "applied up to `(s,7)`; frontier rolled back to `(s,3)`". `[CHA1-22]`
     * as landed cannot name a `(sourceId, counter)`: `HostDurability`'s `FrontierRecord` is a
     * `private data class` whose body is Java-serialised, so from outside `:kernel` only a record's
     * **tag byte** is readable and a rollback selects by *counting* frontier advances
     * (`FrontierRollbackJournal`'s KDoc — `computenet-umx.3`'s reported structural limit, not a
     * shortcut taken here). Keeping the first three advances is this rig's expression of "rolled
     * back to the third applied position", and the test asserts the consequence it can observe —
     * that positions past the retained advances are re-delivered — rather than a decoded frontier
     * it has no way to read.
     *
     * ## The comparison is what makes the answer attributable
     *
     * Two runs on the same pinned seed differ in exactly one field, `keepFrontierAdvances`. The
     * journal prefix is `null` — the whole log — in both, which exercises `[CHA1-22]`'s
     * independence claim directly. Anything that differs between their effect logs is attributable
     * to the rollback and to nothing else.
     *
     * ## RECORDED ANSWER ([CHA2-12])
     *
     * **The re-delivered invocations re-fire.** The rolled-back run acts on strictly more positions
     * than the control, and the extra entries are repeats of positions the sink had already acted
     * on before the restart (observed: `1,2,3,4,5,6` then `4,5,6` again, then `7,8`). The control,
     * with its frontier intact, acts on each position exactly once.
     *
     * **The interpretation, and how BS-2 changed it.** Read alone, this could be dismissed as an
     * artificial injury — the frontier *is* the exactly-once mechanism, and the fault deletes
     * durably-recorded state the kernel wrote and never lost on its own. BS-2 above removes that
     * escape: an ordinary journal truncation at any odd prefix reaches the same state without any
     * frontier surgery at all. So the honest verdict is that both are the same finding
     * (`computenet-xxeo`) seen through two different faults, and that `[24-DUR-05]`'s exactly-once
     * effect delivery is exactly as durable as the frontier journal and no stronger.
     *
     * **No `@ExpectedFailure` here**, because this test asserts the *observed* behaviour and
     * therefore passes; the annotation is a claim that a body still fails, and it fails the build
     * when its body passes (`[CHA2-44]`). BS-2 carries the standing claim for both.
     */
    @Test
    fun `BS-3 rolling the processed frontier back re-delivers and re-fires the invocations past it`() {
        fun runWith(
            graph: DurableEffectSweepGraph,
            keep: Int?,
        ): Triple<DstReport, List<Applied>, RestartAtFrontierFault> {
            val restart = RestartAtFrontierFault(
                id = "restart",
                host = DurableEffectSweepGraph.HOST,
                journal = DurableEffectSweepGraph.JOURNAL,
                atStep = graph.restartStep,
                prefix = null, // the WHOLE log in both runs: only the frontier knob varies
                keepFrontierAdvances = keep,
            )
            val report = DstRun(
                graph.spec,
                FaultPlan.of(Seeds.FRONTIER_ROLLBACK, restart),
                check = C9SweepRegistrar.atMostOncePerPosition,
            ).execute()
            return Triple(report, graph.effects.toList(), restart)
        }

        val (controlReport, controlEffects, controlRestart) =
            runWith(C9SweepRegistrar.rollbackControl, keep = null)
        val (rolledReport, rolledEffects, rolledRestart) =
            runWith(C9SweepRegistrar.rolledBack, keep = rollbackKeepsAdvances)

        println("[BS-3 control] counters=${controlEffects.map { it.counter }} outcome=${controlReport.outcome}")
        println("[BS-3 rolled ] counters=${rolledEffects.map { it.counter }} outcome=${rolledReport.outcome}")

        assertNotNull(controlRestart.lastRecovery, "the control restart never fired: ${controlReport.summary()}")
        assertNotNull(rolledRestart.lastRecovery, "the rolled-back restart never fired: ${rolledReport.summary()}")
        assertTrue(controlEffects.isNotEmpty(), "the sink acted on nothing, so nothing was tested")

        // The control: an intact frontier survives the restart and suppresses every replayed frame
        // it had already acted on. This is BS-2's k = R case, stated on its own.
        assertEquals(
            DstOutcome.PASSED,
            controlReport.outcome,
            "with the frontier intact the at-most-once property holds: ${controlReport.summary()}",
        )
        assertEquals(
            controlEffects.map { it.position }.distinct().size,
            controlEffects.size,
            "control: each position acted on exactly once — $controlEffects",
        )

        // THE RECORDED ANSWER ([CHA2-12]): they re-fire.
        val repeats = rolledEffects.groupingBy { it.position }.eachCount().filter { it.value > 1 }
        assertTrue(
            repeats.isNotEmpty(),
            "recorded answer: a rolled-back frontier re-fires the re-delivered invocations. Observed no " +
                "repeat at all, which would mean the rollback never reached the frontier — check " +
                "FrontierRollbackJournal.frontierAdvancesAfterLastCheckpoint. rolled=$rolledEffects",
        )
        assertEquals(
            (rollbackKeepsAdvances + 1).toLong(),
            repeats.keys.mapNotNull { it.second }.min(),
            "the FIRST re-fired position is the one just past the retained advances — otherwise the " +
                "rollback landed somewhere other than where it was aimed: repeats=${repeats.keys}",
        )
        assertTrue(
            rolledEffects.size > controlEffects.size,
            "the rollback must lose suppression the control applies: " +
                "rolled=${rolledEffects.size} control=${controlEffects.size}",
        )
        assertEquals(
            DstOutcome.FAILED,
            rolledReport.outcome,
            "the at-most-once check must see the re-fire: ${rolledReport.summary()}",
        )
        assertEquals(
            C9SweepRegistrar.C9_AT_MOST_ONCE_IDENTITY,
            rolledReport.failingCheck?.message,
            "…and must fail with the stable identity the shrinker compares, not a run-varying message",
        )

        // BS-17: the reproduction records its artifact path and replay command ([CHA1-50], [CHA1-51]).
        println("[BS-3 reproduction]\n${renderFailure(rolledReport, "c9-frontier-rollback")}")
    }

    // ==============================================================================================
    // BS-6 ([CHA2-15]) — torn tail vs corrupted record, with effects.
    // ==============================================================================================

    /**
     * **BS-6, converging half — a torn tail drops exactly the torn record, recovery completes, and
     * no effect fires for it.**
     *
     * A torn trailing record is a suffix the host never acknowledged: the journal's last write did
     * not survive the crash. What this adds over the rig's own `BS-9 converging control` is the
     * **effect** dimension the rig deliberately does not assert — not merely that recovery reported
     * complete, but that the dropped record produced no invocation at the `Effectful` sink.
     *
     * At pinned seed [Seeds.TORN_TAIL] the torn record is the journaled frame for counter 7, which
     * was written at intake and not yet delivered when the restart discarded the host. So the
     * effect log ends up `[1, 2, 3, 4, 5, 6, 8]`: counter 7 never fires, counter 8 is a live
     * post-recovery emission, and nothing fires twice. That exact list is asserted rather than a
     * weaker property, because "no effect fired for the torn record" and "some effects are missing"
     * are different claims and only the first is `[CHA2-15]`'s.
     */
    @Test
    fun `BS-6 a torn tail replays clean minus exactly the torn record and fires no effect for it`() {
        val graph = C9SweepRegistrar.tornTail
        val restart = RestartAtFrontierFault(
            id = "restart",
            host = DurableEffectSweepGraph.HOST,
            journal = DurableEffectSweepGraph.JOURNAL,
            atStep = graph.restartStep,
        )
        val tear = JournalFault.truncateTail("tear", DurableEffectSweepGraph.JOURNAL, n = 1)

        val report = DstRun(
            graph.spec,
            FaultPlan.of(Seeds.TORN_TAIL, tear, restart),
            check = C9SweepRegistrar.atMostOncePerPosition,
        ).execute()

        val recovery = assertNotNull(restart.lastRecovery, "the restart never fired: ${report.summary()}")
        println("[BS-6 torn tail] recovery=$recovery counters=${graph.effects.map { it.counter }}")

        assertTrue(recovery.complete, "a torn tail must replay clean, got $recovery")
        assertEquals(0, recovery.unapplied, "no record may be left unapplied by a torn tail")
        assertEquals(
            (restart.recordsAtRestart ?: 0) - 1,
            recovery.offered,
            "exactly the torn record is missing from the replay, no more",
        )
        assertTrue(
            report.appliedFaults.none { it.inert },
            "both faults must have fired, or the run tested nothing: ${report.appliedFaults}",
        )
        assertTrue(
            report.deadLetters.isEmpty(),
            "a torn tail is not an error and must not dead-letter: ${report.deadLetters.map { it.description }}",
        )

        // The effect half [CHA2-15] asks for.
        assertEquals(
            DstOutcome.PASSED,
            report.outcome,
            "no position may be acted on twice under a torn tail: ${report.summary()}",
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 8L),
            graph.effects.map { it.counter },
            "the torn record's own invocation (counter 7) must not fire, nothing may fire twice, and the " +
                "live post-recovery emission must still land: ${graph.effects}",
        )
    }

    /**
     * **BS-6, diverging half — a corrupted interior record aborts replay at that record, and no
     * effect fires for any record at or beyond it.**
     *
     * `[CHA2-15]`'s two assertions, both made here: `RecoveryIncomplete`'s `recordIndex` is the
     * corrupted index and its `total` is the record count recovery was offered; and the partial
     * replay is **not** treated as complete — the records from the corrupted one onward never
     * applied, so the sink acted on nothing they would have delivered.
     *
     * The abort point is checked against the **effect log**, not only against the exception:
     * "aborted at record `i`" and "delivered nothing from record `i` on" are different claims and
     * only the second is about effects. With [corruptIndex] `= 1` and the alternating layout the
     * census pins, exactly one frame — counter 1, at record 0 — sits before the abort point, and it
     * is the only position the replay may re-deliver.
     *
     * ## What the run also shows, and where it is owned
     *
     * That one re-delivery **re-fires**, so the composed run FAILS its at-most-once check. It is
     * the same defect BS-2 pins and `computenet-xxeo` owns — record 0's frontier advance is
     * record 1, the corrupted one, so the replay applies the frame and never reaches the advance
     * that would have suppressed it. It is recorded here rather than asserted as a separate
     * finding, and the assertions below are careful to say which is which: the re-fire is confined
     * to records before the abort point, and every record at or beyond it produced nothing.
     */
    @Test
    fun `BS-6 a corrupted record aborts replay at its index and fires no effect at or beyond it`() {
        val graph = C9SweepRegistrar.corrupted
        val restart = RestartAtFrontierFault(
            id = "restart",
            host = DurableEffectSweepGraph.HOST,
            journal = DurableEffectSweepGraph.JOURNAL,
            atStep = graph.restartStep,
        )
        val rot = JournalFault.corruptAt("rot", DurableEffectSweepGraph.JOURNAL, corruptIndex)

        val report = DstRun(
            graph.spec,
            FaultPlan.of(Seeds.CORRUPT_RECORD, rot, restart),
            check = C9SweepRegistrar.atMostOncePerPosition,
        ).execute()

        val recovery = assertNotNull(restart.lastRecovery, "the restart never fired: ${report.summary()}")
        val incomplete = assertNotNull(
            recovery.incomplete,
            "a corrupted interior record must abort recovery, got $recovery",
        )
        println("[BS-6 corrupt] recovery=$recovery counters=${graph.effects.map { it.counter }}")

        assertEquals(corruptIndex, incomplete.recordIndex, "recovery aborts AT the corrupted record")
        assertEquals(recovery.offered, incomplete.total, "total is the record count recovery was offered")
        assertTrue(!recovery.complete, "a partial replay must not be reported complete")
        assertTrue(recovery.unapplied > 0, "records from the corrupted one onward did not apply")

        // [CHA1-20]: the index and total reach the run report through the trace it carries.
        val traced = report.trace.filter { it.faultTag == "restart" }.mapNotNull { it.port }
        assertTrue(
            traced.any { it == "recovery-incomplete@${incomplete.recordIndex}/${incomplete.total}" },
            "the report must carry RecoveryIncomplete's index and total; traced: $traced",
        )
        assertTrue(
            report.deadLetters.any { it.description.contains("journal replay: record $corruptIndex") },
            "the corrupted record must be dead-lettered, not swallowed: " +
                "${report.deadLetters.map { it.description }}",
        )

        // The effect half. The frame for counter c sits at record 2(c - 1) (pinned by the census
        // test), so the records the replay could reach before aborting at `corruptIndex` are the
        // counters below.
        val reachable = (1..DurableEffectSweepGraph.EMITS)
            .filter { 2 * (it - 1) < corruptIndex }
            .map { it.toLong() }
            .toSet()
        val refired = graph.effects
            .groupingBy { it.counter }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .filterNotNull()
            .toSet()

        assertEquals(
            reachable,
            refired,
            "only a record BEFORE the abort point may be re-delivered — a re-fire at or beyond " +
                "record $corruptIndex would mean the aborted replay applied records it reported unapplied: " +
                "${graph.effects}",
        )

        // …and the control run on the same pinned seed, with no corruption, is what says which
        // positions a COMPLETE replay would have covered. Nothing the corrupted run acted on may
        // lie outside it: a partial replay must not synthesise a delivery.
        val control = C9SweepRegistrar.corruptedControl
        val controlReport = DstRun(
            control.spec,
            FaultPlan.of(
                Seeds.CORRUPT_RECORD,
                RestartAtFrontierFault(
                    id = "restart",
                    host = DurableEffectSweepGraph.HOST,
                    journal = DurableEffectSweepGraph.JOURNAL,
                    atStep = control.restartStep,
                ),
            ),
            check = C9SweepRegistrar.atMostOncePerPosition,
        ).execute()

        assertEquals(
            DstOutcome.PASSED,
            controlReport.outcome,
            "the uncorrupted control must hold at-most-once: ${controlReport.summary()}",
        )
        // Compared by COUNTER, not by whole position: the two runs are two builds, so their
        // outlets mint different `sourceId`s and a position from one is never equal to a position
        // from the other. The counter is the part that is comparable across builds, and it is the
        // part the claim is about.
        assertTrue(
            graph.effects.mapNotNull { it.counter }.toSet()
                .subtract(control.effects.mapNotNull { it.counter }.toSet()).isEmpty(),
            "the aborted replay acted on an invocation the complete replay never did: " +
                "corrupted=${graph.effects} control=${control.effects}",
        )
    }

    // ==============================================================================================
    // BS-17 ([CHA2-47], [CHA1-50], [CHA1-51]) — artifact + replay command, per reproduction.
    // ==============================================================================================

    /**
     * **BS-17 — a reproduction records a replayable artifact and a copy-pasteable replay command.**
     *
     * The artifact asserted on here is BS-3's rollback run: the one construction in this suite that
     * is *supposed* to fail its check, so its artifact is written from a real failure rather than
     * manufactured. What is asserted is the chain a later reader depends on:
     *
     * - the artifact lands under a module `build/` directory (`[CHA1-54]`, enforced by
     *   `DstArtifacts.requireUnderBuildDirectory` rather than by this test);
     * - it names the graph, the check and the seed, so `DstReplay` can re-run it (`[CHA1-32]`) — an
     *   artifact from a `FAILED` run *without* a `checkId` is refused by `DstArtifact.of`, because
     *   replaying it would reproduce the run as passing;
     * - the seed it records is the pinned one, unchanged (`[CHA2-47]`, `[CHA1-35]`);
     * - the rendered command names `DstReplayCli` and carries the `--register` a fresh JVM needs to
     *   resolve this suite's graph and check (`[CHA1-51]`).
     *
     * The command's classpath is this JVM's, so it is valid until the next build rewrites those
     * directories — which is why it is printed into the test log beside the failure it replays
     * rather than copied into a bead or a document.
     */
    @Test
    fun `BS-17 a reproduction writes a replayable artifact and a copy-pasteable replay command`() {
        C9SweepRegistrar.ensureRegistered()
        val graph = C9SweepRegistrar.rolledBack
        val restart = RestartAtFrontierFault.withFrontierRolledBack(
            id = "restart",
            host = DurableEffectSweepGraph.HOST,
            journal = DurableEffectSweepGraph.JOURNAL,
            atStep = graph.restartStep,
            keepFrontierAdvances = rollbackKeepsAdvances,
        )
        val run = DstRun(
            GraphRegistry.require(graph.id),
            FaultPlan.of(Seeds.FRONTIER_ROLLBACK, restart),
            check = CheckRegistry.require(C9SweepRegistrar.CHECK_ID),
        )
        val report = run.execute()

        assertEquals(
            DstOutcome.FAILED,
            report.outcome,
            "BS-17 needs a real failure to write an artifact from: ${report.summary()}",
        )

        val artifact = DstArtifacts.write(
            DstArtifact.of(run, report, suite = SUITE, checkId = C9SweepRegistrar.CHECK_ID),
        )
        val rendered = FailureReport.of(
            report,
            suite = SUITE,
            artifact = artifact,
            registrars = C9SweepRegistrar.REGISTRARS,
            exclusives = graph.ledger,
        )
        println("[BS-17 reproduction]\n${rendered.render()}")

        assertTrue(artifact.isFile, "the artifact must exist on disk: ${artifact.absolutePath}")
        assertTrue(
            artifact.absoluteFile.toPath().any { it.toString() == "build" },
            "[CHA1-54]: artifacts live under a module build directory: ${artifact.absolutePath}",
        )

        val readBack = DstArtifacts.read(artifact)
        assertEquals(Seeds.FRONTIER_ROLLBACK, readBack.seed, "[CHA2-47]: the recorded seed is the pinned one")
        assertEquals(graph.id, readBack.graphId, "the artifact names a graph a replay can resolve")
        assertEquals(
            C9SweepRegistrar.CHECK_ID,
            readBack.checkId,
            "a FAILED artifact must name its check, or a replay reports it as passing ([CHA1-32])",
        )
        assertEquals(
            RestartAtFrontierFault.KIND,
            readBack.plan.faults.single().kind,
            "the plan round-trips by kind: ${readBack.plan}",
        )
        assertEquals(
            C9SweepRegistrar.C9_AT_MOST_ONCE_IDENTITY,
            readBack.observed.failingCheck,
            "the artifact records the stable failure identity, not a run-varying message",
        )

        val command = rendered.replay.commandLine
        assertTrue(command.contains("DstReplayCli"), "the replay command invokes the rig's CLI: $command")
        assertTrue(command.contains(artifact.absolutePath), "…on this artifact: $command")
        assertTrue(
            C9SweepRegistrar.REGISTRARS.all { command.contains(it) },
            "[CHA1-51]: the command must carry the --register a fresh JVM needs: $command",
        )
    }

    // ==============================================================================================
    // Shared helpers.
    // ==============================================================================================

    /**
     * `[CHA1-50]`/`[CHA1-51]`: write [report]'s artifact and render the full failure report, replay
     * command included.
     *
     * The `DstRun` handed to `DstArtifact.of` supplies only the budget — the seed, the plan, the
     * graph id and the observed outcome all come from [report] — so a report produced inside a
     * sweep can be captured without the sweep exposing the run object that produced it.
     */
    private fun renderFailure(report: DstReport, suite: String): String {
        val artifact: File? = runCatching {
            DstArtifacts.write(
                DstArtifact.of(
                    DstRun(GraphRegistry.require(report.graphId), report.plan, report.budget),
                    report,
                    suite = suite,
                    checkId = C9SweepRegistrar.CHECK_ID,
                ),
            )
        }.getOrNull()
        return FailureReport.of(
            report,
            suite = suite,
            artifact = artifact,
            registrars = C9SweepRegistrar.REGISTRARS,
        ).render()
    }

    private companion object {
        /** The artifact directory this suite writes under, below `kernel/build/dst/failures`. */
        const val SUITE = "c9-effect-replay-sweep"
    }
}
