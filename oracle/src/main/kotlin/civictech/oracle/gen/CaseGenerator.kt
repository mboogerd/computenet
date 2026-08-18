package civictech.oracle.gen

import civictech.oracle.bind.OperatorCatalog
import kotlin.random.Random

/**
 * The one entry point of `civictech.oracle.gen`: `(seed, `[GeneratorConfig]`)` in, one
 * [GeneratedCase] out — the graph, the drive script, the remove audit and the derived
 * controller seed, composed from [GraphGenerator] and [ScriptGenerator] in a fixed order.
 *
 * ## Order of operations, and why it is fixed
 *
 * 1. **Validate first.** [GeneratorConfig.validateAgainstCatalog] runs in [init], before any
 *    rng exists, so a vocabulary naming an unregistered id fails loudly at *construction*
 *    naming every absent id (`[ORA1-GEN-08]`'s enforcement, reached through the front door)
 *    rather than half-way through a generation.
 * 2. **One rng per case.** [generate] derives exactly one `Random(seed)` and threads that same
 *    instance through [GraphGenerator.generate] and then [ScriptGenerator.generate]. The order
 *    is part of the contract: the script's draws continue the stream the graph left off at, so
 *    moving either call reshuffles every case in the corpus.
 *
 * ## Determinism (`[ORA1-GEN-01]`, epic risk 5)
 *
 * Identical `(seed, config)` must yield an identical [GeneratedCase] in another JVM, on another
 * machine, at another time. Epic risk 5 names the four ways that is lost, and none of them is
 * reachable from this file or the two generators it composes:
 *
 * - **No wall clock** — no `System.currentTimeMillis()`/`nanoTime()`, no `Instant.now()`.
 * - **No `UUID.randomUUID()`** on the case path. Note that `civictech.cell.graph.SpawnStep`'s
 *   default `IdentityBinding.FreshLogical` is resolved to a concrete ref by the *applier*
 *   (`civictech.cell.graph.GraphDsl`), at apply time — the emitted spec is data and carries no
 *   generated identity, which is exactly why it stays byte-stable. Do not "fix" that by pinning
 *   `IdentityBinding.Exact` refs: that would put a fresh `UUID` into the spec itself.
 * - **No unordered-collection iteration feeding a choice** — every collection any draw ranges
 *   over is a `List` or a `LinkedHash*` (see `GraphGenerator.Builder`'s `LinkedHashMap` of
 *   nodes and `ScriptGenerator`'s `LinkedHashSet` known-sets).
 * - **No JVM identity hashes** — no `System.identityHashCode`, and no `hashCode()` of anything
 *   whose hash is identity-derived. Element payloads come from [ElementDomains]' static string
 *   tables.
 *
 * That is a claim about code, so it is checked as one: `CaseGeneratorTest`'s in-process
 * determinism sweep and `Bs16ReproducibilityTest`'s cross-JVM byte comparison both fail if any
 * of the four re-enters, which was demonstrated by mutation rather than asserted in prose.
 *
 * ## Controller seed (`[ORA1-GEN-07]`)
 *
 * [GeneratedCase.controllerSeed] is a pure function of the case seed alone — a single
 * splitmix64 step, documented on that property. This facade neither computes nor stores it: one
 * seed identifies one (graph, script, schedule) triple because the triple's third element is
 * *derived*, not carried.
 *
 * ## Non-goals
 *
 * Executing a case, diffing it against a `civictech.oracle.model.ReferenceOp`, shrinking it, or
 * injecting faults: the runner feature (computenet-4ru.8) and later.
 */
class CaseGenerator(private val config: GeneratorConfig) {

    init {
        // Loud, and before anything else: an unregistered vocabulary id is a configuration bug,
        // and the message names every absent id at once (GeneratorConfig.validateAgainstCatalog).
        config.validateAgainstCatalog()
    }

    /**
     * Generates the case for [seed].
     *
     * Equal `(seed, config)` pairs yield equal [GeneratedCase]s — in this JVM, in a freshly
     * launched one, and on another machine (`[ORA1-GEN-01]`).
     *
     * @throws IllegalStateException if the configured vocabulary cannot express the configured
     *   topology (`GraphGenerator.generate`'s own checks) or if a generated script would place
     *   an order-dependent source under more than one writer (`[ORA1-MODEL-09]`).
     */
    fun generate(seed: Long): GeneratedCase {
        val rng = Random(seed)
        val graph = GraphGenerator(config).generate(rng)
        val script = ScriptGenerator(config, graph.topology, rng).generate()
        return GeneratedCase(
            seed = seed,
            topology = graph.topology,
            spec = graph.spec,
            script = script.script,
            removeAudit = script.removeAudit,
        )
    }

    /** Generates [count] cases for seeds `first until first + count`, in order. */
    fun generateAll(first: Long, count: Int): List<GeneratedCase> =
        (first until first + count).map { generate(it) }

    companion object {
        /**
         * One-shot convenience: `CaseGenerator(config).generate(seed)`. Prefer the instance form
         * when generating a sweep — [OperatorCatalog] validation then happens once, not per seed.
         */
        fun generate(seed: Long, config: GeneratorConfig): GeneratedCase =
            CaseGenerator(config).generate(seed)
    }
}
