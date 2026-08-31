package civictech.query.ast

import civictech.query.schema.AttrType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `Term.Const`'s value/type pairing is validated at construction time. */
class TermTest {

    @Test
    fun `a Const whose value matches its declared type constructs`() {
        Term.Const(42, AttrType.INT).value shouldBe 42
        Term.Const(42L, AttrType.LONG).value shouldBe 42L
        Term.Const(1.5, AttrType.DOUBLE).value shouldBe 1.5
        Term.Const("s", AttrType.STRING).value shouldBe "s"
        Term.Const(true, AttrType.BOOL).value shouldBe true
    }

    @Test
    fun `a Const whose value does not match its declared type is rejected`() {
        shouldThrow<IllegalArgumentException> { Term.Const("not an int", AttrType.INT) }
        shouldThrow<IllegalArgumentException> { Term.Const(42, AttrType.LONG) }
        shouldThrow<IllegalArgumentException> { Term.Const(42L, AttrType.INT) }
    }

    @Test
    fun `two Vars with the same name are equal`() {
        Term.Var("X") shouldBe Term.Var("X")
    }
}
