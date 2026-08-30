package civictech.concord.driver.kernel

import civictech.cell.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.concord.driver.CellId
import civictech.concord.value.Value
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The kernel binding of `Driver.emissionCount` (`computenet-i37v`), at both ends
 * of the contract `concord/schema/scenario.md` §`emission-count` states.
 *
 * The corpus half (`42-TMAP-REPL-01`'s two `emission-count` checks) exercises the
 * HAPPY path only, and it exercises it through the same counter it is asserting —
 * so a binding whose count were structurally always zero would make every
 * `exactly: 0` in the corpus pass for the wrong reason with nothing going red.
 * Two things close that, and neither is expressible in a scenario:
 *
 * - the count is cross-checked against a **second, independently installed**
 *   Observe-role tap over the same mesh, and against an `interest: {empty: true}`
 *   control that forces a real re-emission — so a zero is demonstrably echo
 *   termination rather than a dead counter;
 * - the **refusal** path, which a scenario cannot reach at all: a scenario naming
 *   an unobservable cell errors rather than asserting anything. The spec is
 *   explicit that a driver which cannot observe the named cell's outlet MUST fail
 *   loudly rather than answer `0`, because the difference of two zeroes is zero —
 *   precisely what an `exactly: 0` check accepts.
 *
 * Both refusal sites are pinned, because they are two distinct guards:
 * [KernelDriver.emissionCount] refuses any cell outside `distReplicas`, and
 * [KernelDriverDist.emissionCount] refuses any cell it never tapped.
 */
class EmissionCountBindingTest {

    private val budget = 5_000_000

    private fun s(v: String): Value = Value.StrVal(v)

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

    /**
     * A SECOND emission tap on [cellId]'s outlet, installed independently of the
     * binding's own (`recordEmissionsOf`, at spawn) and counting from now on. It
     * is Observe-role like the binding's, so it gates nothing and perturbs no
     * wave; its only job is to be a witness the binding does not share state with.
     */
    private fun KernelDriver.independentTap(cellId: CellId): () -> Long {
        @Suppress("UNCHECKED_CAST")
        val outlet = PortRegistry.of(cells.getValue(cellId).cell)["outlet"] as FanOutlet<Propagate<Any>>
        var n = 0L
        outlet.tap(Use.fixed(Propagate<Any> { n++ }, PortRef.generate()), negotiated = false)
        return { n }
    }

    @Test
    fun `the reported count tracks an independently installed tap, and a zero window is echo termination`() {
        val d = mesh()
        val independent = d.independentTap("r2")
        val atInstall = d.emissionCount("r2")

        // A real emission at r2: the dot crosses the mesh and the receiving replica
        // re-emits its fold downstream.
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)
        d.readView("r2") shouldBe d.readView("r1")

        val afterFirst = d.emissionCount("r2") - atInstall
        // agreement with a counter the binding shares nothing with — a count that
        // were structurally always zero could not survive this line
        afterFirst shouldBe independent()
        afterFirst shouldBeGreaterThan 0L

        // The zero window: re-delivering a dot r2 already holds re-emits NOTHING.
        val beforeDuplicate = d.emissionCount("r2")
        val independentBeforeDuplicate = independent()
        d.retransmit("r2", null, "r1", 1, "put", kv("k1", "v1"))
        d.quiesce(budget)
        (d.emissionCount("r2") - beforeDuplicate) shouldBe 0L
        (independent() - independentBeforeDuplicate) shouldBe 0L
        d.deadLetters() shouldBe emptyList()

        // The control that makes that zero mean something: the identical injection
        // at a replica the gossip linker never shipped to (`interest: {empty: true}`)
        // is a FIRST arrival, so it re-emits exactly once. Without this arm a zero
        // would be equally consistent with a counter that never counts.
        val unseen = mesh(Value.MapVal(mapOf("empty" to Value.BoolVal(true))))
        val unseenIndependent = unseen.independentTap("r2")
        unseen.apply("r1", "put", kv("k1", "v1"))
        unseen.quiesce(budget)

        val beforeControl = unseen.emissionCount("r2")
        val unseenIndependentBefore = unseenIndependent()
        unseen.retransmit("r2", null, "r1", 1, "put", kv("k1", "v1"))
        unseen.quiesce(budget)
        (unseen.emissionCount("r2") - beforeControl) shouldBe 1L
        (unseenIndependent() - unseenIndependentBefore) shouldBe 1L
    }

    /**
     * [KernelDriver.emissionCount]'s guard: anything outside `distReplicas` is
     * refused rather than answered `0`. A `set-source` and a `set-view` in a core
     * graph, plus an id this driver never spawned at all.
     */
    @Test
    fun `emissionCount at a cell outside the replication mesh fails loudly rather than answering zero`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("a", "v", null, null, null)
        d.apply("a", "add", s("x"))
        d.quiesce(budget)

        // a source that demonstrably DID emit — the refusal is about observability,
        // not about there being nothing to count
        val atSource = assertThrows<UnsupportedCatalogBinding> { d.emissionCount("a") }
        atSource.message!! shouldContain "no other cell's outlet is observed"

        val atView = assertThrows<UnsupportedCatalogBinding> { d.emissionCount("v") }
        atView.message!! shouldContain "no other cell's outlet is observed"

        val unknown = assertThrows<UnsupportedCatalogBinding> { d.emissionCount("nosuch") }
        unknown.message!! shouldContain "no other cell's outlet is observed"
    }

    /**
     * [KernelDriverDist.emissionCount]'s own guard, which the outer guard shadows:
     * a cell the dist binding never placed in a mesh has no tap, so it is refused.
     * Reached directly, because `KernelDriver.emissionCount` gates on the same set
     * and would never forward such a cell — the guard is nonetheless the one that
     * makes the *dist* half of the contract true, and it is separately mutable.
     */
    @Test
    fun `the dist binding refuses a cell it never tapped rather than answering zero`() {
        val d = mesh()
        d.spawn("h1", "plain", "set-source", emptyMap())
        d.apply("r1", "put", kv("k1", "v1"))
        d.quiesce(budget)

        // the tapped replica answers
        d.dist.emissionCount("r2") shouldBeGreaterThan 0L

        val untapped = assertThrows<UnsupportedCatalogBinding> { d.dist.emissionCount("plain") }
        untapped.message!! shouldContain "no Observe-role emission tap is installed on it"

        val unknown = assertThrows<UnsupportedCatalogBinding> { d.dist.emissionCount("nosuch") }
        unknown.message!! shouldContain "no Observe-role emission tap is installed on it"
    }
}
