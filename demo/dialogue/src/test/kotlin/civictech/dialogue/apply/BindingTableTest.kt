package civictech.dialogue.apply

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.graph.ApplyContext
import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import civictech.testkit.SimWorld
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BindingTable] is a read-only projection of dialogue handles in the topology fold. */
class BindingTableTest {

    private class Rig {
        val world = SimWorld(seed = 1L)
        val context = ApplyContext(world.host)
        val service = AgoraService(world.host, world.registry, context = context)
        val table = BindingTable(context)

        fun create(key: ClaimKey, text: String = key.value): CellRef =
            service.createClaim(text, BindingTable.refFor(key), handle = BindingTable.handleFor(key))
    }

    @Test
    fun `refFor is a pure deterministic function of the key, distinct per key and per namespace`() {
        val k1 = ClaimKey("alice thinks the sky is blue")
        val k2 = ClaimKey("bob thinks the sky is green")

        assertEquals(BindingTable.refFor(k1), BindingTable.refFor(k1), "same key, same ref, called twice")
        assertNotEquals(BindingTable.refFor(k1), BindingTable.refFor(k2), "distinct claim keys map to distinct refs")
        assertNotEquals(
            BindingTable.refFor(k1),
            BindingTable.refFor(RelationKey(k1.value)),
            "claim and relation namespaces are disjoint",
        )
    }

    @Test
    fun `live dialogue spawns define claim and relation bindings including the reverse lookup`() {
        val rig = Rig()
        val source = ClaimKey("source")
        val target = ClaimKey("target")
        val relation = RelationKey("source-supports-target")
        val sourceRef = rig.create(source)
        val targetRef = rig.create(target)
        val relationRef = rig.service.createEdge(
            sourceRef,
            targetRef,
            Polarity.SUPPORT,
            BindingTable.refFor(relation),
            handle = BindingTable.handleFor(relation),
        )
        rig.world.runToIdle()

        assertEquals(setOf(source, target), rig.table.boundClaims())
        assertEquals(setOf(relation), rig.table.boundRelations(), "relation-prefixed live spawns are included")
        assertTrue(rig.table.isBound(source))
        assertTrue(rig.table.isBound(relation))
        assertEquals(sourceRef, rig.table.refOf(source))
        assertEquals(relationRef, rig.table.refOf(relation))
        assertEquals(BoundKey.OfClaim(source), rig.table.keyOf(sourceRef))
        assertEquals(BoundKey.OfRelation(relation), rig.table.keyOf(relationRef))
    }

    @Test
    fun `despawn removes the binding from every read surface`() {
        val rig = Rig()
        val key = ClaimKey("gone")
        val ref = rig.create(key)
        assertTrue(rig.table.isBound(key), "create made the dialogue handle live")

        rig.service.remove(ref)
        rig.world.runToIdle()

        assertFalse(rig.table.isBound(key))
        assertNull(rig.table.refOf(key))
        assertNull(rig.table.keyOf(ref))
        assertEquals(emptySet(), rig.table.boundClaims())
    }

    @Test
    fun `a non-dialogue handle is not a binding even when its spawn ref is deterministic`() {
        val rig = Rig()
        val key = ClaimKey("foreign")
        val ref = BindingTable.refFor(key)
        rig.service.createClaim(key.value, ref, handle = "claim:${UUID.randomUUID()}")

        assertFalse(rig.table.isBound(key))
        assertNull(rig.table.refOf(key))
        assertNull(rig.table.keyOf(ref))
        assertEquals(emptySet(), rig.table.boundClaims())
    }
}
