# Implementing a task / bug / chore

You are implementing one task as **one commit** on a feature branch, in a
worktree you share with other agents working other tasks of the same feature
*right now*. Two rules follow from that, and they're not optional:

- **Stay inside your `metadata.files` claim.** The orchestrator scheduled you
  in parallel precisely because your claim looked disjoint from theirs.
- **Commit by explicit pathspec.** Agents in a shared working tree share a
  git index; a bare `git add`/`git commit` can sweep up another agent's
  in-progress files into your commit (observed in this repo).

1. Read the task and its context:
   ```bash
   bd show <id> --json
   ```
   Plus its parent feature and epic, and any spec sections or prior comments
   they cite. Follow AGENTS.md's "Start every task here" — the cited spec
   text is the authority, not this file. Note `metadata.files`: that's your
   boundary.
2. Check dependencies (`bd show <id>` lists blockers). If something it
   depends on isn't actually done, that's a data problem in beads, not
   something to route around — park it (see [ask-human.md](ask-human.md))
   rather than implementing against an assumption.
3. Do **not** create a branch or worktree. You were told which worktree and
   branch to work in; stay there, and never touch the main checkout.
4. Implement the smallest coherent change that satisfies the task.
   - Hit a fork that clears the [ask-human.md](ask-human.md) bar (ambiguous,
     expensive, risky, hard to revert)? Park it there instead of guessing,
     then report and finish.
   - Need a file outside your claim? **Stop and report it** rather than
     expanding silently — another agent may be editing that file this
     second.
5. Verify per AGENTS.md's "Verification" section — narrowest relevant test
   first, then the affected module's suite. Don't report success on an
   untested claim. Expect the tree to contain other agents' in-flight work;
   a failure in a module you didn't touch is probably theirs, not yours —
   report it, don't try to fix it.
6. Commit, by pathspec only:
   ```bash
   git -C <worktree> commit -- <your/exact/paths> -m "<what changed and why>"
   git -C <worktree> show --stat HEAD
   ```
   The `--` pathspec form bypasses the shared index, so you commit your files
   and nobody else's. Never `git add -A`, never `--amend` (HEAD moves under
   you), never `git push --force`. Verify with `show --stat` that the commit
   contains exactly your paths — if it swept up someone else's file, say so
   in your report rather than trying to rewrite history underneath them.
7. If implementation reveals genuine new follow-up work, create it as a
   normal beads item (`bd create --parent=<feature-id>`) with its own
   `model` and `files` metadata — don't silently fold unrelated scope into
   this task.
8. Finish:
   ```bash
   bd comment <id> "<what landed, commit sha, anything notable>"
   bd close <id>
   bd dolt push
   ```
   Don't push the branch or touch the PR — the orchestrator owns those, and
   two agents pushing the same branch concurrently is how you lose commits.
9. Report: the task id, the outcome, the commit sha, and the files you
   **actually** touched (not just the ones you claimed). Drift between claim
   and reality is how the orchestrator fixes scheduling for later batches.
