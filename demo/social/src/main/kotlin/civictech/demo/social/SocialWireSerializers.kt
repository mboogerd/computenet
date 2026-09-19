package civictech.demo.social

import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers every `:demo:social` payload type that can reach a journaled
 * inlet with `WireCodec`'s polymorphic `Any` scope (bug `computenet-5ab6f`),
 * discovered through `META-INF/services/civictech.cell.wire.WireSerializers`
 * exactly as `:demo:agora`'s `AgoraWireSerializers` and `:demo:tiering`'s
 * `TieringWireSerializers` are.
 *
 * **Why this class has to exist.** `SocialGraph` writes through the *routed*
 * hosted inlet (`host.lookup(TypedRef<SetApi<F>>(ref))!!.inlet.call`,
 * jo2jk-D1), so with a non-null `journalDir` `ManagedHost` write-ahead
 * encodes each accepted invocation — payload included — through
 * `WireCodec`'s `polymorphic(Any)` scope before staging it. None of these
 * types was registered there, which is why under `--journal` **every** `/op`
 * threw `SerializationException: Serializer for subclass 'Profile' is not
 * found in the polymorphic scope of 'Any'` (`'Knows'` for `action=knows`),
 * nothing was ever journaled, and — `SerializationException` being an
 * `IllegalArgumentException` — the client saw a 400 validation error.
 *
 * **What the list has to cover: every type ever passed to a `SetOps.add` /
 * `remove` in [SocialGraph]**, which is the set of element types of the four
 * keyed families and the four static dimension cells. Container types
 * ([Person], [Forum], [Message]) are reachable only as properties of a
 * registered arm ([PersonFact.Profile], [ForumFact.Info],
 * [MessageFact.Body]) — a property is encoded by its static type, so it needs
 * `@Serializable` (it has it) but no polymorphic registration — except
 * [Message], which `snb-authored` holds as its element type directly, so it
 * is registered here too. [Person]/[Forum] are deliberately NOT registered:
 * a contribution that re-registers a type another contribution (or the kernel
 * baseline) already holds fails codec construction, so the list is kept to
 * exactly what is needed rather than to everything in `Schema.kt`.
 */
class SocialWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            // snb-person elements
            subclass(PersonFact.Profile::class)
            subclass(Knows::class)
            subclass(PersonFact.StudyAt::class)
            subclass(PersonFact.WorkAt::class)
            subclass(PersonFact.HasInterest::class)
            // snb-authored elements
            subclass(Message::class)
            // snb-forum elements
            subclass(ForumFact.Info::class)
            subclass(ForumFact.Member::class)
            subclass(ForumFact.Contains::class)
            subclass(ForumFact.HasTag::class)
            // snb-message elements
            subclass(MessageFact.Body::class)
            subclass(MessageFact.Reply::class)
            subclass(MessageFact.LikedBy::class)
            subclass(MessageFact.HasTag::class)
            // the four static dimension sets
            subclass(Tag::class)
            subclass(TagClass::class)
            subclass(Place::class)
            subclass(Organisation::class)
        }
    }
}
