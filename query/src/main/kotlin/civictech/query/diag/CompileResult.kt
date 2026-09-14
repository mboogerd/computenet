package civictech.query.diag

import civictech.query.run.CompiledQuery
import java.io.Serializable

/**
 * The outcome of compiling a query (epic computenet-cab §2.3): `Compiled | Rejected`.
 *
 * `Compiled` was deliberately withheld until computenet-cab.4.4 landed `CompiledQuery` in
 * `civictech.query.run` (this file's own earlier KDoc explained the wait); it is landed now
 * that type exists. The `Refused` (lowering) -> `Rejected` (this sealed interface's) mapping
 * — the `NO_LOWERING` `RejectionCode` — remains computenet-cab.5's, not this task's.
 */
sealed interface CompileResult : Serializable {

    /** The query compiled; [query] is the applyable, `Serializable` lowered artifact. */
    data class Compiled(val query: CompiledQuery) : CompileResult

    /** The query was rejected; every reason it was rejected, each with its own [Locus]. */
    data class Rejected(val rejections: List<Rejection>) : CompileResult
}
