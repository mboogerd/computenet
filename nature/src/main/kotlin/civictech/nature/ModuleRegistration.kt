package civictech.nature

import civictech.gen.wire.ProxyModule
import civictech.gen.wire.ProxyRegistry

/**
 * Identity of a descriptor *contributor* — the unit of provenance and of
 * reversal (JAR1 [JAR1-REG-03]). Anything present at process start (the
 * one-shot `ServiceLoader` scan each registry runs in its `init`) is attributed
 * to [HOST], which is deliberately not unregisterable [JAR1-REG-04].
 */
@JvmInline
value class ModuleId(val id: String) {
    override fun toString(): String = id

    companion object {
        /** The process itself: classpath descriptors discovered by the init-time scan. */
        val HOST = ModuleId("<host>")
    }
}

/** Which registry table a [RegistryConflict] was found in. */
enum class ConflictKind { CONTRACT_ID, METHOD_KEY, PROTOCOL_ID, PROTOCOL_CONTRACT_ID }

/**
 * One refusal reason. The diagnostic names the colliding id and **both** FQNs
 * [JAR1-REG-05]; the rationale is the pre-existing one and is not softened —
 * the wire decodes ids only, so a silent repoint mis-decodes frames.
 */
data class RegistryConflict(
    val kind: ConflictKind,
    val id: String,
    val existingFqn: String,
    val incomingFqn: String,
    val existing: String,
    val incoming: String,
) {
    fun message(): String =
        "$kind collision: $id already registered as $existingFqn ($existing), cannot also register " +
            "$incomingFqn ($incoming) — the wire decodes ids only, so a silent repoint mis-decodes frames"
}

/** Outcome of a dry run: empty ⇒ the contribution commits cleanly. */
data class ValidationReport(val conflicts: List<RegistryConflict> = emptyList()) {
    val isValid: Boolean get() = conflicts.isEmpty()

    fun describe(): String = conflicts.joinToString("; ") { it.message() }

    companion object {
        val OK = ValidationReport()
    }
}

/**
 * Refusal of a registration. An [IllegalArgumentException] on purpose: the
 * pre-existing `require`-based seam threw exactly that, and callers (concord's
 * `KernelAdapters`, kernel's spawn-check test) keep compiling and behaving
 * unmodified.
 */
class RegistrationRefusedException(val report: ValidationReport) :
    IllegalArgumentException(report.describe())

/**
 * The single write lock guarding ALL three registries. Reads stay lock-free on
 * the existing `ConcurrentHashMap`s; every mutation — validate-then-commit and
 * unregister alike — happens under this monitor, which is what makes
 * cross-registry atomicity [JAR1-REG-02] mean something: a ContractModule and a
 * ProxyModule are separate `ServiceLoader` services, so per-registry locking
 * could not span them.
 */
internal object RegistryMutation {
    val lock = Any()
}

/**
 * Scratch view of "the registries as they would be after this contribution",
 * accumulated across every contributed module BEFORE anything is written. Seeded
 * lazily from live state, so a conflict against an already-registered id and a
 * conflict against an id contributed earlier in the same batch read the same.
 */
internal class Staging {
    val contracts = mutableMapOf<Long, ContractDescriptor>()
    val methods = mutableMapOf<String, Pair<ContractDescriptor, MethodDescriptor>>()
    val protocolsById = mutableMapOf<String, ProtocolDescriptor>()
    val protocolsByContractId = mutableMapOf<Long, ProtocolDescriptor>()
    val conflicts = mutableListOf<RegistryConflict>()

    fun report(): ValidationReport = ValidationReport(conflicts.toList())
}

/**
 * The combined, cross-registry registration seam — the entry point feature
 * computenet-051.3's loader calls. Deliberately free of any jar / classloader /
 * manifest concern.
 *
 * Why this exists rather than only per-registry `validate`/`register`: scenario
 * B7 requires that a refused contribution leave `ProxyRegistry` without a
 * factory as well as `ContractRegistry` without a contract, and those arrive as
 * two independent `ServiceLoader` services. Only a validate pass spanning both
 * tables, followed by a commit under one lock, can promise that
 * [JAR1-REG-01][JAR1-REG-02].
 */
object ModuleRegistration {

    /** Pure dry run over every contributed table against live state. Mutates nothing. */
    fun validate(
        contractModules: List<ContractModule> = emptyList(),
        proxyModules: List<ProxyModule> = emptyList(),
    ): ValidationReport {
        ensureRegistriesInitialized()
        return synchronized(RegistryMutation.lock) { stage(contractModules, proxyModules).report() }
    }

    /**
     * Validate everything, then commit into ContractRegistry + ProtocolRegistry +
     * ProxyRegistry under one lock. Any conflict leaves all three byte-for-byte
     * unchanged and throws [RegistrationRefusedException].
     */
    fun register(
        owner: ModuleId,
        contractModules: List<ContractModule> = emptyList(),
        proxyModules: List<ProxyModule> = emptyList(),
    ): ValidationReport {
        ensureRegistriesInitialized()
        return synchronized(RegistryMutation.lock) {
            val report = stage(contractModules, proxyModules).report()
            if (!report.isValid) throw RegistrationRefusedException(report)
            contractModules.forEach { module ->
                ContractRegistry.commit(module, owner)
                ProtocolRegistry.commit(module.protocols, owner)
            }
            proxyModules.forEach { ProxyRegistry.commit(it, owner) }
            report
        }
    }

    /**
     * Drop every contribution made by [owner] across all three registries.
     * Entries another module also contributed survive [JAR1-REG-06].
     */
    fun unregister(owner: ModuleId) {
        require(owner != ModuleId.HOST) {
            "the host module is not unregisterable: descriptors present at process start back the " +
                "running graph, so removing them would strand live cells"
        }
        ensureRegistriesInitialized()
        synchronized(RegistryMutation.lock) {
            ContractRegistry.removeOwner(owner)
            ProtocolRegistry.removeOwner(owner)
            ProxyRegistry.removeOwner(owner)
        }
    }

    private fun stage(contractModules: List<ContractModule>, proxyModules: List<ProxyModule>): Staging {
        val staging = Staging()
        contractModules.forEach { module ->
            ContractRegistry.stage(module, staging)
            ProtocolRegistry.stage(module.protocols, staging)
        }
        proxyModules.forEach { ProxyRegistry.stage(it, staging) }
        return staging
    }

    /**
     * Touch each registry's class *before* taking the lock. A registry's `<clinit>`
     * registers its own scan and so takes the same lock; triggering that
     * initialization while already holding it would let a second thread block on the
     * class-init monitor while we block on nothing — cheap to avoid, awkward to debug.
     */
    private fun ensureRegistriesInitialized() {
        ContractRegistry.contracts
        ProtocolRegistry.protocols
        ProxyRegistry.factory(ModuleRegistration::class.java)
    }
}

/**
 * Contributor multiset for one registry table. Values are immutable lists swapped
 * wholesale under [RegistryMutation.lock], so a lock-free reader never sees a
 * half-built list. A multiset (not a set) because "who else still holds this
 * entry" is the question unregistration asks.
 */
internal class Provenance<K : Any> {
    private val contributors = java.util.concurrent.ConcurrentHashMap<K, List<ModuleId>>()

    fun add(key: K, owner: ModuleId) {
        contributors[key] = (contributors[key] ?: emptyList()) + owner
    }

    fun of(key: K): List<ModuleId> = contributors[key] ?: emptyList()

    /** Keys whose last contribution by [owner] leaves nobody behind. */
    fun drop(owner: ModuleId): List<K> {
        val orphaned = mutableListOf<K>()
        contributors.keys.toList().forEach { key ->
            val remaining = (contributors[key] ?: emptyList()).filterNot { it == owner }
            if (remaining.isEmpty()) {
                contributors.remove(key)
                orphaned += key
            } else {
                contributors[key] = remaining
            }
        }
        return orphaned
    }
}
