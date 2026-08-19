# Execution discipline for dispatched agents

The rules every dispatched agent — implementer or reviewer — runs under.
task.md, review-task.md and review-feature.md all point here; this file is
the single copy. Nothing here is advisory: each rule exists because its
absence cost a session (the cited bead holds the story — `bd show <id>`).

## Contents

- [Your scratch dir is yours alone](#your-scratch-dir-is-yours-alone)
- [Foreground timeouts, and why there is no `timeout` binary](#foreground-timeouts-and-why-there-is-no-timeout-binary)
- [Commit before you wait on evidence](#commit-before-you-wait-on-evidence)
- [The bounded until-loop, and refusals](#the-bounded-until-loop-and-refusals)
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
call with an explicit `timeout` argument, up to 600000 ms.** The
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

**On refusals.** A *bare* long `sleep` is refused by the auto-mode
classifier (`sleep 240 && echo done` → "use Monitor with an until-loop…",
measured 2026-08-17), which is where the until form comes from. A
`for i in $(seq 1 N)` waiter was refused once (computenet-ng9o) but is not
reliably refused — treat that refusal as contextual. If one form is refused,
switch to the other rather than reaching for a bare sleep.

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
