package civictech.gen.wire

/**
 * Marks a port contract interface for wire identity (G-15, C-5): the KSP
 * [ContractProcessor] emits a [ContractDescriptor] with stable contract and
 * method ids, so the serialized invocation form never carries reflection
 * artifacts (P9).
 *
 * [management] distinguishes management contracts (returns allowed, may
 * block, spec 12/G-11) from push-only data contracts.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Contract(
    val management: Boolean = false,
    /** Marks a world-touching boundary that shadow execution must suppress. */
    val effect: Boolean = false,
)

/** Selects the single routing argument of a partitionable data invocation. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Key
