# Implementing a task / bug / chore

You implement one task on **your own branch, in your own worktree**, cut
from the feature branch. Sibling tasks are running on sibling branches right
now; yours merges into the feature branch after it passes review.

Two rules follow:

- **Stay inside your `metadata.files` claim.** Siblings were scheduled in
  parallel on the assumption it's accurate — a file outside it is likely
  being edited on another branch this second, and will merge into a conflict.
- **Work only in your own worktree.** Never the main checkout, the feature
  worktree, or another task's.

**You may be a resumed task.** If the worktree already has commits, an
earlier session started this and stopped at a clean point — read the beads
comments for what's done and what's left, and continue from there rather
than restarting. That's the whole reason the worktree is preserved.

1. Read the task and its context:
   ```bash
   bd show <id> --json
   ```
   Plus its parent feature and epic, and any spec sections or prior comments
   they cite. Follow AGENTS.md's "Start every task here" — the cited spec
   text is the authority, not this file. Note `metadata.files`: your
   boundary.
2. Check dependencies (`bd show <id>` lists blockers). If something it
   depends on isn't actually done, that's a data problem in beads, not
   something to route around — park it ([ask-human.md](ask-human.md))
   rather than implementing against an assumption.
3. Implement the smallest coherent change that satisfies the task.
   - Hit a fork that clears the [ask-human.md](ask-human.md) bar (ambiguous,
     expensive, risky, hard to revert)? Park it instead of guessing, then
     report and finish.
   - Need a file outside your claim? **Stop and report it** rather than
     expanding silently.
4. Verify per AGENTS.md's "Verification" section — narrowest relevant test
   first, then the affected module's suite. Don't report success on an
   untested claim.
5. Commit on your branch, then push it. Your worktree has its own index, so
   ordinary staging is safe here:
   ```bash
   git -C <your-worktree> add <your paths>
   git -C <your-worktree> commit -m "<what changed and why>"
   git -C <your-worktree> push -u origin <your-branch>
   ```
   Push even when the task is unfinished — an unpushed branch exists only on
   this machine, so the work is invisible and gets redone if anything else
   picks the task up.

   Push **your own branch only**. Never merge into the feature branch, push
   it, or touch its PR: the orchestrator merges after review and serializes
   it so concurrent merges don't race.
6. If implementation reveals genuine new follow-up work, create it as a
   beads item (`bd create --parent=<feature-id>`) with its own `model` and
   `files` metadata — don't fold unrelated scope into this task.
7. Finish:
   ```bash
   bd comment <id> "<what landed, and if unfinished, exactly what's left>"
   ```
   The comment stays in the local beads DB; the orchestrator's Finalize push
   (SKILL.md step 6) is what sends it to the shared tracker. Don't sync it
   yourself — per-session Dolt sync is down to that one push plus the one
   pull at session start.

   Leave the task `in_progress` — the reviewer and the orchestrator close it
   once it's merged.
8. Report: the task id, the outcome, and the files you **actually** touched,
   not just the ones you claimed. Drift is how the orchestrator fixes
   scheduling for later batches.

   Add a **friction** line for anything that made you slower or forced a
   guess: an underspecified task, a wrong `files` claim, a command in these
   instructions that didn't work, a step that didn't cover your case. Don't
   file it yourself — the orchestrator logs it centrally so recurrences are
   visible (SKILL.md step 7). Nothing to report is a fine answer; inventing
   one is not.
