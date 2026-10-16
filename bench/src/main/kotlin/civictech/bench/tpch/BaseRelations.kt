package civictech.bench.tpch

import civictech.cell.data.SetCell
import civictech.cell.graph.graph
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Registers every TPC-H row that can cross a journalled SetCell inlet. */
internal object TpchWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Part::class, Part.serializer())
            subclass(Supplier::class, Supplier.serializer())
            subclass(PartSupp::class, PartSupp.serializer())
            subclass(Customer::class, Customer.serializer())
            subclass(Orders::class, Orders.serializer())
            subclass(Lineitem::class, Lineitem.serializer())
            subclass(Nation::class, Nation.serializer())
            subclass(Region::class, Region.serializer())
        }
    }
}

private val tpchWireSerializers: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    WireCodec.contribute(TpchWireSerializers)
}

internal fun ensureTpchWireSerializers() {
    tpchWireSerializers
}

/** The eight hosted TPC-H base relations and their refresh application protocol. */
class BaseRelations private constructor(
    val region: SetCell<Region>,
    val nation: SetCell<Nation>,
    val supplier: SetCell<Supplier>,
    val part: SetCell<Part>,
    val partSupp: SetCell<PartSupp>,
    val customer: SetCell<Customer>,
    val orders: SetCell<Orders>,
    val lineitem: SetCell<Lineitem>,
    private val rig: TpchRig,
) {

    val partsupp: SetCell<PartSupp>
        get() = partSupp

    val lineItem: SetCell<Lineitem>
        get() = lineitem

    val lineItems: SetCell<Lineitem>
        get() = lineitem

    private var logical: LogicalTables? = null

    val logicalTables: LogicalTables
        get() = checkNotNull(logical) { "load(tables) must be called before reading logicalTables" }

    val tables: LogicalTables
        get() = logicalTables

    /** Load the immutable dimensions and base facts through the hosted inlets. */
    fun load(tables: TpchTables) {
        check(logical == null) { "TPC-H base relations are already loaded" }
        logical = LogicalTables(tables)
        tables.region.forEach(region.inlet.call::add)
        tables.nation.forEach(nation.inlet.call::add)
        tables.supplier.forEach(supplier.inlet.call::add)
        tables.part.forEach(part.inlet.call::add)
        tables.partSupp.forEach(partSupp.inlet.call::add)
        tables.customer.forEach(customer.inlet.call::add)
        tables.orders.forEach(orders.inlet.call::add)
        tables.lineitem.forEach(lineitem.inlet.call::add)
    }

    /** Apply RF1 in referential order: ORDERS first, then LINEITEM. */
    fun apply(batch: RefreshBatch) {
        val state = logicalTables
        when (batch) {
            is RefreshBatch.Rf1 -> {
                state.applyRf1(batch)
                batch.orders.forEach(orders.inlet.call::add)
                batch.lineitems.forEach(lineitem.inlet.call::add)
            }

            is RefreshBatch.Rf2 -> {
                // Resolve exact values before changing either the cells or the
                // logical maps. SetCell.remove is value-shaped, not key-shaped.
                val removals = batch.orderkeys.map { orderKey ->
                    val order = state.order(orderKey)
                        ?: error("RF2 order key $orderKey is not live in LogicalTables")
                    order to state.lineitemsFor(orderKey)
                }
                removals.forEach { (_, lines) -> lines.forEach(lineitem.inlet.call::remove) }
                removals.forEach { (order, _) -> orders.inlet.call.remove(order) }
                state.applyRf2(batch)
            }
        }
    }

    fun membership(): Map<String, Int> = linkedMapOf(
        "region" to region.membership().size,
        "nation" to nation.membership().size,
        "supplier" to supplier.membership().size,
        "part" to part.membership().size,
        "partsupp" to partSupp.membership().size,
        "customer" to customer.membership().size,
        "orders" to orders.membership().size,
        "lineitem" to lineitem.membership().size,
    )

    fun memberships(): Map<String, Int> = membership()

    fun close() = rig.close()

    companion object {
        /** Spawn all eight relations on [rig]'s host through the graph DSL. */
        fun spawn(rig: TpchRig): BaseRelations {
            lateinit var region: SetCell<Region>
            lateinit var nation: SetCell<Nation>
            lateinit var supplier: SetCell<Supplier>
            lateinit var part: SetCell<Part>
            lateinit var partSupp: SetCell<PartSupp>
            lateinit var customer: SetCell<Customer>
            lateinit var orders: SetCell<Orders>
            lateinit var lineitem: SetCell<Lineitem>

            graph(rig.host.managementInlet) {
                region = spawn("tpch-region") { ref -> SetCell<Region>(ref) }.cell
                nation = spawn("tpch-nation") { ref -> SetCell<Nation>(ref) }.cell
                supplier = spawn("tpch-supplier") { ref -> SetCell<Supplier>(ref) }.cell
                part = spawn("tpch-part") { ref -> SetCell<Part>(ref) }.cell
                partSupp = spawn("tpch-partsupp") { ref -> SetCell<PartSupp>(ref) }.cell
                customer = spawn("tpch-customer") { ref -> SetCell<Customer>(ref) }.cell
                orders = spawn("tpch-orders") { ref -> SetCell<Orders>(ref) }.cell
                lineitem = spawn("tpch-lineitem") { ref -> SetCell<Lineitem>(ref) }.cell
            }
            return BaseRelations(
                region = region,
                nation = nation,
                supplier = supplier,
                part = part,
                partSupp = partSupp,
                customer = customer,
                orders = orders,
                lineitem = lineitem,
                rig = rig,
            )
        }

        fun open(rig: TpchRig): BaseRelations = spawn(rig)
    }
}
