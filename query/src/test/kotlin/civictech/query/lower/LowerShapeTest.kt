package civictech.query.lower

import civictech.cell.graph.CellFactory
import civictech.query.architecture.HierarchyCompleteness
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.io.Serializable

/**
 * `[QRY1-LOWER-06]`'s shape guarantee for `civictech.query.lower`: every cell factory and every
 * result/diagnostic type is `Serializable` pure data with no function-typed field, and every
 * factory is a `data class`. The completeness half fails naming a sealed subtype of
 * [LoweringResult] or [LoweringDiagnostic] added without updating [shapeTypes]
 * (`HierarchyCompleteness.missingFrom`, computenet-njvps), or a [CellFactory] implementation in
 * the package's compiled main classes missing from [factoryTypes].
 */
class LowerShapeTest {

    private val factoryTypes: List<Class<*>> = listOf(
        SetSourceFactory::class.java,
        FilterFactory::class.java,
        FlatMapFactory::class.java,
        JoinFactory::class.java,
        SemiJoinFactory::class.java,
        UnionFactory::class.java,
        IntersectFactory::class.java,
        PadFactory::class.java,
        GroupByFactory::class.java,
        CountFactory::class.java,
    )

    private val aggregateSpecTypes: List<Class<*>> = listOf(
        AggregateSpec::class.java,
        AggregateSpec.Count::class.java,
        AggregateSpec.Sum::class.java,
        AggregateSpec.Avg::class.java,
        AggregateSpec.Min::class.java,
        AggregateSpec.Max::class.java,
        AggregateSpec.TopK::class.java,
        AggregateSpec.CollectToSet::class.java,
    )

    private val shapeTypes: List<Class<*>> = factoryTypes + aggregateSpecTypes + listOf(
        LoweringResult::class.java,
        LoweringResult.Lowered::class.java,
        LoweringResult.Refused::class.java,
        LoweringRefusal::class.java,
        LoweringDiagnostic::class.java,
        LoweringDiagnostic.GateNotProvable::class.java,
        LoweringDiagnostic.EventuallyConsistent::class.java,
    )

    @Test
    fun `every factory and result or diagnostic type implements java-io-Serializable`() {
        val nonSerializable = shapeTypes.filterNot { Serializable::class.java.isAssignableFrom(it) }
        withClue("lower types not implementing java.io.Serializable: $nonSerializable") {
            nonSerializable.shouldBeEmpty()
        }
    }

    @Test
    fun `no factory or result or diagnostic type declares a function-typed field`() {
        // The factories' fields hold `civictech.query.expr` interpreters, which IMPLEMENT a
        // Kotlin function type as a concrete data class (ExprShapeTest guards them). What is
        // forbidden is a field whose declared type is a function type itself — `(Row) -> Boolean`
        // compiles to the `Function1` interface — or any other function implementation, since
        // either can hold a lambda with no structural equality.
        val functionTyped = shapeTypes.flatMap { klass ->
            klass.declaredFields
                .filter { field -> kotlin.Function::class.java.isAssignableFrom(field.type) }
                .filter { field -> field.type.isInterface || field.type.packageName != "civictech.query.expr" }
                .map { field -> "${klass.simpleName}.${field.name}" }
        }
        withClue("lower fields with a function type: $functionTyped") {
            functionTyped.shouldBeEmpty()
        }
    }

    @Test
    fun `every factory is a data class implementing CellFactory`() {
        // A Kotlin data class compiles a `componentN` per primary-constructor property and a
        // `copy`; a plain class or a lambda-backed SAM does not.
        val notData = factoryTypes.filterNot { klass ->
            CellFactory::class.java.isAssignableFrom(klass) &&
                klass.declaredMethods.any { it.name == "component1" } &&
                klass.declaredMethods.any { it.name == "copy" }
        }
        withClue("factories that are not data classes implementing CellFactory: $notData") {
            notData.shouldBeEmpty()
        }
    }

    @Test
    fun `QRY1 §LOWER-05 the completion task's factories round-trip equals through Java serialization`() {
        val columns = listOf("g", "v")
        val selector = civictech.query.expr.RowSelector(columns, "v")
        val longSelector = civictech.query.expr.RowLongSelector(columns, "v")
        val key = civictech.query.expr.RowKey(columns, listOf("g"))
        val factories: List<CellFactory> = listOf(
            IntersectFactory(columns),
            PadFactory(civictech.query.expr.RowPad(columns, listOf("g", "w", "v"))),
            CountFactory(columns),
            GroupByFactory(null, AggregateSpec.Count),
            GroupByFactory(key, AggregateSpec.Sum(longSelector)),
            GroupByFactory(key, AggregateSpec.Avg(longSelector)),
            GroupByFactory(key, AggregateSpec.Min(selector, civictech.query.schema.AttrType.STRING)),
            GroupByFactory(key, AggregateSpec.Max(selector, civictech.query.schema.AttrType.DOUBLE)),
            GroupByFactory(key, AggregateSpec.TopK(3, selector, civictech.query.schema.AttrType.LONG)),
            GroupByFactory(key, AggregateSpec.CollectToSet),
        )
        withClue("non-vacuity: every new factory class appears") {
            factories.map { it.javaClass }.toSet() shouldBe
                setOf(IntersectFactory::class.java, PadFactory::class.java, CountFactory::class.java, GroupByFactory::class.java)
        }
        factories.forEach { factory ->
            val bytes = java.io.ByteArrayOutputStream().also { out ->
                java.io.ObjectOutputStream(out).use { it.writeObject(factory) }
            }.toByteArray()
            val back = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readObject() }
            withClue("$factory") {
                back shouldBe factory
                back.hashCode() shouldBe factory.hashCode()
            }
        }
    }

    @Test
    fun `shapeTypes names every permitted subclass of LoweringResult and LoweringDiagnostic`() {
        val missing = HierarchyCompleteness.missingFrom(LoweringResult::class.java, shapeTypes) +
            HierarchyCompleteness.missingFrom(LoweringDiagnostic::class.java, shapeTypes) +
            HierarchyCompleteness.missingFrom(AggregateSpec::class.java, shapeTypes)
        withClue("shapeTypes is missing sealed-hierarchy members: $missing") {
            missing.shouldBeEmpty()
        }
    }

    @Test
    fun `factoryTypes names every CellFactory implementation declared in the lower package`() {
        // CellFactory is not sealed (it is a kernel `fun interface`), so permittedSubclasses
        // cannot enumerate it. The declared set is read from the package's COMPILED classes, not
        // its source text: a source regex misses a `data object`, a supertype list not led by
        // CellFactory, and a constructor whose parameter list contains a parenthesis (a default
        // `listOf()`), all of which a class-file walk sees as the CellFactory it is.
        val classesRoot = File(SetSourceFactory::class.java.protectionDomain.codeSource.location.toURI())
        val packageDir = File(classesRoot, "civictech/query/lower")
        withClue("the lower package's main classes are a directory: $packageDir") { packageDir.isDirectory shouldBe true }
        val loader = SetSourceFactory::class.java.classLoader
        val declared = packageDir.listFiles { file -> file.isFile && file.extension == "class" }!!
            .map { Class.forName("civictech.query.lower." + it.nameWithoutExtension, false, loader) }
            .filter { CellFactory::class.java.isAssignableFrom(it) && !it.isInterface && !it.isSynthetic }
            .map { it.simpleName }
            .toSet()
        withClue("non-vacuity: the scan finds the factories") { declared.isEmpty() shouldBe false }

        val missing = declared - factoryTypes.map { it.simpleName }.toSet()
        withClue("CellFactory implementations missing from factoryTypes: $missing") {
            missing.shouldBeEmpty()
        }
    }
}
