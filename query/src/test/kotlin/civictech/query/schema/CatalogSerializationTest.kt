package civictech.query.schema

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * `[QRY1-LANG-06]` (schema half): the schema catalog round-trips through
 * `ObjectOutputStream`/`ObjectInputStream` to an object equal to the original. The example
 * is the one the feature bead names: `Catalog(mapOf("r" to RelationSchema(attrs=[a:INT,
 * b:STRING], rowKey={a})))`.
 */
class CatalogSerializationTest {

    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(value) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
    }

    @Test
    fun `a catalog with a keyed relation round-trips equal to the original`() {
        val catalog = Catalog(
            mapOf(
                "r" to RelationSchema(
                    attributes = listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.STRING)),
                    rowKey = setOf("a"),
                ),
            ),
        )

        roundTrip(catalog) shouldBe catalog
    }

    @Test
    fun `a catalog with an unkeyed relation round-trips equal to the original, key still absent`() {
        val catalog = Catalog(
            mapOf(
                "r" to RelationSchema(
                    attributes = listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.STRING)),
                ),
            ),
        )

        val result = roundTrip(catalog)
        result shouldBe catalog
        result.relations.getValue("r").rowKey shouldBe null
    }

    @Test
    fun `a catalog spanning multiple relations and all AttrType members round-trips`() {
        val catalog = Catalog(
            mapOf(
                "ints" to RelationSchema(listOf(Attribute("i", AttrType.INT), Attribute("l", AttrType.LONG))),
                "reals" to RelationSchema(listOf(Attribute("d", AttrType.DOUBLE))),
                "text" to RelationSchema(listOf(Attribute("s", AttrType.STRING), Attribute("b", AttrType.BOOL))),
            ),
        )

        roundTrip(catalog) shouldBe catalog
    }
}
