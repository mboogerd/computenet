package civictech.cell.architecture

import civictech.cell.host.DeliveryHold
import civictech.cell.host.InstanceIndex
import civictech.cell.host.LocationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * BS-18: pins the shape [InstanceIndex] and [DeliveryHold] extracted out of
 * [LocationRegistry] (computenet-iyi epic) so the lanes cannot silently creep
 * back in.
 *
 * Half 1 (declared-field shape): [LocationRegistry] must not itself declare a
 * field holding the interest table, the by-logical-id membership index, or
 * the flip-window hold set — those live exclusively inside [InstanceIndex]
 * and [DeliveryHold] now. A field of one of the extracted types is the only
 * acceptable surviving shape (`instances: InstanceIndex`, `holds:
 * DeliveryHold`); `locations`, `parked`, and `topology` legitimately remain
 * on [LocationRegistry] (location + parking are its own mandate, spec 33/41).
 *
 * Half 2 (import fence): [InstanceIndex]'s source imports none of
 * `ManagedHost`, `InvocationSink`, `ParkQueue`, or `InstanceSet` — the
 * extraction's KDoc claims this ("No reference to ManagedHost,
 * InvocationSink, ParkQueue, or LocationRegistry"); this half makes the claim
 * a build-breaking fact instead of prose. Scans only lines matching `^import
 * ` (KDoc prose may name these types freely, e.g. to describe the boundary).
 */
class ExtractionFenceTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `LocationRegistry declares no field holding the interest table, membership index, or hold set`() {
        val fields = LocationRegistry::class.java.declaredFields

        assertTrue(fields.isNotEmpty()) {
            "LocationRegistry::class.java.declaredFields returned nothing — reflection is scanning the " +
                "wrong class, not evidence the extraction holds"
        }

        val instanceIndexFields = fields.filter { it.type == InstanceIndex::class.java }
        val deliveryHoldFields = fields.filter { it.type == DeliveryHold::class.java }

        assertEquals(1, instanceIndexFields.size) {
            "expected exactly one InstanceIndex-typed field on LocationRegistry (the extracted membership " +
                "index), found: ${instanceIndexFields.map { it.name }}"
        }
        assertEquals(1, deliveryHoldFields.size) {
            "expected exactly one DeliveryHold-typed field on LocationRegistry (the extracted hold set), " +
                "found: ${deliveryHoldFields.map { it.name }}"
        }

        // No other field's *name* suggests it is a second, competing home for the
        // membership index or the hold set (a raw Map/Set doing InstanceIndex's or
        // DeliveryHold's job instead of delegating to it).
        val suspectNames = listOf("byLogicalId", "interest", "held", "holds")
        val stragglers = fields.filter { field ->
            field.type != InstanceIndex::class.java &&
                field.type != DeliveryHold::class.java &&
                suspectNames.any { field.name.contains(it, ignoreCase = true) }
        }
        assertTrue(stragglers.isEmpty()) {
            "LocationRegistry field(s) look like a second home for state InstanceIndex/DeliveryHold now " +
                "owns: ${stragglers.map { "${it.name}: ${it.type.simpleName}" }}"
        }
    }

    @Test
    fun `InstanceIndex imports none of ManagedHost, InvocationSink, ParkQueue, InstanceSet`() {
        val root = repoRoot()
        val file = File(root, "kernel/src/main/kotlin/civictech/cell/host/InstanceIndex.kt")
        assertTrue(file.isFile) { "Missing source file: ${file.path}" }

        val forbidden = listOf("ManagedHost", "InvocationSink", "ParkQueue", "InstanceSet")
        val importLines = file.readLines().filter { it.trimStart().startsWith("import ") }

        assertTrue(importLines.isNotEmpty()) {
            "InstanceIndex.kt has no import lines at all — scan is broken, not the fence " +
                "(the file at minimum imports civictech.cell.CellRef and java.util.*)"
        }

        val offending = importLines.filter { line -> forbidden.any { line.contains(it) } }
        assertTrue(offending.isEmpty()) {
            "InstanceIndex.kt imports a type its own KDoc says it has no reference to: $offending"
        }
    }
}
