package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModuleCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.reflect.KClass

/**
 * The registration-coverage lint (`[WIR1-C05]`, `[WIR1-C12]`, computenet-ncz.2.3):
 * every discriminator `WireCodec` registers has a positive vector in the corpus,
 * or an explicit `manifest.pending` entry naming the bead that owes one.
 *
 * ## How the registrations are read (ncz.2-D1)
 *
 * `WireCodec` exposes no registration list, and this feature forbids a
 * production diff, so the lint reads the PRIVATE `json` field reflectively and
 * walks its `serializersModule` with the public `SerializersModule.dumpTo`.
 * That is test-only reflection into a kernel object: a rename of the field
 * fails [liveJson] with the fields it did find, never a silent pass. The
 * fallback, if reflection ever stops working, is a read-only accessor on
 * `WireCodec` filed as its own bead — not added here.
 *
 * `json` is the LIVE codec — the baseline plus any `contribute()` not yet
 * withdrawn. `WsLateWireSerializersRoundTripTest` contributes a `civictech.wire`
 * type and withdraws it in a `finally`; under JUnit's same-thread sequential
 * execution that contribution cannot be live while this method runs, so seeing
 * one is a leak (a missed `withdraw`), and the lint fails naming it instead of
 * filtering it out. The snapshot is therefore taken inside the test method,
 * never at class init.
 *
 * ## What counts as vectored
 *
 * A discriminator is vectored when some POSITIVE vector's `decoded` contains a
 * `{type, fields}` or `{type, value}` node with that `type`, excluding the
 * reserved envelope/handshake names [RESERVED_TYPES]. Neither the registered
 * set nor the vectored set is a literal here: both are derived on every run, so
 * a new registration goes red the day it lands.
 *
 * ## Strict mode (ncz.2-D6)
 *
 * With `-Dwire.vectors.strict=true` (forwarded by `wire/build.gradle.kts`) a
 * non-empty `pending` also fails. It is OFF by default until the last feature
 * draining `pending` lands and makes it the committed default.
 */
class RegistrationCoverageTest {

    /** One `polymorphic(baseClass, actualClass, serializer)` call seen by `dumpTo`. */
    data class Registration(val base: String, val name: String, val actual: String)

    @Test
    fun `every WireCodec registration has a positive vector or a pending entry`() {
        val registrations = registrationsOf(liveJson())
        val loader = VectorLoader.locate()
        val findings = coverageFindings(
            registrations = registrations,
            vectored = vectoredDiscriminators(loader.documents()),
            pending = loader.pending().map { it.discriminator },
            strict = System.getProperty("wire.vectors.strict") == "true",
        )
        if (findings.isNotEmpty()) {
            fail(
                "registration coverage lint (${registrations.size} registrations under bases " +
                    "${registrations.map { it.base }.toSet()}, corpus ${loader.root}):\n" +
                    findings.joinToString("\n") { "  - $it" },
            )
        }
    }

    // The derivation seams, pinned on synthetic inputs so a vacuous lint (an
    // empty registration read, a vectored set that swallows everything) cannot
    // pass silently. The corpus-data mutations in the landing comment cover the
    // same rules end to end.

    @Test
    fun `the live codec's registrations are read, not an empty module`() {
        val registrations = registrationsOf(liveJson())
        val names = registrations.map { it.name }.toSet()
        // two anchors, not a count: the seeds' own discriminators and one Interest arm
        assertTrue(names.containsAll(setOf("Stall", "Resume", "Interest.Total")), "registered names: $names")
        assertEquals(registrations.size, names.size, "duplicate discriminators: $registrations")
    }

    @Test
    fun `vectored reads type nodes at any depth and ignores reserved names and plain string fields`() {
        val decoded = Json.parseToJsonElement(
            """
            {"type":"frame","fields":{"type":"PORT_API","args":[
              {"type":"Owned","fields":{"value":{"type":"Stall","fields":{"reason":"SUSPENDED"}}}},
              {"type":"kotlin.Long","value":7},
              {"entries":[{"key":{"type":"Uuid","value":"x"},"value":{"type":"HELLO2","fields":{}}}]}
            ]}}
            """,
        )
        assertEquals(setOf("Owned", "Stall", "kotlin.Long", "Uuid"), typeNodes(decoded) - RESERVED_TYPES)
    }

    @Test
    fun `each lint rule fires and names its discriminator`() {
        fun reg(name: String, base: String = "kotlin.Any", actual: String = "civictech.cell.$name") =
            Registration(base, name, actual)
        val registered = listOf(reg("A"), reg("B"), reg("C"), reg("Interest.Total", base = INTEREST))

        assertEquals(emptyList<String>(), coverageFindings(registered, setOf("A", "Interest.Total"), listOf("B", "C"), strict = false))

        val unvectored = coverageFindings(registered, setOf("A", "Interest.Total"), listOf("B"), strict = false)
        assertEquals(1, unvectored.size, "$unvectored")
        assertTrue(unvectored.single().contains("no vector and no pending entry") && unvectored.single().contains("C"))

        val stale = coverageFindings(registered, setOf("A", "B", "Interest.Total"), listOf("B", "C"), strict = false)
        assertTrue(stale.single().startsWith("stale pending entry") && stale.single().contains("B"), "$stale")

        val unknown = coverageFindings(registered, setOf("A", "Interest.Total"), listOf("B", "C", "Gone"), strict = false)
        assertTrue(unknown.single().startsWith("pending names a discriminator the codec does not register") && unknown.single().contains("Gone"), "$unknown")

        val strict = coverageFindings(registered, setOf("A", "Interest.Total"), listOf("B", "C"), strict = true)
        assertTrue(strict.single().startsWith("strict mode") && strict.single().contains("B") && strict.single().contains("C"), "$strict")

        val thirdBase = coverageFindings(registered + reg("X", base = "civictech.cell.Other"), setOf("A", "X", "Interest.Total"), listOf("B", "C"), strict = false)
        assertTrue(thirdBase.single().startsWith("unexpected polymorphic base") && thirdBase.single().contains("civictech.cell.Other"), "$thirdBase")

        val leaked = coverageFindings(registered + reg("Late", actual = "civictech.wire.LateDelta"), setOf("A", "Late", "Interest.Total"), listOf("B", "C"), strict = false)
        assertTrue(leaked.single().startsWith("leaked :wire test-local contribution") && leaked.single().contains("civictech.wire.LateDelta"), "$leaked")
    }

    companion object {
        private const val INTEREST = "civictech.cell.link.Interest"

        /** The polymorphic bases the corpus has a category for; a third is a finding. */
        val EXPECTED_BASES: Set<String> = setOf("kotlin.Any", INTEREST)

        /** `type` values that name the frame envelope or a handshake line, not a registration. */
        val RESERVED_TYPES: Set<String> = setOf("frame", "HELLO", "HELLO2", "PROOF")

        /** `WireCodec`'s private live `Json`, read reflectively (ncz.2-D1). */
        fun liveJson(): Json {
            val field = try {
                WireCodec::class.java.getDeclaredField("json")
            } catch (e: NoSuchFieldException) {
                fail(
                    "WireCodec has no `json` field any more (ncz.2-D1's reflective read); declared fields: " +
                        WireCodec::class.java.declaredFields.joinToString { "${it.name}: ${it.type.name}" },
                )
            }
            field.isAccessible = true
            return field.get(WireCodec) as? Json
                ?: fail("WireCodec.json is not a kotlinx Json: ${field.type.name}")
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun registrationsOf(json: Json): List<Registration> {
            val out = mutableListOf<Registration>()
            json.serializersModule.dumpTo(object : SerializersModuleCollector {
                override fun <T : Any> contextual(
                    kClass: KClass<T>,
                    provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>,
                ) = Unit

                override fun <Base : Any, Sub : Base> polymorphic(
                    baseClass: KClass<Base>,
                    actualClass: KClass<Sub>,
                    actualSerializer: KSerializer<Sub>,
                ) {
                    out += Registration(
                        base = baseClass.qualifiedName ?: baseClass.java.name,
                        name = actualSerializer.descriptor.serialName,
                        actual = actualClass.qualifiedName ?: actualClass.java.name,
                    )
                }

                override fun <Base : Any> polymorphicDefaultSerializer(
                    baseClass: KClass<Base>,
                    defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?,
                ) = Unit

                override fun <Base : Any> polymorphicDefaultDeserializer(
                    baseClass: KClass<Base>,
                    defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?,
                ) = Unit
            })
            return out
        }

        /** Every `type` of a `{type, fields|value}` node in each positive vector's `decoded`, minus [RESERVED_TYPES]. */
        fun vectoredDiscriminators(documents: List<VectorDocument>): Set<String> =
            documents.filter { it.kind.positive }.flatMap { doc -> doc.decoded?.let(::typeNodes) ?: emptySet() }
                .toSet() - RESERVED_TYPES

        fun typeNodes(element: JsonElement): Set<String> = buildSet {
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        val type = e["type"] as? JsonPrimitive
                        if (type != null && type.isString && ("fields" in e || "value" in e)) add(type.content)
                        e.values.forEach(::walk)
                    }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(element)
        }

        /** The lint's rules, each finding naming the discriminators it concerns. Empty means pass. */
        fun coverageFindings(
            registrations: List<Registration>,
            vectored: Set<String>,
            pending: List<String>,
            strict: Boolean,
        ): List<String> = buildList {
            val bases = registrations.map { it.base }.toSet() - EXPECTED_BASES
            if (bases.isNotEmpty()) {
                add(
                    "unexpected polymorphic base(s) $bases — the corpus has no category for them: " +
                        registrations.filter { it.base in bases }.map { "${it.base}/${it.name}" },
                )
            }
            val leaked = registrations.filter { it.actual.startsWith("civictech.wire.") }
            if (leaked.isNotEmpty()) {
                add("leaked :wire test-local contribution(s) still registered (withdraw did not run): ${leaked.map { "${it.name} (${it.actual})" }}")
            }
            val registered = registrations.map { it.name }.toSet()
            val pendingSet = pending.toSet()
            val uncovered = registered - vectored - pendingSet
            if (uncovered.isNotEmpty()) add("registration has no vector and no pending entry: ${uncovered.sorted()}")
            val stale = pendingSet intersect vectored
            if (stale.isNotEmpty()) add("stale pending entry — a vector already covers: ${stale.sorted()}")
            val unknown = pendingSet - registered
            if (unknown.isNotEmpty()) add("pending names a discriminator the codec does not register — a rename or removal happened: ${unknown.sorted()}")
            if (strict && pending.isNotEmpty()) {
                add("strict mode (wire.vectors.strict=true) requires an empty pending list; ${pending.size} pending: $pending")
            }
        }
    }
}
