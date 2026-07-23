# Durable keyed cell family (spawn-on-first-touch, journaled membership)

## Origin
`demo/shopping` grows one writer cell per user, lazily, and must itself keep that
family durable across restarts. `writerFor(user)` (Main.kt) hand-codes the whole
lifecycle:
- deterministic ref minting per key (`UUID.nameUUIDFromBytes("demo-writer:items:$user@$myRole")`),
- a `synchronized` `writers` map as the in-memory index,
- a side-file `users.txt` (`usersFile`) to remember which keys ever existed,
- and a recovery dance in `init`: `knownUsers().forEach { writerFor(it) }` **before**
  `host.recoverFrom(journal)`, because replayed ops need their cells pre-spawned to
  re-mint replay-stable tags.

That's four moving parts and an ordering hazard, re-implemented in every demo that
has a dynamic, per-key set of writers. Get the pre-spawn order wrong and journal
replay silently diverges.

## What it is
A framework-level "dynamic keyed family" — `KeyedCells<K>` — that owns exactly the
plumbing above: deterministic ref-per-key, lazy `getOrSpawn(key)`, a durable record
of live keys, and correct **replay ordering** (pre-spawn all known keys before the
host replays their journaled ops).

## Why it fits the framework
- The pieces are all already framework concepts — deterministic `CellRef` minting,
  `IdentityBinding.Exact`, `ManagedHost.spawn`, `FileJournal`/`recoverFrom`. Today
  they're assembled by the app; this packages the assembly with the one correct
  ordering, so "durable dynamic graph growth" is a supported operation rather than a
  demo trick.
- Directly serves the durability invariants (M10.4): "deterministic identity +
  routed (journaled) deltas are what make a journal replay reconstruct the same
  graph." The pre-spawn-before-replay rule is subtle and easy to get wrong — the
  exact kind of thing that belongs behind an API with a test, not in prose comments.
- Distinct from `PartitionedCell` (placement/partitioning of a keyed dataset, G-24):
  this is about **lifecycle + durability of a dynamically-sized cell family**, not
  about splitting one cell across hosts. (Design should confirm they compose and
  don't overlap.)

## Solution sketch
```
class KeyedCells<K>(
    private val host: ManagedHost,
    private val journalDir: File?,             // null = ephemeral
    private val namespace: String,             // e.g. "demo-writer:items@listener"
    private val factory: (K, CellRef) -> Cell,
) {
    fun getOrSpawn(key: K): Cell               // lazy, deterministic ref, records key durably
    fun recover()                              // pre-spawn all known keys, THEN host.recoverFrom(journal)
    fun keys(): Set<K>
}
```
`namespace` + `key` derive the deterministic ref; the durable key set replaces the
ad-hoc `users.txt`; `recover()` encapsulates the pre-spawn/replay ordering.

## Inputs / outputs
- **Input:** a namespace, a per-key factory, an optional journal dir, and keys via
  `getOrSpawn`.
- **Output:** live cells with stable refs across restarts; a durable, queryable key
  set; a `recover()` that leaves the family byte-identical to pre-crash.

## Acceptance criteria
- After N `getOrSpawn` + routed ops, `kill -9` + `recover()` yields identical
  converged state and identical tags (the shopping crash/restart scenario, but the
  bookkeeping owned by `KeyedCells`).
- Re-`getOrSpawn(key)` for a live key returns the same cell/ref (idempotent), and
  hitting the `Exact` live-ref spawn guard is impossible via this API.
- `demo/shopping` replaces `writerFor` + `writers` map + `usersFile` +
  manual pre-spawn with one `KeyedCells`; `CrashRestartConvergenceTest` passes
  unchanged.
- Ephemeral mode (no journal dir) works with zero files touched.
