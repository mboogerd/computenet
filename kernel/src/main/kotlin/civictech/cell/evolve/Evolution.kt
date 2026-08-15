package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkRequest
import civictech.cell.link.LinkRole
import civictech.cell.link.Linked
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.Proxy
import civictech.nature.ContractRegistry
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
            if (port is FanInlet<*>) suppress(port)
        }
    }

    /**
     * NoOp-serve a single fan-in [inlet] (spec 52's "NoOp-served sinks") — the
     * per-inlet form [suppress]`(cell)` applies to every inlet. Reused by
     * PN-17's follower effect-suppression (spec 31 §Effects on instance sets):
     * a `SingleWriterReplication` follower suppresses exactly its effect inlet
     * while the leader keeps serving the real, effect-firing api.
     */
    fun <T : Any> suppress(inlet: FanInlet<T>) {
        inlet.serve(suppressionProxy(inlet.clazz))
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
        reauthorizeRebinds(from, to, outletName, downstream)

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

    /**
     * Rolling replicated (and, shard-by-shard, partitioned) promotion (PN-14,
     * spec 53 §Replicated promotion). A replicated cell has no single upstream
     * gate to red — its inputs are local writes and peer gossip — and its
     * incumbent is not retired but RETAINED on every surviving peer. Promotion
     * is therefore a per-instance **rebind behind a reused CellRef**, rolled one
     * instance at a time by the caller (the ordering/abort policy is
     * [PromotionPolicy] data, consulted via [judge] exactly as in [promote]).
     * This is additive: single-instance [promote] is unchanged.
     *
     * PRECHECK (no side effects, freely abortable), then COMMIT via
     * [civictech.cell.replication.Replication.rebind] (fully qualified because
     * `Replication` is not imported in this file — T11-D):
     *  - [judge], when supplied, must return [PromotionVerdict.Accept] (same
     *    contract as [promote]).
     *  - the candidate MUST reuse the incumbent's [CellRef]. A fresh ref re-mints
     *    the ref-derived tag lane and the delivered-watermark slot, orphaning the
     *    incumbent's row (the retired row would hold frontiers forever) and
     *    breaking crash-recovery equivalence — refused here.
     *  - the fresh-epoch **T2 fallback is refused** for a replicated cell: its
     *    state re-syncs by anti-entropy over the *same* ref-derived lane, so a
     *    fresh source is never sound. A candidate declaring
     *    [NonIdempotentCatchUp] (the T2-soundness marker) is refused outright,
     *    exactly as in the single-instance path.
     *  - structural port sameness (93 I-2) on the delta [outletName] outlet.
     *
     * The same rolling form extends shard-by-shard to a partitioned node: each
     * shard is a ref-addressed instance, and promoting it is this same rebind
     * (promotion is a rebind, re-running link-time authority). Set-atomic
     * promotion (all replicas at once) is consensus and out of scope.
     */
    fun promoteReplica(
        host: ManagedHost,
        replication: civictech.cell.replication.Replication,
        incumbent: civictech.cell.data.Replicable<*>,
        candidate: civictech.cell.data.Replicable<*>,
        outletName: String = "outlet",
        judge: PromotionJudge? = null,
    ) {
        // PRECHECK — decided strictly before any state mutation.
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
        if (candidate.ref != incumbent.ref) {
            throw PromotionAborted(
                "PRECHECK",
                "replicated promotion must reuse the incumbent's CellRef (the crash-recovery mechanism: " +
                    "identity — tag lane, watermark row, port refs — derives from the ref); candidate " +
                    "${candidate.ref} != incumbent ${incumbent.ref} (spec 53 §Replicated promotion)",
            )
        }
        if (candidate is NonIdempotentCatchUp) {
            throw PromotionAborted(
                "PRECHECK",
                "replicated promotion has no sound T2 (fresh-epoch) fallback: the candidate re-syncs by " +
                    "anti-entropy over the same ref-derived lane, so a fresh source would double-count " +
                    "(spec 53 §Replicated promotion; §Three handoff tiers)",
            )
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
        // COMMIT — the rebind (reuse-ref crash-recovery); surviving replicas play
        // the retained incumbent and re-feed the candidate.
        replication.rebind(incumbent, candidate, host)
    }

    /**
     * [SEC1-10] — "promotion IS a rebind and MUST re-authorize" (spec
     * `40-distribution/43-security.md` §"The three seams" item 2; 93 I-28 §4.3
     * seam 2: any new full-ref link runs `linkAuthority`). Each downstream
     * [Use] is about to be moved from [from] to [to], which is a *new* link
     * across the candidate's boundary — so it is offered to the same
     * first-rejection-wins gate a fresh handshake would run
     * ([civictech.cell.link.handshake]): the target's own policies first, then
     * the source's (here the candidate outlet, which is where a mediated
     * exposure's producer-side `linkAuthority` lives).
     *
     * **Why PRECHECK and not the COMMIT relink.** COMMIT is non-vetoing by
     * construction — "the admission decision was PRECHECK's, so nothing here
     * may newly reject" — and a throw inside it is classified as an
     * infrastructure fault that triggers rollback. Enforcing there would
     * disguise a policy denial as a fault and only after the gate had gone red.
     * Here the evaluation is pure and nothing has yet been buffered,
     * snapshotted, unsubscribed or re-subscribed: a refusal leaves the
     * incumbent linked and serving, with no partial topology ([SEC1-09]
     * pattern) and no in-flight `Owned`/`Leased` payload to strand — a
     * [civictech.cell.link.LinkRequest] carries no payload, and the delivery
     * path this refusal declines to change is the one still carrying traffic.
     *
     * The identity offered is the one the incumbent's link was ESTABLISHED with
     * ([civictech.cell.link.LinkSupport.identityFor]), not an ambient one. Two
     * limits are real and deliberate, both inherited from the COMMIT relink
     * being a direct `subscribe` rather than a handshake: a downstream attached
     * without a handshake retains no identity and so is evaluated as a local
     * request (which `allowPeers` admits — [SEC1-02] default-open), and the
     * rebind installed by COMMIT registers no link on the candidate, so the
     * retained identity is NOT carried forward to a subsequent promotion —
     * while the incumbent keeps its now-stale record, `unsubscribe` dropping
     * the consumer entry and not the [civictech.cell.link.LinkSupport] one.
     * All of that would be closed by routing the COMMIT relink through the
     * handshake, which is a change to promotion's link semantics beyond this
     * refusal (computenet-usd.5.5).
     *
     * Denial accounting (typed record, counter, sanitized dead letter, epic
     * [SEC1-25]) is deliberately absent here: it rides the SEC1 accounting seam
     * in its own item, and a second mechanism would have to be unpicked.
     */
    private fun reauthorizeRebinds(
        from: FanOutlet<*>,
        to: FanOutlet<*>,
        outletName: String,
        downstream: List<Use<*>>,
    ) {
        downstream.forEach { use ->
            val request = LinkRequest(to.ref, use.ref, from.linking.identityFor(use.ref), LinkRole.Consume)
            val refusal = (use as? Linked)?.linking?.reauthorize(request)
                ?: to.linking.reauthorize(request)
            if (refusal != null) {
                throw PromotionAborted(
                    "PRECHECK",
                    "promotion would rebind ${use.ref} from the incumbent's '$outletName' onto the " +
                        "candidate's, and link authority refuses that new link: ${refusal.reason} " +
                        "(promotion is a rebind and re-authorizes, spec 43 §The three seams item 2)",
                )
            }
        }
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
