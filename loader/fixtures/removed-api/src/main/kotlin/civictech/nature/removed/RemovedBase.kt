package civictech.nature.removed

/**
 * Stands in for a shared API type the host build later removed.
 * `:loader:fixtures:missing-shared-type` compiles against this class through a
 * `compileOnly` dependency (so it never lands in that fixture's own jar), then
 * the parent-first-delegated `civictech.nature.` prefix means resolving that
 * fixture's cell class inside a `ModuleClassLoader` fails with
 * `NoClassDefFoundError` naming this class — ERR-04/B12.
 */
open class RemovedBase
