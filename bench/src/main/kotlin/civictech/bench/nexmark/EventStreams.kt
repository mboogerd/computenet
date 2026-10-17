package civictech.bench.nexmark

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.graph
import civictech.cell.host.ManagedHost
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Registers every Nexmark row that can cross a journalled SetCell inlet. */
internal object NexmarkWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Person::class, Person.serializer())
            subclass(Auction::class, Auction.serializer())
            subclass(Bid::class, Bid.serializer())
            subclass(ClockTick::class, ClockTick.serializer())
        }
    }
}

private val nexmarkWireSerializers: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    WireCodec.contribute(NexmarkWireSerializers)
}

internal fun ensureNexmarkWireSerializers() {
    nexmarkWireSerializers
}

/** Four hosted Nexmark streams and their value-shaped ingress protocol. */
class EventStreams private constructor(
    val persons: SetCell<Person>,
    val auctions: SetCell<Auction>,
    val bids: SetCell<Bid>,
    val clock: SetCell<ClockTick>,
    private val rig: NexmarkRig,
    refs: Map<SetCell<*>, CellRef>,
) {
    private val personsIn = rig.host.api<Person>(refs.getValue(persons))
    private val auctionsIn = rig.host.api<Auction>(refs.getValue(auctions))
    private val bidsIn = rig.host.api<Bid>(refs.getValue(bids))
    private val clockIn = rig.host.api<ClockTick>(refs.getValue(clock))

    /** Maximum event time over every applied add, including a later late-drop. */
    var maxEventTimeApplied: Long? = null
        private set

    fun apply(ops: List<StreamOp>) {
        ops.forEach { op ->
            when (op) {
                is StreamOp.Add -> {
                    maxEventTimeApplied = maxEventTimeApplied
                        ?.let { maxOf(it, op.event.dateTime) }
                        ?: op.event.dateTime
                    when (val event = op.event) {
                        is Person -> personsIn.inlet.call.add(event)
                        is Auction -> auctionsIn.inlet.call.add(event)
                        is Bid -> bidsIn.inlet.call.add(event)
                    }
                }

                is StreamOp.Remove -> when (val event = op.event) {
                    is Person -> personsIn.inlet.call.remove(event)
                    is Auction -> auctionsIn.inlet.call.remove(event)
                    is Bid -> bidsIn.inlet.call.remove(event)
                }
            }
        }
    }

    fun tick(at: Long) {
        clockIn.inlet.call.add(ClockTick(at))
    }

    companion object {
        fun spawn(rig: NexmarkRig): EventStreams {
            lateinit var persons: SetCell<Person>
            lateinit var auctions: SetCell<Auction>
            lateinit var bids: SetCell<Bid>
            lateinit var clock: SetCell<ClockTick>
            val refs = HashMap<SetCell<*>, CellRef>()

            graph(rig.host.managementInlet) {
                persons = spawn("nexmark-persons") { ref -> SetCell<Person>(ref) }
                    .also { refs[it.cell] = it.ref }.cell
                auctions = spawn("nexmark-auctions") { ref -> SetCell<Auction>(ref) }
                    .also { refs[it.cell] = it.ref }.cell
                bids = spawn("nexmark-bids") { ref -> SetCell<Bid>(ref) }
                    .also { refs[it.cell] = it.ref }.cell
                clock = spawn("nexmark-clock") { ref -> SetCell<ClockTick>(ref) }
                    .also { refs[it.cell] = it.ref }.cell
            }
            return EventStreams(persons, auctions, bids, clock, rig, refs)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> ManagedHost.api(ref: CellRef): SetApi<T> =
    lookup(ref, SetApi::class.java) as SetApi<T>?
        ?: error("Nexmark stream $ref is not hosted")
