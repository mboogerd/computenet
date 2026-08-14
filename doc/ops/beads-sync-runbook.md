# Beads Dolt sync runbook — nightly/manual job, residual risk, recovery

Task: computenet-o97.5.2. Feature: computenet-o97.5. Epic: computenet-o97 (TRK1).
Updated by computenet-o97.7 (2026-08-14) to correct the remote topology below.

This runbook is the doc/ops counterpart to the skill-side change in
computenet-o97.5.1 (`.claude/skills/work/**`): per-session `bd dolt pull`/`push`
calls are cut down to two sites (session-start pull, Finalize push); durability
against a machine lost mid-session moves to this nightly/manual job plus the
periodic `.beads/backup` snapshots.

**Durability rests on a native Dolt remote hosted on DoltHub** — `sync.remote`
in `.beads/config.yaml`, currently
`https://doltremoteapi.dolthub.com/mrboo/computenet`. This is a change from
this runbook's original text (PR #63, computenet-o97.5.2): sync used to run
over `refs/dolt/data` on the GitHub remote, piggybacking the same repo. PR #64
("Sync beads over DoltHub and drop cross-session epic stickiness", merged
2026-08-12) moved it to DoltHub because the database outgrew the `refs/dolt/data`
path on GitHub and its pushes started failing outright. `.beads/config.yaml`
and `scripts/beads-nightly-sync.sh` were updated in that PR; this runbook was
not, until now. Everywhere below that used to say "the git remote" or
`refs/dolt/data` now means the DoltHub remote instead — the mechanism (two
per-session calls, a nightly/manual catch-up job, `.beads/backup` for local
recovery) is otherwise unchanged, only how often the round-trip runs outside a
live session.

## Per-machine setup: DoltHub credentials (read before §0)

The DoltHub remote requires a registered credential per machine; a machine
without one fails every `bd dolt push` it attempts. `bd dolt pull` is
**not** blocked the same way — see the observation below. One-time setup on
each machine that runs `bd dolt` commands against this repo — pull, push, or
the nightly script:

```bash
dolt creds new                     # generates a keypair, prints a public key
```

Then register the printed public key at
<https://www.dolthub.com/settings/credentials> (a human step in the DoltHub
web UI — no `bd`/`dolt` CLI does this part). Until both steps are done on a
given machine, its `bd dolt pull`/`bd dolt push` calls run against an
unauthorized credential.

**What an unauthorized machine looks like when it fails (verified 2026-08-14,
computenet-o97.8).** Captured from an isolated environment, never against the
live shared database directly: `DOLT_ROOT_PATH` was pointed at a throwaway
scratch directory so `dolt creds new` generated and activated a fresh keypair
that was never registered on DoltHub, keeping the real credential under the
default `~/.dolt` completely untouched. Reads against `sync.remote`
(`https://doltremoteapi.dolthub.com/mrboo/computenet`) turned out to be
unauthenticated regardless of credential, so `bd bootstrap` under that
isolated root could clone the real database read-only without ever exercising
the credential check — that clone became the throwaway local database used
below (a fresh `bd init` database has unrelated history and fails pull/push
with an unrelated "no common ancestor" error that has nothing to do with
authorization, which is why a real clone was needed instead). With that
throwaway clone as the working directory (still under the isolated
`DOLT_ROOT_PATH`, still the unregistered key):

`bd dolt pull` against `sync.remote` **succeeds** with the unregistered
credential:

```
$ bd dolt pull
Pulling from Dolt remote...
Pull complete.
```

(exit 0). So the credential gate on this remote applies only to writes; an
unauthorized machine can silently pull current state and would only discover
it lacks access when it tries to push.

`bd dolt push` (after a local-only throwaway commit made in that same
isolated clone) fails with:

```
$ bd dolt push
Pushing to Dolt remote...
Error: push to origin/main: Error 1105: unknown push error; rpc error: code = PermissionDenied desc = permission denied
```

(exit 1). The underlying `dolt push` call (bypassing `bd`'s wrapper) surfaces
the same transport detail, without `bd`'s `push to origin/main:` prefix:
`unknown push error; rpc error: code = PermissionDenied desc = permission
denied`. That `PermissionDenied` / `permission denied` text is the string to
grep for in a failing sync log. This matches `.beads/config.yaml`'s own
comment block, which says the push fails "exactly where it hurts (unattended
scheduled runs)" when the credential is missing.

## 0. Why two per-session calls survive (a deliberate deviation)

TRK1's "what done means" says **"No `bd dolt pull`/`push` in the unattended
path."** Read literally that is zero calls per session. What shipped is two:
one pull at `SKILL.md` step 3 and one push at Finalize. The deviation is
deliberate, and the reasoning belongs here rather than only on the bead:

1. **The cost goal is already met at two.** The epic's premise was a ~10
   minute round-trip; `doc/ops/beads-sync-cost.md` measured ~34s per call and
   showed the pain was *count*, not duration — 10 calls in a minimal session.
   Two calls cost ~1.1–1.4 min (§2), under the 2-minute threshold that doc
   pre-registered. Cutting the last two buys ~68s.
2. **What those 68s would cost.** The session-start pull is the *only*
   mechanism by which a machine sees the other machine's claims. Remove it and
   two machines can take the same epic with nothing anywhere noticing until a
   human looks — the same silent-divergence class that cost a full run's state
   in computenet-kg7 and computenet-3v8. The Finalize push is the only thing
   that makes this session's work visible at all; without it the tracker is
   permanently machine-local and the "shared" tracker stops being shared.
3. **Asymmetry of error.** Keeping the two and later dropping them is a
   two-line edit. Dropping them and later discovering a double-claim costs a
   run plus manual tracker repair.

So the frequency target is met and the safety property is kept. The residual
that this choice accepts — the one-session double-claim window — is §4, and
what claim safety now does and does not guarantee is
`.claude/skills/work/references/claim-sync.md`.

If you actually want zero per-session sync — i.e. you accept that two machines
can double-claim between nightly runs, or you intend claims to stop being
cross-machine at all — that is a human decision; say so on `computenet-o97.5`
(where the same reasoning was recorded as an assumption on 2026-08-12) and the
two remaining sites come out.

### 0.1 Amendment 2026-08-13: exactly-two retired as an invariant (computenet-wpvy.3)

Decided by the user 2026-08-13. The exactly-two count above was shaped by the
~10-minute-round-trip era; with the measured ~30s round-trip the constraint
moved from cost to correctness, and the policy is now the principle **sync
brackets acquisition, not writes; ownership makes writes free**:

- **Owned territory** (items under an epic the session claimed, items it
  claimed): writes stay local; the Finalize push is *publication*. The two
  boundary calls of §0 survive as this publication cadence — a floor, no
  longer a ceiling.
- **Acquisitions and shared-surface writes** (claiming an epic, claiming an
  item in another epic, filing/upvoting under the SDLC epic
  `computenet-wpvy`, stealing a stale claim): each is bracketed
  pull → verify → write → push at the moment it happens. The push turns the
  claim from a record into a lock; the double-claim window of §4 shrinks
  from a session to seconds.

Accepted residuals: two machines racing the same acquisition inside the
seconds-wide window, and agents legitimately writing *into* a claimed epic
(e.g. filing a story that thematically belongs there) — ownership is a
working convention, not a fence. Skill-side statement of the same policy:
`.claude/skills/work/references/claim-sync.md`.

## 1. The job: invocation path

- **Script**: `scripts/beads-nightly-sync.sh` (repo root). Runs `bd dolt pull`
  then `bd dolt push` against the DoltHub remote (`sync.remote` in
  `.beads/config.yaml`), in that order, from the repo root it resolves
  relative to its own location.
- **Trigger**: left as a **manual runbook step** by this task — no
  launchd/cron entry is installed by this change. To install one, a human
  runs the exact commands in [§8](#8-installing-a-schedule-machine-side-step)
  on each machine that should run it unattended. Until installed, run the
  script by hand:
  ```bash
  scripts/beads-nightly-sync.sh
  ```
- **On failure**: the script never exits 0 on a failed sync (see script
  comments and exit codes below). It prints which command failed
  (`bd dolt pull` or `bd dolt push`) to stderr and exits nonzero:
  - `1` — `bd dolt pull` failed. If the failure text is the merge-conflict
    abort (`merge conflicts in issues require operator resolution`), the
    script does **not** retry and instead points at
    [§3.3](#33-conflict-resolution-sequence-both-sides-diverged) of this
    runbook rather than swallowing the failure.
  - `2` — `bd dolt push` failed (pull succeeded).
  - `3` — precondition failed: `bd` not on `PATH`, or no `.beads/` directory
    found under the resolved repo root.
  - There is no code path that logs success while either command actually
    failed — every failure branch calls `exit` with a nonzero code before
    returning.

## 2. Recomputed per-session cost

Source: `doc/ops/beads-sync-cost.md` (computenet-o97.3, PR #62) — measured,
not re-measured here per this task's instructions. Its raw per-call numbers:

| Op | Mean | Median |
|---|---|---|
| `bd dolt pull` | 33.56s | 34.09s |
| `bd dolt push` | 48.86s | 33.84s |

**These numbers predate PR #64's move to the DoltHub remote (§ above) and are
retained as history, not a current measurement** — see the staleness notice
at the top of `doc/ops/beads-sync-cost.md`. A single 2026-08-14 sample against
the current DoltHub remote (also recorded there) put one `bd dolt pull` at
9.90s, well under this table's pull figures; that single sample is not a
substitute for a full re-run of this section's model and none is claimed
here. The per-session recomputation below is retained as-is because it is
the number `.claude/skills/work/SKILL.md`'s own design (computenet-o97.5.1)
was justified against, not because it is asserted current:

With the skill cut down (computenet-o97.5.1) to exactly one pull (session
start) and one push (Finalize) per session, the per-session cost recomputes
as:

- **Means**: 33.56 + 48.86 = 82.4s ≈ **1.37 min**
- **Medians** (excluding the recorded first-push outlier): 34.09 + 33.84 =
  67.9s ≈ **1.13 min**

Both figures are under the 2-minute threshold that `beads-sync-cost.md`
pre-registered before any measurement was taken. This job (§1) does not add
to that per-session figure — it runs outside a live session, on its own
schedule.

## 3. Residual-loss window

### 3.1 What is lost, and how much

With per-session sync reduced to one pull and one push, a machine that is
lost (crashes, loses power, network partition that never recovers) **between**
its own Finalize push and the next nightly/manual run of this job loses every
tracker mutation it made since its last successful `bd dolt push` — claims,
comments, closes, new issues, everything written locally and not yet pushed.

The window is bounded by:

- **Up to one full session's worth of mutations** — everything a session did
  between its start-of-session pull and whatever point it was interrupted
  before Finalize's push ever ran, PLUS
- **The gap until the next nightly/manual sync runs** — if the job is on a
  nightly schedule and the machine is lost right after that night's run, the
  window extends to nearly 24h of any manual work also done on that machine
  outside the skill's own session boundaries. **With no schedule installed
  (the state today — §5), there is no upper bound on this second term at
  all**: the only pushes that happen are the ones sessions perform at
  Finalize and the ones a human runs by hand. Installing §8's schedule is
  what turns this term into ~24h.

Because durability still rests on the DoltHub remote, nothing on the OTHER
machine or on the remote is at risk — only the lost machine's own unpushed
local state.

### 3.2 What a human does about it

1. Confirm the machine is actually gone (not just slow) before treating any
   state as lost — check whether it can still be reached to run
   `bd dolt push` itself, which is strictly better than any recovery path
   below.
2. If it cannot: the lost machine's local Dolt database may still be
   reachable via its own `.beads/backup` (periodic Dolt-native backup —
   confirmed still active, see [§5](#5-backup-freshness-finding)). Recover
   the LOCAL state from that backup on a scratch copy first (see
   [§6](#6-recovery-proof) for the exact commands), inspect what it holds,
   and decide case by case whether any of it is worth manually replaying
   (e.g. `bd comment`, `bd update`) against the live tracker once the
   machine or a fresh clone is back online.
3. If the lost machine's unpushed mutations are unrecoverable (disk gone,
   backup also lost), the loss is bounded to the window in §3.1 — accept it,
   per the feature's recorded design decision, and re-derive any lost work
   from source (the beads item's own history on other machines, PRs, or
   human memory) rather than treating it as unrecoverable data loss requiring
   a wider investigation.

### 3.3 Conflict-resolution sequence (both sides diverged)

If reconciling a recovered/lost machine's state against the DoltHub remote
surfaces a genuine two-sided conflict (both the remote and the local copy
mutated the same issue since they last agreed), `bd dolt pull` aborts with:

```
merge conflicts in issues require operator resolution
```

`bd` has no conflict-resolution subcommand for this. The operator sequence
that resolved this exact failure on 2026-08-12 (transcribed verbatim from
`doc/ops/beads-sync-cost.md`, itself transcribed from `bd comments
computenet-3v8`) is:

```bash
cd .beads/embeddeddolt/computenet
dolt config --local --add user.name 'Merlijn Boogerd'      # merge needs an identity
dolt config --local --add user.email 'mlboogerd@gmail.com'
dolt fetch
dolt sql -q "set @@dolt_allow_commit_conflicts=1; call dolt_merge('--no-commit','origin/main'); select * from dolt_conflicts;"

# -> issues: N conflicts, all our_diff_type=modified / their_diff_type=modified

# resolve last-write-wins by updated_at: take theirs where their_updated_at
# > our_updated_at, keep ours otherwise, then clear the conflict table
dolt sql <<'SQL'
set @@dolt_allow_commit_conflicts = 1;
UPDATE issues i JOIN dolt_conflicts_issues c ON i.id = c.our_id
SET <generated from information_schema.columns>
WHERE c.their_updated_at > c.our_updated_at;
DELETE FROM dolt_conflicts_issues;
SQL
dolt commit -am 'Merge origin/main: resolve N issue conflicts last-write-wins by updated_at'
```

The `SET` clause is generated per-database from `information_schema.columns`
(55 columns at the time of the 2026-08-12 incident, 53 non-key columns as of
2026-08-14) rather than typed by hand.

> **Do not generate it with SQL `group_concat()`.** Measured 2026-08-14: it
> silently truncates at `group_concat_max_len` (1024 bytes by default),
> yielding a clause that assigned **25 of 53 columns with no error anywhere** —
> a resolution that restores under half of every conflicting row and reports
> success. Either raise `group_concat_max_len` first and assert the column
> count, or emit one row per column and join them outside SQL. Whichever you
> pick, count the assignments against
> `select count(*) from information_schema.columns ... and column_name<>'id'`
> before running the `UPDATE`.
After resolution, verify with `bd dolt pull` (expect `Pull complete.`) then
`bd dolt push` (expect `Push complete.`). Note: a `bd dolt push` following a
conflict resolution has been observed to take **over 120 seconds** (blowing a
default 120s shell timeout) — run it able to finish in the background if
scripting this.

## 4. Two-machine double-claim collision

### 4.1 How it happens

With only a session-start pull and a Finalize push (computenet-o97.5.1), two
machines that both start a session inside the same pull-to-push window can
both see the same item as unclaimed, both claim it, and neither finds out
until whichever machine pushes second overwrites — or conflicts with — the
first machine's claim. This is the explicit tradeoff stated in [§0](#0-why-two-per-session-calls-survive-a-deliberate-deviation):
keeping exactly one pull/push per session bounds the window to a single
session's length rather than eliminating it. (Zero per-session sync would not
bound it at all.)

### 4.2 How it is discovered

- **At push time**: if both machines' local mutations of the same issue
  conflict at the Dolt row level, the second machine's `bd dolt push` (or the
  next nightly job's `bd dolt pull`) surfaces it as the merge-conflict abort
  in [§3.3](#33-conflict-resolution-sequence-both-sides-diverged).
- **Without a row-level conflict** (e.g. both machines wrote to different
  columns, or one merely claimed while the other closed): the push may
  succeed silently. The tell is two machines' work products (PRs, branches,
  worktrees) both addressing the same beads id — a human or the next
  session's `bd show <id>` / `bd ready` surfaces the duplicate, not `bd`
  itself.

### 4.3 How a human resolves it

1. Identify which machine's claim/work should stand — usually the one that
   is further along (has a PR, has passed review) rather than the one that
   merely claimed first, since claim order is exactly what was not
   serialized here.
2. On the losing side: release or reassign the beads claim
   (`bd update <id> --assignee=<other>` or equivalent), and fold or discard
   any duplicate work (close the redundant branch/PR) rather than merging
   both.
3. If the row-level conflict already surfaced via §3.3, resolve it there
   first (last-write-wins by `updated_at` is the recorded default), then do
   step 1-2 on top for anything the automatic resolution didn't settle
   correctly (e.g. it picked the "wrong" side of a real divergence).
4. This collision class is a known limitation of the o97.5 design, not a bug
   to route around by adding more per-session syncs — see [§0](#0-why-two-per-session-calls-survive-a-deliberate-deviation)
   for the tradeoff reasoning; if it recurs often enough to be a problem,
   that is a signal to park an escalation for a human, not to silently
   tighten sync frequency back up.

## 5. Known-callers inventory

**Re-derived against current main (computenet-o97.7, 2026-08-14), not edited
in place from the earlier version of this table.** Derivation command, run
from the repo root:

```bash
grep -rnE 'bd dolt (pull|push)|dolt creds|dolthub\.com|refs/dolt/data|beads-nightly-sync\.sh' \
  --include='*.md' --include='*.sh' --include='*.yml' --include='*.yaml' -- .
```

followed by reading every hit in context to classify it as an actual
invocation site, a doc reference/mention, or an explicit non-caller. Hits
inside `doc/ops/beads-sync-runbook.md` and `doc/ops/beads-sync-cost.md`
themselves are excluded from the table below (self-references). `.github/`
and `.claude/hooks/` were checked separately and contain zero `bd dolt`
references — nothing in CI or a hook invokes sync.

| Caller | Op | When |
|---|---|---|
| `.claude/skills/work/SKILL.md` step 3 (`bd dolt pull`) | pull | Once, at session start |
| `.claude/skills/work/SKILL.md` acquisition brackets (epic claim, item claim in another epic, SDLC-epic filing, stale-claim steal — several sites, e.g. lines ~287, ~1271–1272, ~1344–1347, ~1374, ~1568) | pull + push | Per acquisition, at the moment it happens (computenet-wpvy.3 policy, §0.1) |
| `.claude/skills/work/SKILL.md` Finalize (`bd dolt push`, ~line 1426, with a recovery pull+push pair at ~1446–1447 if it's rejected non-fast-forward) | push | Once, at session end — the publication push |
| `.claude/skills/work/references/claim-sync.md` | pull + push | Not a separate invocation site — states the same acquisition-bracket and Finalize-recovery policy that `SKILL.md` implements; listed because it documents the pattern independently |
| `.claude/skills/remediate-friction/SKILL.md` (pull at start ~line 57; acquisition push ~79–84; closing push ~145) | pull + push | Once at session start, then bracketed around each friction item claimed/filed, then once at session close — same acquisition-brackets-writes policy as `/work`, on its own separate lane |
| `AGENTS.md` / `CLAUDE.md` Session Completion, "Team-maintainer opt-in only" step (`bd dolt push`) | push | Once, at end of a Beads workflow that is not a `/work` or `/remediate-friction` session, and only under the team-maintainer profile |
| `AGENTS.md` "Dolt sync of issue state is not remote sync" (prose, not a distinct call site) | pull + push | Describes the same `/work` orchestrator calls above as "routine... as part of the session flow" — not an additional caller, listed so this table doesn't look incomplete next to it |
| `scripts/beads-nightly-sync.sh` (this job, §1) | pull + push | **Not scheduled anywhere today** — manual invocation only, until a human installs a schedule per §8 |
| Humans, by hand | pull and/or push | Ad hoc — e.g. after resolving a conflict (§3.3), or before/after direct `bd` use outside a `/work`/`/remediate-friction` session |

**Explicit non-callers, worth naming because they mention `bd dolt push` in
passing and could be mistaken for invocation sites**:

- `.claude/skills/work/references/review-feature.md` ("Don't run `bd dolt
  push`" — issue-state sync is the orchestrator's job, not the feature
  reviewer's; the reviewer's `bd` writes stay local).
- `.claude/skills/work/references/red-check-attribution.md` — references a
  blocked `bd dolt push` from SKILL.md step 6, doesn't invoke one itself.
- `.beads/config.yaml` — the configuration the callers above read
  (`sync.remote`, plus the credential comment block quoted in the per-machine
  setup section); it declares the remote, it never invokes a sync.
- `.beads/README.md` — beads' own generic boilerplate quick-start doc (not
  written for this repo); it lists `bd dolt push`/`pull` as example commands,
  not as something this repo's tooling runs.

**Changes since the pre-DoltHub version of this table**: the
`.claude/skills/remediate-friction/SKILL.md` row, the `claim-sync.md` row, and
the `AGENTS.md` "Dolt sync... not remote sync" row are new — none appear in
the version of this table shipped in PR #63; `remediate-friction` in
particular did not exist yet. Every row that did exist before is still
accurate; nothing in the old table names a caller that has since disappeared.

**Nothing in this repo runs the job automatically.** No launchd plist, cron
entry, CI workflow or hook invokes `scripts/beads-nightly-sync.sh`; the word
"nightly" in its name and elsewhere in the repo describes the schedule it is
*meant* to be installed on (§8), not one that exists. Treat every reference to
"the nightly job" as "a human runs the script, or has installed §8's schedule
on this machine" until this table's row says otherwise.

**Machine-side residual, unverifiable from the repo**: whether any OTHER
automation on either machine still calls `bd dolt pull`/`push` — e.g. a
leftover cron/launchd entry from before this change, or from before
computenet-o97.5.1's skill cut-down. This cannot be checked from repo
contents; a human must check on each machine directly:

```bash
crontab -l | grep -i 'bd dolt\|beads-nightly-sync'
launchctl list | grep -i beads
# macOS: also check for LaunchAgents/LaunchDaemons plists referencing bd or beads-nightly-sync.sh
ls ~/Library/LaunchAgents ~/Library/LaunchDaemons 2>/dev/null | grep -i beads
```

No such entry was installed by this task (per §1 — installation is left as a
documented manual step, §8 below). If one already exists that this runbook doesn't know about,
inventory it into this table rather than leaving it silently outside this
accounting.

## 6. Backup freshness finding

Checked 2026-08-12 against the live tracker's `.beads/backup` directory
(`/Users/merlijn/Documents/local-projects/computenet/.beads/backup`, ~26M,
read-only for this check — see §7 for how it was used without touching the
live database). `.beads/config.yaml`'s `backup:` block is commented out
(defaults apply — periodic backup is enabled, not disabled; the comment shows
`enabled: false` only as an example of how to turn it OFF). File timestamps
inside `.beads/backup` at check time ranged up to minutes before the check
(e.g. `manifest` and several `.darc` chunk files stamped within the same hour
as the check), consistent with the default ~15-minute auto-backup interval
still running. **Finding: periodic backup is active and current** — the
recovery proof in §7 depends on this, and it held.

## 7. Recovery proof

Performed once, 2026-08-12, from this task's session. **Hard invariant
upheld throughout: the live `.beads/` at
`/Users/merlijn/Documents/local-projects/computenet/.beads` and the shared
remote were never written to.** The restore target was a scratch directory
under the session scratchpad, entirely separate from the live tracker and
from this worktree's own `.beads/`. `bd dolt push` was never run from the
scratch copy.

### 7.1 Setup: fresh scratch database

```
$ SCRATCH=.../scratchpad/recovery-proof
$ mkdir -p "$SCRATCH" && cd "$SCRATCH"
$ bd init --prefix computenet
...
✓ bd initialized successfully!
  Backend: dolt
  Mode: embedded
  Database: computenet
  Issue prefix: computenet
  Issues will be named: computenet-<hash> (e.g., computenet-a3f2dd)
```

### 7.2 Restore from the named backup artifact

Source artifact: `/Users/merlijn/Documents/local-projects/computenet/.beads/backup`
(the live tracker's current periodic-backup directory, confirmed fresh in
§6). Restored via `bd backup restore`, which reads from the given path and
writes only into the scratch database initialized in §7.1 — the source
directory is read-only input to this command:

```
$ bd backup restore /Users/merlijn/Documents/local-projects/computenet/.beads/backup --force

✓ Restore complete
```

### 7.3 Verify the restore actually landed the real tracker's data, not an empty db

```
$ pwd
/private/tmp/.../scratchpad/recovery-proof

$ bd list --all --json | jq 'length'
251

$ bd show computenet-o97 --json | head -5
[
  {
    "id": "computenet-o97",
    "title": "TRK1 — Take Dolt sync off the per-session path",
    ...
```

251 issues and the real `computenet-o97` epic body match the live tracker's
known content (see `doc/ops/beads-sync-cost.md`'s own issue-count
measurement) — this is the restored data, not a fresh empty database.

### 7.4 `bd ready` against the restored copy

```
$ bd ready
○ computenet-oxv ● P1 [epic] WSK2 — /work review and ship integrity
○ computenet-k9d ● P1 [epic] WSK1 — /work queue, claim and worktree correctness
○ computenet-8kj ● P1 [epic] BDS0 — Spike: is bd import a sound replication write-seam?
○ computenet-zqf ● P1 [bug] orchestrator dispatched a repair agent into a worktree its reviewer was still working in, which review-feature.md assumes cannot happen ← WSK1 — /work queue, claim and worktree correctness
○ computenet-0ja ● P1 a reviewer's local test run is on a different OS than the required checks, so it cannot see a platform-specific failure before the ready call ← WSK2 — /work review and ship integrity
... (full output ran to more rows, truncated here for length)
○ computenet-o97.6 ● P2 Untrack .beads/issues.jsonl from main ← TRK1 — Take Dolt sync off the per-session path
```

### 7.5 One mutation against the restored copy

The task's own id (`computenet-o97.5.2`) does not exist in the restored
snapshot — it was created after that backup was taken — so the mutation
below targets `computenet-o97.6`, an existing issue visible in the `bd ready`
output above, still inside the scratch copy:

```
$ bd comment computenet-o97.6 "RECOVERY-PROOF-TEST: comment added against restored scratch copy at $SCRATCH, 2026-08-12T14:11:56Z. This mutation is local to the scratch DB only; bd dolt push was never run from here."
✓ Comment added to computenet-o97.6 — Untrack .beads/issues.jsonl from main

$ bd comments computenet-o97.6
Comments on computenet-o97.6:

[MacBoo] at 2026-08-12 14:11

    RECOVERY-PROOF-TEST: comment added against restored scratch copy at
    /private/tmp/.../scratchpad/recovery-proof, 2026-08-12T14:11:56Z. This
    mutation is local to the scratch DB only; bd dolt push was never run from
    here.
```

### 7.6 Confirm the live tracker was never touched

```
$ cd /Users/merlijn/Documents/local-projects/computenet
$ bd comments computenet-o97.6 | grep -c "RECOVERY-PROOF-TEST"
0
```

Zero matches on the live tracker — the mutation in §7.5 landed only in the
scratch copy. The recovery path (restore from `.beads/backup` → `bd ready` →
one mutation) is proven end to end without endangering the live database or
the shared remote.

## 8. Installing a schedule (machine-side step)

Not performed by this task (see §1) — a human runs one of the following on
each machine that should run this unattended, then records it as installed
in §5's known-callers table.

**launchd (macOS)**, nightly at 02:00 local time:

```bash
cat > ~/Library/LaunchAgents/com.computenet.beads-nightly-sync.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.computenet.beads-nightly-sync</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-lc</string>
    <string>/path/to/computenet/scripts/beads-nightly-sync.sh</string>
  </array>
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>2</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>/tmp/beads-nightly-sync.log</string>
  <key>StandardErrorPath</key><string>/tmp/beads-nightly-sync.log</string>
</dict>
</plist>
EOF
launchctl load ~/Library/LaunchAgents/com.computenet.beads-nightly-sync.plist
```

**cron**, nightly at 02:00 local time:

```bash
( crontab -l 2>/dev/null; echo "0 2 * * * /path/to/computenet/scripts/beads-nightly-sync.sh >> /tmp/beads-nightly-sync.log 2>&1" ) | crontab -
```

Replace `/path/to/computenet` with the actual checkout path on that machine.
Either way, the script's nonzero exit codes (§1) are what a monitoring wrapper
or the log tail should watch for — this task does not wire up alerting beyond
the script's own exit status and log output.
