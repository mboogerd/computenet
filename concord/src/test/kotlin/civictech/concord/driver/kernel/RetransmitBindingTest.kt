package civictech.concord.driver.kernel

import civictech.cell.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.concord.driver.CellId
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The `retransmit` binding's own tests (`computenet-yh6.1.8`) — the driver half
 * of the gated schema change `computenet-yh6.1.3.3` froze in
 * `concord/schema/scenario.md` (`#### retransmit`).
 *
 * Two things need pinning here rather than in the corpus. First, **which
 * counter a scenario must name**: a corpus scenario states an explicit
 * `(source, counter)` position, and if that literal were off by one the
 * scenario would still pass — a position *behind* the frontier is suppressed
 * just as one *at* it is — while duplicating a coordinate nothing ever emitted.
 * So the mapping "the Nth `add` on a journaled source mints counter N" is
 * asserted behaviourally: the position one past the last add is shown to
 * **fire**, which fixes the frontier's high-water exactly and grounds the
 * literals `DUR-LIVE-01` and `DUR-CKPT-FRONTIER-01` use.
 *
 * Second, the binding's **refusals**. Each is a loud failure by design (see
 * [KernelDriverDur.retransmit]); a corpus scenario cannot exercise one, because
 * a scenario that provoked it would simply error rather than assert anything.
 */
class RetransmitBindingTest {

    private val budget = 5_000_000

    private fun s(v: String): Value = Value.StrVal(v)

    /** The `dur` shape both corpus scenarios use: a journaled source into an effect sink, plus the crash handle. */
    private fun durGraph(): KernelDriver = KernelDriver(0L).also { d ->
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "sink", "effect-sink", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
        d.connect("source", "sink", null, null, null)
    }

    private fun KernelDriver.keys(): List<String?> = effectLog("sink").map { it.key }

    @Test
    fun `a live duplicate at the restored frontier is suppressed, and the next position fires`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.apply("source", "add", s("k2"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // crash + recoverFrom: the replayed frames are suppressed at the restored
        // frontier (the half DUR-REPLAY-01 already covers)
        d.despawn("ctl")
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // LIVE duplicate at the frontier: suppressed by the same rule replay uses
        // ([24-DUR-05]'s "or post-recovery live delivery").
        d.retransmit("sink", null, "source", 2, "add", s("k2"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // One position PAST the frontier fires. This is what makes the literal
        // `counter: 2` above meaningful: the source's second add really did mint
        // counter 2, because counter 3 is not yet on the frontier.
        d.retransmit("sink", null, "source", 3, "add", s("ahead"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2", "ahead")

        // ...and re-delivering that position now finds it on the frontier, so the
        // live fire above genuinely ADVANCED it rather than dodging a stale check.
        d.retransmit("sink", null, "source", 3, "add", s("ahead"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2", "ahead")
    }

    /**
     * The optional baseline anchor (`computenet-yh6.1.12`), pinned as a
     * *contrast* rather than as an assertion about the anchor's contents. Two
     * retransmits identical in every field but one — a position ahead of the
     * frontier, delivered with and without `baseline:` — and the difference the
     * step's presence makes is exactly `[24-DUR-07]`'s rule: an ordinary live
     * delivery ADVANCES the processed-frontier, a baseline delivery does not.
     *
     * The omitted-anchor arm is the optionality evidence at the binding: it is
     * the same behaviour this file already pinned before the parameter existed
     * (`a live duplicate at the restored frontier is suppressed…`), re-asserted
     * against the widened signature.
     */
    @Test
    fun `an omitted baseline anchor advances the frontier, a stated one does not`() {
        val withoutAnchor = durGraph()
        withoutAnchor.retransmit("sink", null, "source", 9, "add", s("plain"))
        withoutAnchor.quiesce(budget)
        withoutAnchor.keys() shouldBe listOf("plain")
        // it was a live frame, so the frontier is now at counter 9: everything at
        // or behind it is suppressed
        withoutAnchor.retransmit("sink", null, "source", 4, "add", s("below"))
        withoutAnchor.quiesce(budget)
        withoutAnchor.keys() shouldBe listOf("plain")

        val withAnchor = durGraph()
        withAnchor.retransmit("sink", null, "source", 9, "add", s("plain"), mapOf("source" to 3L))
        withAnchor.quiesce(budget)
        withAnchor.keys() shouldBe listOf("plain")
        // a baseline is anchored at a link-install event, not at a wave position:
        // the frontier never moved, so a genuine live frame BELOW it still fires
        withAnchor.retransmit("sink", null, "source", 4, "add", s("below"))
        withAnchor.quiesce(budget)
        withAnchor.keys() shouldBe listOf("plain", "below")
    }

    @Test
    fun `a baseline anchor naming a cell this driver does not hold is refused`() {
        val d = durGraph()

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", null, "source", 9, "add", s("k1"), mapOf("nosuch" to 1L))
        }
        refused.message!! shouldContain "retransmit baseline names 'nosuch'"
    }

    @Test
    fun `a baseline anchor naming a cell with no outlet identity is refused`() {
        val d = durGraph()
        d.spawn(KernelDriverDur.DUR_HOST, "view", "journal-set-view", emptyMap())

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", null, "source", 9, "add", s("k1"), mapOf("view" to 1L))
        }
        refused.message!! shouldContain "no per-source identity a merge-tag frontier could be anchored on"
    }

    /**
     * The refusal `computenet-j2x.4.6` **narrowed** rather than removed. A duplicate
     * needs something that decides whether to act on it twice, and a core cell has
     * no such decision — neither an `Effectful` processed-frontier (dur) nor a
     * replication mesh's dot algebra (dist). Both halves are pinned here:
     *
     * - a `set-view`, which has no [civictech.cell.data.Replicable.deltaInlet] at
     *   all, and
     * - a `set-source`, which HAS one and is still refused, because a cell that was
     *   never placed in a mesh (`replica-of`) has no already-gossiped delta for a
     *   re-delivery to duplicate. "Replicable" is not the gate; "replica" is.
     */
    @Test
    fun `a retransmit at a core cell fails loudly rather than injecting an unobserved delivery`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("a", "v", null, null, null)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("v", null, "a", 1, "add", s("x"))
        }
        refused.message!! shouldContain "would assert nothing"

        // the Replicable-but-unreplicated half: a deltaInlet is not enough
        val atSource = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("a", null, "a", 1, "add", s("x"))
        }
        atSource.message!! shouldContain "would assert nothing"
    }

    // ------------------------------------------------------------------
    // dist profile: a duplicate at a replica's gossip inlet (computenet-j2x.4.6,
    // retiring `[KE1-37]`). The corpus half is `42-TMAP-REPL-01`, which asserts
    // the re-emission count directly via its two `emission-count` checks
    // (`concord/schema/scenario.md` §checks). This driver-level pin stays as a
    // second, lower-level witness alongside those checks — it keeps its
    // interest-empty replica as the control that a zero count is termination
    // and not a dropped injection.
    // ------------------------------------------------------------------

    private fun kv(key: String, value: String): Value = Value.ListVal(listOf(s(key), s(value)))

    /** Two `ormap-source` replicas of one logical map, one per host — `42-TMAP-REPL-01`'s shape. */
    private fun mesh(interest: Value? = null): KernelDriver = KernelDriver(0L).also { d ->
        d.spawn("h1", "r1", "ormap-source", mapOf("replica-of" to Value.StrVal("shared")))
        d.spawn(
            "h2", "r2", "ormap-source",
            buildMap {
                put("replica-of", Value.StrVal("shared"))
                interest?.let { put("interest", it) }
            },
        )
    }

    /** Count [cellId]'s outlet emissions from now on — an Observe-role tap, so it gates nothing. */
    private fun KernelDriver.countEmissions(cellId: CellId): () -> Int {
        @Suppress("UNCHECKED_CAST")
        val outlet = PortRegistry.of(cells.getValue(cellId).cell)["outlet"] as FanOutlet<Propagate<Any>>
        var n = 0
        outlet.tap(Use.fixed(Propagate<Any> { n++ }, PortRef.generate()), negotiated = false)
        return { n }
    }

    /**
     * The property `[KE1-33]`'s duplicate-delivery half asks for, stated as a
     * **count**: re-delivering a dot the receiving replica already holds re-emits
     * NOTHING (`novelty` reduces it to null, so `absorb`/`originate` never run).
     *
     * The control is the same call at a replica that has *not* seen the dot — an
     * `interest: {empty: true}` replica the gossip linker never ships to — where
     * the identical injection DOES re-emit exactly once. Without it, a zero count
     * would be equally consistent with the injection never arriving at all.
     */
    @Test
    fun `a duplicate at a replica's gossip inlet re-emits nothing, where a first arrival re-emits once`() {
        val d = mesh()
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)
        // the dot really did cross the mesh: both replicas fold it
        d.readView("r2") shouldBe d.readView("r1")

        val reEmissions = d.countEmissions("r2")
        d.retransmit("r2", null, "r1", 1, "put", kv("k1", "v1"))
        d.quiesce(budget)

        reEmissions() shouldBe 0
        d.deadLetters() shouldBe emptyList()
        d.readView("r2") shouldBe d.readView("r1")

        // control: the same dot, the same coordinates, at a replica that never
        // received it — echo termination has nothing to terminate, so it re-emits.
        val unseen = mesh(Value.MapVal(mapOf("empty" to Value.BoolVal(true))))
        unseen.apply("r1", "put", kv("k1", "v1"))
        unseen.quiesce(budget)
        val controlEmissions = unseen.countEmissions("r2")
        unseen.retransmit("r2", null, "r1", 1, "put", kv("k1", "v1"))
        unseen.quiesce(budget)
        controlEmissions() shouldBe 1
    }

    @Test
    fun `a retransmit naming a position the source never minted is refused`() {
        val d = mesh()
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("r2", null, "r1", 7, "put", kv("k1", "v1"))
        }
        refused.message!! shouldContain "has minted no such dot"
    }

    @Test
    fun `a retransmit whose op-value does not describe the recorded dot is refused`() {
        val d = mesh()
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("r2", null, "r1", 1, "put", kv("k1", "wrong"))
        }
        refused.message!! shouldContain "does not describe the dot"
    }

    @Test
    fun `a retransmit at a replica naming itself as source is refused`() {
        val d = mesh()
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("r1", null, "r1", 1, "put", kv("k1", "v1"))
        }
        refused.message!! shouldContain "names itself as source"
    }

    @Test
    fun `a retransmit at a replica naming the write inlet, or a baseline anchor, is refused`() {
        val d = mesh()
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)

        val wrongInlet = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("r2", "inlet", "r1", 1, "put", kv("k1", "v1"))
        }
        wrongInlet.message!! shouldContain "deltaInlet"

        val anchored = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("r2", null, "r1", 1, "put", kv("k1", "v1"), mapOf("r1" to 1L))
        }
        anchored.message!! shouldContain "nothing consults"
    }

    @Test
    fun `a retransmit at a durable cell that is not an effect sink is refused`() {
        val d = KernelDriver(0L)
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "view", "journal-set-view", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
        d.connect("source", "view", null, null, null)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("view", null, "source", 1, "add", s("k1"))
        }
        refused.message!! shouldContain "tag identity"
    }

    @Test
    fun `a retransmit naming an inlet the durable delta port is not called is refused`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", "other", "source", 1, "add", s("k1"))
        }
        refused.message!! shouldContain "the durable delta port is 'inlet'"
    }

    @Test
    fun `a retransmit carrying an op an effect boundary cannot act on is refused`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", null, "source", 1, "remove", s("k1"))
        }
        refused.message!! shouldContain "retransmit op 'remove' unbound"
    }
}
