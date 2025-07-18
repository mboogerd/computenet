package civictech.gen.async

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.io.OutputStream

class SerializerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        environment.logger.warn("SerializerProcessor instantiated")
        return SerializerProcessor(environment.codeGenerator, environment.logger)
    }
}

class SerializerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("SerializerProcessor started")
        val symbols = resolver.getSymbolsWithAnnotation("civictech.gen.async.GenerateSuspended")
        for (symbol in symbols.filterIsInstance<KSClassDeclaration>()) {
            if (symbol.classKind == ClassKind.INTERFACE) {
                val parameters = symbol.getAnnotationsByType(GenerateSuspended::class)
                    .first()
                    .parameters(symbol)
                logger.warn("Generating suspending interface with parameters $parameters")
                parameters.generateSuspendedInterfaceAndImpl()
            }
        }
        logger.info("SerializerProcessor done")
        return emptyList()
    }

    private fun GenerationParameters.generateSuspendedInterfaceAndImpl() {

        val derivedSuspends = DerivedSuspends::class.asClassName().parameterizedBy(sourceInterfaceClassName)

        val (suspendFns, implFns) = generateCompositeOperations()

        generateOperationFunctions(suspendFns, implFns)

        val suspendedInterface = generateSuspendedInterface(suspendFns, derivedSuspends)

        val implClass = generateImplClass(implFns)

        writeGeneratedFile(suspendedInterface, implClass)
    }

    private fun GenerationParameters.generateCompositeOperations(): Pair<MutableList<FunSpec>, MutableList<FunSpec>> {
        val suspendFns = mutableListOf<FunSpec>()
        val implFns = mutableListOf<FunSpec>()

        // Composite operation
        val blockOperationParamType = LambdaTypeName.get(
            receiver = sourceInterfaceClassName,
            returnType = Companion.anyName
        )
        suspendFns += FunSpec.builder(compositeOperationName)
            .addModifiers(KModifier.SUSPEND, KModifier.ABSTRACT)
            .addParameter(ParameterSpec.builder("block", blockOperationParamType).build())
            .build()

        val syncBlockQueryType = syncOp.parameterizedBy( sourceInterfaceClassName)
        implFns += FunSpec.builder(compositeOperationName)
            .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
            .addParameter(ParameterSpec.builder("block", blockOperationParamType).build())
            .addStatement("sender.operate(%T { it.block() })", syncBlockQueryType)
            .build()

        // Composite query
        val typeVariableR = TypeVariableName("R")
        val blockQueryParamType = LambdaTypeName.get(
            receiver = sourceInterfaceClassName,
            returnType = typeVariableR
        )
        suspendFns += FunSpec.builder(compositeQueryName)
            .addModifiers(KModifier.SUSPEND, KModifier.ABSTRACT)
            .addTypeVariable(typeVariableR)
            .addParameter(ParameterSpec.builder("block", blockQueryParamType).build())
            .returns(typeVariableR)
            .build()

        val asyncBlockQueryType = asyncQuery.parameterizedBy(sourceInterfaceClassName, typeVariableR)
        implFns += FunSpec.builder(compositeQueryName)
            .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
            .addTypeVariable(typeVariableR)
            .addParameter(ParameterSpec.builder("block", blockQueryParamType).build())
            .addStatement("return sender.query(%T { it.block() })", asyncBlockQueryType)
            .returns(typeVariableR)
            .build()

        return suspendFns to implFns
    }

    private fun GenerationParameters.generateOperationFunctions(
        suspendFns: MutableList<FunSpec>,
        implFns: MutableList<FunSpec>,
    ) {
        for (fn in sourceInterface.getAllFunctions().filter { it.isAbstract }) {
            val name = fn.simpleName.asString()
            val returnType = fn.returnType?.resolve()?.toTypeName() ?: UNIT
            val params = fn.parameters.map {
                ParameterSpec.builder(it.name?.asString() ?: "param", it.type.toTypeName()).build()
            }

            suspendFns += FunSpec.builder(name)
                .addModifiers(KModifier.SUSPEND, KModifier.ABSTRACT)
                .addParameters(params)
                .returns(returnType)
                .build()

            val implFn = FunSpec.builder(name)
                .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                .addParameters(params)
                .returns(returnType)

            val argList = params.joinToString(", ") { it.name }
            if (returnType == UNIT) {
                implFn.addStatement(
                    "sender.operate(%T { it.%L($argList) })",
                    syncOp.parameterizedBy(sourceInterfaceClassName),
                    name
                )
            } else if (fn.modifiers.contains(Modifier.SUSPEND)) {
                implFn.addStatement(
                    "return sender.query(%T { it.%L($argList) })",
                    asyncQuery.parameterizedBy(sourceInterfaceClassName, returnType),
                    name
                )
            } else {
                implFn.addStatement(
                    "return sender.query(%T { it.%L($argList) })",
                    syncQuery.parameterizedBy(sourceInterfaceClassName, returnType),
                    name
                )
            }

            implFns += implFn.build()
        }
    }

    private fun GenerationParameters.generateSuspendedInterface(
        suspendFns: List<FunSpec>,
        derivedSuspends: ParameterizedTypeName
    ): TypeSpec {
        return TypeSpec.interfaceBuilder(derivedInterfaceName)
            .addModifiers(KModifier.PUBLIC)
            .addFunctions(suspendFns)
            .addSuperinterface(derivedSuspends)
            .build()
    }

    private fun GenerationParameters.generateImplClass(implFns: List<FunSpec>): TypeSpec {
        val asyncRecipeType = AsyncRecipe::class.asClassName()
            .parameterizedBy(sourceInterfaceClassName, derivedImplementationClassName)

        val typeOfMember = MemberName("kotlin.reflect", "typeOf")

        val companion = TypeSpec.companionObjectBuilder()
            .superclass(asyncRecipeType)
            .addSuperclassConstructorParameter("%M<%T>()", typeOfMember, sourceInterfaceClassName)
            .addFunction(
                FunSpec.builder("construct")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("sendOperation", sendOp)
                    .returns(derivedImplementationClassName)
                    .addStatement("return %T(sendOperation)", derivedImplementationClassName)
                    .build()
            )
            .build()

        return TypeSpec.classBuilder(derivedImplementationName)
            .addSuperinterface(derivedInterfaceClassName)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("sender", sendOp).build())
            .addProperty(PropertySpec.builder("sender", sendOp, KModifier.PRIVATE).initializer("sender").build())
            .addFunctions(implFns)
            .addType(companion)
            .build()
    }

    private fun GenerationParameters.writeGeneratedFile(
        suspendedInterface: TypeSpec,
        implClass: TypeSpec
    ) {
        val file = FileSpec.builder(packageName, derivedInterfaceName)
            .addType(suspendedInterface)
            .addType(implClass)
            .build()

        val out: OutputStream = codeGenerator.createNewFile(
            Dependencies(false, sourceInterface.containingFile!!),
            packageName,
            derivedInterfaceName
        )
        out.bufferedWriter().use { writer -> file.writeTo(writer) }
    }

    companion object {
        val anyName = Any::class.asClassName()
        val sendOp = SendOperation::class.asClassName()
        val syncOp = SyncOp::class.asClassName()
        val syncQuery = SyncQuery::class.asClassName()
        val asyncQuery = AsyncQuery::class.asClassName()

        fun GenerateSuspended.parameters(annotated: KSClassDeclaration): GenerationParameters {
            val derivedInterfaceName = interfaceName.ifBlank { "Async${annotated.simpleName.asString()}" }
            return GenerationParameters(
                sourceInterface = annotated,
                derivedInterfaceName = derivedInterfaceName,
                derivedImplementationName = implementationClassName.ifBlank { "${derivedInterfaceName}Impl" },
                packageName = packageName.ifBlank { annotated.packageName.asString() },
                compositeOperationName = compositeOperationName,
                compositeQueryName = compositeQueryName,
            )
        }
    }

    data class GenerationParameters(
        val sourceInterface: KSClassDeclaration,
        val derivedInterfaceName: String,
        val derivedImplementationName: String,
        val packageName: String,
        val compositeOperationName: String,
        val compositeQueryName: String,
    ) {
        val sourceInterfaceClassName = sourceInterface.toClassName()
        val derivedInterfaceClassName = ClassName(packageName, derivedInterfaceName)
        val derivedImplementationClassName = ClassName(packageName, derivedImplementationName)

        fun shouldGenerateCompositeOperation(): Boolean = compositeOperationName.isNotBlank()
        fun shouldGenerateCompositeQuery(): Boolean = compositeQueryName.isNotBlank()
    }

}
