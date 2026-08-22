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
        // concurrentSnapshot, not toList(): every mutation here happens under
        // RegistryMutation.lock so the size==1 TOCTOU is not reachable today, but the
        // shape is the one that broke the lock-free getters (computenet-pu0c) and
        // nothing in the type stops a future caller reading provenance off-lock.
        contributors.keys.concurrentSnapshot().forEach { key ->
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

/**
 * Outcome of [CellProvenance.drop]: which fqns lost their last contributor
 * entirely ([orphaned]), and which fqns still have a live contributor but must
 * have [ContractRegistry]'s `cellsByFqn` restored to that contributor's own
 * descriptor ([repointed]) because the departing contributor's descriptor was
 * the one currently resolving (computenet-b7fr).
 */
internal data class CellProvenanceDrop(val orphaned: List<String>, val repointed: Map<String, CellDescriptor>)

/**
 * Contributor multiset for [ContractRegistry]'s cell table, specialized (not
 * built on [Provenance]) because cell registration is deliberately
 * last-writer-wins and NOT validated [JAR1-REG-01] — unlike contract/method/
 * protocol *primary* keys, a later contributor's [CellDescriptor] can silently
 * repoint an fqn a still-live contributor already holds. [drop] has to know
 * not just who is left (what [Provenance] tracks) but *which descriptor* they
 * contributed, so [ContractRegistry.removeOwner] can restore it — otherwise a
 * departed contributor's descriptor outlives it whenever it was not the last
 * one to unregister (computenet-b7fr).
 *
 * Contract*Ids*, protocols and proxies keep using the plain [Provenance] for
 * their own primary keys: contract and protocol contributions are refused
 * outright when non-equal [JAR1-REG-05], and proxy contributions are
 * first-writer-wins with functionally-identical constructors — neither
 * primary table can be repointed by a later contributor, so neither needs a
 * descriptor to restore.
 *
 * `ContractRegistry.byFqn`/`byMethodKey` are a **different** case, despite
 * looking like the primary-key case: they are *secondary* indexes, keyed on
 * fqn / method-key rather than contractId, and [ContractRegistry.stage]
 * validates by contractId only — it never compares the fqn. Two contracts
 * with different contractIds can therefore legitimately share one fqn (only
 * reachable via a hand-constructed [ContractDescriptor] today; JAR2's
 * same-FQN-two-versions requirement — epic computenet-051's G-49 note — is
 * built to make it reachable in production), and `commit` repoints
 * `byFqn[fqn]` to whichever contract committed last. computenet-dhgy is that
 * hole: [ContractRegistry.removeOwner] restores `byFqn`/`byMethodKey` from
 * [ContractFqnIndex] the same way, keyed on contractId rather than [ModuleId]
 * since it is a still-live *contractId* under the fqn, not a still-live
 * module, that the caller needs to fall back to.
 */
internal class CellProvenance {
    private val contributions = java.util.concurrent.ConcurrentHashMap<String, List<Pair<ModuleId, CellDescriptor>>>()

    fun add(fqn: String, owner: ModuleId, descriptor: CellDescriptor) {
        contributions[fqn] = (contributions[fqn] ?: emptyList()) + (owner to descriptor)
    }

    fun of(fqn: String): List<ModuleId> = (contributions[fqn] ?: emptyList()).map { it.first }

    /**
     * Remove every contribution [owner] made. An fqn left with no contributors
     * is dropped entirely and reported in [CellProvenanceDrop.orphaned]. An fqn
     * that still has contributors after [owner]'s are removed is reported in
     * [CellProvenanceDrop.repointed], mapped to the *last remaining*
     * contributor's descriptor — the same last-writer-wins ordering
     * [ContractRegistry.commit] applies among survivors — so the caller can
     * restore it even when it was not the one currently resolving (a harmless
     * no-op write in that case).
     */
    fun drop(owner: ModuleId): CellProvenanceDrop {
        val orphaned = mutableListOf<String>()
        val repointed = mutableMapOf<String, CellDescriptor>()
        // concurrentSnapshot, not toList(): same TOCTOU reasoning as Provenance.drop —
        // every mutation here happens under RegistryMutation.lock.
        contributions.keys.concurrentSnapshot().forEach { fqn ->
            val existing = contributions[fqn] ?: emptyList()
            val remaining = existing.filterNot { it.first == owner }
            if (remaining.isEmpty()) {
                contributions.remove(fqn)
                orphaned += fqn
            } else {
                contributions[fqn] = remaining
                if (remaining.size != existing.size) {
                    repointed[fqn] = remaining.last().second
                }
            }
        }
        return CellProvenanceDrop(orphaned, repointed)
    }
}

/**
 * Tracks, per fqn, the distinct contractIds that have ever committed a
 * [ContractDescriptor] under it, in commit order — bookkeeping
 * `ContractRegistry.byFqn`'s last-writer-wins index does not itself retain,
 * needed so [ContractRegistry.removeOwner] can repoint `byFqn`/`byMethodKey`
 * to the surviving contract rather than stranding them (computenet-dhgy).
 *
 * Deliberately **not** built on [Provenance]: `Provenance` answers "which
 * [ModuleId]s still hold this key", scanning every key a departing module
 * touched. This index answers a different question — "which *contractIds*
 * still target this fqn" — and the caller already knows the one fqn to ask
 * about (the departing contract's own), so it is a direct per-fqn add/remove
 * rather than an owner-wide scan. [CellProvenance] was considered as a
 * template too, but its value type ([CellDescriptor]) is per-contribution and
 * keeps every contributor's own descriptor for exact equality comparison on
 * removal (`remove(fqn, descriptor)`); here the value that matters is just
 * the *contractId*, because [ContractRegistry.byId] is already the source of
 * truth for the descriptor a live contractId currently holds — reusing that
 * table on repoint is what keeps this index from also having to duplicate
 * descriptor storage.
 */
internal class ContractFqnIndex {
    private val byFqn = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    /** Record [contractId] as a contributor to [fqn], if not already recorded. */
    fun add(fqn: String, contractId: Long) {
        val existing = byFqn[fqn] ?: emptyList()
        if (contractId !in existing) byFqn[fqn] = existing + contractId
    }

    /**
     * Remove [contractId] from [fqn]'s contributor list and return the last
     * remaining contractId, if any — the survivor `byFqn`/`byMethodKey` should
     * repoint to, mirroring the last-writer-wins order [ContractRegistry.commit]
     * applies. `null` means [fqn] has no live contractId left at all.
     */
    fun remove(fqn: String, contractId: Long): Long? {
        val remaining = (byFqn[fqn] ?: emptyList()) - contractId
        if (remaining.isEmpty()) byFqn.remove(fqn) else byFqn[fqn] = remaining
        return remaining.lastOrNull()
    }
}
