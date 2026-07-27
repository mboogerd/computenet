// Lives in :nature (T09 §A): runtime vocabulary :kernel imports (Contract/Key/
// Protocol are processor input read by :gen's ContractProcessor, but the
// annotations themselves must sit on :kernel's compile+runtime classpath without
// dragging :gen's KotlinPoet/symbol-processing-api/kotlin-reflect along). Package
// intentionally still `civictech.gen.wire`, not `civictech.nature` — this is what
// generated code and every existing kernel import site already names.
package civictech.gen.wire

import civictech.nature.ProtocolDirection

/**
 * Marks a port contract interface for wire identity (G-15, C-5): the KSP
 * `ContractProcessor` (`:gen`) emits a [ContractDescriptor] with stable contract
 * and method ids, so the serialized invocation form never carries reflection
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

/** Marks a bounded framework metadata protocol carried beside a port's data contract. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Protocol(
    val id: String,
    val direction: ProtocolDirection,
    val band: Int,
)
