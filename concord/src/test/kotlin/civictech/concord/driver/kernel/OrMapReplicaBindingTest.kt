package civictech.concord.driver.kernel

import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The `dist` binding of `ormap-source` (KE1-F4, computenet-j2x.4.2): a
 * `replica-of` cell of that type joins the same gossip mesh `set-source`
 * replicas do — [KernelDriverDist.spawnReplica] builds an `OrMapCell` under the
 * group's shared logical id, stages any `interest:`, hands it to
 * `Replication.replicate`, and gives it a co-hosted `tagged-map-view` companion
 * so `readView` folds the *converged* map (spec 42 §Design as implemented,
 * 96 §E1.3).
 *
 * Everything here is asserted through the driver surface only — `spawn` /
 * `apply` / `quiesce` / `readView` — which is exactly what a corpus scenario's
 * `replicas-converge` check reaches through; kernel internals are never touched.
 */
class OrMapReplicaBindingTest {

    private val BUDGET = 5_000_000

    /** Two `ormap-source` replicas of one logical id, one per host. */
    private fun mesh(seed: Long = 0L): KernelDriver = KernelDriver(seed).apply {
        createHost("h1")
        createHost("h2")
        spawn("h1", "r1", "ormap-source", mapOf("replica-of" to Value.StrVal("shared")))
        spawn("h2", "r2", "ormap-source", mapOf("replica-of" to Value.StrVal("shared")))
    }

    /**
     * Concurrent writes to *different* keys: the add-wins presence of both dots
     * survives the merge, so both replicas fold to the union. This is the
     * `set-source` mesh's `42-REPL-01` shape lifted to the map — and it is the
     * property that fails outright if the companion cannot fold a
     * `TaggedMapDelta` (a `set-view` companion here would not even type).
     */
    @Test
    fun `concurrent puts on two ormap-source replicas converge to the merged map`() {
        val d = mesh()
        d.apply("r1", "put", list(s("k1"), i(1)))
        d.apply("r2", "put", list(s("k2"), i(2)))
        d.quiesce(BUDGET)

        val expected = map("k1" to i(1), "k2" to i(2))
        d.readView("r1") shouldBe expected
        d.readView("r2") shouldBe expected
        d.deadLetters() shouldBe emptyList()
    }

    /**
     * A remove issued on the *other* replica: `r2` has already observed `k1`'s
     * dot through gossip when it removes, so the tombstone covers a dot `r1`
     * minted, and `r1` must drop the key on the echo back. The untouched `k2`
     * pins that the remove is per-key, not a reset of the fold.
     */
    @Test
    fun `a remove on one ormap-source replica converges on the other`() {
        val d = mesh()
        d.apply("r1", "put", list(s("k1"), i(1)))
        d.apply("r1", "put", list(s("k2"), i(2)))
        d.quiesce(BUDGET) // r2 now observes both dots
        d.apply("r2", "remove", s("k1"))
        d.quiesce(BUDGET)

        val expected = map("k2" to i(2))
        d.readView("r1") shouldBe expected
        d.readView("r2") shouldBe expected
    }

    /**
     * Concurrent puts to the SAME key: each replica mints a dot the other had
     * not observed, so both dots survive the merge (neither remove-covers the
     * other) and the map exposes exactly one of them — the same one on both
     * sides. Which one is dot order's to choose, so the assertion is agreement
     * plus membership of the two candidates, never a hand-picked winner.
     */
    @Test
    fun `concurrent same-key puts expose one agreed value on both replicas`() {
        val d = mesh()
        d.apply("r1", "put", list(s("k"), s("from-r1")))
        d.apply("r2", "put", list(s("k"), s("from-r2")))
        d.quiesce(BUDGET)

        val onR1 = d.readView("r1")
        d.readView("r2") shouldBe onR1
        listOf(map("k" to s("from-r1")), map("k" to s("from-r2"))) shouldContain onR1
    }

    /**
     * A later-joining replica catches up through the mesh (the `42-REPL-LATE-01`
     * shape): `replicate`'s onPublish wiring links it to the peers already
     * published under the logical id, and its own companion folds the state it
     * receives.
     */
    @Test
    fun `a late-joining ormap-source replica catches up to the mesh`() {
        val d = KernelDriver(0L).apply {
            createHost("h1")
            spawn("h1", "r1", "ormap-source", mapOf("replica-of" to Value.StrVal("shared")))
            apply("r1", "put", list(s("k1"), i(1)))
            quiesce(BUDGET)
            createHost("h2")
            spawn("h2", "r2", "ormap-source", mapOf("replica-of" to Value.StrVal("shared")))
            quiesce(BUDGET)
        }
        d.readView("r2") shouldBe map("k1" to i(1))
        d.readView("r1") shouldBe map("k1" to i(1))
    }

    /**
     * The refusal stays loud for everything the mesh does not bind — widening it
     * by two types must not turn an unbound `replica-of` into a silent
     * mis-binding. `map-source` is the sharpest control: it folds to the same
     * shape a `tagged-map-view` renders, so a binding that keyed off the *view*
     * rather than the cell's mergeability would wrongly admit it (`MapCell` is
     * not `Replicable` — last-writer-wins over a non-lattice is not mergeable).
     */
    @Test
    fun `replica-of refuses a type with no mergeable binding`() {
        val d = KernelDriver(0L)
        d.createHost("h1")
        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.spawn("h1", "bad", "map-source", mapOf("replica-of" to Value.StrVal("shared")))
        }
        // Truthful message: it names both bound types, not just the pre-KE1-F4 one.
        refused.message!!.contains("set-source") shouldBe true
        refused.message!!.contains("ormap-source") shouldBe true
    }

    /**
     * The `set-source` mesh is untouched by the widening — same fixture shape,
     * `set-view` companion, `42-REPL-01` semantics.
     */
    @Test
    fun `set-source replicas still converge after the widening`() {
        val d = KernelDriver(0L).apply {
            createHost("h1")
            createHost("h2")
            spawn("h1", "s1", "set-source", mapOf("replica-of" to Value.StrVal("shared-set")))
            spawn("h2", "s2", "set-source", mapOf("replica-of" to Value.StrVal("shared-set")))
            apply("s1", "add", s("a"))
            apply("s2", "add", s("b"))
            quiesce(BUDGET)
        }
        d.readView("s1") shouldBe list(s("a"), s("b"))
        d.readView("s2") shouldBe list(s("a"), s("b"))
    }
}
