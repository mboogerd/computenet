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

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val contracts = resolver.getSymbolsWithAnnotation(Contract::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .sortedBy { it.qualifiedName!!.asString() }
            .toList()
        if (contracts.isEmpty()) return emptyList()

        // deterministic per-module class name: same contract set -> same table
        val moduleHash = StableHash.of(contracts.joinToString(",") { it.qualifiedName!!.asString() })
        val moduleName = "ContractTable_" + java.lang.Long.toHexString(moduleHash)
        logger.info("ContractProcessor: ${contracts.size} contracts -> $GENERATED_PACKAGE.$moduleName")

        val table = buildCodeBlock {
            add("listOf(\n⇥")
            contracts.forEach { contract ->
                val fqn = contract.qualifiedName!!.asString()
                val management = contract.getAnnotationsByType(Contract::class).first().management
                add(
                    "%T(contractId·=·%LL, fqn·=·%S, management·=·%L, methods·=·listOf(\n⇥",
                    ContractDescriptor::class.asClassName(), StableHash.of(fqn), fqn, management,
                )
                contract.getAllFunctions().filter { it.isAbstract }
                    .map { fn ->
                        val name = fn.simpleName.asString()
                        val descriptor = resolver.mapToJvmSignature(fn)
                            ?: error("no JVM signature for $fqn#$name")
                        Triple(name, descriptor, StableHash.of("$fqn#$name$descriptor"))
                    }
                    .sortedBy { it.third }
                    .forEach { (name, descriptor, id) ->
                        add(
                            "%T(methodId·=·%LL, name·=·%S, jvmDescriptor·=·%S),\n",
                            MethodDescriptor::class.asClassName(), id, name, descriptor,
                        )
                    }
                add("⇤)),\n")
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
            .build()

        val sources = Dependencies(true, *contracts.mapNotNull { it.containingFile }.toTypedArray())

        FileSpec.builder(GENERATED_PACKAGE, moduleName)
            .addType(moduleType)
            .build()
            .writeTo(codeGenerator, sources)

        codeGenerator.createNewFileByPath(sources, "META-INF/services/civictech.gen.wire.ContractModule", "")
            .bufferedWriter()
            .use { it.write("$GENERATED_PACKAGE.$moduleName\n") }

        return emptyList()
    }

    companion object {
        const val GENERATED_PACKAGE = "civictech.gen.wire.generated"
    }
}
