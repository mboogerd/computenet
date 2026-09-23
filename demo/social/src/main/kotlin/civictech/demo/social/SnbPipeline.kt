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
 * When [build] is handed a [LocationRegistry], each `snb-authored` cell
 * registers the interest `Interest.Ranges([Range(k, k + 1)])` for its author
 * key `k` as it is spawned (feature `computenet-8eb53` design 8eb53-D4), so a
 * [FeedSession] can check a leg against `registry.interestOf(ref)` rather than
 * the `Interest.Total` an unregistered ref reads as.
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
 * Recovery (F7, `computenet-v10ou`) is [SocialRecovery]: it pre-spawns every
 * family's known keys FIRST, through [SocialGraph.spawnKnown] so each cell's
 * observe sink exists, then calls `host.recoverFrom` exactly ONCE against the
 * shared root WAL ([KeyedCells.hostJournal]`(journalDir)`). It never calls
 * `family.recover()`: **four `family.recover()` calls would replay nothing
 * at all, silently** — not, as this KDoc claimed until `computenet-5ab6f`,
 * replay the same host journal four times over. [KeyedCells.recover] resolves
 * `hostJournal` against its OWN per-family `journalDir`
 * (`kernel/src/main/kotlin/civictech/cell/host/KeyedCells.kt:89-92`), which
 * here is `<root>/person/host.journal` — a file this pipeline never writes —
 * so the replay half of each call finds an absent journal and does nothing.
 * The other half of [KeyedCells.recover], pre-spawning that family's
 * durably-known keys, is correct in itself, but it spawns through the family
 * alone and would leave [SocialGraph] with no observe sink for the key — which
 * is why the pre-spawn goes through [SocialGraph.spawnKnown] instead — and
 * the root-WAL `recoverFrom` must happen exactly once.
 *
 * `parse = String::toLong` is required on every family: [KeyedCells]'s
 * default `parse` is an unchecked identity cast from the keys-file `String`
 * to `K`, which is wrong for `Long` and would fail the moment a family's
 * `keys` file is read back (the first recovery).
 *
 * No links are wired here (F1 non-goal): [SocialGraph] reaches each cell's
 * inlet directly through the routed, journaled write path
 * (`host.lookup(TypedRef<SetApi<F>>(cell.ref))!!.inlet.call`, jo2jk-D1).
 *
 * **Which read paths are glitch-free-routed: none of them** (`[SOC1-ATOM-03]`,
 * feature `computenet-jadt6`). Every read this demo serves —
 * `SocialApp`'s `/state` and its `/events` SSE stream (which re-serves the same
 * `stateJson()`), and the IS1-IS7 short reads behind `/person/` and `/message/`
 * through [BoundedReader] — is a
 * [civictech.cell.host.ManagedHost.readState] page or a
 * [civictech.cell.observe.ObservationSink] snapshot
 * ([SocialGraph]'s per-cell sinks; F-21 records the same for [BoundedReader]),
 * and **not** routed through a
 * [civictech.cell.consistency.GlitchFreeCell]. No read path here waits for a
 * wave to be complete across the three cells one
 * [SocialGraph.addPost] writes, so a concurrent reader can see the post in one
 * of those cells and not yet in another. `[SOC1-ATOM-03]` is therefore covered
 * only by `SocialAtomicityTest`'s test-side, `manage.link`-fed (Consume-role)
 * [civictech.cell.consistency.GlitchFreeCell] path — and there only as
 * "the released contributions of one wave are contiguous and carry one wave
 * id", since that cell groups a wave rather than combining it. The measured
 * reason one `addPost` is three waves rather than one, and what a single-wave
 * ingress would cost, is `doc/demo-findings.md` F-22.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.refAs
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Interest
import java.io.File
import java.util.UUID

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
     * The four static cells likewise carry fixed refs ([staticIdentity]), so
     * their journaled frames replay onto the same cells after a restart —
     * and so [build] runs at most once per host.
     *
     * [registry], when given, receives each `snb-authored` cell's per-author
     * interest at spawn (8eb53-D4); `null` registers nothing.
     */
    fun build(host: ManagedHost, journalDir: File?, registry: LocationRegistry? = null): Graph {
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
                factory = { key: Long, ref: CellRef ->
                    registry?.setInterest(ref, Interest.Ranges(listOf(Interest.Ranges.Range(key, key + 1))))
                    SetCell<Message>(ref)
                },
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
                tags = spawn("snb-tags", identity = staticIdentity("snb-tags")) { ref -> SetCell<Tag>(ref) }.refAs(),
                tagClasses = spawn("snb-tagclasses", identity = staticIdentity("snb-tagclasses")) { ref ->
                    SetCell<TagClass>(ref)
                }.refAs(),
                places = spawn("snb-places", identity = staticIdentity("snb-places")) { ref -> SetCell<Place>(ref) }.refAs(),
                organisations = spawn("snb-organisations", identity = staticIdentity("snb-organisations")) { ref ->
                    SetCell<Organisation>(ref)
                }.refAs(),
            )
        }
        return Graph(families, statics)
    }

    /**
     * The four static cells' fixed, restart-stable identity (computenet-v10ou.1,
     * orchestrator decision on the task review): `nameUUIDFromBytes(name)`,
     * the same derivation [KeyedCells] uses per key. With the default fresh
     * ref each build minted new refs, so a recovering app's WAL frames for the
     * static sets targeted refs that no longer existed and dead-lettered as
     * `unknown cell` — the recovered app served zero tags/places/organisations.
     * The consequence: at most ONE [build] per host; a second on the same host
     * is refused as "Cell already spawned".
     */
    private fun staticIdentity(name: String): IdentityBinding =
        IdentityBinding.Exact(CellRef(UUID.nameUUIDFromBytes(name.toByteArray())))
}
