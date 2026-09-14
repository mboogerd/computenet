# Recovery

The orchestrator reads this when something rare interrupts SKILL.md's normal flow. Read only the section you need. `<scratch>` means your scratch directory's absolute path, spelled out, because shell variables do not survive between Bash calls.

## Contents

- Resuming after the host died — clock, side effects, reboot, revoked folder access
- Stalled agents and load — silent agents, watchdog stalls, the reviewer ladder, when to stop dispatching
- A red required check — the four artifacts, re-runs, the infrastructure park
- Dolt pull conflicts — the one resolvable shape and its exact commands
- Parks — the bar, how to park, re-triage and unpark
- Collisions — the signs, and why you never pick a winner

## Resuming after the host died

| Situation | Do | Why |
|---|---|---|
| A `task-notification` with `status=stopped` from the previous session | Recover the clock; stop every job in the previous `<scratch>/jobs`; re-stamp your holders by re-running `claim-epic.sh <epic-id>` (a hot-subtree SKIP on your own subtree → `CLAIM_SKIP_HOT=1`) and `claim-item.sh` on your `in_progress` feature; query side effects; rejoin SKILL.md "5. Work features" | The outcome is unknown, not failed, and your old holder token is dead, so a sibling's step 3 could release your epic |
| Several budget notifications arrive at once | Run `slot-elapsed.sh` and act on its rung, which is usually Finalize | The host was suspended and the Monitor fired late, so its tiers carry no information |
| The host rebooted | As above, after re-creating the scratch dir. If `claim-epic.sh` exits 1 with a LIVE or FOREIGN holder, another session has the epic: leave it and return to SKILL.md "3. Sync and claim one epic" | A reboot also clears `/private/tmp`, and with it your scratch dir |
| Every file operation in the repository is refused, sandbox or not | Do not retry. Ship only PRs a reviewer certified whose checks are green, working from outside the repository with `--repo mboogerd/computenet`; end by listing the bead writes that did not happen | The host revoked access and only a person can restore it; local tracker state survives for the next session |

Recover the clock from the original slot start, never from now: run `.claude/skills/work/scripts/slot-elapsed.sh <previous session's scratch dir>`. If that directory is gone, create a new one and write `slot-start` (epoch seconds) and `slot-seconds` into it. Take the start from the epic's `started_at`, which the claim set a few minutes after the true start. Read it with `.claude/skills/work/scripts/bead.sh <epic-id> -r '.started_at'`, then convert it to epoch seconds. Take the slot length from the routine that invoked you. If neither can be recovered, write the current time with a length of 3600, and say so in the summary.

Query side effects, then resume rather than restart. A killed breakdown may already have filed its children, and a killed implementer may already have committed. Re-dispatch only the units that left nothing behind, with the resume framing from "Stalled agents and load".

```bash
bd list --parent=<feature-or-epic-id> --all --limit 0 --json > <scratch>/children.json
git -C <task-worktree> log --oneline -5; git -C <task-worktree> status --short
```

If a task branch is missing locally, another machine did the work. Task branches are never pushed, and machines do not share refs. An item may be `in_progress`, or its comments may describe commits, while `git -C <main-checkout> rev-parse --verify task/<id>` fails. That item was started elsewhere, even though `next-batch.py` reports `resumed: false`. The dispatch prompt names the machine that holds the branch, or states that the prior work is unreachable and this is a re-implementation.

## Stalled agents and load

Judge a dispatched agent by its durable side effects, never its output file (SKILL.md "Hard constraints"). There are three safe signals: the completion notification, `bd comments <id> --json > <scratch>/<id>-comments.json`, and `git -C <worktree> log --oneline -5` together with `git -C <worktree> status --short`. At dispatch, record the time with `date -u +%s > <scratch>/dispatched-<id>`, and subtract it at each decision point. If nothing else would bring you back, arm one non-persistent Monitor that runs a single `sleep` followed by an `echo`.

| Situation | Do | Why |
|---|---|---|
| The agent returned without its outcome token (DONE/PARTIAL/BLOCKED, PASS/FAIL, READY/DRAFT) | Continue it and ask for its outcome plus a `NOT VERIFIED` section. Never act on the notification alone | A finished agent and a self-stopped one produce the same notification |
| The agent is slow, but a signal moved | Wait. At the budget rung that forbids waiting, `TaskStop` it | Nothing useful lies in between |
| No signal moved at a progress check | Continue it once. With no substantive reply, `TaskStop` it and re-dispatch | It may never have started |
| `status=failed` with "Agent stalled: no progress for 600s" | Follow the watchdog procedure below | The harness watchdog fired, often because of machine load |
| A reviewer has been quiet for about 60 minutes, with its bead unchanged since the implementer's comment | Follow the reviewer ladder below | A reviewer that neither reports nor stops never wakes you |

To continue an agent, use `SendMessage` if it exists; one `ToolSearch` for `select:SendMessage` tells you. Otherwise, dispatch a fresh agent whose prompt says it is a resume, not a clean start. That prompt carries the prior commit shas, the files touched, the gates that ran and their results, and what remains. It quotes the prior agent's own bead comment rather than paraphrasing it, or its `git log` and `status` when it left none, and tells the new agent to stop any live job in the prior agent's `<scratch>/jobs` first. It also says that your summary is unreviewed orchestrator text, to verify before building on it.

Watchdog procedure:
1. Read the machine with `python3 .claude/skills/work/scripts/next-batch.py --capacity` and `ps -eo pid,pcpu,comm | sort -k2 -rn | head`. If the load is external, say so in any new prompt.
2. Read the three signals.
3. If all three are empty, the agent never started. Re-dispatch it with: "A previous agent stalled before taking any action. I verified it left no side effects. This is a clean start, not a resume; do not look for prior work." If you release the claim instead, comment that fact on the bead.
4. If any signal moved, treat it as the slow agent in the table.

Reviewer ladder:
1. Continue the reviewer and ask for its verdict now, plus `NOT VERIFIED`. Give it a short window.
2. If it does not answer, `TaskStop` it. A stopped review certifies nothing. Route on what it wrote to the bead.
3. On the remaining budget, choose between a fresh reviewer and leaving the PR draft.

If the continuation itself stalls, do not resend it: replaying a long transcript is what stalls. Instead, dispatch a fresh reviewer scoped to the open criteria, with the prior findings in its prompt. Tell it that it is the second reviewer, whether there is partial state to reconcile, which blockers you cleared, and that a stated verdict on honestly scoped evidence outranks exhaustive coverage. Clear predictable blockers before dispatching. If `origin/main` has moved and the review needs that merge, you merge, run the affected module suites, and push. If a superseded pass left a review marker, run `bd update <id> --unset-metadata review` and comment what the old marker meant.

Load: the advice string from `next-batch.py --capacity` tells our load apart from host load.

| Situation | Do | Why |
|---|---|---|
| Load is ours (one of our builds is busy) | Dispatch nothing. Wait for the build | A new agent stalls, and the build ends on its own |
| Load is host load | Do not idle. Choose a unit that needs no Gradle: bead text, reconciliation, or review of an already-green PR. Tell the agent that tool calls will be slow, to take fewer and larger steps, and to comment on its bead early | There is nothing to wait for. `bd` is contended too, so this work is Gradle-free, not load-free |
| Two consecutive dispatches died with no side effects | Stop dispatching. Hold on a bounded Monitor until load1 is under 2x cores, or go to Finalize if the budget cannot absorb the wait. Keep doing orchestrator-local bookkeeping | At this load any tool call can outlive the watchdog |
| Your own capacity read times out | Read `uptime`. At or above 5x cores, apply the stop rule without retrying the read | A read that cannot return is itself the measurement |
| One live agent is the session's most valuable unit | Holding every dispatch is legitimate. Comment the hold on the epic | A marginal dispatch is likelier to kill the live agent than to finish |

## A red required check

This section covers a required check that is red in a module the diff does not touch, or red after you marked the PR ready. A red check on touched code is the feature's own work (SKILL.md "5e. Feature review and ship"). A red required check is this feature's defect until evidence shows the diff cannot have caused it, because from outside a flake looks exactly like a regression. Have all four artifacts first — use the ones a reviewer's report already quotes, and produce the rest. Whatever you write onward carries the run id, the job and the verbatim `FAILED` line, and marks any untested mechanism `unverified:`.

1. The failing test, its assertion and its frame, read from the log. `gh pr checks <pr-url>` names the failing check and its run. Logs exist only after the whole run finishes. `-R` is needed because `gh` resolves the repo from the working directory.

   ```bash
   gh run view <run-id> --log-failed -R mboogerd/computenet \
     | grep -E 'FAILED|FAILURE| e: |Caused by:|(Exception|Error):|\.(kt|java):[0-9]+\)' \
     | head -60
   ```

   `--log-failed` returns the failed job's whole log, mostly `PASSED` lines, which is why the output is filtered. If `head` cut off the summary, grep for `FAILED` alone. Quote the `FAILED` line and the `at <Class>.<method>(<File>:<line>)` frames beneath it. The frame tells a setup failure apart from a failure in the behaviour under test, and the log ages out before anyone works the bead.

2. Evidence the diff cannot have caused it. Run `gh pr diff <pr-url> --name-only`. If the failing test's module is absent, this artifact holds. If the module is present, it holds only when three things are all true. The failing class is not among the changed files. Every change is comment or documentation text, with zero executable lines. No test reads the prose you edited. Tests here do read comments and docs, some by walking a directory without naming any file, so search for the file's name and its ancestor directories:

   ```bash
   git grep -l '<edited basename>' -- '*/src/test/*'
   d=$(dirname '<repo-relative path of the edited file>')
   while [ "$d" != . ]; do
     git grep -l "$d" -- '*/src/test/*'
     case "$d" in */src/test) break;; esac
     d=$(dirname "$d")
   done | sort -u
   ```

   Read each hit. A walker that matches patterns against comment text reads your prose. One that filters on file names does not. State which granularity your attribution stands on.

3. A prior occurrence, found rather than remembered. `bd search` matches titles only, and a flake is usually named in a description, so run both:

   ```bash
   bd search "<failing test class>" --status all --json > <scratch>/flake-titles.json
   bd list --all --limit 0 --desc-contains "<failing test class>" --json > <scratch>/flake-descs.json
   ```

   A bead naming the test is only a candidate. It counts once its stated mechanism still explains this failure (a timeout bead does not cover a wrong value) and its numbers still hold at this revision, on the failing lane. Say which way each check fell. If the only match is closed, file a new bug bead carrying both occurrences (`create-ticket.sh --top-level`, then `bd dep add <new-id> <closed-id> --type related`). If nothing matches, this is a first sighting, not a flake, and the check is red work. The fix belongs on `main`, so file an unparented bug bead. It carries the failing task, the exception and full stack, the surrounding task headers with timestamps, and the runner spec:

   ```bash
   .claude/skills/work/scripts/create-ticket.sh --type bug --top-level --title "<test>: <symptom>" --desc-file <scratch>/flake-bug.md
   ```

4. What the prior bead instructs, even when it is closed. A standing "do not re-run" instruction overrides everything below.

With all four in hand, you re-run the failed jobs; reviewers hand the re-run back to you. Re-run at most twice. Comment each occurrence (run id, sha, pass or fail) on the bug bead, because the count is what gets the flake fixed. A reviewer that attributed the failure may write that comment itself (references/review.md "Write scope").

```bash
gh run rerun <run-id> --failed -R mboogerd/computenet
```

| Outcome | Do |
|---|---|
| Green | A DRAFT whose only blocker was this check is now shippable on the reviewer's certification: continue at SKILL.md 5e, Ship step 2 |
| Still red, and artifact 2 held | Record on the bug bead that the failure reproduces. The feature is blocked on infrastructure, not defective. Leave the PR as it is. Keep the feature `in_progress`, and keep any `review=passed`. Run `bd update <feature-id> --set-metadata parked_at=$(date +%s)`, then comment "blocked on `<bug-id>`: `<check>`, runs `<ids>`". Go to SKILL.md "5f. Next unit". File no task under the feature |
| Still red, and artifact 2 did not hold | It is the feature's red work |
| The re-run command is refused | Take the same infrastructure park. Push no empty commit to trigger a run. Name the refused command verbatim in the session summary |

**Never ship on a red required check**, whatever the attribution says.

If a red check lands after the PR is ready, run `gh pr ready --undo <pr-url>`. Wait for the feature reviewer's notification before dispatching the fix into that worktree. **Never put a second agent into a live agent's worktree or onto its branch**: the worktrees share one branch ref, so the stale one's next commit silently reverts the fix. Do not mark the PR ready again until every agent on it has reported.

## Dolt pull conflicts

Enter here when `bd dolt pull` fails with `merge conflicts in issues require operator resolution`, when `publish-beads.sh` exits 2 on a conflict, or when a `bd` write fails with `Error 1105: Merge conflict detected`. That write did not happen: after resolving, re-issue it and re-read the bead. Any other pull failure stops the session (SKILL.md "3. Sync and claim one epic"). This is an ordinary two-machine concurrent edit, not corruption, and `bd` has no resolver, so you resolve it with the `dolt` CLI. **This route resolves only `modified`/`modified` rows on the `issues` table, last-write-wins by `updated_at`.** Escalate every other shape: abort the merge if one is in progress (`dolt merge --abort`), stop syncing, and put the conflict rows at the top of the session summary.

| Signal | Why it is not this route |
|---|---|
| A table other than `issues` | Each table needs its own rule. `child_counters` has one (below); other tables have none |
| `added`/`added`, or `our_title` ≠ `their_title` | Two machines minted one id for different beads, and last-write-wins would destroy one of them |
| Either side is `removed` | The bulk resolution keeps ours silently; decide it explicitly |
| Statuses differ (`closed` vs `open`) | A close is a real event. Check its PR or commit, not the timestamp |
| A constraint violation, or a row in `dolt_schema_conflicts` | Either the merge does not land, or the generated clause cannot describe a column that exists on one side only |

Steps 1–3 run in the database directory, so each block begins with a `cd`. Step 1: give dolt an identity (a merge commit needs one, and `--local` keeps it out of the global config), take a backup branch, fetch, merge without committing, and inspect. The inspection is the gate. Every `issues` row must read `modified`/`modified` with `same_title` true and equal statuses, and no other table may appear except `child_counters` (below). Anything else leaves the route.

```bash
cd <main-checkout>/.beads/embeddeddolt/computenet || exit 1
dolt config --local --add user.name  "$(git config user.name)"
dolt config --local --add user.email "$(git config user.email)"
dolt branch pre-merge-backup-$(date +%Y%m%d-%H%M) main
dolt fetch
dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  call dolt_merge('--no-commit','origin/main');
  select * from dolt_conflicts;"
dolt sql -q "select our_id, our_diff_type, their_diff_type,
  our_title = their_title as same_title,
  our_status, their_status, our_updated_at, their_updated_at
  from dolt_conflicts_issues;"
```

Step 2: resolve. Generate the `SET` clause; a hand-typed list silently drops columns. Raise `group_concat_max_len` in the same invocation, and strip the quotes that `-r csv` adds. Count the assignments against the schema, because a truncated clause can still be valid SQL that half-merges rows.

```bash
cd <main-checkout>/.beads/embeddeddolt/computenet || exit 1
COLS=$(dolt sql -r csv -q "select count(*) from information_schema.columns
  where table_name='issues' and table_schema=database();" | tail -1)
SET=$(dolt sql -r csv -q "
  set session group_concat_max_len=1000000;
  select group_concat(concat('i.\`', column_name, '\` = c.\`their_', column_name, '\`') separator ', ')
  from information_schema.columns
  where table_name='issues' and table_schema=database();" | tail -1)
SET=${SET#\"}; SET=${SET%\"}
N=$(printf '%s' "$SET" | grep -o 'c\.`their_' | wc -l | tr -d ' ')
[ "$N" = "$COLS" ] || { echo "STOP: SET names $N of $COLS columns — truncated"; exit 1; }
dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  UPDATE issues i JOIN dolt_conflicts_issues c ON i.id = c.our_id
  SET $SET
  WHERE c.their_updated_at > c.our_updated_at;
  DELETE FROM dolt_conflicts_issues;"
```

Rows where ours is newer stay as they are. Emptying `dolt_conflicts_issues` is what marks the conflict resolved.

`child_counters` has no `updated_at`, so its rule is to take the greater `last_child`: the lower side re-mints ids that live beads already hold. First inspect with `select * from dolt_conflicts_child_counters;`. Before committing, check that no existing child index under that parent exceeds the resolved value.

```bash
cd <main-checkout>/.beads/embeddeddolt/computenet || exit 1
dolt sql -q "UPDATE child_counters cc JOIN dolt_conflicts_child_counters c
  ON cc.parent_id = c.our_parent_id
  SET cc.last_child = GREATEST(c.our_last_child, c.their_last_child);
  DELETE FROM dolt_conflicts_child_counters;"
```

Step 3: commit. Both selects must print nothing.

```bash
cd <main-checkout>/.beads/embeddeddolt/computenet || exit 1
dolt sql -q "select * from dolt_conflicts; select * from dolt_constraint_violations;"
dolt commit -am "Merge origin/main: resolve <N> issue conflicts last-write-wins by updated_at"
```

Step 4: prove the round trip from the main checkout. Expect the pull to complete and the publication push to succeed. Give each a Bash timeout of at least 300000 ms.

```bash
cd <main-checkout> && bd dolt pull
```

```bash
cd <main-checkout> && .claude/skills/work/scripts/publish-beads.sh
```

Last-write-wins is lossy. Name every id you resolved, and which side won, in the summary and on any bead whose state changed. If a resolution would discard real work, abort and park it for a person instead. Examples: a close against substantive comments, or a description rewritten on both sides.

## Parks

Park only when the question is both ambiguous (the source supports more than one reading) and costly (risky beyond the item, hard to revert, or expensive to unpick). The test is whether a wrong choice costs a five-minute fix or a five-hour one. A decision the item explicitly reserves for a person is a park however cheap. An ordinary judgment call is not a park, and neither is work bigger than its item implied: split that. A missing local tool is a `needs:<tool>` label and a skip, never a park, because another machine may have the tool.

Park the narrowest stuck item, never its feature or epic. First read the thread with `.claude/skills/work/scripts/park-thread.sh <id>`, because the answer may already be there. Its exit 1 means no comment matched the known answer forms, not that there is no answer, so read what it printed.

Write the question to a file that begins `QUESTION:`, for a cold reader: what you were doing, the options, your default, and why the call is not yours. Then park with one write per call. Each field does work: `blocked` keeps the item out of `bd ready`, `assignee=human` keeps it out of the stale-claim sweep, and the `human` label surfaces it in `bd human list`.

```bash
bd update <id> --status=blocked --assignee=human --add-label=human
```

```bash
bd comment <id> --file <scratch>/question-<id>.md
```

A child created under a parked item inherits `human`, so pass `--no-inherit-labels`. Name the park in your report and move on; do not wait for the answer.

Re-triage human parks once per session, on the first pass through SKILL.md "5. Work features". `bd ready` cannot see them, so they rot after their blocker clears. List them repo-wide with the command below, then keep the ones under your epic by running `epic-of.sh <id>` on each (exit 1 means unresolved, not unparented). Read each kept thread with `park-thread.sh`. `bd human respond` closes the item it answers, so answered parks appear in `bd human list --status=closed`, not in the blocked list.

```bash
bd list --status=blocked --label human --limit 0 --json > <scratch>/parked.json
```

- The newest comment is not the state. A comment recording a decision outranks any later comment restating the question, because re-parks are written by agents. Authorship cannot tell them apart: every comment carries the machine actor.
- Unpark only on observable evidence: an answer, or the named PR merged, bead closed, or secret present. Close a superseded item with the reason instead. Elapsed time is not evidence, and neither is your own view that the answer is obvious.
- Unpark all three fields together, and comment why, naming the answer's timestamp and any later re-park's. A leftover assignee makes the item unclaimable. A leftover label hides it from selection.

  ```bash
  bd update <id> --status=open --assignee="" --remove-label=human
  ```

- Reconcile the bead text before anyone is dispatched on it, because a reviewer scores against the fields, not the thread. Rewrite acceptance (`--acceptance "$(cat <file>)"`), and the description (`--body-file <file>`) where it prescribes the rejected option, so both read as current work. Put the superseded wording in a comment. Amend the same clause in the parent feature's and epic's criteria, or comment why not. File an overridden criterion that still names real work as its own bead. The dispatch prompt says "this bead was parked and answered — read its comment thread".

## Collisions

Two sessions can both win a race that the claim bracket did not close. Nothing errors at claim time; the signs surface later. One item has two sets of children (`twin-scan.py <parent-id>`, run from the main checkout, flags them with exit 1; exit 3 means nothing was checked). Two branches or two PRs exist for one feature. The assignee names one machine while another machine's worktree holds committed work for the item. At merge or ship time, origin is ahead of your local branch, or an open PR on the head is not yours. A sibling PR touches your item's files. **Stop working that item and park a question naming both sides' artifacts; never pick a winner.** The losing side may hold committed, pushed, unreviewed work. Do not merge, force-push, close or delete through a collision. Keep working the feature's other items.
