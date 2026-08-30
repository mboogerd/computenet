package civictech.demo.allocatorobserve.declaration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllocationDeclarationParserTest {

    @Test
    fun `valid declaration yields project weights cap and window`() {
        val result = parseAllocationDeclaration(
            """
            projects:
              computenet: 60
              glass-factory: 40
            window: rolling-month
            monthly_cap:
              hours: 100
            """.trimIndent(),
        )

        assertEquals(
            DeclarationParse.Valid(
                AllocationDeclaration(
                    weights = mapOf("computenet" to 60.0, "glass-factory" to 40.0),
                    monthlyCapHours = 100.0,
                    window = "rolling-month",
                ),
            ),
            result,
        )
    }

    @Test
    fun `missing window is valid with null window`() {
        val result = parseAllocationDeclaration(
            """
            projects: {computenet: 60, glass-factory: 40}
            monthly_cap: {hours: 100}
            """.trimIndent(),
        )

        assertEquals(
            DeclarationParse.Valid(
                AllocationDeclaration(
                    weights = mapOf("computenet" to 60.0, "glass-factory" to 40.0),
                    monthlyCapHours = 100.0,
                    window = null,
                ),
            ),
            result,
        )
    }

    @Test
    fun `reformatted declaration has equal parsed identity`() {
        val first = parseAllocationDeclaration(
            """
            projects:
              computenet: 60
              glass-factory: 40
            monthly_cap:
              hours: 100
            """.trimIndent(),
        )
        val reformatted = parseAllocationDeclaration(
            "projects: { glass-factory: 40, computenet: 60 }\nmonthly_cap: { hours: 100 }",
        )

        assertEquals(first, reformatted)
    }

    @Test
    fun `unknown top-level key is tolerated`() {
        val result = parseAllocationDeclaration(
            """
            projects: {computenet: 60}
            monthly_cap: {hours: 100}
            future_setting: enabled
            """.trimIndent(),
        )

        assertTrue(result is DeclarationParse.Valid)
    }

    @Test
    fun `missing monthly cap is malformed`() {
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration("projects: {computenet: 60}"),
        )
    }

    @Test
    fun `missing projects is malformed`() {
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration("monthly_cap: {hours: 100}"),
        )
    }

    @Test
    fun `missing monthly cap hours is malformed`() {
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration(
                "projects: {computenet: 60}\nmonthly_cap: {}",
            ),
        )
    }

    @Test
    fun `non-numeric weight is malformed`() {
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration(
                "projects: {computenet: many}\nmonthly_cap: {hours: 100}",
            ),
        )
    }

    @Test
    fun `wrong required field shapes are malformed`() {
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration(
                "projects: [computenet]\nmonthly_cap: {hours: 100}",
            ),
        )
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration(
                "projects: {computenet: 60}\nmonthly_cap: 100",
            ),
        )
        assertEquals(
            DeclarationParse.Malformed,
            parseAllocationDeclaration(
                "projects: {computenet: 60}\nmonthly_cap: {hours: many}",
            ),
        )
    }

    @Test
    fun `garbage and empty input are malformed without throwing`() {
        assertEquals(DeclarationParse.Malformed, parseAllocationDeclaration("not yaml: ["))
        assertEquals(DeclarationParse.Malformed, parseAllocationDeclaration(""))
    }

    @Test
    fun `weights and cap are not constrained by parser`() {
        val result = parseAllocationDeclaration(
            """
            projects: {computenet: -2, glass-factory: 0}
            monthly_cap: {hours: -1.5}
            """.trimIndent(),
        )

        assertEquals(
            DeclarationParse.Valid(
                AllocationDeclaration(
                    weights = mapOf("computenet" to -2.0, "glass-factory" to 0.0),
                    monthlyCapHours = -1.5,
                    window = null,
                ),
            ),
            result,
        )
    }
}
