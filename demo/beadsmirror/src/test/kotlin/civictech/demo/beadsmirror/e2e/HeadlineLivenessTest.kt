package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.projector.MirrorEdge
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Feature computenet-7em.3's headline test: the epic's own claim, stated as
 * one executable assertion rather than a demo script — "node B's view
 * reflects node A's beads mutation while B's replica has seen no bd sync".
 *
 * Reuses the two-node rig from computenet-7em.1
 * ([TwoNodeRig], [TwoNodeRig.Node.logHead], [TwoNodeRig.Node.view],
 * [TwoNodeRig.Node.edgeView], [TwoNodeRig.Node.servedStatus],
 * [TwoNodeRig.Node.exportNow], [TwoNodeRig.Node.quiesce], [TwoNodeRig.await])
 * unedited — this file adds no rig capability, only the assertions the epic's
 * headline needs.
 *
 * **The fixture waits, the tests assert** (same split as [TwoNodeRigTest]):
 * [setUp] builds the rig, seeds two issues on the listener for the edge case,
 * and records the dialer's `dolt_log` head **once**, before either mutation —
 * the head recorded before EITHER mutation is the one both `@Test`s check
 * against, per the bead's design.
 *
 * Not here (non-goals): latency/benchmark numbers — this is an ordering
 * claim, not a millisecond one; edits to [TwoNodeRigTest] or its assertions;
 * `bd dolt remote add`/`pull` anywhere; the convergence suite; the two-JVM
 * variant.
 *
 * Guarded like every other real-`bd` test in this module: green-but-skipped
 * where `bd`/`dolt` are not on `PATH` (CI installs neither — computenet-7em.5
 * is the sibling item addressing that on the CI side), a real gate on a
 * developer machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadlineLivenessTest {

    private var rig: TwoNodeRig? = null

    /** Seeded on the listener before either mutation, for the edge case ([x] depends on [y]). */
    private lateinit var x: String
    private lateinit var y: String

    /** The dialer's `dolt_log` head, recorded once the rig is idle and before EITHER mutation below. */
    private lateinit var dialerHeadBeforeMutations: List<String>

    /** The headline mutation: a content create on the listener's workspace. */
    private lateinit var idHeadline: String

    @BeforeAll
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")

        val rig = TwoNodeRig.create("bds2-headline").also { this.rig = it }

        val listener = rig.startListener()
        listener.quiesce()

        // seeded on the listener, before the dialer joins — arrival on the
        // dialer is setup for the edge case below, not itself the assertion.
        x = rig.listenerWorkspace.createIssue("x")
        y = rig.listenerWorkspace.createIssue("y")

        val dialer = rig.startDialer()
        dialer.quiesce()

        rig.await("both seeded issues reach the dialer's view") {
            dialer.view().containsKey(x) && dialer.view().containsKey(y)
        }

        // belt-and-braces window guard: neither workspace has a dolt remote
        // configured. Asserting absence only — no bd dolt remote add/pull
        // anywhere in this file; the re-baseline sibling (computenet-7em.4)
        // owns remotes in its own tests.
        remotesOf(rig.listenerWorkspace).shouldBeEmpty()
        remotesOf(rig.dialerWorkspace).shouldBeEmpty()

        // the head recorded here covers BOTH mutations below — one recording,
        // taken while idle, before either the headline create or the dep add.
        dialerHeadBeforeMutations = dialer.logHead()

        // --- the headline mutation: content -------------------------------
        idHeadline = rig.listenerWorkspace.createIssue("headline")
        rig.await("the headline create reaches the dialer's served fold") {
            dialer.servedStatus(idHeadline) == 200
        }
        rig.await("the headline create reaches the dialer's view") {
            dialer.view().containsKey(idHeadline)
        }

        // --- the structure mutation: a dependency edge ---------------------
        rig.listenerWorkspace.run("dep", "add", x, y, "--type", "blocks")
        rig.await("the dependency edge reaches the dialer's edge view") {
            dialer.edgeView().contains(MirrorEdge(x, y, "blocks"))
        }
    }

    @AfterAll
    fun tearDown() {
        rig?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /**
     * Feature rule 1 (headline) AND rule 2's content half: the create on the
     * listener's workspace reaches the dialer's view and served fold — not
     * merely as a presence key but as the field value itself, the created
     * issue's `title` — while the dialer's OWN `dolt_log` head is
     * byte-identical to what it was before the create, and the dialer's own
     * `bd export` has never heard of the issue. Only the cell delta crossed —
     * no bd-level sync moved anything to node B.
     *
     * The stored value is `bd`'s own JSON-quoted string, matching the
     * convention every other field-value assertion in this module uses (see
     * `RebaselineTest.kt`'s `view().getValue("B")["title"] shouldBe
     * "\"Beta\""`) — not the bare title text.
     */
    @Test
    fun `a bd mutation on the listener's workspace reaches the dialer's fold while the dialer's dolt head is unchanged`() {
        val dialer = rigOrFail.dialer

        dialer.servedStatus(idHeadline) shouldBe 200
        dialer.view().keys.contains(idHeadline) shouldBe true
        dialer.view().getValue(idHeadline)["title"] shouldBe "\"headline\""

        dialer.logHead() shouldBe dialerHeadBeforeMutations
        dialer.exportNow().map { it.id }.contains(idHeadline) shouldBe false
    }

    /**
     * Feature rule 2's structure half: a dependency edge added on the
     * listener's workspace appears in the dialer's edge view while the
     * dialer's head is still the one recorded before either mutation — the
     * same head the content case checks, since both mutations landed after
     * one single recording.
     */
    @Test
    fun `a dep edge added on the listener's workspace reaches the dialer's edge view while its dolt head is unchanged`() {
        val dialer = rigOrFail.dialer

        dialer.edgeView() shouldBe setOf(MirrorEdge(x, y, "blocks"))
        dialer.logHead() shouldBe dialerHeadBeforeMutations
    }

    /**
     * Rule 1's belt-and-braces window guard, restated as its own assertion so
     * a reader does not have to trust the fixture's inline check alone: while
     * the assertion window is open (both `@Test`s above read live state from
     * this same rig instance), neither workspace has a dolt remote
     * configured — the mutations above could not have crossed by a `bd dolt
     * pull` even in principle.
     */
    @Test
    fun `neither workspace has a dolt remote configured during the assertion window`() {
        remotesOf(rigOrFail.listenerWorkspace).shouldBeEmpty()
        remotesOf(rigOrFail.dialerWorkspace).shouldBeEmpty()
    }

    private fun remotesOf(workspace: BdScratchWorkspace): List<Map<String, JsonElement>> =
        DoltSql(workspace.doltRoot).query("select * from dolt_remotes")

    private companion object {
        fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
