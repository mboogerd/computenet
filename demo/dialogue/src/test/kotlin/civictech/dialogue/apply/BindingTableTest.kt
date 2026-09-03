package civictech.dialogue.apply

import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BindingTable]: deterministic refs, durability across re-instantiation,
 * and the ephemeral (`journalDir = null`) contract. Test names are the
 * bead's acceptance-criteria clauses (computenet-2aw.4.1).
 */
class BindingTableTest {

    @Test
    fun `refFor is a pure deterministic function of the key, distinct per key and per namespace`() {
        val k1 = ClaimKey("alice thinks the sky is blue")
        val k2 = ClaimKey("bob thinks the sky is green")

        // Two independent instances (fresh directories) agree on refFor
        // without ever talking to each other — proof it does not depend on
        // instance state, only on the key's string value.
        val tableA = BindingTable(journalDir = null)
        val tableB = BindingTable(journalDir = null)

        assertEquals(BindingTable.refFor(k1), BindingTable.refFor(k1), "same key, same ref, called twice")
        assertEquals(tableA.bind(k1), tableB.bind(k1), "same key binds to the same ref on independent instances")

        assertNotEquals(BindingTable.refFor(k1), BindingTable.refFor(k2), "distinct claim keys map to distinct refs")

        // A claim key and a relation key sharing the same string value map to
        // distinct refs: the namespaces (dialogue:claim: / dialogue:relation:)
        // are disjoint.
        val relationSameValue = RelationKey(k1.value)
        assertNotEquals(
            BindingTable.refFor(k1),
            BindingTable.refFor(relationSameValue),
            "a claim key and a relation key with the same string value map to distinct refs",
        )
    }

    @Test
    fun `bindings on a journalled table survive re-instantiation, including an unbind`() {
        val dir = createTempDirectory().toFile()
        try {
            val claim = ClaimKey("claim-k")
            val relation = RelationKey("relation-k")

            val first = BindingTable(journalDir = dir)
            val claimRef = first.bind(claim)
            val relationRef = first.bind(relation)

            val second = BindingTable(journalDir = dir)
            assertTrue(second.isBound(claim))
            assertTrue(second.isBound(relation))
            assertEquals(claimRef, second.refOf(claim), "reopened table reports the SAME ref for the claim key")
            assertEquals(relationRef, second.refOf(relation), "reopened table reports the SAME ref for the relation key")

            // Now unbind the claim on the first table before a third opens.
            first.unbind(claim)
            val third = BindingTable(journalDir = dir)
            assertFalse(third.isBound(claim), "the unbind record replays: the claim is unbound")
            assertTrue(third.isBound(relation), "the relation, never unbound, is still bound")
            assertEquals(relationRef, third.refOf(relation))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `journalDir null touches no file and still binds and unbinds in memory`() {
        val dir = createTempDirectory().toFile()
        try {
            val table = BindingTable(journalDir = null)
            val key = ClaimKey("ephemeral")

            val ref = table.bind(key)
            assertTrue(table.isBound(key))
            assertEquals(ref, table.refOf(key))

            table.unbind(key)
            assertFalse(table.isBound(key))
            assertNull(table.refOf(key))

            assertEquals(emptyList<File>(), dir.listFiles()?.toList() ?: emptyList(), "no file was ever created")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keyOf is the reverse of bind, and boundClaims-boundRelations report exactly what is bound`() {
        val table = BindingTable(journalDir = null)
        val claim = ClaimKey("c1")
        val relation = RelationKey("r1")

        val claimRef = table.bind(claim)
        val relationRef = table.bind(relation)

        assertEquals(BoundKey.OfClaim(claim), table.keyOf(claimRef))
        assertEquals(BoundKey.OfRelation(relation), table.keyOf(relationRef))
        assertEquals(setOf(claim), table.boundClaims())
        assertEquals(setOf(relation), table.boundRelations())

        table.unbind(claim)
        assertNull(table.keyOf(claimRef), "unbinding removes the reverse-index entry too")
        assertEquals(emptySet(), table.boundClaims())
        assertEquals(setOf(relation), table.boundRelations())
    }
}
