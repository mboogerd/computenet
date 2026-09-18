# Execution discipline for dispatched agents

Every dispatched agent — breakdown, implementer, task reviewer, feature
reviewer — reads this first, before its role reference. It covers what you may
touch, how to run and wait on commands, and what your final message must say.
Command pitfalls for `bd`, `git`, `gh` and the shell are in [traps.md](traps.md).

## Contents

- Scope
- Running commands
- Waiting
- Your final message

## Scope

- **Nothing resumes you.** No monitor, scheduler or caller wakes you later;
  your turn ending is your completion, whatever state you are in.
- Create your own scratch directory once, with
  `mktemp -d "<harness scratchpad>/<your-id>-<role>.XXXXXX"`, and keep every
  log and body file in it. Never write to the shared scratchpad directly:
  other agents keep files there under the names you would pick. Shell
  variables do not survive between Bash calls, so spell the path out.
- Edit only the files your dispatch or your item's `metadata.files` names.
  Siblings run in parallel on the assumption the claim is accurate. If the
  work needs a file outside it, report that rather than working around it.
- Tracker writes go to your own item and to items you create. Closing,
  re-prioritising, reassigning or editing any other item is the orchestrator's.
- Commit on your own branch with `git commit -m "<msg>" -- <paths>`, never
  `-a`: worktrees share one repository. A dispatch prompt that says "commit on
  your branch" is the explicit grant AGENTS.md's multi-agent clause asks for.
- **Never `git push` and never `bd dolt push`.** Your bead writes stay local
  and ride out on the orchestrator's next sync. The one exception: a feature
  reviewer pushes its own repair commits to the feature branch.
- Read `.claude/skills/work/**` from your own worktree, which is cut from
  `origin/main`; without one, use `git show origin/main:<path>`. The main
  checkout's working copy is stale.
- A refused command is not retried in a different disguise, and a refused
  sanctioned path never justifies a banned shortcut. Do the permitted
  equivalent (such as a refused script's steps, by hand), or list the exact
  command under `REQUIRED ORCHESTRATOR ACTION`.

## Running commands

- **Give every Bash call an explicit `timeout` argument, up to 600000 ms.**
  Past the default the call is backgrounded whatever you intended. This is the
  Bash tool's parameter: there is no `timeout` binary here, and piped it fails
  open, so a suite that never ran reports success.
- Every Gradle and npm call sets the Bash tool's `dangerouslyDisableSandbox`
  parameter (a tool parameter, not a `./gradlew` flag). The default sandbox
  fails KSP with `Operation not permitted`.
- `gradle.properties (Operation not permitted)` is not a build failure. A
  daemon started from a sandboxed call survives it and poisons later calls,
  other agents' worktrees included. Run `./gradlew --stop`, then re-run with
  the sandbox disabled.
- **Never write an unquoted `=` separator between batched commands.** zsh
  expands an `=`-initial word to that command's path, so `echo ===` fails with
  `(eval):1: == not found` **and kills every command batched after it** — the
  later half reads as having run and produced nothing. Quote it (`echo '==='`)
  or use a `# ---` comment. This is the single most-reported friction from
  dispatched agents (computenet-wfgba): five hit it independently in one slot,
  because the separator is written reflexively while batching. **Do not delete
  this rule to save lines** — it was stated here and in two role references
  until the 2026-09-14 distillation (`e6ecc3d1`) condensed all three away, and
  every recurrence since is from the fortnight that followed. The rest of the
  zsh family is in AGENTS.md "Implementation conventions".
- Redirect long output to a log in your scratch directory. Proving a test run
  executed is in [evidence.md](evidence.md).
- Scope your Gradle gate to the modules you touched unless your dispatch
  assigns you the repo-wide `./gradlew test`; only one agent runs that at a time.

## Waiting

- **Commit before you wait on long evidence**, so a stop costs the evidence
  and never the work. While `.mutation-in-progress` exists you do not commit:
  restore, remove the marker, commit, then start the long run.
- **The wait lives inside a foreground Bash call** — not a Monitor, not a
  backgrounded loop. A notification is delivered to a turn, and yours will
  have ended.
- A run longer than the call cap goes in the background
  (`run_in_background: true`), bounded by its own wrapper so a hung child
  cannot outlive you, writing to a log:

  ```bash
  perl -e 'alarm shift @ARGV; exec @ARGV' 3600 ./gradlew <task> > "<scratch>/run.log" 2>&1
  ```

- Wait on the log's content, never on a process (`pgrep` matches siblings),
  in a foreground call that expires under the 600 s cap:

  ```bash
  python3 - <<'EOF'
  import time, re, sys
  LOG, DEADLINE = "<scratch>/run.log", time.time() + 500
  while time.time() < DEADLINE:
      try: t = open(LOG).read()
      except FileNotFoundError: t = ""
      if re.search(r"BUILD (SUCCESSFUL|FAILED)", t):
          print(t[-800:]); sys.exit(0)
      time.sleep(10)
  print("WAITER EXPIRED - job may still be running; reissue")
  EOF
  ```

  An expired waiter is a reading, not a failure: reissue it. If the job died
  without a `BUILD` line, read the log tail rather than assuming.
- A `nohup … &` job reports completion on detach; track it by its log only.
- Append every background job to `<scratch>/jobs` (`<kind> <id or pid> <what
  it waits for>`) the moment you start it. Kill each before your final
  message: a job that outlives you keeps waking the orchestrator with stale
  copies of your report. An empty ledger is the positive answer that you
  started none.

## Your final message

State your outcome in words. The completion notification looks the same
whether you finished or stopped, and a result with no outcome can be read as
approval.

- The outcome token your role reference names (PASS or FAIL for a task
  review, READY or DRAFT for a feature review), and the files you actually
  touched.
- A `NOT VERIFIED` section listing everything you did not check. Out of time
  or room, give the partial outcome and put the rest here.
- A `REQUIRED ORCHESTRATOR ACTION` section with the exact commands you were
  refused or may not run. Omit it when empty.
- Your scratch directory's path, always: a resumed agent reads your job ledger
  and logs there.
- If you stop with a suite still running, say so on your item: which suite,
  which log, which committed sha. If you cannot write to the item, write the
  report to your scratch directory.
- Friction: one line per thing that cost real time, was not obvious, and is
  likely to recur. Report it; do not file it. "None" is a fine answer.
