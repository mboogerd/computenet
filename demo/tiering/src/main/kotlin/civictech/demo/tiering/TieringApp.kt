package civictech.demo.tiering

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.KeyedSetApi
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.OrMapApi
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.refAs
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.link
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import civictech.demo.shell.value
import civictech.wire.WsTransport
import com.sun.net.httpserver.HttpExchange
import java.io.Serializable
import java.net.URI
import java.net.URLDecoder
import java.util.*
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.CombineLatestApi
import civictech.cell.data.op.UntagApi
import civictech.cell.data.op.UntagCell
import kotlinx.serialization.SerialName

/**
 * Incremental tiering: agents emit absolute tier valuations (S..F per item)
 * and relative pairwise preferences ("x beats y"); both fold into one global
 * S–F tier board, incrementally re-tiered on any change. Score fusion +
 * fixed thresholds live in [Tiering]; the two signal averages are ordinary
 * GroupBy cells; combining them per key is the kernel
 * [CombineLatestCell] — the outer per-key combine this demo prototyped
 * (F-1/F-2), wired with `combine = Tiering.fuse`.
 */
@kotlinx.serialization.Serializable
@SerialName("tiering.Valuation")
data class Valuation(val agent: String, val item: String, val score: Long) : Serializable

@kotlinx.serialization.Serializable
@SerialName("tiering.Pref")
data class Pref(val agent: String, val winner: String, val loser: String) : Serializable

/**
 * One pairwise vote projected onto one item. Carries agent AND opponent so
 * distinct preferences never collide as set elements (FlatMapSetCell folds
 * colliding outputs by tag-union — identity must stay per-preference).
 */
data class Contribution(val item: String, val agent: String, val opponent: String, val sign: Long) : Serializable

/**
 * The dataflow pipeline, shared verbatim by the app and the seeded test:
 *
 *   vals  (KeyedSetCell<(agent,item), Valuation>) ─► tierAvg (GroupBy item, avg score)  ─► fuse.left
 *   prefs (SetCell<Pref>) ─► contribs (flatMap ±1) ─► prefAvg (GroupBy item, avg sign) ─► fuse.right
 *   fuse (CombineLatestCell, combine = Tiering.fuse) ─► MapDelta<item, Tiered>
 *
 * and the manual re-tier lane (feature computenet-j2x.5, E1.6), which is
 * present in every mode:
 *
 *   manual (OrMapCell<item, tier>) ─► manualEffective (UntagCell) ─► board.right
 *   fused ─► board.left
 *   board (CombineLatestCell, manual wins) ─► MapDelta<item, Tiered>
 *
 * The manual edge converges under concurrent multi-host writes because it is
 * an OR-map, and it reaches the deterministic join family through [UntagCell]
 * — decision j2x.5-D2: no demo-private merge, no timestamps, no arrival-order
 * state anywhere in this file.
 */
object TierPipeline {
    /**
     * The one logical id every host mints its manual-re-tier replica under
     * ([KE1-31]'s ref-derivation half). Derived from a fixed string, exactly
     * as `demo/shopping`'s `SHARED_ID` is, so a RESTART re-derives the same
     * ref and the replica's dotSource is replay-stable.
     */
    val MANUAL_ID: UUID = UUID.nameUUIDFromBytes("tiering-replica:manual-retier".toByteArray())

    /**
     * The manual replica's instance id for a peering role: the dialer takes 1,
     * the listener and a solo process take 0. Role-derived, so the two sides
     * need no discovery protocol and no extra flag to end up with distinct
     * instance ids under one shared logical id — `demo/shopping`'s
     * `sharedInstance` idiom verbatim.
     */
    fun manualInstance(role: String): Long = if (role == "dialer") 1L else 0L

    fun manualRef(role: String): CellRef = CellRef(MANUAL_ID, manualInstance(role))

    /**
     * Derived logical ids for the three cells [handleOp][TieringApp.handleOp]
     * writes to through a **routed** hosted lookup (`items`, `vals`, `prefs`),
     * exactly the [MANUAL_ID] treatment — a `--journal` record names the ref
     * it was written against, so a cell a replay must find has to carry the
     * SAME ref across a restart, not the `graph { }` DSL's default
     * `IdentityBinding.FreshLogical` random mint.
     *
     * **The instance id is [manualInstance]'s, role-derived, for the same
     * reason [MANUAL_ID]'s is — and it is NOT optional (computenet-3san,
     * caught in review).** These cells are not [Replication]-replicated, but
     * `Peering.announceTo` announces *every* local ref its registry holds and
     * `LocationRegistry.publish(ref, sink)` installs the announced `Remote`
     * unconditionally — it does not defer to an existing `Local`. So two
     * peers holding one ref overwrite each other's local location, and once
     * the announcements have been applied every routed write to that ref
     * leaves for the wire and is lost at the far end, which is holding the
     * same ref as `Remote` right back. Measured on the fixed-instance-`0`
     * version of this change: after the mesh had settled, neither peer's
     * `/state` ever showed an item it posted itself. A role-derived instance
     * keeps the two sides distinct while staying stable across a restart at
     * the same role, which is exactly what `--journal` needs.
     * [TieringWirePerPeerStateTest][civictech.demo.tiering.TieringWirePerPeerStateTest]
     * pins it, and only after driving the manual lane both ways first: before
     * the announcements land the local location still wins and the defect is
     * invisible.
     *
     * The remaining pipeline cells (`contribs`, `tierAvg`, `prefAvg`,
     * `fused`, `manualEffective`, `board`) stay `FreshLogical`: nothing
     * writes to them directly, so nothing journals a ref for them to match —
     * they recompute from the replayed `items`/`vals`/`prefs`/`manual` deltas
     * once the graph is rebuilt and linked, whatever ref they mint.
     */
    val ITEMS_ID: UUID = UUID.nameUUIDFromBytes("tiering-pipeline:items".toByteArray())
    val VALS_ID: UUID = UUID.nameUUIDFromBytes("tiering-pipeline:vals".toByteArray())
    val PREFS_ID: UUID = UUID.nameUUIDFromBytes("tiering-pipeline:prefs".toByteArray())

    data class Refs(
        val items: TypedRef<SetApi<String>>,
        val vals: TypedRef<KeyedSetApi<Pair<String, String>, Valuation>>,
        val prefs: TypedRef<SetApi<Pref>>,
        val tierAvg: TypedRef<GroupByApi<Valuation, String, Double>>,
        val prefAvg: TypedRef<GroupByApi<Contribution, String, Double>>,
        val fused: TypedRef<CombineLatestApi<String, Double, Double, Tiered>>,
        /** The manual re-tier OR-map: item → tier label, replicated in wire mode. */
        val manual: TypedRef<OrMapApi<String, String>>,
        /** [manual]'s converged state, spoken as an untagged `MapDelta`. */
        val manualEffective: TypedRef<UntagApi<String, String>>,
        /** What the board renders: [fused] with [manualEffective] overriding it. */
        val board: TypedRef<CombineLatestApi<String, Tiered, String, Tiered>>,
    )

    /**
     * Wire the pipeline on [host].
     *
     * [manual] is passed in (rather than spawned inside the `graph { }` spec)
     * because in wire mode it is not this host's to spawn: it is a replica,
     * minted at a role-derived ref and handed to `Replication.replicate`,
     * which does the spawning itself. [spawnManual] is that seam — the
     * default is a plain management spawn, which is exactly what solo mode
     * wants.
     */
    fun build(
        host: ManagedHost,
        manual: OrMapCell<String, String> = OrMapCell(manualRef("solo")),
        spawnManual: (OrMapCell<String, String>) -> Unit = { host.managementInlet.call.spawn(it) },
    ): Refs {
        spawnManual(manual)
        // The peering role slot, read off the manual replica's own ref rather
        // than taken as a parameter: [manual] is already minted at
        // [manualRef], so its instance id IS this host's role. Keeping the
        // routed pipeline cells on the same slot is what stops two peers
        // minting one ref (see [ITEMS_ID]'s KDoc).
        val instance = manual.ref.instanceId
        lateinit var built: Refs
        var untagCell: UntagCell<String, String>? = null
        graph(host.managementInlet) {
            val items = spawn("items", identity = IdentityBinding.Exact(CellRef(ITEMS_ID, instance))) { ref ->
                SetCell<String>(ref)
            }
            val vals = spawn("vals", identity = IdentityBinding.Exact(CellRef(VALS_ID, instance))) { ref ->
                KeyedSetCell<Pair<String, String>, Valuation>(ref)
            }
            val prefs = spawn("prefs", identity = IdentityBinding.Exact(CellRef(PREFS_ID, instance))) { ref ->
                SetCell<Pref>(ref)
            }
            val contribs = spawn("contribs") {
                FlatMapSetCell(f = { p: Pref ->
                    listOf(
                        Contribution(p.winner, p.agent, p.loser, +1),
                        Contribution(p.loser, p.agent, p.winner, -1),
                    )
                })
            }
            val tierAvg = spawn("tierAvg") {
                GroupByCell(keyFn = { v: Valuation -> v.item }, aggregator = Aggregators.avgOf { v: Valuation -> v.score })
            }
            val prefAvg = spawn("prefAvg") {
                GroupByCell(keyFn = { c: Contribution -> c.item }, aggregator = Aggregators.avgOf { c: Contribution -> c.sign })
            }
            val fused = spawn("fused") { CombineLatestCell<String, Double, Double, Tiered>(combine = { _, t, p -> Tiering.fuse(t, p) }) }
            // The adoption seam (j2x.5-D2): the tagged map's converged state,
            // projected down to the untagged MapDelta vocabulary the join
            // family already speaks. No demo code ever merges dots.
            val manualEffective = spawn("manualEffective") { UntagCell<String, String>() }
            // Manual wins where present; otherwise the computed tier; a key
            // absent from both sides is dropped (CombineLatestCell removes it
            // regardless, so there are no ghost rows).
            val board = spawn("board") {
                CombineLatestCell<String, Tiered, String, Tiered>(
                    combine = { _, computed, manualTier -> manualTier?.let(Tiering::manualTiered) ?: computed },
                )
            }
            link(vals.cell.outlet, tierAvg.cell.inlet)
            link(prefs.cell.outlet, contribs.cell.inlet)
            link(contribs.cell.outlet, prefAvg.cell.inlet)
            link(tierAvg.cell.outlet, fused.cell.left)
            link(prefAvg.cell.outlet, fused.cell.right)
            link(fused.cell.outlet, board.cell.left)
            link(manualEffective.cell.outlet, board.cell.right)
            untagCell = manualEffective.cell
            built = Refs(
                items = items.refAs(),
                vals = vals.refAs(),
                prefs = prefs.refAs(),
                tierAvg = tierAvg.refAs(),
                prefAvg = prefAvg.refAs(),
                fused = fused.refAs(),
                manual = TypedRef(manual.ref),
                manualEffective = manualEffective.refAs(),
                board = board.refAs(),
            )
        }
        // Linked outside the `graph { }` spec, and deliberately: [manual] was
        // not spawned by this spec (it may be a Replication-owned replica), so
        // recording a ConnectStep that names it would make the spec unreplayable
        // on its own. demo/shopping links its unions into DSL-built cells the
        // same way.
        host.managementInlet.call.link(manual.outlet, untagCell!!.inlet)
        return built
    }
}

/**
 * [wire] is the two-host mode (`--listen` / `--peer`), **off by default**
 * (decision j2x.5-D1). With it absent no bridge host, no listener and no
 * [Replication] is constructed and this app is byte-identical to the
 * single-host demo it has always been, save for the additive manual lane —
 * which exists in every mode.
 *
 * [journalDir] is `--journal`: the app host write-ahead journals every
 * *routed* invocation, and [ManagedHost.recoverFrom] replays it on restart.
 * That is why every write below goes through a hosted lookup rather than the
 * cell object.
 *
 * **`--journal` covers the whole demo (computenet-3san), not only the manual
 * lane it originally shipped for.** Two independent gaps, both measured
 * 2026-08-29 and now closed:
 *
 * 1. *Payloads.* The host journal encodes each routed invocation through
 *    `WireCodec`, whose `polymorphic(Any)` scope registers kernel payload
 *    types plus whatever a process contributes at start
 *    (`WireCodec.kt:158-176`). `tier` passes a `Pair<String, String>` key and
 *    a [Valuation]; `pref`/`unpref` pass a [Pref]. [TieringWireSerializers],
 *    discovered through `META-INF/services/civictech.cell.wire.WireSerializers`,
 *    registers all three — `Pref`/`Valuation` as `@Serializable` types, the
 *    `Pair<String, String>` key via kotlinx's `PairSerializer` — so every
 *    action's payload now encodes. `item`/`unitem`/`retier` carried only
 *    `String`s and always encoded fine.
 * 2. *Refs.* [TierPipeline]'s cells were spawned through the `graph { }` DSL's
 *    default `IdentityBinding.FreshLogical`, which mints a random ref per
 *    process start (`GraphDsl.kt:378-390`) — unreplayable, because a journal
 *    record names the ref it was written against. The three cells a routed
 *    invocation actually reaches — `items`, `vals`, `prefs` — now spawn at
 *    fixed, derived refs ([TierPipeline.ITEMS_ID]/[TierPipeline.VALS_ID]/
 *    [TierPipeline.PREFS_ID] over a role-derived instance id), the same
 *    treatment [TierPipeline.MANUAL_ID] already gave the manual lane — the
 *    role included, because two peers on one ref lose every routed write to
 *    it (see [TierPipeline.ITEMS_ID]'s KDoc). The remaining pipeline cells stay
 *    `FreshLogical`: nothing writes to them directly, so replaying
 *    `items`/`vals`/`prefs`/`manual` and re-linking the rebuilt graph is
 *    enough to recompute them.
 *
 * So a restart over the same `--journal <dir>` now replays the full
 * `/state` payload — items, valuations, preferences and the manual pins —
 * not only the manual OR-map.
 */
class TieringApp(
    port: Int = 8080,
    private val wire: Wire? = null,
    journalDir: java.io.File? = null,
) {
    /** Peer mode: symmetric peers — one listens, the other dials. */
    sealed interface Wire {
        data class Listen(val wsPort: Int) : Wire
        data class Dial(val uri: String) : Wire
    }

    private val registry = LocationRegistry()

    private val myRole = when (wire) {
        is Wire.Listen -> "listener"
        is Wire.Dial -> "dialer"
        null -> "solo"
    }

    /**
     * The replica mesh linker, held for the process lifetime and constructed
     * **here** — a property initializer, not the tail of [init] — because its
     * constructor installs the registry publish hooks the whole mesh runs on,
     * and [init] is where peering is established. Every peer announcement this
     * JVM will ever see therefore arrives after the hooks are in place.
     * (`demo/shopping`'s `replication` KDoc states the same ordering.)
     */
    private val replication: Replication? = if (wire != null) Replication(registry) else null

    private val journal = KeyedCells.hostJournal(journalDir)
    private val host = ManagedHost(registry = registry, journal = journal)

    /** The peering bridge's own host; null outside wire mode. */
    private val bridgeHost: ManagedHost? = wire?.let { ManagedHost(registry = registry) }

    /**
     * This host's instance of the one shared manual-re-tier logical cell.
     * Same [TierPipeline.MANUAL_ID] on both sides, instance id derived from
     * the peering role — so a restart at the same role re-derives the same
     * ref, which is what makes the dots it mints replay-stable ([KE1-31]).
     */
    private val manualCell = OrMapCell<String, String>(TierPipeline.manualRef(myRole))

    private val refs = TierPipeline.build(host, manualCell) { cell ->
        // In wire mode the replica is spawned by Replication, which also links
        // it to every already-known replica of the same logical id; in solo
        // mode it is an ordinary local cell.
        if (replication != null) replication.replicate(cell, host) else host.managementInlet.call.spawn(cell)
    }
    private val itemOps = host.lookup(refs.items)!!.inlet.call
    private val valOps = host.lookup(refs.vals)!!.inlet.call
    private val prefOps = host.lookup(refs.prefs)!!.inlet.call

    /**
     * The manual re-tier write path: a **routed** hosted lookup, never the
     * cell object. Routed is what makes the invocation write-ahead journaled
     * and therefore replayable — the whole point of `--journal`.
     */
    private val manualOps = host.lookup(refs.manual)!!.inlet.call

    /** The `--listen` listener, kept so [boundWsPort] can report what it bound. */
    private var wsListener: WsTransport.WsListener? = null

    private val state = Object()
    // Read model: each derived outlet materialized by a kernel observation sink,
    // read via current() in stateJson. Constructed WITHOUT an onChange listener
    // so no broadcast() fires before all eight sinks exist; listeners are
    // registered in init once construction is complete.
    private val itemsView = host.observe(refs.items.ref, View.set<String>())
    private val valuationsView = host.observe(refs.vals.ref, View.set<Valuation>())
    private val prefsView = host.observe(refs.prefs.ref, View.set<Pref>())
    private val tierAvgView = host.observe(refs.tierAvg.ref, View.map<String, Double>())
    private val prefAvgView = host.observe(refs.prefAvg.ref, View.map<String, Double>())
    private val fusedView = host.observe(refs.fused.ref, View.map<String, Tiered>())

    /** The converged manual map, read off the [UntagCell] rather than the OR-map. */
    private val manualView = host.observe(refs.manualEffective.ref, View.map<String, String>())

    /** What the UI board and `/state`'s `"board"` render: fused, manual-overridden. */
    private val boardView = host.observe(refs.board.ref, View.map<String, Tiered>())

    // KeyedSetCell now owns the retract-old memory (F-3), so the app no longer
    // keeps a Valuation-valued shadow index. This lightweight KEY set exists only
    // so `unitem` can enumerate an item's valuation keys to cascade — the F-3
    // residual. These are the app's *authoritative* write-side record, maintained
    // synchronously in the op handlers. The `valuations`/`prefs` fields above
    // are the async read model (folded off the SSE hubs); cascades must use
    // THESE indices, never the read model, or a signal added just before an
    // unitem is missed and ghosts the removed item onto the board.
    private val liveValKeys = mutableSetOf<Pair<String, String>>()  // (agent,item) keys with a live valuation; authoritative write-side record for the unitem cascade
    private val livePrefs = mutableSetOf<Pref>()

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    /**
     * The peering port this JVM is listening on, or null in dial/solo mode.
     * Distinct from `Wire.Listen.wsPort`, which is what was *asked for*:
     * `--listen 0` means "any free port" and only the listener knows which one
     * it got (computenet-dqy.25), so this — not the requested value — is what
     * `main` announces.
     */
    val boundWsPort: Int? get() = wsListener?.port

    /** This host's manual-replica instance id — 0 listener/solo, 1 dialer. */
    val manualInstanceId: Long get() = manualCell.ref.instanceId

    init {
        // Register one broadcast per sink now that all eight exist; registering
        // fires an immediate catch-up (harmless — clients is still empty).
        itemsView.onChange { broadcast() }
        valuationsView.onChange { broadcast() }
        prefsView.onChange { broadcast() }
        tierAvgView.onChange { broadcast() }
        prefAvgView.onChange { broadcast() }
        fusedView.onChange { broadcast() }
        manualView.onChange { broadcast() }
        boardView.onChange { broadcast() }

        if (wire != null) {
            val side = Peering.Side(registry, bridgeHost!!)
            when (wire) {
                is Wire.Listen -> wsListener = WsTransport.listen(wire.wsPort, side)
                is Wire.Dial -> WsTransport.connect(URI(wire.uri), side)
            }
        }

        // The graph is fixed and fully built by now, so replay is the plain
        // ManagedHost form (ManagedHost.kt:749, KDoc at :68-75 — rebuild the
        // graph, THEN recover). Only routed invocations were journaled, which
        // is why every write path above is a hosted lookup.
        journal?.let { host.recoverFrom(it) }

        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        shell.sse("/events") { stateJson() }
    }

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }

        fun name(key: String): String? =
            params[key]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }

        when (params["action"]) {
            "item" -> {
                val item = name("name") ?: return exchange.respond(400, "missing name")
                itemOps.add(item)
            }

            "unitem" -> {
                val item = name("name") ?: return exchange.respond(400, "missing name")
                itemOps.remove(item)
                // cascade the item's own signals so it doesn't haunt the board.
                // Drive from the authoritative write-side indices, not the async
                // read model, so a signal added moments earlier is never missed.
                synchronized(state) {
                    liveValKeys.filter { it.second == item }.forEach { key ->
                        valOps.remove(key); liveValKeys -= key
                    }
                    livePrefs.filter { it.winner == item || it.loser == item }.forEach {
                        livePrefs -= it; prefOps.remove(it)
                    }
                }
                // and the manual pin, which would otherwise hold the removed
                // item on the board on its own (the override lane is a board
                // input like any other). Reset-remove: it tombstones only the
                // dots this host has observed, so a concurrent re-tier at the
                // peer survives, which is [KE1-30]'s shape.
                manualOps.remove(item)
            }

            "tier" -> {
                val agent = name("agent") ?: return exchange.respond(400, "missing agent")
                val item = name("item") ?: return exchange.respond(400, "missing item")
                val tier = params["tier"]?.takeIf { it in Tiering.TIERS || it == "none" }
                    ?: return exchange.respond(400, "tier must be one of ${Tiering.TIERS} or none")
                synchronized(state) {
                    if (tier != "none") {
                        valOps.put(agent to item, Valuation(agent, item, Tiering.SCORE_OF.getValue(tier)))
                        liveValKeys += agent to item
                    } else {
                        valOps.remove(agent to item)
                        liveValKeys -= agent to item
                    }
                }
            }

            // The manual re-tier lane: a pin that overrides whatever the
            // signals fused to, and `none` to release it back to the computed
            // tier. Validated exactly as `tier` above. Written through the
            // routed [manualOps] proxy, so `--journal` replays it.
            "retier" -> {
                val item = name("item") ?: return exchange.respond(400, "missing item")
                val tier = params["tier"]?.takeIf { it in Tiering.TIERS || it == "none" }
                    ?: return exchange.respond(400, "tier must be one of ${Tiering.TIERS} or none")
                if (tier != "none") manualOps.put(item, tier) else manualOps.remove(item)
            }

            "pref", "unpref" -> {
                val agent = name("agent") ?: return exchange.respond(400, "missing agent")
                val winner = name("winner") ?: return exchange.respond(400, "missing winner")
                val loser = name("loser") ?: return exchange.respond(400, "missing loser")
                if (winner == loser) return exchange.respond(400, "winner and loser must differ")
                val p = Pref(agent, winner, loser)
                synchronized(state) {
                    if (params["action"] == "pref") { livePrefs += p; prefOps.add(p) }
                    else { livePrefs -= p; prefOps.remove(p) }
                }
            }

            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String {
        fun num(d: Double) = "%.4f".format(Locale.ROOT, d)

        val items = itemsView.current()
        val valuations = valuationsView.current()
        val prefs = prefsView.current()
        val tierAvg = tierAvgView.current()
        val prefAvg = prefAvgView.current()
        val fused = fusedView.current()
        // The board renders the OVERRIDE cell — fused with the converged
        // manual pins applied. The signals table below still reads `fused`
        // and the two GroupBy averages, unchanged: it is the *computed*
        // pipeline's read-out, and a pin is not a computation.
        val tiered = boardView.current()
        val manual = manualView.current()

        val board = Tiering.TIERS.joinToString(",") { tier ->
            val entries = tiered.filterValues { it.tier == tier }.entries
                .sortedByDescending { it.value.score }
                .joinToString(",", "[", "]") { (item, t) -> """{"item":${esc(item)},"score":${num(t.score)}}""" }
            "${esc(tier)}:$entries"
        }
        val unrated = (items - tiered.keys).sorted().joinToString(",", "[", "]") { esc(it) }
        val signals = (fused.keys + items).sorted().joinToString(",", "[", "]") { item ->
            val t = tierAvg[item]?.let { num(it) } ?: "null"
            val p = prefAvg[item]?.let { num(it) } ?: "null"
            val f = fused[item]
            """{"item":${esc(item)},"tierAvg":$t,"prefAvg":$p,""" +
                    """"score":${f?.let { num(it.score) } ?: "null"},"tier":${f?.let { esc(it.tier) } ?: "null"}}"""
        }
        val vals = valuations.sortedWith(compareBy({ it.agent }, { it.item }))
            .joinToString(",", "[", "]") {
                """{"agent":${esc(it.agent)},"item":${esc(it.item)},"tier":${esc(Tiering.TIER_OF_SCORE.getValue(it.score))}}"""
            }
        val prefList = prefs.sortedWith(compareBy({ it.agent }, { it.winner }, { it.loser }))
            .joinToString(",", "[", "]") {
                """{"agent":${esc(it.agent)},"winner":${esc(it.winner)},"loser":${esc(it.loser)}}"""
            }

        // Additive: `"manual"` is appended after every field the existing page
        // and tests already parse, and none of them changed shape.
        val manualJson = manual.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (item, tier) -> "${esc(item)}:${esc(tier)}" }

        return """{"items":${items.sorted().joinToString(",", "[", "]") { esc(it) }},""" +
                """"board":{$board,"unrated":$unrated},"signals":$signals,""" +
                """"valuations":$vals,"prefs":$prefList,"manual":$manualJson}"""
    }

    fun start(): TieringApp = apply { shell.start() }

    fun stop() = shell.stop()
}

fun main(args: Array<String>) {
    // `demoPort` reads the first non-`--` token as this demo's port, so every
    // `--flag value` pair has to be stripped before it or one of their values
    // is mistaken for the port (demo/shopping's `main`, verbatim).
    val demoArgs = stripPairs(args, "--listen", "--peer", "--journal")
    val port = demoPort(demoArgs)
    val wire = args.value("--listen")?.let { TieringApp.Wire.Listen(it.toInt()) }
        ?: args.value("--peer")?.let { TieringApp.Wire.Dial(it) }
    val journalDir = args.value("--journal")?.let { java.io.File(it).apply { mkdirs() } }

    val app = TieringApp(port, wire, journalDir).start()
    println("computenet tiering: http://localhost:${app.boundPort}")
    // every announcePort here reports a port this process HOLDS, so a
    // supervising test never has to pick one for it (computenet-dqy.25)
    announcePort("http", app.boundPort)
    when (wire) {
        is TieringApp.Wire.Listen -> {
            // the BOUND port, not `wire.wsPort`: `--listen 0` asks for any free one
            val wsPort = checkNotNull(app.boundWsPort) { "a listening peer must have a bound ws port" }
            println("  awaiting a peer on ws://localhost:$wsPort")
            announcePort("ws", wsPort)
        }

        is TieringApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
    println("  manual re-tier replica ${TierPipeline.MANUAL_ID}, this JVM's instance ${app.manualInstanceId}")
}

/** Drop each `--flag value` pair from [args] — see [main]'s use. */
private fun stripPairs(args: Array<String>, vararg flags: String): Array<String> {
    val rest = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] in flags) i += 2 else rest += args[i++]
    }
    return rest.toTypedArray()
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>tiering — incremental tier board</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1080px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; align-items: flex-start; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 300px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  .tierrow { display: flex; align-items: stretch; border: 1px solid var(--line); border-radius: 8px; margin: .25rem 0; overflow: hidden; min-height: 2.2rem; }
  .tierrow .label { flex: 0 0 2.4rem; display: flex; align-items: center; justify-content: center; font-weight: 700; }
  .tierrow .slots { flex: 1; display: flex; flex-wrap: wrap; gap: .3rem; padding: .3rem .5rem; align-items: center; }
  .t-S .label { background: #fecaca; } .t-A .label { background: #fed7aa; } .t-B .label { background: #fde68a; }
  .t-C .label { background: #fef08a; } .t-D .label { background: #d9f99d; } .t-E .label { background: #bbf7d0; }
  .t-F .label { background: #bfdbfe; } .t-U .label { background: #f3f4f6; color: var(--dim); font-size: .7rem; }
  .chip { background: #fff; border: 1px solid var(--line); border-radius: 999px; padding: .1rem .55rem; font-size: .8rem; }
  .chip small { color: var(--dim); }
  form { display: flex; gap: .4rem; margin-bottom: .6rem; flex-wrap: wrap; }
  input, select { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  form button, .beats { padding: .35rem .7rem; border: none; border-radius: 6px; background: var(--blue); color: #fff; cursor: pointer; }
  .itemrow { display: flex; align-items: center; gap: .4rem; margin: .25rem 0; font-size: .9rem; flex-wrap: wrap; }
  .itemrow b { min-width: 5.5rem; }
  .pick button { width: 1.7rem; height: 1.5rem; border: 1px solid var(--line); background: #fff; cursor: pointer; font-size: .7rem; border-radius: 4px; }
  .pick button.mine { background: var(--blue); border-color: var(--blue); color: #fff; }
  .pick.pin { margin-left: .5rem; }
  .pick.pin small { color: var(--dim); margin-right: .25rem; }
  .pick.pin button.mine { background: #7c3aed; border-color: #7c3aed; }
  .itemrow .del { margin-left: .5rem; border: none; background: none; color: var(--dim); cursor: pointer; font-size: 1rem; line-height: 1; }
  .itemrow .del:hover { color: #dc2626; }
  .prefline { font-size: .85rem; margin: .2rem 0; }
  .prefline button { border: none; background: none; color: var(--dim); cursor: pointer; }
  table { border-collapse: collapse; font-size: .8rem; width: 100%; }
  th, td { text-align: left; padding: .2rem .5rem; border-bottom: 1px solid var(--line); }
  th { color: var(--dim); font-weight: normal; }
  #agent { width: 8rem; }
</style>
</head>
<body>
<h1>Tier board <small>absolute valuations ⊕ pairwise preferences, fused incrementally</small></h1>
<div class="row">
  <div class="card" style="flex:2">
    <h2>Global tiers</h2>
    <div id="board"></div>
  </div>
  <div class="card">
    <h2>You</h2>
    <form id="agentForm">as <input id="agent"></form>
    <h2>Add item</h2>
    <form id="itemForm"><input id="itemName" placeholder="item"><button>Add</button></form>
    <h2>Your tier per item</h2>
    <div id="rate"></div>
    <h2>Preference</h2>
    <form id="prefForm"><select id="winner"></select><button class="beats">beats</button><select id="loser"></select></form>
    <div id="prefs"></div>
  </div>
</div>
<div class="row">
  <div class="card"><h2>Signals (intermediate views)</h2>
    <table><thead><tr><th>item</th><th>tier avg (0–6)</th><th>pref avg (−1…1)</th><th>fused</th><th>tier</th></tr></thead><tbody id="signals"></tbody></table>
  </div>
</div>
<script>
const TIERS = ["S","A","B","C","D","E","F"];
let state = {};
const agentInput = document.getElementById('agent');
agentInput.value = sessionStorage.agent ??= 'user-' + Math.random().toString(36).slice(2, 6);
agentInput.onchange = () => { sessionStorage.agent = agentInput.value.trim(); render(); };
const me = () => agentInput.value.trim();
const op = body => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams(body) });

document.getElementById('agentForm').onsubmit = e => e.preventDefault();
document.getElementById('itemForm').onsubmit = e => {
  e.preventDefault();
  const name = itemName.value.trim();
  if (name) op({ action: 'item', name });
  itemName.value = '';
};
document.getElementById('prefForm').onsubmit = e => {
  e.preventDefault();
  if (winner.value && loser.value && winner.value !== loser.value)
    op({ action: 'pref', agent: me(), winner: winner.value, loser: loser.value });
};

function render() {
  const board = document.getElementById('board'); board.innerHTML = '';
  for (const tier of TIERS.concat(['unrated'])) {
    const row = document.createElement('div');
    row.className = 'tierrow t-' + (tier === 'unrated' ? 'U' : tier);
    const label = document.createElement('div'); label.className = 'label';
    label.textContent = tier === 'unrated' ? 'new' : tier; row.appendChild(label);
    const slots = document.createElement('div'); slots.className = 'slots';
    for (const entry of (state.board || {})[tier] || []) {
      const chip = document.createElement('span'); chip.className = 'chip';
      if (tier === 'unrated') chip.textContent = entry;
      else { chip.innerHTML = ''; chip.append(entry.item + ' '); const s = document.createElement('small'); s.textContent = entry.score; chip.appendChild(s); }
      slots.appendChild(chip);
    }
    row.appendChild(slots); board.appendChild(row);
  }

  const mine = {};
  for (const v of state.valuations || []) if (v.agent === me()) mine[v.item] = v.tier;
  const rate = document.getElementById('rate'); rate.innerHTML = '';
  for (const item of state.items || []) {
    const div = document.createElement('div'); div.className = 'itemrow';
    const b = document.createElement('b'); b.textContent = item; div.appendChild(b);
    const pick = document.createElement('span'); pick.className = 'pick';
    for (const t of TIERS.concat(['none'])) {
      const btn = document.createElement('button');
      btn.textContent = t === 'none' ? '∅' : t;
      if (mine[item] === t) btn.classList.add('mine');
      btn.onclick = () => op({ action: 'tier', agent: me(), item, tier: t });
      pick.appendChild(btn);
    }
    div.appendChild(pick);
    // manual re-tier: a pin that overrides the fused tier on every host
    // (replicated OR-map → UntagCell → board override). '∅' releases it.
    const pinned = (state.manual || {})[item];
    const pin = document.createElement('span'); pin.className = 'pick pin';
    const pinLabel = document.createElement('small'); pinLabel.textContent = 'pin'; pin.appendChild(pinLabel);
    for (const t of TIERS.concat(['none'])) {
      const btn = document.createElement('button');
      btn.textContent = t === 'none' ? '∅' : t;
      if (pinned === t) btn.classList.add('mine');
      btn.onclick = () => op({ action: 'retier', item, tier: t });
      pin.appendChild(btn);
    }
    div.appendChild(pin);
    const del = document.createElement('button');
    del.textContent = '×'; del.className = 'del'; del.title = 'remove item (retracts it and its signals globally)';
    del.onclick = () => op({ action: 'unitem', name: item });
    div.appendChild(del); rate.appendChild(div);
  }

  for (const sel of [winner, loser]) {
    const prev = sel.value; sel.innerHTML = '';
    for (const item of state.items || []) {
      const o = document.createElement('option'); o.value = o.textContent = item; sel.appendChild(o);
    }
    if (prev) sel.value = prev;
  }

  const prefsEl = document.getElementById('prefs'); prefsEl.innerHTML = '';
  for (const p of state.prefs || []) {
    const div = document.createElement('div'); div.className = 'prefline';
    div.append(p.agent + ': ' + p.winner + ' ≻ ' + p.loser + ' ');
    const x = document.createElement('button'); x.textContent = '×';
    x.onclick = () => op({ action: 'unpref', agent: p.agent, winner: p.winner, loser: p.loser });
    div.appendChild(x); prefsEl.appendChild(div);
  }

  const tbody = document.getElementById('signals'); tbody.innerHTML = '';
  for (const s of state.signals || []) {
    const tr = document.createElement('tr');
    for (const v of [s.item, s.tierAvg, s.prefAvg, s.score, s.tier]) {
      const td = document.createElement('td'); td.textContent = v ?? '—'; tr.appendChild(td);
    }
    tbody.appendChild(tr);
  }
}
new EventSource('/events').onmessage = e => { state = JSON.parse(e.data); render(); };
</script>
</body>
</html>
"""
