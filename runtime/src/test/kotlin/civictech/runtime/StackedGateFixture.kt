package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.Gossiping
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.PromotionJudge
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.evolve.SatisfactionCriterion
import civictech.cell.evolve.StateMigrating
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.UnlinkStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkOptions
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.partition.PartitionedCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.port.streamTo
import civictech.cell.proxy.Proxy
import civictech.cell.replication.Replication
import civictech.cell.verify.ReplicaConvergence
import civictech.cell.verify.Violation
import civictech.cell.wire.Peering
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import java.io.Serializable
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Contract
interface GateIngest {
    fun offer(@Key item: Owned<String>)
}

@Contract
interface GatePush {
    fun push(@Key item: Owned<String>)
}

/** Shared graph, workload, oracle, and deterministic two-node rig for both stacked-gate legs. */
object StackedGateFixture {
    enum class Node { A, B }

    data class Variant(
        val journalOff: Boolean = false,
        val replicationOff: Boolean = false,
        val nonTotalRouting: Boolean = false,
        val droppingConsumer: Boolean = false,
        val unguardedSwap: Boolean = false,
    ) : Serializable {
        /** The oracle clause each single-flag negative control is expected to break. */
        fun expectedFailureClauses(): Set<String> = buildSet {
            if (journalOff) add("(i)")
            if (replicationOff || nonTotalRouting) add("(i)")
            if (droppingConsumer) add("(iii)")
            if (unguardedSwap) add("(ii)")
        }
    }

    data class Operation(val phase: Int, val node: Node, val add: Boolean, val element: String) : Serializable

    data class Script(
        val seed: Long,
        val beforeFirstBatch: List<Operation>,
        val afterPromotion: List<Operation>,
        val whilePartitioned: List<Operation>,
        val afterRecovery: List<Operation>,
        val batch1: List<String>,
        val batch2: List<String>,
        val faultingIds: Set<String>,
    ) : Serializable {
        companion object {
            operator fun invoke(seed: Long): Script {
                val random = Random(seed)
                val live = linkedSetOf<String>()
                fun operations(phase: Int, count: Int, forced: Node? = null): List<Operation> = buildList {
                    repeat(count) {
                        val node = forced ?: if (random.nextBoolean()) Node.A else Node.B
                        val add = live.isEmpty() || (live.size < domain.size && random.nextInt(10) < 6)
                        val candidates = if (add) domain.filterNot(live::contains) else live.toList()
                        val element = candidates[random.nextInt(candidates.size)]
                        if (add) live += element else live -= element
                        add(Operation(phase, node, add, element))
                    }
                }

                val first = operations(1, 20)
                val postPromotion = operations(3, 10)
                val partitioned = operations(4, 6, Node.B)
                val recovered = operations(6, 10)
                val batch1 = (0 until 24).map { "batch-1-$it" }
                val batch2 = (0 until 24).map { "batch-2-$it" }
                val faultRandom = Random(seed * 31 + 7)
                val faults = (batch1 + batch2).filter { faultRandom.nextInt(5) == 0 }.toSet()
                return Script(seed, first, postPromotion, partitioned, recovered, batch1, batch2, faults)
            }
        }
    }

    /** One batch recomputation of the partitioned board. */
    fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy(::regionOf).mapValues { (_, elements) -> elements.sumOf(::amountOf) }

    private fun regionOf(element: String): String = element.first().toString()

    private fun amountOf(element: String): Long = element.drop(1).toLong()

    private fun logicalId(seed: Long, name: String): UUID =
        UUID.nameUUIDFromBytes("stacked-gate:$seed:$name".toByteArray())

    private fun journal(variant: Variant, node: Node): String? =
        if (variant.journalOff && node == Node.B) null else "main"

    /** The one node-local graph definition used by the simulated and real-JVM legs. */
    fun spec(node: Node, variant: Variant = Variant(), seed: Long): GraphSpec {
        val durable = journal(variant, node)
        val staged = LinkOptions(staged = true)
        val steps = buildList {
            add(
                SpawnStep(
                    "orders", FixtureFactory(Kind.ORDERS, seed, variant),
                    identity = IdentityBinding.NewInstanceOf(logicalId(seed, "orders")),
                    replicated = !variant.replicationOff,
                    journalId = durable,
                ),
            )
            add(
                SpawnStep(
                    "board",
                    FixtureFactory(if (variant.nonTotalRouting) Kind.LEAKY_BOARD else Kind.BOARD, seed, variant),
                    identity = IdentityBinding.NewInstanceOf(logicalId(seed, "board")),
                    replicated = !variant.replicationOff && !variant.nonTotalRouting,
                ),
            )
            add(ConnectStep("orders", "outlet", "board", "inlet"))

            if (node == Node.A) {
                add(
                    SpawnStep(
                        "relay", FixtureFactory(Kind.RELAY, seed, variant),
                        identity = IdentityBinding.NewInstanceOf(logicalId(seed, "relay")),
                        journalId = "main", inputs = setOf("owned-input"),
                    ),
                )
            } else {
                add(SpawnStep("gate", FixtureFactory(Kind.GATE, seed, variant)))
                add(SpawnStep("rows", FixtureFactory(Kind.ROWS, seed, variant), journalId = durable))
                add(
                    SpawnStep(
                        "total", FixtureFactory(Kind.TOTAL_V1, seed, variant),
                        identity = IdentityBinding.NewInstanceOf(logicalId(seed, "total")), journalId = durable,
                    ),
                )
                if (!variant.unguardedSwap) {
                    add(
                        SpawnStep(
                            "totalV2", FixtureFactory(Kind.TOTAL_V2, seed, variant),
                            identity = IdentityBinding.NewInstanceOf(logicalId(seed, "total")),
                            journalId = durable, shadow = true,
                        ),
                    )
                }
                add(SpawnStep("invariant", FixtureFactory(Kind.INVARIANT, seed, variant), journalId = durable))
                add(SpawnStep("consumer", FixtureFactory(Kind.CONSUMER, seed, variant), journalId = durable))
                add(
                    SpawnStep(
                        "shadowConsumer", FixtureFactory(Kind.SHADOW_CONSUMER, seed, variant),
                        shadow = true,
                    ),
                )
                add(ConnectStep("orders", "outlet", "gate", "dataInlet", staged))
                add(ConnectStep("gate", "dataOutlet", "rows", "inlet", staged))
                add(ConnectStep("gate", "dataOutlet", "total", "inlet", staged))
                if (!variant.unguardedSwap) add(ConnectStep("gate", "dataOutlet", "totalV2", "inlet", staged))
                add(ConnectStep("rows", "outlet", "invariant", "inlet"))
                add(ConnectStep("total", "outlet", "invariant", "inlet"))
            }
        }
        return GraphSpec(steps)
    }

    fun manifest(root: Path, variant: Variant = Variant()): Manifest = Manifest(
        nodes = mapOf(
            "a" to NodeSpec(
                transport = "ws", listen = "ws://127.0.0.1:0", peerName = "a",
                journalDir = root.resolve("a").toString(), journalTopology = true,
            ),
            "b" to NodeSpec(
                transport = "ws", dial = listOf("a"), peerName = "b",
                journalDir = if (variant.journalOff) null else root.resolve("b").toString(),
                journalTopology = !variant.journalOff,
            ),
        ),
    )

    class GateRelayCell(seed: Long, override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<GateIngest>())
        val live = registerPort("live", FanOutlet.create<GatePush>())
        val shadow = registerPort("shadow", FanOutlet.create<GatePush>())
        private val lanes = Random(seed)
        val liveIds = mutableListOf<String>()
        val shadowIds = mutableListOf<String>()

        init {
            inlet.serve(object : GateIngest {
                override fun offer(item: Owned<String>) {
                    val id = item.borrow().value
                    if (lanes.nextBoolean()) {
                        liveIds += id
                        live.call.push(item)
                    } else {
                        shadowIds += id
                        shadow.call.push(item)
                    }
                }
            })
        }
    }

    class GateConsumerCell(
        private val faults: Set<String>,
        private val dropping: Boolean,
        override val ref: CellRef,
    ) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<GatePush>())
        val taken = linkedSetOf<String>()

        init {
            inlet.serve(object : GatePush {
                override fun push(item: Owned<String>) {
                    if (dropping) return
                    val id = item.borrow().value
                    if (id in faults && id !in taken) error("seeded fault before take: $id")
                    item.take()
                    taken += id
                }
            })
        }

        override fun snapshot(): Serializable = ArrayList(taken)

        override fun restore(state: Serializable) {
            @Suppress("UNCHECKED_CAST")
            taken.apply { clear(); addAll(state as List<String>) }
        }
    }

    class GateShadowConsumerCell(override val ref: CellRef) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<GatePush>())
        var effects = 0
            private set

        init {
            inlet.serve(object : GatePush {
                override fun push(item: Owned<String>) {
                    item.take()
                    effects++
                }
            })
        }
    }

    abstract class TotalCell(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Long>>>())
        protected abstract var total: Long
        private val adds = linkedMapOf<String, MutableSet<Timestamp>>()
        private val dels = linkedMapOf<String, MutableSet<Timestamp>>()
        var receivedCount = 0
            private set

        init {
            inlet.serve(Propagate { delta ->
                receivedCount++
                val before = total
                delta.adds.forEach { (element, tags) -> adds.getOrPut(element) { linkedSetOf() }.addAll(tags) }
                delta.dels.forEach { (element, tags) -> dels.getOrPut(element) { linkedSetOf() }.addAll(tags) }
                total = adds.entries.sumOf { (element, tags) ->
                    if ((tags - dels[element].orEmpty()).isNotEmpty()) amountOf(element) else 0L
                }
                if (total != before || CurrentContext.get()?.baseline != null) {
                    outlet.call.propagate(MapDelta(mapOf("total" to total), emptySet()))
                }
            })
        }

        protected fun encodedState(): String = buildList {
            add("total=$total")
            adds.toSortedMap().forEach { (element, tags) ->
                tags.sortedWith(compareBy(Timestamp::sourceId, Timestamp::counter)).forEach { tag ->
                    add("A|$element|${tag.sourceId}|${tag.counter}")
                }
            }
            dels.toSortedMap().forEach { (element, tags) ->
                tags.sortedWith(compareBy(Timestamp::sourceId, Timestamp::counter)).forEach { tag ->
                    add("D|$element|${tag.sourceId}|${tag.counter}")
                }
            }
        }.joinToString("\n")

        protected fun restoreEncodedState(encoded: String): Long {
            adds.clear()
            dels.clear()
            val lines = encoded.lineSequence().toList()
            lines.drop(1).forEach { line ->
                val (kind, element, source, counter) = line.split('|', limit = 4)
                val target = if (kind == "A") adds else dels
                target.getOrPut(element) { linkedSetOf() }.add(Timestamp(UUID.fromString(source), counter.toLong()))
            }
            return lines.first().removePrefix("total=").toLong()
        }

        fun currentTotal(): Long = total
    }

    class TotalCellV1(ref: CellRef) : TotalCell(ref) {
        override var total = 0L
        override fun snapshot(): Serializable = total
        override fun restore(state: Serializable) { total = state as Long }
    }

    class TotalCellV2(ref: CellRef) : TotalCell(ref), StateMigrating {
        private var representation = "total=0"
        override var total: Long
            get() = representation.removePrefix("total=").toLong()
            set(value) { representation = "total=$value" }

        override fun snapshot(): Serializable = encodedState()
        override fun restore(state: Serializable) {
            total = restoreEncodedState(state as String)
        }
        override fun importFrom(prior: Serializable) { total = prior as Long }
    }

    class InvariantBoardCell(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Long>>>())
        val violations = registerPort("violations", FanOutlet.create<Propagate<Violation>>())
        private val rows = linkedMapOf<String, Long>()
        private var total = 0L
        private val recentViolations = ArrayDeque<String>()
        var observationCount = 0
            private set
        var evaluationCount = 0
            private set
        var violationCount = 0
            private set

        init {
            inlet.serve(Propagate(::apply))
        }

        private fun apply(delta: MapDelta<String, Long>) {
            observationCount++
            delta.puts.forEach { (key, value) -> if (key == "total") total = value else rows[key] = value }
            delta.removals.forEach { key -> if (key == "total") total = 0 else rows.remove(key) }
        }

        /** Called by the orchestrator only after it has drained the graph to quiescence. */
        fun evaluate() {
            evaluationCount++
            val rowTotal = rows.values.sum()
            if (rowTotal != total) {
                violationCount++
                recentViolations.addLast("evaluation=$evaluationCount total=$total rows=$rows")
                while (recentViolations.size > 12) recentViolations.removeFirst()
                violations.call.propagate(
                    Violation("stacked-total", "total $total != row sum $rowTotal", rows.toMap()),
                )
            }
        }

        fun diagnostic(): String =
            "total=$total rows=$rows evaluations=$evaluationCount recent=$recentViolations"

        fun matches(expectedRows: Map<String, Long>): Boolean = rows == expectedRows && total == expectedRows.values.sum()

        private data class Snapshot(
            val rows: LinkedHashMap<String, Long>,
            val total: Long,
            val evaluations: Int,
            val violations: Int,
        ) : Serializable

        override fun snapshot(): Serializable =
            Snapshot(LinkedHashMap(rows), total, evaluationCount, violationCount)

        override fun restore(state: Serializable) {
            val saved = state as Snapshot
            rows.apply { clear(); putAll(saved.rows) }
            total = saved.total
            evaluationCount = saved.evaluations
            violationCount = saved.violations
        }
    }

    /** Deliberately non-total control: region `a` is folded by both organelles and summed twice. */
    class LeakyRouterBoard(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Long>>>())
        private val shards = List(2) {
            GroupByCell<String, String, Long, Long>(keyFn = ::regionOf, aggregator = Aggregators.sumOf(::amountOf))
        }
        private val shardValues = List(2) { linkedMapOf<String, Long>() }

        init {
            shards.forEachIndexed { index, shard ->
                shard.outlet.subscribe(fixed(Propagate { delta ->
                    delta.puts.forEach { (key, value) -> shardValues[index][key] = value }
                    delta.removals.forEach { shardValues[index].remove(it) }
                    val keys = delta.puts.keys + delta.removals
                    val puts = keys.associateWith { key -> shardValues.sumOf { it[key] ?: 0L } }
                        .filterValues { it != 0L }
                    val removals = keys.filterTo(linkedSetOf()) { key -> shardValues.all { key !in it } }
                    outlet.call.propagate(MapDelta(puts, removals))
                }))
            }
            inlet.serve(Propagate { delta ->
                val slices = Array(2) { SetDelta<String>() }
                fun slice(entries: Map<String, Set<Timestamp>>, adds: Boolean) {
                    entries.forEach { (element, tags) ->
                        val targets = if (regionOf(element) == "a") listOf(0, 1) else listOf(Math.floorMod(element.hashCode(), 2))
                        targets.forEach { target ->
                            val old = slices[target]
                            slices[target] = if (adds) old.copy(adds = old.adds + (element to tags))
                            else old.copy(dels = old.dels + (element to tags))
                        }
                    }
                }
                slice(delta.adds, true)
                slice(delta.dels, false)
                slices.forEachIndexed { index, value ->
                    if (value.adds.isNotEmpty() || value.dels.isNotEmpty()) shards[index].inlet.call.propagate(value)
                }
            })
        }
    }

    data class Fates(
        val n: Int,
        val ids: Set<String>,
        val liveLane: List<String>,
        val shadowLane: List<String>,
        val consumed: List<String>,
        val discharged: Long,
        val deadLettered: List<String>,
        val tapSaw: List<String>,
        val doubleDischargeDelta: Long,
    )

    data class Observation(
        val expectedMembership: Set<String>,
        val expectedBoard: Map<String, Long>,
        val boardA: Map<String, Long>,
        val boardB: Map<String, Long>,
        val membershipA: Set<String>,
        val membershipB: Set<String>,
        val converged: Boolean,
        val violationCount: Int,
        val invariantMatchesExpected: Boolean,
        val fates: List<Fates>,
        val shadowEffects: Int,
        val recoveredCandidate: Boolean,
        val incumbentRetired: Boolean,
        val consumerRestored: Boolean,
        val invariantDiagnostic: String,
    )

    object Oracle {
        fun violations(observation: Observation): List<String> = buildList {
            if (observation.membershipA != observation.expectedMembership ||
                observation.membershipB != observation.expectedMembership ||
                observation.boardA != observation.expectedBoard ||
                observation.boardB != observation.expectedBoard ||
                !observation.converged
            ) {
                add("(i) replicas/partitioned boards do not equal the batch oracle")
            }
            if (observation.violationCount != 0 || !observation.invariantMatchesExpected) {
                add(
                    "(ii) quiescent invariant recorded ${observation.violationCount} violation(s) " +
                        "or did not reach the batch state",
                )
            }
            observation.fates.forEachIndexed { index, fate ->
                val total = fate.consumed.size + fate.discharged + fate.deadLettered.size
                val liveFates = (fate.consumed + fate.deadLettered).toSet()
                if (total != fate.n.toLong() ||
                    fate.consumed.toSet().intersect(fate.deadLettered.toSet()).isNotEmpty() ||
                    liveFates != fate.liveLane.toSet() ||
                    fate.discharged != fate.shadowLane.size.toLong() ||
                    fate.tapSaw != fate.liveLane || fate.doubleDischargeDelta != 0L
                ) {
                    add("(iii) Owned batch ${index + 1} has inexact fates: $fate")
                }
            }
            if (observation.shadowEffects != 0) {
                add("(iii) shadow consumer performed ${observation.shadowEffects} effect(s)")
            }
            if (!observation.consumerRestored) add("(iii) recovered consumer lost a prior taken id")
            if (!observation.recoveredCandidate || !observation.incumbentRetired) {
                add("(iv) recovery did not keep TotalCellV2 live with the incumbent retired")
            }
        }
    }

    interface RelayProxy { val inlet: Use<GateIngest> }
    interface PushProxy { val inlet: Use<GatePush> }

    class SimNode(
        val name: Node,
        private val seed: Long,
        private val variant: Variant,
        private val controller: SimulationController,
        val journal: InMemoryJournal = InMemoryJournal(),
    ) {
        lateinit var registry: LocationRegistry
            private set
        lateinit var host: ManagedHost
            private set
        lateinit var bridgeHost: ManagedHost
            private set
        lateinit var side: Peering.Side
            private set
        lateinit var replication: Replication
            private set
        lateinit var context: ApplyContext
            private set
        private lateinit var dataScheduler: HostScheduler
        private lateinit var bridgeScheduler: HostScheduler

        fun launch(recover: Boolean = false) {
            registry = LocationRegistry()
            dataScheduler = controller.scheduler()
            bridgeScheduler = controller.scheduler()
            lateinit var nextContext: ApplyContext
            val activeJournal = if (variant.journalOff && name == Node.B) null else journal
            host = ManagedHost(
                scheduler = dataScheduler,
                registry = registry,
                journal = activeJournal,
                journalFor = { ref -> nextContext.journalFor(ref) ?: activeJournal },
            )
            bridgeHost = ManagedHost(scheduler = bridgeScheduler, registry = registry)
            side = Peering.Side(registry, bridgeHost)
            replication = Replication(registry)
            nextContext = ApplyContext(
                host, replication,
                journals = if (activeJournal == null) emptyMap() else mapOf("main" to journal),
                topology = activeJournal,
            )
            context = nextContext
            if (recover && activeJournal != null) {
                val recovery = context.recover(journal)
                controller.runToIdle()
                recovery.awaitApplied(1)
            } else {
                spec(name, variant, seed).apply(context)
            }
        }

        fun crash() {
            dataScheduler.shutdown()
            bridgeScheduler.shutdown()
        }

        fun ref(handle: String): CellRef = context.handles.getValue(handle)

        @Suppress("UNCHECKED_CAST")
        fun <T : Cell> cell(handle: String): T = captured.getValue(ref(handle)) as T
    }

    data class Checkpoints(val p1: Observation, val p2: Observation)

    class Harness(val seed: Long, val variant: Variant = Variant()) {
        val script = Script(seed)
        private val controller = SimulationController(seed)
        val a = SimNode(Node.A, seed, variant, controller)
        var b = SimNode(Node.B, seed, variant, controller)
            private set
        private lateinit var peering: Peering.Loopback
        private val live = linkedSetOf<String>()
        private val boardA = linkedMapOf<String, Long>()
        private val boardB = linkedMapOf<String, Long>()
        private val letters = mutableListOf<DeadLetter>()
        private val tapSaw = mutableListOf<String>()
        private val fates = mutableListOf<Fates>()
        private var shadowEffects = 0
        private var convergence: ReplicaConvergence<SetDelta<String>, TagFold>? = null

        fun runFull(): Checkpoints {
            a.launch()
            b.launch()
            peering = Peering.loopback(a.side, b.side)
            controller.runToIdle()
            foldBoard(a, boardA)
            attachBObservers(resetBoard = true)
            attachConvergence()
            wireOwnedLanes()
            b.cell<TrafficLightCell<Propagate<SetDelta<String>>>>("gate").controlInlet.call.setGreen()

            apply(script.beforeFirstBatch)
            controller.runToIdle()
            runOwnedBatch(script.batch1)
            promote()
            apply(script.afterPromotion)
            controller.runToIdle()
            b.cell<InvariantBoardCell>("invariant").evaluate()
            val p1 = observe(recovered = false)

            peering.partition()
            controller.runToIdle()
            apply(script.whilePartitioned)
            controller.runToIdle()
            shadowEffects += b.cell<GateShadowConsumerCell>("shadowConsumer").effects
            b.crash()

            val retainedJournal = b.journal
            b = SimNode(Node.B, seed, variant, controller, retainedJournal)
            b.launch(recover = true)
            peering = Peering.loopback(a.side, b.side)
            peering.heal()
            controller.runToIdle()
            attachBObservers(resetBoard = true)
            attachConvergence()
            controller.runToIdle()
            b.cell<TrafficLightCell<Propagate<SetDelta<String>>>>("gate").controlInlet.call.setGreen()

            runOwnedBatch(script.batch2)
            apply(script.afterRecovery)
            controller.runToIdle()
            b.cell<InvariantBoardCell>("invariant").evaluate()
            val p2 = observe(recovered = true)
            return Checkpoints(p1, p2)
        }

        private fun attachBObservers(resetBoard: Boolean) {
            if (resetBoard) boardB.clear()
            foldBoard(b, boardB)
            captureLetters(b.host)
        }

        private fun attachConvergence() {
            val boardA = a.cell<Cell>("board")
            val boardB = b.cell<Cell>("board")
            if (boardA is Gossiping<*> && boardB is Gossiping<*>) {
                @Suppress("UNCHECKED_CAST")
                convergence = ReplicaConvergence(
                    a.registry, logicalId(seed, "board"), TagFold(), TagFold::apply,
                ).also { invariant ->
                    invariant.attach(boardA as Gossiping<SetDelta<String>>)
                    invariant.attach(boardB as Gossiping<SetDelta<String>>)
                }
            } else {
                convergence = null
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun foldBoard(node: SimNode, target: MutableMap<String, Long>) {
            val board = node.cell<Cell>("board")
            val outlet = when (board) {
                is PartitionedCell<*, *, *, *> ->
                    (board as PartitionedCell<String, String, Long, Long>).outlet
                is LeakyRouterBoard -> board.outlet
                else -> error("unsupported board ${board.javaClass.name}")
            }
            outlet.streamTo(Propagate { delta ->
                delta.puts.forEach { (key, value) -> target[key] = value }
                delta.removals.forEach { target.remove(it) }
            })
        }

        private fun captureLetters(host: ManagedHost) {
            host.deadLetterOutlet.subscribe(fixed(Propagate { letters += it }))
        }

        private fun wireOwnedLanes() {
            val relay = a.cell<GateRelayCell>("relay")
            val remoteLive =
                (HostedCellProxy.create(b.ref("consumer"), a.registry, PushProxy::class.java) as PushProxy).inlet.call
            val remoteShadow =
                (HostedCellProxy.create(b.ref("shadowConsumer"), a.registry, PushProxy::class.java) as PushProxy).inlet.call
            relay.live.tap(fixed(object : GatePush {
                override fun push(item: Owned<String>) { tapSaw += item.borrow().value }
            }))
            relay.live.subscribe(fixed(remoteLive))
            relay.shadow.subscribe(fixed(remoteShadow))
        }

        private fun apply(operations: List<Operation>) {
            operations.forEach { operation ->
                val node = if (operation.node == Node.A) a else b
                val ops = node.host.lookup<SetApi<String>>(node.ref("orders"))!!.inlet.call
                if (operation.add) {
                    ops.add(operation.element)
                    live += operation.element
                } else {
                    controller.runToIdle()
                    ops.remove(operation.element)
                    live -= operation.element
                }
            }
        }

        private fun runOwnedBatch(ids: List<String>) {
            val relay = a.cell<GateRelayCell>("relay")
            val beforeLive = relay.liveIds.size
            val beforeShadow = relay.shadowIds.size
            val beforeTap = tapSaw.size
            val beforeDischarges = Proxy.discharges
            val beforeDouble = Proxy.doubleDischarges
            val beforeLetters = deadLetterIds().toSet()
            val relayIn = (HostedCellProxy.create(relay.ref, a.host, RelayProxy::class.java) as RelayProxy).inlet.call
            val input = a.host.durableInput(relay.ref, "owned-input")
            ids.chunked(8).forEachIndexed { index, chunk ->
                input.commit {
                    chunk.forEach { relayIn.offer(Owned(it)) }
                    index
                }
            }
            controller.runToIdle()
            val idSet = ids.toSet()
            val consumer = b.cell<GateConsumerCell>("consumer")
            fates += Fates(
                n = ids.size,
                ids = idSet,
                liveLane = relay.liveIds.drop(beforeLive).filter { it in idSet },
                shadowLane = relay.shadowIds.drop(beforeShadow).filter { it in idSet },
                consumed = consumer.taken.filter { it in idSet },
                discharged = Proxy.discharges - beforeDischarges,
                deadLettered = deadLetterIds().filter { it in idSet && it !in beforeLetters },
                tapSaw = tapSaw.drop(beforeTap).filter { it in idSet },
                doubleDischargeDelta = Proxy.doubleDischarges - beforeDouble,
            )
        }

        private fun promote() {
            val invariant = b.cell<InvariantBoardCell>("invariant")
            invariant.evaluate()
            if (variant.unguardedSwap) {
                GraphSpec(
                    listOf(
                        SpawnStep(
                            "totalV2", FixtureFactory(Kind.TOTAL_V2, seed, variant),
                            identity = IdentityBinding.NewInstanceOf(logicalId(seed, "total")),
                            journalId = journal(variant, Node.B),
                        ),
                        ConnectStep("gate", "dataOutlet", "totalV2", "inlet"),
                        UnlinkStep("total", "outlet", "invariant", "inlet"),
                        ConnectStep("totalV2", "outlet", "invariant", "inlet"),
                    ),
                ).apply(b.context)
            } else {
                check(invariant.violationCount == 0) {
                    "default invariant failed before promotion: ${invariant.diagnostic()}"
                }
                val judge = PromotionJudge(
                    PromotionPolicy(
                        listOf("invariant"), ObservationWindow(1),
                        threshold = SatisfactionCriterion { invariant.violationCount == 0 },
                        judge = "stacked-judge",
                    ),
                )
                judge.observeCandidateWave()
                b.context.promote(
                    b.ref("gate"), b.ref("total"), b.ref("totalV2"), "outlet",
                    listOf(b.ref("invariant") to "inlet"), judge,
                )
            }
            controller.runToIdle()
        }

        private fun observe(recovered: Boolean): Observation {
            val membershipA = a.cell<SetCell<String>>("orders").membership()
            val membershipB = b.cell<SetCell<String>>("orders").membership()
            return Observation(
                expectedMembership = live.toSet(),
                expectedBoard = batch(live),
                boardA = boardA.toMap(),
                boardB = boardB.toMap(),
                membershipA = membershipA,
                membershipB = membershipB,
                converged = convergence?.converged() ?: true,
                violationCount = b.cell<InvariantBoardCell>("invariant").violationCount,
                invariantMatchesExpected = b.cell<InvariantBoardCell>("invariant").matches(batch(live)),
                fates = fates.toList(),
                shadowEffects = shadowEffects + b.cell<GateShadowConsumerCell>("shadowConsumer").effects,
                recoveredCandidate = !recovered || b.cell<Cell>("totalV2") is TotalCellV2,
                incumbentRetired = "total" !in b.context.handles,
                consumerRestored = !recovered || fates.firstOrNull()?.consumed.orEmpty().all {
                    it in b.cell<GateConsumerCell>("consumer").taken
                },
                invariantDiagnostic = buildString {
                    val invariant = b.cell<InvariantBoardCell>("invariant")
                    val candidate = b.cell<TotalCellV2>("totalV2")
                    append(invariant.diagnostic())
                    append(" observations=").append(invariant.observationCount)
                    append(" candidateReceived=").append(candidate.receivedCount)
                    append(" candidateTotal=").append(candidate.currentTotal())
                },
            )
        }

        private fun deadLetterIds(): List<String> = letters.flatMap { letter ->
            letter.invocation?.invocation?.args.orEmpty()
                .filterIsInstance<Frozen<*>>()
                .map { it.value as String }
        }
    }

    data class TagFold(
        val adds: Map<String, Set<Timestamp>> = emptyMap(),
        val dels: Map<String, Set<Timestamp>> = emptyMap(),
    ) {
        fun apply(delta: SetDelta<String>): TagFold {
            val nextAdds = adds.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
            val nextDels = dels.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
            delta.adds.forEach { (element, tags) -> nextAdds.getOrPut(element) { linkedSetOf() }.addAll(tags) }
            delta.dels.forEach { (element, tags) -> nextDels.getOrPut(element) { linkedSetOf() }.addAll(tags) }
            return TagFold(nextAdds, nextDels)
        }
    }

    private enum class Kind {
        ORDERS, BOARD, LEAKY_BOARD, RELAY, GATE, ROWS, TOTAL_V1, TOTAL_V2, INVARIANT,
        CONSUMER, SHADOW_CONSUMER,
    }

    private data class FixtureFactory(val kind: Kind, val seed: Long, val variant: Variant) : CellFactory {
        override fun create(ref: CellRef): Cell {
            val cell: Cell = when (kind) {
                Kind.ORDERS -> SetCell<String>(ref)
                Kind.BOARD -> PartitionedCell(ref, 3, ::regionOf, Aggregators.sumOf(::amountOf))
                Kind.LEAKY_BOARD -> LeakyRouterBoard(ref)
                Kind.RELAY -> GateRelayCell(seed, ref)
                Kind.GATE -> TrafficLightCell(propagateSetDelta, ref)
                Kind.ROWS -> GroupByCell(ref, ::regionOf, Aggregators.sumOf(::amountOf))
                Kind.TOTAL_V1 -> TotalCellV1(ref)
                Kind.TOTAL_V2 -> TotalCellV2(ref)
                Kind.INVARIANT -> InvariantBoardCell(ref)
                Kind.CONSUMER -> GateConsumerCell(Script(seed).faultingIds, variant.droppingConsumer, ref)
                Kind.SHADOW_CONSUMER -> GateShadowConsumerCell(ref)
            }
            captured[ref] = cell
            return cell
        }
    }

    private fun <T : Any> fixed(api: T): Use<T> = Use.fixed(api, PortRef.generate())

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = Propagate::class.java as Class<Propagate<SetDelta<String>>>

    private val captured = ConcurrentHashMap<CellRef, Cell>()

    private val domain = listOf(
        "a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7",
    )
}
