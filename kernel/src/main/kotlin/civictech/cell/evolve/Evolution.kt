package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.host.ManagedHost
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.Proxy
import civictech.gen.wire.ContractRegistry
import java.io.Serializable

/**
 * Effect classification (G-32, with G-11's push-only lint): a cell that
 * causes externally visible side effects — writes, notifications, actuator
 * calls — declares it. Shadow mode ([Shadow.spawn]) suppresses exactly these.
 */
interface Effectful

/**
 * State migration across instances (G-33, spec 53): a candidate that can
 * transform its predecessor's exported state declares this; the transform
 * runs inside the swap's buffered window ([Promotion.promote]). Cells that
 * cannot transform rely on upstream catch-up replay instead (spec 21) — the
 * relink fires `onLinked`, so data cells re-sync without ceremony.
 */
interface StateMigrating {
    /** [prior] is the previous instance's [Stateful.snapshot] output. */
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
                (port as FanInlet<Any>).serve(suppressionProxy(port.clazz as Class<Any>))
            }
        }
    }

    private fun <T : Any> suppressionProxy(clazz: Class<T>): T =
        if (ContractRegistry.descriptor(clazz)?.methods?.any { it.exclusive } == true) {
            Proxy.discharging(clazz)
        } else {
            Proxy.noop(clazz)
        }
}

/**
 * Promotion as the four-phase swap transaction (spec 53 §The promotion swap,
 * decided 93 I-11, G-49): PRECHECK (no side effects, freely abortable) →
 * PREPARE (red, drain) → COMMIT (non-vetoing state handoff + relink) →
 * RETIRE (despawn). Every step is an existing kernel primitive — traffic
 * light (33), snapshot (G-25), subscribe/unsubscribe (13), despawn (15) —
 * orchestrated, not invented. The incumbent is retained hot with its links
 * until COMMIT fully succeeds: a mid-COMMIT failure reverses any partial
 * relinks and re-greens onto the incumbent unchanged ("same swap, reversed").
 * Rollback *after* a successful promotion (post-RETIRE) is a fresh swap in
 * the reverse direction — not this function's concern.
 */
object Promotion {

    /**
     * Declares that this cell's downstream merge is non-idempotent under a
     * source-identity change (spec 53 §Three handoff tiers: e.g. a running
     * counter). The T2 catch-up fallback mints a fresh source and replays
     * catch-up state, which would double-count the incumbent's
     * already-delivered contribution for such a cell — so a candidate
     * declaring this MUST supply a T0/T1 state transfer ([StateMigrating]
     * over a [Stateful] incumbent, or matching snapshot schemas); PRECHECK
     * refuses the promotion outright rather than falling back to T2.
     */
    interface NonIdempotentCatchUp

    /**
     * A promotion phase failed. PRECHECK failures leave the incumbent
     * completely untouched (nothing was attempted). COMMIT failures leave
     * the incumbent retained and re-linked — the swap was rolled back and
     * the gate is green again onto the incumbent, exactly as if promotion
     * had never been called.
     */
    class PromotionAborted(phase: String, message: String, cause: Throwable? = null) :
        RuntimeException("promotion aborted at $phase: $message", cause)

    /**
     * Promote [candidate] over [incumbent] behind [gate], despawning the
     * retired incumbent from [host] only after a successful COMMIT.
     *
     * [judge], when supplied, is the declarative replacement for an
     * imperative caller-side check (spec 53 "Judgment is declarative
     * policy", G-50): PRECHECK consults [PromotionJudge.verdict] first and
     * aborts — incumbent completely untouched — unless it is
     * [PromotionVerdict.Accept]. A `null` judge preserves the prior
     * behavior (the caller judges by hand, as in the pre-G-50 tests).
     */
    fun <T : Any> promote(
        host: ManagedHost,
        gate: TrafficLightApi<T>,
        incumbent: Cell,
        candidate: Cell,
        outletName: String,
        downstream: List<Use<*>>,
        judge: PromotionJudge? = null,
    ) {
        // 1. PRECHECK — no side effects, freely abortable. Admission is
        // decided strictly before the window, so mid-swap rejection cannot
        // occur: everything checked here is settled before the gate ever
        // turns red.
        if (judge != null) {
            when (val verdict = judge.verdict()) {
                is PromotionVerdict.Accept -> {}
                is PromotionVerdict.Pending -> throw PromotionAborted(
                    "PRECHECK",
                    "promotion policy's observation window is not yet filled (verdict: Pending)",
                )
                is PromotionVerdict.Reject -> throw PromotionAborted("PRECHECK", verdict.reason)
            }
        }
        val from = outlet(incumbent, outletName)
        val to = outlet(candidate, outletName)
        if (from.clazz != to.clazz) {
            throw PromotionAborted(
                "PRECHECK",
                "candidate outlet '$outletName' contract ${to.clazz.name} " +
                    "does not match incumbent's ${from.clazz.name} (structural port sameness, 93 I-2)",
            )
        }
        val migrates = candidate is StateMigrating && incumbent is Stateful
        if (!migrates && candidate is NonIdempotentCatchUp) {
            throw PromotionAborted(
                "PRECHECK",
                "candidate declares NonIdempotentCatchUp with no T0/T1 state transfer available; " +
                    "the T2 catch-up fallback would double-count the incumbent's already-delivered " +
                    "contribution under a fresh source (spec 53 §Three handoff tiers)",
            )
        }

        // 2. PREPARE — the membrane goes red: inbound faces serve a
        // Buffering proxy, all coupled inlets parking together in one
        // window. After this the incumbent quiesces hot; no incumbent wave
        // is in flight.
        gate.controlInlet.call.setRed()

        var droppedIncumbentFromGate = false
        val relinked = mutableListOf<Use<Any>>()
        try {
            // 3. COMMIT — non-vetoing: the admission decision was PRECHECK's,
            // so nothing here may newly reject; a thrown exception here is an
            // infrastructure fault, not a veto, and triggers rollback below.
            if (migrates) {
                (candidate as StateMigrating).importFrom((incumbent as Stateful).snapshot())
                // preserved-epoch adoption (spec 20/22 §Source identity, 93
                // I-11/I-27 default): the state transfer carries the
                // outlet's (sourceId, highWater) inside this buffered swap
                // window too, so the candidate continues the same source
                // lane — wave-invisible, no ReBaseline, the glitch-free
                // frontier stays intact (G-42/G-43). T0/T1.
                to.adoptWaveState(from.waveState())
            } else {
                // T2 (catch-up fallback): the fresh epoch MUST NOT be silent
                // (93 I-22) — the candidate re-emits its shadow-taught state
                // as an ordinary catch-up delta flagged with the superseded
                // sourceId, making the succession wave-observable rather
                // than a silent fresh-source reset. Refused above for
                // NonIdempotentCatchUp candidates.
                val superseded = to.mintFreshEpoch()
                if (candidate is ReBaselineEmitting) {
                    candidate.reBaseline(setOf(superseded), supersede = true)
                }
            }

            downstream.forEach { use ->
                from.unsubscribe(use.ref)
                @Suppress("UNCHECKED_CAST")
                (to as FanOutlet<Any>).subscribe(use as Use<Any>)
                @Suppress("UNCHECKED_CAST")
                relinked += use as Use<Any>
            }

            // retire the incumbent from the live path: the gate's replay and
            // all future traffic reach the candidate only
            dropIncumbentFromGate(gate, incumbent)
            droppedIncumbentFromGate = true

            // 4a. green: replay the parked window and remove the gate from
            // the per-message path.
            gate.controlInlet.call.setGreen()
        } catch (e: Exception) {
            // Rollback: "same swap, reversed" — the incumbent was retained
            // hot with its links throughout, so undo whatever COMMIT managed
            // to do, in reverse order, and re-green onto it. Buffered
            // traffic always has a home; the in-window case needs no journal.
            if (droppedIncumbentFromGate) restoreIncumbentToGate(gate, incumbent)
            relinked.asReversed().forEach { use ->
                @Suppress("UNCHECKED_CAST")
                (to as FanOutlet<Any>).unsubscribe(use.ref)
                @Suppress("UNCHECKED_CAST")
                (from as FanOutlet<Any>).subscribe(use)
            }
            gate.controlInlet.call.setGreen()
            throw PromotionAborted("COMMIT", e.message ?: e.toString(), e)
        }

        // 4b. RETIRE — despawn the incumbent (15). Only now is it gone; a
        // rollback can no longer reach it, so this runs strictly after the
        // swap that could still fail is fully behind us.
        host.managementInlet.call.despawn(incumbent.ref)
    }

    private fun outlet(cell: Cell, name: String): FanOutlet<*> =
        PortRegistry.of(cell)[name] as? FanOutlet<*>
            ?: error("no fan-out outlet '$name' on ${cell.ref}")

    private fun <T : Any> dropIncumbentFromGate(gate: TrafficLightApi<T>, incumbent: Cell) {
        val ports = PortRegistry.of(incumbent)
        ports.names().forEach { name ->
            (ports[name] as? FanInlet<*>)?.let { inlet ->
                (gate.dataOutlet as FanOutlet<*>).unsubscribe(inlet.ref)
            }
        }
    }

    private fun <T : Any> restoreIncumbentToGate(gate: TrafficLightApi<T>, incumbent: Cell) {
        val ports = PortRegistry.of(incumbent)
        ports.names().forEach { name ->
            (ports[name] as? FanInlet<*>)?.let { inlet ->
                @Suppress("UNCHECKED_CAST")
                (gate.dataOutlet as FanOutlet<T>).subscribe(inlet as Use<T>)
            }
        }
    }
}
