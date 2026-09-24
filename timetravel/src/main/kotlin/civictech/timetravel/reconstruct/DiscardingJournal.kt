package civictech.timetravel.reconstruct

import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal

/**
 * A [Journal] that retains nothing at all — strictly less than
 * [DurabilityClass.IN_MEMORY] promises for an ordinary in-memory journal,
 * which at least survives the process ([InMemoryJournal][civictech.cell.durability.InMemoryJournal]
 * does). `append` and `reset` are no-ops and `replay()` is always empty.
 *
 * Exists for one reason (6tm33-D9, KFX-12 / `[24-DUR-04]`): [HostDurability.installDurableEpochs][civictech.cell.host.HostDurability.installDurableEpochs]
 * returns early — leaving the outlet on a freshly minted, random epoch —
 * whenever the host's `journalFor` selector answers `null` for a cell
 * (confirmed by reading `HostDurability.installDurableEpochs` at
 * `origin/main` 7991aeda: `if (journalSelector(cellRef) == null) return`
 * precedes `outlet.adoptWaveState(OutletWaveState.durable(outlet.ref))`).
 * The reconstruction host must never journal anything (`[TTD1-12]`), but
 * every one of its outlets must still land on the same ref-derived epoch the
 * live run journaled, or a replayed source's re-emission would carry a
 * `sourceId` the network never observed, defeating wave dedup for any linked
 * downstream cell. Handing the reconstruction host `journalFor = { DiscardingJournal }`
 * — a non-null selector for every cell — satisfies that gate without writing
 * or retaining anything anywhere: the kernel's own epoch-adoption code runs
 * unmodified, and this object is the sink it writes into.
 */
internal object DiscardingJournal : Journal {
    override val durability: DurabilityClass = DurabilityClass.IN_MEMORY

    override fun append(record: ByteArray) = Unit

    override fun replay(): List<ByteArray> = emptyList()

    override fun reset(records: List<ByteArray>) = Unit
}
