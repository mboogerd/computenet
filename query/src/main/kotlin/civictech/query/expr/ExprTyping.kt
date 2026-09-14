package civictech.query.expr

import civictech.query.schema.AttrType
import java.io.Serializable

/**
 * The result of typing an [Expr] against a set of column types — a value, never an exception
 * (cab.4-D1): a mismatch or an unknown column is a fact the caller (the lowering task) turns
 * into a refusal, not a control-flow exception this module throws.
 */
sealed class TypingResult : Serializable {
    /** [expr] types to [type]. */
    data class Typed(val type: AttrType) : TypingResult()

    /** [left] and [right] type to different [AttrType]s — no implicit widening between them. */
    data class TypeMismatch(val left: AttrType, val right: AttrType) : TypingResult()

    /** [name] is not a key of the `columnTypes` map the caller supplied. */
    data class UnknownAttribute(val name: String) : TypingResult()
}

/**
 * Types [Expr] values against a row's column types (cab.4-D1). Total over well-typed input —
 * every case below returns a [TypingResult], and a badly-typed expression yields
 * [TypingResult.TypeMismatch] or [TypingResult.UnknownAttribute] rather than throwing.
 */
object ExprTyping {

    /**
     * The [AttrType] [expr] evaluates to when its `Attr` leaves are drawn from [columnTypes]
     * (column name to declared type). [Expr.Cmp] requires both operands to type to the SAME
     * [AttrType] (no implicit INT/LONG widening in comparisons) and returns [AttrType.BOOL];
     * [Expr.And]/[Expr.Or]/[Expr.Not] require [AttrType.BOOL] operands and return
     * [AttrType.BOOL].
     */
    fun typeOf(expr: Expr, columnTypes: Map<String, AttrType>): TypingResult = when (expr) {
        is Expr.Attr -> columnTypes[expr.name]?.let { TypingResult.Typed(it) }
            ?: TypingResult.UnknownAttribute(expr.name)

        is Expr.Const -> TypingResult.Typed(expr.type)

        is Expr.Cmp -> {
            val leftResult = typeOf(expr.left, columnTypes)
            val rightResult = typeOf(expr.right, columnTypes)
            combineFirstFailure(leftResult, rightResult) { leftType, rightType ->
                if (leftType == rightType) {
                    TypingResult.Typed(AttrType.BOOL)
                } else {
                    TypingResult.TypeMismatch(leftType, rightType)
                }
            }
        }

        is Expr.And -> requireBoolBoth(expr.left, expr.right, columnTypes)
        is Expr.Or -> requireBoolBoth(expr.left, expr.right, columnTypes)

        is Expr.Not -> when (val operand = typeOf(expr.expr, columnTypes)) {
            is TypingResult.Typed ->
                if (operand.type == AttrType.BOOL) {
                    TypingResult.Typed(AttrType.BOOL)
                } else {
                    TypingResult.TypeMismatch(AttrType.BOOL, operand.type)
                }

            else -> operand
        }
    }

    private fun requireBoolBoth(
        left: Expr,
        right: Expr,
        columnTypes: Map<String, AttrType>,
    ): TypingResult {
        val leftResult = typeOf(left, columnTypes)
        val rightResult = typeOf(right, columnTypes)
        return combineFirstFailure(leftResult, rightResult) { leftType, rightType ->
            when {
                leftType != AttrType.BOOL -> TypingResult.TypeMismatch(AttrType.BOOL, leftType)
                rightType != AttrType.BOOL -> TypingResult.TypeMismatch(AttrType.BOOL, rightType)
                else -> TypingResult.Typed(AttrType.BOOL)
            }
        }
    }

    /**
     * Propagates the first non-[TypingResult.Typed] result (mismatch or unknown attribute) of
     * [left]/[right] unchanged; otherwise applies [onBothTyped] to their resolved types.
     */
    private inline fun combineFirstFailure(
        left: TypingResult,
        right: TypingResult,
        onBothTyped: (AttrType, AttrType) -> TypingResult,
    ): TypingResult {
        if (left !is TypingResult.Typed) return left
        if (right !is TypingResult.Typed) return right
        return onBothTyped(left.type, right.type)
    }
}
