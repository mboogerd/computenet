package civictech.gen.wire

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP-F2: nature marker interfaces on a cell surface as the expected
 * `PortDescriptor.natures` in the generated descriptor, mirroring the way the
 * existing [CellColor] scan is emitted. Cell-level markers (`Blocking`,
 * `Replicable`) fold onto every port; per-port axes (`Owned`, `Magnitude`) come
 * off the port's own Api contract. Unmarked ports keep the natureless 4-arg
 * form — the zero-behavior-change default.
 */
@OptIn(ExperimentalCompilerApi::class)
class NatureDescriptorSweepTest {

    // gen cannot depend on kernel — restate the marker FQNs the processor scans.
    private val cellStubs = """
        package civictech.cell
        interface Cell
        interface BlockingCell : Cell
        interface SuspendingCell : Cell
        class Owned<T>
        class Leased<T>
        """.trimIndent()
    private val dataStubs = """
        package civictech.cell.data
        import civictech.cell.Cell
        interface Replicable<D> : Cell
        """.trimIndent()
    private val controlStubs = """
        package civictech.cell.control
        interface Magnitude
        """.trimIndent()
    private val portStubs = """
        package civictech.cell.port
        class FanInlet<Api : Any>
        class FanOutlet<Api : Any>
        """.trimIndent()
    private val graphStubs = """
        package civictech.cell.graph
        class InletId<Api>(val name: String)
        class OutletId<Api>(val name: String)
        """.trimIndent()

    @Test
    fun `cell and port markers fold into PortDescriptor natures`() {
        val (compilation, result) = compileKeepingSources(
            cellStubs, dataStubs, controlStubs, portStubs, graphStubs,
            """
            package example
            import civictech.cell.Cell
            import civictech.cell.BlockingCell
            import civictech.cell.Owned
            import civictech.cell.control.Magnitude
            import civictech.cell.data.Replicable
            import civictech.cell.port.FanInlet
            import civictech.cell.port.FanOutlet

            interface SharedOps { fun ping() }
            interface OwnedOps { fun push(v: Owned<String>) }
            interface MagnitudeOps { fun bump(m: Magnitude) }

            // Blocking + Replicable cell: color + merge fold onto every port.
            class GossipCell : Cell, BlockingCell, Replicable<String> {
                val shared: FanInlet<SharedOps> = FanInlet()
                val owned: FanInlet<OwnedOps> = FanInlet()
                val meter: FanOutlet<MagnitudeOps> = FanOutlet()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val table = generatedSource(compilation, "ContractTable_")
        // color + merge-idempotence fold onto every port of the Blocking Replicable cell
        assertTrue(
            "PortDescriptor(\"shared\", PortDirection.IN, \"example.SharedOps\", " in table &&
                "natures = NatureVector.of(Color.BLOCKING, MergeClass.IDEMPOTENT))" in table,
            table,
        )
        // the Owned-carrying port additionally gains EXCLUSIVE ownership
        assertTrue(
            "PortDescriptor(\"owned\", PortDirection.IN, \"example.OwnedOps\", " in table &&
                "natures = NatureVector.of(Color.BLOCKING, MergeClass.IDEMPOTENT, Ownership.EXCLUSIVE))" in table,
            table,
        )
        // the Magnitude-carrying port additionally gains MONOTONE monotonicity
        assertTrue(
            "PortDescriptor(\"meter\", PortDirection.OUT, \"example.MagnitudeOps\", " in table &&
                "natures = NatureVector.of(Color.BLOCKING, MergeClass.IDEMPOTENT, Monotonicity.MONOTONE))" in table,
            table,
        )
    }

    @Test
    fun `an unmarked cell keeps the natureless default port descriptor`() {
        val (compilation, result) = compileKeepingSources(
            cellStubs, dataStubs, portStubs, graphStubs,
            """
            package example
            import civictech.cell.Cell
            import civictech.cell.port.FanInlet

            interface Ops { fun ping() }
            class PlainCell : Cell {
                val inlet: FanInlet<Ops> = FanInlet()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val table = generatedSource(compilation, "ContractTable_")
        assertTrue("PortDescriptor(\"inlet\", PortDirection.IN, \"example.Ops\"" in table, table)
        // no natures arg emitted for a default port
        assertTrue("natures" !in table.substringAfter("PlainCell"), table)
    }

    private fun generatedSources(compilation: KotlinCompilation) =
        compilation.kspSourcesDir.walkTopDown().filter { it.isFile }.toList()

    private fun generatedSource(compilation: KotlinCompilation, nameFragment: String): String =
        generatedSources(compilation)
            .firstOrNull { it.name.contains(nameFragment.removeSuffix(".kt")) }
            ?.readText()
            ?: error(
                "no generated source matching '$nameFragment' among:\n" +
                    generatedSources(compilation).joinToString("\n") { it.path }
            )

    private fun compileKeepingSources(vararg source: String): Pair<KotlinCompilation, JvmCompilationResult> {
        val compilation = KotlinCompilation().apply {
            sources = source.mapIndexed { index, text -> SourceFile.kotlin("Source$index.kt", text) }
            inheritClassPath = true
            messageOutputStream = System.out
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ContractProcessorProvider()
            }
        }
        return compilation to compilation.compile()
    }
}
