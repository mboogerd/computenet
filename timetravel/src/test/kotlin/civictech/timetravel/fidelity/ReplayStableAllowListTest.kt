package civictech.timetravel.fidelity

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.evolve.Effectful
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.zip.ZipFile

/**
 * TTD1 F3 (computenet-kxex2.1) D2: the replay-stable allow-list is itself tested
 * [TTD1-35], [TTD1-38]. Enumerates every concrete [Cell] class actually on the classpath
 * under `civictech.cell.data` (rather than hand-listing them, which would drift) and
 * asserts each classifies [Fidelity.Faithful]; and pins the Effectful-first-then-vouch
 * order (kxex2-D7) and the conservative default with test-local probe classes.
 */
class ReplayStableAllowListTest {

    /** [Cell] is never Effectful and never in a data package: a plain non-vacuity control. */
    private class OpaqueProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell

    private class EffectfulProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful

    private open class VouchedBase(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell

    private class UnvouchedSubclass : VouchedBase()

    @Test
    fun everyConcreteDataCellClassifiesFaithfulUnderDefault() {
        val classes = concreteDataCellClasses()

        classes.shouldNotBeEmpty()

        // Non-vacuity control: at least these seven must be present.
        val simpleNames = classes.map { it.simpleName }.toSet()
        listOf("SetCell", "MapCell", "OrMapCell", "PnCounterCell", "CounterCell", "ListCell", "KeyedSetCell")
            .forEach { simpleNames.shouldContain(it) }

        for (cls in classes) {
            // The package set is complete for what is on the classpath: every enumerated class's
            // package must be one this test scanned for, which by construction it is — asserted
            // explicitly here so a future package added under civictech.cell.data.* is caught.
            (cls.packageName in ReplayStable.FAITHFUL_PACKAGES) shouldBe true
            ReplayStable.DEFAULT.classify(cls) shouldBe Fidelity.Faithful
        }
    }

    @Test
    fun effectfulClassifiesDegradedUnderDefault() {
        ReplayStable.DEFAULT.classify(EffectfulProbe::class.java) shouldBe
            Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
    }

    @Test
    fun effectfulClassifiesDegradedEvenWhenVouchedFor() {
        val vouching = ReplayStable(setOf(EffectfulProbe::class.java))

        vouching.classify(EffectfulProbe::class.java) shouldBe Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
    }

    @Test
    fun opaqueClassClassifiesDegradedUnderDefault() {
        ReplayStable.DEFAULT.classify(OpaqueProbe::class.java) shouldBe
            Fidelity.Degraded(setOf(Reason.UNKNOWN_DETERMINISM))
    }

    @Test
    fun opaqueClassClassifiesFaithfulWhenVouchedFor() {
        val vouching = ReplayStable(setOf(OpaqueProbe::class.java))

        vouching.classify(OpaqueProbe::class.java) shouldBe Fidelity.Faithful
    }

    @Test
    fun vouchingForAClassDoesNotVouchForItsSubclasses() {
        val vouching = ReplayStable(setOf(VouchedBase::class.java))

        vouching.classify(VouchedBase::class.java) shouldBe Fidelity.Faithful
        vouching.classify(UnvouchedSubclass::class.java) shouldBe Fidelity.Degraded(setOf(Reason.UNKNOWN_DETERMINISM))
    }

    companion object {
        /**
         * Every concrete (non-interface, non-abstract) [Cell] class whose fully-qualified name
         * starts with `civictech.cell.data.`, discovered by walking the code source that
         * declares [Cell] itself (the kernel main code, whether packaged as a classes directory
         * or a jar). Mirrors `testkit`'s `ReplayTest.classesDeclaringAFaultCodecField`, which
         * :timetravel cannot depend on (that shape lives in testkit's *test* source set).
         */
        private fun concreteDataCellClasses(): List<Class<out Cell>> {
            val loader = Cell::class.java.classLoader
            val source = Cell::class.java.protectionDomain.codeSource.location
            val root = File(source.toURI())

            val names: List<String> = if (root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                    .toList()
            } else {
                ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .map { it.removeSuffix(".class").replace('/', '.') }
                        .toList()
                }
            }

            return names
                .filter { it.startsWith("civictech.cell.data.") }
                .mapNotNull { name ->
                    val type = Class.forName(name, false, loader)
                    if (Cell::class.java.isAssignableFrom(type) && !type.isInterface && !Modifier.isAbstract(type.modifiers)) {
                        @Suppress("UNCHECKED_CAST")
                        type as Class<out Cell>
                    } else {
                        null
                    }
                }
        }
    }
}
