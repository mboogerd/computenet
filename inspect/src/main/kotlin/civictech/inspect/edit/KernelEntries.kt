package civictech.inspect.edit

import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.membrane.TrafficLightCell

/**
 * WKB2 F12 (va0c4-D10) — the handful of kernel cells the two demos register
 * into [Catalogue] when their write plane is enabled ([WritePlane.Enabled]),
 * so the epic's manual check (`doc/spec/90-roadmap/97-inspector-plan/`, epic
 * §8 "Manual") has something in the palette to name. Deliberately host-side
 * (feature design item 5): registration is part of the opt-in, not of the
 * read-only instrument, so a process that never asks for the write plane
 * never populates the catalogue.
 *
 * Every entry constructs its cell through `(id, params)` and the handed-in
 * `ref` alone, per the risk-3 guard (epic §9 risk 3, feature design item 6):
 * none needs a host verb that does not already exist.
 *
 * [trafficLight.string]'s name is aspirational but its construction is not:
 * `TrafficLightCell(String::class.java, ref)` throws at construction —
 * `TrafficLightCell`'s inlet/outlet proxy their type parameter through
 * `Proxy.fromClass` (`kernel/.../membrane/TrafficLightCell.kt`), which
 * requires an interface, and `String` is not one. `civictech.cell.Consumer`
 * is (`@Contract interface Consumer<T>`) and is exactly what the kernel's own
 * `TrafficLightTest.kt` instantiates the cell with — never a bare `String` —
 * so this entry builds `TrafficLightCell(Consumer::class.java, ref)`, the
 * same substitution `computenet-va0c4.1`'s `CatalogueTest.kt` already made
 * for the identical reason.
 */
object KernelEntries {

    /** `civictech.cell.data.SetCell<String>` — no parameters. */
    const val SET_STRING = "set.string"

    /**
     * `civictech.cell.data.op.FilterCell<String>` — the feature's "mapper"
     * entry (`civictech.cell.MapperCell` has no generated descriptor and so is
     * not usable as a catalogue entry). One required `prefix: STRING`
     * parameter; the built cell passes through elements whose string form
     * starts with it.
     */
    const val FILTER_STRING_PREFIX = "filter.string.prefix"

    /** `civictech.cell.membrane.TrafficLightCell` — no parameters; see class KDoc. */
    const val TRAFFIC_LIGHT_STRING = "trafficLight.string"

    /**
     * Registers every entry above that is not already registered — idempotent
     * across two demos in one JVM, or two [civictech.inspect.InspectorServer]s
     * started by the same test, per feature rule 2 (`Catalogue.register`
     * itself refuses a duplicate id, so a second call must skip rather than
     * retry it).
     */
    fun register() {
        if (Catalogue.entry(SET_STRING) == null) {
            Catalogue.register(
                CatalogueEntry(
                    id = SET_STRING,
                    descriptorFqn = "civictech.cell.data.SetCell",
                    schema = ParamSchema(emptyList()),
                    build = EntryBuilder { _, ref -> SetCell<String>(ref) },
                ),
            )
        }
        if (Catalogue.entry(FILTER_STRING_PREFIX) == null) {
            Catalogue.register(
                CatalogueEntry(
                    id = FILTER_STRING_PREFIX,
                    descriptorFqn = "civictech.cell.data.op.FilterCell",
                    schema = ParamSchema(listOf(ParamSpec("prefix", ParamKind.STRING))),
                    build = EntryBuilder { params, ref ->
                        val prefix = (params.getValue("prefix") as ParamValue.Str).value
                        FilterCell<String>(ref) { it.startsWith(prefix) }
                    },
                ),
            )
        }
        if (Catalogue.entry(TRAFFIC_LIGHT_STRING) == null) {
            Catalogue.register(
                CatalogueEntry(
                    id = TRAFFIC_LIGHT_STRING,
                    descriptorFqn = "civictech.cell.membrane.TrafficLightCell",
                    schema = ParamSchema(emptyList()),
                    build = EntryBuilder { _, ref -> TrafficLightCell(Consumer::class.java, ref) },
                ),
            )
        }
    }
}
