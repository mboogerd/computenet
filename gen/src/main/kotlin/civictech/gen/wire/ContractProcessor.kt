package civictech.gen.wire

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getVisibility
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
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
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

        // C-5 completion (W4.6, spec 10/14 §Reflection budget): one KSP-generated
        // proxy class per contract, replacing java.lang.reflect.Proxy.newProxyInstance
        // for in-process cell API dispatch. Each generated class still dispatches
        // through the existing java.lang.reflect.InvocationHandler shape, so every
        // Proxy/Buffering/Broadcast/NoOp/Throwing/Callback call site is untouched —
        // only proxy *construction* moves from runtime bytecode generation to an
        // ahead-of-time-compiled class.
        // Proxy generation needs to *reference* the contract type (and its
        // method signatures) from generated code, unlike the reflective
        // descriptor table above — so file-private/local test fixtures (not
        // real cell API surface) are skipped here; they still get ordinary
        // ContractDescriptor entries.
        val proxyableContracts = contracts.filter {
            it.getVisibility() != com.google.devtools.ksp.symbol.Visibility.PRIVATE &&
                it.getVisibility() != com.google.devtools.ksp.symbol.Visibility.LOCAL
        }

        if (proxyableContracts.isNotEmpty()) {
            val proxyEntries = proxyableContracts.map { contract -> generateProxyClass(contract, resolver, sources) }

            val proxyModuleName = "ProxyTable_" + java.lang.Long.toHexString(moduleHash)
            val factoriesInit = buildCodeBlock {
                add("mapOf(\n⇥")
                proxyEntries.forEach { (contractClassName, _, constructedType) ->
                    add("%T::class.java·to·{·h·:·%T·->·%T(h)·},\n", contractClassName, INVOCATION_HANDLER, constructedType)
                }
                add("⇤)")
            }
            val proxyModuleType = TypeSpec.classBuilder(proxyModuleName)
                .addSuperinterface(PROXY_MODULE)
                .addProperty(
                    PropertySpec.builder(
                        "factories",
                        MAP.parameterizedBy(CLASS_STAR, PROXY_CONSTRUCTOR),
                        KModifier.OVERRIDE,
                    ).initializer(factoriesInit).build()
                )
                .build()

            FileSpec.builder(GENERATED_PACKAGE, proxyModuleName)
                .addType(proxyModuleType)
                .build()
                .writeTo(codeGenerator, sources)

            codeGenerator.createNewFileByPath(sources, "META-INF/services/civictech.gen.wire.ProxyModule", "")
                .bufferedWriter()
                .use { it.write("$GENERATED_PACKAGE.$proxyModuleName\n") }
        }

        return emptyList()
    }

    /**
     * Emits one proxy class implementing [contract], dispatching every abstract
     * method through the [InvocationHandler] passed to its constructor — the
     * same shape `java.lang.reflect.Proxy.newProxyInstance` provided, minus the
     * runtime bytecode generation. Returns the contract's raw [ClassName] paired
     * with the generated proxy's [ClassName] for the factory table entry.
     */
    @OptIn(KspExperimental::class)
    private fun generateProxyClass(
        contract: KSClassDeclaration,
        resolver: Resolver,
        sources: Dependencies,
    ): Triple<ClassName, ClassName, com.squareup.kotlinpoet.TypeName> {
        val fqn = contract.qualifiedName!!.asString()
        val contractClassName = contract.toClassName()
        val classTypeParamResolver = contract.typeParameters.toTypeParameterResolver()
        val classTypeVars = contract.typeParameters.map { it.toTypeVariableName(classTypeParamResolver) }
        val contractType = if (classTypeVars.isEmpty()) contractClassName else contractClassName.parameterizedBy(classTypeVars)
        // Erased witness type arguments (the constructor never references T, so
        // nothing constrains inference at the call site — supply each type
        // variable's own upper bound explicitly instead).
        val witnessTypeArgs = classTypeVars.map { it.bounds.firstOrNull() ?: ANY.copy(nullable = true) }

        val proxyName = contract.simpleName.asString() + "_Proxy_" + java.lang.Long.toHexString(StableHash.of(fqn))
        val proxyClassName = ClassName(GENERATED_PACKAGE, proxyName)

        val methods = contract.getAllFunctions().filter { it.isAbstract }
            .map { fn ->
                val name = fn.simpleName.asString()
                val jvmDescriptor = resolver.mapToJvmSignature(fn) ?: error("no JVM signature for $fqn#$name")
                Triple(fn, name, jvmDescriptor)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))

        val methodProps = mutableListOf<PropertySpec>()
        val funSpecs = mutableListOf<FunSpec>()

        methods.forEachIndexed { index, (fn, name, jvmDescriptor) ->
            val methodPropName = "M$index"
            methodProps += PropertySpec.builder(methodPropName, METHOD, KModifier.PRIVATE)
                .initializer(
                    "TARGET.methods.first·{·it.name·==·%S·&&·%T.of(it)·==·%S·}",
                    name, JvmDescriptors::class.asClassName(), jvmDescriptor,
                )
                .build()

            val fnTypeParamResolver = fn.typeParameters.toTypeParameterResolver(classTypeParamResolver)
            val fnTypeVars = fn.typeParameters.map { it.toTypeVariableName(fnTypeParamResolver) }
            val params = fn.parameters.mapIndexed { i, p ->
                ParameterSpec.builder(p.name?.asString() ?: "arg$i", p.type.toTypeName(fnTypeParamResolver)).build()
            }
            val returnType = fn.returnType?.toTypeName(fnTypeParamResolver) ?: UNIT
            // Explicit `Any?` witness: `arrayOf` needs a materializable element
            // type, and the parameter type may be an unreified class/method type
            // variable (T, E, ...) — erased anyway once past the InvocationHandler.
            val argsExpr = if (params.isEmpty()) "null" else params.joinToString(", ", "arrayOf<Any?>(", ")") { it.name }

            val body = if (returnType == UNIT) {
                CodeBlock.of("handler.invoke(this,·%L,·%L)\n", methodPropName, argsExpr)
            } else {
                CodeBlock.of(
                    "@Suppress(\"UNCHECKED_CAST\")\nreturn handler.invoke(this,·%L,·%L)·as·%T\n",
                    methodPropName, argsExpr, returnType,
                )
            }

            funSpecs += FunSpec.builder(name)
                .addModifiers(KModifier.OVERRIDE)
                .addTypeVariables(fnTypeVars)
                .addParameters(params)
                .returns(returnType)
                .addCode(body)
                .build()
        }

        val companion = TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("TARGET", CLASS_STAR, KModifier.PRIVATE)
                    .initializer("%T::class.java", contractClassName)
                    .build()
            )
            .addProperties(methodProps)
            .build()

        val proxyType = TypeSpec.classBuilder(proxyName)
            .addTypeVariables(classTypeVars)
            .addSuperinterface(contractType)
            .primaryConstructor(
                FunSpec.constructorBuilder().addParameter("handler", INVOCATION_HANDLER).build()
            )
            .addProperty(
                PropertySpec.builder("handler", INVOCATION_HANDLER, KModifier.PRIVATE)
                    .initializer("handler")
                    .build()
            )
            .addFunctions(funSpecs)
            .addType(companion)
            .build()

        FileSpec.builder(GENERATED_PACKAGE, proxyName)
            .addType(proxyType)
            .build()
            .writeTo(codeGenerator, sources)

        val constructedType: com.squareup.kotlinpoet.TypeName =
            if (witnessTypeArgs.isEmpty()) proxyClassName else proxyClassName.parameterizedBy(witnessTypeArgs)
        return Triple(contractClassName, proxyClassName, constructedType)
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

        // Proxy generation (W4.6, C-5 completion)
        val INVOCATION_HANDLER: ClassName = ClassName("java.lang.reflect", "InvocationHandler")
        val METHOD: ClassName = ClassName("java.lang.reflect", "Method")
        val CLASS_STAR = ClassName("java.lang", "Class").parameterizedBy(STAR)
        val PROXY_MODULE: ClassName = ClassName("civictech.gen.wire", "ProxyModule")
        val PROXY_CONSTRUCTOR: ClassName = ClassName("civictech.gen.wire", "ProxyConstructor")
    }
}
