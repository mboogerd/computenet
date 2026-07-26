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
        val cellPorts: Map<KSClassDeclaration, List<ScannedPort>> = cells.associateWith(::scanPorts)
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
            .addSuperinterface(CELL_IFACE)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("ref", CELL_REF)
                            .defaultValue("%T(%T.randomUUID())", CELL_REF, JAVA_UUID)
                            .build()
                    )
                    .build()
            )
            .addProperty(PropertySpec.builder("ref", CELL_REF, KModifier.OVERRIDE).initializer("ref").build())

        val init = CodeBlock.builder()
        iface.getAllProperties().forEach { prop ->
            val propType = runCatching { prop.type.resolve() }.getOrNull()?.takeUnless { it.isError }
            val roleFqn = propType?.declaration?.qualifiedName?.asString()
            val api = propType?.arguments?.firstOrNull()?.type?.resolve()?.takeUnless { it.isError }
            val name = prop.simpleName.asString()
            if (propType == null || api == null || roleFqn !in PORT_ROLES) {
                if (roleFqn in PORT_ROLES) logger.warn(
                    "@CellBase ${iface.simpleName.asString()}.$name: unresolvable port Api type — left abstract", prop,
                )
                return@forEach // non-port members stay abstract for the subclass
            }
            val apiTypeName = api.toTypeName(typeParamResolver)
            when (roleFqn) {
                SUBSCRIBE_ROLE -> builder.addProperty(
                    PropertySpec.builder(name, FAN_OUTLET_CLASS.parameterizedBy(apiTypeName), KModifier.OVERRIDE)
                        .initializer("%M(%S, %T.create<%T>())", REGISTER_PORT, name, FAN_OUTLET_CLASS, apiTypeName)
                        .build()
                )

                else -> { // SERVE_ROLE / USE_ROLE: an inlet
                    builder.addProperty(
                        PropertySpec.builder(name, FAN_INLET_CLASS.parameterizedBy(apiTypeName), KModifier.OVERRIDE)
                            .initializer("%M(%S, %T.create<%T>())", REGISTER_PORT, name, FAN_INLET_CLASS, apiTypeName)
                            .build()
                    )
                    if (api.declaration.qualifiedName?.asString() == PROPAGATE_MARKER) {
                        val payload = api.arguments.firstOrNull()?.type?.resolve()
                        val payloadName = payload?.toTypeName(typeParamResolver)
                        if (payloadName == null) {
                            logger.warn("@CellBase ${iface.simpleName.asString()}.$name: unresolvable payload — not auto-bound", prop)
                        } else {
                            val handler = "on" + name.replaceFirstChar { it.uppercase() }
                            builder.addFunction(
                                FunSpec.builder(handler)
                                    .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                                    .addParameter("value", payloadName)
                                    .build()
                            )
                            init.addStatement("%L.%M(this::%L)", name, ON_EACH, handler)
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
                isSubtype(type, FAN_INLET) || isSubtype(type, INLET) -> PortDirection.IN
                isSubtype(type, FAN_OUTLET) || isSubtype(type, OUTLET) -> PortDirection.OUT
                isSubtype(type, FEEDBACK_INLET) -> {
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
            val idClass = if (p.direction == PortDirection.IN) INLET_ID else OUTLET_ID
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
        if (isSubtype(cell.asStarProjectedType(), REPLICABLE_MARKER)) {
            levels += NATURE_MERGE to "IDEMPOTENT"
        }
        // per-port axes read off the port's Api contract methods.
        val api = port.apiType.declaration as? KSClassDeclaration
        if (api != null) {
            val abstractFns = api.getAllFunctions().filter { it.isAbstract }.toList()
            if (abstractFns.any { fn -> fn.parameters.any { carriesExclusive(it.type.resolve()) } }) {
                levels += NATURE_OWNERSHIP to "EXCLUSIVE"
            }
            if (abstractFns.any { fn -> fn.parameters.any { carriesMarker(it.type.resolve(), MAGNITUDE_MARKER) } }) {
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
            if (isSubtype(cell.asStarProjectedType(), GLITCH_FREE_MARKER)) {
                levels += NATURE_WAVE to "WAVED"
            }
            // INTEREST_SCOPED: the outlet carries a `Scoped` delta the linker can slice.
            if (api != null && carriesMarker(port.apiType, SCOPED_MARKER)) {
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
        if (isSubtype(type, GLITCH_FREE_MARKER)) tags += "GLITCH_FREE"
        if (isSubtype(type, STATEFUL_MARKER)) tags += "DURABLE"
        if (isSubtype(type, REPLICABLE_MARKER) || isSubtype(type, REBASELINE_MARKER)) tags += "REPLICATED"
        if (isSubtype(type, PARTITIONED_MARKER)) tags += "PARTITIONED"
        return tags
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
        const val PROPAGATE_MARKER = "civictech.cell.data.Propagate"
        val CELL_IFACE: ClassName = ClassName("civictech.cell", "Cell")
        val CELL_REF: ClassName = ClassName("civictech.cell", "CellRef")
        val JAVA_UUID: ClassName = ClassName("java.util", "UUID")
        val FAN_INLET_CLASS: ClassName = ClassName("civictech.cell.port", "FanInlet")
        val FAN_OUTLET_CLASS: ClassName = ClassName("civictech.cell.port", "FanOutlet")
        val REGISTER_PORT = MemberName("civictech.cell.port", "registerPort")
        val ON_EACH = MemberName("civictech.cell.data", "onEach")
        const val MAGNITUDE_MARKER = "civictech.cell.data.Magnitude"
        const val REPLICABLE_MARKER = "civictech.cell.data.Replicable"
        const val BLOCKING_MARKER = "civictech.cell.BlockingCell"
        const val SUSPENDING_MARKER = "civictech.cell.SuspendingCell"

        // PN-12 markers for the two refusing axes + the CellManifest scan (no new
        // annotations: every one is an existing marker interface / `Scoped`).
        const val SCOPED_MARKER = "civictech.cell.replication.Scoped"
        const val STATEFUL_MARKER = "civictech.cell.Stateful"
        const val REBASELINE_MARKER = "civictech.cell.ReBaselineEmitting"
        const val GLITCH_FREE_MARKER = "civictech.cell.consistency.GlitchFree"
        const val PARTITIONED_MARKER = "civictech.cell.data.Partitioned"

        // Nature scan (CP-F2): generated level-enum references, folded into
        // PortDescriptor.natures. These live in :gen alongside NatureVector.
        val NATURE_VECTOR = ClassName("civictech.gen.wire", "NatureVector")
        val NATURE_COLOR = ClassName("civictech.gen.wire", "Color")
        val NATURE_MERGE = ClassName("civictech.gen.wire", "MergeClass")
        val NATURE_OWNERSHIP = ClassName("civictech.gen.wire", "Ownership")
        val NATURE_MONOTONICITY = ClassName("civictech.gen.wire", "Monotonicity")
        val NATURE_WAVE = ClassName("civictech.gen.wire", "WaveParticipation")
        val NATURE_SCOPING = ClassName("civictech.gen.wire", "InstanceScoping")
        val MANIFEST = ClassName("civictech.gen.wire", "Manifest")

        // Proxy generation (W4.6, C-5 completion)
        val INVOCATION_HANDLER: ClassName = ClassName("java.lang.reflect", "InvocationHandler")
        val METHOD: ClassName = ClassName("java.lang.reflect", "Method")
        val CLASS_STAR = ClassName("java.lang", "Class").parameterizedBy(STAR)
        val PROXY_MODULE: ClassName = ClassName("civictech.gen.wire", "ProxyModule")
        val PROXY_CONSTRUCTOR: ClassName = ClassName("civictech.gen.wire", "ProxyConstructor")
    }
}
