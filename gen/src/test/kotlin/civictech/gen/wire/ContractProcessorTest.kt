package civictech.gen.wire

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
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
