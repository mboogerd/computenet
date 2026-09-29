# Make each bead true before dispatch

The orchestrator's checklist in /work step 5b, run on every task bead before
its implementer is dispatched. Reviewers score the bead's text and the
implementer builds on it literally.

- `acceptance-placement.sh`: MISPLACED → move criteria into the field; ABSENT →
  check the description, then write criteria to [breakdown.md](breakdown.md)'s
  standard and say so in the prompt.
- Test **every load-bearing claim the bead makes about state outside itself** —
  one the implementer or the review would act on or build against — for the cost of a grep or
  a file read, against the artifact that would show it, not a commit subject: a
  blocker, a precondition, a prescribed repro, a handoff instruction, a cited
  baseline or prior measurement, an assertion about what earlier work did or
  did not establish. That list is illustrative, and deliberately so —
  enumerating kinds is what let a superseded baseline and a false "prior work
  never tested this" through (computenet-d5y5); neither is a blocker, a
  precondition or a repro. Background prose nobody will act on is not
  load-bearing. Stale → correct the bead. Only checkable by doing the work, or
  dearer than a grep → mark it `unverified:` in the prompt
  ([breakdown.md](breakdown.md)); disproving it is a result.
- A cited record needs two answers, not one: does it still say what the bead
  says it says, **and is it still the current version of itself?** A superseded
  record usually sits exactly where it was with its original numbers intact,
  and is corrected by a LATER entry elsewhere in the record — so search the
  whole record for a later entry that corrects it, not only the lines cited.
  Where that record is a bead the correction is a later comment, and where it
  is a findings journal it is a later entry in the same file.
- A measurement also carries its host and configuration: figures from another
  machine, JVM or config are not a baseline for this one, and dispersion is the
  quantity most sensitive of all. Say so in the prompt as a limit on what the
  comparison can support, and where a same-host control arm is available
  authorize that instead — a control arm defeats confounds a single arm cannot
  even detect.
- Files claim: run `check-files-claim.sh`, then reason about what else must
  change ([breakdown.md](breakdown.md), "The files claim"); widen and
  comment why, then amend any acceptance clause the widening contradicts (old
  wording in a comment). An unexplained empty claim gets fixed now. Tasks whose
  acceptance reaches into another's claim go in separate batches.
- A disproved prediction, or an obligation a review added to a later task, is
  written on each affected unstarted bead as an `AMENDS <id>` comment before it
  is dispatched; `propagate-correction.py` finds the siblings repeating a claim.
