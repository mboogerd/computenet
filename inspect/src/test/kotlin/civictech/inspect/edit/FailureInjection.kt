package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.graph.CellFactory
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * Failure-injection fixtures for [StagedApplier] (WKB2 F3, computenet-e1ojt).
 *
 * Verb ORDER is asserted from the registry, not from a wrapper around the
 * host's management API: `LocationRegistry.onLocalPublish` /
 * `onLocalUnpublish` / `onLocalTopology` see every spawn, despawn, link and
 * unlink the applier drives, in the order the host performs them — and they
 * are the observable the acceptance criteria name. The applier's own
 * recording management API is private, so no test-side wrapper could sit in
 * front of it anyway.
 */

/**
 * A [CellFactory] whose [n]-th `create` — counted per factory instance —
 * throws `IllegalStateException("injected")`; every other call builds with
 * [build].
 *
 * The count is per instance, and **PRECHECK constructs every spawn once, cold**
 * (F2's `GraphSpec.precheck` calls `factory.create(ref)` into a scratch map),
 * so the STAGE construction of a spawn is its factory's call 2. A spawn that
 * must fail in STAGE therefore uses `FailingFactory(2)`; `FailingFactory(1)`
 * throws out of PRECHECK instead.
 */
class FailingFactory(private val n: Int, private val build: (CellRef) -> Cell) : CellFactory {
    private val calls = AtomicInteger()

    override fun create(ref: CellRef): Cell {
        check(calls.incrementAndGet() != n) { "injected" }
        return build(ref)
    }
}

/** A spontaneous producer: [emit] originates a fresh wave on [outlet]. */
class EmitterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

    fun emit(value: String) = outlet.call.propagate(value)
}

/** A consumer recording every value delivered to [inlet]. */
class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = CopyOnWriteArrayList<String>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

    init {
        inlet.serve(Propagate { value -> received += value })
    }
}

/**
 * A cell whose [inlet]'s link policy refuses every `LinkRequest`, so a
 * management `connect` into it answers `LinkResult.Rejected`. Note that F2's
 * precheck runs the same policy walk, so a plan linking into it is refused
 * `POLICY_DENIAL` — reaching the CUT_OVER rejection takes `skipPrecheck`.
 */
class RejectingInlet(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>()).also {
        it.linking.policies += LinkPolicy { LinkResult.Rejected("injected: RejectingInlet refuses every link") }
    }
}

/** A live cell whose `onDeactivate` throws, so its management `despawn` throws after unpublishing. */
class ThrowingDeactivateCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    override fun onDeactivate(ctx: CellContext) {
        throw IllegalStateException("injected: onDeactivate")
    }
}
