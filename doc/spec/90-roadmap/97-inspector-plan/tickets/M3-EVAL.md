# M3-EVAL — Evaluate & merge the flow vertical

Model: `claude-opus-5` (effort high) · Fresh session · Depends: M3-BE + M3-FE.
You are the arbiter: verify, fix or bounce, then merge.

Follow `00-orchestration.md` §Evaluation protocol. This milestone touches the
closest ground to the kernel's semantic contracts — weight the audit
accordingly:

1. **P2 audit (bounce-level):** read the tap handler line by line. Per-message
   cost must be a handful of atomic/volatile operations; any allocation,
   locking against the HTTP side, or work proportional to payload size on the
   graph thread is a structural defect. Confirm the BE report's stated
   per-message cost against the code.
2. **Ownership audit:** taps observe `Borrowed`; no payload retention.
3. **Fused-edge honesty:** fused chains report `fused: true`, zero rates, and
   the UI renders them as fused — end-to-end with a real fused chain in the
   pilot graph.
4. **Attribution correctness:** rerun the BE attribution test; then a live
   check — drive load through skillmatch, verify the busy edges light up
   proportionally and quiet edges stay dark; kill the UI mid-load and confirm
   the demo's throughput is unaffected (viz never blocks).
5. **Lifecycle:** link/unlink under load attaches/detaches taps without
   error; inspector shutdown untaps (rerun tests + code review).
6. Standard hygiene + gates, full `./gradlew test` pre-merge (this milestone
   is the likeliest to have perturbed kernel-adjacent behavior — the full
   gate is non-negotiable).

Merge as `inspector(M3): …`; append report to `90-progress-log.md`.
