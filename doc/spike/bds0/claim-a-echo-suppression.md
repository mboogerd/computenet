# BDS0 claim (a) — echo suppression via `metadata.cn_dot`

Epic `computenet-8kj` §1(a). The claim under test: a provenance stamp
`metadata.cn_dot = "<replica>:<seq>"` placed on a replicated write

- (i) survives `bd import`'s upsert verbatim on B's **stored issue**,
- (ii) is readable off B's **journal record** for that upsert, so a projector
  reading the journal recognises its own echo and mints no fresh dot,
- (iii) survives B's **re-export** unchanged.

Result in one line: **(i) pass, (ii) fail, (iii) pass.** The value survives
the store and the re-export byte-identically; the journal cannot see it,
because the journal record has no metadata column and imported *updates*
produce no journal record at all.

Everything below was executed. Every transcript is a paste-able block against
a fresh `rig.sh init`; the whole sequence was re-run end to end on a second,
independent rig root and produced the same outcomes (issue ids differ per
init — they are `mktemp`-fresh workspaces, so substitute your own).

## Environment, verified rather than assumed

Verified in this run, not carried over from the rig's README:

```
$ cat "$BDS0_RIG_ROOT/bd-version.txt"
bd version 1.1.2 (Homebrew)

$ bd -C "$BDS0_RIG_ROOT/B" --sandbox events bdsa-00r
Error: unknown command "events" for "bd"

$ bd -C "$BDS0_RIG_ROOT/B" --sandbox sql -q "select 1"
Error: 'bd sql' is not yet supported in embedded mode
```

So there is no `bd`-level journal read. `rig.sh journal` reads the `events`
table out of the workspace's embedded Dolt database with the `dolt` CLI, and
that table is the only journal surface this section could inspect.

Safety: every command here ran against a synthetic `mktemp -d` rig root via
`bd -C <ws> --sandbox` or a `rig.sh` subcommand. Nothing in this section read
or wrote the repository's live `.beads`.

`bash scripts/spike/bds0/rig.sh smoke` passed before the run
(`== rig.sh smoke: OK (1 journal event(s) for bdsa-rv2 in B) ==`).

## Setup

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"
A="$BDS0_RIG_ROOT/A"; B="$BDS0_RIG_ROOT/B"
X=$(bd -C "$A" --sandbox list --json | jq -r '[.[]|select(.title|startswith("Plain"))][0].id')   # bdsa-00r

bd -C "$A" --sandbox update "$X" --title "edited in A (hop 1)"
sleep 1                                             # LWW is 1s wall-clock; a same-second hop ties
bash scripts/spike/bds0/rig.sh hop A B --dot "A:7" "$X"
```

```json
{"created": 1, "ids": ["bdsa-00r"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

`X` does not exist in B before the hop — B is seeded with its own `bdsb-*`
issues — so this first hop is a create.

## Sub-check (i) — stored issue: **PASS**

```bash
bd -C "$B" --sandbox show "$X" --json \
  | jq 'if type=="array" then .[0] else . end | {id,title,updated_at,metadata}'
```

```json
{
  "id": "bdsa-00r",
  "title": "edited in A (hop 1)",
  "updated_at": "2026-08-13T15:10:54Z",
  "metadata": { "cn_dot": "A:7" }
}
```

`metadata.cn_dot` is byte-identical to the stamped value, and the stamp did
not disturb `updated_at` (B's copy carries A's `updated_at`, not an import
timestamp). A second hop carrying a different dot replaces it as expected
(`A:7` → `A:8`, see below), so the field is a normal mutable metadata key, not
a write-once one.

The rig's `hop` preserves other metadata keys while stamping, so the seeded
issue that already carries `metadata.cn_dot="seed:0"` is a *replacement* case,
not a merge case; nothing here tested a stamp landing beside other keys of the
same row, because the rig stamps the same key the seed uses.

## Sub-check (ii) — journal record: **FAIL**

The record shape that loses it. The `events` table has no metadata column at
all:

```bash
cd "$B/.beads/embeddeddolt"/*/ && dolt sql -q "describe events"
```

```
+------------+--------------+------+-----+-------------------+
| Field      | Type         | Null | Key | Default           |
+------------+--------------+------+-----+-------------------+
| id         | char(36)     | NO   | PRI | NULL              |
| issue_id   | varchar(255) | NO   | MUL | NULL              |
| event_type | varchar(32)  | NO   |     | NULL              |
| actor      | varchar(255) | NO   |     | NULL              |
| old_value  | longtext     | YES  |     | NULL              |
| new_value  | longtext     | YES  |     | NULL              |
| comment    | text         | YES  |     | NULL              |
| created_at | datetime     | NO   | MUL | CURRENT_TIMESTAMP |
+------------+--------------+------+-----+-------------------+
```

Provenance could in principle ride in `new_value`, which is a JSON blob for
locally-originated edits. It does not. The record the import left is:

```bash
bash scripts/spike/bds0/rig.sh journal B "$X"
```

```json
{"rows": [{"actor":"MacBoo","created_at":"2026-08-13 17:10:57","event_type":"created",
           "id":"019ffbad-282f-71e8-bf83-c41316fac150","issue_id":"bdsa-00r",
           "new_value":"","old_value":""}]}
```

`event_type=created`, **empty** `old_value` and `new_value`, `actor` = the
importing machine's local actor (`MacBoo`), no field naming the origin
replica. A projector reading only this row knows an issue id changed and
nothing else — not what changed, not who originated it.

For contrast, the same workspace's *locally* originated edits do carry
payload. A's journal for the same issue after two local `bd update`s:

```bash
bash scripts/spike/bds0/rig.sh journal A "$X"
```

```json
{"rows": [
 {"event_type":"created","new_value":"","old_value":"", ...},
 {"event_type":"updated","new_value":"{\"title\":\"edited in A (hop 1)\"}",
  "old_value":"{\"id\":\"bdsa-00r\",\"title\":\"Plain seeded task in bdsa\",\"status\":\"open\",...}", ...},
 {"event_type":"status_changed","new_value":"{\"status\":\"in_progress\"}",
  "old_value":"{...,\"updated_at\":\"2026-08-13T15:10:54Z\"}", ...}]}
```

Note what the local `old_value`/`new_value` blobs contain: the issue's scalar
columns. `metadata` is absent from them too, so even the richer local-edit
record shape would not carry `cn_dot`.

**The stronger failure: an imported *update* journals nothing at all.**
Second hop, against a row that already exists in B:

```bash
bd -C "$A" --sandbox update "$X" --status in_progress
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:8" "$X"
```

```json
{"created": 1, "ids": ["bdsa-00r"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "updated": 1,
 "updated_issues": [{"changes": "status open → in_progress, metadata", "id": "bdsa-00r"}]}
```

The write landed on the stored issue —

```json
{"id":"bdsa-00r","title":"edited in A (hop 1)","status":"in_progress",
 "updated_at":"2026-08-13T15:11:32Z","metadata":{"cn_dot":"A:8"}}
```

— and B's journal for it is *unchanged*, still exactly one `created` row:

```json
{"rows": [{"event_type":"created","id":"019ffbad-282f-71e8-bf83-c41316fac150", ...}]}
```

Control, same workspace, same issue, a local edit instead of an import:

```bash
bd -C "$B" --sandbox update "$X" --priority 3
bash scripts/spike/bds0/rig.sh journal B "$X" | jq -c '.rows | map(.event_type)'
```

```json
["created","updated"]
```

So the asymmetry is `bd import`'s, not the row's: local mutation journals,
imported mutation does not. A projector watching B's `events` table sees an
imported create (payload-free) and sees imported updates **not at all**.

Answer to the claim as posed: a projector reading only the journal cannot
recognise its own echo. The journal row does give `issue_id`, so a projector
could join back to `issues.metadata` to read `cn_dot` — but that is a
current-state read outside the journal, not the journal record, and it is not
point-in-time: by the time the projector reads it, the row's `cn_dot` is
whatever the latest write left, not the value belonging to the event.

One further surface, recorded as an observation and not pursued here: the
embedded Dolt database commits every import, so `dolt log` in
`<ws>/.beads/embeddeddolt/<prefix>/` does distinguish imported writes from
local ones by commit message —

```
5fi7hda... bd: update (auto-commit) by MacBoo [bdsa-00r]
875bmli... bd import: 1 issues from stdin
pg7v8l2... bd import: 1 issues from stdin
```

— but the message names neither the issue nor the origin replica, and this is
the Dolt layer, not a `bd` surface. Whether it is a usable seam is verdict
material, not a sub-check result.

## Sub-check (iii) — re-export: **PASS**

```bash
bd -C "$B" --sandbox export | jq -c --arg x "$X" 'select(.id==$x)'
```

```json
{"_type":"issue","id":"bdsa-00r","title":"edited in A (hop 1)","status":"open","priority":2,
 "issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T15:10:20Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T15:10:54Z","metadata":{"cn_dot":"A:7"},
 "dependency_count":0,"dependent_count":0,"comment_count":0}
```

`cn_dot` is still `A:7` after the round trip, unchanged and unrewritten;
`updated_at` is still A's. Nothing on the export path touches the stamp.

## An adjacent result the claim depends on: a stamp needs a newer `updated_at`

Re-hopping the same row with a *new* dot and no other change does not land the
dot, because the LWW comparison ties:

```bash
bash scripts/spike/bds0/rig.sh hop A B --dot "A:10" "$X"
```

```json
{"created": 1, "ids": ["bdsa-00r"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "tie_kept_local_ids": ["bdsa-00r"]}
```

B's stored `metadata.cn_dot` stays at `A:8`. So provenance cannot be stamped
onto an already-replicated row as a separate step; it only travels attached to
a write that also wins on `updated_at`. (Ordering authority itself is claim
(b)'s subject; this is only the part claim (a) trips over.)

## The two-events observation, reproduced and explained

`computenet-8kj.1`'s reviewer observed, without investigating, that "a
mutate-then-hop left two events on B's imported row". Investigated here:

**Not reproducible for a plain row.** Mutate-then-hop of the plain seeded task
leaves exactly **one** event on B (`created`, transcript above), and the
second mutate-then-hop of the same row adds **zero** more. No repetition of
that sequence produced two events on that row.

**Reproducible, and expected, for a row carrying a label.** Hopping the seed
that has a label plus a comment:

```bash
L=$(bd -C "$A" --sandbox list --json \
    | jq -r '[.[]|select(.title|startswith("Seeded task with label"))][0].id')   # bdsa-gwh
bash scripts/spike/bds0/rig.sh hop A B --dot "A:9" "$L"
bash scripts/spike/bds0/rig.sh journal B "$L"
```

```json
{"rows": [
 {"actor":"MacBoo","created_at":"2026-08-13 17:11:55","event_type":"created",
  "id":"019ffbae-0b1b-73dc-ab93-a192de244f23","issue_id":"bdsa-gwh","new_value":"","old_value":""},
 {"actor":"MacBoo","comment":"Added label: seed-demo","created_at":"2026-08-13 17:11:55",
  "event_type":"label_added","id":"019ffbae-0b1c-7700-9df2-e16ee033ac75","issue_id":"bdsa-gwh"}]}
```

That is the two events: **one `created` for the issue upsert plus one
`label_added` per label carried on the row**, both written in the same second
by the same import. It is not double-journaling of the mutation, and not a
create-then-update pair. The same shape appears on a locally created labelled
issue (B's own seed `bdsb-hcy` has `created` + `label_added`), so import is
reproducing the ordinary local shape, not adding anything.

The imported comment produced **no** event (`comment_count` is 1 on B's copy,
and there is no comment event row), so comments are not a third event.

Caveat, stated rather than glossed: the reviewer's original run was not
preserved, so this is an explanation of a reproduction, not of that exact run.
The most likely path to their observation is a hop that selected a labelled
row (a hop with no id arguments replicates every exported row). What could
*not* be reproduced under any sequence tried here is two events from a plain
mutate-then-hop.

**What it means for echo counting.** The event count per replication hop is
not 1 and not stable: it is 1 for a create, 1 + (number of labels) for a
labelled create, and **0** for an update. So a projector cannot count journal
events to count replicated writes, and — given sub-check (ii) — cannot read
provenance off them either.

## What did not work

- `bd events` — no such command in bd 1.1.2.
- `bd sql -q ...` — `'bd sql' is not yet supported in embedded mode`. Both are
  why the journal is read via the `dolt` CLI against `events`.
- Reading `cn_dot` off any journal row: the `events` table has no metadata
  column, and the `old_value`/`new_value` blobs (empty for imports, scalar
  columns only for local edits) never contain it.
- Observing an imported update in the journal at all: `bd import` updates the
  stored issue and writes no event, while `bd update` on the same row in the
  same workspace does.
- Stamping a new `cn_dot` onto an already-replicated row without also
  advancing `updated_at`: the import ties and keeps B's copy
  (`tie_kept_local_ids`).
- Reproducing "two events" from a plain mutate-then-hop; it reproduces only
  for rows carrying labels, as `created` + `label_added`.

Surprises worth recording, observed but not diagnosed here:

- `bd import`'s report prints `"created": 1` on every hop in this run,
  including the pure tie (alongside `tie_kept_local_ids`) and the pure update
  (alongside `"updated": 1`). The counter does not appear to mean "issues
  created"; the `updated_issues[].changes` string ("status open → in_progress,
  metadata") is the field that actually described what happened.
- `updated_issues[].changes` names `metadata` as a changed field, i.e. the
  import report *does* notice provenance moved — on stdout, not in the
  journal.
