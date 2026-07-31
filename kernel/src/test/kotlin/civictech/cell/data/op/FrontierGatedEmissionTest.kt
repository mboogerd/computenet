package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
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

    private companion object {
        /** What `combine` yields for a key held by only one side. */
        const val NULL_EXTENSION = -1
    }
}
