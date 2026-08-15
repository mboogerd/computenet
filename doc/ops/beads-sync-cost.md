# Beads Dolt sync cost — measurement and GO/NO-GO verdict

Task: computenet-o97.3.1. Feature: computenet-o97.3. Epic: computenet-o97 (TRK1).

Measures the real wall-clock cost of `bd dolt pull` / `bd dolt push` against
origin (`https://github.com/mboogerd/computenet.git`, embedded Dolt mode,
`refs/dolt/data`) from the shared checkout at
`/Users/merlijn/Documents/local-projects/computenet`, to settle whether TRK1's
premise (a ~10 minute round-trip that makes per-session sync too expensive)
is real.

**Staleness notice (computenet-o97.7, 2026-08-14).** Every number below was
measured against the old `refs/dolt/data` ref on the GitHub remote
(`https://github.com/mboogerd/computenet.git`). PR #64 (merged 2026-08-12,
after this report's own measurements) moved `sync.remote` to a native Dolt
remote on DoltHub (`https://doltremoteapi.dolthub.com/mrboo/computenet`)
because the database had outgrown the `refs/dolt/data` path and its pushes
had started failing outright — so the numbers below no longer describe the
path in current use, and the specific GitHub-remote failure mode they
document (the `merge conflicts in issues require operator resolution` abort)
is a property of that old path, not necessarily of DoltHub.

This task took one fresh data point against the *current* DoltHub remote
rather than leaving the doc silently stale, sized to fit inside a single
measurement (not a full re-run of the six-call protocol below, which this
task's slot did not have room for, and which risked colliding with this
session's own orchestrator, also syncing against the same database
concurrently — see the instruction to prefer measuring pull and not loop):

```
$ cd /Users/merlijn/Documents/local-projects/computenet
$ time bd dolt pull
Pulling from Dolt remote...
Pull complete.
bd dolt pull  1.13s user 0.38s system 15% cpu 9.904 total
```

**One `bd dolt pull` against DoltHub: 9.90s wall time**, 2026-08-14, no
concurrent `bd dolt` operation observed to be running against the same
database at that moment. That is markedly faster than this report's own
pull numbers below (mean 33.56s, median 34.09s against the old GitHub
remote) — consistent with DoltHub being a purpose-built Dolt remote host
rather than a ref on a GitHub git remote, though a single sample cannot
establish a distribution and this is not a claim that push behaves the same
way. **The rest of this document — the raw timed runs, the per-session
model, and the GO verdict — is retained as history of the old path and
should not be read as a current measurement of the DoltHub remote.**

**That full re-measurement has since been done: see
[the 2026-08-15 section](#dolthub-remote--full-re-measurement-2026-08-15)
immediately below, which is the current cost of record.** Everything after
it (decision rule, baseline, the 2026-08-12 incident, raw timed runs,
per-session model, verdict) is the old `refs/dolt/data` path and is kept
only as history.

## DoltHub remote — full re-measurement 2026-08-15

Task: computenet-cyd. **This section is the cost of record for the current
transport.** Everything below it measures the retired `refs/dolt/data` path.

### How it was measured

Machine MacBoo, shared checkout `/Users/merlijn/Documents/local-projects/computenet`,
`sync.remote = https://doltremoteapi.dolthub.com/mrboo/computenet`, 2026-08-15
10:48–10:58 UTC. Every timing is `/usr/bin/time -p <cmd>`, `real` line, taken
in the foreground. Database at measurement time: `.beads` 303M
(`embeddeddolt` 200M, `backup` 96M, `issues.jsonl` 6.8M), **535 issues** —
roughly 3× the 95M / 251-issue database the old-transport numbers below were
taken against, so the two are not comparable like-for-like even before the
transport change.

**Real deltas, not no-ops, and both are reported separately.** A throwaway
probe bead `computenet-pxv9` was created solely to mint deltas, written to
repeatedly, and **closed** when done (never `bd delete`d — a local delete
pushes as a deletion of the other machine's issue, §3.3 of the runbook).
Deltas *from* the remote were minted by cloning the database into a scratch
directory with `bd bootstrap`, writing there, and pushing from the clone —
so the shared checkout genuinely had something to fetch. The scratch clone
was removed afterwards.

Every delta size is stated. A "1-commit delta" is one `bd` auto-commit
(one comment or one field update, i.e. one row touched); `bd` auto-commits
per mutation, so commit count is the natural unit.

### Raw readings

`bd dolt pull`:

| # | delta | wall |
|---|---|---|
| 1 | nothing to fetch | 3.87s |
| 2 | nothing to fetch | 3.29s |
| 3 | nothing to fetch | 3.11s |
| 4 | nothing to fetch | 3.50s |
| 5 | nothing to fetch | 2.89s |
| 6 | 1 commit behind | 7.50s |
| 7 | 1 commit behind | 6.54s |
| 8 | 1 commit behind | 7.16s |
| 9 | 1 commit behind | 6.96s |
| 10 | 7 commits behind | 8.50s |
| 11 | 7 commits behind, but `dolt fetch` already run out-of-band | 4.48s |
| 12 | two-sided: 8 local vs 89 remote commits, fetch already run out-of-band | 4.08s |

`bd dolt push`:

| # | delta | wall |
|---|---|---|
| 1 | nothing to send (first push of the session) | 13.66s |
| 2 | nothing to send | 6.06s |
| 3 | nothing to send | 5.56s |
| 4 | nothing to send | 5.98s |
| 5 | nothing to send | 5.96s |
| 6 | 1 commit (new issue row) | 12.88s |
| 7 | 1 commit (one comment) | 12.54s |
| 8 | 1 commit (one field update) | 12.50s |
| 9 | 2 commits (comment + update) | 12.57s |
| 10 | 1 commit, pushed from the scratch clone | 12.70s |
| 11 | 7 commits, pushed from the scratch clone | 13.16s |
| 12 | 9 commits including a merge commit | 13.59s |
| — | *rejected non-fast-forward* (failure, exit 1) | 14.20s, 6.49s |

Local `bd` writes, for the acquisition-bracket arithmetic below:
`bd create` 1.85s, `bd comment` 0.74s, `bd update --priority` 1.80s.

`bd bootstrap` (full clone of the database from DoltHub, 74,662 chunks):
**10.08s** — one reading. Worth knowing: recovering a machine from the
remote is a ten-second operation, not a restore project.

### Summary

| operation | n | median | mean | range |
|---|---|---|---|---|
| pull, **no-op** | 5 | 3.29s | 3.33s | 2.89–3.87s |
| pull, **1-commit delta** | 4 | 7.06s | 7.04s | 6.54–7.50s |
| pull, 7-commit delta | 1 | — | 8.50s | — |
| push, **no-op** (warm) | 4 | 5.97s | 5.89s | 5.56–6.06s |
| push, **1–2 commit delta** | 5 | 12.57s | 12.64s | 12.50–12.88s |
| push, 7–9 commit delta | 2 | — | 13.38s | 13.16–13.59s |

Four things this shows that a single number would not:

1. **A no-op is not free, and it is not the same operation.** A no-op push
   costs ~6s against ~12.6s carrying a delta; a no-op pull ~3.3s against
   ~7.0s. Roughly half the price of a real sync is handshake, which is why
   syncing per item costs nearly as much as syncing per session.
2. **Push is about twice pull**, at both delta sizes and at no-op.
3. **Size barely matters over this range.** Going from 1 to 7–9 commits
   moved push from 12.6s to 13.4s and pull from 7.0s to 8.5s — under a
   second per operation. The cost is per round-trip, not per row. This is
   measured only up to 9 commits (see limits).
4. **The first push of a session pays a cold-start penalty**: 13.66s for a
   no-op, against 5.56–6.06s for four warm no-ops taken minutes later,
   including two taken seconds after a delta push. That is the whole of the
   old report's "first push outlier" effect, now ~8s rather than ~45s.

### Re-derived per-session cost

The publication cadence (`beads-sync-runbook.md` §0/§0.1) is one
session-start pull plus one Finalize push. Both carry real deltas in
practice — the session-start pull picks up the other machine's work, and the
Finalize push is by definition publishing something:

    7.04 (delta pull) + 12.64 (delta push) = 19.7s ≈ 0.33 min

An **acquisition bracket** (pull → verify with `bd show` → local write →
push), which §0.1 requires per acquisition:

    7.04 (delta pull) + ~1.4 (bd show + one bd write) + 12.64 (delta push) ≈ 21s

on a pull that has something to fetch, or ≈17s when it does not. That
confirms — now against the DoltHub remote rather than by carry-over — the
~17s figure `claim-sync.md` cites, and it means a session with the four or
five acquisition brackets a normal `/work` run performs spends roughly
**1.5–2 minutes** on sync in total, publication push included.

**Consequence for the old GO verdict, stated plainly.** The pre-registered
rule below was "GO iff per-session sync cost exceeds 2 minutes". Applying it
to *these* numbers and the old report's own grounded call-site count of the
smallest realistic session (2 pulls, 8 pushes):

    2 × 7.04 + 8 × 12.64 = 14.1 + 101.1 = 115.2s ≈ 1.92 min

— which is **under** the threshold. The DoltHub transport would not have
returned GO on prong 1. That does not retract TRK1: the epic shipped, and
§0.1 has since re-founded the sync policy on *correctness* (bracket
acquisitions so a claim becomes a lock) rather than on cost. It is recorded
here because a reader who takes the verdict below as current would be
reasoning from a transport that no longer exists.

### What was NOT measured, and stays unmeasured

- **Push cost for a session-sized delta.** The largest delta measured is 9
  commits. A long unattended session can accumulate hundreds. Point 3 above
  suggests the curve is flat, but it is measured over one order of magnitude
  short of that and must not be extrapolated to it.
- **Conflict-resolution cost (§3.3 of the runbook).** No genuine two-sided
  *conflict* occurred during this window, so the timings of the operator
  sequence are unmeasured. The 8-vs-89-commit two-sided merge that did occur
  (reading 12 above) merged cleanly, in 4.08s of merge work on top of an
  already-completed fetch.
- **Cross-machine variance.** Every reading is from MacBoo on one network,
  inside a ten-minute window. Nothing here bounds a bad network day, and the
  second machine (`Anva@A0030`) was not measured at all.
- **The old-transport numbers below are not re-derived and not adjusted.**
  They stand as recorded, labelled as the retired path.

### Incidental finding: a concurrent machine, and what a rejection looks like

Mid-measurement (10:56 UTC) the other machine pushed 89 commits, and the
next push from this checkout was rejected:

```
$ bd dolt push
Pushing to Dolt remote...
Error: push to origin/main: Error 1105: To https://doltremoteapi.dolthub.com/mrboo/computenet
 ! [rejected]            main -> main (non-fast-forward)
error: failed to push some refs to '...'
hint: Updates were rejected because the tip of your current branch is behind
hint: its remote counterpart. Integrate the remote changes (e.g.
hint: 'dolt pull ...') before pushing again.
```

(exit 1; the two rejected attempts cost 14.20s and 6.49s.) `bd dolt pull`
then `bd dolt push` resolved it with no conflict and no operator step — the
recovery pair the `/work` skill's Finalize already prescribes. Two things
worth carrying:

- **A rejection is not cheap and not rare.** It costs about what a real push
  costs, and it happened inside a ten-minute window in which one other
  machine was active.
- **`bd dolt push`'s failure output is multi-line, and its success output is
  one line.** A caller that pipes it through `tail -4` sees only `/usr/bin/time`'s
  own output and reads a rejection as a pass. Check the exit code, or don't
  truncate. This cost two readings in this very measurement before it was
  noticed.

## Decision rule (pre-registered, before any numbers below)

**GO** (the problem is real, TRK1 proceeds) **iff** the measured per-session
sync cost exceeds 2 minutes **OR** a sync operation fails outright requiring
manual intervention. **Otherwise NO-GO.**

This rule is taken from computenet-o97.3's acceptance criteria verbatim and is
not loosened here.

## Baseline: `.beads` size and issue count

*(Old `refs/dolt/data` transport, 2026-08-12. Current baseline: 303M / 535
issues — see the 2026-08-15 section.)*

Measured 2026-08-12 12:42 UTC, in `/Users/merlijn/Documents/local-projects/computenet`:

| Item | Size |
|---|---|
| `.beads` (total) | 95M |
| `.beads/embeddeddolt` | 65M |
| `.beads/backup` | 26M |
| `.beads/issues.jsonl` | 4.0M |

Issue count: 251 (`bd list --all --json | jq length`).

(Prior measurement cited in the epic/feature description, 2026-08-12 earlier:
82M total / 55M embeddeddolt / 24M backup / 3.6M jsonl / 202 issues. The DB
grew ~13M and 49 issues in the interim — consistent with ongoing unattended
work sessions plus the computenet-3v8 conflict-merge commit.)

## The 2026-08-12 ~12:20 UTC hard failure (reproducible failure mode)

Source: closing comment of computenet-3v8 (`bd comments computenet-3v8`),
transcribed verbatim below (commentary trimmed, commands intact).

`bd dolt pull` aborted with `merge conflicts in issues require operator
resolution`. `bd` has no conflict-resolution subcommand; the fix went through
the `dolt` CLI directly against the embedded DB's git-backed repo at
`.beads/embeddeddolt/computenet`. Eleven issues conflicted (all
`our_diff_type=modified` / `their_diff_type=modified`); resolution was
last-write-wins by `updated_at`. Exact command sequence used:

> This is the 2026-08-12 transcript, kept verbatim. **Do not run it as the
> procedure** — `doc/ops/beads-sync-runbook.md` §3.3 is the live recipe, and it
> carries a REQUIRED id-collision pre-step this transcript predates.

```bash
cd .beads/embeddeddolt/computenet
dolt config --local --add user.name 'Merlijn Boogerd'      # merge needs an identity
dolt config --local --add user.email 'mlboogerd@gmail.com'
dolt fetch
dolt sql -q "set @@dolt_allow_commit_conflicts=1; call dolt_merge('--no-commit','origin/main'); select * from dolt_conflicts;"

# -> issues: 11 conflicts, all our_diff_type=modified / their_diff_type=modified

# resolve last-write-wins by updated_at: take theirs where their_updated_at > our_updated_at,
# keep ours (the working-set value) otherwise, then clear the conflict table

dolt sql <<'SQL'
set @@dolt_allow_commit_conflicts = 1;
UPDATE issues i JOIN dolt_conflicts_issues c ON i.id = c.our_id
SET : i.col = c.their_col
WHERE c.their_updated_at > c.our_updated_at;
DELETE FROM dolt_conflicts_issues;
SQL
dolt commit -am 'Merge origin/main: resolve 11 issue conflicts last-write-wins by updated_at'
```

(The full `SET` clause used in practice was generated from
`information_schema.columns` — 55 columns — rather than typed by hand; the
placeholder `SET : i.col = c.their_col` above is the shorthand recorded in the
source comment.)

Conflicting ids: computenet-8sw, -9xj, -aer, -dqy.10, -dqy.16, -dqy.19,
-dqy.20, -dqy.21, -dqy.22, -dqy.23, -dqy.24. Seven resolved to ours, four to
theirs. Eight of the eleven were closed on both sides and differed only in
timestamp/metadata (near-no-op). The one real divergence was computenet-aer
(ours closed 12:17:46 vs theirs open 08:21:28 — ours four hours later and a
close, so closed won).

After resolution: `bd dolt pull` → `Pull complete.`, `bd dolt push` → `Push
complete.` — cross-machine sync was restored.

**Note recorded alongside that fix** (directly relevant to this report): the
verification `bd dolt push` after the fix **took over 120 seconds** — it blew
the default Bash 120s timeout and had to finish in the background. That is a
first, informal data point toward this report's own measurement below, and it
means this failure class recurs: nothing in the fix above prevents the next
concurrent-edit conflict from requiring the same manual intervention.

## Raw timed runs

*(Old `refs/dolt/data` transport. Superseded for current cost by the
2026-08-15 section above; retained as history, not adjusted.)*

Ran `time bd dolt pull` ×3, then `time bd dolt push` ×3, strictly serially,
from `/Users/merlijn/Documents/local-projects/computenet`, no other `bd dolt`
operation concurrent, 2026-08-12 ~12:43–12:47 UTC. All six runs succeeded
(`Pull complete.` / `Push complete.`) — no failure occurred during this
measurement window.

| # | Command | Result | Wall time |
|---|---|---|---|
| 1 | `bd dolt pull` | success | 32.06s |
| 2 | `bd dolt pull` | success | 34.09s |
| 3 | `bd dolt pull` | success | 34.54s |
| 4 | `bd dolt push` | success | 79.09s |
| 5 | `bd dolt push` | success | 33.84s |
| 6 | `bd dolt push` | success | 33.64s |

Pull: mean 33.56s, median 34.09s (range 32.06–34.54s, tight).

Push: mean 48.86s, median 33.84s (range 33.64–79.09s). Run #4 (the first
push of this session) is a clear outlier at 79.09s — consistent with the
"push took over 120s" note from computenet-3v8's fix, i.e. push cost is not
stable and has been observed to blow a 120s timeout on this same machine.
Runs #5–#6, with no first-push effect to absorb, land at ~34s, close to pull
cost.

## Per-session sync cost

*(Old `refs/dolt/data` transport. The DoltHub re-derivation is in the
2026-08-15 section above.)*

**Assumption, grounded in `.claude/skills/work/SKILL.md` and
`references/claim-sync.md`, not guessed.** The task description that spawned
this report suggested "e.g. 3" claims/closes as an illustrative number; that
figure is the task's own placeholder, not a count taken from the skill, and
it undercounts what the skill actually prescribes. Walking the skill's own
steps for the *smallest* unit of real work — one feature carrying two
tasks, an epic already claimed in a prior session (no epic-claim push) —
the `bd dolt pull`/`push` call sites are:

| Site | SKILL.md ref | Op | Count for 1 feature / 2 tasks |
|---|---|---|---|
| Session start | step 3, L102 | pull | 1 |
| Feature setup (new `metadata.branch`) | step 5a, L250 | push | 1 |
| Task batch claim | step 5b, L332/L337 | pull + push | 1 pull + 1 push (one batch covers both tasks) |
| Per-task worker finish (task subagent, still `in_progress`) | `references/task.md` step 7, L61 | push | 2 (one per dispatched task — a distinct push from the merge-time one below, run by the task subagent itself before the orchestrator ever merges it) |
| Task merge/close, "one at a time" (orchestrator) | step 5d, L419 | push | 2 (one per task closed, after merging into the feature branch) |
| PR creation (first time only) | step 5c, L463 | push | 1 |
| Finalize, per worktree touched | step 6, L591 | push | 1 |

This count is a **floor**, not a ceiling: it deliberately omits four further
push sites that a fuller session hits — the epic claim (`SKILL.md` L139, when
the session takes a new epic rather than resuming one), the epic- and
feature-decomposition subagents' finish pushes (`references/epic.md` L67,
`references/feature.md` L93), the feature review's own push
(`references/review-feature.md` L73, once per feature reviewed), and the
friction-chore push (`SKILL.md` L650). Counting those would raise the total,
never lower it, so the arithmetic below errs toward NO-GO.

Totals: **2 pulls, 8 pushes** for the smallest realistic session (one
feature, two tasks, epic already claimed) — each task pushes twice: once
from the task subagent finishing implementation (`task.md`), once more from
the orchestrator merging and closing it (`SKILL.md` step 5d). A session that
lands the skill's own stated bar — "three of five features" (SKILL.md L21)
— multiplies this by roughly the feature count, not by a flat "3" per
session; the claim/close-only framing in the task description undercounts by
ignoring the epic-claim, feature-setup, PR-creation, and finalize push
sites, none of which are claims or closes, and by treating each task as one
push instead of two.

Using means (33.56s pull, 48.86s push):

    2 × 33.56 + 8 × 48.86 = 67.12 + 390.88 = 458.00s ≈ 7.63 min

Using medians (34.09s pull, 33.84s push — i.e. excluding the run #4 outlier):

    2 × 34.09 + 8 × 33.84 = 68.18 + 270.72 = 338.90s ≈ 5.65 min

For comparison, the task description's own illustrative "1 pull + 3 push"
shape (kept here only to show it is not load-bearing for the verdict):

    33.56 + 3 × 48.86 = 180.13s ≈ 3.00 min (mean push)
    34.09 + 3 × 33.84 = 135.60s ≈ 2.26 min (median push, outlier excluded)

Every one of these estimates — the grounded minimal-session figure and the
task description's placeholder figure alike — exceeds the 2-minute
threshold. The grounded count is markedly higher (≈5.7–7.6 min vs.
≈2.3–3.0 min), so correcting the undercount does not change which side of
the line the numbers fall on; it only strengthens the case.

## Verdict

*(Reached on the old `refs/dolt/data` transport. The 2026-08-15 section above
records that prong 1 would not hold on the DoltHub numbers, and why that does
not retract the epic.)*

**GO.** Prong 1 of the pre-registered rule holds on its own; prong 2 also
reads as satisfied, with the caveat recorded below:

- Measured per-session sync cost is ≈2.3–7.6 minutes depending on which
  push-count model is used (≈2.3–3.0 min on the task description's
  illustrative "3 pushes" placeholder, ≈5.7–7.6 min on the grounded count of
  actual `SKILL.md`/`task.md` push sites for the smallest realistic session)
  — over the 2-minute threshold under every model, using this session's own
  timings (no reliance on the historical "~10 minutes" figure from
  computenet-4bv, which remains unverified and is not needed to reach GO).
- A sync operation has already failed outright requiring manual intervention:
  the 2026-08-12 ~12:20 UTC `bd dolt pull` hard failure above, which needed
  direct `dolt` CLI conflict resolution outside `bd`'s own command surface.
  That failure class is not fixed by anything measured or changed in this
  report — it can recur on the next concurrent edit.

**Which prong the verdict actually rests on.** Prong 1 (timing) carries this
verdict on its own. Prong 2 is weaker than it looks and should not be leaned
on: computenet-3v8's closing comment records that the 12:20 UTC conflict was
resolved *"by the midday unattended run (work skill step 3), without a
human"* — so a `bd`-external `dolt` CLI sequence was required, but no human
operator was in fact blocked on it, and whether that counts as "manual
intervention" is arguable. It is also worth stating plainly that prong 2 was
already known to have occurred before this measurement was designed, so the
pre-registered rule could only ever return GO. A rule with a prong that is
satisfied before the first measurement is not a measurement gate; the
measurement gate here is prong 1 alone.

Prong 1's margin is honest but not enormous at the narrow end: the task
description's illustrative "1 pull + 3 pushes" model on median timings gives
2.26 min, clearing the 2-minute threshold by only ~16%, and a faster network
day could put that single model under the line. The grounded call-site count
(≈5.7–7.6 min, itself a floor per the section above) is what makes prong 1
robust, and it is the model this verdict uses.

TRK1 proceeds. Of the epic's remaining open features, the numbers most
directly justify **computenet-o97.1** (shrink: prune/purge closed issues +
Dolt GC, before/after sizes) — push cost (the more expensive and more
variable of the two operations) is plausibly driven in part by `.beads` size
(65M `embeddeddolt` at measurement time), and shrinking is "independently
useful... attacks the cause regardless of topology" per the epic text, so it
is not blocked by anything this report found.

The epic's core mandate — "no `bd dolt pull/push` in the unattended path" —
is also directly justified by the >2-minute per-session cost measured here.
**computenet-o97.5** carries that mandate and the numbers here justify it.
Its acceptance criteria (as rewritten 2026-08-12 13:50 UTC, after this
report's measurements were taken) target the surviving non-server-mode
design: a nightly/manual sync job, and the per-session call sites in
`.claude/skills/work/` cut to exactly two — one pull at session start, one
push at Finalize. On this report's numbers that reduced shape costs
33.56 + 48.86 = 82.4s ≈ **1.37 min** on means, or 67.9s ≈ **1.13 min** on
medians — under the 2-minute threshold, which is what o97.5's third
criterion requires. So the cutover it describes is not merely justified by
these numbers; it is arithmetically sufficient to clear the bar this report
pre-registered.

One stale edge remains on o97.5 and is **not** this report's to fix: it
still carries a `blocks` dependency on computenet-o97.4, which is closed as
obsolete (Headscale/tailnet dropped). That dependency predates o97.5's
rescoping and should be dropped by whoever schedules the epic; flagged here
for the feature reviewer and the orchestrator.

**computenet-o97.6** (untrack `issues.jsonl` from main) is not justified by
this report's timing numbers — untracking `issues.jsonl` affects `main`'s
history and repo size, not the `refs/dolt/data` round-trip these timings
measure, since `bd dolt push`/`pull` operate on `refs/dolt/data`, a ref
distinct from `main`. It rests on its own already-made human decision
("keeping tracker data off main is a requirement, not a preference" per its
description) and is unaffected by this measurement either way.

## Addendum, 2026-08-15 — DoltHub remote, four fresh readings

*(Superseded by the fuller
[2026-08-15 re-measurement](#dolthub-remote--full-re-measurement-2026-08-15)
near the top, taken later the same day with stated delta sizes and repeated
samples. Kept because it is consistent with it — its no-op push readings of
13.60s/6.14s reproduce the cold-start-then-~6s pattern exactly — and because
it is the source `claim-sync.md`'s ~17s bracket figure came from.)*

Taken from the shared checkout on MacBoo while deciding whether a child
claim could afford its own sync bracket (computenet-k9d.3). Not a re-run of
the six-call protocol above — four `/usr/bin/time -p` readings against the
*current* DoltHub remote, which is the path the staleness notice says the
body of this document no longer describes.

| operation | wall clock |
|---|---|
| `bd dolt pull`, nothing to fetch | 2.96s, 2.78s, 3.12s |
| `bd dolt push`, nothing to send | 13.60s (first), 6.14s (second) |
| `bd update <id> --set-metadata` (local write) | 1.45s |
| `bd dolt push` carrying that one write | 13.63s |

Two things worth keeping. A **full acquisition bracket costs ~17s** (pull +
verify + local write + push), against the ~30s figure `claim-sync.md` cited
as its rationale — corrected there. And a **no-op push is not free**: the
first one cost 13.6s and a second, seconds later, 6.1s, so the price is in
the handshake rather than the payload. Anything that pushes per item pays
roughly the same whether or not it has something to say, which is the
argument for keeping syncs proportional to the depth of the tree rather than
its breadth.
