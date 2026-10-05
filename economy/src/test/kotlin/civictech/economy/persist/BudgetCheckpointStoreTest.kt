package civictech.economy.persist

import civictech.cell.ClaimClass
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `xbs8t-D1`, `66m-D9`: the checkpoint store is an atomic, one-file-per-scope
 * persistence boundary with strict decoding and typed configuration refusals.
 */
class BudgetCheckpointStoreTest {

    private var wallMillis = 123L

    private val state = LedgerState(
        policyLabel = "policy-a",
        sequence = 7,
        wallStampMillis = 123,
        buckets = listOf(
            BucketRecord(
                peer = "alice",
                claimClass = ClaimClass.Attention,
                balance = 2,
                heldTotal = 1,
                bootstrapLevel = 3,
                issuer = "anchor-a",
            ),
        ),
    )

    private fun store(directory: Path) = FileBudgetCheckpointStore(directory) { wallMillis }

    private fun checkpointFiles(directory: Path): List<Path> =
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList()
        }

    private fun checkpointFile(directory: Path): Path = checkpointFiles(directory).single()

    @Test
    fun `write then read round-trips exactly`(@TempDir directory: Path) {
        val store = store(directory)

        store.write("host-a", state)

        store.read("host-a") shouldBe CheckpointRead.Present(state)
    }

    @Test
    fun `leftover temporary file beside a good checkpoint is ignored`(@TempDir directory: Path) {
        val store = store(directory)
        store.write("host-a", state)
        val checkpoint = checkpointFile(directory)
        Files.writeString(checkpoint.resolveSibling("${checkpoint.fileName}.tmp"), "{")

        store.read("host-a") shouldBe CheckpointRead.Present(state)
    }

    @Test
    fun `scope file names are injective across characters a sanitizer could merge`(@TempDir directory: Path) {
        val store = store(directory)
        val slash = state.copy(sequence = 1)
        val underscore = state.copy(sequence = 2)

        store.write("a/b", slash)
        store.write("a_b", underscore)

        checkpointFiles(directory).size shouldBe 2
        store.read("a/b") shouldBe CheckpointRead.Present(slash)
        store.read("a_b") shouldBe CheckpointRead.Present(underscore)
    }

    @Test
    fun `missing directory is a typed refusal on read and write`(@TempDir parent: Path) {
        val missing = parent.resolve("missing")
        val store = store(missing)

        assertThrows<CheckpointStoreRefusedException> { store.read("scope") }.reason shouldBe
            CheckpointStoreRefusal.DIRECTORY_MISSING
        assertThrows<CheckpointStoreRefusedException> { store.write("scope", state) }.reason shouldBe
            CheckpointStoreRefusal.DIRECTORY_MISSING
    }

    @Test
    fun `regular file configured as directory is a typed refusal on read and write`(@TempDir parent: Path) {
        val notDirectory = parent.resolve("checkpoint-root")
        Files.writeString(notDirectory, "not a directory")
        val store = store(notDirectory)

        assertThrows<CheckpointStoreRefusedException> { store.read("scope") }.reason shouldBe
            CheckpointStoreRefusal.NOT_A_DIRECTORY
        assertThrows<CheckpointStoreRefusedException> { store.write("scope", state) }.reason shouldBe
            CheckpointStoreRefusal.NOT_A_DIRECTORY
    }

    @Test
    fun `garbage malformed missing and unknown fields are unreadable`(@TempDir directory: Path) {
        val store = store(directory)
        store.write("scope", state)
        val checkpoint = checkpointFile(directory)

        listOf(
            "not-json",
            "{",
            """{"policyLabel":"policy-a","sequence":7,"wallStampMillis":123}""",
            """{"policyLabel":"policy-a","sequence":7,"wallStampMillis":123,"buckets":[],"unknown":1}""",
        ).forEach { body ->
            Files.writeString(checkpoint, body)
            store.read("scope").shouldBeInstanceOf<CheckpointRead.Unreadable>()
        }
    }

    @Test
    fun `io failure while reading a present checkpoint is unreadable`(@TempDir directory: Path) {
        val store = store(directory)
        store.write("scope", state)
        val checkpoint = checkpointFile(directory)
        Files.delete(checkpoint)
        Files.createDirectory(checkpoint)

        store.read("scope").shouldBeInstanceOf<CheckpointRead.Unreadable>()
    }

    @Test
    fun `atomic move failure is an unwritable refusal`(@TempDir directory: Path) {
        val store = store(directory)
        store.write("scope", state)
        val checkpoint = checkpointFile(directory)
        Files.delete(checkpoint)
        Files.createDirectory(checkpoint)

        assertThrows<CheckpointStoreRefusedException> {
            store.write("scope", state.copy(sequence = 8))
        }.reason shouldBe CheckpointStoreRefusal.UNWRITABLE
    }

    @Test
    fun `serialized checkpoint descriptors carry exactly the decided fields`() {
        elementNames(LedgerState.serializer()) shouldContainExactlyInAnyOrder
            setOf("policyLabel", "sequence", "wallStampMillis", "buckets")
        elementNames(BucketRecord.serializer()) shouldContainExactlyInAnyOrder
            setOf("peer", "claimClass", "balance", "heldTotal", "bootstrapLevel", "issuer")
    }

    private fun elementNames(serializer: KSerializer<*>): Set<String> {
        val descriptor = serializer.descriptor
        return (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
    }
}
