---
name: work-unsupervised
description: Runs the `work` skill in autonomous mode — no human is available, so hard calls get parked in Beads rather than guessed at. Use when a cron job, scheduled task, or routine starts an unattended work slot, or the user says "/work-unsupervised".
disable-model-invocation: true
---

You are running **unsupervised**. Read this file, then invoke the `work` skill with the
Skill tool and follow it.

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
- **That includes complaints about the process itself.** `work`'s step 7 friction log
  is the only channel you have for "this skill told me to do something that didn't
  work". Unsupervised, it is also the only way the skill ever improves — nobody was
  watching to notice. Use it, including for your own misreadings.

The one exception is `work`'s identity check: if `BEADS_ACTOR` cannot be resolved, still
stop and end the run. Guessing a machine identity silently breaks the claim safety that
keeps two machines off each other's work — a dead run is far cheaper than that.

## Where the parking bar sits when nobody is listening

`work`'s [ask-human.md](../work/references/ask-human.md) defines the bar and the exact
sequence. Follow it as written — parking is a specific sequence, not just a comment, and
its five-minute/five-hour test is already calibrated for nobody being there.

The one thing to hold harder here: **an answer arrives on human time, not session time.**
A parked item sits until someone next looks, so parking is genuinely expensive — but so
is a wrong guess on an interface, a wire format, or a semantic the spec doesn't settle.
Record every assumption you *do* make in the PR body or a Beads comment, since that is
the only place a human will ever see it.

## Ending the run

`work` finalizes properly on its own — follow its "Finalize" step rather than inventing an
ending. Two failure modes to hold yourself to, since nobody is watching for them:

- **Padding.** If the queue is genuinely empty or everything left is parked, stop early
  and say so. Inventing work to fill the budget is worse than finishing at hour two.
- **Stopping mid-flight.** Whatever state you leave behind is what a human finds. Never
  end on a broken build, a half-applied refactor, or an uncommitted working tree.
