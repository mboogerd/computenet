# tiering — backend bug: SSE `send()` writes to shared streams without synchronization

**Status**: latent / unconfirmed — identified by code + threading-model inspection; not
reproduced under load (see below)
**Severity**: low (hardening) — could corrupt the live event stream under true concurrent
broadcast; no user-visible corruption observed in testing
**Location**: `demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt`,
`send()` / `broadcast()` / `handleEvents()`

## Observation

`broadcast()` iterates `clients` and calls `send(out, json)`, which does an unsynchronized
`out.write(...)` + `out.flush()` on each client's `OutputStream`:

```kotlin
private fun broadcast() { val json = stateJson(); clients.forEach { send(it, json) } }
private fun send(out: OutputStream, json: String) {
    try { out.write("data: $json\n\n".toByteArray()); out.flush() }
    catch (_: Exception) { clients -= out }
}
```

A single user action can update several hubs (`tierAvg`, `prefAvg`/`fused`, `vals`), each
firing its own `broadcast()`. The host runs on `VirtualThreadScheduler`, so two hub callbacks
can in principle run on different threads and call `broadcast()` concurrently, both writing to
the **same** client `OutputStream`. `OutputStream.write(byte[])` is not atomic, so concurrent
writes can interleave bytes and produce a malformed `data: …\n\n` frame; the browser's
`EventSource` then fails to parse it and the live view silently stops updating. The initial
snapshot write in `handleEvents` (on the HTTP dispatch thread) can likewise overlap a broadcast
to the same stream.

## Expectation

Each SSE frame must be written to a given stream atomically with respect to any other writer of
that stream, so frames never interleave.

## Root-cause analysis / reproduction status

`CopyOnWriteArrayList` makes *iteration* of `clients` safe, but gives no mutual exclusion on the
per-stream `write`. There is no lock around the write itself.

I tried to reproduce corruption by reading the raw `/events` byte stream while firing 48
concurrent `tier` ops (which fan out to multiple hubs): **145 frames delivered, 0 malformed**.
The likely reason it did not corrupt is that the host's dispatcher appears to serialize hub
dispatch in practice (one cell dispatched at a time), so broadcasts rarely truly overlap. That
serialization is an implementation property, not a guarantee this code relies on, so the write
path is still unsafe by construction.

## Solution direction

Serialize per-stream writes — cheapest is a single lock around the frame write:

```kotlin
private fun send(out: OutputStream, json: String) {
    try { synchronized(out) { out.write("data: $json\n\n".toByteArray()); out.flush() } }
    catch (_: Exception) { clients -= out }
}
```

(or give each client a small single-threaded writer / queue). Coalescing rapid broadcasts into
one frame per settle-tick would also reduce write pressure, but the correctness fix is the
per-stream lock.
