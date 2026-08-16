package civictech.gen.wire

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class ContractProcessorTest {

    @Test
    fun `data contracts reject non Unit returns`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract

            @Contract
            interface QueryContract {
                fun query(value: String): String
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("data contract example.QueryContract#query returns kotlin.String"))
        assertTrue(result.messages.contains("push-only on the data path"))
    }

    @Test
    fun `broadcast exclusive methods require a key`() {
        val result = compile(
            """
            package civictech.cell
            class Owned<T>
            """.trimIndent(),
            """
            package example
            import civictech.cell.Owned
            import civictech.gen.wire.Contract

            @Contract
            interface ExclusiveContract {
                fun push(value: Owned<String>)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("broadcasts an exclusive Owned/Leased payload"))
        assertTrue(result.messages.contains("annotate its routing parameter with @Key"))
    }

    @Test
    fun `keyed exclusive methods compile`() {
        val result = compile(
            """
            package civictech.cell
            class Owned<T>
            """.trimIndent(),
            """
            package example
            import civictech.cell.Owned
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Key

            @Contract
            interface ExclusiveContract {
                fun push(@Key value: Owned<String>)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `data contracts reject more than one key`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Key

            @Contract
            interface AmbiguousKeyContract {
                fun push(@Key tenant: String, @Key partition: String)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("has more than one @Key parameter"))
    }

    @Test
    fun `management contracts may return values`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract

            @Contract(management = true)
            interface QueryContract {
                fun query(value: String): String
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // W4.6 (C-5 completion): a `@Contract` interface must get a KSP-generated
    // proxy class — not a `java.lang.reflect.Proxy.newProxyInstance` dynamic
    // proxy — registered for `civictech.gen.wire.ProxyRegistry` (ServiceLoader
    // discovery, mirroring `ContractRegistry`).
    @Test
    fun `contracts get a KSP-generated proxy registered for ProxyRegistry`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract

            @Contract
            interface GreetContract {
                fun greet(name: String)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val classLoader = result.classLoader
        val contractClass = classLoader.loadClass("example.GreetContract")

        // Locate the generated ProxyModule class by its well-known package and
        // "ProxyTable_" prefix (rather than through the `ProxyRegistry`
        // singleton, whose ServiceLoader scan runs once per classloader and may
        // already have run against an earlier compilation in this JVM before
        // this module's service entry existed; the compile-testing harness
        // also doesn't surface KSP resource outputs the way a real Gradle
        // build's classpath does).
        val moduleClassFile = result.compiledClassAndResourceFiles.firstOrNull {
            it.path.contains("civictech/gen/wire/generated/ProxyTable_") && it.path.endsWith(".class")
        } ?: error(
            "no generated ProxyTable_* class among:\n" +
                result.compiledClassAndResourceFiles.joinToString("\n") { it.path }
        )
        val moduleClassName = "civictech.gen.wire.generated." + moduleClassFile.name.removeSuffix(".class")
        val proxyModuleClass = classLoader.loadClass("civictech.gen.wire.ProxyModule")
        val moduleInstance = classLoader.loadClass(moduleClassName).getDeclaredConstructor().newInstance()
        val factoriesMethod = proxyModuleClass.getMethod("getFactories")
        @Suppress("UNCHECKED_CAST")
        val factories = factoriesMethod.invoke(moduleInstance) as Map<Class<*>, (java.lang.reflect.InvocationHandler) -> Any>

        val factory = factories[contractClass]
        assertTrue(factory != null, "no generated proxy constructor registered for example.GreetContract")

        val calls = mutableListOf<String>()
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            calls += "${method.name}(${args?.joinToString(",")})"
            null
        }
        val instance = factory!!.invoke(handler)

        assertTrue(
            !java.lang.reflect.Proxy.isProxyClass(instance.javaClass),
            "expected a KSP-generated class, got a JDK dynamic proxy: ${instance.javaClass}",
        )

        val greet = contractClass.getMethod("greet", String::class.java)
        greet.invoke(instance, "world")

        assertEquals(listOf("greet(world)"), calls)
    }

    // Minimal kernel stubs for the port scan (gen cannot depend on kernel).
    private val portStubs = """
        package civictech.cell
        interface Cell
        """.trimIndent()
    private val portClassStubs = """
        package civictech.cell.port
        class FanInlet<Api : Any>
        class FanOutlet<Api : Any>
        """.trimIndent()
    private val graphStubs = """
        package civictech.cell.graph
        class InletId<Api>(val name: String)
        class OutletId<Api>(val name: String)
        """.trimIndent()

    // T09 §D: process() was one 301-line method interleaving round-1 gating,
    // discovery, three inline buildCodeBlock table builders, and five inline
    // lints. The lints moved to ContractLints (unit-tested directly below) and
    // the table builders moved to contractTable()/protocolTable()/cellTable() —
    // this test pins the full generated ContractTable_*.kt text (not just
    // substrings, unlike the other generation tests in this file) so that
    // extraction, or any future one, cannot silently reshape the emitted code.
    @Test
    fun `full ContractTable output is byte-for-byte pinned across contract, protocol, and cell tables`() {
        val (compilation, result) = compileKeepingSources(
            portStubs, portClassStubs, graphStubs,
            """
            package example

            import civictech.gen.wire.Contract
            import civictech.gen.wire.Protocol
            import civictech.nature.ProtocolDirection
            import civictech.cell.Cell
            import civictech.cell.port.FanInlet

            @Contract
            interface PingContract {
                fun ping(id: String)
            }

            @Contract(management = true)
            @Protocol("ping-protocol", ProtocolDirection.UPSTREAM, band = 0)
            interface PingProtocol {
                fun ping(id: String)
            }

            class PingCell : Cell {
                val inlet: FanInlet<PingContract> = FanInlet()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        // Independently re-derive the ids ContractProcessor computes (StableHash
        // is pure/deterministic — pinned separately by StableHash's own tests) so
        // this assertion doesn't read expected values out of the very output
        // under test. KotlinPoet renders long literals with `_` digit-group
        // separators, so the ids are formatted the same way it would.
        val pingContractId = underscored(civictech.nature.StableHash.of("example.PingContract"))
        val pingProtocolContractId = underscored(civictech.nature.StableHash.of("example.PingProtocol"))
        val pingContractMethodId = underscored(civictech.nature.StableHash.of("example.PingContract#ping(Ljava/lang/String;)V"))
        val pingProtocolMethodId = underscored(civictech.nature.StableHash.of("example.PingProtocol#ping(Ljava/lang/String;)V"))
        val moduleHash = civictech.nature.StableHash.of(
            "contract:example.PingContract,contract:example.PingProtocol,cell:example.PingCell"
        )
        val moduleClassName = "ContractTable_" + java.lang.Long.toHexString(moduleHash)

        val table = generatedSource(compilation, "ContractTable_")
        assertEquals(
            "package civictech.gen.wire.generated\n" +
                "\n" +
                "import civictech.nature.CellColor\n" +
                "import civictech.nature.CellDescriptor\n" +
                "import civictech.nature.ContractDescriptor\n" +
                "import civictech.nature.ContractModule\n" +
                "import civictech.nature.MethodDescriptor\n" +
                "import civictech.nature.PortDescriptor\n" +
                "import civictech.nature.PortDirection\n" +
                "import civictech.nature.ProtocolDescriptor\n" +
                "import civictech.nature.ProtocolDirection\n" +
                "import kotlin.collections.List\n" +
                "\n" +
                "public class $moduleClassName : ContractModule {\n" +
                "  override val contracts: List<ContractDescriptor> = listOf(\n" +
                "        ContractDescriptor(contractId = ${pingContractId}L, fqn = \"example.PingContract\", management = false, effect = false, methods = listOf(\n" +
                "          MethodDescriptor(methodId = ${pingContractMethodId}L, name = \"ping\", jvmDescriptor = \"(Ljava/lang/String;)V\", exclusive = false, magnitude = false, idempotentMerge = false, keyIndex = -1),\n" +
                "        )),\n" +
                "        ContractDescriptor(contractId = ${pingProtocolContractId}L, fqn = \"example.PingProtocol\", management = true, effect = false, methods = listOf(\n" +
                "          MethodDescriptor(methodId = ${pingProtocolMethodId}L, name = \"ping\", jvmDescriptor = \"(Ljava/lang/String;)V\", exclusive = false, magnitude = false, idempotentMerge = false, keyIndex = -1),\n" +
                "        )),\n" +
                "      )\n" +
                "\n" +
                "  override val cells: List<CellDescriptor> = listOf(\n" +
                "        CellDescriptor(fqn = \"example.PingCell\", color = CellColor.PURE, ports = listOf(\n" +
                "          PortDescriptor(\"inlet\", PortDirection.IN, \"example.PingContract\", ${pingContractId}L),\n" +
                "        )),\n" +
                "      )\n" +
                "\n" +
                "  override val protocols: List<ProtocolDescriptor> = listOf(\n" +
                "        ProtocolDescriptor(\"ping-protocol\", ${pingProtocolContractId}L, ProtocolDirection.UPSTREAM, 0),\n" +
                "      )\n" +
                "}\n",
            table,
        )
    }

    /** KotlinPoet's `%L` long-literal rendering: `_` every three digits from the right. */
    private fun underscored(value: Long): String {
        val negative = value < 0
        val digits = value.toString().removePrefix("-")
        val grouped = digits.reversed().chunked(3).joinToString("_").reversed()
        return if (negative) "-$grouped" else grouped
    }

    @Test
    fun `generic cell gets a Ports object with type-parameterized accessors and port descriptors`() {
        val (compilation, result) = compileKeepingSources(
            portStubs, portClassStubs, graphStubs,
            """
            package example
            import civictech.cell.Cell
            import civictech.cell.port.FanInlet
            import civictech.cell.port.FanOutlet

            interface Ops<E> { fun add(e: E) }
            class BagCell<E> : Cell {
                val inlet: FanInlet<Ops<E>> = FanInlet()
                val outlet: FanOutlet<Ops<E>> = FanOutlet()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val generated = generatedSource(compilation, "BagCellPorts.kt")
        assertTrue("const val INLET: String = \"inlet\"" in generated, generated)
        assertTrue("public fun <E> inlet(): InletId<Ops<E>> = InletId(INLET)" in generated, generated)
        assertTrue("public fun <E> outlet(): OutletId<Ops<E>> = OutletId(OUTLET)" in generated, generated)

        val table = generatedSource(compilation, "ContractTable_")
        assertTrue("PortDescriptor(\"inlet\", PortDirection.IN, \"example.Ops\"" in table, table)
        assertTrue("PortDescriptor(\"outlet\", PortDirection.OUT, \"example.Ops\"" in table, table)
    }

    @Test
    fun `non-generic cell gets val port ids and private cell gets descriptors only`() {
        val (compilation, result) = compileKeepingSources(
            portStubs, portClassStubs, graphStubs,
            """
            package example
            import civictech.cell.Cell
            import civictech.cell.port.FanInlet

            interface Ops { fun ping() }
            class PlainCell : Cell {
                val inlet: FanInlet<Ops> = FanInlet()
            }
            private class HiddenCell : Cell {
                val inlet: FanInlet<Ops> = FanInlet()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val generated = generatedSource(compilation, "PlainCellPorts.kt")
        assertTrue("public val inlet: InletId<Ops> = InletId(INLET)" in generated, generated)
        assertTrue(generatedSources(compilation).none { it.name == "HiddenCellPorts.kt" })
        val table = generatedSource(compilation, "ContractTable_")
        assertTrue("example.HiddenCell" in table, table) // descriptor rows still emitted
    }

    // Richer stubs for @CellBase generation: the generated base calls
    // registerPort/FanInlet.create/onEach/serve, so the stubs carry those shapes.
    private val cellBaseStubs = """
        package civictech.cell
        interface Cell { val ref: CellRef }
        class CellRef(val id: java.util.UUID)
        """.trimIndent()
    private val cellBasePortStubs = """
        package civictech.cell.port
        interface Serve<Api>
        interface Use<Api>
        interface Subscribe<Api>
        class FanInlet<Api : Any> : Serve<Api>, Use<Api> {
            fun serve(api: Api) {}
            companion object { inline fun <reified Api : Any> create(): FanInlet<Api> = FanInlet() }
        }
        class FanOutlet<Api : Any> : Subscribe<Api> {
            companion object { inline fun <reified Api : Any> create(): FanOutlet<Api> = FanOutlet() }
        }
        fun <P : Any> Any.registerPort(name: String, port: P): P = port
        """.trimIndent()
    private val cellBaseDataStubs = """
        package civictech.cell
        import civictech.cell.port.Serve
        fun interface Propagate<T> { fun propagate(value: T) }
        fun <T> Serve<Propagate<T>>.onEach(handler: (T) -> Unit) {}
        """.trimIndent()

    @Test
    fun `CellBase interface generates an abstract base with registered ports and bound handlers`() {
        val (compilation, result) = compileKeepingSources(
            cellBaseStubs, cellBasePortStubs, cellBaseDataStubs,
            """
            package example
            import civictech.gen.wire.CellBase
            import civictech.cell.Propagate
            import civictech.cell.port.Serve
            import civictech.cell.port.Subscribe
            import civictech.cell.port.Use

            interface Ops { fun ping() }

            @CellBase
            interface EchoApi {
                val inlet: Serve<Propagate<String>>
                val ops: Use<Ops>
                val outlet: Subscribe<Propagate<String>>
            }

            @CellBase
            interface BoxApi<T> {
                val input: Serve<Propagate<T>>
                val output: Subscribe<Propagate<T>>
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        // normalize KotlinPoet's line wrapping before asserting on shapes
        val echo = generatedSource(compilation, "EchoCellBase.kt").replace(Regex("\\s+"), " ")
        assertTrue("public abstract class EchoCellBase(" in echo, echo)
        assertTrue("override val inlet: FanInlet<Propagate<String>> = registerPort(\"inlet\", FanInlet.create<Propagate<String>>())" in echo, echo)
        assertTrue("protected abstract fun onInlet(`value`: String)" in echo, echo)
        assertTrue("protected abstract fun opsHandler(): Ops" in echo, echo)
        assertTrue("inlet.onEach(this::onInlet)" in echo, echo)
        assertTrue("ops.serve(opsHandler())" in echo, echo)
        assertTrue("override val outlet: FanOutlet<Propagate<String>>" in echo, echo)

        val box = generatedSource(compilation, "BoxCellBase.kt").replace(Regex("\\s+"), " ")
        assertTrue("public abstract class BoxCellBase<T>(" in box, box)
        assertTrue("protected abstract fun onInput(`value`: T)" in box, box)
    }

    @Test
    fun `CellBase on a class is a compile error`() {
        val result = compile(
            cellBaseStubs,
            """
            package example
            import civictech.gen.wire.CellBase

            @CellBase
            class NotAnInterface
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("targets the cell's Api interface"))
    }

    // T09 §B: an unresolvable @CellBase port Api type used to be a warning — the
    // member is left abstract, so a concrete subclass either fails to compile
    // (fine) or someone hand-implements it outside the generated-port machinery
    // (silently missing its descriptor row and static handler binding). `Serve<*>`
    // is the simplest legal Kotlin that makes the Api type argument a star
    // projection (`KSTypeArgument.type == null`), which is exactly what the
    // processor treats as unresolvable.
    @Test
    fun `CellBase unresolvable port Api type is now an error, not a warning`() {
        val result = compile(
            cellBaseStubs,
            cellBasePortStubs,
            """
            package example
            import civictech.gen.wire.CellBase
            import civictech.cell.port.Serve

            @CellBase
            interface StarApi {
                val inlet: Serve<*>
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("StarApi.inlet: unresolvable port Api type — left abstract"), result.messages)
    }

    // T09 §B: an unresolvable Propagate<T> payload used to be a warning — the
    // port is registered but its on<Name> handler is never generated or bound,
    // so the inlet accepts messages and silently drops every one. `Propagate<*>`
    // makes the payload argument a star projection, unresolvable the same way.
    @Test
    fun `CellBase unresolvable Propagate payload is now an error, not a warning`() {
        val result = compile(
            cellBaseStubs,
            cellBasePortStubs,
            cellBaseDataStubs,
            """
            package example
            import civictech.gen.wire.CellBase
            import civictech.cell.Propagate
            import civictech.cell.port.Serve

            @CellBase
            interface StarPayloadApi {
                val inlet: Serve<Propagate<*>>
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("StarPayloadApi.inlet: unresolvable payload — not auto-bound"),
            result.messages,
        )
    }

    // T09 §B: the port scan's own "unresolvable Api type" diagnostic (distinct
    // from the two @CellBase ones above — this one runs for every hand-rolled
    // cell, not just @CellBase Api interfaces) stays a warning: the ticket only
    // promotes the two @CellBase paths. A bare `FanInlet<*>` makes the port's Api
    // type argument a star projection, so the port is skipped — no descriptor row
    // — but compilation still succeeds.
    @Test
    fun `scanPorts unresolvable Api type still warns and drops the port's descriptor row`() {
        val (compilation, result) = compileKeepingSources(
            portStubs, portClassStubs, graphStubs,
            """
            package example
            import civictech.cell.Cell
            import civictech.cell.port.FanInlet

            class StarCell : Cell {
                val starInlet: FanInlet<*> = FanInlet<Any>()
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("StarCell.starInlet: unresolvable Api type — skipped"),
            result.messages,
        )
        val table = generatedSource(compilation, "ContractTable_")
        assertTrue("starInlet" !in table, table) // the port descriptor row was skipped
        assertTrue("example.StarCell" in table, table) // the cell row itself is still emitted
    }

    // T09 §B: the three protocol-contract errors (spec 12) were already
    // `logger.error`, but ContractProcessorTest asserted none of them.
    @Test
    fun `protocol contract must be management-class`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Protocol
            import civictech.nature.ProtocolDirection

            @Contract
            @Protocol("not-mgmt", ProtocolDirection.UPSTREAM, band = 0)
            interface NotManagementProtocol {
                fun push(id: String)
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("protocol contract example.NotManagementProtocol must be management-class (spec 12)"),
            result.messages,
        )
    }

    @Test
    fun `protocol contract methods must be push-only`() {
        val result = compile(
            """
            package example
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Protocol
            import civictech.nature.ProtocolDirection

            @Contract(management = true)
            @Protocol("returns", ProtocolDirection.UPSTREAM, band = 0)
            interface ReturnsProtocol {
                fun query(id: String): String
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("protocol contract example.ReturnsProtocol#query must be push-only"),
            result.messages,
        )
    }

    @Test
    fun `protocol contract methods must not carry Owned or Leased`() {
        val result = compile(
            """
            package civictech.cell
            class Owned<T>
            """.trimIndent(),
            """
            package example
            import civictech.cell.Owned
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Key
            import civictech.gen.wire.Protocol
            import civictech.nature.ProtocolDirection

            @Contract(management = true)
            @Protocol("owned", ProtocolDirection.UPSTREAM, band = 0)
            interface OwnedProtocol {
                fun push(@Key payload: Owned<String>)
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("protocol contract example.OwnedProtocol#push must not carry Owned/Leased"),
            result.messages,
        )
    }

    // computenet-yzsc (found reviewing computenet-ulss): the 93 I-6/I-8 property walk
    // in `carriesExclusive` must stop at `Borrowed`/`Frozen`, exactly as
    // `Proxy.discharge`'s `is Borrowed<*>, is Frozen<*> -> Unit` branch does. Both are
    // non-consuming, fan-out-safe views (spec 23 §Taps, `Ownership.kt` "Fan-out safe"),
    // so a tap port declared over one must not be marked exclusive — the bit drives the
    // link handshake's SPSC rule, `Shadow.suppressionProxy`'s choice of
    // `Proxy.discharging`, and ADMIT accounting.
    //
    // The test pins both directions so neither can regress: false for the two view-wrapped
    // parameters, and still true for the nested exclusive the widening was written for.
    @Test
    fun `exclusive bit stops at Borrowed and Frozen but still reaches a nested Owned`() {
        val (compilation, result) = compileKeepingSources(
            """
            package civictech.cell
            class Owned<T : Any>(private val value: T)
            class Borrowed<T : Any>(val value: T)
            class Frozen<T : Any>(val value: T)
            """.trimIndent(),
            """
            package example
            import civictech.cell.Borrowed
            import civictech.cell.Frozen
            import civictech.cell.Owned
            import civictech.gen.wire.Contract
            import civictech.gen.wire.Key

            class OwnedEnvelope(val label: String, val payload: Owned<String>)

            @Contract
            interface TapContract {
                fun tapBorrowed(@Key view: Borrowed<OwnedEnvelope>)
                fun tapFrozen(@Key view: Frozen<OwnedEnvelope>)
                fun pushNested(@Key envelope: OwnedEnvelope)
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val table = generatedSource(compilation, "ContractTable_").replace(Regex("\\s+"), " ")
        assertEquals(false, exclusiveBitOf(table, "tapBorrowed"), table)
        assertEquals(false, exclusiveBitOf(table, "tapFrozen"), table)
        assertEquals(true, exclusiveBitOf(table, "pushNested"), table)
    }

    /** Reads one `MethodDescriptor` row's `exclusive` flag out of a whitespace-normalised table. */
    private fun exclusiveBitOf(normalisedTable: String, methodName: String): Boolean {
        val row = Regex("""name = "$methodName",.*?exclusive = (true|false)""")
            .find(normalisedTable)
            ?: error("no MethodDescriptor row for '$methodName' in:\n$normalisedTable")
        return row.groupValues[1].toBoolean()
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

    private fun compile(vararg source: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = source.mapIndexed { index, text -> SourceFile.kotlin("Source$index.kt", text) }
            inheritClassPath = true
            messageOutputStream = System.out
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ContractProcessorProvider()
            }
        }.compile()
}
