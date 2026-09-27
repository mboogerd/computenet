package civictech.timetravel.cli

import civictech.timetravel.timeline.Position
import java.util.UUID

/**
 * Where one side's topology comes from (computenet-3qkx1 D2/D7): a Java-serialized `GraphSpec`
 * file ([graphFile]), or a `GraphSource` class name ([provider]) with an optional `(String)`
 * constructor argument ([providerArg]). At most one of [graphFile] and [provider] is set; neither
 * set is "no graph source", which `Reconstructor.of` refuses — not this type.
 */
data class GraphFlags(val graphFile: String? = null, val provider: String? = null, val providerArg: String? = null) {
    val isEmpty: Boolean get() = graphFile == null && provider == null
}

/** One parsed command line (computenet-3qkx1 D1/D7/D10). */
sealed interface Command {
    val json: Boolean

    data class Inspect(val path: String, override val json: Boolean) : Command

    data class Reconstruct(
        val path: String,
        val at: Position,
        val graph: GraphFlags,
        val seed: Long,
        override val json: Boolean,
    ) : Command

    /** [seed] is `null` when `--seed` was not given (a directory diff refuses a non-null one). */
    data class Diff(
        val pathA: String,
        val pathB: String,
        val graphA: GraphFlags,
        val graphB: GraphFlags,
        val seed: Long?,
        override val json: Boolean,
    ) : Command
}

/** The command line is malformed: exit 2 with the usage on stderr (computenet-3qkx1.1). */
class UsageError(message: String) : Exception(message)

/**
 * The hand-rolled `timetravel` argument parser (computenet-3qkx1 D1, D7, D10; no CLI library,
 * `[TTD1-51]`). Pure: it touches no file — whether a path exists is the reader's question.
 *
 * Grammar:
 * ```
 * inspect     <path> [--json]
 * reconstruct <path> --at <n | uuid=counter[,uuid=counter…]> [--graph <file> | --graph-provider <fqcn> [--graph-arg <s>]] [--seed N] [--json]
 * diff        <pathA> <pathB> [graph flags, each also as -a / -b suffixed] [--seed N] [--json]
 * ```
 * For `diff`, a side that names its own source (`--graph-a`/`--graph-provider-a`) uses only its
 * suffixed source flags; `--graph-arg-a` overrides `--graph-arg` for side A either way.
 */
object Args {

    const val USAGE: String =
        "usage: timetravel inspect <path> [--json]\n" +
            "       timetravel reconstruct <path> --at <index | uuid=counter,...> " +
            "[--graph <file> | --graph-provider <fqcn> [--graph-arg <s>]] [--seed N] [--json]\n" +
            "       timetravel diff <pathA> <pathB> [--graph… | --graph-provider… [--graph-arg…]] " +
            "(each also as -a/-b) [--seed N] [--json]"

    private val GRAPH_KEYS = listOf("--graph", "--graph-provider", "--graph-arg")
    private val DIFF_GRAPH_KEYS = GRAPH_KEYS.flatMap { listOf(it, "$it-a", "$it-b") }

    /** @throws UsageError on an unknown command or flag, a missing value, or a wrong positional count. */
    fun parse(args: Array<String>): Command {
        if (args.isEmpty()) throw UsageError("no command given")
        val rest = args.drop(1)
        return when (val word = args[0]) {
            "inspect" -> {
                val parsed = split(word, rest, valued = emptySet())
                val path = parsed.positionals(word, 1).single()
                Command.Inspect(path, parsed.json)
            }

            "reconstruct" -> {
                val parsed = split(word, rest, valued = setOf("--at", "--seed") + GRAPH_KEYS)
                val path = parsed.positionals(word, 1).single()
                val at = parsed.values["--at"] ?: throw UsageError("reconstruct needs --at")
                Command.Reconstruct(
                    path = path,
                    at = position(at),
                    graph = graphFlags(parsed.values["--graph"], parsed.values["--graph-provider"], parsed.values["--graph-arg"]),
                    seed = parsed.values["--seed"]?.let(::seed) ?: 0L,
                    json = parsed.json,
                )
            }

            "diff" -> {
                val parsed = split(word, rest, valued = setOf("--seed") + DIFF_GRAPH_KEYS)
                val (a, b) = parsed.positionals(word, 2)
                Command.Diff(
                    pathA = a,
                    pathB = b,
                    graphA = diffSide(parsed.values, "a"),
                    graphB = diffSide(parsed.values, "b"),
                    seed = parsed.values["--seed"]?.let(::seed),
                    json = parsed.json,
                )
            }

            else -> throw UsageError("unknown command '$word'")
        }
    }

    /**
     * `--at` (computenet-3qkx1 D10): a non-negative decimal is a [Position.Index]; otherwise a
     * comma-separated list of `uuid=counter` is a [Position.Cut].
     *
     * @throws UsageError when [text] is neither.
     */
    fun position(text: String): Position {
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) {
            val n = text.toIntOrNull() ?: throw UsageError("--at $text: index out of range")
            return Position.Index(n)
        }
        val cut = LinkedHashMap<UUID, Long>()
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) throw UsageError("--at $text: expected <index> or <uuid>=<counter>[,…]")
            val source = try {
                UUID.fromString(part.substring(0, eq))
            } catch (e: IllegalArgumentException) {
                throw UsageError("--at $text: '${part.substring(0, eq)}' is not a UUID")
            }
            val counter = part.substring(eq + 1).toLongOrNull()
                ?: throw UsageError("--at $text: '${part.substring(eq + 1)}' is not a counter")
            if (cut.put(source, counter) != null) throw UsageError("--at $text: source $source named twice")
        }
        return Position.Cut(cut)
    }

    private fun seed(text: String): Long = text.toLongOrNull() ?: throw UsageError("--seed $text: not a number")

    private fun graphFlags(graph: String?, provider: String?, arg: String?): GraphFlags {
        if (graph != null && provider != null) throw UsageError("--graph and --graph-provider are mutually exclusive")
        if (arg != null && provider == null) throw UsageError("--graph-arg needs --graph-provider")
        return GraphFlags(graph, provider, arg)
    }

    private fun diffSide(values: Map<String, String>, side: String): GraphFlags {
        val ownGraph = values["--graph-$side"]
        val ownProvider = values["--graph-provider-$side"]
        val arg = values["--graph-arg-$side"] ?: values["--graph-arg"]
        return if (ownGraph != null || ownProvider != null) {
            graphFlags(ownGraph, ownProvider, arg)
        } else {
            graphFlags(values["--graph"], values["--graph-provider"], arg)
        }
    }

    private class Split(val positionals: List<String>, val values: Map<String, String>, val json: Boolean) {
        fun positionals(command: String, count: Int): List<String> {
            if (positionals.size != count) {
                throw UsageError("$command takes $count path argument(s), got ${positionals.size}")
            }
            return positionals
        }
    }

    private fun split(command: String, args: List<String>, valued: Set<String>): Split {
        val positionals = mutableListOf<String>()
        val values = LinkedHashMap<String, String>()
        var json = false
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--json" -> json = true
                arg in valued -> {
                    val value = args.getOrNull(i + 1) ?: throw UsageError("$arg needs a value")
                    if (values.put(arg, value) != null) throw UsageError("$arg given twice")
                    i++
                }

                arg.startsWith("-") && arg != "-" -> throw UsageError("unknown flag '$arg' for $command")
                else -> positionals += arg
            }
            i++
        }
        return Split(positionals, values, json)
    }
}
