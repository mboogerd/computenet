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
 * Limit: beyond the typed-field assertions, the second-home check is a *name*
 * heuristic, so a raw re-implementation under an unsuggestive name (measured:
 * `parkedForFlip`) passes. It catches the likely accident, not an adversary.
 *
 * Half 2 (import fence): [InstanceIndex]'s source imports none of
 * `ManagedHost`, `InvocationSink`, `ParkQueue`, or `InstanceSet` — the
 * extraction's KDoc claims this ("No reference to ManagedHost,
 * InvocationSink, ParkQueue, or LocationRegistry"); this half makes the claim
 * a build-breaking fact instead of prose. Scans only lines matching `^import
 * ` (KDoc prose may name these types freely, e.g. to describe the boundary).
 *
 * Half 3 (code-body fence) exists because half 2 alone is **vacuous for
 * `ManagedHost`**: it lives in `civictech.cell.host`, the same package as
 * [InstanceIndex], so a creep-back reference needs no import line and half 2
 * stays green (measured 2026-08-15: adding `internal var creepBack:
 * ManagedHost? = null` to InstanceIndex left the import fence passing). Since
 * reaching back for [ManagedHost] is precisely the creep-back the extraction
 * exists to prevent, half 3 scans the *code* of InstanceIndex.kt — comments
 * and KDoc stripped — for any word-boundary occurrence of the four names,
 * which also catches a fully-qualified reference that dodges the import line.
 * Limit: the comment stripper is textual, so a forbidden name appearing
 * inside a string literal that itself contains comment punctuation could be
 * misjudged; InstanceIndex.kt has no such literal today.
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

    @Test
    fun `InstanceIndex code body references none of ManagedHost, InvocationSink, ParkQueue, InstanceSet`() {
        val root = repoRoot()
        val file = File(root, "kernel/src/main/kotlin/civictech/cell/host/InstanceIndex.kt")
        assertTrue(file.isFile) { "Missing source file: ${file.path}" }

        val codeLines = stripComments(file.readLines())
        assertTrue(codeLines.any { it.contains("class InstanceIndex") }) {
            "comment stripping ate the class declaration — the scan is broken, not the fence " +
                "(kept ${codeLines.size} code lines)"
        }

        val forbidden = listOf("ManagedHost", "InvocationSink", "ParkQueue", "InstanceSet")
        val offending = codeLines.filter { line ->
            forbidden.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(line) }
        }
        assertTrue(offending.isEmpty()) {
            "InstanceIndex.kt's code references a type its own KDoc says it has no reference to " +
                "(ManagedHost needs no import — it is in the same package, so the import fence " +
                "cannot see it): ${offending.map { it.trim() }}"
        }
    }

    /** Drop KDoc/block comments and `//` tails, keeping only lines with code left on them. */
    private fun stripComments(lines: List<String>): List<String> {
        var inBlock = false
        val out = mutableListOf<String>()
        for (raw in lines) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) continue
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            if (line.isNotBlank()) out += line
        }
        return out
    }
}
