package civictech.demo.allocatorobserve.declaration

import civictech.cell.data.SetCell
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DeclarationIngesterTest {

    @TempDir
    lateinit var dir: Path

    private val declarationPath: Path get() = dir.resolve("allocation.yaml")

    private val firstDeclaration =
        AllocationDeclaration(
            weights = mapOf("computenet" to 60.0, "glass-factory" to 40.0),
            monthlyCapHours = 100.0,
            window = null,
        )

    private val secondDeclaration =
        AllocationDeclaration(
            weights = mapOf("computenet" to 30.0, "glass-factory" to 70.0),
            monthlyCapHours = 100.0,
            window = null,
        )

    private val firstObservedAt = Instant.parse("2026-08-30T10:00:00Z")
    private val secondObservedAt = Instant.parse("2026-08-30T11:00:00Z")

    private fun declaration(
        computenet: Int,
        glassFactory: Int,
        formatting: String = "block",
    ): String =
        when (formatting) {
            "block" ->
                """
                projects:
                  computenet: $computenet
                  glass-factory: $glassFactory
                monthly_cap:
                  hours: 100
                """.trimIndent()

            "inline" ->
                "projects: { glass-factory: $glassFactory, computenet: $computenet }\n" +
                    "monthly_cap: { hours: 100 }"

            else -> error("unknown test formatting: $formatting")
        }

    private fun write(content: String) {
        Files.writeString(declarationPath, content)
    }

    private fun clock(vararg observations: Instant): () -> Instant {
        require(observations.toList().zipWithNext().all { (a, b) -> a < b }) {
            "test observation clocks must be strictly increasing"
        }
        val iterator = observations.iterator()
        return { check(iterator.hasNext()) { "test clock exhausted" }; iterator.next() }
    }

    @Test
    fun `first valid observation appends a timestamped declaration`() {
        write(declaration(60, 40))
        val ingester = DeclarationIngester(declarationPath, clock = clock(firstObservedAt))

        val outcome = ingester.poll().shouldBeInstanceOf<DeclarationPollOutcome.Appended>()

        outcome.event shouldBe DeclarationEvent(firstObservedAt, firstDeclaration)
        ingester.history() shouldBe listOf(outcome.event)
        ingester.parseFailures shouldBe 0L
    }

    @Test
    fun `different content appends once and byte-identical or reformatted content is unchanged`() {
        val history = SetCell<DeclarationEvent>()
        write(declaration(60, 40))
        val ingester = DeclarationIngester(
            declarationPath,
            history,
            clock(firstObservedAt, secondObservedAt),
        )

        ingester.poll() shouldBe DeclarationPollOutcome.Appended(
            DeclarationEvent(firstObservedAt, firstDeclaration),
        )

        write(declaration(30, 70))
        ingester.poll() shouldBe DeclarationPollOutcome.Appended(
            DeclarationEvent(secondObservedAt, secondDeclaration),
        )

        write(declaration(30, 70))
        ingester.poll() shouldBe DeclarationPollOutcome.Unchanged

        write(declaration(30, 70, formatting = "inline"))
        ingester.poll() shouldBe DeclarationPollOutcome.Unchanged

        ingester.history() shouldBe
            listOf(
                DeclarationEvent(firstObservedAt, firstDeclaration),
                DeclarationEvent(secondObservedAt, secondDeclaration),
            )
    }

    @Test
    fun `malformed content increments failures and recovery appends from the last valid declaration`() {
        val history = SetCell<DeclarationEvent>()
        write(declaration(30, 70))
        val ingester = DeclarationIngester(
            declarationPath,
            history,
            clock(firstObservedAt, secondObservedAt),
        )
        ingester.poll()

        write("projects: [not a declaration")
        ingester.poll() shouldBe DeclarationPollOutcome.ParseFailed
        ingester.parseFailures shouldBe 1L
        ingester.history() shouldBe listOf(DeclarationEvent(firstObservedAt, secondDeclaration))

        write(declaration(60, 40))
        ingester.poll() shouldBe DeclarationPollOutcome.Appended(
            DeclarationEvent(secondObservedAt, firstDeclaration),
        )
        ingester.parseFailures shouldBe 1L
    }

    @Test
    fun `absent file is not a parse failure`() {
        val ingester = DeclarationIngester(declarationPath)

        ingester.poll() shouldBe DeclarationPollOutcome.Absent
        ingester.parseFailures shouldBe 0L
        ingester.history() shouldBe emptyList()
    }

    @Test
    fun `restart derives current declaration from the existing history cell`() {
        val history = SetCell<DeclarationEvent>()
        write(declaration(60, 40))
        val first = DeclarationIngester(declarationPath, history, clock(firstObservedAt))
        first.poll()

        val restarted = DeclarationIngester(declarationPath, history, clock(secondObservedAt))
        restarted.poll() shouldBe DeclarationPollOutcome.Unchanged
        restarted.parseFailures shouldBe 0L

        // The history remains consumable even when the source file is gone.
        Files.delete(declarationPath)
        restarted.history() shouldBe listOf(DeclarationEvent(firstObservedAt, firstDeclaration))
    }
}
