package civictech.query.parse

import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr

/**
 * The head-predicate dependency graph of a [Query]'s rule and definition set
 * (computenet-cab.2.3), feeding [SafetyAnalysis]'s `[QRY1-LANG-09]` RECURSION_UNSUPPORTED
 * check: a directed edge from a statement's head predicate to every predicate its body or
 * expression references.
 *
 * **Traversal direction — this task's one `unverified` clause, now resolved against the
 * landed AST (cab.2.1, merged):** a [civictech.query.ast.Rule]'s body contributes an edge
 * from [civictech.query.ast.Rule.head]'s predicate to every [Literal.Positive] and
 * [Literal.Negated] atom's predicate ([Literal.Comparison] carries no predicate and
 * contributes nothing). A [Definition]'s [RelationalExpr] contributes an edge from
 * [Definition.head]'s predicate to every [RelationalExpr.Relation] leaf reachable through
 * [RelationalExpr.SetOp] and [RelationalExpr.OuterJoin] nodes — **set-op operands and
 * outer-join arms are walked exactly like positive body atoms**, confirming the direction the
 * bead decided rather than diverging from it: nothing in the landed `RelationalExpr` shape
 * (`query/src/main/kotlin/civictech/query/ast/RelationalExpr.kt`) marks a leaf as belonging
 * to an outer join's non-preserved side, or as anything other than a plain
 * [civictech.query.ast.Atom] reference — [RelationalExpr.OuterJoin.left] and
 * [RelationalExpr.OuterJoin.right] are walked identically, with no special-casing for either
 * arm, which is the concrete form "outer-join arms bind their variables like positive atoms"
 * takes here: there is no separate, weaker edge kind for the arm that may end up unmatched at
 * evaluation time. This is a dependency-graph decision only; the sibling `[QRY1-LANG-04]`
 * evaluation semantics for a genuinely unmatched outer-join row are a later feature's concern.
 */
class RuleGraph private constructor(private val edges: Map<String, Set<String>>) {

    /** Every predicate this graph knows about, whether a dependency source, a target, or both. */
    val predicates: Set<String> get() = edges.keys + edges.values.flatten()

    /** The predicates directly depended on by [predicate] — empty if [predicate] is a leaf. */
    fun dependenciesOf(predicate: String): Set<String> = edges[predicate].orEmpty()

    /**
     * Every cycle in the graph, each as the set of predicates on it. Computed with Tarjan's
     * strongly-connected-components algorithm so a cycle spanning three or more predicates
     * (a longer mutual-recursion chain) is found exactly like a two-predicate one, and a
     * self-loop (a predicate depending directly on itself, `path :- path, edge` for example)
     * is reported as its own single-predicate cycle. A trivial one-predicate component with
     * no self-loop is not a cycle and is filtered out. Deterministic: predicates are visited
     * in sorted order, both as DFS roots and as each node's neighbour order, so the result is
     * stable across runs for the same [Query].
     */
    fun cycles(): List<Set<String>> {
        var counter = 0
        val indexOf = mutableMapOf<String, Int>()
        val lowlink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val components = mutableListOf<Set<String>>()

        fun strongConnect(v: String) {
            indexOf[v] = counter
            lowlink[v] = counter
            counter++
            stack.addLast(v)
            onStack += v

            for (w in dependenciesOf(v).sorted()) {
                if (w !in indexOf) {
                    strongConnect(w)
                    lowlink[v] = minOf(lowlink.getValue(v), lowlink.getValue(w))
                } else if (w in onStack) {
                    lowlink[v] = minOf(lowlink.getValue(v), indexOf.getValue(w))
                }
            }

            if (lowlink.getValue(v) == indexOf.getValue(v)) {
                val component = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack -= w
                    component += w
                    if (w == v) break
                }
                components += component
            }
        }

        for (v in predicates.sorted()) {
            if (v !in indexOf) strongConnect(v)
        }

        return components.filter { component ->
            component.size > 1 || dependenciesOf(component.single()).contains(component.single())
        }
    }

    companion object {

        /** Builds the graph over every [Query.rules] and [Query.definitions] statement. */
        fun of(query: Query): RuleGraph {
            val edges = mutableMapOf<String, MutableSet<String>>()

            fun addEdge(from: String, to: String) {
                edges.getOrPut(from) { mutableSetOf() }.add(to)
            }

            fun touch(predicate: String) {
                edges.getOrPut(predicate) { mutableSetOf() }
            }

            for (rule in query.rules) {
                touch(rule.head.predicate)
                for (literal in rule.body) {
                    when (literal) {
                        is Literal.Positive -> addEdge(rule.head.predicate, literal.atom.predicate)
                        is Literal.Negated -> addEdge(rule.head.predicate, literal.atom.predicate)
                        is Literal.Comparison -> Unit
                    }
                }
            }

            fun relationsOf(expr: RelationalExpr): List<String> = when (expr) {
                is RelationalExpr.Relation -> listOf(expr.atom.predicate)
                is RelationalExpr.SetOp -> relationsOf(expr.left) + relationsOf(expr.right)
                is RelationalExpr.OuterJoin -> relationsOf(expr.left) + relationsOf(expr.right)
            }

            for (definition in query.definitions) {
                touch(definition.head.predicate)
                for (predicate in relationsOf(definition.expr)) {
                    addEdge(definition.head.predicate, predicate)
                }
            }

            return RuleGraph(edges)
        }
    }
}
