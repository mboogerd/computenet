package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.control.absorbAck
import civictech.cell.Timestamp
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * `emitOnFrontier` (spec 20/24 `[24-OP-SEMIJOIN-04]` and the mirrored
 * `CombineLatestCell` clause; 96 §E2.4): the opt-in gate that holds a
 * non-monotone operator's emission until the input wave is complete and then
 * emits the wave's **net** effect, so a within-wave flicker cancels before the
 * wire.
 *
 * The register is [civictech.cell.consistency.GlitchFreeDiamondTest]'s: one
 * source fans out to two derived arms which feed the operator's two inlets, and
 * seeded partial draining across the arms' hosts randomizes which arm reaches
 * the operator first within a wave. Both arms **always** emit, and both
 * arm→operator edges are fused rather than rerouted, so the CC1 reroute landmine
 * (a synchronous `Progress` ack overtaking a queued delta on one edge) cannot
 * arise here.
 *
 * The invariants:
 *  - **gated**: exactly one delta per wave, carrying only the net enter/exit
 *    (`SemiJoinCell`) or the settled combined value (`CombineLatestCell`), with
 *    `MintedTags` hygiene preserved — no tombstone for a tag never advertised;
 *  - **ungated control**: the same seed range flickers / emits-then-retracts, so
 *    the harness demonstrably has teeth;
 *  - **liveness**: nothing stays buffered at `runToIdle`, including a wave one
 *    arm — or every arm — absorbs entirely, and including after an `EdgeClose`.
 */
class FrontierGatedEmissionTest {

    // ------------------------------------------------------------- harness

    /** Routed-delivery proxies (the `GlitchFreeDiamondTest` device): must be non-private. */
    interface SetArmProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    interface MapArmProxy {
        val inlet: Use<Propagate<MapDelta<String, Int>>>
    }

    @Suppress("UNCHECKED_CAST")
    private val setApi = Propagate::class.java as Class<Propagate<SetDelta<String>>>

    @Suppress("UNCHECKED_CAST")
    private val mapApi = Propagate::class.java as Class<Propagate<MapDelta<String, Int>>>

    private class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private class MapSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())
        fun send(delta: MapDelta<String, Int>) = outlet.call.propagate(delta)
    }

    /** A derived arm: one input delta in, one output delta out, reactively (so the wave rides through). */
    private class SetArm(
        clazz: Class<Propagate<SetDelta<String>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
        private val f: (SetDelta<String>) -> SetDelta<String>,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { outlet.call.propagate(f(it)) }
        }
    }

    private class MapArm(
        clazz: Class<Propagate<MapDelta<String, Int>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
        private val f: (MapDelta<String, Int>) -> MapDelta<String, Int>,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())

        init {
            inlet.onEach { outlet.call.propagate(f(it)) }
        }
    }

    private class SetObserver(
        clazz: Class<Propagate<SetDelta<String>>>,
        val seen: MutableList<Seen<SetDelta<String>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.onEach { seen += Seen(CurrentContext.get()!!.timestamp, it) }
        }
    }

    private class MapObserver(
        clazz: Class<Propagate<MapDelta<String, Int>>>,
        val seen: MutableList<Seen<MapDelta<String, Int>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.onEach { seen += Seen(CurrentContext.get()!!.timestamp, it) }
        }
    }

    private data class Seen<D>(val timestamp: Timestamp, val delta: D)

    // ------------------------------------------------- SemiJoinCell diamond

    /**
     * The right arm's image of one source wave `w<n>`:
     *  - even `n` → `Xw<n>`, whose key matches no left row (so `Lw<n>` enters);
     *  - odd `n`  → `Rw<n>` (key `w<n>`, matching `Lw<n>` — which therefore never
     *    enters) plus, from `n >= 3`, `Rw<n-1>`, which retires the row the
     *    previous even wave entered.
     *
     * So the antijoin's net effect per wave is fully determined — add on even
     * waves, del on odd waves from 3 — while *within* a wave the left arm's add
     * and the right arm's matching add are exactly the opposing updates that make
     * an ungated cell flicker.
     */
    private fun rightImage(delta: SetDelta<String>): SetDelta<String> {
        val adds = LinkedHashMap<String, Set<Timestamp>>()
        delta.adds.forEach { (element, tags) ->
            val n = element.removePrefix("w").toInt()
            if (n % 2 == 0) {
                adds["X$element"] = tags
            } else {
                adds["R$element"] = tags
                if (n >= 3) adds["Rw${n - 1}"] = tags
            }
        }
        return SetDelta(adds = adds)
    }

    private fun runSemiJoinDiamond(seed: Long, waves: Int, gated: Boolean): List<Seen<SetDelta<String>>> {
        val controller = SimulationController(seed)
        val hostL = ManagedHost(scheduler = controller.scheduler())
        val hostR = ManagedHost(scheduler = controller.scheduler())

        val source = SetSource()
        val leftArm = SetArm(setApi) { d -> SetDelta(adds = d.adds.mapKeys { (k, _) -> "L$k" }) }
        val rightArm = SetArm(setApi) { d -> rightImage(d) }
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it.removePrefix("L") },
            rightKey = { it.removePrefix("R") },
            negated = true,
            emitOnFrontier = gated,
        )
        val seen = mutableListOf<Seen<SetDelta<String>>>()
        val observer = SetObserver(setApi, seen)

        listOf(source, leftArm, join, observer).forEach { hostL.managementInlet.call.spawn(it) }
        hostR.managementInlet.call.spawn(rightArm)

        // the source fans out to both arms through their own host queues, so the
        // controller's seeded cross-host pick decides which arm reaches the join
        // first within a wave
        source.outlet.subscribe(Use.fixed(hostL.lookup<SetArmProxy>(leftArm.ref)!!.inlet.call, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(hostR.lookup<SetArmProxy>(rightArm.ref)!!.inlet.call, PortRef.generate()))

        @Suppress("UNCHECKED_CAST")
        leftArm.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        rightArm.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
        join.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        val rnd = Random(seed)
        val tagSource = UUID.randomUUID()
        for (n in 1..waves) {
            source.send(SetDelta(adds = mapOf("w$n" to setOf(Timestamp(tagSource, n.toLong())))))
            repeat(rnd.nextInt(4)) { controller.step() } // partial, seed-randomized draining
        }
        controller.runToIdle()

        join.bufferedWaves shouldBe 0 // liveness: no wave left buffered at idle
        return seen
    }

    @Test
    fun `gated SemiJoinCell emits one net delta per wave and never flickers, over 200 seeds`() {
        val waves = 20
        for (seed in 0L until 200L) {
            val seen = runSemiJoinDiamond(seed, waves, gated = true)

            seen.groupBy { it.timestamp }.forEach { (timestamp, group) ->
                group.size shouldBe 1 // coalesced to the wave's net effect
                val delta = group.single().delta
                withClue(timestamp) { (delta.adds.keys intersect delta.dels.keys).shouldBeEmpty() }
            }

            // the exact net effect, wave by wave — an enter-then-exit that
            // cancelled inside the wave shows up here as a missing/extra row
            val byCounter = seen.associate { it.timestamp.counter to it.delta }
            for (n in 1..waves) {
                val delta = byCounter[n.toLong()]
                when {
                    n % 2 == 0 -> {
                        delta!!.adds.keys shouldBe setOf("Lw$n")
                        delta.dels.keys.shouldBeEmpty()
                    }

                    n >= 3 -> {
                        delta!!.dels.keys shouldBe setOf("Lw${n - 1}")
                        delta.adds.keys.shouldBeEmpty()
                    }
                    // wave 1: Lw1 is matched the moment it arrives, so the whole
                    // wave is net-neutral and absorb-acks instead of emitting
                    else -> delta shouldBe null
                }
            }

            // MintedTags hygiene (`[24-OP-SEMIJOIN-02]`): every retracted tag was
            // advertised on the wire first — reconciling on membership before
            // minting is what guarantees it.
            val advertised = mutableSetOf<Timestamp>()
            seen.forEach { s ->
                s.delta.adds.values.forEach { advertised += it }
                s.delta.dels.values.flatten().forEach { tag -> (tag in advertised).shouldBeTrue() }
            }
        }
    }

    @Test
    fun `control - the ungated SemiJoinCell flickers within a wave on at least one seed`() {
        var flickered = 0
        for (seed in 0L until 50L) {
            val seen = runSemiJoinDiamond(seed, waves = 20, gated = false)
            val flicker = seen.groupBy { it.timestamp }.any { (_, group) ->
                val entered = mutableSetOf<String>()
                var found = false
                group.forEach { s ->
                    s.delta.dels.keys.forEach { if (it in entered) found = true }
                    entered += s.delta.adds.keys
                }
                found
            }
            if (flicker) flickered++
        }
        // if this fails the harness is too weak to detect the flicker — tune
        // interleaving. Measured 2026-07-31: 48 of these 50 seeds flicker.
        (flickered > 0).shouldBeTrue()
    }

    // -------------------------------------------- CombineLatestCell diamond

    /** A key whose other side never arrives — its null-extension is genuine, not transient. */
    private fun soloKey(n: Int) = "solo$n"

    private fun runCombineDiamond(seed: Long, waves: Int, gated: Boolean): List<Seen<MapDelta<String, Int>>> {
        val controller = SimulationController(seed)
        val hostL = ManagedHost(scheduler = controller.scheduler())
        val hostR = ManagedHost(scheduler = controller.scheduler())

        val source = MapSource()
        val leftArm = MapArm(mapApi) { d ->
            val puts = LinkedHashMap<String, Int>()
            d.puts.forEach { (_, n) -> puts["k$n"] = n; puts[soloKey(n)] = n }
            MapDelta(puts, emptySet())
        }
        val rightArm = MapArm(mapApi) { d ->
            MapDelta(d.puts.values.associate { n -> "k$n" to n * 10 }, emptySet())
        }
        val combine = CombineLatestCell<String, Int, Int, Int>(emitOnFrontier = gated) { _, v, w ->
            if (v == null || w == null) NULL_EXTENSION else v + w
        }
        val seen = mutableListOf<Seen<MapDelta<String, Int>>>()
        val observer = MapObserver(mapApi, seen)

        listOf(source, leftArm, combine, observer).forEach { hostL.managementInlet.call.spawn(it) }
        hostR.managementInlet.call.spawn(rightArm)

        source.outlet.subscribe(Use.fixed(hostL.lookup<MapArmProxy>(leftArm.ref)!!.inlet.call, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(hostR.lookup<MapArmProxy>(rightArm.ref)!!.inlet.call, PortRef.generate()))

        @Suppress("UNCHECKED_CAST")
        leftArm.outlet.linkTo(combine.left as LinkFrom<Propagate<MapDelta<String, Int>>>)

        @Suppress("UNCHECKED_CAST")
        rightArm.outlet.linkTo(combine.right as LinkFrom<Propagate<MapDelta<String, Int>>>)
        combine.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        val rnd = Random(seed)
        for (n in 1..waves) {
            source.send(MapDelta(mapOf("w$n" to n), emptySet()))
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        combine.bufferedWaves shouldBe 0
        return seen
    }

    @Test
    fun `gated CombineLatestCell never emits a null-extension it retracts in the same wave, over 200 seeds`() {
        val waves = 20
        for (seed in 0L until 200L) {
            val seen = runCombineDiamond(seed, waves, gated = true)

            seen.groupBy { it.timestamp }.forEach { (_, group) -> group.size shouldBe 1 }

            val byCounter = seen.associate { it.timestamp.counter to it.delta }
            for (n in 1..waves) {
                val delta = byCounter[n.toLong()]!!
                withClue("seed $seed wave $n") {
                    // both sides of "k$n" arrive in this wave, so the gated cell
                    // only ever sees the settled pair — never the null-extension
                    delta.puts["k$n"] shouldBe n * 11
                    // outer semantics preserved: a genuinely one-sided key still
                    // null-extends, at completeness
                    delta.puts[soloKey(n)] shouldBe NULL_EXTENSION
                    delta.removals.shouldBeEmpty()
                }
            }
        }
    }

    @Test
    fun `control - the ungated CombineLatestCell emits and retracts a null-extension within one wave`() {
        var retracted = 0
        for (seed in 0L until 50L) {
            val seen = runCombineDiamond(seed, waves = 20, gated = false)
            val flicker = seen.groupBy { it.timestamp }.any { (_, group) ->
                val nullExtended = mutableSetOf<String>()
                var found = false
                group.forEach { s ->
                    s.delta.puts.forEach { (key, value) ->
                        if (key in nullExtended && value != NULL_EXTENSION) found = true
                        if (value == NULL_EXTENSION && key.startsWith("k")) nullExtended += key
                    }
                }
                found
            }
            if (flicker) retracted++
        }
        // measured 2026-07-31: all 50 of these seeds emit-then-retract ungated
        (retracted > 0).shouldBeTrue()
    }

    // ------------------------------------------------------------ liveness

    @Test
    fun `a gated cell settles a wave one arm absorbs entirely, and after that arm's edge closes`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = SetSource()
        val leftArm = SetArm(setApi) { d -> SetDelta(adds = d.adds.mapKeys { (k, _) -> "L$k" }) }
        // absorbs every wave and absorb-acks it (CP-A3) — never a delta on this edge
        val rightArm = FilterCell<String> { false }
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it.removePrefix("L") },
            rightKey = { it },
            negated = true,
            emitOnFrontier = true,
        )
        val seen = mutableListOf<Seen<SetDelta<String>>>()
        val observer = SetObserver(setApi, seen)
        listOf(source, leftArm, rightArm, join, observer).forEach { host.managementInlet.call.spawn(it) }

        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(leftArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(rightArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        leftArm.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        rightArm.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
        join.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        val tagSource = UUID.randomUUID()
        source.send(SetDelta(adds = mapOf("w1" to setOf(Timestamp(tagSource, 1L)))))
        controller.runToIdle()

        // the right arm's absorb-ack is what settled this wave's right edge
        join.bufferedWaves shouldBe 0
        seen.single().delta.adds.keys shouldBe setOf("Lw1")

        // EdgeClose shrinks the completeness condition exactly as it does for the frontier
        join.right.linking.links.single().unlink()
        controller.runToIdle()
        source.send(SetDelta(adds = mapOf("w2" to setOf(Timestamp(tagSource, 2L)))))
        controller.runToIdle()

        join.bufferedWaves shouldBe 0
        seen.last().delta.adds.keys shouldBe setOf("Lw2")
    }

    @Test
    fun `a gated cell retires a wave every arm absorbs entirely`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = SetSource()
        val leftArm = FilterCell<String> { false }
        val rightArm = FilterCell<String> { false }
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it },
            rightKey = { it },
            negated = true,
            emitOnFrontier = true,
        )
        val seen = mutableListOf<Seen<SetDelta<String>>>()
        val observer = SetObserver(setApi, seen)
        listOf(source, leftArm, rightArm, join, observer).forEach { host.managementInlet.call.spawn(it) }

        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(leftArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(rightArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        leftArm.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)

        @Suppress("UNCHECKED_CAST")
        rightArm.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
        join.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        val tagSource = UUID.randomUUID()
        source.send(SetDelta(adds = mapOf("w1" to setOf(Timestamp(tagSource, 1L)))))
        controller.runToIdle()

        // known only from two absorb-acks: buffered by noteAbsorbed, completed,
        // reconciled to nothing, and absorb-acked onward — never left pending
        join.bufferedWaves shouldBe 0
        seen.shouldBeEmpty()
    }

    // -------------------------------------- disjoint-wave arms (G-13/G-40)

    /**
     * The item-kind split (computenet-23bf): both inlets descend from one root,
     * so the topology reads as [WaveGate]'s shared-source diamond, but each arm
     * carries a **disjoint subset** of the root's waves — a left-kind element is
     * a real delta on the left arm and nothing at all on the right, and the
     * mirror image for a right-kind element.
     *
     * The wave that an arm structurally cannot deliver is retired by that arm's
     * CP-A3 absorb-ack ([civictech.cell.control.absorbAck]) — but the ack is
     * **edge-local**. It is minted by the absorbing operator onto its own outlet
     * links and **no plain cell relays it**: a `FlatMapSetCell`/`FilterCell` hop
     * has no [civictech.cell.protocol.Protocols.Progress] handler, so an ack
     * arriving on its inlet neither advances anything nor is re-emitted. Only a
     * cell that installs a frontier ([WaveGate], `WaveFrontier`,
     * `CoalescingCombineCell`, `AlignedCompositeCell`) consumes one.
     *
     * So the depth of the silent arm decides everything, and it is the *depth*,
     * not the disjointness, that breaks the gate:
     *
     *  - **one hop** (the absorber links straight into the gated inlet): the ack
     *    lands on the expected edge, the wave completes, the gate is correct —
     *    this is `a gated cell settles a wave one arm absorbs entirely` above;
     *  - **two or more hops** (an absorber with a pure hop below it, which is the
     *    AGO1 relation leg's shape): the ack dies at the hop, the expected edge
     *    never settles for that wave, and the wave is held until the arm delivers
     *    a *later* wave — so mid-stream output LAGS, and at rest, when the final
     *    wave is one the arm never carries, output is WITHHELD PERMANENTLY.
     *
     * That is the answer to the discriminating question this test exists for:
     * **both, and which one you see depends only on whether a later wave follows**.
     */
    private inner class DisjointArmRig(hops: Int) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetSource()
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it.removePrefix("L") },
            rightKey = { it.removePrefix("R") },
            negated = false,
            emitOnFrontier = true,
        )
        val seen = mutableListOf<Seen<SetDelta<String>>>()
        private val tagSource = UUID.randomUUID()

        /**
         * One arm: a [FilterCell] that admits only its own item [kind] — and
         * absorb-acks every wave carrying the other kind — followed by
         * `hops - 1` pure identity [FlatMapSetCell] hops. That is the AGO1
         * relation leg's shape (`extractedItems -> extractedRelations ->
         * relationCandidates -> nonSelfRelations`) reduced to its essentials.
         */
        private fun arm(kind: String, hops: Int) {
            val head = FilterCell<String> { it.startsWith(kind) }
            host.managementInlet.call.spawn(head)

            @Suppress("UNCHECKED_CAST")
            source.outlet.linkTo(head.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            var tail: FanOutlet<Propagate<SetDelta<String>>> = head.outlet
            repeat(hops - 1) {
                val hop = FlatMapSetCell<String, String>(f = { listOf(it) })
                host.managementInlet.call.spawn(hop)

                @Suppress("UNCHECKED_CAST")
                tail.linkTo(hop.inlet as LinkFrom<Propagate<SetDelta<String>>>)
                tail = hop.outlet
            }
            val gatedInlet = if (kind == "L") join.left else join.right

            @Suppress("UNCHECKED_CAST")
            tail.linkTo(gatedInlet as LinkFrom<Propagate<SetDelta<String>>>)
        }

        init {
            val observer = SetObserver(setApi, seen)
            listOf(source, join, observer).forEach { host.managementInlet.call.spawn(it) }
            arm("L", hops)
            arm("R", hops)
            join.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
            controller.runToIdle()
        }

        fun send(counter: Long, element: String) {
            source.send(SetDelta(adds = mapOf(element to setOf(Timestamp(tagSource, counter)))))
            controller.runToIdle()
        }
    }

    @Test
    fun `control - disjoint-wave arms one hop deep settle, because the absorb-ack lands on the gated edge`() {
        val rig = DisjointArmRig(hops = 1)

        rig.send(1L, "L1") // left-kind wave: the right arm absorbs and acks
        rig.join.bufferedWaves shouldBe 0
        rig.seen.shouldBeEmpty() // L1 is live but unmatched, so the semijoin holds it out

        rig.send(2L, "R1") // right-kind wave: the left arm absorbs and acks
        rig.join.bufferedWaves shouldBe 0
        rig.seen.single().delta.adds.keys shouldBe setOf("L1")
    }

    @Test
    fun `disjoint-wave arms TWO hops deep withhold output at rest - the absorb-ack dies at the intervening hop`() {
        val rig = DisjointArmRig(hops = 2)

        rig.send(1L, "L1")
        withClue("the right arm's ack died at its identity hop, so wave 1 never completes") {
            rig.join.bufferedWaves shouldBe 1
        }

        rig.send(2L, "R1")
        withClue("WITHHELD AT REST: L1 is matched and live, and nothing has been emitted") {
            // wave 1 did retire here — the right arm's real wave-2 delta advanced
            // its edge watermark past wave 1 (monotone `max`), which is the third
            // advance mechanism standing in for the ack that died. But it retired
            // BEFORE wave 2's right fold was applied, so it reconciled L1 against
            // an empty right side and emitted nothing; and wave 2 itself, the wave
            // that actually makes L1 enter, has no later left wave to release it.
            rig.seen.shouldBeEmpty()
            rig.join.bufferedWaves shouldBe 1
        }

        // ...and the withholding is released only by a LATER wave on the stalled
        // arm (monotone `max` on the per-edge watermark), never by quiescence:
        // this is the LAG half of the same mechanism.
        rig.send(3L, "L3")
        withClue("waves 1 and 2 flush once the left arm delivers wave 3") {
            rig.seen.single().delta.adds.keys shouldBe setOf("L1")
            rig.seen.single().timestamp.counter shouldBe 2L
        }
        withClue("wave 3 is now the one held: the right arm never carries it") {
            rig.join.bufferedWaves shouldBe 1
        }
    }

    // ------------------------------------- CombineLatestCell disjoint-wave arms
    // (computenet-u0oa: F-15's declared "not measured" gap — CombineLatestCell
    // shares WaveGate verbatim with SemiJoinCell (see WaveGate's own KDoc), so
    // this is [DisjointArmRig] above with the gated cell and wire type swapped
    // and nothing else — the same arm shape (a kind-filtering head that
    // absorb-acks every other wave, CP-A3, followed by `hops - 1` pure identity
    // hops), so a green one-hop control here pins the mechanism to the same ack
    // non-relay, not to SemiJoinCell specifically.)

    /**
     * The `MapDelta` analogue of `DisjointArmRig`'s `FilterCell` head: admits
     * only entries whose key carries this arm's [kind] prefix, strips the
     * prefix (so both arms address the same combine key), and — like
     * [FilterCell] — CP-A3 absorb-acks a wave that carries none of its kind
     * instead of leaving it un-acknowledged.
     */
    private class MapFilterHead(
        clazz: Class<Propagate<MapDelta<String, Int>>>,
        private val kind: String,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())

        init {
            inlet.onEach { delta ->
                val puts = delta.puts.filterKeys { it.startsWith(kind) }.mapKeys { (k, _) -> k.removePrefix(kind) }
                val removals = delta.removals.filter { it.startsWith(kind) }.map { it.removePrefix(kind) }.toSet()
                if (puts.isEmpty() && removals.isEmpty()) {
                    outlet.absorbAck() // no entry of this arm's kind in this wave — swallowed, CP-A3
                } else {
                    outlet.call.propagate(MapDelta(puts, removals))
                }
            }
        }
    }

    /** [DisjointArmRig], but the gated cell is `CombineLatestCell` and the wire type is `MapDelta`. */
    private inner class CombineDisjointArmRig(hops: Int) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = MapSource()
        val combine = CombineLatestCell<String, Int, Int, Int>(emitOnFrontier = true) { _, v, w ->
            if (v == null || w == null) NULL_EXTENSION else v + w
        }
        val seen = mutableListOf<Seen<MapDelta<String, Int>>>()

        /** One arm: a [MapFilterHead] admitting only [kind], followed by `hops - 1` pure identity [MapArm] hops. */
        private fun arm(kind: String, hops: Int) {
            val head = MapFilterHead(mapApi, kind)
            host.managementInlet.call.spawn(head)

            @Suppress("UNCHECKED_CAST")
            source.outlet.linkTo(head.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
            var tail: FanOutlet<Propagate<MapDelta<String, Int>>> = head.outlet
            repeat(hops - 1) {
                val hop = MapArm(mapApi) { it } // pure identity hop: installs no Protocols.Progress handler
                host.managementInlet.call.spawn(hop)

                @Suppress("UNCHECKED_CAST")
                tail.linkTo(hop.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
                tail = hop.outlet
            }
            val gatedInlet = if (kind == "L") combine.left else combine.right

            @Suppress("UNCHECKED_CAST")
            tail.linkTo(gatedInlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        }

        init {
            val observer = MapObserver(mapApi, seen)
            listOf(source, combine, observer).forEach { host.managementInlet.call.spawn(it) }
            arm("L", hops)
            arm("R", hops)
            combine.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
            controller.runToIdle()
        }

        /** [key] rides unprefixed once the arm's head strips its kind marker; [element] is `"<kind><key>"`. */
        fun send(element: String, value: Int) {
            source.send(MapDelta(puts = mapOf(element to value), removals = emptySet()))
            controller.runToIdle()
        }
    }

    @Test
    fun `control - CombineLatestCell disjoint-wave arms one hop deep settle, because the absorb-ack lands on the gated edge`() {
        val rig = CombineDisjointArmRig(hops = 1)

        rig.send("L1", 1) // left-kind wave: the right arm absorbs and acks directly onto combine.right
        withClue("the ack landed on the expected edge, so the wave settled and the genuinely one-sided key null-extends") {
            rig.combine.bufferedWaves shouldBe 0
            rig.seen.single().delta.puts["1"] shouldBe NULL_EXTENSION
        }

        rig.send("R1", 10) // right-kind wave: the left arm absorbs and acks directly onto combine.left
        withClue("both sides of key \"1\" are now known and reconciled together, in the wave that completed them") {
            rig.combine.bufferedWaves shouldBe 0
            rig.seen.last().delta.puts["1"] shouldBe 11
        }
    }

    @Test
    fun `CombineLatestCell disjoint-wave arms TWO hops deep emit a null-extension that is never corrected at rest - the absorb-ack dies at the intervening hop`() {
        val rig = CombineDisjointArmRig(hops = 2)

        rig.send("L1", 1)
        withClue("the right arm's ack died at its identity hop, so wave 1's right edge never settled on its own") {
            rig.combine.bufferedWaves shouldBe 1
            rig.seen.shouldBeEmpty()
        }

        rig.send("R1", 10)
        withClue(
            "wave 1 DID retire here — the right arm's real wave-2 delta advanced its edge watermark past wave 1 " +
                "(monotone max), the third advance mechanism standing in for the lost ack. But it retired BEFORE " +
                "wave 2's own right fold applied, so it reconciled key \"1\" against an empty right side and " +
                "emitted a null-extension that a shallower arm would never have shown: unlike SemiJoinCell's " +
                "silent withholding, this is a WRONG value on the wire, and nothing retracts it — MapDiffPublisher " +
                "only re-emits on the NEXT change to the key, which needs wave 2 to flush too.",
        ) {
            rig.seen.single().delta.puts["1"] shouldBe NULL_EXTENSION
            rig.seen.single().timestamp.counter shouldBe 1L
            // wave 2 stalls in turn: the left arm's ack for it died at its own hop,
            // and no later left wave has arrived yet to advance that watermark
            rig.combine.bufferedWaves shouldBe 1
        }

        // AT REST is exactly here: if nothing else ever arrives, key "1" stays
        // published as -1 forever, even though both its real values (1 and 10)
        // are sitting settled in the cell's own leftMap/rightMap — the discharge
        // this gate exists to prevent, reintroduced by the hop depth it can't see.
        withClue("WITHHELD/WRONG AT REST: the settled combine (11) is computed and buffered, never delivered") {
            rig.seen.single().delta.puts["1"] shouldBe NULL_EXTENSION
        }

        // ...and it is released only by a LATER wave on the stalled (left) arm,
        // never by quiescence — the LAG half of the same mechanism.
        rig.send("L2", 100)
        withClue("wave 2 flushes once the left arm delivers wave 3, correcting key \"1\" to its settled value") {
            rig.seen.last().delta.puts["1"] shouldBe 11
            rig.seen.last().timestamp.counter shouldBe 2L
        }
        withClue("wave 3 is now the one held: the right arm never carries it") {
            rig.combine.bufferedWaves shouldBe 1
        }
    }

    private companion object {
        /** What `combine` yields for a key held by only one side. */
        const val NULL_EXTENSION = -1
    }
}
