package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.host.ManagedHost
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.Proxy
import java.io.Serializable

/**
 * Effect classification (G-32, with G-11's push-only lint): a cell that
 * causes externally visible side effects — writes, notifications, actuator
 * calls — declares it. Shadow mode ([Shadow.spawn]) suppresses exactly these.
 */
interface Effectful

/**
 * State migration across incarnations (G-33, spec 53): a candidate that can
 * transform its predecessor's exported state declares this; the transform
 * runs inside the swap's buffered window ([Promotion.promote]). Cells that
 * cannot transform rely on upstream catch-up replay instead (spec 21) — the
 * relink fires `onLinked`, so data cells re-sync without ceremony.
 */
interface StateMigrating {
    /** [prior] is the previous incarnation's [Stateful.snapshot] output. */
    fun importFrom(prior: Serializable)
}

/**
 * Shadow deployment (G-32, spec 52): run a candidate against live inputs
 * with its effects suppressed. Subscribing the shadow subgraph to production
 * outlets is ordinary linking (fan-out); this helper only adds the missing
 * piece — [Effectful] cells spawned in shadow mode get every inlet NoOp-served,
 * so the candidate is judged (by invariant cells, 52) without acting twice
 * on the world.
 */
object Shadow {

    /**
     * Spawn [cell] on [host] in shadow mode: if it is [Effectful], all of its
     * fan-in inlets are NoOp-served after activation. Non-effectful cells
     * spawn unchanged — pure derivation is harmless to duplicate.
     *
     * ponytail: NoOp-serving happens from the caller's thread post-spawn
     * (fine in the single-threaded simulation; a host-queue hop is the
     * production upgrade, same ceiling as replication wiring).
     */
    fun spawn(host: ManagedHost, cell: Cell): CellRef {
        val ref = host.managementInlet.call.spawn(cell)
        if (cell is Effectful) suppress(cell)
        return ref
    }

    /** NoOp-serve every fan-in inlet of [cell] (spec 52's "NoOp-served sinks"). */
    fun suppress(cell: Cell) {
        val ports = PortRegistry.of(cell)
        ports.names().forEach { name ->
            val port = ports[name]
            if (port is FanInlet<*>) {
                @Suppress("UNCHECKED_CAST")
                (port as FanInlet<Any>).serve(Proxy.noop(port.clazz as Class<Any>))
            }
        }
    }
}

/**
 * Promotion as a link swap (spec 53, M9.3): buffer → transfer state →
 * relink → replay. Every step is an existing kernel primitive — traffic
 * light (33), snapshot (G-25), subscribe/unsubscribe (13) — orchestrated,
 * not invented. Rollback is the same call with old and new exchanged.
 */
object Promotion {

    /**
     * Promote [candidate] over [incumbent] behind [gate]:
     * 1. red — upstream traffic parks in the gate (zero loss, spec 33);
     * 2. state — if the candidate is [StateMigrating] and the incumbent
     *    [Stateful], the exported state transfers (G-33); otherwise the
     *    candidate keeps what shadowing taught it / catches up on relink;
     * 3. relink — [downstream]s move from the incumbent's [outletName] to the
     *    candidate's; the incumbent's gate subscription drops so replay
     *    reaches only the promoted incarnation (callers despawn it at leisure);
     * 4. green — the gate replays the parked window and removes itself from
     *    the path.
     */
    fun <T : Any> promote(
        gate: TrafficLightApi<T>,
        incumbent: Cell,
        candidate: Cell,
        outletName: String,
        downstream: List<Use<*>>,
    ) {
        gate.controlInlet.call.setRed()

        if (candidate is StateMigrating && incumbent is Stateful) {
            candidate.importFrom(incumbent.snapshot())
        }

        val from = outlet(incumbent, outletName)
        val to = outlet(candidate, outletName)
        downstream.forEach { use ->
            from.unsubscribe(use.ref)
            @Suppress("UNCHECKED_CAST")
            (to as FanOutlet<Any>).subscribe(use as Use<Any>)
        }
        // retire the incumbent from the live path: the gate's replay and all
        // future traffic reach the candidate only
        incumbentGateSubscription(gate, incumbent)

        gate.controlInlet.call.setGreen()
    }

    private fun outlet(cell: Cell, name: String): FanOutlet<*> =
        PortRegistry.of(cell)[name] as? FanOutlet<*>
            ?: error("no fan-out outlet '$name' on ${cell.ref}")

    private fun <T : Any> incumbentGateSubscription(gate: TrafficLightApi<T>, incumbent: Cell) {
        val ports = PortRegistry.of(incumbent)
        ports.names().forEach { name ->
            (ports[name] as? FanInlet<*>)?.let { inlet ->
                (gate.dataOutlet as FanOutlet<*>).unsubscribe(inlet.ref)
            }
        }
    }
}
