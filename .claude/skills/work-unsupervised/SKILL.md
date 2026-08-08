---
name: work-unsupervised
description: Run the `work` skill in autonomous mode — no human is available, so hard calls get parked in Beads rather than guessed at.
disable-model-invocation: true
---

You are running **unsupervised**. Read this file, then run the `work` skill and follow it.

This file adds only the conditions the run happens under. Everything about *what* the
work is and *how* to do it — the epic/feature/task flow, time budget, claiming, parking
mechanics, branches and PRs, finalizing — lives in `work` and its references. Don't
restate it here, and don't second-guess it.

## No human is available

This session was started by a scheduled task, not by a person. Nobody is reading your
output while it runs, and nobody will answer a question you ask.

- **Do not ask clarifying questions.** A question in your output is not a question — it
  is a dropped task. Either decide it, or park it in Beads.
- **Do not wait or block on anything.** No approvals, no confirmations, no "let me know
  if you'd like me to continue."
- **Anything you want a human to see must be written somewhere durable** — a Beads
  comment, a commit message, a PR body. Your transcript is not durable.

The one exception is `work`'s identity check: if `BEADS_ACTOR` cannot be resolved, still
stop and end the run. Guessing a machine identity silently breaks the claim safety that
keeps two machines off each other's work — a dead run is far cheaper than that.

## Where the parking bar sits when nobody is listening

`work`'s [ask-human.md](../work/references/ask-human.md) defines what parking is and how
to do it. Follow it exactly — parking is a specific sequence, not just a comment.

What changes unsupervised is only *how readily* you park. Supervised, asking is cheap.
Here, a parked item waits until a human next looks, so both directions cost real time:

- **Park it** when the item is genuinely ambiguous *and* choosing wrong is expensive to
  undo — spreads across many call sites, changes a public interface or wire format, or
  commits to a semantic the spec doesn't actually settle.
- **Decide it yourself** when it's an ordinary judgment call — naming, file layout, test
  structure, which of two equivalent idioms. Record the assumption in the PR body or a
  Beads comment and move on.

The test: *if this choice turns out wrong, is it a five-minute fix or a five-hour one?*
Five minutes, decide. Five hours, park.

An agent that parks every small uncertainty makes no progress, which is the same as
failing. An agent that guesses on the expensive ones costs more than the run was worth.

## Ending the run

`work` finalizes properly on its own — follow its step 6 rather than inventing an
ending. Two failure modes to hold yourself to, since nobody is watching for them:

- **Padding.** If the queue is genuinely empty or everything left is parked, stop early
  and say so. Inventing work to fill the budget is worse than finishing at hour two.
- **Stopping mid-flight.** Whatever state you leave behind is what a human finds. Never
  end on a broken build, a half-applied refactor, or an uncommitted working tree.
