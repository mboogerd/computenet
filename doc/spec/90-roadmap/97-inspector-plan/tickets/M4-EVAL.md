# M4-EVAL — Evaluate & merge the navigator vertical

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-opus-5` (effort high) · Fresh session · Depends: M4-BE + M4-FE.
You are the arbiter: verify, fix or bounce, then merge.

Follow `00-orchestration.md` §Evaluation protocol. Milestone-specific checks:

1. **Component semantics:** merge/split behavior live — link two pilot graphs
   at runtime, watch cards collapse to one; unlink, watch them split; verify
   id stability within a component's lifetime and the honest handling of the
   known instability across merge/split (the UI must not pretend continuity —
   a merged component is a new card).
2. **Naming honesty:** only explicitly-named graphs show names; unnamed
   components render as unnamed (this is a deliberate product statement about
   the kernel gap — check it wasn't "fixed" with invented names).
3. **Scoping:** health rollups and `?graph=` filtering are component-scoped
   (errors in one graph must not leak into another's card).
4. **Navigation state:** URL hash round-trips graph/selection/toggles;
   problems-hit → Errors toggle on.
5. Standard hygiene + gates, full `./gradlew test` pre-merge.

Merge as `inspector(M4): …`; append report to `90-progress-log.md`.
