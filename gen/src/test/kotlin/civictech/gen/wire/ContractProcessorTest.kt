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
