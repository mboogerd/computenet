package civictech.demo.beadsmirror.ready

import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.extension
import kotlin.io.path.readBytes

/**
 * computenet-98u.1.2: the derived ready set over the mirror's field OR-map and
 * dependency-edge set — correct under each delta shape, incremental by an
 * instrumented count, and derived without ever consuming `is_blocked` or
 * running `bd`.
 *
 * Hand-built [ChangeRecord]s over an in-process [MirrorProjector], the fixture
 * shape `projector/EchoDropTest.kt` and `baseline/BaselineBuilderTest.kt` use:
 * no `bd`, no `dolt`, no workspace on disk, so this is a real CI gate rather
 * than an environment-gated one.
 */
class ReadySetCellTest {

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private val minter = DotMinter("beads-scratch-98u")

    /** Projector plus a ready cell attached before the first record (the cell's stated precondition). */
    private fun rig(): Pair<MirrorProjector, ReadySetCell> {
        val projector = MirrorProjector(minter)
        return projector to ReadySetCell.derivedFrom(projector)
    }

    /**
     * One commit's changes to one issue. Field values are handed in as
     * [JsonElement]s exactly as the feed carries them, because
     * `MirrorProjector` stores `JsonElement.toString()` — a quoted string for
     * a `varchar` column, a bare integer for a `tinyint(1)` one
     * (READY-COVERAGE §3).
     */
    private fun record(
        height: Long,
        issue: String,
        type: DiffType?,
        vararg fields: Pair<String, JsonElement?>,
        edges: List<EdgeDiff> = emptyList(),
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height-$ordinal",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) -> FieldDiff(column, old = null, new = value) },
        edgeDiffs = edges,
    )

    /** An `ADDED` record for an ordinary open task — ready but for whatever blocks it. */
    private fun openTask(
        height: Long,
        issue: String,
        vararg extra: Pair<String, JsonElement?>,
        edges: List<EdgeDiff> = emptyList(),
    ) = record(
        height,
        issue,
        DiffType.ADDED,
        "status" to JsonPrimitive("open"),
        "issue_type" to JsonPrimitive("task"),
        *extra,
        edges = edges,
    )

    private fun blocks(issue: String, target: String, type: String = "blocks") =
        EdgeDiff(DiffType.ADDED, issue, target, type)

    private fun unblocks(issue: String, target: String, type: String = "blocks") =
        EdgeDiff(DiffType.REMOVED, issue, target, type)

    /** Everything the ready cell emitted, one entry per effective delta. */
    private fun emissions(ready: ReadySetCell): MutableList<SetDelta<String>> {
        val out = mutableListOf<SetDelta<String>>()
        ready.outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        out += value
                    }
                },
                PortRef.generate(),
            )
        )
        return out
    }

    // ------------------------------------------------------------------
    // Ex/derive-only — the join, and nothing but the two cells feeding it
    // ------------------------------------------------------------------

    @Test
    fun `ready is derived from the two cells alone - A open, B open, B blocks on A`() {
        val (projector, ready) = rig()
        val probe = NoSubprocessProbe.start()

        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))

        ready.readySet() shouldBe setOf("A")
        // A is open, so B's `blocks` edge onto it holds B out; A itself has no
        // blocking edge at all.
        probe.assertNoSubprocessSpawned()
    }

    @Test
    fun `nothing on the derivation path can spawn a process`() {
        // The structural half of the "no bd" claim, independent of any one
        // run: the compiled classes of the two packages the derivation is made
        // of name no process-spawning API at all.
        NoSubprocessProbe.assertDerivationPackagesNameNoProcessApi()
    }

    // ------------------------------------------------------------------
    // the mirrored is_blocked column is never consumed
    // ------------------------------------------------------------------

    @Test
    fun `a mirrored is_blocked=1 on an unblocked issue does not make it unready`() {
        val (projector, ready) = rig()

        // A lying denormalized column: beads' own `is_blocked` says blocked,
        // the edge set says otherwise, and the edge set is the only thing this
        // derivation reads. (Staleness of exactly this shape — the column
        // saying 1 after the blocker closed — is why BDS3 derives it.)
        projector.apply(openTask(1, "C", "is_blocked" to JsonPrimitive(1)))

        ready.readySet() shouldBe setOf("C")
    }

    @Test
    fun `a mirrored is_blocked=0 on a genuinely blocked issue does not make it ready`() {
        val (projector, ready) = rig()

        // The inverse lie: the column says clear, a live `blocks` edge onto an
        // open issue says otherwise. Derivation wins again, in the direction
        // that costs membership rather than granting it.
        projector.apply(openTask(1, "A"))
        projector.apply(
            openTask(2, "B", "is_blocked" to JsonPrimitive(0), edges = listOf(blocks("B", "A")))
        )

        ready.readySet() shouldBe setOf("A")
    }

    // ------------------------------------------------------------------
    // Ex/unblock — one delta, two membership moves
    // ------------------------------------------------------------------

    @Test
    fun `closing A in one record removes A and admits B in the same delta`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))
        ready.readySet() shouldBe setOf("A")

        val emitted = emissions(ready)
        projector.apply(record(3, "A", DiffType.MODIFIED, "status" to JsonPrimitive("closed")))

        // A leaves the status set AND B unblocks, from the one record.
        ready.readySet() shouldBe setOf("B")
        emitted.size shouldBe 1
        emitted.single().adds.keys shouldBe setOf("B")
        emitted.single().dels.keys shouldBe setOf("A")
    }

    @Test
    fun `reopening the blocker puts B back out`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))
        projector.apply(record(3, "A", DiffType.MODIFIED, "status" to JsonPrimitive("closed")))
        ready.readySet() shouldBe setOf("B")

        projector.apply(record(4, "A", DiffType.MODIFIED, "status" to JsonPrimitive("open")))

        ready.readySet() shouldBe setOf("A")
    }

    // ------------------------------------------------------------------
    // Ex/edge-remove
    // ------------------------------------------------------------------

    @Test
    fun `removing the blocking edge admits B`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))
        ready.readySet() shouldBe setOf("A")

        // an edge-only record: `diffType == null`, so the field half of the
        // projector contributes nothing and the SetDelta is the whole change.
        projector.apply(record(3, "B", null, edges = listOf(unblocks("B", "A"))))

        ready.readySet() shouldBe setOf("A", "B")
    }

    @Test
    fun `the last of two blockers leaving is what admits B`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "A2"))
        projector.apply(openTask(3, "B", edges = listOf(blocks("B", "A"), blocks("B", "A2"))))
        ready.readySet() shouldBe setOf("A", "A2")

        projector.apply(record(4, "B", null, edges = listOf(unblocks("B", "A"))))
        ready.readySet() shouldBe setOf("A", "A2") // A2 still blocks B

        projector.apply(record(5, "A2", DiffType.MODIFIED, "status" to JsonPrimitive("closed")))
        ready.readySet() shouldBe setOf("A", "B")
    }

    // ------------------------------------------------------------------
    // blocking semantics, per READY-COVERAGE §2
    // ------------------------------------------------------------------

    @Test
    fun `conditional-blocks blocks and an unlisted dep type does not`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A", "conditional-blocks"))))
        projector.apply(openTask(3, "P", edges = listOf(blocks("P", "A", "parent-child"))))

        // §2.1: `blocks` and `conditional-blocks` block; `parent-child`
        // propagation is the section's excluded half, so a parent-child edge
        // onto an open issue is inert here.
        ready.readySet() shouldBe setOf("A", "P")
    }

    @Test
    fun `a blocker whose status is the pinned enum value no longer blocks`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))

        // §2.2: an open blocker is `status <> 'closed' AND status <> 'pinned'`.
        // This `pinned` is the status enum value, not the boolean `pinned`
        // column the ready issue itself is tested on.
        projector.apply(record(3, "A", DiffType.MODIFIED, "status" to JsonPrimitive("pinned")))

        // A itself leaves (its status is outside the default ready set); B enters.
        ready.readySet() shouldBe setOf("B")
    }

    @Test
    fun `removing the blocker issue entirely unblocks its dependent`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))

        projector.apply(record(3, "A", DiffType.REMOVED))

        // A is gone from the mirror, so the edge onto it is now the dangling
        // case (§2.3) and stops blocking — same rule, reached by deletion.
        ready.readySet() shouldBe setOf("B")
    }

    // ------------------------------------------------------------------
    // Ex/dangling
    // ------------------------------------------------------------------

    @Test
    fun `an edge onto an id this mirror does not hold does not block`() {
        val (projector, ready) = rig()

        // READY-COVERAGE §2.3: beads resolves a blocking edge's target with an
        // INNER JOIN against issues/wisps, so a target that is neither — a
        // foreign or dangling `depends_on` — produces no row and does not
        // block.
        projector.apply(openTask(1, "X", edges = listOf(blocks("X", "Y"))))

        ready.readySet() shouldBe setOf("X")
    }

    @Test
    fun `a dangling target that later arrives open starts blocking`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "X", edges = listOf(blocks("X", "Y"))))
        ready.readySet() shouldBe setOf("X")

        projector.apply(openTask(2, "Y"))

        // Y is a real open issue now, so the edge that was dangling blocks.
        ready.readySet() shouldBe setOf("Y")
    }

    // ------------------------------------------------------------------
    // Ex/incrementality
    // ------------------------------------------------------------------

    @Test
    fun `one status delta re-evaluates the issue and its dependents, not the workspace`() {
        val population = 500
        val (projector, ready) = rig()

        // 500 mirrored issues; three of them depend on i-0.
        projector.apply(openTask(1, "i-0"))
        (1 until population).forEach { i ->
            val edges = if (i in 1..3) listOf(blocks("i-$i", "i-0")) else emptyList()
            projector.apply(openTask(1 + i.toLong(), "i-$i", edges = edges))
        }
        ready.readySet().size shouldBe population - 3 // i-1..i-3 are blocked by i-0

        val before = ready.evaluationCount
        projector.apply(
            record(1000, "i-0", DiffType.MODIFIED, "status" to JsonPrimitive("closed"))
        )
        val spent = ready.evaluationCount - before

        // i-0 itself plus exactly its reverse-dependency set — not the 500.
        spent shouldBe 4L
        (spent < population) shouldBe true
        ready.readySet().size shouldBe population - 1 // i-0 closed, i-1..i-3 admitted
    }

    @Test
    fun `an ordinary field edit on a blocker does not fan out to its dependents`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        projector.apply(openTask(2, "B", edges = listOf(blocks("B", "A"))))

        val before = ready.evaluationCount
        // A stays open, so its open-blocker state does not flip and B is not
        // reconsidered at all — the reverse index is consulted, never walked.
        projector.apply(record(3, "A", DiffType.MODIFIED, "priority" to JsonPrimitive(1)))

        (ready.evaluationCount - before) shouldBe 1L
        ready.readySet() shouldBe setOf("A")
    }

    // ------------------------------------------------------------------
    // emission
    // ------------------------------------------------------------------

    @Test
    fun `emission is effective-only`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        val emitted = emissions(ready)

        // a field edit that moves no membership emits nothing at all
        projector.apply(record(2, "A", DiffType.MODIFIED, "priority" to JsonPrimitive(2)))

        emitted.isEmpty() shouldBe true
        ready.readySet() shouldBe setOf("A")
    }

    // ------------------------------------------------------------------
    // computenet-vsbx: the read side, while the writer thread is folding
    // ------------------------------------------------------------------

    /**
     * [ReadySetCell.readySet] answers a **whole** value while another thread
     * drives `MirrorProjector.apply` — no `ConcurrentModificationException`,
     * and no observation of a half-applied reconciliation pass.
     *
     * ## The fixture is what makes a torn read nameable
     *
     * One blocker (`root`) and [DEPENDENTS] issues each holding a `blocks`
     * edge onto it. That is the one shape where a *single* field delta moves
     * many memberships in **one** [ReadySetCell.reconcile] pass, via the
     * reverse index: closing `root` takes `root` out and admits all
     * [DEPENDENTS] dependents; reopening it does the reverse. So the derived
     * value alternates between exactly two legal states —
     * `{root}` and `{d-0 .. d-N}` — and **every intermediate is illegal by
     * construction**: any set that is neither is a mid-pass observation, i.e.
     * a torn read. The reader asserts set *equality* against those two, so a
     * torn read is caught whether it shows a wrong size or a wrong membership
     * of the right size.
     *
     * ## Why it is not flaky
     *
     * The assertion is a safety property, not a timing one: with the fix, a
     * published snapshot is a complete post-pass state under *every*
     * interleaving, so no schedule can redden this. Nothing here waits on a
     * sleep or on a race being won — the reader spins until the writer is
     * done and the join is bounded, so a stuck thread fails as a timeout
     * rather than hanging CI.
     *
     * Against the unsynchronized version it fails immediately and for the same
     * reason on any schedule: [ROUNDS] passes each restructure ~[DEPENDENTS]
     * entries of the backing `LinkedHashMap`, while the reader copies its key
     * set thousands of times per pass. See the class KDoc's Threading section
     * for what now holds.
     */
    @Test
    fun `readySet answers a whole value while the projector is fed from another thread`() {
        val (projector, ready) = rig()

        projector.apply(openTask(1, ROOT))
        (0 until DEPENDENTS).forEach { i ->
            projector.apply(openTask(2 + i.toLong(), dependent(i), edges = listOf(blocks(dependent(i), ROOT))))
        }

        // The only two legal values, and the two the writer alternates between.
        val rootOpen = setOf(ROOT)
        val rootClosed = (0 until DEPENDENTS).mapTo(LinkedHashSet<String>(), ::dependent)
        ready.readySet() shouldBe rootOpen

        val start = CountDownLatch(1)
        val writerDone = AtomicBoolean(false)
        val observations = AtomicLong(0)
        val failures = ConcurrentLinkedQueue<String>()

        fun report(message: String) {
            if (failures.size < MAX_REPORTED_FAILURES) failures += message
        }

        val writer = Thread({
            try {
                start.await()
                var height = 1_000L
                repeat(ROUNDS) { round ->
                    val status = if (round % 2 == 0) "closed" else "open"
                    projector.apply(
                        record(height++, ROOT, DiffType.MODIFIED, "status" to JsonPrimitive(status))
                    )
                }
            } catch (t: Throwable) {
                report("writer threw ${t::class.qualifiedName}: ${t.message}")
            } finally {
                writerDone.set(true)
            }
        }, "vsbx-writer")

        val reader = Thread({
            try {
                start.await()
                while (!writerDone.get()) {
                    val seen = try {
                        ready.readySet()
                    } catch (t: Throwable) {
                        // The unsynchronized read's own failure mode: iterating
                        // the writer's LinkedHashMap while it is being restructured.
                        report("readySet() threw ${t::class.qualifiedName}: ${t.message}")
                        break
                    }
                    observations.incrementAndGet()
                    if (seen != rootOpen && seen != rootClosed) {
                        report("torn read: size ${seen.size}, neither {$ROOT} (1) nor the $DEPENDENTS dependents")
                    }
                }
            } catch (t: Throwable) {
                report("reader threw ${t::class.qualifiedName}: ${t.message}")
            }
        }, "vsbx-reader")

        writer.start()
        reader.start()
        start.countDown()
        writer.join(JOIN_TIMEOUT_MS)
        reader.join(JOIN_TIMEOUT_MS)

        writer.isAlive shouldBe false
        reader.isAlive shouldBe false
        failures.toList() shouldBe emptyList<String>()
        // The reader really ran against a moving writer rather than finishing
        // before it started: at least one read per round, by a wide margin.
        (observations.get() > ROUNDS) shouldBe true
        // And the writer really applied every flip.
        ready.readySet() shouldBe rootOpen
    }

    /**
     * The "no `bd` on the derivation path" instrument (this task's acceptance
     * clause), in two independent halves — and honest about what each one can
     * and cannot see.
     *
     * 1. [assertNoSubprocessSpawned] compares the JVM's live child processes
     *    before and after the derivation and requires the *difference* to be
     *    empty. A difference (rather than a count) is what keeps it stable
     *    against a `bd`/`dolt` child leaked by another test class in the same
     *    worker JVM: such a child is in both snapshots and cancels. Its blind
     *    spot is the converse — a subprocess that both starts and exits
     *    between the two snapshots leaves no live handle to observe. That is
     *    what half 2 is for, and it is a *measured* blind spot, not a
     *    theoretical one: a mutation adding
     *    `ProcessBuilder(listOf("bd", "version")).start().waitFor()` to
     *    `ReadySetCell.evaluate` left this half green and was caught only by
     *    half 2 (computenet-98u.1.2's mutation check).
     * 2. [assertDerivationPackagesNameNoProcessApi] reads the compiled classes
     *    of the packages the derivation is made of and requires that none of
     *    them names a process-spawning JDK API. This is exact for *direct*
     *    references and blind to a transitive one: a call into some third
     *    class that spawns would pass. Both packages depend only on `:kernel`
     *    cell types and `kotlinx.serialization`, so the residual risk is
     *    naming a new collaborator, which is a visible source change.
     *
     * Neither half is a general "no subprocess ran anywhere" assertion, and
     * neither is presented as one.
     */
    class NoSubprocessProbe private constructor(private val before: Set<Long>) {

        fun assertNoSubprocessSpawned() {
            val after = liveChildren()
            (after - before) shouldBe emptySet()
        }

        companion object {
            /** Every JDK entry point that can start an external process. */
            private val PROCESS_APIS = listOf(
                "java/lang/ProcessBuilder",
                "java/lang/Runtime",
                "java/lang/ProcessHandle",
            )

            /** The packages the ready derivation is made of, as classpath directories. */
            private val DERIVATION_PACKAGES = listOf(
                "civictech/demo/beadsmirror/ready",
                "civictech/demo/beadsmirror/projector",
            )

            fun start(): NoSubprocessProbe = NoSubprocessProbe(liveChildren())

            private fun liveChildren(): Set<Long> =
                ProcessHandle.current().children().map { it.pid() }.toList().toSet()

            fun assertDerivationPackagesNameNoProcessApi() {
                val root = classesRoot()
                val offenders = DERIVATION_PACKAGES.flatMap { pkg ->
                    val dir = root.resolve(pkg)
                    check(Files.isDirectory(dir)) { "no compiled classes for $pkg under $root" }
                    Files.walk(dir).use { paths ->
                        paths.filter { it.extension == "class" }
                            .map { path -> path to path.readBytes().decodeToString(throwOnInvalidSequence = false) }
                            .filter { (_, body) -> PROCESS_APIS.any { it in body } }
                            .map { (path, _) -> root.relativize(path).toString() }
                            .toList()
                    }
                }
                offenders shouldBe emptyList()
            }

            /**
             * The compiled-main-classes directory this module's production
             * classes were loaded from — the same bytes the derivation ran.
             */
            private fun classesRoot(): Path =
                Path.of(ReadySetCell::class.java.protectionDomain.codeSource.location.toURI())
        }
    }

    /** Fixture constants for the concurrent-read test above. */
    companion object {

        /** The one blocker whose status flip moves every membership in a single pass. */
        private const val ROOT = "root"

        /**
         * How many issues depend on [ROOT] — i.e. how many entries one
         * reconciliation pass restructures. Large enough that a mid-pass
         * observation is unmistakable and that the unsynchronized read
         * reliably trips over a rehash; small enough that the whole test is a
         * fraction of a second.
         */
        private const val DEPENDENTS = 200

        /** Status flips applied. Even, so the run ends with [ROOT] open again. */
        private const val ROUNDS = 400

        /** Enough evidence to diagnose a failure without drowning the report. */
        private const val MAX_REPORTED_FAILURES = 8

        /** Bounded so a wedged thread fails the test instead of hanging CI. */
        private const val JOIN_TIMEOUT_MS = 60_000L

        private fun dependent(i: Int) = "d-$i"
    }
}
