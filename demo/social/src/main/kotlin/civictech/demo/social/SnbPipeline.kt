/**
 * The `:demo:social` cell topology (SOC1, epic `computenet-07k`), decided in
 * feature `computenet-jo2jk` design jo2jk-D4.
 *
 * Four durable, dynamically-sized [KeyedCells]`<Long>` families carry the
 * per-entity SNB facts — `snb-person` -> [PersonFact], `snb-authored` ->
 * [Message], `snb-forum` -> [ForumFact], `snb-message` -> [MessageFact] — plus
 * four static [SetCell]s for the small SNB dimension tables (tags, tag
 * classes, places, organisations), spawned once through [graphOf].
 *
 * **No global message table** (07k-D1): a message's body is held twice — once
 * in the author's `snb-authored` cell (the [Message] itself, added by
 * [SocialGraph.addPost]/[SocialGraph.addComment]) and once in the owning
 * `snb-message` cell as [MessageFact.Body] — so an IS4-IS7-shaped read by
 * message id stays one keyed read, never a scan over every author's stream
 * ([SOC1-FEED-02]).
 *
 * **Journal layout** (jo2jk-D4): each family gets its OWN subdirectory of
 * [build]'s `journalDir` — `person/`, `authored/`, `forum/`, `message/` —
 * because two [KeyedCells] families sharing one `journalDir` collide on the
 * family-local `keys` file (observed: `demo/shopping`'s `Main.kt:136-139`
 * comment on its own per-key writer family). The host write-ahead journal
 * itself stays at the ROOT of `journalDir`
 * ([KeyedCells.hostJournal]`(journalDir)`), one level ABOVE all four
 * subdirectories: the tree is `<root>/host.journal` beside `<root>/person/`,
 * `<root>/authored/`, `<root>/forum/` and `<root>/message/`, each holding its
 * own `keys` log. No family's `keys` log is a sibling of the WAL (corrected
 * under `computenet-5ab6f`; observed tree after the jo2jk-D6 op sequence:
 * `<root>/host.journal`, `<root>/person/keys`, `<root>/authored/keys`,
 * `<root>/forum/keys`, `<root>/message/keys`).
 *
 * A later feature (F7, `computenet-v10ou`) recovers by pre-spawning every
 * family's known keys FIRST, then calling
 * `host.recoverFrom(KeyedCells.hostJournal(journalDir))` exactly ONCE against
 * the shared root WAL. **Four `family.recover()` calls would replay nothing
 * at all, silently** — not, as this KDoc claimed until `computenet-5ab6f`,
 * replay the same host journal four times over. [KeyedCells.recover] resolves
 * `hostJournal` against its OWN per-family `journalDir`
 * (`kernel/src/main/kotlin/civictech/cell/host/KeyedCells.kt:89-92`), which
 * here is `<root>/person/host.journal` — a file this pipeline never writes —
 * so the replay half of each call finds an absent journal and does nothing.
 * The other half of [KeyedCells.recover], pre-spawning that family's
 * durably-known keys, is correct and is a perfectly good way to do the
 * pre-spawn step above; it is only the root-WAL `recoverFrom` that must
 * happen exactly once.
 *
 * `parse = String::toLong` is required on every family: [KeyedCells]'s
 * default `parse` is an unchecked identity cast from the keys-file `String`
 * to `K`, which is wrong for `Long` and would fail the moment a family's
 * `keys` file is read back (the first recovery).
 *
 * No links are wired here (F1 non-goal): [SocialGraph] reaches each cell's
 * inlet directly through the routed, journaled write path
 * (`host.lookup(TypedRef<SetApi<F>>(cell.ref))!!.inlet.call`, jo2jk-D1).
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.refAs
import civictech.cell.host.KeyedCells
import civictech.cell.host.ManagedHost
import java.io.File

object SnbPipeline {
    /** The four per-entity keyed families, each its own [KeyedCells]`<Long>`. */
    class Families(
        val person: KeyedCells<Long>,
        val authored: KeyedCells<Long>,
        val forum: KeyedCells<Long>,
        val message: KeyedCells<Long>,
    )

    /** The four static SNB dimension sets, typed refs into cells spawned once via [graphOf]. */
    data class Statics(
        val tags: TypedRef<SetApi<Tag>>,
        val tagClasses: TypedRef<SetApi<TagClass>>,
        val places: TypedRef<SetApi<Place>>,
        val organisations: TypedRef<SetApi<Organisation>>,
    )

    data class Graph(val families: Families, val statics: Statics)

    /**
     * Spawns the four keyed families and the four static dimension cells on
     * [host], durable under [journalDir] (`null` = ephemeral, matching every
     * [KeyedCells] family). Two `build` calls against two hosts mint the same
     * per-key refs for the same namespace+key ([SOC1-SCHEMA-05]), since
     * [KeyedCells]'s ref derivation is a pure function of namespace and key.
     */
    fun build(host: ManagedHost, journalDir: File?): Graph {
        val families = Families(
            person = KeyedCells<Long>(
                host = host,
                journalDir = journalDir?.resolve("person"),
                namespace = "snb-person",
                factory = { _: Long, ref: CellRef -> SetCell<PersonFact>(ref) },
                parse = String::toLong,
            ),
            authored = KeyedCells<Long>(
                host = host,
                journalDir = journalDir?.resolve("authored"),
                namespace = "snb-authored",
                factory = { _: Long, ref: CellRef -> SetCell<Message>(ref) },
                parse = String::toLong,
            ),
            forum = KeyedCells<Long>(
                host = host,
                journalDir = journalDir?.resolve("forum"),
                namespace = "snb-forum",
                factory = { _: Long, ref: CellRef -> SetCell<ForumFact>(ref) },
                parse = String::toLong,
            ),
            message = KeyedCells<Long>(
                host = host,
                journalDir = journalDir?.resolve("message"),
                namespace = "snb-message",
                factory = { _: Long, ref: CellRef -> SetCell<MessageFact>(ref) },
                parse = String::toLong,
            ),
        )
        val (statics, _) = graphOf(host.managementInlet) {
            Statics(
                tags = spawn("snb-tags") { ref -> SetCell<Tag>(ref) }.refAs(),
                tagClasses = spawn("snb-tagclasses") { ref -> SetCell<TagClass>(ref) }.refAs(),
                places = spawn("snb-places") { ref -> SetCell<Place>(ref) }.refAs(),
                organisations = spawn("snb-organisations") { ref -> SetCell<Organisation>(ref) }.refAs(),
            )
        }
        return Graph(families, statics)
    }
}
