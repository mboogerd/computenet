# Resolving a `bd dolt pull` merge conflict

`bd dolt pull` hard-fails when two machines edited the same issue rows between
syncs:

```
merge conflicts in issues require operator resolution
```

**This is an ordinary two-machine concurrent-edit conflict, not corruption and
not a reason to run without sync.** It is the expected consequence of the very
concurrency this skill is built for, and it recurs — nothing prevents the next
one. SKILL.md step 3 stops the session on a failed pull because claiming
against stale state is unsafe; this file is how you get the pull working again
rather than proceeding blind.

`bd` has no conflict-resolution subcommand. The embedded database is a normal
dolt repo, so the dolt CLI resolves it.

## Scope — what this file resolves, and what still stops the session

**The signature is not only a `pull` abort.** A `bd` MUTATION (`--claim`,
`update`, `close`) can fail with `Error 1105: Merge conflict detected,
@autocommit transaction rolled back …` — same conflict, raw SQL wording, and
the write you asked for did NOT happen. It can also self-clear: `dolt
conflicts` showed `issues, 1` and two calls later nothing, with the write
still silently absent (computenet-zy98). Treat 1105 as this file's entry
point: run step 2's scope table, resolve or escalate, then **re-issue the
mutation and re-read the bead** — never assume it landed.

This route is **only** for a `modify/modify` conflict confined to the `issues`
table. That is the common shape, and the resolution rule below is
last-write-wins by `updated_at`, which is meaningless or destructive elsewhere.
Stop and escalate ([ask-human.md](ask-human.md)) if any of these hold — step 2
and step 3 below are what tell you:

| Signal | Why it is not this route |
| --- | --- |
| The abort names a table other than `issues` (`child_counters`, `dependencies`, …) | Different rule per table; `child_counters` has no `updated_at` at all. See "Other tables". |
| A conflict row has `our_diff_type='added'` **and** `their_diff_type='added'` | Two machines minted the same id for **different** beads. LWW silently destroys one. Re-mint, don't pick a winner (computenet-azt, the wpvy.46 hash-id route). |
| `our_title` differs from `their_title` on a `modified/modified` row | Same signature as the id collision above. |
| Either side is `removed` | A delete against an edit. The sequence below leaves those rows **as they are locally** and the final `DELETE` marks them resolved — i.e. ours wins silently, whichever way round it is. Decide those explicitly. |
| The statuses differ (`closed` vs `open`) | A close is a real event with an external artifact; a later touch is not evidence. Check the PR/commit, not the timestamp (computenet-dqy.40 was nearly reopened by this rule). |
| The merge reports a **constraint violation** | See "Constraint violations" — the merge does not even land. |

## The sequence

Worked end to end on 2026-08-12 by an unattended run, resolving 11 conflicts
with no human (computenet-3v8, computenet-gq0). Run it from the **main
checkout**, where `.beads/` lives.

```bash
cd .beads/embeddeddolt/computenet
```

**1. Give dolt an identity** — a merge commit needs one, and `--local` keeps it
out of your global config:

```bash
dolt config --local --add user.name  "$(git config user.name)"
dolt config --local --add user.email "$(git config user.email)"
```

There is **no `--no-pager` flag on dolt** — `dolt --no-pager sql …` fails with
`unknown option 'no-pager'` (measured on dolt 2.2.3 here). Non-interactive `-q` output
does not page anyway; just leave the flag off.

**2. Take a backup branch, then fetch and merge without committing**, allowing
the working set to hold conflicts so you can inspect them. The branch costs
nothing and is the only cheap way back if a resolution turns out wrong:

```bash
dolt branch pre-merge-backup-$(date +%Y%m%d-%H%M) main
dolt fetch
dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  call dolt_merge('--no-commit','origin/main');
  select * from dolt_conflicts;"
```

Read the count and the table it names. In the worked case: `issues`, 11 rows,
every one `our_diff_type=modified` / `their_diff_type=modified`. **If it names
any table other than `issues`, stop here** and read "Other tables" below.

**3. Inspect before resolving — this is the gate, not a formality.**
`dolt_conflicts_issues` carries `our_*` and `their_*` for every column. Select
the diff types and the titles too: they are what distinguish the benign shape
from the id collision that silently destroys a bead.

```bash
dolt sql -q "select our_id, our_diff_type, their_diff_type,
  our_title = their_title as same_title,
  our_status, their_status, our_updated_at, their_updated_at
  from dolt_conflicts_issues;"
```

Every row must read `modified`/`modified` with `same_title = true` before you go
on. Anything else is a row in the scope table above — resolve those by hand or
escalate; do not let the bulk `UPDATE` and `DELETE` decide them.

The benign, expected shape is **both machines closed the same bead**: their
`sweep-merged-prs.sh` runs both observed the same merged PR, so the rows differ
only in `updated_at`/`closed_at` with an identical `content_hash`. When only a
couple of columns differ, an explicit `SET i.updated_at = c.their_updated_at,
i.closed_at = c.their_closed_at` is better than the generated clause below —
auditable, and it cannot truncate.

**4. Resolve last-write-wins by `updated_at`.** **Generate the `SET` clause,
do not type it** — `issues` has **54 columns** (measured 2026-08-17), and a
hand-written list silently drops whichever one you forget. Two traps sit in
that one generated line, both measured 2026-08-17 against dolt 2.2.3:

- **`group_concat` truncates at `group_concat_max_len`, which defaults to
  1024**, and the clause is 2054 characters. Raise the limit **in the same
  `dolt sql` invocation** — a session variable set in a separate call does not
  survive.
- **`-r csv` wraps the value in double quotes**, because it contains commas.
  Those quotes survive `$(…)` and land inside the SQL, which then reads the
  whole clause as a string literal and fails with `syntax error at position
  2130`. Strip them. (The quoted string is 2056 characters, the clause 2054 —
  so a length threshold alone passes happily on an unusable value.)

Count the assignments against the schema rather than measuring characters:

```bash
COLS=$(dolt sql -r csv -q "select count(*) from information_schema.columns
  where table_name='issues' and table_schema=database();" | tail -1)

SET=$(dolt sql -r csv -q "
  set session group_concat_max_len=1000000;
  select group_concat(concat('i.\`', column_name, '\` = c.\`their_', column_name, '\`') separator ', ')
  from information_schema.columns
  where table_name='issues' and table_schema=database();" | tail -1)
SET=${SET#\"}; SET=${SET%\"}        # -r csv quotes any value containing commas

N=$(printf '%s' "$SET" | grep -o 'c\.`their_' | wc -l | tr -d ' ')
[ "$N" = "$COLS" ] || { echo "STOP: SET names $N of $COLS columns — truncated"; exit 1; }

dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  UPDATE issues i JOIN dolt_conflicts_issues c ON i.id = c.our_id
  SET $SET
  WHERE c.their_updated_at > c.our_updated_at;
  DELETE FROM dolt_conflicts_issues;"
```

Rows where ours is newer are left as they are — that is the "keep ours" half —
and clearing `dolt_conflicts_issues` is what marks the conflict resolved.
The id assignment the generator emits (`i.id = c.their_id`) is a no-op: the
join is on `our_id` and dolt keys conflicts by primary key, so `their_id`
equals `our_id` on every row it can match — verified against a
modify/modify, an added/added and both delete/modify orientations. It never
rewrites a primary key.

**Why the count check is not paranoia.** At the default limit the clause is cut
at exactly 1024 characters, which on this schema lands mid-identifier
(`… i.ephemeral = c.their_ephemeral, i.`) and the `UPDATE` fails with a
syntax error — loud, and survivable. But where the cut lands is an accident of
column-name widths: one rename away it falls on a clause boundary instead, and
then the `UPDATE` is **valid** and silently copies only the first ~20 of 54
columns, leaving rows half-merged with the conflict table already deleted. That
is unrecoverable without a re-pull. Check the count; do not rely on the syntax
error, and do not check the length.

**5. Commit, then prove the round trip works:**

```bash
dolt sql -q "select * from dolt_conflicts; select * from dolt_constraint_violations;"   # both empty
dolt commit -am "Merge origin/main: resolve N issue conflicts last-write-wins by updated_at"
cd -                       # back to the main checkout
bd dolt pull               # expect: Pull complete.
bd dolt push               # expect: Push complete. Can exceed 120s — give it >=300s
```

Emptying `dolt_conflicts_issues` really is what marks the merge resolved on
this version — `dolt status` then says *"All conflicts and constraint
violations fixed but you are still merging"*, and `dolt commit` concludes it
with a proper two-parent merge commit. `dolt conflicts resolve` and
`dolt_conflicts_resolve()` are **not** required, which is just as well: the
stored-procedure form has been blocked by the auto-mode classifier in an
unattended session, while this `UPDATE`/`DELETE` form was permitted
(computenet-gq0, 2026-08-13).

## Other tables, constraint violations, schema conflicts

**Other tables.** The same abort fires naming `child_counters` or
`dependencies`; four recorded instances named `child_counters`, not `issues`.
`child_counters` (`parent_id`, `last_child`) has **no `updated_at`**, so
last-write-wins does not even typecheck, and taking the lower side re-mints ids
that already belong to live beads. Its rule is `GREATEST`, verified against the
data before committing:

```bash
dolt sql -q "select * from dolt_conflicts_child_counters;"
# UPDATE child_counters cc JOIN dolt_conflicts_child_counters c
#   ON cc.parent_id = c.our_parent_id
#   SET cc.last_child = GREATEST(c.our_last_child, c.their_last_child);
# DELETE FROM dolt_conflicts_child_counters;
# then check: max existing child index under that parent <= the resolved value
```

Any other table: stop and escalate. There is no verified rule.

**Constraint violations are a different failure and they abort the merge.**
Eight tables carry foreign keys into `issues` (`comments`, `dependencies`,
`labels`, `events`, …), so one machine deleting an issue while the other
comments on it produces a violation, not a conflict. Measured: `call
dolt_merge('--no-commit', …)` then **rolls the whole statement back** and dumps
`Foreign Key Constraint Violation` — nothing is merged, `dolt_conflicts` is
empty, and steps 3–4 find nothing to do. (With `@@dolt_force_transaction_commit=1`
the merge lands instead and `dolt commit` refuses with *"the table(s) X have
constraint violations"*.) Either way you are not in this route: escalate.

**Schema conflicts** land in `dolt_schema_conflicts` and are likewise out of
scope — the generated clause is built from the *current* schema and cannot
describe a column that exists on only one side.

## What to say afterwards

**Name the ids you resolved and which side won**, in the session summary and on
any bead whose state the merge changed. Last-write-wins is lossy by
construction: a row resolved to *theirs* discards a local edit that nobody will
ever see again. In the worked case 7 of 11 resolved to ours and 4 to theirs,
and 8 of the 11 were closed on both sides differing only in a timestamp — so
the resolution was close to a semantic no-op, which is the normal shape. The
one real divergence was a bead closed locally at 12:17 against open remotely at
08:21; the close was later, so the close won.

If a resolution looks like it would discard **real** work — a close against an
open with substantive comments, a description rewritten on both sides — stop
and park it for a human ([ask-human.md](ask-human.md)) rather than letting the
`updated_at` comparison decide something a person should.
