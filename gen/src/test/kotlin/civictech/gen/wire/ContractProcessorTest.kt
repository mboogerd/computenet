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
