package civictech.gen.async

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
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("SerializerProcessor started")
        val symbols = resolver.getSymbolsWithAnnotation("civictech.gen.async.GenerateSuspended")
        for (symbol in symbols.filterIsInstance<KSClassDeclaration>()) {
            if (symbol.classKind == ClassKind.INTERFACE) {
                logger.warn("Generating suspending interface for ${symbol.simpleName.asString()}")
                generateSuspendedInterfaceAndImpl(symbol)
            }
        }
        logger.info("SerializerProcessor done")
        return emptyList()
    }

    private fun generateSuspendedInterfaceAndImpl(original: KSClassDeclaration) {
        val packageName = original.packageName.asString()
        val name = original.simpleName.asString()
        val suspendedName = "Suspended$name"
        val implName = "${suspendedName}Impl"
        val interfaceType = original.toClassName()
        val sendOp = ClassName("civictech.gen.async", "SendOperation").parameterizedBy(interfaceType)
        val fireAndForget = ClassName("civictech.gen.async", "FireAndForget").parameterizedBy(interfaceType)
        val request = ClassName("civictech.gen.async", "Request")
        val asyncRequest = ClassName("civictech.gen.async", "AsyncRequest")
        val completableDeferred = ClassName("kotlinx.coroutines", "CompletableDeferred")

        val suspendFns = mutableListOf<FunSpec>()
        val implFns = mutableListOf<FunSpec>()

        for (fn in original.getAllFunctions().filter { it.isAbstract }) {
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

            val deferredReturn = completableDeferred.parameterizedBy(returnType)

            val argList = params.joinToString(", ") { it.name }
            if (returnType == UNIT) {
                implFn.addStatement("sender.send(%T { it.%L($argList) })", fireAndForget, name)
            } else if (fn.modifiers.contains(Modifier.SUSPEND)) {
                implFn.addStatement(
                    "return sender.send(%T(%T()) { it.%L($argList) })",
                    asyncRequest.parameterizedBy(interfaceType, returnType),
                    deferredReturn,
                    name
                )
            } else {
                implFn.addStatement(
                    "return sender.send(%T(%T()) { it.%L($argList) })",
                    request.parameterizedBy(interfaceType, returnType),
                    deferredReturn,
                    name
                )
            }

            implFns += implFn.build()
        }

        val suspendedInterface = TypeSpec.interfaceBuilder(suspendedName)
            .addModifiers(KModifier.PUBLIC)
            .addFunctions(suspendFns)
            .build()

        val implClass = TypeSpec.classBuilder(implName)
            .addSuperinterface(ClassName(packageName, suspendedName))
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("sender", sendOp).build())
            .addProperty(PropertySpec.builder("sender", sendOp, KModifier.PRIVATE).initializer("sender").build())
            .addFunctions(implFns)
            .build()

        val file = FileSpec.builder(packageName, suspendedName)
            .addType(suspendedInterface)
            .addType(implClass)
            .build()

        val out: OutputStream = codeGenerator.createNewFile(
            Dependencies(false, original.containingFile!!),
            packageName,
            suspendedName
        )
        out.bufferedWriter().use { writer -> file.writeTo(writer) }
    }
}
