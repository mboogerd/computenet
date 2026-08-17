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

## Real-workspace tests need `bd` and `dolt` on PATH

Most of this module's test suite is synthetic (in-process fixtures, no
external process). But the tests that actually validate the module's whole
reason for existing — that the mirror agrees with a real `bd` workspace —
drive a real `bd --sandbox init` scratch workspace and a real `dolt` binary
via `BdScratchWorkspace`:

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
