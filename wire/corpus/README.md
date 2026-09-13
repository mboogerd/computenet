# ComputeNet wire — the prose companion

The document a TS/Rust implementer reads first (epic computenet-ncz, WIR1,
`[WIR1-C13]`). It states the current wire behaviour in prose; `SCHEMA.md`
defines the vector document format that pins that behaviour in bytes;
`NONDETERMINISM.md` is the honesty ledger for what cannot be pinned yet.

Written against this task's base commit `de75da04e` (verify:
`git -C wire/corpus/.. log -1 --format=%H de75da04e`). A claim here that is
not backed by a cited test or a command run during this task carries
`unverified:`.

## Contents

1. [What this corpus is](#1-what-this-corpus-is)
2. [Transport framing](#2-transport-framing)
3. [JSON options in force](#3-json-options-in-force)
4. [The envelope: WireFrame](#4-the-envelope-wireframe)
5. [The version rule](#5-the-version-rule)
6. [The unknown-key rule](#6-the-unknown-key-rule)
7. [PORT_PROTOCOL frames](#7-port_protocol-frames)
8. [Payload discriminators](#8-payload-discriminators)
9. [Identity and ids](#9-identity-and-ids)

## 1. What this corpus is

`wire/corpus/` pins the wire format **as this JVM implementation behaves
today** — every byte example and every rule below is a fact about
`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt` and
`wire/src/main/kotlin/civictech/wire/{WsTransport,HelloProtocol}.kt` at the
base commit above, not a derivation from `doc/spec/`. Per the epic's
"Ambition": the vector is wrong until a spec change says otherwise — a future
spec change that alters the wire is a corpus update, not the other way
around. This corpus does **not** prove a non-JVM peer conforms; that is
WIR2's job, against these vectors as fixtures. It lives here, under
`wire/corpus/`, and not under `concord/`: `concord/` is the executable
spec↔code conformance suite driven from `doc/spec/` scenario YAML, and this
corpus has no scenario grammar, no `doc/spec/` `covers:` ids, and is read by
a wire driver, not by concord's harness (ncz-D1). See `SCHEMA.md` for the
vector document format and `NONDETERMINISM.md` for the ledger of bytes this
corpus cannot pin deterministically.

## 2. Transport framing

The handshake is **one WebSocket TEXT message per side**, sent first on the
connection, before any binary frame. `WsTransport.kt`'s listener writes it
with `conn.send(session.hello())` — a `String`, which the Java-WebSocket
library sends as a TEXT message (`wire/src/main/kotlin/civictech/wire/WsTransport.kt:2270`).
Every subsequent `WireFrame` crosses as **UTF-8 JSON inside a BINARY WebSocket
message** — `WireCodec.encode` returns a `ByteArray`
(`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:500,518`), and the
listener's two `onMessage` overloads split on message kind: `onMessage(conn,
String)` dispatches to `Session.onText` (handshake lines only) and
`onMessage(conn, ByteBuffer)` dispatches to `Session.onFrame` (bridge-decoded
`WireFrame`s) — `wire/src/main/kotlin/civictech/wire/WsTransport.kt:2273-2278`.
**No line has a terminator anywhere** — not `\n`, not any other byte —
`HelloProtocol.kt`'s `encodeHello2`/`encodeProof` build the line with
`buildString`/string concatenation and return it as-is
(`wire/src/main/kotlin/civictech/wire/HelloProtocol.kt:255-267`); the
receiving side reads the whole WebSocket TEXT message as the line.

There are two handshake grammars, chosen by whether the sending side has
`PeerCredentials` (`Session.hello()`,
`wire/src/main/kotlin/civictech/wire/WsTransport.kt:960-974`):

| line | grammar | when |
|---|---|---|
| legacy `HELLO` | `HELLO <mirrorRef>[ <peerName>]` | `side.credentials == null` |
| `HELLO2` | `HELLO2 <mirrorRef> <claimedPeerId> <base64url(SPKI)> <base64url(nonce)>` | `side.credentials != null` |
| `PROOF` | `PROOF <base64url(signature)>` | answers a `HELLO2` the peer is authenticating against |

Prefixes and token counts (`HelloProtocol.kt`): `HELLO2_PREFIX = "HELLO2 "`
(line 68), `PROOF_PREFIX = "PROOF "` (line 71), `LEGACY_HELLO_PREFIX =
"HELLO "` (line 84, **with** the trailing space — the legacy line is a
strict prefix-match target so `HELLO2 …` can never be misparsed as a legacy
hello with peer name `"2 …"`). `Session.hello()`'s legacy branch is
`HELLO + fresh.ref.id + (side.peer?.let { " ${it.name}" } ?: "")` — no
`peerName` token when the side has none. `encodeHello2` appends, space
separated: `mirrorRef`, `claimedPeerId`, base64url-no-pad(SPKI), then
base64url-no-pad(nonce). `encodeProof` is `PROOF_PREFIX +
base64url-no-pad(signature)`. A nonce of at least `MIN_HELLO_NONCE_BYTES =
16` bytes is accepted on receipt; a fresh hello generates
`HELLO_NONCE_BYTES = 32`.

**What this section pins and what it does not.** The three line grammars
above are pinned — a conforming peer must produce and parse exactly these
token shapes. Whether a given signature or proof **verifies** is DSC1's
concern, not this corpus's: see `HelloProtocolTest` and
`WsAuthenticatedHelloTest` (`wire/src/test/kotlin/civictech/wire/`) for the
cryptographic verification behaviour. The **session-level** behaviours around
the handshake — a frame arriving before hello is dropped, an unlisted peer is
refused, a re-hello on an established session — are asserted by `:wire`'s own
tests (`WsTransportPreHelloDropTest`, `WsHelloAllowlistDerivedIdTest`,
`WsHelloMixedVersionTest`, `WsHelloAdversarialTest`), not by a corpus vector
(ncz-D7): this corpus's `handshake-text` vectors (feature ncz.5) pin the three
line grammars' bytes, nothing about session admission.

## 3. JSON options in force

`WireCodec.build` (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:398`)
configures exactly three non-default `Json` options. It never sets
`encodeDefaults` or `ignoreUnknownKeys` — both stay at kotlinx.serialization's
default, `false`.

### `useArrayPolymorphism = true`

A polymorphic value (an `args` element, `protocolMessage`, an `Interest`
arm, a `Stamped.delta`, an `Owned`/`Frozen`/`Borrowed.value`, a
`RoutedCommand` payload, a structured map key/value) encodes as the
two-element array `["<discriminator>", <value>]` rather than kotlinx's
default `{"type":"<discriminator>", ...}` wrapper. The seed vector
`frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json` is the worked example —
its `args` field is:

```json
"args":[["Stall",{"reason":"SUSPENDED"}]]
```

— checked by reading the vector's `encoded.utf8` directly:

```bash
python3 -c 'import json; d=json.load(open("wire/corpus/frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json")); print(json.loads(d["encoded"]["utf8"])["args"])'
```
prints `[['Stall', {'reason': 'SUSPENDED'}]]` — array polymorphism, not an
object wrapper.

A **primitive** argument encodes the same way: `["kotlin.String","x"]`.
`unverified:` on the wire — no test in the repo pins a primitive arg's bytes
today (`WireCodecTest`'s `"wire bytes carry no reflection artifacts"` test
asserts only the absence of the substrings `civictech` and `propagate`, not
the presence of a `kotlin.` discriminator: `grep -n 'wire bytes carry no
reflection artifacts' -A8 kernel/src/test/kotlin/civictech/cell/wire/WireCodecTest.kt`
shows the assertion). The first primitive vector (features ncz.2/ncz.3) pins
it; `SCHEMA.md`'s primitive-discriminator note and `NONDETERMINISM.md`'s
Findings entry carry the full reasoning.

### `allowStructuredMapKeys = true`

A map whose key serializes as a JSON primitive (`String`, number, boolean,
including a `UUID` or enum key) encodes as a plain JSON object. Worked from
`wire/src/test/resources/fixtures/watermark-delta-pre-ke3-full.bin`
(`PreKe3WireFixtureTest`), whose `WatermarkDelta.rows` field — a
`Map<UUID, Map<UUID, Long>>` keyed by primitive-serialized `UUID` — encodes
as a plain nested object:

```bash
python3 -c "print(open('wire/src/test/resources/fixtures/watermark-delta-pre-ke3-full.bin','rb').read().decode('utf-8'))"
```
shows `"rows":{"00000000-0000-0000-0000-0000000000a1":{"00000000-0000-0000-0000-0000000000b1":3,...`
— a JSON *object*, keyed directly by the UUID string, not an array of
key/value pairs.

A map whose key is **structured** (polymorphic, or a class) instead encodes
as a **flat alternating array** `[k1, v1, k2, v2, …]` — kotlinx's documented
`allowStructuredMapKeys` behaviour for a non-primitive key.
`unverified:` no bytes anywhere in the repo pin a structured map key today
(`git grep` over `kernel`/`wire` tests for a `MapDelta`/`TaggedMapDelta`
literal with a non-primitive key finds none); this is a prediction from
kotlinx's documented semantics, not an observation, and is pinned by the
first structured-key vector (ncz.2's `MapDelta` over polymorphic `String`
keys — `SCHEMA.md`'s Maps section).

### Default omission (`encodeDefaults` unset)

`WireCodec.build` never sets `encodeDefaults`, so kotlinx.serialization's
default (`false`) applies: **every field at its default value produces zero
bytes**. This governs, among others: `WireFrame.version` (default
`WireCodec.VERSION = 2`), `natures` (default `emptyList()`), every field
whose default is `null` (`context`, `protocolId`, `protocolMessage`, `edge`,
`routingEpoch`, `signature`, `signerKeyId`, `sigCounter`, `notAfter`),
`args` (default `emptyList()`), and `cellRef.instanceId` (default `0`, per
the G-8 CellRef retrofit `WireCodec.VERSION`'s KDoc names). A conforming
encoder **must** omit these too — an encoder that always writes them
produces bytes this corpus's vectors do not match (`[WIR1-I19]`).

The seed vector is the worked example: `WV-PORT-API-STALL-SUSPENDED-01`'s
`encoded.utf8` carries no `"version"` key and no `"natures"` key anywhere in
its 200 bytes —

```bash
python3 -c 'import json; d=json.load(open("wire/corpus/frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json")); u=d["encoded"]["utf8"]; print("version" in u, "natures" in u)'
```
prints `False False`. `WV-PORT-API-STALL-RESUME-01`'s `args` field —
`[["Resume",{}]]` — is the worked example for a **polymorphic class with
every field defaulted**: `Resume` is a `data object` (no fields at all), and
still carries an empty `fields`/`{}` rather than being omitted, because the
omission rule applies to a *field of an envelope or payload*, never to an
`args` list element itself (`SCHEMA.md`'s "Absent optionals" section draws
this line precisely).

## 4. The envelope: `WireFrame`

`WireFrame` (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:94-207`)
is the top-level JSON object every binary `WireFrame` message carries. All 17
fields, in declaration order:

| field | JSON type | required/optional | default | populated when |
|---|---|---|---|---|
| `version` | integer | optional | `WireCodec.VERSION` (`2`) | never, by this codec (§5) |
| `contractId` | integer (64-bit) | required | — | always |
| `methodId` | integer (64-bit) | required | — | always |
| `cellRef` | object (`CellRef`) | required | — | always |
| `portName` | string | required | — | always |
| `type` | string (enum) | required | — | always — `PORT_MANAGEMENT` \| `PORT_API` \| `PORT_PROTOCOL` |
| `context` | object (`MessageContext`) or absent | optional | `null` | non-`PORT_PROTOCOL` frames carrying a wave/message context |
| `args` | array | optional | `emptyList()` | non-`PORT_PROTOCOL` frames (the invocation's captured arguments) |
| `protocolId` | string or absent | optional | `null` | `PORT_PROTOCOL` frames only — required there (§7) |
| `protocolMessage` | polymorphic value or absent | optional | `null` | `PORT_PROTOCOL` frames only |
| `edge` | object (`WireEdge`) or absent | optional | `null` | `PORT_PROTOCOL` frames only — required there (§7) |
| `routingEpoch` | integer (64-bit) or absent | optional | `null` | never emitted by this codec (write-disabled); still decoded when present (PN-6, §7) |
| `natures` | array of integers | optional | `emptyList()` | a link-establishing `PORT_PROTOCOL` frame declaring non-default natures (§7) |
| `signature` | string (base64url, no padding) or absent | optional | `null` | a `RegistryAnnounce` call encoded with a non-null `AnnouncementSigner` (DSC1-ANN-01) |
| `signerKeyId` | string or absent | optional | `null` | same condition as `signature` |
| `sigCounter` | integer (64-bit) or absent | optional | `null` | same condition as `signature` |
| `notAfter` | integer (64-bit, epoch millis) or absent | optional | `null` | same condition as `signature` |

`WireEdge` (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:210-220`),
all 7 fields, all required whenever `edge` itself is present:

| field | JSON type | meaning |
|---|---|---|
| `id` | string | the logical link id, this side's own bookkeeping (need not match cross-host) |
| `fromRef` | object (`PortRef`) | the sending endpoint's port ref |
| `fromCell` | object (`CellRef`) | the sending endpoint's cell ref |
| `fromPortName` | string | the sending endpoint's port name |
| `toRef` | object (`PortRef`) | the receiving endpoint's port ref |
| `toCell` | object (`CellRef`) | the receiving endpoint's cell ref |
| `toPortName` | string | the receiving endpoint's port name |

`HostedPortInvocation.Type`
(`kernel/src/main/kotlin/civictech/cell/proxy/HostedPortInvocation.kt:58-68+`),
verified against the enum declaration: `PORT_MANAGEMENT` (management-API
calls — `linkTo`, `linkFrom`, `serve`, `delegate`), `PORT_API`
(functional-API calls — e.g. `provide(data)`), `PORT_PROTOCOL`
(generic-protocol crossings — §7).

## 5. The version rule

`version` is **never emitted** by this codec: it defaults to
`WireCodec.VERSION` and `encodeDefaults` is unset, so every frame this codec
encodes omits the key entirely (`WireCodecTest`'s `"KE3-39 - VERSION is
omitted from every encoded frame, so a bump cannot gate a mixed-version
mesh"` test). `codecVersion` in a corpus vector document
(`SCHEMA.md`'s Document fields) is **corpus metadata only** — it never
appears as a key in `encoded` bytes.

A frame that **explicitly** carries a `version` key whose value differs from
this build's `WireCodec.VERSION` is refused:
`decodeFrame`'s `check(frame.version == VERSION) { "unsupported wire version
${frame.version}" }` (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:622`).
Because no encoder in this repo ever emits the key, this check is
unreachable against any frame this codec itself produces — it is a guard
against a **foreign** frame (hand-built, or from a future encoder that does
emit `version`), not a mixed-version negotiation gate:
`WireCodecTest`'s `"u5gb - the version check's domain is exactly an
explicitly differing version"` test pins exactly this. **There is no
negotiation** — no capability exchange, no version handshake field, nothing
that lets two differently-versioned peers agree on a common wire
generation; two peers at different `WireCodec.VERSION`s simply interoperate
at the frame level, by decision (ncz-D6).

Cited spec decisions, both in
`doc/spec/40-distribution/42-replication.md` §"Wire compatibility of
additive fields (KE3-39)":
- "Decision: the frame version stays unemitted, and the check stays a
  foreign-frame guard" (computenet-u5gb) — governs this section.
- "Decision: the constraint stays operational — no mechanism"
  (computenet-5zba) — governs §6 below.

This rule is **pinned because current** (`[WIR1-C16]`): nothing in
`doc/spec/` requires the version field to stay unemitted forever, only that
it does today, by the cited decision, which names its own reopening
trigger (cross-peer capability/version negotiation, or a supported rolling
upgrade) rather than committing to never revisit it.

## 6. The unknown-key rule

`WireCodec.build` never sets `ignoreUnknownKeys`, so kotlinx.serialization's
default (`false`) applies: **a decoded frame carrying an envelope key the
reader does not recognise is refused as a whole** —
`kotlinx.serialization.SerializationException` — and **nothing from that
frame is delivered**, not even the fields the reader does recognise.
`WireCodecTest`'s `"KE3-39 - ignoreUnknownKeys rescues an unknown KEY but
never an unknown enum CONSTANT"` test pins this half; the same file's
mixed-version hazard KDoc calls out an **independent** second hazard — an
unrecognised *enum constant* on a key the reader already knows (e.g. a
`Stall.reason` value an older peer's `StallReason` enum lacks) — which
`ignoreUnknownKeys` does not and cannot address, since that setting governs
unrecognised object *keys*, not unrecognised *values*.

**Additive compatibility runs one way only**: a newer reader decoding an
older writer's bytes tolerates the older writer's absent fields (they take
their defaults); an older reader decoding a newer writer's bytes that
actually **populates** a field the older reader's build predates throws on
the unknown key. `doc/spec/40-distribution/42-replication.md`
§"Wire compatibility of additive fields (KE3-39)" → "Decision: the
constraint stays operational — no mechanism" (computenet-5zba) is the cited
decision: nothing mechanises this constraint — no `VERSION` bump, no
negotiation, no gate — a mixed-version mesh simply must not populate a
newly-added optional field (or emit a newly-added enum constant) until every
peer has upgraded past the version that introduced it.

This rule is **pinned because current** (`[WIR1-C16]`), and is the
epic's re-scope item 1: the earlier draft of `[WIR1-I05]` read "the
implementation SHALL ignore \[an unrecognised envelope field\] and decode
the remainder successfully" — the OPPOSITE of the measured behaviour. The
re-scoped `[WIR1-I05]` (epic computenet-ncz's "Re-scope 2026-09-13" comment)
reads: "IF a received frame carries an envelope KEY the implementation does
not recognise, THEN the implementation SHALL reject the frame and SHALL NOT
deliver any part of it" — which is what this section states.

## 7. `PORT_PROTOCOL` frames

A `PORT_PROTOCOL` frame is built by `WireCodec.encode`'s `PORT_PROTOCOL`
branch (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:522-543`):

- **`contractId` and `methodId` are `0`/`0`, and emitted** (they are not the
  Kotlin default for a `Long`-typed *optional* field — there is none here;
  both are non-nullable required fields on `WireFrame`, so `0L` still
  appears in the bytes rather than being treated as absent).
- **`protocolId` and `edge` are required, and their absence is refused.**
  `invocation(frame)`'s `PORT_PROTOCOL` branch does
  `checkNotNull(frame.protocolId) { "PORT_PROTOCOL frame missing
  protocolId" }` and `checkNotNull(frame.edge) { "PORT_PROTOCOL frame
  missing edge" }` (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:626-635`)
  — a decoded frame lacking either throws before any part of the invocation
  is reconstructed (`[WIR1-I10]`).
- **`natures` is the sparse flat `[axisOrdinal, levelRank, axisOrdinal,
  levelRank, …]` list** — `NatureVector.toWire()`
  (`nature/src/main/kotlin/civictech/nature/ContractDescriptor.kt:232`).
  `NatureVector.DEFAULT` (the empty vector) projects to the empty list,
  which `encodeDefaults`-unset omits entirely: **empty ⇒ omitted ⇒ the
  `natures` field's own default** (§3's default-omission rule applies to
  this field like any other). The inverse, `natureVectorFromWire`
  (line 242), is **forward-compatible by construction**: an axis ordinal or
  level rank it cannot resolve is silently ignored, never refused
  (`[WIR1-I11]`) — a newer peer's declared axis is dropped on decode by an
  older peer rather than failing the frame. This tolerant-unknown-axis
  behaviour is **pinned because current** (`[WIR1-C16]`): nothing in
  `doc/spec/` requires ignoring an unresolvable axis specifically, only that
  this implementation does it today.
- **`args` is absent** on a `PORT_PROTOCOL` frame — the branch never sets
  it, so it stays at its `emptyList()` default and is omitted per §3.
- **`routingEpoch` is write-disabled, read-tolerated.** The non-`PORT_PROTOCOL`
  encode branch explicitly sets `routingEpoch = null` with the comment "PN-6:
  no longer sniff a routed command's epoch onto the frame ... encode simply
  stops populating it" (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:566-572`) —
  no encoder in this codec ever emits `routingEpoch`. The field stays in
  `WireFrame`'s schema and `decodeFrame` still accepts a legacy frame that
  carries it (the field has no `checkNotNull`, so absence and presence both
  decode). This is **pinned because current** (`[WIR1-C16]`): it is a
  decommissioning-in-progress state (the field is kept "for one release"
  per the KDoc), not a permanent wire contract.

## 8. Payload discriminators

The registered polymorphic list, grouped as `WireCodec.baselineModule`
registers them (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:182-267`;
`grep -c 'subclass(' kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt`
prints `40` = 33 `Any`-polymorphic registrations + 7 `Interest` arms):

**Primitives** (no `@SerialName` — builtin kotlinx serializers; discriminator
is the builtin descriptor's serial name, §3): `String` (`kotlin.String`),
`Long` (`kotlin.Long`), `Int` (`kotlin.Int`), `Boolean` (`kotlin.Boolean`),
`Double` (`kotlin.Double`).

**Identity**: `UUID` (discriminator `Uuid`, via `UuidSerializer`),
`Timestamp`, `CellRef`, `PortRef`, `TopologyLink`, `MessageContext`.

**Deltas** (spec 20/24 data-cell operator algebra): `CounterDelta`,
`PnCounterDelta`, `WatermarkDelta`, `SetDelta`, `MapDelta`,
`TaggedMapDelta`, `ListDelta`.

**Replication / routing**: `RoutedCommand`, `Stamped`, `LeaderMark`,
`Assignment`.

**Ownership wrappers** (spec 23): `Owned`, `Frozen`, `Borrowed`. `Leased`
is **not** in this list — it is never registered, because a `Leased`
payload must never serialize at all (below).

**Protocol messages** (generic-protocol crossings, spec 41 point 4):
`Attention`, `Stall` (`StallNotice.Stall`), `Resume` (`StallNotice.Resume`),
`Progress`, `SaturationSignal`, `EdgeOpen`, `EdgeClose`, `StateRequest`.

**`Interest` arms** (its own `polymorphic` block,
`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:257-266`): `Total`,
`Empty`, `Union`, `Intersect`, `Complement`, `Ranges`, `Slots`. **Not** bare
simple names on the wire: each carries its own explicit `@SerialName`
override in `kernel/src/main/kotlin/civictech/cell/link/Interest.kt`, and
every one is prefixed — `Interest.Total` (line 83), `Interest.Empty` (96),
`Interest.Union` (106), `Interest.Intersect` (114), `Interest.Complement`
(122), `Interest.Ranges` (136), `Interest.Slots` (166). The discriminator
string a reader must match is `Interest.<Name>`, not `<Name>` — distinct
both from the bare simple name and from the `Any`-polymorphic namespace
above (verify: `git grep -n 'SerialName("Interest\.' kernel/src/main/kotlin/civictech/cell/link/Interest.kt`).

Every one of the 33+7 registrations except the 5 primitives and `Uuid`
carries an explicit `@SerialName` equal to its simple name (verified by the
sibling task ncz.1.3's breakdown comment: `git grep -n 'SerialName("'` over
`kernel/src/main/kotlin` matches all 33 `Any` registrations and 7 `Interest`
arms) — no fully-qualified Kotlin class name appears among these
discriminators.

**`Leased` never crosses the wire.** `BridgeEgressCell`'s deliver path
refuses it before encoding is attempted:
```
require(args.none { it is Leased<*> }) {
    "Leased payloads must not cross machine boundaries (spec 23) — freeze or copy first"
}
```
(`kernel/src/main/kotlin/civictech/cell/wire/BridgeCells.kt:60-62`) —
`[WIR1-I16]`.

**`Owned` is consumed by encoding.** The same deliver path calls
`args.forEach { (it as? Owned<*>)?.consume() }`
(`kernel/src/main/kotlin/civictech/cell/wire/BridgeCells.kt:65`) — an
`Owned` argument crossing the bridge is released from the sender's side as
part of sending, not merely serialized — `[WIR1-I17]`.

**Application-contributed types are outside this corpus** (`[WIR1-C15]`).
`WireSerializers` (`kernel/src/main/kotlin/civictech/cell/wire/WireSerializers.kt`)
is the extension point: an interface exposing one `SerializersModule`,
discovered two ways — `META-INF/services/civictech.cell.wire.WireSerializers`
(process-start `ServiceLoader` discovery, folded into `baselineModule`) and
`WireCodec.contribute`/`withdraw` (a late module, for a type registered
after the codec has already encoded frames). Both routes fold into the same
`Json` build: `build(live)` does
`live.fold(baselineModule) { acc, contribution -> acc + contribution.module }`.
`plus` (kotlinx.serialization) fails fast — throws — if a contribution's
discriminator collides with a kernel registration or an earlier
contribution, so registration never silently shadows. This corpus does not
vector application types because the registration is **per-process**: a
`WireSerializers` id travels on the wire as an ordinary `@SerialName`
discriminator exactly like a kernel type, but whether that discriminator
decodes on a given process depends entirely on what that process has
contributed — a corpus vector pinned against one process's contribution set
would silently stop applying to any other. The kernel-registered baseline
above is what every process shares.

## 9. Identity and ids

`contractId` and `methodId` are 64-bit FNV-1a hashes — `doc/spec/40-distribution/41-location-transparency.md`
"Wire layer" point 1: "ids hashed FNV-1a 64 from FQN / FQN#name+erased-JVM-signature"
— generated once by `gen.wire.ContractProcessor` into `ContractDescriptor`
tables and stable literals thereafter (not recomputed per encode). They are
**not** reflective (`java.lang.reflect.Method`) identifiers — decode recovers
the in-process dispatch path from the descriptor's name plus erased JVM
signature, not from the hash itself. `StallNoticeWireCompatTest` already
pins one concrete pair for `Propagate::propagate`:
`contractId=-996426215734216040`, `methodId=-134175827537617903` — the same
pair both seed vectors under `wire/corpus/frames/port-api/` carry.

**A conforming reader MUST preserve every 64-bit integer on the wire
exactly** — `contractId`, `methodId`, `Timestamp.counter`, `sigCounter`,
`notAfter`, `routingEpoch`, and any other id or epoch. These routinely
exceed `2^53` and a reader that parses them into an IEEE-754 double (a naive
JavaScript `JSON.parse`) silently corrupts them; `SCHEMA.md`'s "64-bit
integers" section states the same requirement for vector authors and names
the concrete tool pitfall (`jq` 1.6 rounds; `jq` ≥1.7 and Python's `json`
module do not).
