package civictech.cell.oracle

import civictech.cell.graph.CellFactory
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.ShapeRule
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ReferenceOp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * `[ORA1-API-01]`: `:oracle` is consumable from another module's test source set through a
 * plain `testImplementation(project(":oracle"))` **with no further configuration**.
 *
 * The load-bearing half of this test is that it *compiles* — `:kernel:compileTestKotlin`
 * resolving `civictech.oracle.*` from a consumer module is the requirement. Deleting the one
 * line this needs from `kernel/build.gradle.kts` turns that into a compile failure, which is
 * how the claim was checked rather than asserted.
 *
 * The body then exercises the seam once from the consumer's side, so a consumer registering
 * an operator against the kernel's own `CellFactory` is demonstrated and not merely typed:
 * that a `civictech.cell.graph.CellFactory` and a `civictech.oracle.model.ReferenceOp` meet
 * in one call is the whole point of the catalog living outside `:kernel`.
 *
 * It is deliberately trivial. The catalog's real behaviour is covered by
 * `civictech.oracle.bind.OperatorCatalogTest` in the module that owns it; duplicating those
 * assertions here would make `:kernel:test` a second, worse owner of :oracle's contract.
 */
class OracleConsumerTest {

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `a kernel-side consumer registers an operator through the oracle catalog`() {
        val entry = OperatorCatalog.register(
            id = "kernel-consumer-probe",
            shape = ShapeRule.unary(
                input = ElementShape.SetOf(ElementShape.Scalar),
                output = ElementShape.Scalar,
            ),
            kernel = CellFactory { ref -> error("OracleConsumerTest never constructs a cell (ref=$ref)") },
            model = object : ReferenceOp {},
        )

        entry.id shouldBe "kernel-consumer-probe"
        OperatorCatalog.shapeOf("kernel-consumer-probe")?.output shouldBe ElementShape.Scalar
    }
}
