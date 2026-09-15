package civictech.query.diag

import civictech.query.run.CompiledQuery
import java.io.Serializable

/**
 * The outcome of compiling a query (epic computenet-cab §2.3): `Compiled | Rejected`.
 *
 * Produced by `civictech.query.QueryCompiler`, which is total ([QRY1-REJECT-03]): every
 * phase's rejections are collected into one [Rejected] ([QRY1-REJECT-10]), and a lowering
 * `Refused` becomes one `NO_LOWERING` [Rejection] per refusal ([QRY1-REJECT-06]). [Rejected]
 * carries no `GraphSpec`, so a rejection has no partial artifact to apply ([QRY1-REJECT-04]).
 */
sealed interface CompileResult : Serializable {

    /** The query compiled; [query] is the applyable, `Serializable` lowered artifact. */
    data class Compiled(val query: CompiledQuery) : CompileResult

    /** The query was rejected; every reason it was rejected, each with its own [Locus]. */
    data class Rejected(val rejections: List<Rejection>) : CompileResult
}
