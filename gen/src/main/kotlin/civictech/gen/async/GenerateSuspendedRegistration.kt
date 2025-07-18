package civictech.gen.async

import civictech.gen.async.SerializerProcessor.Companion.parameters
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.writeTo

class GenerateSuspendedRegistrationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    // TODO: Reimplement to make use of AsyncRecipe!
    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("Generating automatic type registrations")
        val symbols = resolver.getSymbolsWithAnnotation(GenerateSuspended::class.qualifiedName!!)

        logger.warn("symbols: ${symbols.toList()}")
        val parameters = symbols.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .map {
                it.getAnnotationsByType(GenerateSuspended::class)
                    .first()
                    .parameters(it)
            }
            .toList()

        if (parameters.isEmpty()) {
            logger.warn("No valid GenerateSuspended-annotated interfaces found. Terminating")
            return emptyList()
        }



        val recipeListName = "allGeneratedAsyncRecipes"
        val recipeType = AsyncRecipe::class.asClassName().parameterizedBy(STAR, STAR)

        val recipesPropertySpec = PropertySpec.builder(recipeListName, LIST.parameterizedBy(recipeType))
            .addModifiers(KModifier.PUBLIC)
            .initializer(buildCodeBlock {
                add("listOf(\n")
                parameters.forEach { params ->
                    add("  %T.Companion,\n", params.derivedImplementationClassName)
                }
                add(")")
            })
            .build()

        val registryName = "defaultAsyncRecipeRegistry"
        val registryType = AsyncRecipeRegistry::class.asClassName()
        val registryPropertySpec = PropertySpec.builder(registryName, registryType)
            .initializer(buildCodeBlock {
                add("AsyncRecipeRegistry(allGeneratedAsyncRecipes)")
            })
            .build()

        val fileSpec = FileSpec.builder("civictech.gen.async", "GeneratedAsyncRecipes")
            .addProperty(recipesPropertySpec)
            .addProperty(registryPropertySpec)
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(true, *parameters.mapNotNull { it.sourceInterface.containingFile }.toTypedArray()))

        return emptyList()
    }
}

class GenerateSuspendedRegistrationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        environment.logger.warn("GenerateSuspendedRegistrationProcessorProvider instantiated")
        return GenerateSuspendedRegistrationProcessor(environment.codeGenerator, environment.logger)
    }
}
