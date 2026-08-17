package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.BdSyncedWorkspacePair
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.equality.Divergence
import civictech.demo.beadsmirror.equality.MirrorExportEquality
import civictech.demo.beadsmirror.projector.MirrorEdge
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task computenet-7em.4.3 — feature computenet-7em.4's whole scenario, end to
 * end, over two running mirrors gossiping through a real `:wire` socket, with
 * a **real** `bd dolt pull` landing peer commits in one mirror's workspace
 * while its poll loop is live.
 *
 * **The rig.** [TwoNodeRig] on [BdScratchWorkspace.createSyncedPair]'s
 * pusher/puller pair (task computenet-7em.4.2), assigned **listener = pusher
 * (WL), dialer = puller (WD)** — so the node that pulls, and therefore the
 * node that re-baselines, is the dialer. Nothing in the scenario depends on
 * which end of the socket dialled; the assignment is fixed here only so every
 * assertion below can name one node.
 *
 * **What each `@Test` owns** (the fixture does all the waiting; each test
 * states one rule against a recorded or still-live value, per this package's
 * convention):
 *
 * 1. *Detection* — WD's captured [MirrorEvent.Rebaselined] carries
 *    [RebaselineReason.HistoryMerged], its poll loop is alive afterwards, and
 *    its checkpoint reached the post-merge head. The signal is **typed**: read
 *    from [TwoNodeRig.Node.events], never inferred from log prose or from a
 *    fold that happened to change.
 * 2. *Union equality* — both nodes' folds equal `union(export(WL),
 *    export(WD))`, dependency edges included, through
 *    [MirrorExportEquality.compare] with zero divergences.
 * 3. *No flicker* — every sampled observation of WD's served fold, across the
 *    whole re-baseline window, shows `a1`'s **post-edit** title.
 *
 * **The no-flicker claim is a sampled one, and says only what sampling can
 * say.** [servedFoldSamples] is a continuous read loop over `GET
 * /beads/issues`, started before the pull and stopped once the typed event was
 * observed. It cannot prove "no observation point ever showed the stale
 * value" — only "no sample did". What keeps it from passing vacuously is
 * [samplesInsideWindow]: the assertion demands samples taken strictly between
 * issuing the pull and observing the re-baseline, so a sampler that never ran
 * during the window fails rather than passing on an empty set.
 *
 * **Feature rule 4 (export failure during the re-baseline) is deliberately
 * NOT re-proven here.** The app's `bd export` is not injectable through the
 * rig — [civictech.demo.beadsmirror.BeadsMirrorApp] builds its own reader —
 * so the failing-export case is asserted at the seam that does own it, task
 * computenet-7em.4.1's `RebaselineTest`, with a throwing `export` lambda under
 * this very reason. Its absence here is a delegation, not an omission.
 *
 * No `assumeTrue` toolchain guard, on purpose: CI installs `bd` and `dolt` on
 * both lanes (computenet-7em.5, PR #294) and a skipped `:demo:beadsmirror`
 * suite turns the required check RED.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PullRebaselineTest {

    private var pair: BdSyncedWorkspacePair? = null
    private var rig: TwoNodeRig? = null

    /** Created on WL (the pusher/listener); the issue whose title is edited and whose edge is asserted. */
    private lateinit var a1: String

    /** Created on WL too — the far side of `a1 -> a2`, so union equality has an edge to exercise. */
    private lateinit var a2: String

    /** Created on WD (the puller/dialer) — the content the pusher's own export never holds. */
    private lateinit var b1: String

    /** `a1`'s title before the edit, in the fold's own storage rendering (bd's JSON-quoted string). */
    private val titleBefore = "\"a1 before the edit\""

    /** `a1`'s title after the edit — the value every sample of WD's served fold must show. */
    private val titleAfter = "\"a1 after the edit\""

    /** WD's served fold, sampled continuously from just before the pull until the typed event was observed. */
    private val servedFoldSamples: MutableList<Sample> = Collections.synchronizedList(mutableListOf<Sample>())

    /** `System.nanoTime()` immediately before `bd dolt pull` was invoked on WD. */
    private var pullStartedAt: Long = 0

    /** `System.nanoTime()` at which WD's typed [RebaselineReason.HistoryMerged] event was first observed. */
    private var rebaselineObservedAt: Long = 0

    /** WD's `dolt_log` head after the pull — the merge commit the re-baseline must checkpoint at. */
    private lateinit var postMergeHead: String

    /** Whatever `bd dolt pull` printed — carried into the failure message if the race turns out not to be safe. */
    private lateinit var pullOutput: String

    private lateinit var listenerDivergences: List<Divergence>
    private lateinit var dialerDivergences: List<Divergence>

    @BeforeAll
    fun setUp() {
        val pair = BdScratchWorkspace.createSyncedPair().also { this.pair = it }
        val rig = TwoNodeRig.create(
            "bds2-pull-rebaseline",
            listenerWorkspace = pair.pusher,
            dialerWorkspace = pair.puller,
        ).also { this.rig = it }

        // --- Given: content on both sides, gossiped both ways ---------------
        a1 = pair.pusher.createIssue("a1 before the edit")
        a2 = pair.pusher.createIssue("a2, the far side of a1's edge")
        pair.pusher.run("dep", "add", a1, a2, "--type", "blocks")
        b1 = pair.puller.createIssue("b1 on the puller")

        val listener = rig.startListener()
        listener.quiesce()
        val dialer = rig.startDialer()
        dialer.quiesce()

        rig.await("a1 and b1 reach BOTH nodes' folds over the socket") {
            listener.view().containsKey(b1) && dialer.view().containsKey(a1) && dialer.view().containsKey(a2)
        }

        // The edit is one-sided by construction — only WL ever writes a1 —
        // which is what makes "the two exports agree on every shared id"
        // (union() below) a fact rather than a hope.
        pair.pusher.run("update", a1, "--title", "a1 after the edit")
        rig.await("the title edit gossips to the dialer's fold") {
            dialer.view()[a1]?.get("title") == titleAfter
        }
        listener.quiesce()

        // --- When: WL pushes, WD pulls while its poll loop is live ----------
        // The rig polls every 200ms, so a pull issued here lands mid-loop with
        // no special interleaving.
        pair.push()

        val sampling = AtomicBoolean(true)
        val sampler = Thread({
            while (sampling.get()) {
                val body = runCatching { dialer.servedFold() }.getOrElse { "unreadable: $it" }
                servedFoldSamples += Sample(System.nanoTime(), body)
            }
        }, "pull-rebaseline-served-fold-sampler").apply { isDaemon = true; start() }

        try {
            pullStartedAt = System.nanoTime()
            pullOutput = pair.pull()

            // (a) detection, as a TYPED event — the whole point of 7em.4.1.
            rig.await("the dialer reports a merge-reason re-baseline through onEvent") {
                dialer.mergeRebaselines().isNotEmpty()
            }
            rebaselineObservedAt = System.nanoTime()
        } finally {
            sampling.set(false)
            sampler.join()
        }

        // --- Then: the rig settles, and both folds are compared -------------
        // Read genesis-first through the feed's own log reader, so "head" is
        // the same commit the re-baseline itself captured rather than a
        // guess about dolt_log's default row order.
        postMergeHead = DoltCommitFeed(pair.puller.doltRoot).history().last()
        dialer.quiesce()
        rig.await("b1 is still on both nodes, and a1's edit survived the re-baseline at the dialer") {
            listener.view()[a1]?.get("title") == titleAfter &&
                dialer.view()[a1]?.get("title") == titleAfter &&
                listener.view().containsKey(b1) && dialer.view().containsKey(b1)
        }

        val union = union(listener.exportNow(), dialer.exportNow())
        rig.await("both folds carry every issue of the union") {
            union.all { listener.view().containsKey(it.id) && dialer.view().containsKey(it.id) }
        }
        listenerDivergences = MirrorExportEquality.compare(listener.view(), listener.edgeView(), union)
        dialerDivergences = MirrorExportEquality.compare(dialer.view(), dialer.edgeView(), union)
    }

    @AfterAll
    fun tearDown() {
        rig?.close()
        pair?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /**
     * Feature rule 1, end to end (its ownership stays with computenet-7em.4.1's
     * unit-level assertions): a real `bd dolt pull` into a running mirror's
     * workspace is observed as [MirrorEvent.Rebaselined] carrying
     * [RebaselineReason.HistoryMerged] — naming the merge commit — the poll
     * loop survives it, and the checkpoint it leaves behind is the post-merge
     * head, i.e. the fold now served derives from a `bd export` taken after
     * the merge.
     *
     * This also settles the feature's one `unverified:` premise: a `bd dolt
     * pull` (an embedded-server write) racing this module's `dolt` CLI reads.
     * If that race were unsafe, the pull would wedge or the poller would carry
     * a failure — both are asserted here rather than assumed.
     */
    @Test
    fun `a real bd dolt pull is observed as a typed merge re-baseline and the poll loop survives it`() {
        val dialer = rigOrFail.dialer

        val merges = dialer.mergeRebaselines()
        merges.size shouldBe 1
        val reason = merges.single().reason as RebaselineReason.HistoryMerged
        reason.mergeCommit shouldBe postMergeHead
        merges.single().headCommit shouldBe postMergeHead

        dialer.app.pollerFailure shouldBe null
        pullOutput.contains("Pull complete") shouldBe true
    }

    /**
     * Feature rule 2: post-re-baseline, listener fold == dialer fold ==
     * `union(export(WL), export(WD))`, dependency edges included, with zero
     * divergences on either node.
     *
     * The union is built by id over both workspaces' `bd export` rows and
     * [union] *fails* rather than picking a side if the two disagree on a
     * shared id — the scenario keeps every edit one-sided, so a disagreement
     * would mean the premise, not the fold, is what broke.
     */
    @Test
    fun `both folds equal the union of the two workspaces' exports, dependency edges included`() {
        listenerDivergences.shouldBeEmpty()
        dialerDivergences.shouldBeEmpty()

        // Stated separately from the divergence list so the edge half of the
        // claim is visible as its own assertion rather than only as an absence.
        rigOrFail.listener.edgeView() shouldBe setOf(MirrorEdge(a1, a2, "blocks"))
        rigOrFail.dialer.edgeView() shouldBe setOf(MirrorEdge(a1, a2, "blocks"))
    }

    /**
     * Feature rule 3: across the whole re-baseline window, no sampled
     * observation of WD's served fold showed `a1`'s **pre-edit** title — the
     * re-baseline re-mints dots for a row WD already held via gossip, and that
     * must never surface as a flicker back to the stale value.
     *
     * Sampling cannot prove "never" exhaustively: this asserts over the
     * samples actually taken, and guards the vacuous case by demanding the
     * samples straddle the window — [samplesInsideWindow] counts observations
     * taken strictly between issuing the pull and seeing the typed event.
     */
    @Test
    fun `no sampled observation of the dialer's served fold ever showed the pre-edit title`() {
        val samples = servedFoldSamples.toList()
        samples.size shouldBeGreaterThan 0
        samplesInsideWindow(samples) shouldBeGreaterThan 0

        val stale = samples.filter { titleOfA1(it.body) == titleBefore }
        stale.map { it.body }.shouldBeEmpty()

        // Not merely "never stale" — the fold was actually readable and
        // carrying a1 during the window, so the absence above is an absence of
        // the stale value rather than an absence of any value at all.
        samples.count { titleOfA1(it.body) == titleAfter } shouldBeGreaterThan 0
    }

    /** One observation of the served fold: when it was taken, and the response body verbatim. */
    private data class Sample(val at: Long, val body: String)

    private fun samplesInsideWindow(samples: List<Sample>): Int =
        samples.count { it.at in pullStartedAt..rebaselineObservedAt }

    /** `a1`'s stored `title` rendering in a `GET /beads/issues` body, or `null` if the body has no `a1`. */
    private fun titleOfA1(body: String): String? {
        val issues = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return (issues[a1] as? JsonObject)?.get("title")?.toString()
    }

    /** Every [MirrorEvent.Rebaselined] this node reported under the merge reason. */
    private fun TwoNodeRig.Node.mergeRebaselines(): List<MirrorEvent.Rebaselined> =
        events().filterIsInstance<MirrorEvent.Rebaselined>().filter { it.reason is RebaselineReason.HistoryMerged }

    /**
     * `union(export(WL), export(WD))` by issue id. Rows sharing an id must be
     * *identical* — every edit in this scenario is one-sided, so a difference
     * means the fixture's premise broke and there is no single row a fold
     * could be compared against. Fail loudly rather than pick a side.
     */
    private fun union(left: List<ExportRow>, right: List<ExportRow>): List<ExportRow> {
        val byId = LinkedHashMap<String, ExportRow>()
        left.forEach { byId[it.id] = it }
        right.forEach { row ->
            val existing = byId[row.id]
            check(existing == null || existing.json == row.json) {
                "the two workspaces' exports disagree on issue ${row.id}, so no single union row exists:\n" +
                    "  listener: ${existing?.json}\n  dialer:   ${row.json}"
            }
            byId[row.id] = row
        }
        return byId.values.toList()
    }
}
