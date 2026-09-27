# Council subagent prompt

/sync-report §8 hands this prompt to one background subagent per parked bead.
Fill in `<id>`. The subagent works in the main checkout and never pushes.

---

You are resolving ONE parked decision, `<id>`, with the `deliberate` skill
(`.claude/skills/deliberate/SKILL.md`). Read that skill first. Work only on
this bead.

1. **Read the thread.** Run `bd show <id>`, then
   `.claude/skills/work/scripts/park-thread.sh <id>`. If it already records an
   answer, or ends in a `COUNCIL` comment with nothing after it, stop and
   report `SKIPPED: <reason>`.
2. **Screen it** against deliberate §1. The question may be a permission (an
   agent was refused an action, or the action needs human approval), a choice
   the maintainer reserved (the thread says the maintainer decided the rule),
   or not a choice at all.
   - A permission or not-a-choice: comment
     `COUNCIL SKIPPED (deliberate, <date>): <why a person must answer this>`,
     leave the park as it is, and report `SKIPPED`.
   - A reserved choice: run the council anyway, but record the outcome in
     step 5 as advice (`COUNCIL ADVICE`) and never unpark.
3. **Write the brief** in a fresh scratch directory, following deliberate §2.
   Take the frame from `doc/spec/00-foundations/01-vision.md` and
   `02-design-principles.md`. Take the problem, context and options from the
   bead and its thread. Where the thread cites code or spec ids, read them, so
   that every fact in the brief is checked rather than copied. Keep it neutral.
4. **Run** `deliberate.py --brief <dir>/brief.md --out <dir>/council` in the
   background and wait for it. If it fails, comment nothing on the bead and
   report `FAILED: <error>`.
5. **Record** one comment on the bead, from a file. Its first line depends on
   the outcome:
   - DECIDED: `COUNCIL DECISION (deliberate, <date>): <id> — <title>`
   - NO-CONSENSUS: `COUNCIL NO-CONSENSUS (deliberate, <date>): a person decides`
   - A reserved choice: `COUNCIL ADVICE (deliberate, <date>): <outcome>`

   Paste the body of `verdict.md` below that line, and end with the path of the
   council directory. On DECIDED only, and never for advice, unpark the bead:
   `bd update <id> --status=open --assignee="" --remove-label=human`.
6. **Report** one line:
   `<id>: DECIDED <choice> (<mean>) | NO-CONSENSUS (<votes>) | ADVICE ... | SKIPPED ... | FAILED ...`,
   followed by at most three lines naming the deciding factors or the split.
