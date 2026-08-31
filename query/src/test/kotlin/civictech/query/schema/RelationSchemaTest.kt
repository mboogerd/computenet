package civictech.query.schema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `[QRY1-API-05]`: a relation without a declared row key must be representable, not an
 * error, and must be distinguishable from one with a declared key. `[QRY1-LANG-05]`: a
 * relation declares attribute names, types, and an optional row key.
 */
class RelationSchemaTest {

    private val attrs = listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.STRING))

    @Test
    fun `a relation with no declared row key constructs and reports no key`() {
        val schema = RelationSchema(attributes = attrs)
        schema.rowKey shouldBe null
        schema.hasRowKey shouldBe false
    }

    @Test
    fun `a relation with a declared row key constructs and reports it`() {
        val schema = RelationSchema(attributes = attrs, rowKey = setOf("a"))
        schema.rowKey shouldBe setOf("a")
        schema.hasRowKey shouldBe true
    }

    @Test
    fun `keyed and unkeyed schemas over the same attributes are distinguishable`() {
        val unkeyed = RelationSchema(attributes = attrs)
        val keyed = RelationSchema(attributes = attrs, rowKey = setOf("a"))
        unkeyed shouldBe unkeyed.copy()
        (unkeyed == keyed) shouldBe false
    }

    @Test
    fun `attributeNames reflects declaration order`() {
        RelationSchema(attributes = attrs).attributeNames shouldBe listOf("a", "b")
    }

    @Test
    fun `an empty row key is rejected in favour of null`() {
        shouldThrow<IllegalArgumentException> {
            RelationSchema(attributes = attrs, rowKey = emptySet())
        }
    }

    @Test
    fun `a row key naming an undeclared attribute is rejected`() {
        shouldThrow<IllegalArgumentException> {
            RelationSchema(attributes = attrs, rowKey = setOf("nope"))
        }
    }

    @Test
    fun `duplicate attribute names are rejected`() {
        shouldThrow<IllegalArgumentException> {
            RelationSchema(attributes = listOf(Attribute("a", AttrType.INT), Attribute("a", AttrType.LONG)))
        }
    }
}
