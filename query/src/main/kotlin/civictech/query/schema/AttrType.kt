package civictech.query.schema

import kotlin.reflect.KClass

/**
 * Closed set of primitive attribute types (cab.1-D4, `[QRY1-LANG-05]`). The Expr algebra
 * feature (cab.2) consumes this set to type-check expressions; adding a member later is an
 * additive change to the query language, not a breaking one — nothing here forecloses it.
 */
enum class AttrType {
    INT,
    LONG,
    DOUBLE,
    STRING,
    BOOL;

    /**
     * The Kotlin runtime type a [civictech.query.ast.Term.Const] of this [AttrType] carries.
     * Used to validate that a [civictech.query.ast.Term.Const]'s `value` and `type` agree.
     */
    val runtimeType: KClass<*>
        get() = when (this) {
            INT -> Int::class
            LONG -> Long::class
            DOUBLE -> Double::class
            STRING -> String::class
            BOOL -> Boolean::class
        }
}
