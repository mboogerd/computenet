# Wire vector schema — v1

The authoring contract for one wire test vector under `wire/corpus/`
(epic computenet-ncz, WIR1, `[WIR1-C14]`). One JSON document = one vector. This
file is the whole contract: a reader implementing a driver in any language needs
this file, `README.md` (the wire itself) and a JSON parser — never Kotlin.
**Changing the neutral `decoded` grammar, the set of vector kinds, a required
document field, or the rejection vocabulary is a deliberate schema-change
ticket — not a corpus-authoring convenience.** A vector that needs a shape this
file does not define is filed as a bead, not improvised (seam rule, mirroring
`concord/schema/scenario.md`; see [Seam rule](#seam-rule)).

Companion references:
- `README.md` — the prose wire companion: transport framing, JSON options,
  `WireFrame`/`WireEdge` field lists, version and unknown-key rules.
- `NONDETERMINISM.md` — the honesty ledger for bytes that cannot be pinned
  (`[WIR1-C11]`).
- `manifest.json` — the machine-readable index (shape in [Manifest](#manifest)).

**Where the `WIR1-*` ids live.** Every `[WIR1-C*]`/`[WIR1-I*]` id cited here and
in a vector's `covers` is registered in epic computenet-ncz's description, **not**
in `doc/spec/`. Concord's dangling-`covers:` lint therefore does not see them,
and this file is their only schema.

Written against base commit `192539789`. Facts about the codec below cite
`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt` at that commit; a
fact marked `unverified:` is a prediction no test in the repo pins yet, and
names the work that will pin it.

## Contents

1. [Top-level document](#top-level-document)
2. [Document fields](#document-fields)
3. [Kinds](#kinds)
4. [The `encoded` block](#the-encoded-block)
5. [The neutral `decoded` grammar](#the-neutral-decoded-grammar)
6. [Ids and categories](#ids-and-categories)
7. [Rejection vocabulary](#rejection-vocabulary)
8. [Manifest](#manifest)
9. [Seam rule](#seam-rule)

## Top-level document

Commented overview (not a parseable example — the worked documents below are):

```text
{
  "id":           "WV-PORT-API-STALL-SUSPENDED-01",  // WV-<TOKEN>-<NAME>-<nn>; equals the file's basename
  "title":        "Stall(SUSPENDED) crosses as a PORT_API arg",
  "category":     "frames/port-api",                 // directory under wire/corpus/ holding the file
  "kind":         "frame",                           // frame | handshake-text | negative
  "covers":       ["WIR1-C05", "WIR1-I01", "41 point 1"],  // >= 1 WIR1-* id
  "codecVersion": 2,                                 // corpus metadata ONLY — never a key in encoded bytes
  "messageKind":  "binary",                          // binary | text — which WebSocket message carries the bytes
  "direction":    "both",                            // positive: both (default) | decode; negative: decode | encode
  "decoded":      { ... },                           // neutral value grammar
  "encoded":      { "utf8": "...", "base64": "..." },// the exact bytes, twice
  "expect":       { "reject": "malformed" },         // negative kind only
  "notes":        "...",                             // why this vector exists; pinned-because-current when so
  "deprecated":   "..."                              // optional; present only on a retired vector
}
```

A worked, complete `frame` vector — the bytes are `StallNoticeWireCompatTest`'s
`goldenSuspended` literal (200 bytes):

```json
{
  "id": "WV-PORT-API-STALL-SUSPENDED-01",
  "title": "Stall(SUSPENDED) crosses as a PORT_API arg",
  "category": "frames/port-api",
  "kind": "frame",
  "covers": ["WIR1-C02", "WIR1-C09", "WIR1-C10", "WIR1-I01", "WIR1-I02", "WIR1-I03", "41 point 1"],
  "codecVersion": 2,
  "messageKind": "binary",
  "decoded": {
    "type": "frame",
    "fields": {
      "contractId": -996426215734216040,
      "methodId": -134175827537617903,
      "cellRef": {"id": "00000000-0000-0000-0000-000000000042"},
      "portName": "inlet",
      "type": "PORT_API",
      "args": [{"type": "Stall", "fields": {"reason": "SUSPENDED"}}]
    }
  },
  "encoded": {
    "utf8": "{\"contractId\":-996426215734216040,\"methodId\":-134175827537617903,\"cellRef\":{\"id\":\"00000000-0000-0000-0000-000000000042\"},\"portName\":\"inlet\",\"type\":\"PORT_API\",\"args\":[[\"Stall\",{\"reason\":\"SUSPENDED\"}]]}",
    "base64": "eyJjb250cmFjdElkIjotOTk2NDI2MjE1NzM0MjE2MDQwLCJtZXRob2RJZCI6LTEzNDE3NTgyNzUzNzYxNzkwMywiY2VsbFJlZiI6eyJpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDA0MiJ9LCJwb3J0TmFtZSI6ImlubGV0IiwidHlwZSI6IlBPUlRfQVBJIiwiYXJncyI6W1siU3RhbGwiLHsicmVhc29uIjoiU1VTUEVOREVEIn1dXX0="
  },
  "notes": "Transcribed, not generated (WIR1-F1-D1), from StallNoticeWireCompatTest.goldenSuspended, captured at ea84150f5 per that test's KDoc. Encodes a HostedPortInvocation of the Propagate::propagate contract (contractId/methodId are that contract's FNV-1a hashes) carrying a Stall(SUSPENDED) notice on cellRef 00000000-0000-0000-0000-000000000042's inlet port. No version key: the frame version stays unemitted while it matches the implementation's own, which is a decision the codec KEEPS operational rather than a spec requirement — pinned-because-current ([WIR1-C16])."
}
```

## Document fields

| field | required | meaning |
|---|---|---|
| `id` | every vector | the vector's stable id, [Ids and categories](#ids-and-categories); equals the file's basename without `.json` |
| `title` | every vector | one human line saying what crosses the wire |
| `category` | every vector | the directory under `wire/corpus/` holding the file (`frames/port-api`, `payloads`, `negative`, …) |
| `kind` | every vector | `frame` \| `handshake-text` \| `negative` — [Kinds](#kinds) |
| `covers` | every vector | non-empty array of strings, at least one a `WIR1-*` id |
| `codecVersion` | every vector | integer, the `WireCodec.VERSION` the vector was authored against (`2` today) |
| `notes` | every vector | string; why the vector exists, and — per `[WIR1-C16]` — the words `pinned-because-current` when it pins behaviour the spec does not require |
| `decoded` | kind-dependent | the value in the [neutral grammar](#the-neutral-decoded-grammar) |
| `encoded` | kind-dependent | `{"utf8", "base64"}` — [The `encoded` block](#the-encoded-block) |
| `expect` | `negative` only | `{"reject": "<classification>"}`, one word from the [rejection vocabulary](#rejection-vocabulary) |
| `direction` | kind-dependent | which conversion the vector asserts — [Kinds](#kinds) |
| `messageKind` | whenever `encoded` is present | `binary` (frames) \| `text` (handshake lines) — the WebSocket message kind that carries the bytes |
| `deprecated` | optional | string reason; see [Ids and categories](#ids-and-categories) |

No other top-level key is allowed; a driver refuses a document carrying one.

- **`covers`** — each element is a bare epic id with no brackets (`WIR1-C05`,
  `WIR1-I01`) or a spec citation in prose form (`41 point 1`,
  `42 KE3-39`). The `WIR1-*` ids are registered in epic computenet-ncz, not in
  `doc/spec/` (see the preamble).
- **`codecVersion` is corpus metadata, never wire content.** No `encoded` byte
  sequence of a positive vector contains a `version` key: `WireFrame.version`
  defaults to `WireCodec.VERSION` and `encodeDefaults` is unset, so the codec
  omits it from every frame it emits. That omission is a decision, not an
  accident — `doc/spec/40-distribution/42-replication.md`, §"Decision: the frame
  version stays unemitted, and the check stays a foreign-frame guard"
  (computenet-u5gb). A `version` key appears only in a `negative` vector
  classified `unsupported-version`. Bumping `codecVersion` on a vector is a
  statement about when it was authored, not about its bytes.

## Kinds

The kind is explicit, never inferred from `category` (WIR1-F1-D3). The set is
closed.

| kind | carries | `messageKind` | `direction` |
|---|---|---|---|
| `frame` | `decoded` + `encoded` | `binary` | `both` (default) \| `decode` |
| `handshake-text` | `decoded` + `encoded` | `text` | `both` (default) \| `decode` |
| `negative` | `expect.reject` + exactly one of `encoded` / `decoded` | `binary` when `encoded` is present | `decode` (with `encoded`) \| `encode` (with `decoded`) — required |

**`frame`.** A binary WebSocket message holding one UTF-8 JSON `WireFrame`.
`decoded` is the frame envelope (`{"type": "frame", "fields": {…}}`, below).

**`handshake-text`.** One text WebSocket message holding one handshake line.
`encoded.utf8` **is** the line, with no terminator (no `\n`). `decoded` names the
line by its leading keyword and its space-separated tokens, in order:

| line | `decoded` |
|---|---|
| `HELLO <mirrorRef>[ <peerName>]` | `{"type": "HELLO", "fields": {"mirrorRef": "<uuid>", "peerName": "<name>"}}` — `peerName` omitted when the line has none |
| `HELLO2 <mirrorRef> <claimedPeerId> <spki> <nonce>` | `{"type": "HELLO2", "fields": {"mirrorRef": "<uuid>", "claimedPeerId": "<id>", "publicKeySpki": "<base64url>", "nonce": "<base64url>"}}` |
| `PROOF <signature>` | `{"type": "PROOF", "fields": {"signature": "<base64url>"}}` |

Token values are written as the exact text of the token (base64url stays
base64url). The `type` names here live in the handshake namespace, which the
`kind` separates from payload discriminators.

```json
{
  "type": "HELLO",
  "fields": {"mirrorRef": "00000000-0000-0000-0000-0000000000c1", "peerName": "alpha"}
}
```

encodes to the text line `HELLO 00000000-0000-0000-0000-0000000000c1 alpha`.

**`negative`.** A vector that a conforming implementation must refuse.

- `direction: "decode"` — the document carries `encoded` and no `decoded`.
  `encoded.base64` is required; `encoded.utf8` is present **only** when the bytes
  are valid UTF-8 (so an `invalid-utf8` vector carries `base64` alone).
- `direction: "encode"` — the document carries `decoded` and no `encoded`: a
  value the implementation must refuse to encode (`leased-at-encode`). No bytes
  exist to pin.

**`direction` on a positive kind** defaults to `both`: the vector asserts
`encode(decoded) == encoded` (`[WIR1-I01]`), `decode(encoded) == decoded`
(`[WIR1-I02]`) and `decode(encode(decoded)) == decoded` (`[WIR1-I03]`).
`direction: "decode"` marks a decode-only positive vector, asserting
`[WIR1-I02]` alone: bytes the current encoder never emits but the decoder must
still accept — a legacy `routingEpoch` (write-disabled, read-tolerated,
`[WIR1-C16]`), or the DSC1 signing quartet, which encoding would need a signer
for (ncz.4-D1). `encode` is not a value for a positive kind.

## The `encoded` block

```json
{"utf8": "{\"contractId\":1}", "base64": "eyJjb250cmFjdElkIjoxfQ=="}
```

- `utf8` — the exact bytes, as a JSON string (so `"` and `\` are escaped in the
  document, and are single characters in the bytes).
- `base64` — the same bytes in standard base64 (RFC 4648 §4 alphabet, with `=`
  padding — what `base64` on macOS and Linux emits). It MUST decode to exactly
  the UTF-8 encoding of `utf8`; a document where the two disagree is invalid.
- **Bytes are the unit.** Whitespace is significant, key order is significant,
  number spelling is significant. Nothing is normalised (`[WIR1-C11]`).

Check one vector with no JVM:

```bash
python3 - wire/corpus/frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json <<'PY'
import base64, json, sys
d = json.load(open(sys.argv[1]))
assert base64.b64decode(d["encoded"]["base64"], validate=True) == d["encoded"]["utf8"].encode("utf-8")
print("encoded block consistent")
PY
```

**64-bit integers.** `contractId`, `methodId`, `Timestamp.counter`, `sigCounter`,
`notAfter`, `routingEpoch` and other epochs are JSON integers that routinely
exceed 2^53. A conforming reader of this corpus MUST preserve them exactly — a
JavaScript reader parses them as `BigInt`, never as `Number`. `jq` ≥ 1.7
preserves integer literals; `jq` 1.6 silently rounds them. Verification commands
in this corpus therefore use `python3`, whose `json` module keeps them exact.

## The neutral `decoded` grammar

The grammar names payloads only by their wire discriminator (the `@SerialName`
the codec writes), and fields only by their wire key. It is not a Kotlin dump:
no class names, package names or Kotlin types appear in it (`[WIR1-I18]`). There
are two positions, and each has one spelling.

### Typed positions: plain JSON

A value whose type is fixed by where it sits — an envelope field, a field of a
known payload, an element of a typed list — is written exactly as it appears in
the bytes.

**Primitive in a typed position** — number, string or boolean:

```json
{"portName": "inlet", "contractId": -996426215734216040, "counter": 7}
```

**Nested class in a typed position** — a plain object with the class's wire
keys, no `type` wrapper (`cellRef` in the envelope; `timestamp` inside a
`Stall`, as `StallNoticeWireCompatTest.goldenDeadLettered` pins it):

```json
{
  "cellRef": {"id": "00000000-0000-0000-0000-000000000042"},
  "timestamp": {"sourceId": "00000000-0000-0000-0000-000000000001", "counter": 7}
}
```

**Enum constant** — its constant name as a string, in any position an enum
occupies:

```json
{"type": "PORT_API", "reason": "SUSPENDED"}
```

**List or set** — a JSON array, in encoding order. The order is part of the
vector; pin it, never sort it (`[WIR1-C11]`):

```json
{"natures": [3, 1], "closed": ["00000000-0000-0000-0000-0000000000a2"]}
```

### Polymorphic positions: `{"type", "fields"}` or `{"type", "value"}`

A value whose type travels on the wire as a discriminator is written with that
discriminator. The polymorphic positions are: an `args` element,
`protocolMessage`, an `Interest` member, `Stamped.delta`,
`Owned`/`Frozen`/`Borrowed`'s `value`, `RoutedCommand`'s payload, and a
polymorphic map key, element or value inside `SetDelta`, `MapDelta`,
`TaggedMapDelta` or `ListDelta`.

- A **class** is `{"type": "<discriminator>", "fields": {…}}`; `fields` holds its
  wire keys under the same two-position rules, recursively.
- A **primitive or UUID** is `{"type": "<discriminator>", "value": <scalar>}`.
- Exactly one of `fields` / `value` is present.
- Either form encodes to the two-element array `["<discriminator>", <object-or-scalar>]`
  (`useArrayPolymorphism = true`).

**Polymorphic class** — encodes to `["Stall",{"reason":"SUSPENDED"}]`; a class
with every field defaulted still carries `fields`, empty, and encodes to
`["Resume",{}]` (`goldenResume`):

```json
{"args": [{"type": "Stall", "fields": {"reason": "SUSPENDED"}}, {"type": "Resume", "fields": {}}]}
```

**Primitive in a polymorphic position** — encodes to `["kotlin.String","x"]` and
`["Uuid","00000000-0000-0000-0000-0000000000a1"]`:

```json
{
  "args": [
    {"type": "kotlin.String", "value": "x"},
    {"type": "Uuid", "value": "00000000-0000-0000-0000-0000000000a1"}
  ]
}
```

> **Primitive discriminators — a recorded fact, not a policy.** The five
> primitive registrations (`String`, `Long`, `Int`, `Boolean`, `Double`) use
> kotlinx.serialization's builtin serializers, which carry no `@SerialName`; their
> discriminator is the builtin descriptor's serial name — `kotlin.String`,
> `kotlin.Long`, `kotlin.Int`, `kotlin.Boolean`, `kotlin.Double` — observed in
> the constant pool of `kotlinx-serialization-core-jvm-1.9.0.jar`. That is a
> descriptor name, not a JVM class name (the JVM class would be
> `java.lang.String`). `UUID` is registered through `UuidSerializer`, whose
> descriptor name is `Uuid`. Every registered **class** carries an explicit
> `@SerialName` — its simple name, or `Interest.<Name>` for the seven `Interest`
> arms — so no fully-qualified name appears among them.
>
> `unverified:` on the wire — no test in the repo pins a primitive arg's bytes
> (`WireCodecTest."wire bytes carry no reflection artifacts"` asserts only the
> absence of `civictech` and `propagate`). The first primitive vector, authored
> by ncz.2/ncz.3, pins it.
>
> **Open, and not decided here:** whether the `kotlin.` prefix counts as a
> reflection artifact under `[WIR1-I18]`. ncz.2's `NoReflectionArtifactsTest`,
> as filed, forbids the substring `kotlin.` in any `encoded.utf8`, so it and the
> first primitive vector will collide. The feature that first pins a primitive
> vector owns that question. This file records the discriminator as the codec
> writes it and proposes no change to it.

**Double** — written as the JSON number the JVM emits, `1.0` and not `1`
(encodes to `["kotlin.Double",1.0]`). The vector pins the literal text in
`encoded.utf8`; `decoded` pins the value. `unverified:` the JVM's rendering is
not yet pinned by any vector (ncz.2 E5), and other runtimes' shortest-round-trip
formatting may spell the same value differently — a divergence found there is
recorded in `NONDETERMINISM.md`, never normalised:

```json
{"args": [{"type": "kotlin.Double", "value": 1.0}]}
```

### The frame envelope

A `frame` vector's `decoded` is the envelope, written as a polymorphic-shaped
class:

```json
{
  "type": "frame",
  "fields": {
    "contractId": -996426215734216040,
    "methodId": -134175827537617903,
    "cellRef": {"id": "00000000-0000-0000-0000-000000000042"},
    "portName": "inlet",
    "type": "PORT_API",
    "args": [{"type": "Stall", "fields": {"reason": "SUSPENDED"}}]
  }
}
```

`fields` holds `WireFrame`'s wire keys (full list in `README.md`); the envelope
itself encodes to a plain JSON object — it is **not** array-polymorphic, and no
`["frame", …]` wrapper appears in the bytes. `frame` is a grammar-reserved name:
`WireFrame` has no `@SerialName`, so the grammar supplies one. It is the only
name the grammar reserves in a positive vector. `edge` inside the envelope is a
typed-position plain object (`WireEdge`), and `context` a typed-position
`MessageContext` object.

### Absent optionals

An optional field that is absent — equal to its default — is **omitted** from
`fields`. An explicit `null` for a `null`-default field is **forbidden**: the
codec omits such a field (`encodeDefaults` unset), so `"context": null` would not
round-trip. The same holds for a defaulted non-null field at its default
(`"natures": []`, `"args": []`, `"version": 2`): omit it.

```json
{
  "type": "frame",
  "fields": {
    "contractId": 1,
    "methodId": 2,
    "cellRef": {"id": "00000000-0000-0000-0000-000000000042"},
    "portName": "inlet",
    "type": "PORT_API"
  }
}
```

(no `context`, `args`, `protocolId`, `protocolMessage`, `edge`, `routingEpoch`,
`natures`, `signature`, `signerKeyId`, `sigCounter`, `notAfter` or `version` —
all at their defaults.)

A key the encoder never emits may still appear in the `fields` of a
`direction: "decode"` vector: `routingEpoch` carrying a legacy value is exactly
that case.

### Maps

**Primitive-keyed map** — a map whose key serializes as a JSON primitive
(string, number or boolean, including `UUID` and enum keys) is written as a
plain JSON object, keys in encoding order. Worked from
`wire/src/test/resources/fixtures/watermark-delta-pre-ke3-full.bin`, whose
`WatermarkDelta` arg encodes as

```text
["WatermarkDelta",{"rows":{"00000000-0000-0000-0000-0000000000a1":{"00000000-0000-0000-0000-0000000000b1":3,"00000000-0000-0000-0000-0000000000b2":1},"00000000-0000-0000-0000-0000000000a2":{"00000000-0000-0000-0000-0000000000b1":2}},"closed":["00000000-0000-0000-0000-0000000000a2"],"suspended":{"00000000-0000-0000-0000-0000000000a1":1},"members":["00000000-0000-0000-0000-0000000000a1","00000000-0000-0000-0000-0000000000a2"]}]
```

and is written:

```json
{
  "type": "WatermarkDelta",
  "fields": {
    "rows": {
      "00000000-0000-0000-0000-0000000000a1": {
        "00000000-0000-0000-0000-0000000000b1": 3,
        "00000000-0000-0000-0000-0000000000b2": 1
      },
      "00000000-0000-0000-0000-0000000000a2": {"00000000-0000-0000-0000-0000000000b1": 2}
    },
    "closed": ["00000000-0000-0000-0000-0000000000a2"],
    "suspended": {"00000000-0000-0000-0000-0000000000a1": 1},
    "members": ["00000000-0000-0000-0000-0000000000a1", "00000000-0000-0000-0000-0000000000a2"]
  }
}
```

A reader MUST keep object key order as it appears in the document when it
re-encodes (Python's `dict` does; a reader that sorts keys is non-conforming).

**Structured-key map** — a map whose key is polymorphic, or a class, is written
as an ordered entry list:

```json
{
  "type": "MapDelta",
  "fields": {
    "puts": {
      "entries": [
        {"key": {"type": "kotlin.String", "value": "a"}, "value": {"type": "kotlin.Long", "value": 1}},
        {"key": {"type": "kotlin.String", "value": "b"}, "value": {"type": "kotlin.Long", "value": 2}}
      ]
    },
    "removals": [{"type": "kotlin.String", "value": "c"}]
  }
}
```

and encodes to a flat array alternating key and value — kotlinx's documented
`allowStructuredMapKeys` form `[k1, v1, k2, v2, …]`:

```text
["MapDelta",{"puts":[["kotlin.String","a"],["kotlin.Long",1],["kotlin.String","b"],["kotlin.Long",2]],"removals":[["kotlin.String","c"]]}]
```

`unverified:` no pinned bytes exist anywhere in the repo for a structured map
key; the flat alternating form above is kotlinx's documented behaviour, not an
observation. It is pinned by the first structured-key vector (ncz.2's
`MapDelta` over polymorphic `String` keys), which also settles the `kotlin.`
question above. Entries are in encoding order and the order is part of the
vector.

### Encode-direction negatives: the lease wrapper

A lease-scoped payload has no discriminator — it is never serialized, which is
the point of `leased-at-encode`. In the `decoded` of a `negative` vector with
`direction: "encode"`, and nowhere else, it is written
`{"type": "Leased", "fields": {"value": <polymorphic value>}}`. `Leased` is not a
wire discriminator and never appears in any bytes.

```json
{
  "type": "frame",
  "fields": {
    "contractId": -996426215734216040,
    "methodId": -134175827537617903,
    "cellRef": {"id": "00000000-0000-0000-0000-000000000042"},
    "portName": "inlet",
    "type": "PORT_API",
    "args": [{"type": "Leased", "fields": {"value": {"type": "kotlin.String", "value": "x"}}}]
  }
}
```

## Ids and categories

**Id form:** `WV-<TOKEN>-<NAME>-<nn>` — e.g. `WV-PORT-API-STALL-SUSPENDED-01`,
`WV-PAYLOAD-MAPDELTA-01`, `WV-NEG-MALFORMED-01`.

- Uppercase ASCII letters and digits, words separated by `-`.
- `<TOKEN>` comes from the category table below; `<NAME>` is one or more words
  naming what the vector shows; `<nn>` is a zero-padded ordinal within
  `<TOKEN>-<NAME>`, starting at `01`.
- The file lives at `wire/corpus/<category>/<id>.json`; the basename equals `id`.
- **Ids are immutable once assigned and never reused after deprecation**
  (`[WIR1-C03]`, mirroring `concord/schema/provenance.md` §1). A retired vector
  keeps its file, gains `"deprecated": "<reason>"`, and stays in `manifest.json`;
  its id is never assigned to another vector. Its `encoded` bytes are not edited
  either (`[WIR1-C10]`): new behaviour is a new vector.

| category (directory) | token | holds |
|---|---|---|
| `frames/port-api` | `PORT-API` | `PORT_API` envelope vectors |
| `frames/port-management` | `PORT-MGMT` | `PORT_MANAGEMENT` envelope vectors |
| `frames/port-protocol` | `PORT-PROTOCOL` | `PORT_PROTOCOL` envelope vectors (`protocolId`, `protocolMessage`, `edge`) |
| `frames/additive` | `ADDITIVE` | present/absent pairs for additive envelope fields |
| `payloads` | `PAYLOAD` | one vector per registered polymorphic discriminator |
| `payloads` | `INTEREST` | one vector per `Interest` arm |
| `handshake` | `HELLO` | `HELLO`, `HELLO2` and `PROOF` lines |
| `negative` | `NEG` | vectors that must be refused |

The tokens are the ones sibling features ncz.2–ncz.6 already cite
(`WV-PAYLOAD-MAPDELTA-01`, `WV-PORT-MGMT-LINKTO-01`,
`WV-ADDITIVE-NATURES-EMPTY-01`, `WV-NEG-MALFORMED-01`). Adding a category is a
table row plus a directory, not a schema change; adding a kind or changing the
grammar is.

Id pattern, for a lint:

```text
^WV-(PORT-API|PORT-MGMT|PORT-PROTOCOL|ADDITIVE|PAYLOAD|INTEREST|HELLO|NEG)(-[A-Z0-9]+)+-[0-9]{2,}$
```

## Rejection vocabulary

`expect.reject` takes exactly one of these ten classifications. The set is closed
(WIR1-F1-D5): a new classification is a schema change to this table, never a
per-vector invention.

| classification | direction | triggered by | serves |
|---|---|---|---|
| `malformed` | decode | valid UTF-8 that is not well-formed JSON, or JSON that is not a frame object | `[WIR1-I07]` |
| `truncated` | decode | a strict prefix of a valid vector's bytes | `[WIR1-I07]` |
| `invalid-utf8` | decode | bytes that are not valid UTF-8 (e.g. a lone continuation byte); refused before any JSON parse; the vector carries `encoded.base64` only | `[WIR1-I07]` |
| `unknown-frame-type` | decode | `type` names no frame kind (`"PORT_TELEPATHY"`) | `[WIR1-I09]` |
| `unknown-discriminator` | decode | a polymorphic position carries a discriminator no registration knows (`["QuantumDelta", {…}]`); the value is neither dropped nor null-substituted | `[WIR1-I08]` |
| `missing-required-field` | decode | a field with no default is absent (`contractId`, `cellRef`, …), or a `PORT_PROTOCOL` frame lacks `protocolId` or `edge` | `[WIR1-I10]` |
| `unknown-ids` | decode | a non-`PORT_PROTOCOL` frame's `contractId`/`methodId` pair names no local descriptor; the rejection names both ids | `[WIR1-I06]` |
| `unsupported-version` | decode | the frame carries an explicit `version` key whose value differs from the implementation's own (`1`, `99`); an absent key is accepted | `[WIR1-I04]` — **pinned-because-current** (`[WIR1-C16]`, epic re-scope item 2) |
| `unknown-envelope-field` | decode | the frame carries an envelope key outside `WireFrame`'s field list | `[WIR1-I05]` — **pinned-because-current** (`[WIR1-C16]`, epic re-scope item 1) |
| `leased-at-encode` | encode | the `decoded` value holds a lease-scoped payload; encoding is refused before any byte is produced | `[WIR1-I16]` |

`truncated` is a refinement of `malformed`: truncated bytes are also malformed
JSON, and a driver whose decoder cannot tell the two apart satisfies a
`truncated` expectation by reporting `malformed` (epic B3.2). The converse does
not hold. Every other classification is matched exactly. A vector whose notes
call it pinned-because-current says so in `notes` too, per `[WIR1-C16]`.

## Manifest

`wire/corpus/manifest.json` is the machine-readable index (`[WIR1-C12]`). A
harness discovers vectors through it, never by walking directories. It is
hand-written in feature ncz.1 and generated by ncz.2 (ncz.2-D2).

```json
{
  "vectors": [
    {
      "id": "WV-PORT-API-STALL-SUSPENDED-01",
      "category": "frames/port-api",
      "kind": "frame",
      "file": "frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json"
    }
  ],
  "pending": [
    {"discriminator": "kotlin.String", "owedBy": "computenet-ncz.3"},
    {"discriminator": "Uuid", "owedBy": "computenet-ncz.3"},
    {"discriminator": "Interest.Total", "owedBy": "computenet-ncz.3"}
  ]
}
```

| field | meaning |
|---|---|
| `vectors[]` | one entry per vector file under `wire/corpus/`, deprecated ones included |
| `vectors[].id` | the vector's `id` |
| `vectors[].category` | the vector's `category` |
| `vectors[].kind` | the vector's `kind` |
| `vectors[].file` | the file's path relative to `wire/corpus/` |
| `pending[]` | registered discriminators that have no positive vector yet |
| `pending[].discriminator` | the exact serial name the coverage lint sees — `kotlin.String` for a primitive, `Uuid` for UUID, `Interest.Total` … for `Interest` arms |
| `pending[].owedBy` | the bead id that owes the vector |

`pending` is the allowlist that lets ncz.2's coverage lint go red on a new,
un-vectored registration from day one while features drain the list
(WIR1-F1-D2). The lint's strict mode — `pending` must be empty — is ncz.2's; the
epic's `[WIR1-C05]` acceptance is met only there.

## Seam rule

The corpus is authored **against** this file; it never grows the file. The
following are each a deliberate schema-change ticket, reviewed as a change to the
contract every driver implements — never a corpus-authoring convenience
(`[WIR1-C14]`):

- changing the neutral `decoded` grammar, including adding a reserved name;
- adding, removing or redefining a vector kind or a `direction` value;
- adding or removing a required document field, or changing a field's meaning;
- adding, removing or redefining a rejection classification;
- changing the id form or the immutability rule.

A vector that needs a shape this file does not define is filed as a bead against
the schema, not written in an improvised shape. Adding a category row to the id
table is not a schema change. Neither is appending a vector: that is what the
corpus is for.
