package civictech.query.diag

import java.io.Serializable

/**
 * The outcome of compiling a query (epic computenet-cab §2.3): intended full shape
 * `Compiled | Rejected`.
 *
 * Only [Rejected] is landed here. `Compiled` is deliberately NOT landed by this task: per the
 * epic it must carry a `CompiledQuery` (§2.3), a type owned by the later lowering/API
 * features — a stub `CompiledQuery` in a not-yet-existing `civictech.query.run` package would
 * trespass on their scope. Adding `Compiled` later is the same additive pattern cab.1-D1
 * establishes for [RejectionCode] variants: a sibling/later feature adds the variant it needs
 * without touching this file's existing shape.
 */
sealed interface CompileResult : Serializable {

    /** The query was rejected; every reason it was rejected, each with its own [Locus]. */
    data class Rejected(val rejections: List<Rejection>) : CompileResult
}
