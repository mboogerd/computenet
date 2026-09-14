package civictech.query.architecture

/**
 * Shared technique for `:query`'s several hand-maintained sealed-hierarchy type lists
 * (computenet-njvps): compares a sealed class/interface's declared permitted subclasses
 * against a hand-maintained list that claims to cover it, and names exactly which permitted
 * subclass the list is missing — so a new case that lands without updating the list fails
 * loudly instead of passing the guard vacuously.
 *
 * `:query` declares no `kotlin-reflect` dependency, so `KClass.sealedSubclasses` is
 * unavailable. `java.lang.Class.permittedSubclasses` (JDK 17+, the `PermittedSubclasses`
 * class-file attribute a `sealed` type's compiler emits) needs only plain `java.lang.Class`
 * reflection, the same reflection `DiagShapeTest` and this module's other guards already use.
 * Verified reachable on this module's toolchain: a throwaway probe test printed non-null
 * `permittedSubclasses` for both `civictech.query.diag.Locus` and
 * `civictech.query.plan.PlanNode` under `./gradlew :query:test` before this file was written
 * (see the technique note on the computenet-njvps bead).
 */
object HierarchyCompleteness {

    /**
     * Every direct permitted subclass of [sealedRoot] that [coveredTypes] does not contain,
     * by fully-qualified name — empty when [coveredTypes] fully covers the hierarchy.
     * Throws if [sealedRoot] is not actually a sealed class/interface (a guard misuse, not a
     * hierarchy gap: `permittedSubclasses` is `null` for any non-sealed type).
     */
    fun missingFrom(sealedRoot: Class<*>, coveredTypes: Collection<Class<*>>): List<String> {
        val permitted = requireNotNull(sealedRoot.permittedSubclasses) {
            "${sealedRoot.name} is not a sealed class/interface (permittedSubclasses is null)"
        }
        val coveredSet = coveredTypes.toSet()
        return permitted.filterNot { it in coveredSet }.map { it.name }
    }
}
