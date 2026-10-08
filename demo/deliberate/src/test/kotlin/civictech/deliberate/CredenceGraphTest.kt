package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionPolicy
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
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
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-graph credence propagation (SPEC CRED-04..06): vector messages, derived consensus. */
class CredenceGraphTest {
    private val schedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun tearDown() = schedulers.forEach { it.shutdown() }

    private fun graph(layers: LayerSet = LayerSet.of(SemanticsCatalog.IDS), journalFile: java.io.File? = null): CredenceGraph {
        val recover = journalFile?.let { it.exists() && it.length() > 0L } == true
        return graphWithJournal(layers, journalFile?.let(::FileJournal), recover)
    }

    private fun graphWithJournal(layers: LayerSet, journal: Journal?, recover: Boolean): CredenceGraph {
        val scheduler = VirtualThreadScheduler("credence-graph-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val context = ApplyContext(host, topology = journal)
        return CredenceGraph(host, registry, layers, context = context).also { graph ->
            if (journal != null && recover) {
                context.recover(journal).awaitApplied(60_000)
                graph.rebuildIndex()
            }
        }
    }

    private class FailingAppendJournal(private val delegate: InMemoryJournal = InMemoryJournal()) : Journal by delegate {
        var failNext = false

        override fun append(record: ByteArray) {
            if (failNext) {
                failNext = false
                throw IOException("injected topology append failure")
            }
            delegate.append(record)
        }
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
        // glo is not in the prototype: an independent Python port of its formula on the same tree.
        "glo" to listOf(0.9529820851773328, 0.7504871711594674),
    )

    @Test
    fun `dynamic hub feeds publish through canonical one-view observations`() {
        val g = graph(LayerSet.of(listOf("dfquad")))

        assertEquals(
            mapOf("hub" to "hub", "sensitivityHub" to "sensitivityHub", "sharesHub" to "sharesHub"),
            g.observationGroups,
        )
        assertEquals(3, g.observationGroupRefs.values.toSet().size)
        assertTrue(
            g.observationGroupRefs.values.none { it in setOf(g.hub.ref, g.sensitivityHub.ref, g.sharesHub.ref) },
            "the stable topology feeds and canonical observation sinks must remain distinct",
        )

        val claim = g.createClaim("canonical")
        g.setStance(claim, "jev", 0.8)
        awaitUntil("the dynamic feed reaches the canonical credence observation") {
            g.credenceOf(claim)?.values == listOf(0.8)
        }
        assertEquals(listOf(0.8), g.hub.current().getValue(claim).values)
    }

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
    fun `model D - every claim and edge carries an arguments-first verdict while an unargued node keeps its prior`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val r = g.createClaim("R", question = true)
        g.setStance(r, "jev", 0.9)
        awaitUntil("an unargued root's arguments-first verdict is its first impression") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.9) < 1e-12 && it.neutral == it.values } == true
        }
        val a = g.createClaim("A")
        val e = g.createEdge(a, r, Polarity.SUPPORT)
        g.setStance(a, "jev", 0.8)
        g.setStance(e, "jev", 0.8)
        // DF-QuAD, one support of energy 0.8 x 0.8 = 0.64: 0.9 + 0.1 x 0.64 = 0.964 from 0.9,
        // 0.5 + 0.5 x 0.64 = 0.82 from one half.
        awaitUntil("both root verdicts follow the support") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.964) < 1e-12 && abs(it.neutral!!.single() - 0.82) < 1e-12 } == true
        }
        awaitUntil("the argument's credence reaches the hub") { g.credenceOf(a)?.values?.single() == 0.8 && g.credenceOf(e) != null }
        assertEquals(g.credenceOf(a)!!.values, g.credenceOf(a)!!.neutral, "an unargued claim keeps its prior")
        assertEquals(g.credenceOf(e)!!.values, g.credenceOf(e)!!.neutral, "an unargued edge keeps its prior")

        val b = g.createClaim("B")
        val eb = g.createEdge(b, a, Polarity.SUPPORT)
        g.setStance(b, "jev", 0.8)
        g.setStance(eb, "jev", 0.8)
        awaitUntil("the argued claim evaluates the same support from its prior and from one half") {
            g.credenceOf(a)?.let { abs(it.values.single() - 0.928) < 1e-12 && abs(it.neutral!!.single() - 0.82) < 1e-12 } == true
        }

        val u = g.createClaim("U")
        val eu = g.createEdge(u, e, Polarity.SUPPORT)
        g.setStance(u, "jev", 0.8)
        g.setStance(eu, "jev", 0.8)
        awaitUntil("the argued edge evaluates the same support from its prior and from one half") {
            g.credenceOf(e)?.let { abs(it.values.single() - 0.928) < 1e-12 && abs(it.neutral!!.single() - 0.82) < 1e-12 } == true
        }
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
    fun `the kernel journal rebuilds the graph, and stances recompute the same credences`() {
        val dir = Files.createTempDirectory("credence-graph").toFile()
        try {
            val journal = java.io.File(dir, "host.journal")
            val first = graph(journalFile = journal)
            val refs = first.tree()
            awaitUntil("first settles") { first.near(refs.getValue("R"), first.layers.ids.map { prototype.getValue(it)[0] }) }
            val before = refs.mapValues { first.credenceOf(it.value)!!.values }
            val second = graph(journalFile = journal)
            assertEquals(first.graph().map { it.ref to it.info }, second.graph().map { it.ref to it.info })
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
    fun `a retired sensitivity topology journal restores while exact value ignores it`() {
        val dir = Files.createTempDirectory("deliberate-retired-sensitivity").toFile()
        try {
            val journalFile = java.io.File(dir, "host.journal")
            val journal = FileJournal(journalFile)
            val scheduler = VirtualThreadScheduler("credence-retired-writer").also { schedulers += it }
            val registry = LocationRegistry()
            val host = ManagedHost(
                scheduler = scheduler,
                registry = registry,
                attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
            )
            val context = ApplyContext(host, topology = journal)
            CredenceGraph(host, registry, LayerSet.of(listOf("dfquad")), context = context)

            val layers = LayerSet.of(listOf("dfquad"))
            val root = CellRef(UUID.randomUUID())
            val child = CellRef(UUID.randomUUID())
            val edge = CellRef(UUID.randomUUID())
            val rootSensitivity = CellRef(UUID.randomUUID())
            val childSensitivity = CellRef(UUID.randomUUID())
            val edgeSensitivity = CellRef(UUID.randomUUID())
            val staged = LinkOptions(staged = true)
            fun claimHandle(ref: CellRef) = "claim:${ref.id}"
            fun edgeHandle(ref: CellRef) = "edge:${ref.id}"
            fun sensitivityHandle(ref: CellRef) = "sens:${ref.id}"

            // This is the topology shape emitted by model C: one sensitivity
            // cell beside every claim and edge, linked to the compatibility hub.
            GraphSpec(
                listOf(
                    SpawnStep(claimHandle(root), ClaimNodeFactory("R", true, layers), IdentityBinding.Exact(root)),
                    SpawnStep(
                        sensitivityHandle(root),
                        SensitivityFactory(root, isEdge = false, question = true, layers, 1e-3),
                        IdentityBinding.Exact(rootSensitivity),
                    ),
                    SpawnStep(claimHandle(child), ClaimNodeFactory("C", false, layers), IdentityBinding.Exact(child)),
                    SpawnStep(
                        sensitivityHandle(child),
                        SensitivityFactory(child, isEdge = false, question = false, layers, 1e-3),
                        IdentityBinding.Exact(childSensitivity),
                    ),
                    SpawnStep(edgeHandle(edge), EdgeNodeFactory(Polarity.SUPPORT, false, layers, 1e-3), IdentityBinding.Exact(edge)),
                    SpawnStep(
                        sensitivityHandle(edge),
                        SensitivityFactory(edge, isEdge = true, question = false, layers, 1e-3),
                        IdentityBinding.Exact(edgeSensitivity),
                    ),
                    ConnectStep(claimHandle(root), "credenceOutlet", "hub", "inlet", staged),
                    ConnectStep(sensitivityHandle(root), "hubOutlet", "sensitivityHub", "inlet", staged),
                    ConnectStep(claimHandle(child), "credenceOutlet", "hub", "inlet", staged),
                    ConnectStep(sensitivityHandle(child), "hubOutlet", "sensitivityHub", "inlet", staged),
                    ConnectStep(edgeHandle(edge), "credenceOutlet", "hub", "inlet", staged),
                    ConnectStep(edgeHandle(edge), "influenceOutlet", claimHandle(root), "influenceInlet", staged),
                    ConnectStep(claimHandle(child), "credenceOutlet", edgeHandle(edge), "sourceInlet", staged),
                    ConnectStep(sensitivityHandle(edge), "hubOutlet", "sensitivityHub", "inlet", staged),
                    ConnectStep(sensitivityHandle(edge), "sourceOutlet", sensitivityHandle(child), "shareInlet", staged),
                    ConnectStep(edgeHandle(edge), "influenceOutlet", sensitivityHandle(root), "influenceInlet", staged),
                    ConnectStep(sensitivityHandle(root), "frameOutlet", sensitivityHandle(edge), "frameInlet", staged),
                ),
            ).apply(context)

            val restored = graph(LayerSet.of(listOf("dfquad")), journalFile)
            restored.setStance(root, "jev", 0.5)
            restored.setStance(child, "jev", 0.8)
            restored.setStance(edge, "jev", 0.9)
            awaitUntil("the retired topology's credence graph settles") {
                restored.credenceOf(root)?.values?.single()?.let { abs(it - 0.86) < 1e-12 } == true
            }
            assertEquals(4, restored.sensitivityCells.size, "three restored cells plus the compatibility hub")
            assertEquals(0.288, restored.exactValueOf(child, listOf(root))!!.expectedRootChange, 1e-12)

            val oldSensitivityCells = restored.sensitivityCells
            restored.createClaim("new")
            assertEquals(oldSensitivityCells, restored.sensitivityCells, "new deltas add no retired sensitivity cells")
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
    fun `sensitivity catch-up snapshots are safely published to the linking thread`() {
        listOf("frame", "sensitivity", "share").forEach { name ->
            val field = SensitivityNode::class.java.getDeclaredField(name)

            assertTrue(
                Modifier.isVolatile(field.modifiers),
                "SensitivityNode.$name is read by catchUpOnLinked on the linking thread, so it must be volatile",
            )
        }
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
    fun `admitted staged wiring records credence head ports and exact cycle evaluation stays finite`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(listOf("dfquad")), quiescence = 1e-3)
        val source = g.createClaim("source", question = true)
        val target = g.createClaim("target", question = true)
        val first = g.createEdge(source, target, Polarity.SUPPORT)
        val head = g.createEdge(target, source, Polarity.ATTACK)

        assertTrue(g.nodeInfo(head)!!.head)
        assertEquals("sourceInlet", g.wiring.single { it.from == source && it.to == first && it.outlet == "credenceOutlet" }.inlet)
        assertEquals("feedbackInlet", g.wiring.single { it.from == target && it.to == head && it.outlet == "credenceOutlet" }.inlet)
        assertTrue(g.wiring.none { it.inlet == "frameInlet" || it.inlet == "feedbackFrameInlet" })

        val links = world.registry.localLinks()
        val observationLinks = listOf(
            Triple(g.hub.ref, g.observationGroupRefs.getValue("hub"), "hub"),
            Triple(g.sensitivityHub.ref, g.observationGroupRefs.getValue("sensitivityHub"), "sensitivityHub"),
            Triple(g.sharesHub.ref, g.observationGroupRefs.getValue("sharesHub"), "sharesHub"),
        )
        assertEquals(
            g.wiring.size + observationLinks.size,
            links.size,
            "every named graph wire plus the three canonical observation edges must be admitted locally",
        )
        g.wiring.forEach { wire ->
            val link = links.single { it.from.cell == wire.from && it.to.cell == wire.to && it.from == PortRef.of(wire.from, wire.outlet) }
            assertEquals(PortRef.of(wire.to, wire.inlet), link.to)
        }
        observationLinks.forEach { (feed, observation, inlet) ->
            val link = links.single { it.from == PortRef.of(feed, "outlet") && it.to.cell == observation }
            assertEquals(PortRef.of(observation, inlet), link.to)
        }

        world.runToIdle()
        val exact = g.exactValueOf(target, listOf(source, target))!!
        assertTrue(exact.expectedRootChange.isFinite())
        assertTrue(exact.dominantSway!!.isFinite())
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
    fun `a new credence cycle creates no retired sensitivity frame cycle`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(listOf("dfquad")), quiescence = 1e-3)
        val source = g.createClaim("source", question = true)
        val target = g.createClaim("target", question = true)
        g.createEdge(source, target, Polarity.SUPPORT)
        g.createEdge(target, source, Polarity.ATTACK)
        assertTrue(g.wiring.none { it.inlet == "frameInlet" || it.inlet == "feedbackFrameInlet" })
        assertEquals(1, g.sensitivityCells.size, "only the old-journal compatibility hub remains")
    }

    // --- Model A: framing a question root as an issue (computenet-dq2fy.29.1) ---

    private val stances3 = listOf(0.8, 0.6, 0.2)

    private fun near(a: List<Double>, b: List<Double>, eps: Double = 1e-9) = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < eps }

    /** The shares the fold should hold for [positions]' current credences: absolute weights per layer and consensus. */
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
    fun `model A - framing is one topology delta and recovery rebuilds the same issue and shares`() {
        val dir = Files.createTempDirectory("credence-graph-issue").toFile()
        try {
            val journalFile = java.io.File(dir, "host.journal")
            val first = graph(journalFile = journalFile)
            val root = first.createClaim("Which is best?", question = true)
            val positions = first.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2", "P3"))

            val deltas = FileJournal(journalFile).replay()
                .map(JournalRecords::decode)
                .filterIsInstance<DecodedJournalRecord.Topology>()
            assertEquals(2, deltas.size, "the root and the complete framing are one topology record each")
            val framing = deltas.last().events
            assertEquals(1, framing.count { it is civictech.cell.graph.TopoEvent.Spawn && it.factory is IssueFactory })
            assertEquals(
                positions.toSet(),
                framing.filterIsInstance<civictech.cell.graph.TopoEvent.Spawn>()
                    .filter { it.factory is ClaimNodeFactory }
                    .map { it.ref }
                    .toSet(),
            )

            assertEquals(CredenceGraph.IssueInfo(CredenceGraph.IssueMode.POSITIONS, positions), first.nodeInfo(root)!!.issue)
            positions.forEach { assertEquals(root, first.nodeInfo(it)!!.positionOf) }
            assertTrue(positions.all { first.nodeInfo(it)!!.question })

            positions.zip(stances3).forEach { (p, v) -> first.setStance(p, "jev", v) }
            awaitUntil("the positions' shares settle on the normalised stances") {
                first.sharesMatch(root, positions) && positions.zip(stances3).all { (p, v) -> first.near(p, List(first.layers.ids.size) { v }) }
            }
            val want = stances3.map { it / stances3.sum() }
            val shares = first.sharesOf(root)!!
            first.layers.ids.indices.forEach { l -> assertTrue(near(shares.values.map { it[l] }, want), "layer $l: $shares") }
            assertTrue(near(shares.consensus, want), "consensus: ${shares.consensus}")
            assertEquals(shares, first.graph().single { it.ref == root }.shares?.copy(size = shares.size))

            val second = graph(journalFile = journalFile)
            assertEquals(first.graph().map { it.ref to it.info }, second.graph().map { it.ref to it.info })
            positions.zip(stances3).forEach { (p, v) -> second.setStance(p, "jev", v) }
            awaitUntil("the rebuilt graph reaches the same shares and credences") {
                val s = second.sharesOf(root)
                s != null && s.positions == shares.positions &&
                    s.values.indices.all { near(s.values[it], shares.values[it]) } && near(s.consensus, shares.consensus) &&
                    positions.all { p -> second.credenceOf(p)?.values?.let { near(it, first.credenceOf(p)!!.values) } == true }
            }
            assertEquals(deltas.size, FileJournal(journalFile).replay().size, "recovery appends nothing")
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
        // positions but not others: one position at 0.8, two never heard -> weights 0.8 : 0.5 : 0.5.
        val scheduler = VirtualThreadScheduler("issue-node-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val three = List(3) { CellRef(UUID.randomUUID()) }
        val partial = IssueNode(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()), three, layers)
        host.managementInlet.call.spawn(partial)
        registry.inlet(partial.ref, IssueNodePorts.positionInlet)
            .propagate(Credence(three[0], listOf(0.8, 0.8), 0.8, 0.8, 0.8, 0.8))
        val want = listOf(0.8 / 1.8, 0.5 / 1.8, 0.5 / 1.8)
        awaitUntil("the heard position is normalised against two unheard halves") {
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
            awaitUntil("reading ${r.id} is an exact-VoI root with a neutral verdict") {
                g.exactValueOf(r, listOf(r))?.dominantSway == 1.0 && g.credenceOf(r)?.neutral != null
            }
        }
        assertEquals(1, g.sensitivityCells.size, "new framing writes no sensitivity cells")
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
    fun `model A - a framing whose topology append fails leaves no positions after recovery`() {
        val layers = LayerSet.of(listOf("dfquad"))
        val journal = FailingAppendJournal()
        val first = graphWithJournal(layers, journal, recover = false)
        val root = first.createClaim("Q", question = true)
        val positions = List(2) { CellRef(UUID.randomUUID()) }

        journal.failNext = true
        assertFailsWith<IOException> {
            first.frame(root, CredenceGraph.IssueMode.POSITIONS, listOf("P1", "P2"), positions)
        }
        assertNull(first.nodeInfo(root)!!.issue)
        positions.forEach { assertNull(first.nodeInfo(it)) }

        val recovered = graphWithJournal(layers, journal, recover = true)
        assertNull(recovered.nodeInfo(root)!!.issue)
        positions.forEach { assertNull(recovered.nodeInfo(it)) }
        assertEquals(listOf(root), recovered.graph().map { it.ref })
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
