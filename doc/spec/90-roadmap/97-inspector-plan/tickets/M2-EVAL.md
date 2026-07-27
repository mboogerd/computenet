# M2-EVAL — Evaluate & merge the errors vertical

Model: `claude-sonnet-5` (effort high) · Fresh session · Depends: M2-BE + M2-FE.
You are the arbiter: verify, fix or bounce, then merge.

Follow `00-orchestration.md` §Evaluation protocol. Milestone-specific checks:

1. **No-consumption invariant:** the dead-letter attachment is Observe-role
   only (code review); the inspector retains no payload references (grep the
   BE diff for stored `DeadLetter`/invocation objects — only extracted strings
   may be retained). This is the ownership invariant from `AGENTS.md`; a
   violation is a bounce, not a local fix.
2. **Vertical smoke:** run the BE ticket's error-induction recipe against
   skillmatch + UI; verify badge/pill/subsection update live and the header
   counters match `/errors`.
3. **Contract conformance** of `/errors` and the three event kinds; reconcile
   FE fixtures to server reality.
4. Standard hygiene + gates (`:inspect:test`, `npm test`, full
   `./gradlew test` pre-merge).

Merge as `inspector(M2): …`; append report to `90-progress-log.md`.
