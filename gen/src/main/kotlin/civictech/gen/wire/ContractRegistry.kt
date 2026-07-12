package civictech.gen.wire

import java.lang.reflect.Method
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime index of generated [ContractModule]s (ServiceLoader-discovered).
 * Resolves a reflective in-process capture to its stable wire identity — the
 * only place reflection and wire ids meet (C-5): in-process dispatch stays
 * reflective, the serialized form never is.
 */
object ContractRegistry {

    private val byId = ConcurrentHashMap<Long, ContractDescriptor>()
    private val byFqn = ConcurrentHashMap<String, ContractDescriptor>()
    private val byMethodKey = ConcurrentHashMap<String, Pair<ContractDescriptor, MethodDescriptor>>()
    private val cellsByFqn = ConcurrentHashMap<String, CellDescriptor>()

    init {
        ServiceLoader.load(ContractModule::class.java, ContractModule::class.java.classLoader)
            .forEach(::register)
    }

    fun register(module: ContractModule) {
        module.contracts.forEach { contract ->
            byId[contract.contractId] = contract
            byFqn[contract.fqn] = contract
            contract.methods.forEach { method ->
                byMethodKey["${contract.fqn}#${method.name}${method.jvmDescriptor}"] = contract to method
            }
        }
        module.cells.forEach { cellsByFqn[it.fqn] = it }
    }

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

    val contracts: Collection<ContractDescriptor> get() = byId.values
    val cells: Collection<CellDescriptor> get() = cellsByFqn.values
}

/** Runtime index of the generated, bounded metadata-protocol descriptors. */
object ProtocolRegistry {
    private val byId = ConcurrentHashMap<String, ProtocolDescriptor>()
    private val byContractId = ConcurrentHashMap<Long, ProtocolDescriptor>()

    init {
        ServiceLoader.load(ContractModule::class.java, ContractModule::class.java.classLoader)
            .flatMap { it.protocols }
            .forEach(::register)
    }

    fun register(descriptor: ProtocolDescriptor) {
        byId[descriptor.protocolId] = descriptor
        byContractId[descriptor.contractId] = descriptor
    }

    fun protocol(id: String): ProtocolDescriptor? = byId[id]
    fun protocol(contractId: Long): ProtocolDescriptor? = byContractId[contractId]
    val protocols: Collection<ProtocolDescriptor> get() = byId.values
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
