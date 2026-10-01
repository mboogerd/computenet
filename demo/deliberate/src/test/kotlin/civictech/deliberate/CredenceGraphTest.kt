package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.inlet
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import civictech.testkit.awaitUntil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-graph credence propagation (SPEC CRED-04..06): vector messages, derived consensus. */
class CredenceGraphTest {
    private val schedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun tearDown() = schedulers.forEach { it.shutdown() }

    private fun graph(layers: LayerSet = LayerSet.of(SemanticsCatalog.IDS), log: java.io.File? = null): CredenceGraph {
        val scheduler = VirtualThreadScheduler("credence-graph-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return CredenceGraph(host, registry, layers, structureLog = log)
    }

    private fun CredenceGraph.near(ref: CellRef, want: List<Double>) =
        credenceOf(ref)?.values?.let { v -> v.indices.all { abs(v[it] - want[it]) < 1e-12 } } == true

    /** R ← A (support, .8), R ← B (attack, .6), A ← C (support, .9), A ← D (attack, .5). */
    private fun CredenceGraph.tree(): Map<String, CellRef> {
        val r = createClaim("R")
        val a = createClaim("A")
        val b = createClaim("B")
        val c = createClaim("C")
        val d = createClaim("D")
        val ea = createEdge(a, r, Polarity.SUPPORT)
        val eb = createEdge(b, r, Polarity.ATTACK)
        val ec = createEdge(c, a, Polarity.SUPPORT)
        val ed = createEdge(d, a, Polarity.ATTACK)
        val refs = mapOf("R" to r, "A" to a, "B" to b, "C" to c, "D" to d, "eA" to ea, "eB" to eb, "eC" to ec, "eD" to ed)
        mapOf("R" to 0.7, "A" to 0.9, "B" to 0.4, "C" to 0.3, "D" to 0.8, "eA" to 0.8, "eB" to 0.6, "eC" to 0.9, "eD" to 0.5)
            .forEach { (k, v) -> setStance(refs.getValue(k), "jev", v) }
        return refs
    }

    /** Node, prototype `semantics.js` `evaluateTree` on the same tree, per layer: R and A. */
    private val prototype = mapOf(
        "dfquad" to listOf(0.81591999999999998, 0.78300000000000003),
        "wlo" to listOf(0.87775693895829232, 0.87050815473215959),
        "jnb" to listOf(0.85883921096734783, 0.90737990042083450),
        "woe" to listOf(0.87682928663712545, 0.87673699408171235),
        "euler" to listOf(0.76008123726605459, 0.89387169876513017),
        "qe" to listOf(0.75390794847182951, 0.88504277706755830),
        "mlp" to listOf(0.78875532139705162, 0.88767653023916870),
    )

    @Test
    fun `every layer of one graph equals the prototype's tree evaluation`() {
        val g = graph()
        val refs = g.tree()
        val ids = g.layers.ids
        for ((i, node) in listOf("R", "A").withIndex()) {
            val want = ids.map { prototype.getValue(it)[i] }
            awaitUntil("$node settles on the prototype's values") { g.near(refs.getValue(node), want) }
        }
        // The consensus and the spread arrive with the vector, derived by the cell.
        val root = g.credenceOf(refs.getValue("R"))!!
        assertEquals(Consensus.of(g.layers.named(root.values), Consensus.DEFAULT_MEMBERS), root.consensus, 1e-15)
        assertEquals(root.values.min(), root.spreadLow)
        assertEquals(root.values.max(), root.spreadHigh)
    }

    @Test
    fun `model D - only a question root carries the neutral-prior verdict, which follows its arguments`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val r = g.createClaim("R", question = true)
        g.setStance(r, "jev", 0.9)
        awaitUntil("the root starts at its first impression, the neutral verdict at one half") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.9) < 1e-12 && it.neutral == listOf(0.5) } == true
        }
        val a = g.createClaim("A")
        val e = g.createEdge(a, r, Polarity.SUPPORT)
        g.setStance(a, "jev", 0.8)
        g.setStance(e, "jev", 0.5)
        // DF-QuAD, one support of energy 0.5 x 0.8 = 0.4: 0.9 + 0.1 x 0.4 = 0.94 from 0.9, 0.5 + 0.5 x 0.4 = 0.7 from one half.
        awaitUntil("both root verdicts follow the support") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.94) < 1e-12 && abs(it.neutral!!.single() - 0.7) < 1e-12 } == true
        }
        awaitUntil("the argument's credence reaches the hub") { g.credenceOf(a)?.values?.single() == 0.8 && g.credenceOf(e) != null }
        assertEquals(null, g.credenceOf(a)!!.neutral, "a claim that is not a question root carries no neutral-prior vector")
        assertEquals(null, g.credenceOf(e)!!.neutral, "an edge carries no neutral-prior vector")
    }

    @Test
    fun `an undercutter lowers the edge's credence and with it the argument's influence`() {
        val g = graph(LayerSet.of(listOf("dfquad", "jnb")))
        val refs = g.tree()
        awaitUntil("tree settles") { g.near(refs.getValue("R"), listOf(prototype["dfquad"]!![0], prototype["jnb"]!![0])) }
        val before = g.credenceOf(refs.getValue("R"))!!.values
        val u = g.createClaim("U")
        val eu = g.createEdge(u, refs.getValue("eA"), Polarity.ATTACK)
        g.setStance(u, "jev", 0.9)
        g.setStance(eu, "jev", 0.9)
        awaitUntil("the undercut edge loses credence in every layer") {
            g.credenceOf(refs.getValue("eA"))!!.values.all { it < 0.8 - 1e-6 }
        }
        awaitUntil("the root loses the argument's support in every layer") {
            g.credenceOf(refs.getValue("R"))!!.values.zip(before).all { (now, was) -> now < was - 1e-6 }
        }
    }

    @Test
    fun `a stance the node already holds is not sent again`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val r = g.createClaim("R")
        g.setStance(r, "jev", 0.7)
        awaitUntil("stance lands") { g.near(r, listOf(0.7)) }
        val seen = g.credenceOf(r)
        g.setStance(r, "jev", 0.7)
        Thread.sleep(50)
        assertTrue(seen === g.credenceOf(r), "an unchanged stance must not reach the cell")
    }

    @Test
    fun `the structure log rebuilds the graph, and stances recompute the same credences`() {
        val dir = Files.createTempDirectory("credence-graph").toFile()
        try {
            val log = java.io.File(dir, "graph.jsonl")
            val first = graph(log = log)
            val refs = first.tree()
            awaitUntil("first settles") { first.near(refs.getValue("R"), first.layers.ids.map { prototype.getValue(it)[0] }) }
            val before = refs.mapValues { first.credenceOf(it.value)!!.values }
            // A torn tail (kill -9 mid-append) is cut off, not fatal.
            log.appendText("{\"op\":\"claim\",\"ref\":\"")
            val second = graph(log = log)
            assertEquals(first.graph().map { it.ref to it.info }, second.graph().map { it.ref to it.info })
            assertFalse(log.readText().contains("{\"op\":\"claim\",\"ref\":\"\n"))
            assertTrue(log.readText().endsWith("\n"))
            mapOf("R" to 0.7, "A" to 0.9, "B" to 0.4, "C" to 0.3, "D" to 0.8, "eA" to 0.8, "eB" to 0.6, "eC" to 0.9, "eD" to 0.5)
                .forEach { (k, v) -> second.setStance(refs.getValue(k), "jev", v) }
            for ((k, want) in before) awaitUntil("$k recomputes") {
                second.credenceOf(refs.getValue(k))?.values?.zip(want)?.all { (a, b) -> abs(a - b) < 1e-9 } == true
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `influence folding reaches the same fixpoint in every arrival order`() {
        fun ref(name: String) = CellRef(UUID.nameUUIDFromBytes(name.toByteArray()))
        fun evaluate(reverse: Boolean): List<Double> {
            val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val g = CredenceGraph(world.host, world.registry, LayerSet.of(SemanticsCatalog.IDS))
            val root = g.createClaim("root", ref("order-root"))
            val a = g.createClaim("a", ref("order-a"))
            val b = g.createClaim("b", ref("order-b"))
            val specs = listOf(
                Triple(ref("order-edge-a"), a, Polarity.SUPPORT),
                Triple(ref("order-edge-b"), b, Polarity.ATTACK),
            )
            (if (reverse) specs.reversed() else specs).forEach { (edge, source, polarity) ->
                g.createEdge(source, root, polarity, edge)
            }
            val stances = listOf(root to 0.61, a to 0.83, b to 0.37, specs[0].first to 0.72, specs[1].first to 0.58)
            (if (reverse) stances.reversed() else stances).forEach { (node, value) -> g.setStance(node, "jev", value) }
            world.runToIdle()
            return g.credenceOf(root)!!.values
        }

        assertEquals(evaluate(false), evaluate(true))
    }

    @Test
    fun `the cycle-closing edge is a head and the vector graph quiesces`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(SemanticsCatalog.IDS), quiescence = 1e-3)
        val a = g.createClaim("A")
        val b = g.createClaim("B")
        g.createEdge(a, b, Polarity.ATTACK)
        val head = g.createEdge(b, a, Polarity.ATTACK)
        assertTrue(g.nodeInfo(head)!!.head)
        g.setStance(a, "jev", 0.99)
        g.setStance(b, "jev", 0.99)

        val steps = world.runToIdle()
        assertTrue(steps > 0)
        assertEquals(0, world.runToIdle(), "a quiescent cycle must not leave another lap queued")
        assertTrue(g.credenceOf(a)!!.values.all { it in 0.0..1.0 })
        assertTrue(g.credenceOf(b)!!.values.all { it in 0.0..1.0 })
    }

    @Test
    fun `claim credence is safely published to link-time catch-up`() {
        val field = ClaimNode::class.java.getDeclaredField("credence")

        assertTrue(
            Modifier.isVolatile(field.modifiers),
            "ClaimNode.credence is read by catchUpOnLinked on the linking thread, so it must be volatile",
        )
    }

    @Test
    fun `a head feedback link learns its source baseline from catch-up on a real scheduler`() {
        val scheduler = VirtualThreadScheduler("head-source-catch-up-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val layers = LayerSet.of(listOf("dfquad"))
        val source = ClaimNode(CellRef(UUID.randomUUID()), layers)
        val edge = EdgeNode(Polarity.ATTACK, CellRef(UUID.randomUUID()), layers, quiescence = 1e-3)
        val observed = AtomicReference<Influence>()
        val sink = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            val inlet = registerPort("inlet", FanInlet.create<Propagate<Influence>>())

            init {
                inlet.onEach(observed::set)
            }
        }
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(edge)
        host.managementInlet.call.spawn(sink)
        registry.inlet(source.ref, ClaimNodePorts.stanceInlet).propagate(Stance("u", 0.95))
        awaitUntil("the source settles before its feedback link is installed") {
            source.credence.values == listOf(0.95)
        }
        assertTrue(
            host.managementInlet.call.connect(edge.ref, "influenceOutlet", sink.ref, "inlet", LinkOptions(staged = true)) is LinkResult.Connected,
        )
        awaitUntil("the sink receives the edge's neutral initial influence") {
            observed.get()?.sourceCredence == listOf(0.5)
        }

        val linked = host.managementInlet.call.connect(
            source.ref,
            "credenceOutlet",
            edge.ref,
            "feedbackInlet",
            LinkOptions(staged = true),
        )
        assertTrue(linked is LinkResult.Connected)
        awaitUntil("the feedback link's catch-up becomes the head's source baseline") {
            observed.get()?.sourceCredence == listOf(0.95)
        }
    }

    @Test
    fun `a head sensitivity link learns a target frame that already contains its edge`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val layers = LayerSet.of(listOf("dfquad"))
        val targetRef = CellRef(UUID.randomUUID())
        val edgeRef = CellRef(UUID.randomUUID())
        val target = SensitivityNode(CellRef(UUID.randomUUID()), targetRef, layers, question = true, quiescence = 1e-3)
        val edge = SensitivityNode(CellRef(UUID.randomUUID()), edgeRef, layers, isEdge = true, quiescence = 1e-3)
        world.host.managementInlet.call.spawn(target)
        world.host.managementInlet.call.spawn(edge)
        target.influenceInlet.call.propagate(
            Influence(edgeRef, Polarity.SUPPORT, strength = listOf(0.7), sourceCredence = listOf(0.8), size = 1.0),
        )

        val linked = world.host.managementInlet.call.connect(
            target.ref,
            "frameOutlet",
            edge.ref,
            "feedbackFrameInlet",
            LinkOptions(staged = true),
        )
        assertTrue(linked is LinkResult.Connected)
        world.runToIdle()

        assertEquals(targetRef, edge.sensitivity.root)
        assertNotNull(edge.sensitivity.values, "the existing target frame must survive the feedback inlet's catch-up")
    }

    @Test
    fun `admitted staged wiring records head feedback ports and reaches finite sensitivity`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(listOf("dfquad")), quiescence = 1e-3)
        val source = g.createClaim("source", question = true)
        val target = g.createClaim("target", question = true)
        val first = g.createEdge(source, target, Polarity.SUPPORT)
        val head = g.createEdge(target, source, Polarity.ATTACK)

        assertTrue(g.nodeInfo(head)!!.head)
        assertEquals("sourceInlet", g.wiring.single { it.from == source && it.to == first && it.outlet == "credenceOutlet" }.inlet)
        assertEquals("feedbackInlet", g.wiring.single { it.from == target && it.to == head && it.outlet == "credenceOutlet" }.inlet)
        assertEquals(1, g.wiring.count { it.inlet == "frameInlet" })
        assertEquals(1, g.wiring.count { it.inlet == "feedbackFrameInlet" })

        val links = world.registry.localLinks()
        assertEquals(g.wiring.size, links.size, "every named wire must be an admitted local link")
        g.wiring.forEach { wire ->
            val link = links.single { it.from.cell == wire.from && it.to.cell == wire.to && it.from == PortRef.of(wire.from, wire.outlet) }
            assertEquals(PortRef.of(wire.to, wire.inlet), link.to)
        }

        world.runToIdle()
        val finite = g.graph().mapNotNull { it.sensitivity }
        assertTrue(finite.isNotEmpty())
        assertTrue(finite.all { it.isFinite() })
    }

    @Test
    fun `a new head edge learns its source's current credence by catch-up`() {
        // b's credence is fixed by its stance before the head edge exists; the a -> b edge has
        // strength 0, so no later lap ever re-emits b. The head edge (b -> a) therefore learns
        // b's credence ONLY from the catch-up its feedback inlet receives at link time.
        fun build(withCycle: Boolean): Pair<CredenceGraph, CellRef> {
            val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val g = CredenceGraph(world.host, world.registry, LayerSet.of(listOf("dfquad")), quiescence = 1e-3)
            val a = g.createClaim("a")
            val b = g.createClaim("b")
            g.setStance(a, "u", 0.6)
            g.setStance(b, "u", 0.95)
            if (withCycle) {
                val e1 = g.createEdge(a, b, Polarity.SUPPORT)
                g.setStance(e1, "u", 0.0)
            }
            world.runToIdle()
            val head = g.createEdge(b, a, Polarity.ATTACK)
            assertEquals(withCycle, g.nodeInfo(head)!!.head)
            world.runToIdle()
            return g to a
        }
        val (cyclic, a1) = build(withCycle = true)
        val (acyclic, a2) = build(withCycle = false)
        // The a -> b edge's residual strength lets a sub-threshold lap through the weak tier,
        // so compare within 10q; a head that misses b's baseline diverges by much more.
        assertEquals(acyclic.credenceOf(a2)!!.values.single(), cyclic.credenceOf(a1)!!.values.single(), absoluteTolerance = 1e-2)
    }

    @Test
    fun `plain frame inlet cannot close the sensitivity cycle of a head`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(listOf("dfquad")), quiescence = 1e-3)
        val source = g.createClaim("source", question = true)
        val target = g.createClaim("target", question = true)
        g.createEdge(source, target, Polarity.SUPPORT)
        g.createEdge(target, source, Polarity.ATTACK)
        val headFrame = g.wiring.single { it.inlet == "feedbackFrameInlet" }

        val result = world.host.managementInlet.call.connect(
            headFrame.from,
            headFrame.outlet,
            headFrame.to,
            "frameInlet",
            LinkOptions(staged = true),
        )
        assertTrue(result is LinkResult.Rejected)
        assertTrue((result as LinkResult.Rejected).reason.startsWith("CycleWithoutHead:"))
    }

    // --- Model A: framing a question root as an issue (computenet-dq2fy.29.1) ---

    private val stances3 = listOf(0.8, 0.6, 0.2)

    private fun near(a: List<Double>, b: List<Double>, eps: Double = 1e-9) = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < eps }

    /** The shares the fold should hold for [positions]' current credences: a softmax per layer and over the consensus. */
    private fun CredenceGraph.expectedShares(positions: List<CellRef>): Pair<List<List<Double>>, List<Double>>? {
        val cs = positions.map { credenceOf(it) ?: return null }
        val perLayer = layers.ids.indices.map { l -> Softmax.shares(cs.map { it.values[l] }) }
        return positions.indices.map { p -> perLayer.map { it[p] } } to Softmax.shares(cs.map { it.consensus })
    }

    private fun CredenceGraph.sharesMatch(root: CellRef, positions: List<CellRef>): Boolean {
        val (values, consensus) = expectedShares(positions) ?: return false
        val s = sharesOf(root) ?: return false
        return s.positions == positions && s.values.indices.all { near(s.values[it], values[it]) } && near(s.consensus, consensus)
    }

    @Test
    fun `model A - framing logs one issue op before the positions, and replay rebuilds the same issue and shares`() {
        val dir = Files.createTempDirectory("credence-graph-issue").toFile()
        try {
            val log = java.io.File(dir, "graph.jsonl")
            val first = graph(log = log)
            val root = first.createClaim("Which is best?", question = true)
            val positions = first.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2", "P3"))

            val ops = log.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(listOf("claim", "issue", "claim", "claim", "claim"), ops.map { it["op"]!!.jsonPrimitive.content })
            assertEquals(root.id.toString(), ops[0]["ref"]!!.jsonPrimitive.content)
            assertEquals(root.id.toString(), ops[1]["ref"]!!.jsonPrimitive.content)
            assertEquals("POSITIONS", ops[1]["mode"]!!.jsonPrimitive.content)
            assertEquals(positions.map { it.id.toString() }, ops[1]["positions"]!!.jsonArray.map { it.jsonPrimitive.content })
            ops.drop(2).zip(positions).forEach { (op, p) ->
                assertEquals(p.id.toString(), op["ref"]!!.jsonPrimitive.content)
                assertEquals("true", op["question"]!!.jsonPrimitive.content)
            }

            assertEquals(CredenceGraph.IssueInfo(CredenceGraph.IssueMode.POSITIONS, positions), first.nodeInfo(root)!!.issue)
            positions.forEach { assertEquals(root, first.nodeInfo(it)!!.positionOf) }
            assertTrue(positions.all { first.nodeInfo(it)!!.question })

            positions.zip(stances3).forEach { (p, v) -> first.setStance(p, "jev", v) }
            awaitUntil("the positions' shares settle on the softmax of their stances") {
                first.sharesMatch(root, positions) && positions.zip(stances3).all { (p, v) -> first.near(p, List(first.layers.ids.size) { v }) }
            }
            val want = listOf(4.0, 1.5, 0.25).map { it / 5.75 }
            val shares = first.sharesOf(root)!!
            first.layers.ids.indices.forEach { l -> assertTrue(near(shares.values.map { it[l] }, want), "layer $l: $shares") }
            assertTrue(near(shares.consensus, want), "consensus: ${shares.consensus}")
            assertEquals(shares, first.graph().single { it.ref == root }.shares?.copy(size = shares.size))

            val second = graph(log = log)
            assertEquals(first.graph().map { it.ref to it.info }, second.graph().map { it.ref to it.info })
            positions.zip(stances3).forEach { (p, v) -> second.setStance(p, "jev", v) }
            awaitUntil("the rebuilt graph reaches the same shares and credences") {
                val s = second.sharesOf(root)
                s != null && s.positions == shares.positions &&
                    s.values.indices.all { near(s.values[it], shares.values[it]) } && near(s.consensus, shares.consensus) &&
                    positions.all { p -> second.credenceOf(p)?.values?.let { near(it, first.credenceOf(p)!!.values) } == true }
            }
            assertEquals(ops.size, log.readLines().size, "replay appends nothing")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model A - an argument under a position moves its share through the fold`() {
        val g = graph(LayerSet.of(listOf("dfquad", "wlo", "jnb", "woe")))
        val root = g.createClaim("Which is best?", question = true)
        val positions = g.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2", "P3"))
        positions.zip(stances3).forEach { (p, v) -> g.setStance(p, "jev", v) }
        // Read the settled Shares from inside the predicate itself, not with a second, separate
        // call afterwards: sharesOf() and sharesMatch() each re-read the live hub, so a read taken
        // after awaitUntil returns can race a further, unrelated update and no longer be the value
        // the predicate actually observed (computenet-y6cj6).
        // Every position, not just positions[0], must have reached its own stance before
        // `before` is sampled: sharesMatch() only checks that sharesOf(root) is consistent
        // with the positions' *current* credences, so it can hold while positions[1]/[2]
        // are still rising toward their stance — sampling then bakes an unsettled, too-low
        // baseline for them into `before`, and the later "the others fall" comparison can
        // fail even though the model is right (computenet-ic9ym).
        lateinit var before: Shares
        awaitUntil("shares settle") {
            val allPositionsSettled = positions.zip(stances3).all { (p, v) -> g.near(p, List(4) { v }) }
            (allPositionsSettled && g.sharesMatch(root, positions)).also { settled ->
                if (settled) before = g.sharesOf(root)!!
            }
        }

        val a = g.createClaim("for P1")
        val e = g.createEdge(a, positions[0], Polarity.SUPPORT)
        g.setStance(a, "jev", 0.9)
        g.setStance(e, "jev", 0.8)
        lateinit var after: Shares
        awaitUntil("the first position's credence rises in every layer and the shares follow it") {
            val settled = g.credenceOf(positions[0])!!.values.all { it > 0.8 + 1e-6 } && g.sharesMatch(root, positions)
            if (settled) after = g.sharesOf(root)!!
            settled
        }
        for (l in g.layers.ids.indices) {
            assertTrue(after.values[0][l] > before.values[0][l], "layer $l: the supported position's share rises")
            assertTrue(after.values[1][l] < before.values[1][l] && after.values[2][l] < before.values[2][l], "layer $l: the others fall")
        }
        assertTrue(after.consensus[0] > before.consensus[0])
    }

    @Test
    fun `model A - a position not heard from counts one half`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"))
        val p = listOf(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()))
        val cell = IssueNode(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()), p, layers)
        assertEquals(listOf(listOf(0.5, 0.5), listOf(0.5, 0.5)), cell.shares.values)
        assertEquals(listOf(0.5, 0.5), cell.shares.consensus)

        val g = graph(layers)
        val root = g.createClaim("Q", question = true)
        val positions = g.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2"))
        awaitUntil("two unjudged positions share evenly") {
            g.sharesOf(root)?.let { s -> s.values == listOf(listOf(0.5, 0.5), listOf(0.5, 0.5)) && s.consensus == listOf(0.5, 0.5) } == true
        }
        g.setStance(positions[0], "jev", 0.8)
        awaitUntil("one judged position, the other still one half") {
            g.sharesOf(root)?.let { s -> near(s.consensus, Softmax.shares(listOf(0.8, 0.5))) } == true
        }

        // In the graph every position is heard at once (its catch-up baseline), so the
        // "unheard counts 1/2" default is observable only on a cell that has heard some
        // positions but not others: one position at 0.8, two never heard -> odds 4 : 1 : 1.
        val scheduler = VirtualThreadScheduler("issue-node-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val three = List(3) { CellRef(UUID.randomUUID()) }
        val partial = IssueNode(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()), three, layers)
        host.managementInlet.call.spawn(partial)
        registry.inlet(partial.ref, IssueNodePorts.positionInlet)
            .propagate(Credence(three[0], listOf(0.8, 0.8), 0.8, 0.8, 0.8, 0.8))
        val want = listOf(4.0 / 6, 1.0 / 6, 1.0 / 6)
        awaitUntil("the heard position holds odds 4 against two unheard halves") {
            near(partial.shares.consensus, want) && layers.ids.indices.all { l -> near(partial.shares.values.map { it[l] }, want) }
        }
    }

    @Test
    fun `model A - readings spawn no issue cell and are question roots of their own`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"))
        val g = graph(layers)
        val root = g.createClaim("Do fish sleep?", question = true)
        val readings = g.frame(root, CredenceGraph.IssueMode.READINGS, listOf("rest state?", "REM-like activity?"))
        assertEquals(CredenceGraph.IssueInfo(CredenceGraph.IssueMode.READINGS, readings), g.nodeInfo(root)!!.issue)
        assertNull(g.issueCellOf(root))
        readings.forEach { r ->
            assertEquals(root, g.nodeInfo(r)!!.positionOf)
            awaitUntil("reading ${r.id} is a sensitivity root with a neutral verdict") {
                g.sensitivityVectorOf(r) == listOf(1.0, 1.0) && g.sensitivityHub.current()[r]?.root == r &&
                    g.credenceOf(r)?.neutral != null
            }
        }
        assertNull(g.sharesOf(root))
        assertTrue(g.wiring.none { it.to == g.sharesHub.ref || it.from == g.sharesHub.ref })
    }

    @Test
    fun `model A - the issue cell feeds only the shares fold`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val root = g.createClaim("Q", question = true)
        val positions = g.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2", "P3"))
        val a = g.createClaim("for P1")
        g.createEdge(a, positions[0], Polarity.SUPPORT)
        val issue = assertNotNull(g.issueCellOf(root))
        val issueSide = setOf(issue, g.sharesHub.ref)
        assertEquals(
            listOf(CredenceGraph.Wire(issue, "sharesOutlet", g.sharesHub.ref, "inlet")),
            g.wiring.filter { it.from in issueSide },
        )
        assertEquals(
            positions.map { CredenceGraph.Wire(it, "credenceOutlet", issue, "positionInlet") },
            g.wiring.filter { it.to == issue },
        )
    }

    @Test
    fun `model A - replay drops a torn framing with its positions and their edges`() {
        val dir = Files.createTempDirectory("credence-graph-torn").toFile()
        try {
            val log = java.io.File(dir, "graph.jsonl")
            val (root, p1, p2, x, e) = List(5) { CellRef(UUID.randomUUID()) }
            log.writeText(
                listOf(
                    """{"op":"claim","ref":"${root.id}","text":"Q","question":true}""",
                    """{"op":"issue","ref":"${root.id}","mode":"POSITIONS","positions":["${p1.id}","${p2.id}"]}""",
                    """{"op":"claim","ref":"${p1.id}","text":"P1","question":true}""",
                    """{"op":"claim","ref":"${x.id}","text":"X"}""",
                    """{"op":"edge","ref":"${e.id}","polarity":"SUPPORT","source":"${p1.id}","target":"${x.id}"}""",
                ).joinToString("\n", postfix = "\n"),
            )
            val g = graph(LayerSet.of(listOf("dfquad")), log)
            assertNull(g.nodeInfo(root)!!.issue)
            assertNull(g.nodeInfo(p1))
            assertNull(g.nodeInfo(p2))
            assertNull(g.nodeInfo(e))
            assertEquals(listOf(root, x), g.graph().map { it.ref })
            assertNull(g.issueCellOf(root))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model A - frame refuses a non-question, a framed root, a root with arguments and fewer than two positions`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val plain = g.createClaim("not a question")
        assertFailsWith<IllegalArgumentException> { g.frame(plain, CredenceGraph.IssueMode.READINGS, listOf("a", "b")) }
        val framed = g.createClaim("Q1", question = true)
        g.frame(framed, CredenceGraph.IssueMode.READINGS, listOf("a", "b"))
        assertFailsWith<IllegalArgumentException> { g.frame(framed, CredenceGraph.IssueMode.POSITIONS, listOf("c", "d")) }
        val argued = g.createClaim("Q2", question = true)
        g.createEdge(g.createClaim("pro"), argued, Polarity.SUPPORT)
        assertFailsWith<IllegalArgumentException> { g.frame(argued, CredenceGraph.IssueMode.POSITIONS, listOf("c", "d")) }
        val lone = g.createClaim("Q3", question = true)
        assertFailsWith<IllegalArgumentException> { g.frame(lone, CredenceGraph.IssueMode.POSITIONS, listOf("only")) }
        assertNull(g.nodeInfo(lone)!!.issue)
    }
}
