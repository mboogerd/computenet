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
`unknown option 'no-pager'` (measured on 1.x here). Non-interactive `-q` output
does not page anyway; just leave the flag off.

**2. Fetch and merge without committing**, allowing the working set to hold
conflicts so you can inspect them:

```bash
dolt fetch
dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  call dolt_merge('--no-commit','origin/main');
  select * from dolt_conflicts;"
```

Read the count and the table it names. In the worked case: `issues`, 11 rows,
every one `our_diff_type=modified` / `their_diff_type=modified`.

**3. Inspect before resolving.** `dolt_conflicts_issues` carries `our_*` and
`their_*` for every column. Look at what actually differs — most conflicts are
semantically empty:

```bash
dolt sql -q "select our_id, our_status, their_status,
  our_updated_at, their_updated_at from dolt_conflicts_issues;"
```

**4. Resolve last-write-wins by `updated_at`.** **Generate the `SET` clause,
do not type it** — `issues` has **54 columns** (measured 2026-08-17), and a
hand-written list silently drops whichever one you forget:

```bash
SET=$(dolt sql -r csv -q "
  select group_concat(concat('i.\`', column_name, '\` = c.\`their_', column_name, '\`') separator ', ')
  from information_schema.columns
  where table_name='issues' and table_schema=database();" | tail -1)

dolt sql -q "set @@dolt_allow_commit_conflicts=1;
  UPDATE issues i JOIN dolt_conflicts_issues c ON i.id = c.our_id
  SET $SET
  WHERE c.their_updated_at > c.our_updated_at;
  DELETE FROM dolt_conflicts_issues;"
```

Rows where ours is newer are left as they are — that is the "keep ours" half —
and clearing `dolt_conflicts_issues` is what marks the conflict resolved.

**5. Commit, then prove the round trip works:**

```bash
dolt commit -am "Merge origin/main: resolve N issue conflicts last-write-wins by updated_at"
cd -                       # back to the main checkout
bd dolt pull               # expect: Pull complete.
bd dolt push               # expect: Push complete. Can exceed 120s — give it >=300s
```

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
