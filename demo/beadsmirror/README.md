# :demo:beadsmirror

Mirrors a `bd`/Dolt-backed beads workspace into a composite-key `OrMapCell`
by polling the workspace's Dolt commit graph (`dolt_diff_issues` /
`dolt_diff_dependencies` in `dolt_log` order). Depends on `:kernel` (the
cell-model types the projector builds on) and `:demo:shell` (the HTTP/SSE
plumbing the mirror serves its materialized fold through). See epic
computenet-dqj for the full design; `BeadsMirrorAppKt` is the runnable
`--workspace <path>` entry point.

## Two-node mode: the transport is injected

`--rig <name>` with `--listen <port>` or `--peer <ws://host:port>` turns on the
two-node mode (`MirrorPeering`): the projector's two cells become replicas of
one logical cell and gossip their deltas to the peer.

**Which transport carries that gossip is a parameter, not a fact of the code.**
`MirrorTransport` (main sources) is the seam — it owns establishing the
listening end, establishing the dialing end, and `partition()`/`heal()` on the
peering between them. `WsMirrorTransport` is the only binding that exists and
the only one a running app constructs; it is also the only file in the module
that names a `:wire` type, so a solo run still loads none of it.

The point of the seam is the convergence suite (feature computenet-7em.2): it
receives its wiring instead of naming it, so re-running the same assertions
over a different transport — the iroh work, epic computenet-7em §3 — is a new
binding and **zero test edits**. No test source under `src/test/**/e2e/`
imports `civictech.wire`; keep it that way.

`partition()`/`heal()` on the WebSocket binding sever and re-dial the
**dialing** end, leaving the listener bound throughout — `heal()` therefore
returns with the link already carrying, so a test's bounded wait is about
convergence and not about the transport coming back. The binding's own KDoc
states why that beats killing the listener.

## `--write-back`: opt-in, imposes the fold's winner onto `bd`

`--write-back` is a bare flag, off by default. With it set, EVERY configured
workspace's mirror runs its own `WriteBackApplier`, ticking once per poll
interval on its own dedicated daemon thread (`WorkspaceMirror.WriteBackScheduler`)
— not on the poll thread, and not gated on that workspace's own poll batch
having produced anything. (The bead originally decided to compose `applyOnce`
inside the poller's `onBatch`; that composition never re-evaluates a
peer-only winner change, because the poller skips `onBatch` entirely when its
own feed is empty, which is exactly what happens on gossip-only convergence
in two-node mode — see `WriteBackScheduler`'s KDoc for the measurement.) Each
tick reads the workspace's own `bd export`, compares it against the fold's
dot-order winner for each issue, and imposes any difference with **one `bd
import --allow-stale` invocation per changed row** — never a bulk import, and
never more than one row per invocation (feature computenet-6wc.1's clause 1).
Before each import it emits a machine-readable pre-flight loss record naming
exactly what that row is about to overwrite. A row whose winner already
agrees with the workspace is left alone — no import runs for it at all — and
a row whose import fails once is not retried until its winner changes.

Each workspace writes only to **its own** `bd` data; nothing here reads or
writes a sibling workspace in the same process. The live-`.beads` refusal
(`refuseIfLiveBeads`) still runs first, for every configured workspace,
whether `--write-back` is given or not — so a workspace that resolves to this
repository's own live `.beads` is refused before the mirror (and therefore
before any `bd import`) is ever built.

```bash
beadsmirror --workspace <path> --write-back
```

## Removal: close is the only interface; bd delete is barred

Feature computenet-6wc.2. Two ways an issue could stop existing, and they are
handled completely differently:

| | originate (`bd close` locally) | replicate (write-back applier) |
|---|---|---|
| close (`status=closed`) | `bd close` — guards fire (open blocking deps, open epic children) and a refusal mutates nothing | ordinary field imposition, same as any other field (`status`/`closed_at`/`close_reason` are members of `ImposedFields.FIELDS`) — no guard runs, by construction, because the import path has none. So a close that a guard refused when attempted locally can still land when it arrives as the dot-order winner from a peer (`writeback.WriteBackCloseTest`, `e2e.WriteBackCloseTwoNodeTest`). |
| delete (hard delete) | `bd delete` — a real, unrecoverable row removal | **never invoked, anywhere in this module** — there is no removal path here at all |

**Why `bd delete` is barred.** A hard delete has no wire representation: `bd
export`/`bd import` carry rows, not tombstones, so a deleted row simply stops
appearing — and "stopped appearing" is indistinguishable from "not part of
this delta". Treating absence as a removal signal is anti-durable under
bidirectional replication: the next hop from *any* peer that still holds the
row reintroduces it, with no error and no diagnostic (spike claim (c) C4,
`doc/spike/bds0/claim-c-close-replication.md`). Close does not have this
problem — `status`/`closed_at`/`close_reason` are ordinary fields that ride
the wire like any other, so a close converges the same way a priority edit
does.

**Accepted consequence.** Because close (not delete) is the only removal this
module replicates, and the open-children guard `bd close` enforces locally is
epic-only (not inherited by field imposition), a closed epic with
an open child can exist, stably, on every machine after replication — the
applier will not "fix" that by touching the child, because absence of the
child from a winner is never a removal signal either.

**Guard.** `RemovalBoundaryTest` scans this module's entire main source set
for the double-quoted string literal `"delete"` (never flagged: backtick KDoc
prose describing the boundary, such as this section's own).

**Operational warning.** Running `bd delete` by hand against a workspace this
mirror replicates does not do what it looks like: the row disappears locally,
then comes back on the next converging poll from any peer that still has it —
silently, with nothing in the mirror's output calling out a "restore".
`bd close` is the operation that actually sticks.

## Echo suppression: the mirror does not re-read its own writes

Write-back creates a loop. Every `bd import` the applier runs produces a Dolt
commit **in the workspace this same mirror polls**, so without suppression the
poller sees that commit, the projector mints a fresh local dot for it, and the
mirror gossips its own write back to the peer as if a human had made it — at
best a phantom concurrent edit that dot order has to keep re-adjudicating.
Feature computenet-6wc.3 closes that loop.

**Two `metadata` keys, written by the applier on every imposed row.**

| key | what it is |
|---|---|
| `cn_dot` | provenance: `"<sourceId>:<counter>"` of the dot-order-max winning dot across every live key of that issue in the fold — the mirror state the row was imposed *from*. Written by `WriteBackPlanner`, deterministic. |
| `cn_echo` | the echo signal: a UUID unique to **one** `bd import` invocation. Minted by `WriteBackApplier` immediately before the import. |

They are two keys rather than one because `cn_dot` cannot identify a commit: it
legitimately repeats across two impositions of the same row (a local edit whose
dot loses on height is re-overwritten by the same peer winner, re-stamping the
same `cn_dot`). Both keys are stripped from **both** sides before any `metadata`
comparison (`Provenance.strip`), and an object empty after stripping counts as
absent — otherwise a re-baseline would project the stamp into the fold and the
mirror would impose every issue on every restart.

**The gate rule.** `EchoGate` sits between the poller and the projector
(`onBatch = { applyAll(echoGate.admit(it)) }`), one per `WorkspaceMirror` — not
per projector, because a re-baseline swaps the projector wholesale and an
expectation registered before the swap must still suppress the commit that lands
after it. The applier announces each token through `expectEcho` *before* the
import and withdraws it with `cancelEcho` on a non-zero exit. A record is an
**echo** if and only if both:

1. its `to_metadata.cn_echo` is a JSON string equal to a token pending for that
   issue, **and**
2. its `from_metadata.cn_echo` differs — i.e. **this commit wrote the token**.

A match consumes the expectation, so one announced token suppresses at most one
commit. Everything else is **local** and reaches the projector unchanged. The
second condition is the whole point: the stamp *persists* in `bd`'s `metadata`,
so every later genuine edit on a stamped row carries the same token on both diff
sides. A rule keyed on "have I seen this provenance before" — which is what the
earlier `CnDotRegistry` drop did, and why it was removed — silently drops real
edits on every stamped row, forever.

**It is observable per commit.** Each record, echo and local alike, emits one
`MirrorEvent.RecordClassified(commitHash, issueId, classification, cnDot,
cnEcho, workspaceIdentity)` through the ordinary event sink, plus `echoCount` /
`localCount` on the gate. A suppression nobody can observe is a suppression
nobody can debug. There is no HTTP field for it.

With write-back off, nothing ever calls `expectEcho`, every record classifies
local, and the gate is a pass-through that counts.

### Recorded limitations — known, not solved

- **`dolt_diff_issues` is not a stable `bd` surface.** The whole provenance read
  depends on it; a `bd` schema change can move it out from under this without
  warning. Hardening against that is explicitly out of scope.
- **`--dolt-auto-commit` batching coalesces commits**, and `bd compact` / `gc` /
  `flatten` squash the feed. (The checkpoint half of that is already handled by
  the `CheckpointGone` re-baseline; the classification half is not.)
- **A local `bd` edit landing inside the import window on the SAME row is
  misclassified** — the import's commit carries the token, and a concurrent
  human edit folded into that same commit rides through suppressed. A narrow
  race, documented rather than closed.
- **A restart between an import and the poll that would have seen its commit
  loses the pending token.** The start-time re-baseline consumes that commit
  instead, so no phantom follows — but that corner is reasoned, not measured.
- **An import that exits non-zero AFTER its Dolt commit already landed loses
  the pending token the same way.** `WriteBackApplier.applyOnce` calls
  `cancelEcho(issueId, token)` on any non-zero exit, withdrawing the
  expectation regardless of whether the commit actually happened — `bd`'s exit
  code is the only signal available, and it does not distinguish "nothing
  committed" from "committed, then failed on the way out" (a crash, a kill
  signal, or a post-commit report-writing failure). The poller then sees that
  commit with no matching expectation and classifies it **local**, minting a
  fresh dot for it. Same harmless-but-noisy outcome as the restart corner
  above: the value imposed is the one the mirror itself just wrote, so the
  fold still converges, just noisily. Empirically, every non-zero exit
  observed from ordinary `bd import` failures (schema/validation errors,
  malformed JSONL) happens *before* any commit; whether `bd` can itself exit
  non-zero after a successful commit in the ordinary (non-killed) case was not
  settled — this bullet records the corner rather than closing the question.
- **Multi-hop topologies are out of scope.** Everything above is stated and
  tested for the two-node rig only.

The end-to-end evidence is `e2e.EchoSuppressionTwoNodeTest` (real `bd`/`dolt`, a
real socket, write-back on **both** nodes): the stamp read back out of
`dolt_diff_issues`, the classification of that exact commit, no dot minted for
it on either node, and both stores, folds and `dolt_log`s quiescent over eight
poll intervals afterwards.

## Real-workspace tests need `bd` and `dolt` on PATH

This module's test suite mixes synthetic tests (in-process fixtures, no
external process) with real-workspace tests that need `bd`/`dolt` on PATH;
which set is larger shifts as tests are added on either side, so don't rely
on either being the majority (computenet-mwwr: measured 2026-08-18, 14 of the
module's 25 test classes carry the guard below, so real-workspace tests were
already close to half even before this note was written). What matters is
that the tests that actually validate the module's whole reason for existing
— that the mirror agrees with a real `bd` workspace — drive a real
`bd --sandbox init` scratch workspace and a real `dolt` binary via
`BdScratchWorkspace`:

- `BeadsMirrorAppTest.AgainstAScratchWorkspace`
- `RebaselineTest`
- `BaselineBuilderTest`
- `equality.MirrorExportEqualityTest`
- `feed.DoltCommitFeedTest`, `feed.CheckpointResumeTest`
- `dolt.DoltSqlTest`
- `e2e.DivergenceControlTest`, `e2e.ScriptedSequenceTest`, `e2e.TwoNodeRigTest`

Each of these guards itself with:

```kotlin
assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
```

If either binary is missing, the guarded tests report **skipped**, not
failed — a suite that skips every real-workspace test is indistinguishable
from a full pass in the console summary and in a green `BUILD SUCCESSFUL`.
On a developer machine with both binaries already on PATH this is invisible:
the local run reports 0 skipped and looks like full coverage even though the
exact same run on a machine missing either binary would report the same
"pass" having exercised only the synthetic half.

**CI installs both binaries.** `.github/workflows/ci.yml`'s `build-test-fast`
job — one of the required checks — installs pinned `bd` and `dolt` releases
before running `./gradlew build check`, specifically so these suites execute
for real rather than skip (computenet-dqj.14). `bd --sandbox init` (what
`BdScratchWorkspace` drives) is a throwaway embedded-Dolt workspace under a
temp directory — no DoltHub credentials or network access are required for
any test in this module. The same job also publishes this module's JUnit
skipped-test count to the run's step summary, and fails the job outright if
`:demo:beadsmirror:test` produced no JUnit XML at all (i.e. did not run), so
a return to "every real-workspace test silently skipped" is visible in the
gate itself rather than only discoverable by reading a local run.

If you see these tests reported as skipped in a CI run, that means the
install step in `build-test-fast` regressed (binary download failed, PATH
not updated, version pin stale) or the runner otherwise lacks `bd`/`dolt` —
treat it as a coverage gap for this module, not as a pass.

Running locally: install `bd` (https://github.com/gastownhall/beads) and
`dolt` (https://github.com/dolthub/dolt) and put both on PATH, then

```bash
./gradlew :demo:beadsmirror:test
```
