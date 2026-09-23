package civictech.timetravel.fidelity

import civictech.cell.Cell
import civictech.cell.evolve.Effectful

/**
 * The replay-stable allow-list ([TTD1-35], [TTD1-38], `computenet-kxex2` D2, kxex2-D7):
 * the positive, conservative classifier deciding which cell classes a reconstruction can
 * trust. Nothing here inspects cell code or infers determinism — the allow-list itself is
 * the whole mechanism (epic §9.2), so anything not explicitly vouched for degrades.
 *
 * Order of checks (kxex2-D7 — this order is the decision, not an implementation detail):
 * 1. [Effectful] is checked first and unconditionally: an application's [extraFaithful]
 *    vouching does **not** clear it. An `Effectful` cell's reconstruction is never the run
 *    (epic §1), so no caller can override this.
 * 2. Otherwise, a class in one of [FAITHFUL_PACKAGES] (exact package match, not prefix) or
 *    explicitly named in [extraFaithful] is [Fidelity.Faithful].
 * 3. Otherwise the conservative default: [Reason.UNKNOWN_DETERMINISM].
 */
class ReplayStable(private val extraFaithful: Set<Class<out Cell>> = emptySet()) {

    fun classify(cls: Class<out Cell>): Fidelity {
        if (Effectful::class.java.isAssignableFrom(cls)) {
            return Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
        }
        if (cls.packageName in FAITHFUL_PACKAGES || extraFaithful.any { it.isAssignableFrom(cls) }) {
            return Fidelity.Faithful
        }
        return Fidelity.Degraded(setOf(Reason.UNKNOWN_DETERMINISM))
    }

    companion object {
        val DEFAULT = ReplayStable()

        /** Exact packages (not prefixes) of the kernel's replay-stable idempotent data vocabulary. */
        val FAITHFUL_PACKAGES = setOf(
            "civictech.cell.data",
            "civictech.cell.data.op",
            "civictech.cell.data.view",
            "civictech.cell.data.delta",
        )
    }
}
