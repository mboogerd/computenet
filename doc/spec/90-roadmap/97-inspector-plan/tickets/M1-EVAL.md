# M1-EVAL — Evaluate & merge the selection/state vertical

Model: `claude-sonnet-5` (effort high) · Fresh session · Depends: M1-BE + M1-FE.
You are the arbiter: verify, fix or bounce, then merge.

Follow `00-orchestration.md` §Evaluation protocol. Milestone-specific checks,
in priority order:

1. **Observer-effect discipline (the load-bearing invariant here, P6):**
   selecting a node creates exactly one observation; deselecting releases it —
   verify server-side (no leaked `ObserveCell`s after a select/deselect loop;
   write a small test if the BE ticket's isn't conclusive). Browsing the graph
   without selecting must issue no observe calls (check FE network traffic).
2. **Value encoding round-trip:** for each golden fixture, compare the real
   server output for an equivalent live cell against the FE fixture — the
   shapes must agree; reconcile fixtures to the server, not vice versa.
3. **Vertical smoke:** with skillmatch running, select a source cell, mutate
   via the demo's ops endpoint, watch the state table update live; frontier
   stamp advances; truncation note appears on a large set (script the bulk
   insert).
4. **Snapshot thread-safety:** confirm host-routed snapshot reads (code
   review + the BE test).
5. Standard hygiene + test gates per protocol (`:inspect:test`, `npm test`,
   full `./gradlew test` pre-merge).

Merge as `inspector(M1): …`; append report to `90-progress-log.md`.
