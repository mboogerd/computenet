package civictech.gen.async

@GenerateSuspended
interface ExhaustiveInterface {
    // --- Properties ---

    // Abstract
    val readOnlyVal: String
    var readWriteVar: Int

    // With default implementation
    val defaultVal: Boolean
        get() = true

    var defaultVar: Double
        get() = 0.0
        set(value) {}

    // With annotations
    @Deprecated("use something else")
    val deprecatedVal: String

    // --- Functions ---

    fun noDefault()
    fun withDefault() {
        println("Default implementation")
    }

    // suspend
    suspend fun suspendFunction(): String

    // operator
    operator fun plus(other: Int): Int

    // infix
    infix fun infixCombine(other: String): String

    // inline (only allowed on top-level or in object; here just for test reference)
    // inline fun inlineFunc() // invalid in interfaces

    // --- Misc ---

    // Companion object with const
    companion object Companion {
        const val VERSION = 1
    }
}