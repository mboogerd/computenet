package civictech.cell.oracle

import civictech.oracle.run.RunOutcome
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Shared per-seed loop for the batch folds migrated to [civictech.oracle.run.DifferentialRunner]
 * `ORA1 §DIFF-11` (computenet-4ru.12.4): OperatorTest's two pipeline folds and
 * RelationalGraphsTest's left-join fold all assert the same property — every seed in
 * `0 until 100` reaches [RunOutcome.Success] — and previously duplicated the loop and the
 * assertion. [check] owns everything about how a seed becomes a case (its script, its
 * reference, and the graph it drives); this only runs it across the shared range and names
 * the offending seed on failure, exactly as each original hand-rolled loop did.
 */
fun forEachBatchFoldSeed(check: (seed: Long) -> RunOutcome) {
    for (seed in 0L until 100L) {
        assertEquals(RunOutcome.Success, check(seed), "seed $seed")
    }
}
