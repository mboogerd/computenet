package civictech.loader

import civictech.cell.CellRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Task computenet-051.5.3: `ModuleHandle.cellFactory(fqn)`, against the
 * `valid-basic` fixture — `[JAR1-SPAWN-01]` (resolve and construct through the
 * module's own classloader) and `[JAR1-SPAWN-05]` (refuse an fqn absent from
 * the module's declared `CellDescriptor` table, with no `Class.forName`
 * fallback).
 *
 * Registries are process-global, so every test that registers goes through
 * [FixtureJars.withLoadedModule], which unregisters and closes in a `finally`.
 */
class ModuleCellFactoryTest {

    private companion object {
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"
    }

    // ------------------------------------------------------------------
    // [JAR1-SPAWN-01] — resolves and constructs through the module's loader
    // ------------------------------------------------------------------

    @Test
    fun `cellFactory for the declared fqn constructs through the module's own classloader with the given ref`() {
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { handle ->
            val factory = handle.cellFactory(GREETING_CELL)

            val ref = CellRef(UUID.randomUUID())
            val cell = factory.create(ref)

            withClue("the constructed cell's class must have loaded through the module's own classloader") {
                cell.javaClass.classLoader shouldBe handle.classLoader
            }
            withClue("the factory must construct with the ref it was given") {
                cell.ref shouldBe ref
            }
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-SPAWN-05] — declared-table gate, no Class.forName fallback
    // ------------------------------------------------------------------

    @Test
    fun `cellFactory for an fqn absent from the module's declared cells is refused, naming the fqn`() {
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { handle ->
            val undeclared = "com.example.NotACell"
            val failure = shouldThrow<UndeclaredCellException> { handle.cellFactory(undeclared) }

            withClue("the diagnostic must name the fqn asked for") {
                failure.message!! shouldContain undeclared
            }
            failure.fqn shouldBe undeclared
            failure.id shouldBe handle.id
        }
    }

    @Test
    fun `cellFactory refuses a host-resolvable fqn the module never declared, without falling back`() {
        // civictech.cell.data.SetCell is a real class the HOST's own classloader can
        // resolve — a fallback to raw Class.forName would silently succeed here. The
        // gate must be against the module's OWN declared table, never against what a
        // loader happens to be able to resolve.
        val hostResolvable = "civictech.cell.data.SetCell"
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { handle ->
            val failure = shouldThrow<UndeclaredCellException> { handle.cellFactory(hostResolvable) }
            failure.fqn shouldBe hostResolvable
        }
    }

    // ------------------------------------------------------------------
    // Only legal while REGISTERED
    // ------------------------------------------------------------------

    @Test
    fun `cellFactory from an unloaded handle is refused`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        loader.unload(handle)

        withClue("unload must have taken the handle out of REGISTERED") {
            handle.state shouldBe ModuleState.CLOSED
        }
        shouldThrow<IllegalStateException> { handle.cellFactory(GREETING_CELL) }
    }
}
