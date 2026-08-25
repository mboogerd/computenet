package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.JoinEvent

/**
 * The reusable churn **seed stream** ([CHA3-53]).
 *
 * ## What it is for
 *
 * `96 §E3.5`'s delivered-watermark stability harness has to be run "under join/churn seeds"
 * (95 §R13's action). [CHA3-53] settles who supplies what: CHA3 **hands over its seeds and the
 * plans they derive**, and does not build or modify that harness. So this is the entry point
 * such a consumer calls — one function, a seed range in, generated plans out — rather than a
 * copy of [ChurnGenerator]'s call convention pasted into a second harness where it can drift.
 *
 * Everything here is a thin, documented wrapper over [ChurnGenerator.generate]; there is no
 * second generator and no second seed derivation. That is the point: a consumer that reaches
 * for `ChurnSeeds.plans(1L..50L)` gets exactly the plans a CHA3 sweep over the same range runs,
 * so a stability result and a churn result are about the *same* adversary.
 *
 * ## File placement
 *
 * This object's natural home is `ChurnGenerator.kt`, beside the function it wraps. It lives in
 * `LastReplicaProbe.kt` because that is the one `src/main` file computenet-umx.2.7's
 * `metadata.files` claim covers, and sibling tasks were scheduled in parallel against that
 * claim. Moving it is a pure file move with no call-site change; nothing below depends on the
 * placement.
 */
object ChurnSeeds {

    /**
     * The seeds of [range], as a list — the exact values a sweep iterates.
     *
     * Trivial, and public anyway, so that a consumer and a CHA3 sweep are demonstrably reading
     * the same seed set rather than two `for` loops that happen to agree today.
     */
    fun seeds(range: LongRange): List<Long> = range.toList()

    /**
     * The generated plan per seed of [range], under [config].
     *
     * Pure and order-preserving: `plans(a..b)[i]` is `ChurnGenerator.generate(a + i, config)`,
     * on any JVM ([CHA3-01], [CHA3-06]). A consumer may therefore pin one element by seed
     * rather than by index.
     */
    fun plans(range: LongRange, config: ChurnConfig = ChurnConfig()): List<ChurnPlan> =
        seeds(range).map { ChurnGenerator.generate(it, config) }

    /**
     * [plans] as a lazy sequence, for a consumer that runs a long range and does not want every
     * plan resident at once. Same values, same order.
     */
    fun planSequence(range: LongRange, config: ChurnConfig = ChurnConfig()): Sequence<ChurnPlan> =
        range.asSequence().map { ChurnGenerator.generate(it, config) }
}

/**
 * One declared journal's reading at the moment a probe ran ([CHA3-32]'s "durable store").
 *
 * @property journal the name the graph declared the journal under ([civictech.testkit.dst.Journals]).
 * @property records how many records its **base** (undecorated) log replays. The base, not the
 *   view: a journal fault's decoration is a lens on reads, and what "is at a durable store"
 *   asks about is what the store actually holds.
 * @property decorated whether a fault currently decorates it — reported so a reading taken
 *   under an active journal mutation is not mistaken for a clean one.
 */
data class DurableStoreReading(val journal: String, val records: Int, val decorated: Boolean) {

    /** Whether this store holds anything at all. */
    val holdsRecords: Boolean get() = records > 0

    fun summary(): String = "$journal: records=$records${if (decorated) " (decorated)" else ""}"
}

/** One peer's directory reading — what its [civictech.cell.host.LocationRegistry] still publishes. */
data class RegistryReading(val peer: String, val publishedInstances: List<Long>, val hostsReplica: Boolean) {
    fun summary(): String = "$peer: published=${publishedInstances.sorted()} hostsReplica=$hostsReplica"
}

/** One peer's delivered-watermark companion reading — the rows that outlived the mesh. */
data class WatermarkRowReading(
    val peer: String,
    val companionPresent: Boolean,
    val rows: Int,
    val closedRows: Int,
    val suspendedRows: Int,
) {
    fun summary(): String =
        if (!companionPresent) "$peer: no companion"
        else "$peer: rows=$rows closed=$closedRows suspended=$suspendedRows"
}

/**
 * BS-12's recorded observation ([CHA3-32], [CHA3-61]): what the kernel did when a plan drove
 * membership to one replica and then attempted that replica's departure.
 *
 * ## A value, not prose
 *
 * The findings task serialises this; it is deliberately not a log line and not a sentence. The
 * one string on it, [NO_HANDOFF_DEFINED], is a *constant* rather than free text, so no caller
 * can soften it.
 *
 * ## "Effective state present at no durable store" — the definition this file implements
 *
 * [CHA3-32] asks whether the last replica "holds effective state not present at any durable
 * store". Both halves are defined here, against what the graph itself declared, because CHA3
 * observes and MEM2 designs — inventing a richer notion of durability would be designing:
 *
 *  - **Effective state** is the survivor's own fold ([MeshPeer.foldSnapshot]) — the sorted
 *    membership of a replicated `SetCell`, or a `PnCounterCell`'s total. It is *effective* when
 *    it is non-empty and non-zero: a survivor holding an empty fold holds nothing that could be
 *    lost, so it is not the condition G-45's second clause is about.
 *  - **At a durable store** is: some journal the graph declared through
 *    [civictech.testkit.dst.Journals] has appended at least one record. A replica whose host was
 *    built with a `journalFor` selector over a declared journal that has appended its state is
 *    at a durable store; a replica on a host with no journal — which is every peer of
 *    [ChurnMesh] as it is built today — is not. The probe reads every declared journal rather
 *    than trying to attribute records to one replica, and reports **each** store's count
 *    ([durableStores]), so a reader can see the attribution the probe did not make.
 *
 * That definition is deliberately coarse in one direction and exact in the other: it can call a
 * replica "at a durable store" when the records belong to some *other* cell on the same journal
 * (a false negative for the last-replica condition, i.e. the conservative direction), and it
 * never calls a replica durable when nothing was journaled at all. A stronger per-cell
 * attribution would need to decode journal records, which is `civictech.cell.durability`'s wire
 * encoding and outside an evidence lane's remit.
 *
 * @property survivor the peer that remained.
 * @property departed the peers that left before it, in departure order.
 * @property reachablePeers `replicasOf(id) − {local}` as the survivor saw it — the exact set
 *   [civictech.cell.replication.Replication.evict] gates on.
 * @property evictDespawned what `evict` returned. `false` **is** the kernel's refusal; `true`
 *   would mean the gate did not hold and is a finding, not a pass.
 * @property stillMember whether the refused eviction left the replica in place.
 * @property rowSuspended whether the survivor's own delivered-watermark row reads PN-19
 *   SUSPENDED (the other half of the refusal: `evict` suspends rather than closing).
 * @property effectiveState the survivor's fold at the moment of the refusal.
 * @property durableStores every journal the graph declared, with its record count.
 */
data class LastReplicaReport(
    val survivor: String,
    val departed: List<String>,
    val reachablePeers: Int,
    val evictDespawned: Boolean,
    val stillMember: Boolean,
    val rowSuspended: Boolean,
    val effectiveState: Any?,
    val durableStores: List<DurableStoreReading>,
) {

    /** Whether the survivor holds anything that could be lost. See the type KDoc. */
    val holdsEffectiveState: Boolean
        get() = when (val state = effectiveState) {
            null -> false
            is Collection<*> -> state.isNotEmpty()
            is Number -> state.toLong() != 0L
            else -> true
        }

    /** Whether any declared journal holds a record. See the type KDoc for what this can and cannot say. */
    val atSomeDurableStore: Boolean get() = durableStores.any { it.holdsRecords }

    /**
     * [CHA3-32]'s last-replica condition: effective state present at no durable store.
     *
     * Reported, never asserted-away. A `false` here on a mesh with a journal is a legitimate
     * observation ("the survivor's state is durable"), not a failed probe.
     */
    val lastReplicaCondition: Boolean get() = holdsEffectiveState && !atSomeDurableStore

    /** Reporting only — it embeds run-varying values. Never a check's message. */
    fun summary(): String = buildString {
        append("last replica: survivor=$survivor departed=$departed reachablePeers=$reachablePeers")
        append("; evict despawned=$evictDespawned stillMember=$stillMember rowSuspended=$rowSuspended")
        append("; holdsEffectiveState=$holdsEffectiveState atSomeDurableStore=$atSomeDurableStore")
        append("; lastReplicaCondition=$lastReplicaCondition")
        append("; durableStores=[").append(durableStores.joinToString("; ") { it.summary() }).append("]")
        append("; ").append(NO_HANDOFF_DEFINED)
    }
}

/**
 * BS-13's recorded observation ([CHA3-62]): exactly what survived a crash of the last replica,
 * and where.
 *
 * Three "wheres", because they fail independently and a report naming only one would be read as
 * naming all of them:
 *
 *  - [durableStores] — the journals. On a mesh that declared none, this is empty, and that
 *    emptiness IS the observation.
 *  - [registryReadings] — the location directories. A crash despawns nothing and unpublishes
 *    nothing, so a directory entry naming a cell no host serves is expected to survive; the
 *    probe records whether it did rather than assuming.
 *  - [watermarkRows] — the delivered-watermark companions, per peer, including the peers that
 *    departed *before* the crash (their companions outlive their replicas).
 *
 * @property crashedPeer the last replica the plan crashed.
 * @property crashGeneration its host's rebuild generation — `1` after one crash.
 * @property liveMembers peers still holding a replica afterwards. Empty is the point.
 * @property lastFoldBeforeCrash the crashed peer's fold as the probe last saw it before the
 *   crash, or null when it was not sampled. Recorded so [durableStores] being empty can be read
 *   against *something*: state that existed and is now at no store.
 */
data class ZeroReplicaReport(
    val crashedPeer: String,
    val crashGeneration: Int,
    val liveMembers: List<String>,
    val lastFoldBeforeCrash: Any?,
    val durableStores: List<DurableStoreReading>,
    val registryReadings: List<RegistryReading>,
    val watermarkRows: List<WatermarkRowReading>,
) {

    /** Whether anything at all was journaled. */
    val atSomeDurableStore: Boolean get() = durableStores.any { it.holdsRecords }

    /** Directory entries that still name a replica nobody hosts — the crash's visible residue. */
    val danglingDirectoryEntries: List<RegistryReading> get() = registryReadings.filter {
        it.publishedInstances.isNotEmpty() && !it.hostsReplica
    }

    /** Reporting only — it embeds run-varying values. Never a check's message. */
    fun summary(): String = buildString {
        append("zero replicas: crashed=$crashedPeer generation=$crashGeneration liveMembers=$liveMembers")
        append("; lastFoldBeforeCrash=$lastFoldBeforeCrash atSomeDurableStore=$atSomeDurableStore")
        append("; durableStores=[").append(durableStores.joinToString("; ") { it.summary() }).append("]")
        append("; registries=[").append(registryReadings.joinToString("; ") { it.summary() }).append("]")
        append("; watermarks=[").append(watermarkRows.joinToString("; ") { it.summary() }).append("]")
        append("; ").append(NO_HANDOFF_DEFINED)
    }
}

/**
 * The boundary statement both reports carry, verbatim and as a constant ([CHA3-84], [CHA3-62]).
 *
 * It is a `const val` rather than a doc comment because [CHA3-62] asks the harness to *state* it
 * — a sentence a report renders, that no caller can reword and no findings pass can quietly
 * drop.
 */
const val NO_HANDOFF_DEFINED: String =
    "CHA3 defines NO last-replica handoff. G-45's second clause — graceful last-replica handoff " +
        "to durable storage (G-25) vs accidental deletion — remains UNDESIGNED and is owned by " +
        "MEM2; leader election is MEM1 (G-44). This report is an observation and an input to " +
        "that design, not a mechanism and not a direction."

/**
 * The last-replica probe ([CHA3-32], [CHA3-61], [CHA3-62]; BS-12, BS-13): generated plans that
 * drive a churn mesh's membership down, plus the recorded observation each one produces.
 *
 * ## Why the plans are built here rather than drawn from [ChurnGenerator]
 *
 * [ChurnGenerator] deliberately **never** generates a departure of the last member: its
 * `membershipEvents` degrades that draw to a bare reassignment, on the stated ground that
 * "emptying the mesh is not churn, it is the end of the run". That is right for a reconvergence
 * sweep and wrong for this probe, whose entire subject is the event the generator declines to
 * emit. So [downToOne] and [downToZero] construct the collapse directly, as
 * [civictech.testkit.dst.ChurnEvent]s the same executor fires, and take a seed only to stamp the
 * plan — the collapse itself is not random, because a probe of one specific kernel gate has
 * nothing to sample.
 *
 * A sweep that wants the *generated* adversary alongside the collapse folds one in:
 * `LastReplicaProbe.downToOne(seed).copy(faults = ChurnSeeds.plans(seed..seed).single().faults)`,
 * or `withFaults(...)` for CHA1 faults. Nothing here forbids it; it is simply not what the two
 * plans below are for.
 *
 * ## What it does not do
 *
 * No handoff, no durable-store design, no election, no kernel change ([CHA3-82], [CHA3-84]).
 * The refusal is *recorded*; it is not routed around, not repaired, and not proposed against.
 */
object LastReplicaProbe {

    /** Step of the joins. Every peer joins together, so the collapse is the only churn. */
    private const val JOIN_STEP = 1

    /**
     * A plan that evicts replicas until one remains, then **attempts departure of that last
     * one** (BS-12, [CHA3-61]).
     *
     * The last departure is `EVICT_CLEAN` — the ordinary graceful path, so that the refusal the
     * probe records is the one an operator would actually hit, not one manufactured by picking
     * the awkward mode. Its `evict` returns `false` and the replica suspends; the plan continues
     * through it rather than stopping at the last survivor, which is the whole of [CHA3-61].
     *
     * @param seed stamped onto the plan and its [ChurnConfig]-derived horizon. The collapse is
     *   deterministic; see the object KDoc for why it is not sampled.
     * @param peers how many peers the mesh starts with. Must be at least 2, or there is no
     *   collapse to drive — one peer is *already* the last replica and the plan would carry only
     *   the attempted departure.
     * @param departStride controller steps between departures. Generous by default: an eviction
     *   is a despawn, an unpublish, a final push-catch-up and a watermark close, and the next
     *   departure must see the directory that produced.
     * @param writesPerPeer how many workload writes each peer issues before the collapse starts,
     *   so the survivor genuinely holds effective state ([CHA3-32]) rather than an empty fold.
     */
    fun downToOne(
        seed: Long,
        peers: Int = 3,
        departStride: Int = 400,
        writesPerPeer: Int = 4,
        writeStride: Int = 50,
    ): ChurnPlan {
        require(peers >= 2) { "a down-to-one plan needs at least 2 peers to have a collapse to drive, got $peers" }
        val roster = List(peers) { "peer$it" }
        val writes = warmupWrites(roster, writesPerPeer, writeStride)
        val firstDeparture = (writes.maxOfOrNull { it.atStep } ?: JOIN_STEP) + departStride
        // Evict from the highest index down, so "the survivor" is always peer0 and a report is
        // readable without consulting the plan.
        val collapse = (peers - 1 downTo 1).mapIndexed { i, index ->
            DepartEvent("last-replica-evict-peer$index", "peer$index", firstDeparture + i * departStride, DepartureMode.EVICT_CLEAN)
        }
        val attempted = DepartEvent(
            "last-replica-attempt-peer0",
            "peer0",
            firstDeparture + (peers - 1) * departStride,
            DepartureMode.EVICT_CLEAN,
        )
        return plan(seed, roster, joins(roster) + collapse + attempted, writes, attempted.atStep + departStride)
    }

    /**
     * A plan that drives membership to one replica and then **crashes it without evict**
     * (BS-13, [CHA3-62]).
     *
     * `CRASH_UNCLEAN`, not a second eviction, precisely because eviction is the path that
     * announces and drains: a crash bypasses [civictech.cell.replication.Replication.evict]
     * entirely (`MeshPeer.crash()` discards the host and rebuilds it empty), so what survives
     * survives by accident rather than by protocol — which is the state G-45's second clause
     * calls "accidental deletion" and which MEM2 has to design against.
     */
    fun downToZero(
        seed: Long,
        peers: Int = 3,
        departStride: Int = 400,
        writesPerPeer: Int = 4,
        writeStride: Int = 50,
    ): ChurnPlan {
        require(peers >= 2) { "a down-to-zero plan needs at least 2 peers to have a collapse to drive, got $peers" }
        val roster = List(peers) { "peer$it" }
        val writes = warmupWrites(roster, writesPerPeer, writeStride)
        val firstDeparture = (writes.maxOfOrNull { it.atStep } ?: JOIN_STEP) + departStride
        val collapse = (peers - 1 downTo 1).mapIndexed { i, index ->
            DepartEvent("last-replica-evict-peer$index", "peer$index", firstDeparture + i * departStride, DepartureMode.EVICT_CLEAN)
        }
        val crash = DepartEvent(
            "last-replica-crash-peer0",
            "peer0",
            firstDeparture + (peers - 1) * departStride,
            DepartureMode.CRASH_UNCLEAN,
        )
        return plan(seed, roster, joins(roster) + collapse + crash, writes, crash.atStep + departStride)
    }

    /** The id of the event that attempts the last replica's departure in a [downToOne] plan. */
    const val ATTEMPTED_LAST_DEPARTURE: String = "last-replica-attempt-peer0"

    /** The id of the event that crashes the last replica in a [downToZero] plan. */
    const val LAST_REPLICA_CRASH: String = "last-replica-crash-peer0"

    // ------------------------------------------------------------------------------ observation

    /**
     * BS-12's report, read off [world] after a [downToOne] plan completed.
     *
     * Reads only; nothing here repairs, retries or heals. [survivor] defaults to `peer0`, which
     * is what [downToOne] leaves standing by construction.
     */
    fun observeLastReplica(world: DstWorld, survivor: String = "peer0"): LastReplicaReport {
        val peer = MeshPeers.require(world, survivor)
        val departed = MeshPeers.all(world).filter { it.name != survivor && it.lastDeparture != null }.map { it.name }
        return LastReplicaReport(
            survivor = survivor,
            departed = departed,
            reachablePeers = peer.reachablePeers(),
            // The plan's own attempted departure is what set this. Null would mean the attempt
            // never fired, which `ChurnMesh.allEventsFired` is the check for — recorded here as
            // `true` would be a lie, so the absence maps to "despawned" only when it really is.
            evictDespawned = peer.lastEvictDespawned ?: error(
                "peer \"$survivor\" never had an eviction attempted on it, so BS-12's refusal was " +
                    "never reached — the plan's \"$ATTEMPTED_LAST_DEPARTURE\" event did not fire",
            ),
            stillMember = peer.member,
            rowSuspended = StabilityObservables.rowSuspended(peer, peer),
            effectiveState = peer.foldSnapshot(),
            durableStores = durableStores(world),
        )
    }

    /**
     * BS-13's report, read off [world] after a [downToZero] plan completed.
     *
     * @param lastFoldBeforeCrash the crashed peer's fold as the caller sampled it *before* the
     *   crash. There is no way to recover it afterwards — that is the finding — so the caller
     *   supplies it or passes null and the report says it was not sampled.
     */
    fun observeZeroReplicas(
        world: DstWorld,
        crashed: String = "peer0",
        lastFoldBeforeCrash: Any? = null,
    ): ZeroReplicaReport {
        val peer = MeshPeers.require(world, crashed)
        val all = MeshPeers.all(world)
        return ZeroReplicaReport(
            crashedPeer = crashed,
            crashGeneration = peer.crashGeneration,
            liveMembers = all.filter { it.member }.map { it.name },
            lastFoldBeforeCrash = lastFoldBeforeCrash,
            durableStores = durableStores(world),
            registryReadings = all.map { p ->
                RegistryReading(
                    peer = p.name,
                    publishedInstances = p.visibleReplicas().map { it.instanceId },
                    hostsReplica = p.replica != null,
                )
            },
            watermarkRows = all.map { p ->
                val companion = p.replication.watermarkOf(p.ref.id)
                WatermarkRowReading(
                    peer = p.name,
                    companionPresent = companion != null,
                    rows = companion?.rows()?.size ?: 0,
                    closedRows = companion?.closed()?.size ?: 0,
                    suspendedRows = companion?.suspended()?.size ?: 0,
                )
            },
        )
    }

    /** Every journal the graph declared, with its base log's record count. */
    fun durableStores(world: DstWorld): List<DurableStoreReading> =
        world.journals.names().sorted().map { name ->
            DurableStoreReading(
                journal = name,
                records = world.journals.base(name).replay().size,
                decorated = world.journals.decorated(name),
            )
        }

    // ---------------------------------------------------------------------------------- checks

    /**
     * [CHA3-61] as a check: the attempted departure of the last replica must have been
     * **refused**, and the replica must still be there.
     *
     * A despawn here would mean the membership gate did not hold — a kernel divergence, which by
     * [CHA3-82] yields a pinned seed and a findings entry rather than a kernel fix in this lane.
     * The message is the fixed failure identity; every number is in [ChurnCheckFailure.detail].
     */
    fun refusalObserved(survivor: String = "peer0"): DstCheck = DstCheck { world ->
        val report = observeLastReplica(world, survivor)
        if (report.evictDespawned || !report.stillMember) {
            throw ChurnCheckFailure(
                "the last replica's departure was not refused",
                detail = report.summary(),
            )
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    private fun joins(roster: List<String>): List<ChurnEvent> =
        roster.map { JoinEvent("last-replica-join-$it", it, JOIN_STEP) }

    private fun warmupWrites(roster: List<String>, perPeer: Int, stride: Int): List<ChurnWrite> {
        var ordinal = 0
        return roster.flatMap { peer ->
            (0 until perPeer).map { ChurnWrite(JOIN_STEP + 1 + ordinal++ * stride, peer, ordinal - 1) }
        }.sortedBy { it.atStep }
    }

    private fun plan(
        seed: Long,
        roster: List<String>,
        events: List<ChurnEvent>,
        writes: List<ChurnWrite>,
        horizon: Int,
    ): ChurnPlan = ChurnPlan(
        seed = seed,
        config = ChurnConfig(
            peerCount = roster.size..roster.size,
            // The collapse is constructed, not generated: a non-zero eventCount here would
            // describe a plan this object does not produce.
            eventCount = 0,
            writeConcurrency = 0.0,
            partitionOverlap = 0.0,
            opScriptLength = writes.size,
            stepBudget = horizon + 1,
            suspendWindow = 4,
        ),
        peers = roster,
        events = events.sortedBy { it.atStep },
        writeSchedule = writes,
    )
}

// ===============================================================================================
// BS-14 — single-writer leader churn (CHA3-50, CHA3-51, CHA3-52)
// ===============================================================================================

/**
 * One sample of "who believes itself leader", taken at a known point in a leader transition.
 *
 * @property at the sample's position on the measurement's own clock. See
 *   [LeaderChurnMeasurement] for what that clock is and why it is not controller steps.
 * @property believedLeaders every instance whose own state says it is leading, by name.
 */
data class LeaderBeliefSample(val at: Int, val believedLeaders: List<String>) {
    val splitBrain: Boolean get() = believedLeaders.size > 1
    fun summary(): String = "@$at ${believedLeaders.sorted()}${if (splitBrain) " SPLIT" else ""}"
}

/** One write the measurement issued and the leader accepted. */
data class AcceptedWrite(val ordinal: Int, val acceptedBy: String, val duringSplitBrain: Boolean) {
    fun summary(): String = "#$ordinal by $acceptedBy${if (duringSplitBrain) " (split-brain)" else ""}"
}

/**
 * BS-14's measurement ([CHA3-50], [CHA3-51], [CHA3-52]): the split-brain window of one leader
 * transition, and the lost/duplicated-write accounting across it.
 *
 * ## Measurements, not verdicts
 *
 * Nothing on this type asserts anything. [splitBrainWindow] can legitimately be `0`, and a `0`
 * is a finding — see `SingleWriterChurnTest` for the branch that produces one and what it
 * means. [CHA3-52] forbids this harness implementing election or choosing between 95 §R1's
 * directions, so the report carries [interleaving] — *what the orchestrator did, in order* —
 * and lets R1's own design pass draw the conclusion.
 *
 * @property interleaving the ordered orchestration steps that produced this transition, as the
 *   driver named them. [CHA3-51] asks for "the interleaving that produced it" and this is it:
 *   with explicit designation there is no election to blame, so the order of the designation
 *   calls *is* the whole causal story.
 * @property samples the belief samples, in order.
 * @property accepted every write the measurement issued that some leader applied.
 * @property expectedTotal what the surviving state should hold if no accepted write was lost
 *   and none was applied twice — one unit per element of [accepted], by construction of the
 *   probe's payload.
 * @property observedTotal what the post-transition leader's state actually holds.
 */
data class LeaderChurnReport(
    val interleaving: List<String>,
    val samples: List<LeaderBeliefSample>,
    val accepted: List<AcceptedWrite>,
    val expectedTotal: Long,
    val observedTotal: Long,
) {

    /** Samples in which more than one instance believed itself leader. */
    val splitBrainSamples: List<LeaderBeliefSample> get() = samples.filter { it.splitBrain }

    /**
     * The measured window, in samples: `0` when no sample ever saw two leaders, otherwise the
     * span from the first split sample to the last, inclusive.
     *
     * A span rather than a count, because the question 95 §R1 asks is "how long", and a count
     * would report two split samples separated by a healed one as a window of 2.
     */
    val splitBrainWindow: Int
        get() {
            val split = splitBrainSamples
            return if (split.isEmpty()) 0 else split.last().at - split.first().at + 1
        }

    /** Accepted writes that never reached the surviving state. Negative is impossible; see [duplicatedWrites]. */
    val lostWrites: Long get() = (expectedTotal - observedTotal).coerceAtLeast(0)

    /** Accepted writes the surviving state counted more than once. */
    val duplicatedWrites: Long get() = (observedTotal - expectedTotal).coerceAtLeast(0)

    /** Writes accepted while more than one instance believed itself leader. */
    val acceptedDuringSplitBrain: List<AcceptedWrite> get() = accepted.filter { it.duringSplitBrain }

    /** Reporting only — it embeds run-varying counts. Never a check's message. */
    fun summary(): String = buildString {
        append("leader churn: window=$splitBrainWindow sample(s) of ${samples.size}")
        append("; accepted=${accepted.size} duringSplitBrain=${acceptedDuringSplitBrain.size}")
        append("; expectedTotal=$expectedTotal observedTotal=$observedTotal")
        append(" lost=$lostWrites duplicated=$duplicatedWrites")
        append("; interleaving=").append(interleaving)
        append("; samples=[").append(samples.joinToString("; ") { it.summary() }).append("]")
        if (accepted.isNotEmpty()) {
            append("; writes=[").append(accepted.joinToString("; ") { it.summary() }).append("]")
        }
    }
}

/**
 * The recorder BS-14's driver feeds ([CHA3-50], [CHA3-51]).
 *
 * ## The clock, and why it is not controller steps
 *
 * `SingleWriterReplication` ships **EXPLICIT/orchestrated** designation only — its own KDoc says
 * so ("this ticket ships EXPLICIT/orchestrated designation only —
 * `SingleWriterReplication.designateLeader` is the manual-failover hook the spec declares the
 * default"), and 95 §R1 is the research gate on the automatic half. A `LeaderMark` is folded
 * into **one peer's** `leaderMarks` map by a direct call; it is not gossiped, and no controller
 * step ever changes who believes itself leader. So a window measured in controller steps would
 * be identically zero for a reason that has nothing to do with leadership: the clock would be
 * measuring the wrong thing and would report a confident 0.
 *
 * The honest clock is therefore the **orchestration step** — one tick per action the operator
 * performs during the failover (designate here, designate there, issue a write, drain). That is
 * what [tick] advances and what [LeaderChurnReport.splitBrainWindow] is denominated in, and it
 * is why [LeaderChurnReport.interleaving] is a required field rather than a nicety: the number
 * only means something next to the order that produced it.
 *
 * ## Fixture-agnostic on purpose
 *
 * "Believes itself leader" is a property of the concrete
 * [civictech.cell.replication.SingleWriterReplicable] implementation (a `leading` flag on the
 * fixture), and `:testkit`'s main source set has no such fixture and should not grow one for a
 * measurement. So the driver supplies the belief read as a lambda and this class stays a
 * recorder — no kernel types, no fixture types, nothing to keep in step with either.
 */
class LeaderChurnMeasurement(
    /** The belief read: every instance name whose own state currently says it is leading. */
    private val believedLeaders: () -> List<String>,
) {
    private val steps = mutableListOf<String>()
    private val samples = mutableListOf<LeaderBeliefSample>()
    private val accepted = mutableListOf<AcceptedWrite>()
    private var at = 0

    /** Record one orchestration action and sample beliefs after it. Returns the sample taken. */
    fun tick(action: String): LeaderBeliefSample {
        steps += action
        val sample = LeaderBeliefSample(at++, believedLeaders())
        samples += sample
        return sample
    }

    /**
     * Record a write the leader accepted. `duringSplitBrain` is read from the beliefs **now**,
     * at the moment of acceptance, rather than from the last [tick]'s sample — a write issued
     * between two ticks belongs to the state of the world it was actually applied in.
     */
    fun acceptedWrite(ordinal: Int, acceptedBy: String) {
        accepted += AcceptedWrite(ordinal, acceptedBy, duringSplitBrain = believedLeaders().size > 1)
    }

    /** The report, against the surviving state's [observedTotal]. */
    fun report(observedTotal: Long): LeaderChurnReport = LeaderChurnReport(
        interleaving = steps.toList(),
        samples = samples.toList(),
        accepted = accepted.toList(),
        expectedTotal = accepted.size.toLong(),
        observedTotal = observedTotal,
    )
}

/**
 * BS-14's boundary statement, the counterpart of [NO_HANDOFF_DEFINED] ([CHA3-52], [CHA3-84]).
 */
const val NO_ELECTION_DEFINED: String =
    "CHA3 implements NO leader election and chooses NO 95 §R1 direction. Designation here is the " +
        "EXPLICIT/orchestrated hook the kernel already ships (SingleWriterReplication.designateLeader); " +
        "the automatic half is G-44/MEM1 and is research-gated. Every number above is a measurement."
