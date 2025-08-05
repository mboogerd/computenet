package civictech.kernel.boot

import civictech.kernel.boot.Handle
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HandleTest {

    interface Api {
        fun value(): String

        fun derive(block: (String) -> String): Api = object : Api {
            override fun value(): String = block(this@Api.value())
        }
    }

    private class SimpleApi(private val v: String) : Api {
        override fun value(): String = v
    }

    @Test
    fun `root handle acquires current API`() {
        val api = SimpleApi("root")
        val root = Handle.Companion.root(api)
        assertEquals("root", root.acquire().value())
    }

    @Test
    fun `derived handle without modification reflects root updates`() {
        val root = Handle.Companion.root(SimpleApi("initial"))
        val branch = root.derive()

        assertEquals("initial", branch.acquire().value())

        root.activate(SimpleApi("updated"))
        assertEquals("updated", branch.acquire().value())
    }

    @Test
    fun `derived handle with decorator wraps behavior`() {
        val root = Handle.Companion.root<Api>(SimpleApi("base"))
        val branch = root.deriveWith { api -> api.derive { "decorated($it)" } }

        assertEquals("decorated(base)", branch.acquire().value())
    }

    @Test
    fun `reattaching handle updates its origin`() {
        val root1 = Handle.Companion.root(SimpleApi("root1"))
        val root2 = Handle.Companion.root(SimpleApi("root2"))
        val branch = root1.derive()

        assertEquals("root1", branch.acquire().value())
        branch.reattach(root2)
        assertEquals("root2", branch.acquire().value())
    }

    @Test
    fun `detached handle becomes independent`() {
        val root = Handle.Companion.root(SimpleApi("root"))
        val branch = root.derive()
        branch.detach(SimpleApi("branchRoot"))

        // Branch no longer tracks root
        root.activate(SimpleApi("rootUpdated"))
        assertEquals("branchRoot", branch.acquire().value())
    }

    @Test
    fun `activate on non-root throws exception`() {
        val root = Handle.Companion.root(SimpleApi("root"))
        val branch = root.derive()

        Assertions.assertThrows(IllegalStateException::class.java) {
            branch.activate(SimpleApi("illegal"))
        }
    }

    @Test
    fun `staleness propagates to downstream`() {
        val root = Handle.Companion.root(SimpleApi("root"))
        val branch = root.derive()
        val grandchild = branch.derive()

        root.activate(SimpleApi("newRoot"))
        // grandchild should rebuild lazily but reflect the new value
        assertEquals("newRoot", grandchild.acquire().value())
    }


    @Test
    fun `wrapper is reapplied after stale`() {
        val root = Handle.Companion.root<Api>(SimpleApi("root"))
        val branch = root.deriveWith { api -> api.derive { "wrapped($it)" } }

        assertEquals("wrapped(root)", branch.acquire().value())
        root.activate(SimpleApi("updated"))
        // wrapper should still apply to the new root value
        assertEquals("wrapped(updated)", branch.acquire().value())
    }

    @Test
    fun `multi level derivation rebuilds through all layers`() {
        val root = Handle.Companion.root<Api>(SimpleApi("root"))
        val branch1 = root.deriveWith { api -> api.derive { "b1($it)" } }
        val branch2 = branch1.deriveWith { api -> api.derive { "b2($it)" } }

        assertEquals("b2(b1(root))", branch2.acquire().value())
        root.activate(SimpleApi("newRoot"))
        assertEquals("b2(b1(newRoot))", branch2.acquire().value())
    }

    @Test
    fun `unmodified derivation preserves API instance identity`() {
        val rootApi = SimpleApi("root")
        val root = Handle.Companion.root(rootApi)

        val branch1 = root.derive()
        val branch2 = branch1.derive()

        // All should resolve to the exact same object
        Assertions.assertSame(rootApi, branch1.acquire())
        Assertions.assertSame(rootApi, branch2.acquire())
    }

    @Test
    fun `decorate replaces wrapper and rebuilds`() {
        val root = Handle.root<Api>(SimpleApi("root"))
        val branch = root.deriveWith { it }

        // Initially, just passes through
        assertEquals("root", branch.acquire().value())

        // Apply a new decoration
        branch.decorate { api -> api.derive { "decorated($it)" } }

        // After decoration, rebuilt with new logic
        assertEquals("decorated(root)", branch.acquire().value())
    }

    @Test
    fun `decorate can stack multiple decorations`() {
        val root = Handle.root<Api>(SimpleApi("root"))
        val branch = root.deriveWith { api -> api.derive { "d1($it)" } }

        assertEquals("d1(root)", branch.acquire().value())

        // Add a second decoration
        branch.decorate { api -> api.derive { "d2(${api.value()})" } }

        // Expect new decoration to override previous wrapper completely
        assertEquals("d2(root)", branch.acquire().value())
    }

    @Test
    fun `decorate invalidates and reapplies logic after root update`() {
        val root = Handle.root<Api>(SimpleApi("root"))
        val branch = root.deriveWith { api -> api.derive { "wrapped($it)" } }

        assertEquals("wrapped(root)", branch.acquire().value())

        // Change decoration
        branch.decorate { api -> api.derive { "newWrap($it)" } }

        // Change root to trigger rebuild
        root.activate(SimpleApi("updated"))
        assertEquals("newWrap(updated)", branch.acquire().value())
    }
}