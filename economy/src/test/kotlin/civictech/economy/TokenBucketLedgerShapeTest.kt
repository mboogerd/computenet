package civictech.economy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * `[ECO1-PAR-02]`: the ledger is per-host local state — neither a cell nor replicable.
 * `[ECO1-POL-07]` / `kwhw6-D6`: its snapshot is plain data with no function-typed field.
 */
class TokenBucketLedgerShapeTest {

    @Test
    fun `TokenBucketLedger is neither Replicable nor a Cell`() {
        civictech.cell.data.Replicable::class.java.isAssignableFrom(TokenBucketLedger::class.java) shouldBe false
        civictech.cell.Cell::class.java.isAssignableFrom(TokenBucketLedger::class.java) shouldBe false
    }

    @Test
    fun `snapshot types carry no function-typed field`() {
        listOf(LedgerSnapshot::class.java, BucketView::class.java).forEach { type ->
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .filter { Function::class.java.isAssignableFrom(it.type) || it.type.name.startsWith("kotlin.jvm.functions.") }
                .map { "${type.simpleName}.${it.name}" }
                .shouldBeEmpty()
        }
    }
}
