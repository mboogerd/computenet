package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
import civictech.cell.port.LinkResult
import civictech.cell.port.Use

/**
 * Phantom-typed port names for the ref-only wiring path: the generated
 * `<CellName>Ports` objects mint these, the connect overloads below lower
 * them to the ordinary string form. Runtime value is just the name —
 * `ConnectStep` and the wire stay strings.
 */
class InletId<Api>(val name: String)

class OutletId<Api>(val name: String)

fun <Api> GraphBuilder.connect(from: CellHandle, outlet: OutletId<Api>, to: CellHandle, inlet: InletId<Api>) =
    connect(from, outlet.name, to, inlet.name)

fun <Api> HostManagementApi.connect(from: CellRef, outlet: OutletId<Api>, to: CellRef, inlet: InletId<Api>): LinkResult =
    connect(from, outlet.name, to, inlet.name)

fun <Api> Use<HostManagementApi>.connect(from: CellRef, outlet: OutletId<Api>, to: CellRef, inlet: InletId<Api>): LinkResult =
    call.connect(from, outlet.name, to, inlet.name)
