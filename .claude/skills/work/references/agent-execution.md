# Execution discipline for dispatched agents

The rules every dispatched agent — implementer or reviewer — runs under.
task.md, review-task.md and review-feature.md all point here; this file is
the single copy. Nothing here is advisory: each rule exists because its
absence cost a session (the cited bead holds the story — `bd show <id>`).

**Open these references with the Read tool, not `cat`.** review-feature.md
overruns the Bash tool's result cap, and so does `cat` on the persisted
overflow file it points you at; Read pages (computenet-jobe).
**If `bd` (or anything under `~/Documents`) starts failing `Operation not
permitted` mid-task, it is macOS revoking the grant, not the sandbox** — it
will not come back. Finish your verdict or report, write it to a file
OUTSIDE that tree (`$SCRATCH` lives under `/private/tmp`), and name the path
in your final message; the orchestrator posts it (computenet-hc3s — the one
reviewer that did this is the reason its verdict survived).

## Contents

- [Your scratch dir is yours alone](#your-scratch-dir-is-yours-alone)
- [Foreground timeouts, and why there is no `timeout` binary](#foreground-timeouts-and-why-there-is-no-timeout-binary)
- [Commit before you wait on evidence](#commit-before-you-wait-on-evidence)
- [The bounded until-loop, and refusals](#the-bounded-until-loop-and-refusals)
- [`gradle.properties (Operation not permitted)` is not a build failure](#gradleproperties-operation-not-permitted-is-not-a-build-failure)
- [Commands that fail QUIETLY in this shell and on this host](#commands-that-fail-quietly-in-this-shell-and-on-this-host)
- [Two pushes, and neither is yours](#two-pushes-and-neither-is-yours)
- [The job ledger](#the-job-ledger)
- [Never end a turn waiting](#never-end-a-turn-waiting)

## Your scratch dir is yours alone

`$SCRATCH` anywhere in these references is **your own** agent-unique dir,
created once:

```bash
SCRATCH=$(mktemp -d "<harness scratchpad>/<your-id>-<role>.XXXXXX")
```

Never the shared harness scratchpad directly: it holds other agents' files
under exactly the names you would pick (~40 stale logs including
`exchange.log` and `wire.log`), and an agent that reads one quotes another
agent's build as its own evidence (computenet-84z6). A shell variable does
not survive between Bash calls — spell the absolute path out where a later
call needs it.

## Foreground timeouts, and why there is no `timeout` binary

**Run every verification command — Gradle above all — in ONE foreground Bash
call with an explicit `timeout` argument, up to 600000 ms.** This is **not a
Gradle rule**: on this host ANY command can outrun the Bash tool's 120s
default. Measured 2026-08-27 — a bare `echo "$BEADS_ACTOR"` killed at the
default as a session's first command, and a routine `uptime && git log && git
fetch` orientation call killed with the fetch mid-flight (computenet-ahg8).
Pass an explicit timeout on orientation commands too; a killed call looks like
a hung host, not like a default you did not set. The
foreground/background choice belongs to the Bash tool's 120s default, not to
your intent: past the timeout the tool backgrounds the call whatever you
asked for, and a turn that ends waiting on a background job never resumes —
your turn ending IS your completion (computenet-hob2).

The timeout meant here is the **Bash tool's own argument**, in milliseconds.
There is no `timeout` binary on this host — neither `timeout` nor `gtimeout`
(verified 2026-08-17). A bare `timeout 600 ./gradlew …` prints `command not
found` and exits 127, but **piped it fails open**: `timeout … | tee log`
hands you the last stage's status, i.e. 0, so a suite that never ran reports
success. `${PIPESTATUS[0]}` is not the rescue either — under zsh, this
repo's session shell, `PIPESTATUS` is empty and only lowercase `pipestatus`
carries the 127 (computenet-fbuo).

## Commit before you wait on evidence

**A suite you KNOW exceeds 10 minutes must be backgrounded, and the order
matters more than the waiting does** (computenet-ng9o):

> **Commit BEFORE you wait on evidence.** Then a stop — budget, classifier,
> host death — costs you the evidence, never the work. An agent that waits
> first and is stopped strands uncommitted changes in a worktree that reads
> to everyone downstream as "produced nothing".

Commit on your own branch, still **without** a push. It does **not** override
the mutation-marker rule: while `.mutation-in-progress` exists you never
commit ([mutation-check.md](mutation-check.md)), so a mutation check is never
what you background and wait on. Restore the code, remove the marker, commit,
and only then start the long evidence run.

## The bounded until-loop, and refusals

Background the long run (`run_in_background: true`, writing to
`"$SCRATCH/run.log"`), then wait with a **bounded** until-loop on the log:

**You have no inbound wake-up.** Nothing will notify you, resume you, or report
back to you. Your turn ending IS your completion. Every version of this defect
has been an agent inventing a mechanism that does not exist — most recently one
that "held" for a monitor to report settlement (computenet-kp0y) — so the fact,
not the prohibition, is what you need: there is no monitor, no scheduler, no
caller polling on your behalf.

**The wait must live INSIDE a foreground Bash call.** Not a `Monitor`, not a
backgrounded loop, not any other watcher — a notification is delivered to a
*turn*, and yours has ended, so the one thing the wait exists to do (keep you
alive) is exactly what those cannot do. This is not hypothetical and it is not
carelessness: an agent given the strongest available version of this warning
still armed a Monitor and reported *"running with a bounded polling monitor…
I'll resume automatically when that monitor task notifies me — ending this turn
now"*. It had complied with "wait with a bounded until-loop" — a Monitor **is**
a bounded watcher — and stalled anyway, because the instruction specified the
wait's SHAPE and not its LOCATION (computenet-oh3h, the third recurrence of
computenet-hob2/computenet-ng9o). The orchestrator's own long jobs are the
opposite case and use Monitor deliberately ([long-jobs.md](long-jobs.md)); that
file is not for you.

**The waiter is itself a foreground Bash call, so it is bounded by the same
10-minute cap as anything else.** Size the loop to expire *inside* one call —
25 rounds of 20s is ~8m20s, comfortably under 600000 ms:

```bash
i=0
until grep -qE 'BUILD (SUCCESSFUL|FAILED)' "$SCRATCH/run.log" 2>/dev/null; do
  i=$((i + 1)); [ "$i" -gt 25 ] && { echo "WAITER EXPIRED at ~8m — job may still be running"; break; }
  sleep 20
done
tail -5 "$SCRATCH/run.log"
```

**A waiter that expires is not a failed job — reissue it.** A suite longer than
the cap needs SEVERAL sequential wait calls, and that is the normal path, not
a fallback: `:demo:beadsmirror:test` runs ~11m45s, so it takes two. The old
example bound here was 60 rounds (~20m), which cannot fit in one call at all —
it died at the 10-minute wall on exactly the suites the pattern exists for, and
two agents in one session hit that (computenet-qk5f). Reading the expiry as
"the job failed", or ending your turn on the first waiter, is how the run gets
lost.

**The bound is the part you cannot drop.** A job that dies without ever
writing a `BUILD` line — an OOM, a killed daemon, a redirect that went
somewhere else — never satisfies the grep, and an unbounded `until` burns the
rest of the session in a loop nobody is watching. Set the bound above the
suite's known duration, and treat hitting it as a reading: read the log's
tail and the job's status rather than assuming.

Wait on the **log's own content**, never on a process: a `pgrep -f
<pattern>` waiter matches any *sibling* process carrying that pattern in its
argv — your own backgrounded poll shell among them — so the condition never
goes false. (It does not match the waiting shell itself or its ancestors:
measured 2026-08-14 on darwin/arm64, macOS `pgrep` excludes both unless given
`-a`.) `gh pr checks --watch` returns immediately when only `auto-merge` has
reported on a fresh head, so it is not usable as a wait either.

**On refusals, and the form that always runs.** A *bare* long `sleep` is
refused by the auto-mode classifier (`sleep 240 && echo done` → "use Monitor
with an until-loop…", measured 2026-08-17), which is where the until form comes
from. A `for i in $(seq 1 N)` waiter was refused once (computenet-ng9o) but is
not reliably refused — treat that refusal as contextual.

**The shell loop above is not always writable.** Three dispatched agents in one
session reported `sleep` refused *inside* the loop, so the prescribed `until
… sleep 20 … done` could not be written at all, and each independently
reinvented the same substitute (computenet-4zv5). Skip the rediscovery — this
is a plain program, not a shell construct the classifier inspects, and it runs
in every harness measured (verified 2026-08-20):

```bash
python3 - <<'EOF'
import time, re, sys
LOG, DEADLINE = "<spell the log path out>", time.time() + 500   # inside the 600s cap
while time.time() < DEADLINE:
    try: t = open(LOG).read()
    except FileNotFoundError: t = ""
    if re.search(r"BUILD (SUCCESSFUL|FAILED)", t):
        print(t[-800:]); sys.exit(0)
    time.sleep(10)
print("WAITER EXPIRED at ~8m20s — job may still be running; reissue")
EOF
```

Everything the shell form promises still holds for it: it lives inside ONE
foreground Bash call, it is bounded, it waits on the log's CONTENT, and an
expiry is a reading rather than a failure — reissue it. Note the asymmetry that
makes this worth stating: the ORCHESTRATOR's harness permits `sleep` (measured
2026-08-20, `sleep 15` and a three-round `until`/`sleep` loop both ran), so an
orchestrator writing a dispatch prompt cannot reproduce the refusal its
implementer will hit.

## `gradle.properties (Operation not permitted)` is not a build failure

A Gradle daemon started from a **sandboxed** Bash call lacks macOS Documents
access. It SURVIVES the call that started it, and then poisons every later
invocation — including unsandboxed ones, and including OTHER agents' worktrees,
because the daemon is shared per JVM/toolchain rather than per worktree:

```
FileNotFoundException: /path/to/worktree/gradle.properties (Operation not permitted)
```

It reads exactly like a real build failure. It is an environment artifact.
Four dispatched agents hit it in one session and one spent its whole first
attempt diagnosing it (computenet-l0jf). The fix, any of:

```bash
./gradlew --stop        # then re-run
./gradlew --no-daemon <task>
```

or run the Gradle call with `dangerouslyDisableSandbox`. **This gets worse with
parallelism**, which this skill actively encourages: the more concurrent
worktrees, the likelier one sandboxed call poisons the shared daemon for
everyone. If a sibling agent's run starts failing this way and yours did not
change, this is why.

## Commands that fail QUIETLY in this shell and on this host

Each of these fails in a way indistinguishable from a clean negative result,
which is what makes them worth naming rather than leaving to be rediscovered.

- **`grep --include` must be QUOTED under zsh** — this repo's session shell.
  Bare `grep -rn --include=*.kt Foo .` dies with `zsh: no matches found:
  --include=*.kt` before grep ever runs, and piped, the pipeline's exit status
  is the last stage's, so it looks like a clean zero-hit search. Two agents hit
  it in one session; one was using it to establish that *nothing* declares
  `project(":bench")` — a no-dependents claim it then leaned on to justify
  skipping a repo-wide test run (computenet-l5rc). Write
  `--include='*.kt'`. This is the same family as AGENTS.md's zsh
  history-modifier trap.
  Same shell, loud variant: a word starting with `=` expands to a command
  path, so an unquoted `echo ===` separator dies (`== not found`) — quote it
  (`echo '==='`) or use `printf` (computenet-a49j).
- **`git stash` is a single REPO-WIDE stack, shared by every linked
  worktree.** Worktrees isolate HEAD, index and files — that isolation is this
  skill's whole concurrency model — and the stash is the exception. A session
  runs a dozen worktrees off one `.git`, so `git stash pop` pops whatever is on
  top, not necessarily what you pushed. Two implementers reached for
  stash/run/pop to get a before-and-after in one worktree and got away with it
  only because their uses did not overlap in time (computenet-89jr). Use a
  worktree-local before-and-after instead: commit first and compare against the
  parent commit, `git show <base>:<path> > "$SCRATCH/before.kt"`, or
  `git worktree add` a throwaway checkout of the base. If you must stash,
  follow AGENTS.md's tagged form (`git stash push -u -m "<unique-tag>"`,
  capture the sha, `apply` never `pop`).
- **A git pathspec ending at a DIRECTORY name matches nothing under it** — it
  matches a path ending there, i.e. a *file* named `main`. Measured:
  `git grep -ln 'FileJournal(' -- '*/src/main'` → 0 hits, exit 1;
  `-- '*/src/main/*'` → 3 files (computenet-fd9d). Every module here nests
  sources at `*/src/{main,test}/…`, so the failing shape is the natural one.
  The quoting is correct — this is the pathspec, not the shell. Before reading
  any zero-hit search as "absent", re-run it in a form whose failure would look
  different: a plain recursive grep, or the same query against a string you
  KNOW is present.
- **`git grep`'s regex engine has no Perl-style `\s`, `\d` or `\w`** — they
  degrade to the LITERAL character, so `\s` matches nothing and `\d` matches
  the letter `d`: false positives as readily as zeros. Measured:
  `git grep -hE '^\s*@Test' <rev> -- 'iroh/src/test/*.kt'` → 0;
  `'^[[:space:]]*@Test'` → 46. The habit transfers from every other search here
  — ripgrep, `grep -P`, the Grep tool all accept `\s` — and only `git grep`
  answers wrongly. `git grep -P` accepts it on a PCRE-enabled build (this
  machine's is); `[[:space:]]` needs no such build. **This one is a REVIEWER's
  trap above all**: it is reached while counting or diffing symbols ACROSS
  REVISIONS, the one job only `git grep` can do, and where a wrong zero
  directly weakens a verdict — a feature reviewer checking a test-only refactor
  had deleted no test method got 0 at both revisions (computenet-s7az).
- **`bd -C <path>` cannot live in a shell variable.** `BD="bd -C /path"; $BD
  show x` fails with `no such file or directory: bd -C /path` — zsh does not
  word-split an unquoted expansion, so the whole string is looked up as one
  command name. Type `bd -C "$WT" show x` each time, or define a function:
  `bd() { command bd -C "$WT" "$@"; }` (computenet-wahz).
- **A `/*` inside a Kotlin KDoc comments out the rest of the file.** Kotlin
  block comments NEST, so a path glob like `micro/*Benchmark.kt` written in a
  `/** … */` opens a second level that never closes. The errors land
  elsewhere — `Unresolved reference` at an unrelated line, `Unclosed comment`
  at EOF (computenet-dy7q). Write `micro/` + `Benchmark.kt` or escape it,
  and after any KDoc edit the opener and closer counts must match:
  `grep -o '/\*' f.kt | wc -l` vs `grep -o '\*/' f.kt | wc -l` (`-o`, not
  `-c`: `-c` counts lines and reads the motivating line as 1 = 1).
- **`strings` cannot be trusted on `.class` files on darwin.** Java's class
  magic `0xCAFEBABE` is *also* the Mach-O universal-binary magic, so Apple's
  `strings` reads the next words as `cputype`/`cpusubtype` and fails with
  `fat file: … truncated or malformed`. Whether it fails depends on those
  bytes, so it is **intermittent** — measured 2026-08-19 on this repo, it
  failed on **57 of 64** class files and quietly succeeded on the other 7,
  which is worse than failing always: one success invites the conclusion that
  the tool is fine. Read a dependency's string constants with `javap` instead:

  ```bash
  javap -v <class> | grep -oE '// String .*'
  ```

  Reading a library's own format constants out of its bytecode is the natural
  way to check a parser against the thing it parses — it is what turns "the
  prose says the banner looks like this" into evidence.

## Two pushes, and neither is yours

"Do not push" in a dispatch prompt reads as covering **git**, and there is a
second push it does not name. A task reviewer reasoned its way into
`bd dolt push` on its own initiative — a defensible instinct, given how much
this skill emphasises writing verdicts durably before stopping — and reported
*"metadata.review=passed set; … Dolt pushed"* (computenet-6uqb). So, in as
many words:

- **No `git push`** — not even your own task branch. The orchestrator's
  merge step owns that.
- **No `bd dolt push`** either. Your bead writes stay LOCAL and ride out on
  the orchestrator's next sync. This is not about permission: the
  orchestrator serializes pushes, concurrent pushes from parallel agents
  contend, and your writes are already carried by its next bracket — so a
  subagent push is exactly the redundant kind the sync policy exists to
  prevent (AGENTS.md, "Syncing bead state is required, not optional").

Write your verdict to the bead and stop. Durability is the local write; the
push is someone else's job.

## The job ledger

**You cannot enumerate your background jobs from memory** — `TaskStop` needs
an id you must already hold, and a poll shell you backgrounded 40 tool calls
ago is not something you will reliably recall (computenet-k9d.10). Write each
one down **as you start it**, and read the file back before you report:

```bash
echo "<Monitor|shell|loop> <id or pid> <what it waits for>" >> "$SCRATCH/jobs"
# at report time: cat "$SCRATCH/jobs", kill each line, then rm -f it
```

An empty or absent file is a positive answer — you started none. "I don't
think I started any" is not. **Kill every one before you send your final
message**: nothing stops a job once you are gone, and every later firing
delivers another task-notification carrying a stale copy of your report —
six such wakes in one session (computenet-k9d.8).

## Never end a turn waiting

**Your final message must state your outcome in as many words** — for a
reviewer, the literal verdict token your reference requires, plus a
NOT VERIFIED section naming everything you did not check; for an implementer,
done / blocked / premise-wrong plus the files you actually touched. Nothing
resumes you: the completion notification looks identical whether you finished
or stopped mid-task, and a result that never states an outcome can be read as
approval — one review returned "Waiting on Arm A. I will resume when it
completes." as its entire result after 108 tool calls.

Out of room, out of time, or blocked: give the partial outcome you have and
put the rest under NOT VERIFIED — an honest partial beats stopping
mid-experiment. **If you stop with a suite still running, say so ON THE
BEAD** — which suite, which log, what is committed (sha) — rather than
returning the wait as your result.
