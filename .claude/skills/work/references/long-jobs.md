# Long jobs the orchestrator starts itself

Read this before starting any long-running background Bash job of your own.
A dispatched agent's completion notification always arrives; a background
job that **hangs** produces no notification at all, so absence of news is
not progress — one such job silently consumed half a session
(computenet-6v1). Never start one bare.

## The hard timeout lives in your wrapper

Give the job a bound it cannot outlive. There is no `timeout(1)` on macOS,
so the bound goes in the wrapper:

```bash
perl -e 'alarm shift @ARGV; exec @ARGV' 3600 <cmd>    # exits 142 at the deadline (verified)
```

**A hung child does not fail, it waits.** A JVM after `OutOfMemoryError`, a
container whose main thread died, a Gradle daemon that lost its worker — all
keep their process alive with no exit status ever arriving. The timeout has
to live in *your* wrapper; trusting the child to exit is what turns a dead
job into a lost session.

## The stall watch

When the job is long enough that you would rather hear about a stall than
wait out the alarm: ledger the job first (step 2's `$SCRATCH/jobs`), then
arm the watch as a **Monitor** — each of its stdout lines becomes a
notification, whereas a backgrounded loop's `echo`s reach you only when the
loop finally exits, too late to be a warning.

```
Monitor({description: "stall watch on <job>", persistent: false, timeout_ms: 1900000,
  command: `
L=<spell the log path out>   # the monitor's shell does not inherit your $SCRATCH
sz() { [ -f "$1" ] && wc -c < "$1" | tr -d ' ' || echo 0; }   # BSD wc pads with spaces
prev=$(sz "$L")
for i in $(seq 1 30); do
  sleep 60
  now=$(sz "$L")
  [ "$now" = "$prev" ] && echo "round $i: STALLED — nothing written in 60s ($now bytes)"
  prev=$now
done
echo "stall watch ended after 30m — job still running, or you missed its exit"`
})
```

Two details are load-bearing. `wc -c < f` on darwin prints the count
**padded with leading spaces**, so comparing against a bare `0` says "alive"
for a file that sat empty the whole minute — precisely the stall; `tr -d
' '` normalizes both sides. And `sz` returns `0` for a *missing* file too,
because a job that dies before creating its log is the same silence.

While this watch is alive it is **the** bounded watch — finish it or
`TaskStop` it before arming a PR-checks loop (step 2's one-bounded-watch
rule).
