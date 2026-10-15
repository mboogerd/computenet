package civictech.concord.driver.kernel

import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.s
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/** Binding tests for the deliberate `43-security` Concord schema extension. */
class WriteAuthorityBindingTest {
    private val budget = 5_000_000

    private fun authority(principal: String): Value = Value.MapVal(
        mapOf("principal" to Value.StrVal(principal)),
    )

    private fun mesh(): KernelDriver = KernelDriver(0L).apply {
        createHost("h1")
        createHost("h2")
        val params = mapOf(
            "replica-of" to Value.StrVal("slice"),
            "authority" to authority("alice"),
        )
        spawn("h1", "r1", "set-source", params)
        spawn("h2", "r2", "set-source", params)
    }

    @Test
    fun `authority admits its author and refuses another author with attributable accounting`() {
        val d = mesh()
        d.signedApply("r1", "alice", "add", s("x"))
        d.signedApply("r2", "bob", "add", s("y"))
        d.quiesce(budget)

        d.readView("r1") shouldBe list(s("x"))
        d.readView("r2") shouldBe list(s("x"))
        d.writeDenials("r1") shouldBe 0L
        d.writeDenials("r2") shouldBe 1L
        d.writeDenials("r2", "bob") shouldBe 1L
    }

    @Test
    fun `transfer admits the new principal and refuses the former principal`() {
        val d = mesh()
        d.signedApply("r1", "alice", "add", s("x"))
        d.transferAuthority("r1", "alice", "bob")
        d.quiesce(budget)
        d.signedApply("r2", "bob", "add", s("z"))
        d.signedApply("r1", "alice", "add", s("w"))
        d.quiesce(budget)

        d.readView("r1") shouldBe list(s("x"), s("z"))
        d.readView("r2") shouldBe list(s("x"), s("z"))
        d.writeDenials("r1", "alice") shouldBe 1L
    }

    @Test
    fun `signed remove reads the live tags and converges the tombstone`() {
        val d = mesh()
        d.signedApply("r1", "alice", "add", s("x"))
        d.quiesce(budget)
        d.signedApply("r2", "alice", "remove", s("x"))
        d.quiesce(budget)

        d.readView("r1") shouldBe list()
        d.readView("r2") shouldBe list()
        d.writeDenials("r1") shouldBe 0L
        d.writeDenials("r2") shouldBe 0L
    }

    @Test
    fun `forged signature and retained replay are denied without changing converged views`() {
        val d = mesh()
        d.signedApply("r1", "alice", "add", s("x"))
        d.quiesce(budget)

        d.forgeSignedApply("r1", "alice", "add", s("forged"))
        d.replaySignedApply("r1", "alice", "add", s("x"))
        d.quiesce(budget)

        d.readView("r1") shouldBe list(s("x"))
        d.readView("r2") shouldBe list(s("x"))
        d.writeDenials("r1") shouldBe 2L
        d.writeDenials("r1", "alice") shouldBe 2L
        d.writeDenials("r2") shouldBe 0L
    }

    @Test
    fun `replay refuses loudly when no matching signed apply was recorded`() {
        val d = mesh()

        assertThrows<UnsupportedCatalogBinding> {
            d.replaySignedApply("r1", "alice", "add", s("x"))
        }.message!! shouldContain "no prior matching signed-apply"
    }

    @Test
    fun `write-denials and signed verbs refuse loudly without an authority adapter`() {
        val d = KernelDriver(0L).apply {
            createHost("h1")
            spawn("h1", "r1", "set-source", mapOf("replica-of" to Value.StrVal("slice")))
        }

        assertThrows<UnsupportedCatalogBinding> { d.writeDenials("r1") }
            .message!! shouldContain "pass vacuously"
        assertThrows<UnsupportedCatalogBinding> { d.signedApply("r1", "alice", "add", s("x")) }
            .message!! shouldContain "no write-authority adapter"
        assertThrows<UnsupportedCatalogBinding> { d.forgeSignedApply("r1", "alice", "add", s("x")) }
            .message!! shouldContain "no write-authority adapter"
        assertThrows<UnsupportedCatalogBinding> { d.replaySignedApply("r1", "alice", "add", s("x")) }
            .message!! shouldContain "no write-authority adapter"
    }
}
