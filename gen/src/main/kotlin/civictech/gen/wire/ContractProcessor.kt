package civictech.gen.wire

import civictech.nature.CellColor
import civictech.nature.CellDescriptor
import civictech.nature.Color
import civictech.nature.ContractDescriptor
import civictech.nature.ContractModule
import civictech.nature.InstanceScoping
import civictech.nature.JvmDescriptors
import civictech.nature.Manifest
import civictech.nature.MergeClass
import civictech.nature.MethodDescriptor
import civictech.nature.Monotonicity
import civictech.nature.NatureVector
import civictech.nature.Ownership
import civictech.nature.PortDescriptor
import civictech.nature.PortDirection
import civictech.nature.ProtocolDescriptor
import civictech.nature.ProtocolDirection
import civictech.nature.StableHash
import civictech.nature.WaveParticipation
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
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
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

// T09 §D: top-level (file-scope, not class members) so both [ContractProcessor]
// and [ContractLints] can call them without threading an instance through.

/**
 * Ownership bit (spec 23, G-21 phase 2): does the type **reach** an `Owned`/`Leased`
 * anywhere?
 *
 * "Reach", not "mention": the scan walks the type's own declaration, its type *arguments*,
 * and — since the 93 I-6 / I-8 widening (`doc/spec/10-programming-model/12-ports.md`,
 * "the exclusive bit's KSP scan is decided to widen"; C-11 residual 1, computenet-ulss) —
 * the declared **properties** of a payload class. A parameter that is a plain data class
 * with an `Owned` field used to be invisible here, so no consumer of the bit (link
 * handshake, suppression proxy, ADMIT accounting) saw the exclusive at all and a
 * shadow-suppressed sink dropped it silently.
 *
 * Termination and cost: [seen] holds the fully-qualified names already under
 * consideration, so a self-referential payload (`data class Node(val next: Node?)`) ends
 * rather than recursing forever, and each declaration is opened at most once per query.
 * Platform types are not opened at all — an exclusive is a kernel type, and it can only sit
 * inside a `kotlin.*`/`java.*` container through a type argument, which the argument walk
 * above already covers.
 */
private fun carriesExclusive(
    type: com.google.devtools.ksp.symbol.KSType,
    seen: MutableSet<String> = mutableSetOf(),
): Boolean {
    val fqn = type.declaration.qualifiedName?.asString()
    if (fqn in ContractProcessor.Companion.KernelFqn.EXCLUSIVE_MARKERS) return true
    if (type.arguments.any { it.type?.resolve()?.let { argument -> carriesExclusive(argument, seen) } == true }) {
        return true
    }
    if (fqn == null || isPlatformType(fqn) || !seen.add(fqn)) return false
    val declaration = type.declaration as? KSClassDeclaration ?: return false
    return declaration.getAllProperties().any { property ->
        carriesExclusive(property.type.resolve(), seen)
    }
}

/** Declarations the property walk of [carriesExclusive] must not open — see its KDoc. */
private fun isPlatformType(fqn: String): Boolean =
    fqn.startsWith("kotlin.") || fqn.startsWith("java.") || fqn.startsWith("javax.") ||
        fqn.startsWith("jdk.") || fqn.startsWith("sun.")

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

/**
 * T09 §D: the five inline diagnostics `ContractProcessor.process()` ran while
 * walking `@Contract`/`@Protocol` interfaces, extracted so each is
 * independently unit-testable without driving the full two-round processor.
 * Each lint re-derives what it needs from [contract] itself (its `@Contract`/
 * `@Protocol` annotations, its abstract functions) rather than being handed
 * precomputed state from the table builders that scan the same interface for
 * codegen — the two concerns (validate vs. emit) no longer share a pass.
 */
@OptIn(KspExperimental::class)
internal object ContractLints {

    /** Push-only lint (spec 12, G-11 completed M9.1): a data contract's methods must return Unit. */
    fun pushOnlyReturns(contract: KSClassDeclaration, logger: KSPLogger) {
        val management = contract.getAnnotationsByType(Contract::class).first().management
        if (management) return
        val fqn = contract.qualifiedName!!.asString()
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

    /** At most one `@Key` routing parameter per method. */
    fun singleKeyParameter(contract: KSClassDeclaration, logger: KSPLogger) {
        val fqn = contract.qualifiedName!!.asString()
        contract.getAllFunctions().filter { it.isAbstract }.forEach { fn ->
            val name = fn.simpleName.asString()
            val keyCount = fn.parameters.count { p ->
                p.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == ContractProcessor.KEY_ANNOTATION
                }
            }
            if (keyCount > 1) logger.error("data contract $fqn#$name has more than one @Key parameter", fn)
        }
    }

    /** An exclusive (`Owned`/`Leased`) payload must route through a `@Key` parameter (G-21). */
    fun exclusiveRequiresKey(contract: KSClassDeclaration, logger: KSPLogger) {
        val fqn = contract.qualifiedName!!.asString()
        contract.getAllFunctions().filter { it.isAbstract }.forEach { fn ->
            val name = fn.simpleName.asString()
            val exclusive = fn.parameters.any { carriesExclusive(it.type.resolve()) }
            val hasKey = fn.parameters.any { p ->
                p.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == ContractProcessor.KEY_ANNOTATION
                }
            }
            if (exclusive && !hasKey) {
                logger.error(
                    "data contract $fqn#$name broadcasts an exclusive Owned/Leased payload — annotate its routing parameter with @Key",
                    fn,
                )
            }
        }
    }

    /** A `@Protocol` contract must be management-class (spec 12). */
    fun protocolIsManagement(contract: KSClassDeclaration, logger: KSPLogger) {
        if (contract.getAnnotationsByType(Protocol::class).firstOrNull() == null) return
        val management = contract.getAnnotationsByType(Contract::class).first().management
        if (!management) {
            logger.error(
                "protocol contract ${contract.qualifiedName!!.asString()} must be management-class (spec 12)",
                contract,
            )
        }
    }

    /** A `@Protocol` contract's methods must be push-only and carry no `Owned`/`Leased` payload. */
    fun protocolMethodShape(contract: KSClassDeclaration, logger: KSPLogger) {
        if (contract.getAnnotationsByType(Protocol::class).firstOrNull() == null) return
        val fqn = contract.qualifiedName!!.asString()
        contract.getAllFunctions().filter { it.isAbstract }.forEach { fn ->
            val returns = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString()
            if (returns != null && returns != "kotlin.Unit") {
                logger.error("protocol contract $fqn#${fn.simpleName.asString()} must be push-only", fn)
            }
            if (fn.parameters.any { carriesExclusive(it.type.resolve()) }) {
                logger.error("protocol contract $fqn#${fn.simpleName.asString()} must not carry Owned/Leased", fn)
            }
        }
    }
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
    private var basesGenerated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (emitted) return emptyList()

        // Round 1, when @CellBase interfaces exist: generate ONLY the base
        // classes, so the next round can resolve cells extending them —
        // otherwise those subclasses' supertypes are error types and the cell
        // scan (descriptors, Ports ids, proxies) misses them entirely.
        if (!basesGenerated) {
            basesGenerated = true
            val cellBases = resolver.getSymbolsWithAnnotation(CellBase::class.qualifiedName!!)
                .filterIsInstance<KSClassDeclaration>()
                .sortedBy { it.qualifiedName!!.asString() }
                .toList()
            cellBases.filter { it.classKind != ClassKind.INTERFACE }.forEach {
                logger.error("@CellBase targets the cell's Api interface; ${it.qualifiedName?.asString()} is not an interface", it)
            }
            val ifaces = cellBases.filter { it.classKind == ClassKind.INTERFACE }
            if (ifaces.isNotEmpty()) {
                val baseSources = Dependencies(true, *ifaces.mapNotNull { it.containingFile }.distinct().toTypedArray())
                ifaces.forEach { generateCellBase(it, baseSources) }
                return emptyList() // emit tables next round, with the bases resolvable
            }
        }

        // Emission round. @Contract interfaces are collected by file walk, not
        // getSymbolsWithAnnotation: in a second round the original sources are
        // no longer "new files", but getAllFiles still sees them.
        val contracts = resolver.getAllFiles()
            .flatMap { file -> file.declarations.flatMap(::classesIn) }
            .filter { decl ->
                decl.classKind == ClassKind.INTERFACE && decl.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == Contract::class.qualifiedName
                }
            }
            .sortedBy { it.qualifiedName!!.asString() }
            .toList()

        val cellType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(KernelFqn.CELL_MARKER))
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
        val cellPorts: Map<KSClassDeclaration, List<ScannedPort>> = cells.associateWith(::scanPorts)
        val moduleName = "ContractTable_" + java.lang.Long.toHexString(moduleHash)
        logger.info("ContractProcessor: ${contracts.size} contracts -> $GENERATED_PACKAGE.$moduleName")

        val moduleType = TypeSpec.classBuilder(moduleName)
            .addSuperinterface(ContractModule::class.asClassName())
            .addProperty(
                PropertySpec.builder(
                    "contracts",
                    LIST.parameterizedBy(ContractDescriptor::class.asClassName()),
                    KModifier.OVERRIDE,
                ).initializer(contractTable(contracts, resolver)).build()
            )
            .addProperty(
                PropertySpec.builder(
                    "cells", LIST.parameterizedBy(CellDescriptor::class.asClassName()), KModifier.OVERRIDE,
                ).initializer(cellTable(cells, cellPorts)).build()
            )
            .addProperty(
                PropertySpec.builder("protocols", LIST.parameterizedBy(ProtocolDescriptor::class.asClassName()), KModifier.OVERRIDE)
                    .initializer(protocolTable(contracts)).build()
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

        codeGenerator.createNewFileByPath(sources, "META-INF/services/civictech.nature.ContractModule", "")
            .bufferedWriter()
            .use { it.write("$GENERATED_PACKAGE.$moduleName\n") }

        // C-5 completion (W4.6, spec 10/14 §Reflection budget): one KSP-generated
        // proxy class per contract, replacing java.lang.reflect.Proxy.newProxyInstance
        // for in-process cell API dispatch. Each generated class still dispatches
        // through the existing java.lang.reflect.InvocationHandler shape, so every
        // Proxy/Buffering/NoOp/Callback/HostProxy/MediateProxy call site is
        // untouched — only proxy *construction* moves from runtime bytecode
        // generation to an ahead-of-time-compiled class.
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

        // Typed port ids (typed graph wiring, ref-only path): one `<CellName>Ports`
        // object per public top-level cell with scannable ports — name constants +
        // phantom-typed InletId/OutletId accessors. Generics survive erasure by
        // re-introducing the cell's type parameters as function type parameters.
        cells.filter {
            it.getVisibility() != com.google.devtools.ksp.symbol.Visibility.PRIVATE &&
                it.getVisibility() != com.google.devtools.ksp.symbol.Visibility.LOCAL &&
                it.parentDeclaration == null && // nested cells: simple-name collisions, skipped in v1
                com.google.devtools.ksp.symbol.Modifier.ABSTRACT !in it.modifiers // bases can't spawn; ids belong to concrete cells
        }.forEach { cell ->
            val ports = cellPorts[cell].orEmpty()
            if (ports.isNotEmpty()) generatePortsObject(cell, ports, sources)
        }

        return emptyList()
    }

    /**
     * The `contracts: List<ContractDescriptor>` table (T09 §D extraction):
     * one row per `@Contract` interface, methods sorted by [MethodDescriptor.methodId].
     * Runs [ContractLints.pushOnlyReturns]/[ContractLints.singleKeyParameter]/
     * [ContractLints.exclusiveRequiresKey] per contract before emitting its rows —
     * validation and codegen no longer share a pass.
     */
    @OptIn(KspExperimental::class)
    private fun contractTable(contracts: List<KSClassDeclaration>, resolver: Resolver): CodeBlock = buildCodeBlock {
        add("listOf(\n⇥")
        contracts.forEach { contract ->
            ContractLints.pushOnlyReturns(contract, logger)
            ContractLints.singleKeyParameter(contract, logger)
            ContractLints.exclusiveRequiresKey(contract, logger)

            val fqn = contract.qualifiedName!!.asString()
            val management = contract.getAnnotationsByType(Contract::class).first().management
            val effect = contract.getAnnotationsByType(Contract::class).first().effect
            add(
                "%T(contractId·=·%LL, fqn·=·%S, management·=·%L, effect·=·%L, methods·=·listOf(\n⇥",
                ContractDescriptor::class.asClassName(), StableHash.of(fqn), fqn, management, effect,
            )
            contract.getAllFunctions().filter { it.isAbstract }
                .map { fn ->
                    val name = fn.simpleName.asString()
                    val descriptor = resolver.mapToJvmSignature(fn)
                        ?: error("no JVM signature for $fqn#$name")
                    val exclusive = fn.parameters.any { carriesExclusive(it.type.resolve()) }
                    val keyIndexes = fn.parameters.mapIndexedNotNull { index, parameter ->
                        index.takeIf { parameter.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == KEY_ANNOTATION } }
                    }
                    val keyIndex = keyIndexes.singleOrNull() ?: -1
                    MethodDescriptor(
                        StableHash.of("$fqn#$name$descriptor"), name, descriptor, exclusive,
                        fn.parameters.any { carriesMarker(it.type.resolve(), KernelFqn.MAGNITUDE_MARKER) },
                        fn.parameters.any { carriesMarker(it.type.resolve(), KernelFqn.REPLICABLE_MARKER) },
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

    /**
     * The `protocols: List<ProtocolDescriptor>` table (T09 §D extraction): one row
     * per `@Protocol`-annotated contract. Runs [ContractLints.protocolIsManagement]/
     * [ContractLints.protocolMethodShape] per protocol contract before emitting its row.
     */
    @OptIn(KspExperimental::class)
    private fun protocolTable(contracts: List<KSClassDeclaration>): CodeBlock = buildCodeBlock {
        add("listOf(\n⇥")
        contracts.forEach { contract ->
            val protocol = contract.getAnnotationsByType(Protocol::class).firstOrNull() ?: return@forEach
            ContractLints.protocolIsManagement(contract, logger)
            ContractLints.protocolMethodShape(contract, logger)
            val fqn = contract.qualifiedName!!.asString()
            add("%T(%S, %LL, %T.%L, %L),\n",
                ProtocolDescriptor::class.asClassName(), protocol.id, StableHash.of(fqn),
                ProtocolDirection::class.asClassName(), protocol.direction.name,
                protocol.band)
        }
        add("⇤)")
    }

    /**
     * The `cells: List<CellDescriptor>` table (T09 §D extraction): one row per
     * scanned `Cell` subclass, with its [portNatureLevels]-derived nature vectors
     * and [manifestOf]-derived structural [Manifest] tags.
     */
    private fun cellTable(
        cells: List<KSClassDeclaration>,
        cellPorts: Map<KSClassDeclaration, List<ScannedPort>>,
    ): CodeBlock = buildCodeBlock {
        add("listOf(\n⇥")
        cells.forEach { cell ->
            val fqn = cell.qualifiedName!!.asString()
            val color = when {
                isSubtype(cell.asStarProjectedType(), KernelFqn.SUSPENDING_MARKER) -> CellColor.SUSPENDING
                isSubtype(cell.asStarProjectedType(), KernelFqn.BLOCKING_MARKER) -> CellColor.BLOCKING
                else -> CellColor.PURE
            }
            val ports = cellPorts[cell].orEmpty()
            val manifest = manifestOf(cell)
            // trailing `, manifest = setOf(...)` (omitted when empty ⇒ the default)
            val manifestSuffix: CodeBlock? = if (manifest.isEmpty()) null else buildCodeBlock {
                add(", manifest·=·setOf(")
                manifest.forEachIndexed { i, tag ->
                    if (i > 0) add(", ")
                    add("%T.%L", MANIFEST, tag)
                }
                add(")")
            }
            if (ports.isEmpty()) {
                add("%T(fqn·=·%S, color·=·%T.%L", CellDescriptor::class, fqn, CellColor::class, color.name)
                manifestSuffix?.let { add("%L", it) }
                add("),\n")
            } else {
                add(
                    "%T(fqn·=·%S, color·=·%T.%L, ports·=·listOf(\n⇥",
                    CellDescriptor::class, fqn, CellColor::class, color.name,
                )
                ports.forEach { p ->
                    val natures = portNatureLevels(cell, color, p)
                    if (natures.isEmpty()) {
                        add(
                            "%T(%S, %T.%L, %S, %LL),\n",
                            PortDescriptor::class.asClassName(), p.name,
                            PortDirection::class.asClassName(), p.direction.name,
                            p.contractFqn, StableHash.of(p.contractFqn),
                        )
                    } else {
                        add(
                            "%T(%S, %T.%L, %S, %LL, natures·=·%T.of(",
                            PortDescriptor::class.asClassName(), p.name,
                            PortDirection::class.asClassName(), p.direction.name,
                            p.contractFqn, StableHash.of(p.contractFqn),
                            NATURE_VECTOR,
                        )
                        natures.forEachIndexed { i, (levelClass, levelName) ->
                            if (i > 0) add(", ")
                            add("%T.%L", levelClass, levelName)
                        }
                        add(")),\n")
                    }
                }
                add("⇤)")
                manifestSuffix?.let { add("%L", it) }
                add("),\n")
            }
        }
        add("⇤)")
    }

    /**
     * One abstract `<Name>CellBase` per `@CellBase` Api interface (see
     * [CellBase] for the authoring contract and the v1 single-round ceiling).
     * Port property names mirror the interface's, so G-17 holds by
     * construction.
     */
    private fun generateCellBase(iface: KSClassDeclaration, sources: Dependencies) {
        val pkg = iface.packageName.asString()
        val baseName = iface.simpleName.asString().removeSuffix("Api") + "CellBase"
        val typeParamResolver = iface.typeParameters.toTypeParameterResolver()
        val typeVars = iface.typeParameters.map { it.toTypeVariableName(typeParamResolver) }
        val ifaceType =
            if (typeVars.isEmpty()) iface.toClassName() else iface.toClassName().parameterizedBy(typeVars)

        val builder = TypeSpec.classBuilder(baseName)
            .addKdoc("Generated from [%L]: ports declared + registered, inlets statically bound.", iface.qualifiedName!!.asString())
            .addModifiers(KModifier.ABSTRACT)
            .addTypeVariables(typeVars)
            .addSuperinterface(ifaceType)
            .addSuperinterface(KernelFqn.CELL_IFACE)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("ref", KernelFqn.CELL_REF)
                            .defaultValue("%T(%T.randomUUID())", KernelFqn.CELL_REF, JAVA_UUID)
                            .build()
                    )
                    .build()
            )
            .addProperty(PropertySpec.builder("ref", KernelFqn.CELL_REF, KModifier.OVERRIDE).initializer("ref").build())

        val init = CodeBlock.builder()
        iface.getAllProperties().forEach { prop ->
            val propType = runCatching { prop.type.resolve() }.getOrNull()?.takeUnless { it.isError }
            val roleFqn = propType?.declaration?.qualifiedName?.asString()
            val api = propType?.arguments?.firstOrNull()?.type?.resolve()?.takeUnless { it.isError }
            val name = prop.simpleName.asString()
            if (propType == null || api == null || roleFqn !in KernelFqn.PORT_ROLES) {
                // T09 §B: an unresolvable port Api type is not survivable — the
                // member stays abstract, so a concrete subclass either fails to
                // compile (good) or hand-implements it outside the generated-port
                // machinery (silently missing its descriptor row and static handler
                // binding). Loud, not a warning.
                if (roleFqn in KernelFqn.PORT_ROLES) logger.error(
                    "@CellBase ${iface.simpleName.asString()}.$name: unresolvable port Api type — left abstract", prop,
                )
                return@forEach // non-port members stay abstract for the subclass
            }
            val apiTypeName = api.toTypeName(typeParamResolver)
            when (roleFqn) {
                KernelFqn.SUBSCRIBE_ROLE -> builder.addProperty(
                    PropertySpec.builder(name, KernelFqn.FAN_OUTLET_CLASS.parameterizedBy(apiTypeName), KModifier.OVERRIDE)
                        .initializer("%M(%S, %T.create<%T>())", KernelFqn.REGISTER_PORT, name, KernelFqn.FAN_OUTLET_CLASS, apiTypeName)
                        .build()
                )

                else -> { // KernelFqn.SERVE_ROLE / KernelFqn.USE_ROLE: an inlet
                    builder.addProperty(
                        PropertySpec.builder(name, KernelFqn.FAN_INLET_CLASS.parameterizedBy(apiTypeName), KModifier.OVERRIDE)
                            .initializer("%M(%S, %T.create<%T>())", KernelFqn.REGISTER_PORT, name, KernelFqn.FAN_INLET_CLASS, apiTypeName)
                            .build()
                    )
                    if (api.declaration.qualifiedName?.asString() == KernelFqn.PROPAGATE_MARKER) {
                        val payload = api.arguments.firstOrNull()?.type?.resolve()
                        val payloadName = payload?.toTypeName(typeParamResolver)
                        if (payloadName == null) {
                            // T09 §B: an unresolvable Propagate<T> payload means the
                            // port is declared and registered but no on<Name>
                            // handler is generated or bound — the inlet accepts
                            // messages and silently drops every one. Loud, not a
                            // warning.
                            logger.error("@CellBase ${iface.simpleName.asString()}.$name: unresolvable payload — not auto-bound", prop)
                        } else {
                            val handler = "on" + name.replaceFirstChar { it.uppercase() }
                            builder.addFunction(
                                FunSpec.builder(handler)
                                    .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                                    .addParameter("value", payloadName)
                                    .build()
                            )
                            init.addStatement("%L.%M(this::%L)", name, KernelFqn.ON_EACH, handler)
                        }
                    } else {
                        val handler = name + "Handler"
                        builder.addFunction(
                            FunSpec.builder(handler)
                                .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                                .returns(apiTypeName)
                                .build()
                        )
                        init.addStatement("%L.serve(%L())", name, handler)
                    }
                }
            }
        }
        val initBlock = init.build()
        if (!initBlock.isEmpty()) builder.addInitializerBlock(initBlock)

        FileSpec.builder(pkg, baseName)
            .addType(builder.build())
            .build()
            .writeTo(codeGenerator, sources)
    }

    private data class ScannedPort(
        val name: String,
        val direction: PortDirection,
        val apiType: com.google.devtools.ksp.symbol.KSType,
        val contractFqn: String,
    )

    /**
     * Declared ports of a cell, by property scan (own + inherited). Direction
     * classifies by the CONCRETE port class only — the role projections
     * (`Use`/`Serve`/`Subscribe`) are used inconsistently across Api
     * interfaces and cannot encode direction reliably.
     */
    private fun scanPorts(cell: KSClassDeclaration): List<ScannedPort> =
        cell.getAllProperties().mapNotNull { prop ->
            // private backing ports (e.g. a port registered under a public
            // interface-property's name) are not the cell's public surface
            if (prop.getVisibility() == com.google.devtools.ksp.symbol.Visibility.PRIVATE) return@mapNotNull null
            val type = runCatching { prop.type.resolve() }.getOrNull()
                ?.takeUnless { it.isError } ?: return@mapNotNull null
            val direction = when {
                isSubtype(type, KernelFqn.FAN_INLET) || isSubtype(type, KernelFqn.INLET) -> PortDirection.IN
                isSubtype(type, KernelFqn.FAN_OUTLET) || isSubtype(type, KernelFqn.OUTLET) -> PortDirection.OUT
                isSubtype(type, KernelFqn.FEEDBACK_INLET) -> {
                    // its type argument is a payload, not a port Api contract
                    logger.info("port ${cell.simpleName.asString()}.${prop.simpleName.asString()}: FeedbackInlet skipped (no Api contract)", prop)
                    return@mapNotNull null
                }

                else -> return@mapNotNull null
            }
            val api = type.arguments.firstOrNull()?.type?.resolve()?.takeUnless { it.isError }
            val apiFqn = api?.declaration?.qualifiedName?.asString()
            if (api == null || apiFqn == null) {
                logger.warn(
                    "port ${cell.simpleName.asString()}.${prop.simpleName.asString()}: unresolvable Api type — skipped",
                    prop,
                )
                return@mapNotNull null
            }
            ScannedPort(prop.simpleName.asString(), direction, api, apiFqn)
        }.toList()

    /** One `<CellName>Ports` object: name constants + typed InletId/OutletId accessors. */
    private fun generatePortsObject(cell: KSClassDeclaration, ports: List<ScannedPort>, sources: Dependencies) {
        val pkg = cell.packageName.asString()
        val objName = cell.simpleName.asString() + "Ports"
        val typeParamResolver = cell.typeParameters.toTypeParameterResolver()
        val cellTypeVars = cell.typeParameters.map { it.toTypeVariableName(typeParamResolver) }

        val builder = TypeSpec.objectBuilder(objName)
            .addKdoc(
                "Typed port ids of [%L] — generated; names mirror the port properties (G-17).",
                cell.qualifiedName!!.asString(),
            )
        ports.forEach { p ->
            val nameConst = p.name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
            builder.addProperty(
                PropertySpec.builder(nameConst, STRING, KModifier.CONST).initializer("%S", p.name).build()
            )
            val idClass = if (p.direction == PortDirection.IN) KernelFqn.INLET_ID else KernelFqn.OUTLET_ID
            // T09 §B: audited but left as a warning, unlike the two @CellBase
            // paths above. `p.apiType` already passed scanPorts's non-error,
            // non-null check, so this guards KotlinPoet-ksp's KSType.toTypeName()
            // itself: its only failure branches are (a) a KSTypeParameter lookup
            // miss — structurally unreached here, since cell.typeParameters is the
            // same resolver this call uses, and KSP's getAllProperties() already
            // member-substitutes inherited generic properties — and (b) an
            // `else -> error(...)` for declaration kinds no ordinary Kotlin/Java
            // JVM source produces. No compileable fixture (star projections,
            // F-bounded Java generics, inherited-generic properties) reached this
            // catch in testing; kept defensive rather than promoted or deleted.
            val apiTypeName = try {
                p.apiType.toTypeName(typeParamResolver)
            } catch (e: Exception) {
                logger.warn("port ${cell.simpleName.asString()}.${p.name}: Api type not expressible — accessor skipped", cell)
                return@forEach
            }
            val idType = idClass.parameterizedBy(apiTypeName)
            if (cellTypeVars.isEmpty()) {
                builder.addProperty(
                    PropertySpec.builder(p.name, idType).initializer("%T(%L)", idClass, nameConst).build()
                )
            } else {
                builder.addFunction(
                    FunSpec.builder(p.name)
                        .addTypeVariables(cellTypeVars)
                        .returns(idType)
                        .addStatement("return %T(%L)", idClass, nameConst)
                        .build()
                )
            }
        }
        FileSpec.builder(pkg, objName)
            .addType(builder.build())
            .build()
            .writeTo(codeGenerator, sources)
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

    /**
     * The non-default nature levels of one port, folded from cell-level markers
     * (mirroring the [CellColor] scan) and the port's own contract (CP-F2). Each
     * pair is (generated level enum, constant name); an empty list ⇒
     * [NatureVector.DEFAULT] ⇒ today's behavior. Sparse by construction: only
     * a level *stronger* than the axis default is emitted.
     *
     * - COLOR         — cell implements `BlockingCell`/`SuspendingCell` (reuses [color]).
     * - MERGE_IDEMPOTENCE — cell implements `Replicable` (idempotent-merge class).
     * - OWNERSHIP     — the port's Api contract carries an `Owned`/`Leased` param.
     * - MONOTONICITY  — the port's Api contract carries a `Magnitude` payload.
     */
    private fun portNatureLevels(
        cell: KSClassDeclaration,
        color: CellColor,
        port: ScannedPort,
    ): List<Pair<ClassName, String>> {
        val levels = mutableListOf<Pair<ClassName, String>>()
        // cell-level color — same markers the CellColor scan reads, folded onto
        // every port of the cell (per-CELL nature reaching the port vector).
        if (color != CellColor.PURE) levels += NATURE_COLOR to color.name
        // cell-level merge class: a Replicable cell declares idempotent merge.
        if (isSubtype(cell.asStarProjectedType(), KernelFqn.REPLICABLE_MARKER)) {
            levels += NATURE_MERGE to "IDEMPOTENT"
        }
        // per-port axes read off the port's Api contract methods.
        val api = port.apiType.declaration as? KSClassDeclaration
        if (api != null) {
            val abstractFns = api.getAllFunctions().filter { it.isAbstract }.toList()
            if (abstractFns.any { fn -> fn.parameters.any { carriesExclusive(it.type.resolve()) } }) {
                levels += NATURE_OWNERSHIP to "EXCLUSIVE"
            }
            if (abstractFns.any { fn -> fn.parameters.any { carriesMarker(it.type.resolve(), KernelFqn.MAGNITUDE_MARKER) } }) {
                levels += NATURE_MONOTONICITY to "MONOTONE"
            }
        }
        // PN-12 refusing-axis OFFERS. Stamped on OUT ports only: a stamp is a
        // *requirement* when read off a consumer inlet and an *offer* when read
        // off a producer outlet, and offering a stronger level never refuses a
        // link (reconcile checks `offered.rank < required.rank`). Keeping these
        // off inlets is what preserves today's behavior verbatim — the exchange
        // demo's durable→volatile links never acquire a new requirement.
        if (port.direction == PortDirection.OUT) {
            // WAVED: a glitch-free cell emits aligned waves on its outlets.
            if (isSubtype(cell.asStarProjectedType(), KernelFqn.GLITCH_FREE_MARKER)) {
                levels += NATURE_WAVE to "WAVED"
            }
            // INTEREST_SCOPED: the outlet carries a `Scoped` delta the linker can slice.
            if (api != null && carriesMarker(port.apiType, KernelFqn.SCOPED_MARKER)) {
                levels += NATURE_SCOPING to "INTEREST_SCOPED"
            }
        }
        return levels
    }

    /**
     * PN-12 — the structural [Manifest] natures of a cell, from the marker
     * interfaces it implements (the same supertype scan [portNatureLevels] and
     * the [CellColor] scan use). Mirrors the runtime `manifestOf` in :kernel;
     * `ManifestDriftTest` pins the two agree.
     */
    private fun manifestOf(cell: KSClassDeclaration): List<String> {
        val type = cell.asStarProjectedType()
        val tags = mutableListOf<String>()
        if (isSubtype(type, KernelFqn.GLITCH_FREE_MARKER)) tags += "GLITCH_FREE"
        if (isSubtype(type, KernelFqn.STATEFUL_MARKER)) tags += "DURABLE"
        if (isSubtype(type, KernelFqn.REPLICABLE_MARKER) || isSubtype(type, KernelFqn.REBASELINE_MARKER)) tags += "REPLICATED"
        if (isSubtype(type, KernelFqn.PARTITIONED_MARKER)) tags += "PARTITIONED"
        return tags
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

        /**
         * Kernel-side FQNs the processor scans for structurally, gathered in one
         * place because `:gen` cannot depend on `:kernel` (the dependency runs
         * the other way) — these can't be compile-checked `X::class` references
         * here, so a kernel-side rename silently desyncs them from the real
         * types. [ManifestDriftTest][civictech.cell.wire.ManifestDriftTest] (in
         * `:kernel`) is the drift guard: it asserts every registered cell's
         * declared [civictech.nature.Manifest] set equals the installed marker
         * scan, so a miss here fails loudly there instead of silently dropping
         * descriptors. Keep every kernel FQN string literal in this object —
         * nothing kernel-side belongs loose in the rest of the companion.
         */
        object KernelFqn {
            val EXCLUSIVE_MARKERS = setOf("civictech.cell.Owned", "civictech.cell.Leased")
            const val CELL_MARKER = "civictech.cell.Cell"

            // Port scan (typed port ids + PortDescriptor emission)
            const val FAN_INLET = "civictech.cell.port.FanInlet"
            const val INLET = "civictech.cell.port.Inlet"
            const val FEEDBACK_INLET = "civictech.cell.port.FeedbackInlet"
            const val FAN_OUTLET = "civictech.cell.port.FanOutlet"
            const val OUTLET = "civictech.cell.port.Outlet"
            val INLET_ID: ClassName = ClassName("civictech.cell.graph", "InletId")
            val OUTLET_ID: ClassName = ClassName("civictech.cell.graph", "OutletId")

            // @CellBase generation
            const val SERVE_ROLE = "civictech.cell.port.Serve"
            const val USE_ROLE = "civictech.cell.port.Use"
            const val SUBSCRIBE_ROLE = "civictech.cell.port.Subscribe"
            val PORT_ROLES = setOf(SERVE_ROLE, USE_ROLE, SUBSCRIBE_ROLE)
            const val PROPAGATE_MARKER = "civictech.cell.Propagate"
            val CELL_IFACE: ClassName = ClassName("civictech.cell", "Cell")
            val CELL_REF: ClassName = ClassName("civictech.cell", "CellRef")
            val FAN_INLET_CLASS: ClassName = ClassName("civictech.cell.port", "FanInlet")
            val FAN_OUTLET_CLASS: ClassName = ClassName("civictech.cell.port", "FanOutlet")
            val REGISTER_PORT = MemberName("civictech.cell.port", "registerPort")
            val ON_EACH = MemberName("civictech.cell", "onEach")
            const val MAGNITUDE_MARKER = "civictech.cell.control.Magnitude"
            const val REPLICABLE_MARKER = "civictech.cell.data.Replicable"
            const val BLOCKING_MARKER = "civictech.cell.BlockingCell"
            const val SUSPENDING_MARKER = "civictech.cell.SuspendingCell"

            // PN-12 markers for the two refusing axes + the CellManifest scan (no
            // new annotations: every one is an existing marker interface / `Scoped`).
            const val SCOPED_MARKER = "civictech.cell.link.Scoped"
            const val STATEFUL_MARKER = "civictech.cell.Stateful"
            const val REBASELINE_MARKER = "civictech.cell.ReBaselineEmitting"
            const val GLITCH_FREE_MARKER = "civictech.cell.consistency.GlitchFree"
            const val PARTITIONED_MARKER = "civictech.cell.partition.Partitioned"
        }

        // :gen's own identity — same module, compile-checked.
        val KEY_ANNOTATION: String = Key::class.qualifiedName!!

        val JAVA_UUID: ClassName = ClassName("java.util", "UUID")

        // Proxy generation (W4.6, C-5 completion) — same module, compile-checked
        // where `::class` can target the type. [ProxyConstructor] is a typealias
        // for a function type (`(InvocationHandler) -> Any`), which has no
        // `::class` of its own, so it stays a literal `ClassName`.
        val INVOCATION_HANDLER: ClassName = ClassName("java.lang.reflect", "InvocationHandler")
        val METHOD: ClassName = ClassName("java.lang.reflect", "Method")
        val CLASS_STAR = ClassName("java.lang", "Class").parameterizedBy(STAR)
        val PROXY_MODULE: ClassName = ProxyModule::class.asClassName()
        val PROXY_CONSTRUCTOR: ClassName = ClassName("civictech.gen.wire", "ProxyConstructor")

        // Nature scan (CP-F2): generated level-enum references, folded into
        // PortDescriptor.natures. These live in :nature (shared by :gen and
        // :kernel) — visible to :gen, so compile-checked via `::class` rather
        // than a hand-typed FQN string.
        val NATURE_VECTOR: ClassName = NatureVector::class.asClassName()
        val NATURE_COLOR: ClassName = Color::class.asClassName()
        val NATURE_MERGE: ClassName = MergeClass::class.asClassName()
        val NATURE_OWNERSHIP: ClassName = Ownership::class.asClassName()
        val NATURE_MONOTONICITY: ClassName = Monotonicity::class.asClassName()
        val NATURE_WAVE: ClassName = WaveParticipation::class.asClassName()
        val NATURE_SCOPING: ClassName = InstanceScoping::class.asClassName()
        val MANIFEST: ClassName = Manifest::class.asClassName()
    }
}
