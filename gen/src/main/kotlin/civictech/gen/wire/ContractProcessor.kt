package civictech.gen.wire

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.writeTo

class ContractProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ContractProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Emits one [ContractModule] per compilation module — a table of
 * [ContractDescriptor]s whose ids hash from FQN + erased JVM signature
 * (spec 41 point 1, C-5): stable across compilations, method reordering,
 * and modules, with no coordination. Registered for `ServiceLoader` via a
 * generated `META-INF/services` entry.
 */
class ContractProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var emitted = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (emitted) return emptyList()
        val contracts = resolver.getSymbolsWithAnnotation(Contract::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .sortedBy { it.qualifiedName!!.asString() }
            .toList()

        val cellType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(CELL_MARKER))
            ?.asStarProjectedType()
        val cells = if (cellType == null) emptyList() else resolver.getAllFiles()
            .flatMap { file -> file.declarations.flatMap(::classesIn) }
            .filter { it.classKind == ClassKind.CLASS && cellType.isAssignableFrom(it.asStarProjectedType()) }
            .sortedBy { it.qualifiedName!!.asString() }
            .toList()
        if (contracts.isEmpty() && cells.isEmpty()) return emptyList()
        emitted = true

        // Include both descriptor families so cell-only modules remain distinct.
        val moduleHash = StableHash.of(
            (contracts.map { "contract:${it.qualifiedName!!.asString()}" } +
                    cells.map { "cell:${it.qualifiedName!!.asString()}" }).joinToString(",")
        )
        val moduleName = "ContractTable_" + java.lang.Long.toHexString(moduleHash)
        logger.info("ContractProcessor: ${contracts.size} contracts -> $GENERATED_PACKAGE.$moduleName")

        val table = buildCodeBlock {
            add("listOf(\n⇥")
            contracts.forEach { contract ->
                val fqn = contract.qualifiedName!!.asString()
                val management = contract.getAnnotationsByType(Contract::class).first().management
                val effect = contract.getAnnotationsByType(Contract::class).first().effect
                add(
                    "%T(contractId·=·%LL, fqn·=·%S, management·=·%L, effect·=·%L, methods·=·listOf(\n⇥",
                    ContractDescriptor::class.asClassName(), StableHash.of(fqn), fqn, management, effect,
                )
                // push-only lint (spec 12, G-11 completed M9.1): data contracts
                // must not return values — returns are a management privilege
                if (!management) {
                    contract.getAllFunctions().filter { it.isAbstract }.forEach { fn ->
                        val returns = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString()
                        if (returns != null && returns != "kotlin.Unit") {
                            logger.error(
                                "data contract $fqn#${fn.simpleName.asString()} returns $returns — " +
                                        "push-only on the data path (spec 12); mark management=true or return Unit",
                                fn,
                            )
                        }
                    }
                }
                contract.getAllFunctions().filter { it.isAbstract }
                    .map { fn ->
                        val name = fn.simpleName.asString()
                        val descriptor = resolver.mapToJvmSignature(fn)
                            ?: error("no JVM signature for $fqn#$name")
                        val exclusive = fn.parameters.any { carriesExclusive(it.type.resolve()) }
                        val keyIndexes = fn.parameters.mapIndexedNotNull { index, parameter ->
                            index.takeIf { parameter.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == KEY_ANNOTATION } }
                        }
                        if (keyIndexes.size > 1) logger.error("data contract $fqn#$name has more than one @Key parameter", fn)
                        val keyIndex = keyIndexes.singleOrNull() ?: -1
                        if (exclusive && keyIndex < 0) {
                            logger.error(
                                "data contract $fqn#$name broadcasts an exclusive Owned/Leased payload — annotate its routing parameter with @Key",
                                fn,
                            )
                        }
                        MethodDescriptor(
                            StableHash.of("$fqn#$name$descriptor"), name, descriptor, exclusive,
                            fn.parameters.any { carriesMarker(it.type.resolve(), MAGNITUDE_MARKER) },
                            fn.parameters.any { carriesMarker(it.type.resolve(), REPLICABLE_MARKER) },
                            keyIndex,
                        )
                    }
                    .sortedBy { it.methodId }
                    .forEach { m ->
                        add(
                            "%T(methodId·=·%LL, name·=·%S, jvmDescriptor·=·%S, exclusive·=·%L, magnitude·=·%L, idempotentMerge·=·%L, keyIndex·=·%L),\n",
                            MethodDescriptor::class.asClassName(), m.methodId, m.name, m.jvmDescriptor, m.exclusive,
                            m.magnitude, m.idempotentMerge, m.keyIndex,
                        )
                    }
                add("⇤)),\n")
            }
            add("⇤)")
        }

        val protocolTable = buildCodeBlock {
            add("listOf(\n⇥")
            contracts.forEach { contract ->
                val protocol = contract.getAnnotationsByType(Protocol::class).firstOrNull() ?: return@forEach
                val fqn = contract.qualifiedName!!.asString()
                val management = contract.getAnnotationsByType(Contract::class).first().management
                if (!management) logger.error("protocol contract $fqn must be management-class (spec 12)", contract)
                contract.getAllFunctions().filter { it.isAbstract }.forEach { fn ->
                    val returns = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString()
                    if (returns != null && returns != "kotlin.Unit")
                        logger.error("protocol contract $fqn#${fn.simpleName.asString()} must be push-only", fn)
                    if (fn.parameters.any { carriesExclusive(it.type.resolve()) })
                        logger.error("protocol contract $fqn#${fn.simpleName.asString()} must not carry Owned/Leased", fn)
                }
                add("%T(%S, %LL, %T.%L, %L, %S, %T.%L),\n",
                    ProtocolDescriptor::class.asClassName(), protocol.id, StableHash.of(fqn),
                    ProtocolDirection::class.asClassName(), protocol.direction.name,
                    protocol.band, protocol.lane,
                    ProtocolCardinality::class.asClassName(), protocol.cardinality.name)
            }
            add("⇤)")
        }

        val moduleType = TypeSpec.classBuilder(moduleName)
            .addSuperinterface(ContractModule::class.asClassName())
            .addProperty(
                PropertySpec.builder(
                    "contracts",
                    LIST.parameterizedBy(ContractDescriptor::class.asClassName()),
                    KModifier.OVERRIDE,
                ).initializer(table).build()
            )
            .addProperty(
                PropertySpec.builder(
                    "cells", LIST.parameterizedBy(CellDescriptor::class.asClassName()), KModifier.OVERRIDE,
                ).initializer(buildCodeBlock {
                    add("listOf(\n⇥")
                    cells.forEach { cell ->
                        val fqn = cell.qualifiedName!!.asString()
                        val color = when {
                            isSubtype(cell.asStarProjectedType(), SUSPENDING_MARKER) -> CellColor.SUSPENDING
                            isSubtype(cell.asStarProjectedType(), BLOCKING_MARKER) -> CellColor.BLOCKING
                            else -> CellColor.PURE
                        }
                        add("%T(fqn·=·%S, color·=·%T.%L),\n", CellDescriptor::class, fqn, CellColor::class, color.name)
                    }
                    add("⇤)")
                }).build()
            )
            .addProperty(
                PropertySpec.builder("protocols", LIST.parameterizedBy(ProtocolDescriptor::class.asClassName()), KModifier.OVERRIDE)
                    .initializer(protocolTable).build()
            )
            .build()

        val sources = Dependencies(
            true,
            *(contracts.mapNotNull { it.containingFile } + cells.mapNotNull { it.containingFile })
                .distinct()
                .toTypedArray(),
        )

        FileSpec.builder(GENERATED_PACKAGE, moduleName)
            .addType(moduleType)
            .build()
            .writeTo(codeGenerator, sources)

        codeGenerator.createNewFileByPath(sources, "META-INF/services/civictech.gen.wire.ContractModule", "")
            .bufferedWriter()
            .use { it.write("$GENERATED_PACKAGE.$moduleName\n") }

        return emptyList()
    }

    /** Ownership bit (spec 23, G-21 phase 2): does the type mention Owned/Leased anywhere? */
    private fun carriesExclusive(type: com.google.devtools.ksp.symbol.KSType): Boolean {
        if (type.declaration.qualifiedName?.asString() in EXCLUSIVE_MARKERS) return true
        return type.arguments.any { it.type?.resolve()?.let(::carriesExclusive) == true }
    }

    private fun isSubtype(type: KSType, marker: String): Boolean {
        if (type.declaration.qualifiedName?.asString() == marker) return true
        return (type.declaration as? KSClassDeclaration)?.superTypes
            ?.any { isSubtype(it.resolve(), marker) } == true
    }

    /** Descriptor scans include nested payloads, matching the established ownership scan. */
    private fun carriesMarker(type: KSType, marker: String): Boolean =
        isSubtype(type, marker) || type.arguments.any { argument ->
            argument.type?.resolve()?.let { carriesMarker(it, marker) } == true
        }

    private fun classesIn(declaration: com.google.devtools.ksp.symbol.KSDeclaration): Sequence<KSClassDeclaration> =
        sequence {
            if (declaration is KSClassDeclaration) {
                yield(declaration)
                declaration.declarations.forEach { yieldAll(classesIn(it)) }
            }
        }

    companion object {
        const val GENERATED_PACKAGE = "civictech.gen.wire.generated"

        // FQN constants: :gen cannot depend on :kernel (the dependency runs the other way)
        val EXCLUSIVE_MARKERS = setOf("civictech.cell.Owned", "civictech.cell.Leased")
        const val KEY_ANNOTATION = "civictech.gen.wire.Key"
        const val CELL_MARKER = "civictech.cell.Cell"
        const val MAGNITUDE_MARKER = "civictech.cell.data.Magnitude"
        const val REPLICABLE_MARKER = "civictech.cell.data.Replicable"
        const val BLOCKING_MARKER = "civictech.cell.BlockingCell"
        const val SUSPENDING_MARKER = "civictech.cell.SuspendingCell"
    }
}
