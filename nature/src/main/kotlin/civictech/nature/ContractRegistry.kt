package civictech.nature

import java.lang.reflect.Method
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of a concurrently-mutated collection that cannot throw while it is
 * taken. Deliberately **not** `Iterable<T>.toList()`: kotlin-stdlib
 * special-cases a `Collection` of `size == 1` as `listOf(iterator().next())`,
 * which reads `size()` and then calls `iterator()`/`next()` as two separate,
 * unsynchronized operations. Against a [ConcurrentHashMap] view, an entry
 * removed between those two reads makes `next()` raise
 * `NoSuchElementException` instead of yielding an empty list — observed on
 * every concurrent `ModuleRegistration.register` call, which reads these
 * getters lock-free by design (computenet-pu0c, [JAR1-REG-09]).
 *
 * `ArrayList(c)` goes through `c.toArray()`, which walks the weakly-consistent
 * view once and treats `size` as a capacity hint only, so a concurrent
 * insertion or removal yields a shorter or longer snapshot but never an error.
 */
internal fun <T> Collection<T>.concurrentSnapshot(): List<T> = java.util.ArrayList(this)

/**
 * Runtime index of generated [ContractModule]s (ServiceLoader-discovered).
 * Resolves a reflective in-process capture to its stable wire identity — the
 * only place reflection and wire ids meet (C-5): in-process dispatch stays
 * reflective, the serialized form never is.
 *
 * Registration is module-scoped and reversible (JAR1): every entry records the
 * multiset of [ModuleId]s that contributed it, a contribution is validated in
 * full before any mutation, and [unregister] removes exactly what one module
 * still holds alone. Classpath descriptors found by the init-time scan are
 * attributed to [ModuleId.HOST] and are not removable.
 */
object ContractRegistry {

    private val byId = ConcurrentHashMap<Long, ContractDescriptor>()
    private val byFqn = ConcurrentHashMap<String, ContractDescriptor>()
    private val byMethodKey = ConcurrentHashMap<String, Pair<ContractDescriptor, MethodDescriptor>>()
    private val cellsByFqn = ConcurrentHashMap<String, CellDescriptor>()

    private val contractProvenance = Provenance<Long>()
    private val cellProvenance = CellProvenance()
    private val fqnIndex = ContractFqnIndex()

    init {
        ServiceLoader.load(ContractModule::class.java, ContractModule::class.java.classLoader)
            .forEach { register(it) }
    }

    /**
     * Validate-then-commit for one module, atomic on its own: the whole module's
     * contracts, methods and cells are checked against live state before anything
     * is written, so a collision on the third contract no longer leaves the first
     * two permanently installed [JAR1-REG-01][JAR1-REG-02].
     *
     * [owner] defaults to [ModuleId.HOST] so every pre-existing caller compiles
     * and behaves unmodified.
     */
    fun register(module: ContractModule, owner: ModuleId = ModuleId.HOST): ValidationReport =
        synchronized(RegistryMutation.lock) {
            val staging = Staging()
            stage(module, staging)
            val report = staging.report()
            if (!report.isValid) throw RegistrationRefusedException(report)
            commit(module, owner)
            report
        }

    /** Pure dry run — mutates nothing. */
    fun validate(module: ContractModule): ValidationReport =
        synchronized(RegistryMutation.lock) {
            val staging = Staging()
            stage(module, staging)
            staging.report()
        }

    /**
     * Drop [owner]'s contributions to this registry. Prefer
     * [ModuleRegistration.unregister], which spans all three registries.
     *
     * This seam handles contracts and cells only — as [register] does, and as it
     * always has: a module's protocols reach [ProtocolRegistry] through
     * [ModuleRegistration] or that registry's own seam.
     */
    fun unregister(owner: ModuleId) {
        require(owner != ModuleId.HOST) {
            "the host module is not unregisterable: descriptors present at process start back the " +
                "running graph, so removing them would strand live cells"
        }
        synchronized(RegistryMutation.lock) { removeOwner(owner) }
    }

    /** Modules that contributed the contract carrying [contractId], newest last. */
    fun contributorsOf(contractId: Long): List<ModuleId> = contractProvenance.of(contractId)

    /** Modules that contributed the cell descriptor for [fqn]. */
    fun cellContributorsOf(fqn: String): List<ModuleId> = cellProvenance.of(fqn)

    internal fun stage(module: ContractModule, staging: Staging) {
        module.contracts.forEach { contract ->
            val existing = staging.contracts[contract.contractId] ?: byId[contract.contractId]
            if (existing != null && existing != contract) {
                staging.conflicts += RegistryConflict(
                    kind = ConflictKind.CONTRACT_ID,
                    id = contract.contractId.toString(),
                    existingFqn = existing.fqn,
                    incomingFqn = contract.fqn,
                    existing = existing.toString(),
                    incoming = contract.toString(),
                )
                return@forEach
            }
            staging.contracts[contract.contractId] = contract
            contract.methods.forEach { method ->
                val key = methodKey(contract, method)
                val incumbent = staging.methods[key] ?: byMethodKey[key]
                if (incumbent != null && incumbent.second.methodId != method.methodId) {
                    staging.conflicts += RegistryConflict(
                        kind = ConflictKind.METHOD_KEY,
                        id = key,
                        existingFqn = incumbent.first.fqn,
                        incomingFqn = contract.fqn,
                        existing = incumbent.second.toString(),
                        incoming = method.toString(),
                    )
                } else {
                    staging.methods[key] = contract to method
                }
            }
        }
        // Cell descriptors are deliberately NOT validated: [JAR1-REG-01] names
        // contractId, methodId and protocolId, and cell placement metadata has
        // always been last-writer-wins here. They still carry provenance, so
        // unregistration reverses them.
    }

    internal fun commit(module: ContractModule, owner: ModuleId) {
        module.contracts.forEach { contract ->
            byId[contract.contractId] = contract
            byFqn[contract.fqn] = contract
            contract.methods.forEach { method ->
                byMethodKey[methodKey(contract, method)] = contract to method
            }
            contractProvenance.add(contract.contractId, owner)
            fqnIndex.add(contract.fqn, contract.contractId)
        }
        module.cells.forEach {
            cellsByFqn[it.fqn] = it
            cellProvenance.add(it.fqn, owner, it)
        }
    }

    internal fun removeOwner(owner: ModuleId) {
        contractProvenance.drop(owner).forEach { contractId ->
            val descriptor = byId.remove(contractId) ?: return@forEach
            // computenet-dhgy/computenet-nh51: byFqn/byMethodKey are secondary indexes
            // keyed on fqn / method-key, not on contractId — stage() validates
            // contractId only, so a different contractId can legitimately share this
            // fqn (JAR2's territory, computenet-051 G-49) and commit()'s
            // last-writer-wins may have pointed byFqn/byMethodKey at the contract that
            // just departed. Restore each stranded key from the contractIds still
            // registered under this fqn, in the same last-writer-wins order commit
            // applies.
            //
            // The two tables are restored on SEPARATE gates on purpose. byFqn is
            // genuinely one-key-one-holder, so "did the departing descriptor hold it"
            // answers for the whole fqn. byMethodKey is not: its holder and byFqn's
            // holder DIVERGE the moment contributors sharing one fqn carry different
            // method sets, because a later contributor lacking method m repoints
            // byFqn without repointing byMethodKey[fqn#m]. Gating byMethodKey on
            // wasFqnHolder therefore stranded a live method whenever the departing
            // contract was byMethodKey's holder but not byFqn's (computenet-nh51), so
            // each method key is decided on its own removal instead.
            val wasFqnHolder = byFqn.remove(descriptor.fqn, descriptor)
            val strandedMethods = descriptor.methods.filter { method ->
                byMethodKey.remove(methodKey(descriptor, method), descriptor to method)
            }
            val remainingIds = fqnIndex.remove(descriptor.fqn, contractId)
            // Newest first: the same ordering commit's last-writer-wins produces.
            val survivors = remainingIds.asReversed().mapNotNull { byId[it] }
            if (wasFqnHolder) {
                survivors.firstOrNull()?.let { byFqn[it.fqn] = it }
            }
            strandedMethods.forEach { departed ->
                val key = methodKey(descriptor, departed)
                survivors.firstNotNullOfOrNull { survivor ->
                    survivor.methods.firstOrNull { methodKey(survivor, it) == key }?.let { survivor to it }
                }?.let { byMethodKey[key] = it }
            }
        }
        val cellDrop = cellProvenance.drop(owner)
        cellDrop.orphaned.forEach { cellsByFqn.remove(it) }
        // computenet-b7fr: cells are last-writer-wins on register (never validated,
        // [JAR1-REG-01]), so a departed contributor may have repointed cellsByFqn[fqn]
        // away from a still-live contributor's descriptor. Restore the surviving
        // contributor's own descriptor rather than leaving the departed one's behind.
        cellDrop.repointed.forEach { (fqn, descriptor) -> cellsByFqn[fqn] = descriptor }
    }

    private fun methodKey(contract: ContractDescriptor, method: MethodDescriptor): String =
        "${contract.fqn}#${method.name}${method.jvmDescriptor}"

    fun contract(contractId: Long): ContractDescriptor? = byId[contractId]

    /** The descriptor of a contract interface, if `@Contract`-annotated and registered. */
    fun descriptor(clazz: Class<*>): ContractDescriptor? = byFqn[clazz.name.replace('$', '.')]

    fun cellDescriptor(clazz: Class<*>): CellDescriptor? = cellsByFqn[clazz.name.replace('$', '.')]

    fun method(contractId: Long, methodId: Long): MethodDescriptor? =
        byId[contractId]?.methods?.find { it.methodId == methodId }

    /** Stable ids of a reflectively captured call; null when the declaring interface has no [Contract]. */
    fun idsOf(method: Method): Pair<Long, Long>? {
        val fqn = method.declaringClass.name.replace('$', '.')
        val key = "$fqn#${method.name}${JvmDescriptors.of(method)}"
        return byMethodKey[key]?.let { (c, m) -> c.contractId to m.methodId }
    }

    /**
     * Defensive copy (T03): a live map view let a caller iterate a registry that
     * mutates underneath it. Taken via [concurrentSnapshot] — this getter is read
     * lock-free, including by `ModuleRegistration.ensureRegistriesInitialized`.
     */
    val contracts: Collection<ContractDescriptor> get() = byId.values.concurrentSnapshot()

    /** Defensive copy (T03), same reasoning as [contracts]. */
    val cells: Collection<CellDescriptor> get() = cellsByFqn.values.concurrentSnapshot()
}

/** Runtime index of the generated, bounded metadata-protocol descriptors. */
object ProtocolRegistry {
    private val byId = ConcurrentHashMap<String, ProtocolDescriptor>()
    private val byContractId = ConcurrentHashMap<Long, ProtocolDescriptor>()

    private val provenance = Provenance<String>()

    init {
        ServiceLoader.load(ContractModule::class.java, ContractModule::class.java.classLoader)
            .flatMap { it.protocols }
            .forEach { register(it) }
    }

    fun register(descriptor: ProtocolDescriptor, owner: ModuleId = ModuleId.HOST): ValidationReport =
        synchronized(RegistryMutation.lock) {
            val staging = Staging()
            stage(listOf(descriptor), staging)
            val report = staging.report()
            if (!report.isValid) throw RegistrationRefusedException(report)
            commit(listOf(descriptor), owner)
            report
        }

    fun validate(descriptors: List<ProtocolDescriptor>): ValidationReport =
        synchronized(RegistryMutation.lock) {
            val staging = Staging()
            stage(descriptors, staging)
            staging.report()
        }

    fun unregister(owner: ModuleId) {
        require(owner != ModuleId.HOST) {
            "the host module is not unregisterable: descriptors present at process start back the " +
                "running graph, so removing them would strand live cells"
        }
        synchronized(RegistryMutation.lock) { removeOwner(owner) }
    }

    fun contributorsOf(protocolId: String): List<ModuleId> = provenance.of(protocolId)

    internal fun stage(descriptors: List<ProtocolDescriptor>, staging: Staging) {
        descriptors.forEach { descriptor ->
            val byIdIncumbent = staging.protocolsById[descriptor.protocolId] ?: byId[descriptor.protocolId]
            if (byIdIncumbent != null && byIdIncumbent != descriptor) {
                staging.conflicts += RegistryConflict(
                    kind = ConflictKind.PROTOCOL_ID,
                    id = descriptor.protocolId,
                    existingFqn = byIdIncumbent.contractId.toString(),
                    incomingFqn = descriptor.contractId.toString(),
                    existing = byIdIncumbent.toString(),
                    incoming = descriptor.toString(),
                )
                return@forEach
            }
            val byContractIncumbent =
                staging.protocolsByContractId[descriptor.contractId] ?: byContractId[descriptor.contractId]
            if (byContractIncumbent != null && byContractIncumbent != descriptor) {
                staging.conflicts += RegistryConflict(
                    kind = ConflictKind.PROTOCOL_CONTRACT_ID,
                    id = descriptor.contractId.toString(),
                    existingFqn = byContractIncumbent.protocolId,
                    incomingFqn = descriptor.protocolId,
                    existing = byContractIncumbent.toString(),
                    incoming = descriptor.toString(),
                )
                return@forEach
            }
            staging.protocolsById[descriptor.protocolId] = descriptor
            staging.protocolsByContractId[descriptor.contractId] = descriptor
        }
    }

    internal fun commit(descriptors: List<ProtocolDescriptor>, owner: ModuleId) {
        descriptors.forEach { descriptor ->
            byId[descriptor.protocolId] = descriptor
            byContractId[descriptor.contractId] = descriptor
            provenance.add(descriptor.protocolId, owner)
        }
    }

    internal fun removeOwner(owner: ModuleId) {
        provenance.drop(owner).forEach { protocolId ->
            val descriptor = byId.remove(protocolId) ?: return@forEach
            byContractId.remove(descriptor.contractId, descriptor)
        }
    }

    fun protocol(id: String): ProtocolDescriptor? = byId[id]
    fun protocol(contractId: Long): ProtocolDescriptor? = byContractId[contractId]

    /** Defensive copy (T03), same reasoning as [ContractRegistry.contracts]. */
    val protocols: Collection<ProtocolDescriptor> get() = byId.values.concurrentSnapshot()
}

/**
 * JVM method descriptors from reflection — must produce exactly what KSP's
 * `mapToJvmSignature` produces at generation time, or [ContractRegistry.idsOf]
 * silently misses.
 */
object JvmDescriptors {
    fun of(method: Method): String =
        method.parameterTypes.joinToString("", "(", ")", transform = ::desc) + desc(method.returnType)

    private fun desc(c: Class<*>): String = when {
        c === Void.TYPE -> "V"
        c === Boolean::class.javaPrimitiveType -> "Z"
        c === Byte::class.javaPrimitiveType -> "B"
        c === Char::class.javaPrimitiveType -> "C"
        c === Short::class.javaPrimitiveType -> "S"
        c === Int::class.javaPrimitiveType -> "I"
        c === Long::class.javaPrimitiveType -> "J"
        c === Float::class.javaPrimitiveType -> "F"
        c === Double::class.javaPrimitiveType -> "D"
        c.isArray -> "[" + desc(c.componentType)
        else -> "L${c.name.replace('.', '/')};"
    }

    /**
     * Parameter type names (in `Class.getName` form) recovered from a method
     * descriptor — how a decoded wire frame regains the reflective in-process
     * dispatch path without class names ever crossing the wire.
     */
    fun parameterTypeNames(methodDescriptor: String): List<String> {
        val params = methodDescriptor.substringAfter('(').substringBefore(')')
        val names = mutableListOf<String>()
        var i = 0
        while (i < params.length) {
            val start = i
            while (params[i] == '[') i++
            i = if (params[i] == 'L') params.indexOf(';', i) + 1 else i + 1
            names += typeName(params.substring(start, i))
        }
        return names
    }

    private fun typeName(desc: String): String = when (desc[0]) {
        'Z' -> "boolean"
        'B' -> "byte"
        'C' -> "char"
        'S' -> "short"
        'I' -> "int"
        'J' -> "long"
        'F' -> "float"
        'D' -> "double"
        'L' -> desc.substring(1, desc.length - 1).replace('/', '.')
        '[' -> desc.replace('/', '.') // Class.getName keeps JVM array form, dot-separated
        else -> error("unparseable type descriptor: $desc")
    }
}
