package civictech.gen.async

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.writeTo

class GenerateSuspendedRegistrationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("Generating automatic type registrations")
        val symbols = resolver.getSymbolsWithAnnotation(GenerateSuspended::class.qualifiedName!!)
        logger.warn("symbols: ${symbols.toList()}")
        val interfaces = symbols.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .toList()

        if (interfaces.isEmpty()) {
            logger.warn("No valid GenerateSuspended-annotated interfaces found. Terminating")
            return emptyList()
        }

        val pkgName = "civictech.gen.async"

        val fileSpecBuilder = FileSpec.builder(pkgName, "GeneratedSuspendedProxyRegistry")
            .addFunction(FunSpec.builder("includeGeneratedProxies")
                .receiver(ClassName(pkgName, "SuspendedProxyFactoryRegistry"))
                .addCode(buildCodeBlock {
                    for (symbol in interfaces) {
                        val name = symbol.simpleName.asString()
                        val suspendedName = "Suspended$name"
                        val implName = "${suspendedName}Impl"
                        addStatement("register<%L, %L>(%L::class) {", name, suspendedName, suspendedName)
                        addStatement("    %L(it as SendOperation<%L>)", implName, name)
                        addStatement("}")
                    }
                })
                .build()
            )

        val fileSpec = fileSpecBuilder.build()
        fileSpec.writeTo(codeGenerator, Dependencies(true, *interfaces.mapNotNull { it.containingFile }.toTypedArray()))

        return emptyList()
    }
}

class GenerateSuspendedRegistrationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        environment.logger.warn("GenerateSuspendedRegistrationProcessorProvider instantiated")
        return GenerateSuspendedRegistrationProcessor(environment.codeGenerator, environment.logger)
    }
}
