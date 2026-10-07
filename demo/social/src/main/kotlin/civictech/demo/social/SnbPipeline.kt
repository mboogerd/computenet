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
 * the `Interest.Total` an unregistered ref reads as. With [interestDriven],
 * that authored family is declared `spawnOnInterest`; the kernel materializes
 * every bounded key before the declaring admission completes.
 *
 * **Journal layout** (jo2jk-D4): [build] still supplies the four named family
 * journal directories — `person/`, `authored/`, `forum/`, `message/` — to the
 * [KeyedFamily] configuration because its `journalId` is part of the recorded
 * graph shape. Those directories are not membership stores and [SocialRecovery]
 * does not read them. Each first key spawn records a `TopoEvent.FamilyKey` in
 * the journal selected for that key's cell; here all four families use the
 * host's shared root WAL ([KeyedCells.hostJournal]`(journalDir)`). The durable
 * layout is therefore `<root>/host.journal`; there is no family-local
 * membership side file to keep in sync.
 *
 * Recovery (F7, `computenet-v10ou`) is [SocialRecovery]: it calls
 * `host.recoverFrom` exactly ONCE against the shared root WAL. During that
 * replay, each `FamilyKey` is decoded and synchronously registered with its
 * family and spawned before a later frame for that key is submitted. Once the
 * WAL replay is staged, [SocialGraph.spawnKnown] attaches each key's observe
 * sink. The app does not run an app-side pre-spawn pass. It also does not call
 * `family.recover()`: that convenience entry point resolves its [KeyedCells]
 * `journalDir`, whereas this composition replays the root WAL directly.
 *
 * Every family is configured with [KeyCodec.Longs], whose `parse` function is
 * `String::toLong`: the graph key codec renders a `Long` into the `FamilyKey`
 * topology record, and recovery must decode that rendered value back to
 * `Long` before spawning the cell under its deterministic ref.
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
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.KeyCodec
import civictech.cell.graph.KeyedCellFactory
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
     * interest at spawn (8eb53-D4); `null` registers nothing. [interestDriven]
     * opts only that family into the kernel's interest-driven spawn policy.
     */
    fun build(
        host: ManagedHost,
        journalDir: File?,
        registry: LocationRegistry? = null,
        interestDriven: Boolean = false,
    ): Graph {
        val context = ApplyContext(
            host = host,
            journalDirs = journalDir?.let { root ->
                mapOf(
                    "person" to root.resolve("person"),
                    "authored" to root.resolve("authored"),
                    "forum" to root.resolve("forum"),
                    "message" to root.resolve("message"),
                )
            } ?: emptyMap(),
        )
        @Suppress("UNCHECKED_CAST")
        fun <K : Any> asKeyedCells(family: KeyedCells<Any>): KeyedCells<K> = family as KeyedCells<K>

        val (graph, _) = graphOf(context) {
            val families = Families(
                person = asKeyedCells(
                    family(
                        name = "snb-person",
                        namespace = "snb-person",
                        keys = KeyCodec.Longs,
                        journalId = journalDir?.let { "person" },
                        factory = KeyedCellFactory { _, ref -> SetCell<PersonFact>(ref) },
                    ),
                ),
                authored = asKeyedCells(
                    family(
                        name = "snb-authored",
                        namespace = "snb-authored",
                        keys = KeyCodec.Longs,
                        journalId = journalDir?.let { "authored" },
                        spawnOnInterest = interestDriven,
                        factory = KeyedCellFactory { key, ref ->
                            val author = key as Long
                            registry?.setInterest(ref, Interest.Ranges(listOf(Interest.Ranges.Range(author, author + 1))))
                            SetCell<Message>(ref)
                        },
                    ),
                ),
                forum = asKeyedCells(
                    family(
                        name = "snb-forum",
                        namespace = "snb-forum",
                        keys = KeyCodec.Longs,
                        journalId = journalDir?.let { "forum" },
                        factory = KeyedCellFactory { _, ref -> SetCell<ForumFact>(ref) },
                    ),
                ),
                message = asKeyedCells(
                    family(
                        name = "snb-message",
                        namespace = "snb-message",
                        keys = KeyCodec.Longs,
                        journalId = journalDir?.let { "message" },
                        factory = KeyedCellFactory { _, ref -> SetCell<MessageFact>(ref) },
                    ),
                ),
            )
            val statics = Statics(
                tags = spawn("snb-tags", identity = staticIdentity("snb-tags")) { ref -> SetCell<Tag>(ref) }.refAs(),
                tagClasses = spawn("snb-tagclasses", identity = staticIdentity("snb-tagclasses")) { ref ->
                    SetCell<TagClass>(ref)
                }.refAs(),
                places = spawn("snb-places", identity = staticIdentity("snb-places")) { ref -> SetCell<Place>(ref) }.refAs(),
                organisations = spawn("snb-organisations", identity = staticIdentity("snb-organisations")) { ref ->
                    SetCell<Organisation>(ref)
                }.refAs(),
            )
            Graph(families, statics)
        }
        return graph
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
