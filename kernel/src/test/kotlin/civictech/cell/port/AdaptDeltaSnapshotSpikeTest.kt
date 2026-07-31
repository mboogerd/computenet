package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.SetView
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.link.catchUpOnLinked
import civictech.cell.onEach
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass

/**
 * FU-7 phase-1 spike (`doc/adr/ADR - Adapter Synthesis.md`, addendum): can a
 * *type-level* adapter registry — keyed by contract pair `(fromApi, toApi)`,
 * a sibling of FU-4's nature-axis registry — offer a **fold adapter** that
 * bridges a delta-typed producer (`Propagate<SetDelta<E>>`) to a
 * snapshot-typed consumer (`Propagate<Set<E>>`)?
 *
 * Entirely test-local: spike registry, spike adapter cell, spike consumer.
 * ZERO production code changes. The FU-4 posture carries over verbatim:
 * registry over synthesis, offered never silent — the registry names a
 * candidate; insertion is an explicit wiring act by the caller.
 *
 * What today actually does with this miswire (pinned by the first test,
 * mirroring `PayloadTypeCheckTest`'s KNOWN GAP): the typed `link()` veneer
 * makes it a compile error, but the stringly `connect` path cannot refuse it —
 * both `Api`s erase to `Propagate::class.java` (`Handshake.checkPayload`,
 * T08 finding 1's residual), so the link reports `Connected` and the first
 * delivery dies as a `ClassCastException` far from the connect that caused it
 * (observed here: thrown straight out of the writer's own op, since the
 * simulation scheduler runs the emission chain inline).
 * Erasure is also why the spike registry is keyed by *caller-supplied*
 * payload-constructor tokens (`SetDelta::class`, `Set::class`) rather than by
 * anything recoverable from the port objects: a production registry needs the
 * same declared-payload witness the COVERAGE residual ("same-wrapper payload
 * mismatch still unchecked") already calls for.
 *
 * Natures note (ADR addendum §natures): the fold adapter's outlet emits via a
 * plain reactive `propagate` — UNWAVED, exactly like a hand-written fold cell.
 * Folding into an ALIGN inlet would additionally need the FU-4 wave lift
 * (re-origination); that stacked/composite case is out of phase-1 scope
 * (single-hop only), noted and not built.
 */
class AdaptDeltaSnapshotSpikeTest {

    // ---- spike registry: keyed by contract pair, offered never silent ------

    /** A validated, registered lift for one contract pair; NOT inserted — offered. */
    private class AdapterCandidate(
        val description: String,
        /** Explicit insertion: the caller spawns and wires the adapter cell. */
        val spawn: () -> SetFoldAdapterCell<String>,
    )

    /**
     * The sibling registry (ticket FU-7 §2): the FU-4 registry is keyed by
     * `(axis, fromLevel, toLevel)` over a *closed* vocabulary of nature axes;
     * this one is keyed by `(fromPayload, toPayload)` over an *open* vocabulary
     * of payload type constructors (`SetDelta -> Set`, `MapDelta -> Map`, ...).
     * The lookup key and the insertion helper differ — the adapter *changes the
     * payload type across itself* (inlet `SetDelta<E>`, outlet `Set<E>`) where
     * FU-4's waver relays the same payload — so it does not generalize the
     * axis registry; it stands beside it. The offered-never-silent contract is
     * identical.
     */
    private class DeltaSnapshotRegistry {
        private data class Key(val fromPayload: KClass<*>, val toPayload: KClass<*>)

        private val entries = mutableMapOf<Key, AdapterCandidate>()

        fun register(fromPayload: KClass<*>, toPayload: KClass<*>, candidate: AdapterCandidate) {
            entries[Key(fromPayload, toPayload)] = candidate
        }

        /** Lookup only — consulting the registry wires nothing. */
        fun candidate(fromPayload: KClass<*>, toPayload: KClass<*>): AdapterCandidate? =
            entries[Key(fromPayload, toPayload)]
    }

    // ---- the fold adapter (the canonical deltas->snapshots direction) ------

    /**
     * Fold adapter cell: accumulates the OR-set tag algebra via the kernel's
     * own consumer-side fold ([SetView] — the payload-generic ingredient; the
     * registry entry for another delta shape would carry its own view/fold)
     * and re-emits the *snapshot* contract. Cadence (ticket FU-7 §3): every
     * **effective** delta — `SetView.apply`'s return guards emission, so tag
     * churn that doesn't move membership emits nothing; wave-aligned cadence
     * is the punted composite case. Late joiners catch up with the current
     * snapshot (G-22), mirroring `RatingCell`.
     */
    private class SetFoldAdapterCell<E>(
        // Deterministic ref derived from the contract pair, per ADR (c)/PN-1:
        // re-wiring after restart reproduces the same adapter identity.
        override val ref: CellRef = CellRef(UUID.nameUUIDFromBytes("adapt:SetDelta->Set".toByteArray())),
    ) : Cell {
        private val view = SetView<E>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Set<E>>>())

        init {
            inlet.onEach { delta ->
                if (view.apply(delta)) outlet.call.propagate(view.current())
            }
            outlet.catchUpOnLinked { view.current().takeIf { it.isNotEmpty() } }
        }
    }

    // ---- the snapshot-typed consumer ---------------------------------------

    /** A consumer whose declared contract is the *folded* form: `Set<String>`. */
    private class SnapshotSetConsumerCell(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val snapshots = mutableListOf<Set<String>>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<Set<String>>>())

        init {
            inlet.onEach { snapshot ->
                // The defensive copy forces the erased payload through a
                // Collection checkcast on the dispatch thread — a miswired
                // SetDelta dies here (dead letter), never reaching the list.
                snapshots += HashSet<String>(snapshot)
            }
        }
    }

    private fun registryWithFoldAdapter(): DeltaSnapshotRegistry =
        DeltaSnapshotRegistry().apply {
            register(
                SetDelta::class, Set::class,
                AdapterCandidate("fold SetDelta<E> -> Set<E> via SetView") { SetFoldAdapterCell() },
            )
        }

    // ---- 1. pinned today ----------------------------------------------------

    @Test
    fun `pinned today - delta outlet into snapshot inlet is not refused (erasure) and the first write dies with a CCE`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val source = SetCell<String>() // outlet: Propagate<SetDelta<String>>
        val consumer = SnapshotSetConsumerCell() // inlet: Propagate<Set<String>>
        mgmt.spawn(source)
        mgmt.spawn(consumer)

        // Both Apis erase to Propagate::class.java, so checkPayload waves the
        // miswire through — the same-wrapper residual PayloadTypeCheckTest pins.
        // (The typed link() veneer rejects this pair at compile time; only the
        // stringly path can even attempt it.)
        mgmt.connect(source.ref, "outlet", consumer.ref, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()

        // Under the simulation scheduler the emission chain runs inline, so
        // the miswire surfaces as a ClassCastException thrown out of the
        // *writer's* op — arbitrarily far from the connect that caused it
        // (on a threaded host the same death lands on the dispatch thread).
        // Either way: no refusal at link time, no snapshot ever delivered.
        shouldThrow<ClassCastException> { source.inlet.call.add("apple") }
        controller.runToIdle()
        consumer.snapshots.shouldBeEmpty()
    }

    // ---- 2. the spike -------------------------------------------------------

    @Test
    fun `spike - registry-selected fold adapter bridges SetDelta producer to Set consumer and values converge`() {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val registry = registryWithFoldAdapter()

        val source = SetCell<String>()
        val consumer = SnapshotSetConsumerCell()
        mgmt.spawn(source)
        mgmt.spawn(consumer)

        // Offered, never silent: the registry *names* the candidate for the
        // contract pair; nothing is wired until the caller inserts it.
        val candidate = registry.candidate(SetDelta::class, Set::class)
        candidate.shouldNotBeNull()
        consumer.snapshots.shouldBeEmpty() // lookup alone changed nothing

        // Explicit insertion: spawn the adapter, wire producer->adapter->consumer.
        val adapter = candidate.spawn()
        mgmt.spawn(adapter)
        mgmt.connect(source.ref, "outlet", adapter.ref, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>() // SetDelta -> SetDelta
        mgmt.connect(adapter.ref, "outlet", consumer.ref, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>() // Set -> Set

        val writer = source.inlet.call
        writer.add("apple")
        writer.add("banana")
        controller.runToIdle()
        consumer.snapshots.shouldNotBeEmpty()
        consumer.snapshots.last() shouldBe setOf("apple", "banana")

        writer.add("cherry")
        writer.remove("banana")
        controller.runToIdle()
        consumer.snapshots.last() shouldBe setOf("apple", "cherry")

        // Effective-only cadence: re-adding a live element mints a fresh tag
        // but moves no membership — the fold emits no redundant snapshot.
        val emissions = consumer.snapshots.size
        writer.add("apple")
        controller.runToIdle()
        consumer.snapshots.size shouldBe emissions
    }

    // ---- 3. the control -----------------------------------------------------

    @Test
    fun `control - an unregistered pair (MapDelta to Set) yields no candidate and the refusal stands`() {
        val registry = registryWithFoldAdapter()

        // The registry has a fold adapter — but for a different contract pair.
        registry.candidate(MapDelta::class, Set::class).shouldBeNull()

        // No candidate means no insertion path: the developer is left exactly
        // where today's refusal leaves them (compile error at the typed link;
        // the erased-connect death pinned by the first test). Nothing in the
        // registry weakens that.
    }
}
