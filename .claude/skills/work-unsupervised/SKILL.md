---
name: work-unsupervised
description: Run the `work` skill in autonomous mode — no human is available, so hard calls get parked in Beads rather than guessed at.
disable-model-invocation: true
---

You are running **unsupervised**. Read this whole file before doing anything, then run the `work` skill.

## No human is available

This session was started by a scheduled task, not by a person. Nobody is reading your
output while it runs, and nobody will answer a question you ask.

That has three consequences:

- **Do not ask clarifying questions.** A question in your output is not a question — it
  is a dropped task. Either decide, or park it (see below).
- **Do not wait or block on anything.** No approvals, no confirmations, no "let me know
  if you'd like me to continue."
- **Everything you want a human to see must be written somewhere durable** — a Beads
  comment, a commit message, a PR body. Your transcript is not durable.

## Time budget: about five hours

Run `date` when you start and note it. Check it periodically — you have no other sense of
elapsed time.

Aim for roughly five hours of productive work, then stop. Around the four-and-a-half hour
mark, stop picking up new work and spend the remainder landing what you have.

Two failure modes to avoid in equal measure:

- **Padding.** If the ready queue is genuinely empty or everything left is parked, stop
  early and say so. Inventing work to fill the budget is worse than finishing at hour two.
- **Stopping mid-flight.** Never end with a broken build, a half-applied refactor, or an
  uncommitted working tree. Whatever state you leave behind is what a human finds.

Work lands on a branch with a PR. Never push directly to the main branch.

## Park what you cannot decide safely

The judgment call is two-sided, and getting it wrong in either direction wastes the run.

**Park it** when the work item is genuinely ambiguous or underspecified *and* choosing
wrong would be expensive: a decision that is costly to undo, risky, spreads across many
call sites, changes a public interface or wire format, or commits to a semantic the spec
does not actually settle.

**Decide it yourself** when it is an ordinary judgment call — naming, file layout, test
structure, which of two equivalent idioms to use, anything cheap to reverse later. Record
the assumption in the PR body and move on. An agent that parks every small uncertainty
makes no progress, which is the same as failing.

When in doubt, ask: *if this choice turns out wrong, is it a five-minute fix or a
five-hour one?* Five minutes, decide. Five hours, park.

### How to park

Attach the question to the issue it belongs to, then flag it:

```bash
bd comment <id> "Blocked on a decision I shouldn't make unsupervised.

Question: <the specific thing that is unclear>
Options considered: <the real alternatives, and the tradeoff between them>
Why I'm not choosing: <what makes this costly, risky, or hard to reverse>
What I'd do if forced: <your best guess, so the human has a default to accept>"

bd tag <id> human
```

The `human` label is what surfaces it — a human picks these up with `bd human list` and
answers with `bd human respond <id>`. A comment without the label is invisible.

Be specific. "This is unclear" is useless three days later. Name the file, the requirement
id, and the exact fork in the road.

After parking, **move on to the next ready issue.** Parking is not stopping. Only end the
run when the budget is spent or nothing unparked remains.

## Now do the work

Invoke the `work` skill and follow it, subject to everything above. If `work` and this
file conflict, this file wins — it describes the conditions the run happens under, not
what the work is.
