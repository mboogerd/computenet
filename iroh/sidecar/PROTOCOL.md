# The sidecar's local-socket protocol

This is the normative description of the contract between the sidecar process
and its host process — the contract the `:iroh` JVM module programs against.
Its executable half is `src/protocol.rs` (layout, kinds, codec) and
`src/server.rs` (behaviour). **Every message kind the code can send or accept is
named here; a kind in the code with no entry in this file is a defect, not an
omission.**

Feature decisions this realizes: `egl.1-D2` (thin bin over the library),
`egl.1-D3` (TCP on `127.0.0.1`, one JSON handshake line), `egl.1-D6` (4-byte
big-endian length prefix plus a kind byte and a link id).

## 1. Transport and handshake

The sidecar binds an **ephemeral TCP port on `127.0.0.1`** and writes exactly
**one** line to stdout:

```json
{"port":54321,"nodeId":"3f2a…64 lowercase hex chars…"}
```

* `port` — the loopback TCP port, a decimal number.
* `nodeId` — this endpoint's ed25519 **public key**, 32 bytes as 64 lowercase
  hex characters. iroh 1.0 calls this an `EndpointId`; the ComputeNet specs call
  it a NodeId. They are the same 32 bytes.

Nothing else is ever written to stdout; diagnostics go to stderr. The host reads
this one line, then connects to `127.0.0.1:<port>`.

The sidecar serves **one host connection at a time**. A second connection waits
until the first ends, so link ids never span two hosts.

## 2. Message layout

Every message on the host socket, in both directions:

```text
 offset  size  field
 0       4     length   u32, big-endian: the number of bytes that FOLLOW
 4       1     kind     see §3
 5       8     link     u64, big-endian: the link this message concerns, or 0
 13      n     payload  kind-specific; n = length - 9
```

`length` counts `kind` + `link` + `payload`, so its minimum legal value is `9`
and the smallest message is 13 bytes on the wire. A message whose `length` is
below 9, or above `9 + 16 MiB` (the peer-frame limit, `MAX_FRAME_LEN`), is a
protocol error and the sidecar answers with `ERROR` on link 0 and drops the
connection.

Link id `0` is reserved for messages that are not about one link (`CONTROL_LINK`).

### Link ids

* **Odd, non-zero ids are the host's.** The host picks one when it sends `DIAL`
  and that link keeps it.
* **Even ids are the sidecar's**, allocated to inbound links it accepts.

The split is what lets the host name a link in `DIAL` before the sidecar has one
without the two allocators ever colliding. A `DIAL` on an even or zero id, or on
an id already in use, is answered with `ERROR` on that id and establishes
nothing.

### Multiplexing is local only

The kind byte and link id multiplex control traffic and every link's frames onto
the **one** host TCP connection. This says nothing about QUIC: a peer link still
owns **exactly one bi-directional QUIC stream** for its whole lifetime, and a
`DATA` payload is exactly the frame that rides it (`src/frame.rs`). The two
framings share the 4-byte big-endian length prefix and nothing else.

### Backpressure

Both queues — the socket writer's and each link's send queue — are bounded (256
messages). A host that stops reading eventually stalls the QUIC read loop
feeding it; a peer that stops reading eventually stalls the host's `DATA`
dispatch. Nothing buffers without limit and nothing is dropped.

## 3. Message kinds

Kinds below `0x80` originate at the host. Kinds at or above `0x80` originate at
the sidecar. `DATA` (`0x05`) is the single kind that travels both ways.

### Host → sidecar

| kind | name | link | payload | answered with |
|------|------|------|---------|---------------|
| `0x01` | `GET_ID` | 0 | empty | `ID` |
| `0x02` | `LISTEN` | 0 | empty | `LISTENING` |
| `0x03` | `ADD_PEER` | 0 | 32-byte endpoint id, then UTF-8 comma-separated socket addresses | `PEER_ADDED`, or `ERROR` on link 0 |
| `0x04` | `DIAL` | host-chosen odd id | 32-byte endpoint id of the peer | `LINK_UP` or `ERROR`, both on that id |
| `0x05` | `DATA` | an established link | the peer frame, verbatim | nothing, or `ERROR` on that id |
| `0x06` | `CLOSE_LINK` | an established link | empty | `LINK_DOWN` on that id, or `ERROR` if unknown |
| `0x07` | `SHUTDOWN` | 0 | empty | nothing; every link is closed and the process ends |

**`GET_ID`** — report this endpoint's own id. Legal at any time.

**`LISTEN`** — start accepting inbound peer connections. Idempotent: a second
`LISTEN` starts nothing new and still answers `LISTENING`. Until it is sent, no
inbound link is accepted.

**`ADD_PEER`** — teach the endpoint how to reach a peer offline, so a later
`DIAL` needs no relay or DNS. The payload is 32 bytes of endpoint id followed by
zero or more socket addresses (`127.0.0.1:41001,[::1]:41001`) as UTF-8, comma
separated; surrounding whitespace is ignored and an empty list is legal. A
malformed payload yields `ERROR` on link 0 and adds nothing.

**`DIAL`** — dial the peer named in the payload and open the link's single
bi-directional QUIC stream. The reply is asynchronous: `LINK_UP` on success,
`ERROR` on failure, both on the host's chosen id. The host **must wait for
`LINK_UP`** before sending `DATA` on that id; a `DATA` for a link that is not yet
established is answered with `ERROR`, not queued.

**`DATA`** — send one frame to the peer on that link. The payload is the frame
body; the sidecar adds the QUIC-side length prefix. A payload larger than
`MAX_FRAME_LEN` (16 MiB) is refused at the codec.

**`CLOSE_LINK`** — take the link down locally. The `LINK_DOWN` that follows comes
from the link's own observer, so a host close and a peer close produce the same
single notification.

**`SHUTDOWN`** — close every link and end the process. The sidecar sends nothing
further.

### Sidecar → host

| kind | name | link | payload |
|------|------|------|---------|
| `0x81` | `ID` | 0 | 32-byte endpoint id of this sidecar |
| `0x82` | `LISTENING` | 0 | UTF-8 comma-separated bound UDP socket addresses |
| `0x83` | `PEER_ADDED` | 0 | the 32-byte endpoint id that was added |
| `0x84` | `LINK_UP` | the link | 32-byte remote endpoint id, then 1 direction byte |
| `0x85` | `LINK_DOWN` | the link | UTF-8 reason |
| `0x86` | `ERROR` | the link it concerns, or 0 | UTF-8 message |

**`LISTENING`** — the addresses this endpoint is bound to, in the exact form
another sidecar's `ADD_PEER` accepts. This is how two sidecars are introduced
without a relay.

**`LINK_UP`** — a link exists and frames may flow. The direction byte is `0x00`
when this side dialled and `0x01` when this side accepted. `LINK_UP` is always
emitted **before** any `DATA` or `LINK_DOWN` for that link id.

On the **accepting** side it is emitted as soon as the peer's connection is
accepted, which is earlier than the link's QUIC stream exists: QUIC reveals a
stream to the peer only when its opener first writes, so the accepting sidecar
adopts the stream when the dialler's first frame arrives. Two consequences the
host has to know:

* `DATA` sent on a freshly accepted link is *queued* until the stream is
  adopted, not refused. It is delivered in order once the dialler speaks.
* A dialling host that wants the accepting side's link visible immediately can
  send an **empty `DATA`** — a zero-length frame is legal on the wire and is
  delivered to the peer as an empty `DATA`.

On the **dialling** side `LINK_UP` follows the successful `DIAL` and its stream
exists already.

**`LINK_DOWN`** — terminal for that link id: no further `DATA` will arrive on it
and no further `DATA` may be sent on it. Emitted **exactly once** per link,
whichever cause comes first — the peer finishing the link's stream, the
connection being lost, or a local `CLOSE_LINK`. The reason is human-readable
only; hosts must not parse it. The id becomes free for reuse afterwards.

**`ERROR`** — a request failed. It is not terminal for the connection unless the
message itself was malformed (§2), in which case the sidecar drops the
connection after sending it.

## 4. A complete exchange

Two sidecars, A (accepting) and B (dialling), with a host driving both:

```text
host→A   GET_ID       link 0
A→host   ID           link 0   <A id>
host→A   LISTEN       link 0
A→host   LISTENING    link 0   "127.0.0.1:49812"

host→B   GET_ID       link 0
B→host   ID           link 0   <B id>
host→B   ADD_PEER     link 0   <A id> "127.0.0.1:49812"
B→host   PEER_ADDED   link 0   <A id>

host→B   DIAL         link 1   <A id>
B→host   LINK_UP      link 1   <A id> 0x00
A→host   LINK_UP      link 2   <B id> 0x01   (on connection; stream adopted below)

host→B   DATA         link 1   3 bytes
host→B   DATA         link 1   70000 bytes
A→host   DATA         link 2   3 bytes
A→host   DATA         link 2   70000 bytes
host→A   DATA         link 2   <reply>
B→host   DATA         link 1   <reply>

host→B   CLOSE_LINK   link 1
B→host   LINK_DOWN    link 1   "link closed"
A→host   LINK_DOWN    link 2   "…"

host→A   SHUTDOWN     link 0
host→B   SHUTDOWN     link 0
```

`tests/protocol.rs` is this exchange, asserted.
