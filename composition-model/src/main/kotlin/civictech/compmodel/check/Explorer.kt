package civictech.compmodel.check

import java.io.File
import java.util.ArrayDeque
import java.util.Random

/**
 * A labelled transition system the checker explores.
 *
 * Every model in this module is one of these: a deterministic successor function over an
 * immutable state (Kotlin data classes, so equality and hashing are structural and a visited
 * set can dedupe states), plus the invariants checked at every state and the extra
 * invariants checked only at *quiescent* states (no progress transition enabled; only
 * optional management actions or faults remain).
 *
 * There is no wall clock and no thread anywhere in a model: "time" is the order of
 * transitions, and every interleaving the bounded exploration reaches is a schedule the
 * real system could take. Faults (crash, lost acknowledgement, RESTART, ...) are ordinary
 * transitions with a per-run budget carried in the state, so a crash is injected between
 * every pair of steps a protocol has, by construction.
 */
interface Spec<S : Any> {
    val name: String
    fun initial(): S
    fun next(s: S): List<Transition<S>>
    /** Invariants that must hold at every reachable state. Empty = holds. */
    fun invariants(s: S): List<String>
    /** Invariants that must hold at every reachable quiescent state. */
    fun quiescent(s: S): List<String> = emptyList()
}

/** One step. [progress] = false marks an optional action (fault, management, checkpoint). */
data class Transition<S>(val label: String, val target: S, val progress: Boolean = true)

/** The shortest (BFS) or seeded (walk) path to a violation, printable in full. */
data class Counterexample(val spec: String, val violation: String, val trace: List<String>, val seed: Long? = null) {
    fun render(): String = buildString {
        append("COUNTEREXAMPLE in ").append(spec)
        if (seed != null) append(" (seed ").append(seed).append(')')
        append("\n  violated: ").append(violation).append('\n')
        trace.forEachIndexed { i, l -> append("  ").append(i + 1).append(". ").append(l).append('\n') }
    }
}

data class Exploration(
    val spec: String,
    val states: Int,
    val transitions: Long,
    val maxDepth: Int,
    /** False when the state cap was hit before the frontier emptied. */
    val complete: Boolean,
    val counterexample: Counterexample?,
) {
    val holds: Boolean get() = counterexample == null
    fun summary(): String =
        "$spec: states=$states transitions=$transitions depth=$maxDepth complete=$complete " +
            (counterexample?.let { "VIOLATION ${it.violation} (trace length ${it.trace.size})" } ?: "HOLDS")
}

data class WalkResult(val spec: String, val seeds: Int, val steps: Long, val counterexample: Counterexample?) {
    val holds: Boolean get() = counterexample == null
}

object Explorer {

    /**
     * Breadth-first exhaustive exploration with state hashing. BFS order makes the first
     * violation found a shortest one. Stops at the first violation.
     */
    fun <S : Any> bfs(spec: Spec<S>, maxStates: Int = 3_000_000): Exploration {
        val index = HashMap<S, Int>()
        val parent = ArrayList<Int>()
        val label = ArrayList<String>()
        val depth = ArrayList<Int>()
        val states = ArrayList<S>()
        val queue = ArrayDeque<Int>()
        var transitions = 0L
        var maxDepth = 0

        fun add(s: S, p: Int, l: String, d: Int): Int? {
            if (index.containsKey(s)) return null
            val id = states.size
            index[s] = id; states.add(s); parent.add(p); label.add(l); depth.add(d)
            if (d > maxDepth) maxDepth = d
            return id
        }

        fun trace(id: Int): List<String> {
            val out = ArrayList<String>()
            var cur = id
            while (cur >= 0 && parent[cur] >= 0) { out.add(label[cur]); cur = parent[cur] }
            out.reverse()
            return out
        }

        fun fail(id: Int, v: String) = Exploration(
            spec.name, states.size, transitions, maxDepth, false, Counterexample(spec.name, v, trace(id)),
        )

        val root = add(spec.initial(), -1, "init", 0)!!
        spec.invariants(states[root]).firstOrNull()?.let { return fail(root, it) }
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (states.size >= maxStates) {
                return Exploration(spec.name, states.size, transitions, maxDepth, false, null)
            }
            val id = queue.poll()
            val s = states[id]
            val succ = spec.next(s)
            if (succ.none { it.progress }) {
                spec.quiescent(s).firstOrNull()?.let { return fail(id, "[quiescent] $it") }
            }
            for (t in succ) {
                transitions++
                val nid = add(t.target, id, t.label, depth[id] + 1) ?: continue
                spec.invariants(t.target).firstOrNull()?.let { return fail(nid, it) }
                queue.add(nid)
            }
        }
        return Exploration(spec.name, states.size, transitions, maxDepth, true, null)
    }

    /**
     * Seeded random walks: from the initial state, pick uniformly among enabled transitions
     * with `java.util.Random(seed)` until no transition is enabled or [maxSteps]. Fully
     * reproducible from the seed; the first failing walk is returned with its whole trace.
     */
    fun <S : Any> walks(spec: Spec<S>, seeds: Iterable<Long>, maxSteps: Int = 400): WalkResult {
        var steps = 0L
        var n = 0
        for (seed in seeds) {
            n++
            val rnd = Random(seed)
            var s = spec.initial()
            val tr = ArrayList<String>()
            spec.invariants(s).firstOrNull()?.let { return WalkResult(spec.name, n, steps, Counterexample(spec.name, it, tr, seed)) }
            for (i in 0 until maxSteps) {
                val succ = spec.next(s)
                if (succ.none { it.progress }) {
                    spec.quiescent(s).firstOrNull()?.let {
                        return WalkResult(spec.name, n, steps, Counterexample(spec.name, "[quiescent] $it", tr, seed))
                    }
                }
                if (succ.isEmpty()) break
                val t = succ[rnd.nextInt(succ.size)]
                steps++
                tr.add(t.label)
                s = t.target
                spec.invariants(s).firstOrNull()?.let { return WalkResult(spec.name, n, steps, Counterexample(spec.name, it, tr, seed)) }
            }
        }
        return WalkResult(spec.name, n, steps, null)
    }
}

/** Seed count for random walks: `-Pcompmodel.seeds=N`, default [DEFAULT_COUNT]. */
object Seeds {
    /** Small enough that `:composition-model:test` stays well under a minute. */
    const val DEFAULT_COUNT = 200
    fun count(): Int = System.getProperty("compmodel.seeds")?.toIntOrNull() ?: DEFAULT_COUNT
    fun all(): List<Long> = (0 until count()).map { it.toLong() }
}

/** Appends measured numbers to the report file the build points at (model-results.md quotes it). */
object Report {
    private val file: File? = System.getProperty("compmodel.report")?.let { File(it) }
    @Synchronized
    fun line(s: String) {
        println(s)
        file?.let { it.parentFile.mkdirs(); it.appendText(s + "\n") }
    }
}
