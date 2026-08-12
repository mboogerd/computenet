# Beads Dolt sync cost — measurement and GO/NO-GO verdict

Task: computenet-o97.3.1. Feature: computenet-o97.3. Epic: computenet-o97 (TRK1).

Measures the real wall-clock cost of `bd dolt pull` / `bd dolt push` against
origin (`https://github.com/mboogerd/computenet.git`, embedded Dolt mode,
`refs/dolt/data`) from the shared checkout at
`/Users/merlijn/Documents/local-projects/computenet`, to settle whether TRK1's
premise (a ~10 minute round-trip that makes per-session sync too expensive)
is real.

## Decision rule (pre-registered, before any numbers below)

**GO** (the problem is real, TRK1 proceeds) **iff** the measured per-session
sync cost exceeds 2 minutes **OR** a sync operation fails outright requiring
manual intervention. **Otherwise NO-GO.**

This rule is taken from computenet-o97.3's acceptance criteria verbatim and is
not loosened here.

## Baseline: `.beads` size and issue count

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

**GO.** Both prongs of the pre-registered rule independently hold:

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

TRK1 proceeds. Of the epic's remaining open features, the numbers most
directly justify **computenet-o97.1** (shrink: prune/purge closed issues +
Dolt GC, before/after sizes) — push cost (the more expensive and more
variable of the two operations) is plausibly driven in part by `.beads` size
(65M `embeddeddolt` at measurement time), and shrinking is "independently
useful... attacks the cause regardless of topology" per the epic text, so it
is not blocked by anything this report found.

The epic's core mandate — "no `bd dolt pull/push` in the unattended path" —
is also directly justified by the >2-minute per-session cost measured here.
However, as currently scoped, **computenet-o97.5** ("cutover") is written
against the withdrawn shared-`dolt sql-server` design: its acceptance
criteria talk about transactional arbitration in a sql-server and it depends
on computenet-o97.4, which is closed as obsolete (Headscale/tailnet dropped).
The epic's own body (§3) describes the surviving version of this mandate
differently — moving `bd dolt pull/push` to "a nightly or manual job," no
server-mode dependency. This report's numbers justify pursuing that
mandate, but o97.5's text needs rescoping to match the epic's current
(non-server-mode) design before it is actionable as written; that rescoping
is outside this task's claim (`doc/ops/beads-sync-cost.md` only) and is
flagged here for the feature reviewer.

**computenet-o97.6** (untrack `issues.jsonl` from main) is not justified by
this report's timing numbers — untracking `issues.jsonl` affects `main`'s
history and repo size, not the `refs/dolt/data` round-trip these timings
measure, since `bd dolt push`/`pull` operate on `refs/dolt/data`, a ref
distinct from `main`. It rests on its own already-made human decision
("keeping tracker data off main is a requirement, not a preference" per its
description) and is unaffected by this measurement either way.
