package civictech.dialogue

import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers `:demo:dialogue`'s wire-capable payload types with the wire codec
 * (journal + wire capable) — the repo's standard `WireSerializers`
 * contribution, loaded from `META-INF/services/civictech.cell.wire.WireSerializers`
 * at process start. Mirrors `AgoraWireSerializers`.
 *
 * Only [Utterance] is registered: it is the only dialogue payload the WAL
 * ever encodes ([DialogueRuntime.isDurable] — the derived pipeline is
 * deliberately volatile).
 *
 * **The mint vocabulary stays deliberately non-wire-capable** (decided in
 * computenet-zxt5). `RelationCandidate`, `ClaimAggregate`, `RelationAggregate`,
 * `StanceAggregate`, `StanceJoinRow` and the two provenance entries carry no
 * `@kotlinx.serialization.Serializable` and are registered nowhere, and their
 * `GroupByCell` keys (`Pair<String, ClaimKey>`, `ClaimKey`, `RelationKey`)
 * have no polymorphic registration under `WireCodec`'s `Any`-rooted scope
 * either. Two reasons, in this order:
 *
 * 1. Nothing wants it. The derived pipeline's volatility is the *recovery
 *    design* ([DialogueRuntime.isDurable]), not a limitation, and
 *    `:demo:dialogue` has no `:wire` dependency — no dialogue cell can cross
 *    a transport boundary today.
 * 2. Registering the key shapes here would be dead weight that also reads as
 *    an invitation: a live registration says "these payloads cross the wire",
 *    which would contradict the recovery design the volatility implements.
 *
 * What it is *not* is impossible. `MintWireCapabilityTest` measures both
 * halves — that the key shapes are unencodable today, and that an ordinary
 * `WireSerializers` contribution (`PairSerializer` over the polymorphic `Any`
 * serializer, plus the value class's generated serializer) makes them encode.
 * See `GroupByCell`'s KDoc for the general form of that decision; when
 * something does want a derived stage durable or bridged, that test is the
 * worked mechanism to lift, and its first arms are the tripwire that says
 * this decision has been overtaken.
 */
class DialogueWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Utterance::class)
        }
    }
}
