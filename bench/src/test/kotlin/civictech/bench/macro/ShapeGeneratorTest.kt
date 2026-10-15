package civictech.bench.macro

import civictech.bench.macro.shape.PREDICATES
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeFactory
import civictech.bench.macro.shape.ShapeSpec
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.charset.StandardCharsets
import java.util.UUID

class ShapeGeneratorTest {
    @ParameterizedTest
    @EnumSource(Shape::class)
    fun `every seeded shape emits a complete spawn and connect graph`(shape: Shape) {
        val generated = ShapeSpec(shape, seed = 7L, cardinality = 8, fanOut = 3, hostCount = 2).generate()
        val spawns = generated.spec.steps.filterIsInstance<SpawnStep>()
        val connects = generated.spec.steps.filterIsInstance<ConnectStep>()

        assertEquals(8, spawns.size)
        assertEquals(8 + connects.size, generated.spec.steps.size)
        assertTrue(generated.spec.steps.all { it is SpawnStep || it is ConnectStep })
        assertEquals(8, spawns.map { it.handle }.toSet().size)
        assertTrue(generated.sources.isNotEmpty())
        assertTrue(generated.sinks.isNotEmpty())
        assertTrue(generated.sources.all { it in spawns.map(SpawnStep::handle) })
        assertTrue(generated.sinks.all { it in spawns.map(SpawnStep::handle) })

        val incoming = connects.groupingBy(ConnectStep::to).eachCount()
        val outgoing = connects.groupingBy(ConnectStep::from).eachCount()
        spawns.forEach { spawn ->
            assertTrue(spawn.factory is ShapeFactory)
            assertEquals(null, spawn.family)
            assertTrue(spawn.inputs.isEmpty())
            assertEquals(civictech.cell.replication.WriteAuthority.Open, spawn.authority)
            assertFalse(spawn.replicated)
            assertEquals(null, spawn.journalId)
            assertFalse(spawn.shadow)
            assertEquals(null, spawn.parent)
            assertTrue(spawn.handle.startsWith("s7-"))
            assertEquals(
                IdentityBinding.Exact(expectedRef(spawn.handle)),
                spawn.identity,
            )
            assertTrue(spawn.placement!!.matches(Regex("host[0-1]")))

            val inDegree = incoming[spawn.handle] ?: 0
            val cell = (spawn.factory as ShapeFactory).create(expectedRef(spawn.handle))
            when {
                shape == Shape.DIAMOND && spawn.handle == generated.sinks.single() ->
                    assertInstanceOf(IntersectSetCell::class.java, cell)
                inDegree == 0 -> assertInstanceOf(SetCell::class.java, cell)
                inDegree == 1 -> assertInstanceOf(FilterCell::class.java, cell)
                else -> assertInstanceOf(UnionSetCell::class.java, cell)
            }
            assertTrue((outgoing[spawn.handle] ?: 0) >= 0)
        }

        assertEquals(
            spawns.filter { (incoming[it.handle] ?: 0) == 0 }.map(SpawnStep::handle),
            generated.sources,
        )
        assertEquals(
            spawns.filter { (outgoing[it.handle] ?: 0) == 0 }.map(SpawnStep::handle),
            generated.sinks,
        )
        connects.forEach { connect ->
            assertEquals("outlet", connect.outlet)
            assertEquals(
                if (shape == Shape.DIAMOND && connect.to == generated.sinks.single()) {
                    connect.inlet
                } else {
                    "inlet"
                },
                connect.inlet,
            )
        }
    }

    @Test
    fun `chain and the two tree variants use their prescribed edge structures`() {
        val chain = generatedEdges(Shape.CHAIN, 8, 3)
        assertEquals((0 until 7).map { it to it + 1 }, chain)

        val fanOut = generatedEdges(Shape.FAN_OUT_TREE, 21, 4)
        assertEquals((1..20).map { it to (it - 1) / 4 }.map { it.second to it.first }, fanOut)

        val fanIn = generatedEdges(Shape.FAN_IN_LATTICE, 21, 4)
        assertEquals(fanOut.map { it.second to it.first }, fanIn)
    }

    @Test
    fun `diamond has two arms and the sink has one left and one right inlet`() {
        val generated = ShapeSpec(Shape.DIAMOND, 11L, 4, 3, 1).generate()
        val spawns = generated.spec.steps.filterIsInstance<SpawnStep>()
        val connects = generated.spec.steps.filterIsInstance<ConnectStep>()
        val handles = spawns.map(SpawnStep::handle)

        assertEquals(
            listOf(
                handles[0] to handles[1],
                handles[0] to handles[2],
                handles[1] to handles[3],
                handles[2] to handles[3],
            ),
            connects.map { it.from to it.to },
        )
        assertEquals(listOf("left", "right"), connects.filter { it.to == handles[3] }.map(ConnectStep::inlet))
        assertEquals("s11-source0", handles[0])
        assertEquals("s11-sink0", handles[3])
    }

    @Test
    fun `fan out and fan in have the expected source and sink counts`() {
        val fanOut = ShapeSpec(Shape.FAN_OUT_TREE, 3L, 21, 4, 1).generate()
        assertEquals(1, fanOut.sources.size)
        assertEquals(16, fanOut.sinks.size)

        val fanIn = ShapeSpec(Shape.FAN_IN_LATTICE, 3L, 21, 4, 1).generate()
        assertEquals(16, fanIn.sources.size)
        assertEquals(1, fanIn.sinks.size)
        val incoming = fanIn.spec.steps.filterIsInstance<ConnectStep>().groupingBy(ConnectStep::to).eachCount()
        assertEquals(5, incoming.values.count { it == 4 })
    }

    @Test
    fun `random dag is bounded, topological, deterministic, and seed-sensitive`() {
        val first = ShapeSpec(Shape.RANDOM_DAG, 19L, 21, 4, 1).generate()
        val same = ShapeSpec(Shape.RANDOM_DAG, 19L, 21, 4, 1).generate()
        val other = ShapeSpec(Shape.RANDOM_DAG, 20L, 21, 4, 1).generate()
        assertEquals(first, same)
        assertNotEquals(
            first.spec.steps.filterIsInstance<ConnectStep>().toSet(),
            other.spec.steps.filterIsInstance<ConnectStep>().toSet(),
        )

        val index = first.spec.steps.filterIsInstance<SpawnStep>().mapIndexed { i, spawn -> spawn.handle to i }.toMap()
        val outgoing = first.spec.steps.filterIsInstance<ConnectStep>().groupingBy(ConnectStep::from).eachCount()
        first.spec.steps.filterIsInstance<ConnectStep>().forEach { edge ->
            assertTrue(index.getValue(edge.from) < index.getValue(edge.to))
            assertTrue(outgoing.getValue(edge.from) <= 4)
        }
    }

    @Test
    fun `host count only changes placement and one host uses host0`() {
        val one = ShapeSpec(Shape.RANDOM_DAG, 31L, 8, 3, 1).generate()
        val three = ShapeSpec(Shape.RANDOM_DAG, 31L, 8, 3, 3).generate()
        val oneSpawns = one.spec.steps.filterIsInstance<SpawnStep>()
        val threeSpawns = three.spec.steps.filterIsInstance<SpawnStep>()
        assertTrue(oneSpawns.all { it.placement == "host0" })
        assertEquals(
            one.spec.steps.filterIsInstance<ConnectStep>(),
            three.spec.steps.filterIsInstance<ConnectStep>(),
        )
        assertEquals(
            oneSpawns.map { it.copy(placement = null) },
            threeSpawns.map { it.copy(placement = null) },
        )
    }

    @Test
    fun `predicates have enough coverage`() {
        assertTrue(PREDICATES.size >= 3)
        PREDICATES.forEach { predicate ->
            assertTrue((0L until 1000L).count(predicate) >= 500)
        }
    }

    @Test
    fun `invalid shape parameters name their offending fields`() {
        val cardinality = assertThrows(IllegalArgumentException::class.java) {
            ShapeSpec(Shape.CHAIN, 1L, 1, 1, 1)
        }
        assertTrue(cardinality.message!!.contains("cardinality"))
        val diamond = assertThrows(IllegalArgumentException::class.java) {
            ShapeSpec(Shape.DIAMOND, 1L, 3, 1, 1)
        }
        assertTrue(diamond.message!!.contains("cardinality"))
        val fanOut = assertThrows(IllegalArgumentException::class.java) {
            ShapeSpec(Shape.CHAIN, 1L, 2, 0, 1)
        }
        assertTrue(fanOut.message!!.contains("fanOut"))
        val hosts = assertThrows(IllegalArgumentException::class.java) {
            ShapeSpec(Shape.CHAIN, 1L, 2, 1, 0)
        }
        assertTrue(hosts.message!!.contains("hostCount"))
    }

    @Test
    fun `handles carry the seed prefix and the role names of the design examples`() {
        fun handles(shape: Shape, seed: Long, cardinality: Int, fanOut: Int): List<String> =
            ShapeSpec(shape, seed, cardinality, fanOut, 2).generate()
                .spec.steps.filterIsInstance<SpawnStep>().map(SpawnStep::handle)

        // Literal expectations from the feature design's examples (ryb6q-D9/D10).
        assertEquals(
            listOf("s7-source0", "s7-n1", "s7-n2", "s7-n3", "s7-sink0"),
            handles(Shape.CHAIN, 7L, 5, 1),
        )
        assertEquals(
            listOf("s11-source0", "s11-n1", "s11-n2", "s11-sink0"),
            handles(Shape.DIAMOND, 11L, 4, 3),
        )
        assertEquals(
            listOf("s3-source0") + (1..4).map { "s3-n$it" } + (0..15).map { "s3-sink$it" },
            handles(Shape.FAN_OUT_TREE, 3L, 21, 4),
        )
        assertEquals(
            listOf("s3-sink0") + (1..4).map { "s3-n$it" } + (0..15).map { "s3-source$it" },
            handles(Shape.FAN_IN_LATTICE, 3L, 21, 4),
        )
    }

    @ParameterizedTest
    @EnumSource(Shape::class)
    fun `every handle is named for its degree role and generation is repeatable`(shape: Shape) {
        val spec = ShapeSpec(shape, seed = 19L, cardinality = 21, fanOut = 4, hostCount = 2)
        val generated = spec.generate()
        assertEquals(generated, spec.generate())

        val connects = generated.spec.steps.filterIsInstance<ConnectStep>()
        val incoming = connects.groupingBy(ConnectStep::to).eachCount()
        val outgoing = connects.groupingBy(ConnectStep::from).eachCount()
        generated.spec.steps.filterIsInstance<SpawnStep>().forEachIndexed { node, spawn ->
            val role = when {
                (incoming[spawn.handle] ?: 0) == 0 -> Regex("s19-source\\d+")
                (outgoing[spawn.handle] ?: 0) == 0 -> Regex("s19-sink\\d+")
                else -> Regex("s19-n$node")
            }
            assertTrue(spawn.handle.matches(role), "${spawn.handle} at node $node should match $role")
        }
    }

    @ParameterizedTest
    @EnumSource(Shape::class, names = ["RANDOM_DAG"], mode = EnumSource.Mode.EXCLUDE)
    fun `only the random dag lets the seed change the edge structure`(shape: Shape) {
        fun structure(seed: Long): List<List<String>> =
            ShapeSpec(shape, seed, cardinality = 21, fanOut = 4, hostCount = 2).generate()
                .spec.steps.filterIsInstance<ConnectStep>().map { connect ->
                    listOf(
                        connect.from.removePrefix("s$seed-"),
                        connect.outlet,
                        connect.to.removePrefix("s$seed-"),
                        connect.inlet,
                    )
                }

        assertEquals(structure(7L), structure(8L))
    }

    private fun generatedEdges(shape: Shape, cardinality: Int, fanOut: Int): List<Pair<Int, Int>> {
        val generated = ShapeSpec(shape, 5L, cardinality, fanOut, 1).generate()
        val index = generated.spec.steps.filterIsInstance<SpawnStep>().mapIndexed { i, spawn -> spawn.handle to i }.toMap()
        return generated.spec.steps.filterIsInstance<ConnectStep>().map { index.getValue(it.from) to index.getValue(it.to) }
    }

    private fun expectedRef(handle: String): CellRef =
        CellRef(UUID.nameUUIDFromBytes(handle.toByteArray(StandardCharsets.UTF_8)), 0L)
}
