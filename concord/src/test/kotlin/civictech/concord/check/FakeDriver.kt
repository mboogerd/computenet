package civictech.concord.check

import civictech.concord.driver.Blob
import civictech.concord.driver.CellId
import civictech.concord.driver.DeadLetter
import civictech.concord.driver.Driver
import civictech.concord.driver.Effect
import civictech.concord.driver.HostId
import civictech.concord.driver.LinkRef
import civictech.concord.driver.LinkResult
import civictech.concord.driver.QuiesceReport
import civictech.concord.driver.ReadCursor
import civictech.concord.driver.ReadPage
import civictech.concord.driver.WavePlane
import civictech.concord.schema.Scenario
import civictech.concord.value.Value

/**
 * A pure, canned [Driver] for check unit tests — no kernel, no scheduling. Only
 * the read-side verbs the checks consume are backed by fixtures; the mutating
 * verbs are inert. This keeps the check tests fast and independent of W1-A.
 */
class FakeDriver(
    private val views: Map<CellId, Value> = emptyMap(),
    private val observations: Map<CellId, List<Value>> = emptyMap(),
    private val deadLetters: List<DeadLetter> = emptyList(),
    private val effects: Map<CellId, List<Effect>> = emptyMap(),
) : Driver {
    override fun createHost(hostId: HostId) {}
    override fun spawn(hostId: HostId, cellId: CellId, type: String, params: Map<String, Value>) {}
    override fun connect(from: CellId, to: CellId, inlet: String?, outlet: String?, role: String?): LinkResult =
        LinkResult.Connected("$from->$to")
    override fun disconnect(linkRef: LinkRef): LinkResult = LinkResult.Connected(linkRef)
    override fun apply(cellId: CellId, op: String, value: Value?) {}
    override fun quiesce(budget: Int): QuiesceReport = QuiesceReport(settled = true, steps = 0)
    override fun readView(cellId: CellId): Value =
        views[cellId] ?: error("FakeDriver: no view fixture for '$cellId'")
    override fun observationLog(cellId: CellId): List<Value> = observations[cellId].orEmpty()
    // A bounded read is an *event* the runner performs and records into
    // CheckContext.reads; a check never calls these back. The fixtures the two
    // read-side checks consume are ReadWalk values handed to FakeContext
    // directly, so these stay inert (and loud, so a check that started calling
    // them would be caught rather than silently fed an empty page).
    override fun readState(cellId: CellId, cursor: ReadCursor?, limit: Int): ReadPage =
        error("FakeDriver: read-state is performed by the runner, not by a check")
    override fun wavePlane(cellId: CellId): WavePlane =
        error("FakeDriver: the wave plane is sampled by the runner around a read, not by a check")
    override fun snapshot(cellId: CellId): Blob = ByteArray(0)
    override fun restore(hostId: HostId, cellId: CellId, blob: Blob) {}
    override fun restart(cellId: CellId) {}
    override fun retransmit(
        cellId: CellId,
        inlet: String?,
        source: CellId,
        counter: Long,
        op: String,
        value: Value?,
    ) {
    }
    override fun despawn(cellId: CellId) {}
    override fun deadLetters(): List<DeadLetter> = deadLetters
    override fun effectLog(cellId: CellId): List<Effect> = effects[cellId].orEmpty()
}

/**
 * Trivial [CheckContext] pairing a [FakeDriver] with the [Scenario] under test,
 * plus any [ReadWalk] fixtures the read-side checks consume (default: none, so
 * every pre-V1C-CONCORD test is unaffected).
 */
class FakeContext(
    override val driver: Driver,
    override val scenario: Scenario,
    override val reads: List<ReadWalk> = emptyList(),
) : CheckContext
