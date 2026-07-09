# 32 — Concurrency Colors: Pure, Blocking, Suspending

> **Status**: Specified (accepted ADR); implemented in legacy, ⚠ not yet ported to germ
> **Sources**: ADR 2 (Accepted)
> **Implementation**: legacy `civictech.runtime.blocking.*` / `runtime.suspending.*` (adapters, bridges, hosts); germ has virtual-thread hosting only

## The three colors

Logic is classified by execution style:

- 🟢 **Pure** — local, non-blocking, non-suspending; computes and returns.
  May be lifted into either other color.
- 🔵 **Blocking** — may block (file IO, JDBC, locks). MUST run on a virtual
  thread.
- 🟣 **Suspending** — may suspend (Kotlin coroutines). MUST run in a
  coroutine context.

Discipline per color: blocking logic calls only blocking/sync APIs; suspending
logic calls only suspend functions; pure logic does neither.

## Compatibility and coercion (normative)

| Host color | May host |
|---|---|
| Virtual-thread host | 🔵, 🟢 (coerced to 🔵) |
| Coroutine host | 🟣, 🟢 (coerced to 🟣) |

- 🔵 and 🟣 never coexist in one host.
- 🟢 cells are written once and **coerced** via adapters
  (`BlockingTaskAdapter` / `SuspendingTaskAdapter` in legacy code) — no logic
  duplication (P5: compatibility is encoded in adapter types).
- Color propagates **upstream** (consumer → producer): a pure cell adopts the
  color of what it feeds, so chains fuse into direct calls inside one host
  (P2). Placement engines SHOULD co-host same-colored chains.

## Bridges

Cross-color (and generally cross-host) communication uses **unidirectional,
type-safe bridges** wrapping the downstream host's intake:

| Bridge | Mechanism |
|---|---|
| Coroutine → Coroutine | wrap downstream `Channel`, `send` |
| Blocking → Blocking | wrap downstream `BlockingQueue`, `put` |
| Coroutine → Blocking | `put` wrapped in `suspendCancellableCoroutine` (never block a coroutine) |
| Blocking → Coroutine | `send` wrapped in `runBlocking` (contained, sender-side) |

Safety properties: no coroutine starvation by blocking calls, no thread
starvation by suspension; misuse is unrepresentable because only matching
bridges exist (P5).

## Where this meets the germ model

In germ terms, colors answer: what is the host's queue/executor, and how do
proxies enqueue into a *differently-colored* host?

- The host consumer loop is color-specific (virtual thread draining a
  `BlockingQueue`-family vs coroutine draining a `Channel`).
- `enqueueHostedInvocation` is exactly where a bridge lives: the proxy held by
  the sender embeds the bridge matching (sender color → target color).
- 🟢 pure cells need no adapter classes in germ — pure logic served on a port
  simply *runs on whatever host context invokes it*; the "coercion" is
  placement. Explicit color declaration remains necessary for 🔵/🟣 so
  placement can validate host compatibility.

⚠ GAP (G-3): port the color model onto germ:
1. `HostColor` on `Host`; color declaration on cells (annotation or marker
   interface: `BlockingCell`, `SuspendingCell`; unmarked ⇒ pure).
2. Coroutine `ManagedHost` (G-27).
3. Bridge selection inside cross-host proxy creation (`HostedCellProxy` /
   `HostProxy`) keyed by (senderColor, targetColor).
4. Spawn-time validation: wrong-color spawn is rejected.
5. Then retire legacy `runtime.*` (G-1).

## Rationale for two runtimes (retained from ADR 2)

Flexibility (choose per workload), performance (skip suspend machinery where
unneeded), safety (no accidental blocking-in-coroutine), reuse (pure logic
runs anywhere). The cost — asymmetric bridges — is isolated and explicitly
modeled.
