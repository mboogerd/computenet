package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit-level tests for [HostFacts] (`[BEN1-23]`, computenet-yhbd) — no [ThroughputReport]
 * plumbing, sub-second, beside [ResultModelTest]'s coverage of [RunEnvironment] itself.
 *
 * The end-to-end refusal and "recorded, not rendering, host" pins live in
 * `civictech.bench.micro.ThroughputReportTest`, next to the equivalent [MeasuringJvm] and
 * [RunKnobs] tests, because they need the JMH-log fixture builders that already live
 * there. This file covers [HostFacts] in isolation: construction validation and the
 * banner round trip [fromJmhLog] depends on.
 */
class EnvTest {

    // ---------------------------------------------------------------------------
    // Construction: every field required, same posture as RunEnvironment/MeasuringJvm.
    // ---------------------------------------------------------------------------

    @Test
    fun `a valid HostFacts constructs`() {
        val facts = HostFacts(cpuModel = "Apple M2 Pro", coreCount = 10, os = "Mac OS X 14.5")
        facts.cpuModel shouldBe "Apple M2 Pro"
        facts.coreCount shouldBe 10
        facts.os shouldBe "Mac OS X 14.5"
    }

    @Test
    fun `refuses a blank cpuModel`() {
        shouldThrow<IllegalArgumentException> {
            HostFacts(cpuModel = "  ", coreCount = 10, os = "Mac OS X 14.5")
        }.message shouldContain "cpuModel"
    }

    @Test
    fun `refuses a non-positive coreCount`() {
        shouldThrow<IllegalArgumentException> {
            HostFacts(cpuModel = "Apple M2 Pro", coreCount = 0, os = "Mac OS X 14.5")
        }.message shouldContain "coreCount"
    }

    @Test
    fun `refuses a blank os`() {
        shouldThrow<IllegalArgumentException> {
            HostFacts(cpuModel = "Apple M2 Pro", coreCount = 10, os = "")
        }.message shouldContain "os"
    }

    // ---------------------------------------------------------------------------
    // bannerLines / fromJmhLog round trip — the artifact OperatorThroughputBenchmark
    // prints and ThroughputReport.renderRun reads back.
    // ---------------------------------------------------------------------------

    @Test
    fun `fromJmhLog reads back exactly what bannerLines prints`() {
        val original = HostFacts(
            cpuModel = "Genuine Intel Xeon Platinum 8375C",
            coreCount = 64,
            os = "Linux 6.2.0-1019-aws",
        )
        val log = original.bannerLines().joinToString("\n")

        HostFacts.fromJmhLog(log, source = "<fixture>") shouldBe original
    }

    @Test
    fun `captureCurrent answers for this process and passes its own validation`() {
        // Not asserting a specific value — that would pin this suite to whichever
        // machine runs it. What matters is that it succeeds and satisfies HostFacts'
        // own non-blank/positive requirements, exactly like `captureCurrent`'s only
        // legitimate callers rely on (an in-process probe, or the measuring fork's own
        // trial-level hook).
        val current = HostFacts.captureCurrent()
        current.cpuModel.isNotBlank() shouldBe true
        (current.coreCount > 0) shouldBe true
        current.os.isNotBlank() shouldBe true
    }

    @Test
    fun `refuses a log carrying no host-facts banner at all`() {
        val failure = shouldThrow<HostFactsUnknownException> {
            HostFacts.fromJmhLog("# JMH version: 1.37\n", source = "<fixture>")
        }
        failure.message shouldContain HostFacts.CPU_MODEL_PREFIX
    }

    @Test
    fun `refuses a core count that is not an integer`() {
        val log = buildString {
            appendLine("${HostFacts.CPU_MODEL_PREFIX} Apple M2 Pro")
            appendLine("${HostFacts.CORE_COUNT_PREFIX} lots")
            appendLine("${HostFacts.OS_PREFIX} Mac OS X 14.5")
        }
        val failure = shouldThrow<HostFactsUnknownException> {
            HostFacts.fromJmhLog(log, source = "<fixture>")
        }
        failure.message shouldContain "lots"
    }

    @Test
    fun `repeated identical banner lines collapse, as JMH's own repeated banner does`() {
        // A sweep prints this once per fork per parameter combination; every occurrence
        // states the same host, and distinct() must not treat that as a disagreement.
        val original = HostFacts(cpuModel = "Apple M2 Pro", coreCount = 10, os = "Mac OS X 14.5")
        val log = (original.bannerLines() + original.bannerLines()).joinToString("\n")

        HostFacts.fromJmhLog(log, source = "<fixture>") shouldBe original
    }

    @Test
    fun `reads a fact JMH relayed into its own progress line`() {
        // Verbatim from a 2-fork sweep of OperatorThroughputBenchmark on this branch
        // (2026-08-19): JMH prints `# Warmup Iteration   1: ` without a newline and
        // relays the fork's stdout onto that line, so the first fact the trial hook
        // prints does not begin at column 0. Expected values are literals read off that
        // log, not recomputed by the parser under test.
        val log = """
            # Warmup Iteration   1: ${HostFacts.CPU_MODEL_PREFIX} Apple M2 Pro
            ${HostFacts.CORE_COUNT_PREFIX} 10
            ${HostFacts.OS_PREFIX} Mac OS X 26.6.2
        """.trimIndent()

        HostFacts.fromJmhLog(log, source = "<fixture>") shouldBe HostFacts(
            cpuModel = "Apple M2 Pro",
            coreCount = 10,
            os = "Mac OS X 26.6.2",
        )
    }

    @Test
    fun `refuses two host-fact markers fused onto one line rather than accepting a corrupted value`() {
        // computenet-x9e.11's exact fixture: JMH's per-line relay (computenet-yhbd,
        // 3e23b915) can put ONE marker mid-line, but never fuses TWO markers onto one
        // line with no separator between them — that shape means the parser matched a
        // marker whose "value" runs into the next marker's text rather than stopping at
        // the fact it names. Before the fix this was ACCEPTED: cpuModel absorbed the
        // trailing marker and value ("Apple M2 Pro# Host core count: 10") while core
        // count still parsed off the same line. It must refuse instead.
        val log = """
            ${HostFacts.CPU_MODEL_PREFIX} Apple M2 Pro${HostFacts.CORE_COUNT_PREFIX} 10
            ${HostFacts.OS_PREFIX} Mac OS X 14.5
        """.trimIndent()

        val failure = shouldThrow<HostFactsUnknownException> {
            HostFacts.fromJmhLog(log, source = "<fixture>")
        }
        failure.message shouldContain HostFacts.CPU_MODEL_PREFIX
        failure.message shouldContain HostFacts.CORE_COUNT_PREFIX
    }

    @Test
    fun `refuses a log stating the CPU model two different ways`() {
        val failure = shouldThrow<HostFactsUnknownException> {
            HostFacts.fromJmhLog(
                """
                ${HostFacts.CPU_MODEL_PREFIX} Apple M2 Pro
                ${HostFacts.CORE_COUNT_PREFIX} 10
                ${HostFacts.OS_PREFIX} Mac OS X 14.5
                ${HostFacts.CPU_MODEL_PREFIX} AMD EPYC 7763
                """.trimIndent(),
                source = "<fixture>",
            )
        }
        failure.message shouldContain "not one run on one host"
    }
}
