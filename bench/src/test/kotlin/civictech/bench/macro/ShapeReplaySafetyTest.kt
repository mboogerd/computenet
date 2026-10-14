package civictech.bench.macro

import civictech.bench.macro.shape.KNOWN_FAILING_SEEDS
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeFactory
import civictech.bench.macro.shape.ShapeSpec
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class ShapeReplaySafetyTest {

    @ParameterizedTest
    @EnumSource(Shape::class)
    fun `every generated shape round trips and applies identically to independent hosts`(shape: Shape) {
        val generated = ShapeSpec(
            shape = shape,
            seed = 7L,
            cardinality = if (shape == Shape.DIAMOND) 4 else 8,
            fanOut = 3,
            hostCount = 2,
        ).generate()
        val original = generated.spec
        val spawns = original.steps.filterIsInstance<SpawnStep>()

        assertTrue(spawns.all { it.factory is ShapeFactory })
        spawns.forEach { spawn ->
            assertFalse(
                spawn.factory.toString().contains("@"),
                "${spawn.handle} factory must not carry an identity-bearing live object",
            )
        }

        val revived = roundTrip(original)
        assertEquals(original, revived)
        assertEquals(original.steps.size, revived.steps.size)

        val expectedHandles = spawns.map(SpawnStep::handle).toSet()
        assertTrue((generated.sources + generated.sinks).all { it in expectedHandles })

        val first = original.applyTo(freshHost(1L).managementInlet)
        val second = original.applyTo(freshHost(2L).managementInlet)
        assertEquals(first, second)
        assertEquals(expectedHandles, first.keys)
        assertTrue((generated.sources + generated.sinks).all { it in first.keys })

        val replayed = revived.applyTo(freshHost(3L).managementInlet)
        assertEquals(first, replayed)
    }

    /**
     * BS-6/[BEN2-12] is intentionally vacuous while [KNOWN_FAILING_SEEDS] is
     * empty. Once a failure is discovered, its seed must remain recorded rather
     * than being replaced with a friendlier seed, as required by AGENTS.md.
     */
    @Test
    fun `every KNOWN_FAILING_SEEDS entry still generates (vacuous while empty)`() {
        KNOWN_FAILING_SEEDS.forEach { (shape, seeds) ->
            seeds.forEach { seed ->
                assertDoesNotThrow {
                    ShapeSpec(shape, seed, cardinality = 8, fanOut = 3, hostCount = 2).generate()
                }
            }
        }
        assertTrue(KNOWN_FAILING_SEEDS.isEmpty(), "the empty seed map makes this check vacuous")
    }

    private fun freshHost(seed: Long): ManagedHost {
        val controller = SimulationController(seed)
        return ManagedHost(scheduler = controller.scheduler())
    }

    private fun roundTrip(spec: GraphSpec): GraphSpec {
        val bytes = ByteArrayOutputStream()
            .also { output -> ObjectOutputStream(output).use { it.writeObject(spec) } }
            .toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as GraphSpec }
    }
}
