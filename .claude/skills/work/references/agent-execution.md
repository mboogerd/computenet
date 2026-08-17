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

```bash
i=0
until grep -qE 'BUILD (SUCCESSFUL|FAILED)' "$SCRATCH/run.log" 2>/dev/null; do
  i=$((i + 1)); [ "$i" -gt 60 ] && { echo "GAVE UP at 20m"; break; }
  sleep 20
done
tail -5 "$SCRATCH/run.log"
```

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
