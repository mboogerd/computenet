package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.durability.InMemoryJournal
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.Shadow
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Proxy
import civictech.cell.wire.Peering
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

@Contract
interface FateIngest {
    fun offer(@Key item: Owned<String>)
}

@Contract
interface FatePush {
    fun push(@Key item: Owned<String>)
}

/**
 * z88w1-D1/D8 fate accounting: N `Owned<String>` items flow through a `durableInput`-journaled
 * inlet on node A, a relay that splits them over two exclusive outlets (LIVE with a
 * [FanOutlet.tap] observer, SHADOW), over a [Peering.loopback] wire to node B, where the LIVE
 * lane ends at a consumer (which faults on a seeded subset before `take()`) and the SHADOW lane
 * at a [Shadow.spawn]ed (discharging-served) consumer. Every item must end in exactly one fate:
 * consumed + discharged + dead-lettered == N (`[52-DISCH-01]`, `[52-TAP-01]`, G-46 accounting).
 *
 * Fates are counted from evidence of consumption (a `take()` performed, a `Proxy.discharges`
 * increment, a `Frozen` in a captured dead letter), never from receipt. The consumer's host
 * uses the default supervision policy (PROPAGATE: dead-letter and keep serving, pinned by
 * `SupervisionTest`), so one handler fault does not stop the consumer.
 *
 * Caveat: `Proxy.discharges` is a process-wide counter read as a delta; a concurrently running
 * test in the same JVM that discharges exclusives would perturb it (kernel tests run
 * single-forked per class by default; this class itself runs its seeds serially).
 */
class ExclusiveFateAccountingTest {

    private enum class LiveKind { TAKING, DROPPING }

    private class Relay(seed: Long, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<FateIngest>())
        val live = registerPort("live", FanOutlet.create<FatePush>())
        val shadow = registerPort("shadow", FanOutlet.create<FatePush>())
        private val lanes = Random(seed)
        val liveIds = mutableListOf<String>()
        val shadowIds = mutableListOf<String>()

        init {
            inlet.serve(object : FateIngest {
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

    private class LiveConsumer(
        private val faults: Set<String>,
        private val kind: LiveKind,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<FatePush>())
        val taken = mutableListOf<String>()
        var received = 0

        init {
            inlet.serve(object : FatePush {
                override fun push(item: Owned<String>) {
                    received++
                    if (kind == LiveKind.DROPPING) return // receives, never takes
                    val id = item.borrow().value
                    if (id in faults) error("seeded fault before take: $id")
                    taken += item.take()
                }
            })
        }
    }

    private class ShadowConsumer(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<FatePush>())
        var effects = 0

        init {
            inlet.serve(object : FatePush {
                override fun push(item: Owned<String>) {
                    item.take()
                    effects++
                }
            })
        }
    }

    interface RelayProxy { val inlet: Use<FateIngest> }
    interface PushProxy { val inlet: Use<FatePush> }

    private data class Fates(
        val n: Int,
        val liveLane: List<String>,
        val shadowLane: List<String>,
        val consumed: List<String>,
        val discharged: Long,
        val deadLettered: List<String>,
        val tapSaw: List<String>,
        val doubleDischargeDelta: Long,
        val shadowEffects: Int,
    ) {
        override fun toString() =
            "consumed=${consumed.size} discharged=$discharged deadLettered=${deadLettered.size} " +
                "(live lane ${liveLane.size}, shadow lane ${shadowLane.size}, N=$n)"
    }

    private fun <T : Any> fixed(api: T): Use<T> = Use.fixed(api, PortRef.generate())

    private fun run(seed: Long, n: Int, kind: LiveKind): Fates {
        val controller = SimulationController(seed)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA, journal = InMemoryJournal())
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))

        val ids = (0 until n).map { "item-$it" }
        val faultRng = Random(seed * 31 + 7)
        val faults = ids.filter { faultRng.nextInt(4) == 0 }.toSet()

        val letters = mutableListOf<civictech.cell.host.DeadLetter>()
        hostB.deadLetterOutlet.subscribe(fixed(Propagate<civictech.cell.host.DeadLetter> { letters += it }))

        val relay = Relay(seed)
        val live = LiveConsumer(faults, kind)
        val shadow = ShadowConsumer()
        hostA.managementInlet.call.spawn(relay)
        hostB.managementInlet.call.spawn(live)
        Shadow.spawn(hostB, shadow)
        controller.runToIdle()

        val remoteLive = (HostedCellProxy.create(live.ref, registryA, PushProxy::class.java) as PushProxy).inlet.call
        val remoteShadow = (HostedCellProxy.create(shadow.ref, registryA, PushProxy::class.java) as PushProxy).inlet.call
        val tapSaw = mutableListOf<String>()
        relay.live.tap(fixed(object : FatePush {
            override fun push(item: Owned<String>) {
                tapSaw += item.borrow().value // observes by hand; never takes
            }
        }))
        relay.live.subscribe(fixed(remoteLive))
        relay.shadow.subscribe(fixed(remoteShadow))

        val relayIn = (HostedCellProxy.create(relay.ref, hostA, RelayProxy::class.java) as RelayProxy).inlet.call
        val input = hostA.durableInput(relay.ref, "fate-input")
        val dischargesBefore = Proxy.discharges
        val doubleBefore = Proxy.doubleDischarges
        ids.chunked(8).forEachIndexed { batch, chunk ->
            input.commit {
                chunk.forEach { relayIn.offer(Owned(it)) }
                batch
            }
        }
        controller.runToIdle()

        val deadLettered = letters.flatMap { letter ->
            letter.invocation?.invocation?.args.orEmpty().filterIsInstance<Frozen<*>>().map { it.value as String }
        }
        return Fates(
            n = n,
            liveLane = relay.liveIds.toList(),
            shadowLane = relay.shadowIds.toList(),
            consumed = live.taken.toList(),
            discharged = Proxy.discharges - dischargesBefore,
            deadLettered = deadLettered,
            tapSaw = tapSaw,
            doubleDischargeDelta = Proxy.doubleDischarges - doubleBefore,
            shadowEffects = shadow.effects,
        )
    }

    /** The oracle: returns every violated clause, empty when each item had exactly one fate. */
    private fun violations(f: Fates): List<String> {
        val out = mutableListOf<String>()
        val total = f.consumed.size + f.discharged + f.deadLettered.size
        if (total != f.n.toLong()) {
            val short = f.n - total
            out += "fate sum $total != N=${f.n} (short by $short; consumed=${f.consumed.size}, " +
                "discharged=${f.discharged}, deadLettered=${f.deadLettered.size})"
        }
        if (f.consumed.size != f.consumed.toSet().size) out += "an item was consumed twice"
        if (f.consumed.toSet().intersect(f.deadLettered.toSet()).isNotEmpty()) out += "consumed and dead-lettered overlap"
        if ((f.consumed + f.deadLettered).toSet() != f.liveLane.toSet()) {
            out += "consumed+deadLettered != live lane: missing=${(f.liveLane.toSet() - (f.consumed + f.deadLettered).toSet())}"
        }
        if (f.discharged != f.shadowLane.size.toLong()) out += "discharged ${f.discharged} != shadow lane ${f.shadowLane.size}"
        if (f.tapSaw != f.liveLane) out += "tap saw ${f.tapSaw} != live lane ${f.liveLane}"
        if (f.doubleDischargeDelta != 0L) out += "doubleDischarges moved by ${f.doubleDischargeDelta}"
        if (f.shadowEffects != 0) out += "shadow consumer acted ${f.shadowEffects} times"
        return out
    }

    @Test
    fun `every Owned item ends in exactly one fate on every seed`() {
        val all = (1L..20L).map { seed ->
            run(seed, n = 40, kind = LiveKind.TAKING).also {
                violations(it).shouldBeEmpty()
                println("seed $seed: $it")
            }
        }
        // the seed sweep must exercise every fate, or the sum would pass vacuously
        (all.sumOf { it.consumed.size } > 0) shouldBe true
        (all.sumOf { it.discharged } > 0L) shouldBe true
        (all.sumOf { it.deadLettered.size } > 0) shouldBe true
    }

    @Test
    fun `a live consumer that receives without taking leaves the oracle short by the live lane`() {
        for (seed in listOf(1L, 2L, 3L)) {
            val fates = run(seed, n = 40, kind = LiveKind.DROPPING)
            val found = violations(fates)
            (found.isNotEmpty()) shouldBe true
            // which fate is short: the live lane has neither consumed nor dead-lettered items
            (fates.consumed.size + fates.deadLettered.size) shouldBe 0
            (fates.n - (fates.consumed.size + fates.discharged + fates.deadLettered.size)) shouldBe fates.liveLane.size
            found.any { it.startsWith("fate sum") } shouldBe true
        }
    }
}
