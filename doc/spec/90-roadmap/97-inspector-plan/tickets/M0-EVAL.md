# M0-EVAL — Evaluate & merge the topology vertical

Model: `claude-opus-5` (effort high) · Fresh session · Depends: M0-BE + M0-FE
complete. You are the arbiter: verify, fix or bounce, then merge.

## Protocol

Follow `00-orchestration.md` §Evaluation protocol. Milestone-specific checks:

1. **Contract conformance (highest priority — M1+ builds on this):** run the
   server against the skillmatch demo, capture `/api/inspect/topology` and a
   minute of `/api/inspect/events`, and diff shapes field-by-field against
   `20-api-contract.md`. Diff the FE fixture (`inspect/ui/fixtures/`) against
   the real snapshot — reconcile the fixture to reality if they diverge.
2. **Vertical smoke test:** skillmatch + inspector up, `npm run dev` against
   the real backend. Verify: graph renders; adding/removing a link at runtime
   (drive via the demo's ops endpoint or a small test harness) appears live
   without full re-layout of unrelated nodes; kill/restart flows update
   lifecycle.
3. **Invariant audit on the BE diff:** the kernel change is confined to the
   declared `describe(ref)` seam; no subscription/attention side effects from
   serving topology; SSE slow-client handling drops rather than blocks
   (read the code *and* run the test).
4. **Hygiene:** diffs confined to declared file scopes; no generated output;
   `./gradlew :inspect:test :kernel:test :demo:skillmatch:test`, `npm test`,
   then full `./gradlew test` before merge.

## Arbitration & merge

Small defects: fix in place. Structural defects (wrong seam, contract
violation, blocking I/O on graph threads): one bounce to the implementing
session with a concrete defect list; if the redo still fails, fix it yourself
and record that. Merge both tracks to `main` as `inspector(M0): …`, verify the
merged tree builds, append your report to `90-progress-log.md`.
