package civictech.bench.tpch

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
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
    refs: Map<SetCell<*>, CellRef>,
) {

    // Ingress goes through the HOSTED proxy, never the bare cell's inlet: a
    // direct call on the locally-built instance bypasses host dispatch, the
    // scheduler and the journal, so SIM work reads 0 and nothing is journalled.
    // The bare cells are kept only for membership() reads (the GraRun idiom).
    private val regionIn = rig.host.api<Region>(refs.getValue(region))
    private val nationIn = rig.host.api<Nation>(refs.getValue(nation))
    private val supplierIn = rig.host.api<Supplier>(refs.getValue(supplier))
    private val partIn = rig.host.api<Part>(refs.getValue(part))
    private val partSuppIn = rig.host.api<PartSupp>(refs.getValue(partSupp))
    private val customerIn = rig.host.api<Customer>(refs.getValue(customer))
    private val ordersIn = rig.host.api<Orders>(refs.getValue(orders))
    private val lineitemIn = rig.host.api<Lineitem>(refs.getValue(lineitem))

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
        tables.region.forEach(regionIn.inlet.call::add)
        tables.nation.forEach(nationIn.inlet.call::add)
        tables.supplier.forEach(supplierIn.inlet.call::add)
        tables.part.forEach(partIn.inlet.call::add)
        tables.partSupp.forEach(partSuppIn.inlet.call::add)
        tables.customer.forEach(customerIn.inlet.call::add)
        tables.orders.forEach(ordersIn.inlet.call::add)
        tables.lineitem.forEach(lineitemIn.inlet.call::add)
    }

    /** Apply RF1 in referential order: ORDERS first, then LINEITEM. */
    fun apply(batch: RefreshBatch) {
        val state = logicalTables
        when (batch) {
            is RefreshBatch.Rf1 -> {
                state.applyRf1(batch)
                batch.orders.forEach(ordersIn.inlet.call::add)
                batch.lineitems.forEach(lineitemIn.inlet.call::add)
            }

            is RefreshBatch.Rf2 -> {
                // Resolve exact values before changing either the cells or the
                // logical maps. SetCell.remove is value-shaped, not key-shaped.
                val removals = batch.orderkeys.map { orderKey ->
                    val order = state.order(orderKey)
                        ?: error("RF2 order key $orderKey is not live in LogicalTables")
                    order to state.lineitemsFor(orderKey)
                }
                removals.forEach { (_, lines) -> lines.forEach(lineitemIn.inlet.call::remove) }
                removals.forEach { (order, _) -> ordersIn.inlet.call.remove(order) }
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
            val refs = HashMap<SetCell<*>, CellRef>()

            graph(rig.host.managementInlet) {
                region = spawn("tpch-region") { ref -> SetCell<Region>(ref) }.also { refs[it.cell] = it.ref }.cell
                nation = spawn("tpch-nation") { ref -> SetCell<Nation>(ref) }.also { refs[it.cell] = it.ref }.cell
                supplier = spawn("tpch-supplier") { ref -> SetCell<Supplier>(ref) }.also { refs[it.cell] = it.ref }.cell
                part = spawn("tpch-part") { ref -> SetCell<Part>(ref) }.also { refs[it.cell] = it.ref }.cell
                partSupp = spawn("tpch-partsupp") { ref -> SetCell<PartSupp>(ref) }.also { refs[it.cell] = it.ref }.cell
                customer = spawn("tpch-customer") { ref -> SetCell<Customer>(ref) }.also { refs[it.cell] = it.ref }.cell
                orders = spawn("tpch-orders") { ref -> SetCell<Orders>(ref) }.also { refs[it.cell] = it.ref }.cell
                lineitem = spawn("tpch-lineitem") { ref -> SetCell<Lineitem>(ref) }.also { refs[it.cell] = it.ref }.cell
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
                refs = refs,
            )
        }

        fun open(rig: TpchRig): BaseRelations = spawn(rig)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> ManagedHost.api(ref: CellRef): SetApi<T> =
    lookup(ref, SetApi::class.java) as SetApi<T>?
        ?: error("TPC-H relation $ref is not hosted")
