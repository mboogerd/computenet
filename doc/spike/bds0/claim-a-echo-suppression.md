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

Everything below was executed. The whole sequence was re-run end to end on
independent rig roots and produced the same outcomes (issue ids differ per
init — they are `mktemp`-fresh workspaces, so substitute your own).

**Execution order — the prose order is not the run order.** Each block is
paste-able against a fresh `rig.sh init`, but only from the state its
predecessor left, and two blocks are presented out of sequence because they
read better where the argument needs them. Every result here was produced by
this one linear sequence:

1. `init`, pick `X`, mutate in A, `sleep 1`, hop 1 `--dot A:7` (a create)
2. sub-check (i): `bd show` in B
3. sub-check (ii): `describe events`, `journal B`, `journal A` for contrast
4. **sub-check (iii)**, the re-export — presented after the whole of sub-check
   (ii), but run *here*, which is why its transcript still shows
   `"status":"open"` and `cn_dot` `A:7`
5. hop 2: mutate in A, `sleep 1`, hop `--dot A:8` (an update); `journal B` again
6. **the tie block** (`--dot A:10`, no mutation) — presented last, under
   "An adjacent result", but run *here*
7. the local-edit **control** (`bd update --priority 3` in B) — presented inside
   sub-check (ii), but run *last*, because it makes B's row locally newer than
   A's and so changes what steps 4 and 6 observe

Steps 4, 6 and 7 are order-sensitive, and each of those blocks below repeats
its precondition. Running the control (7) before the tie (6) turns the tie into
a *stale* skip — measured:
`{"created": 0, "skipped": 1, "stale_skipped_ids": ["<id>"]}` — which is a
different result about a different thing. Running it before the re-export (4)
leaves `priority: 3` and B's own `updated_at` in the exported row.

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

Control, same workspace, same issue, a local edit instead of an import.
**Precondition: run this last** (step 7 of the execution order above) — it
gives B's row an `updated_at` newer than A's, which turns the later tie block
into a stale skip and adds `priority: 3` to the re-export:

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

**Precondition: run immediately after hop 1** (step 4 above), before hop 2 and
before the local control — that is the state this transcript records.

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
dot, because the LWW comparison ties. **Precondition: run immediately after
hop 2** (step 6 above), with no local write in B intervening — a local `bd
update` in B first makes B strictly newer, and the same command then reports a
*stale* skip (`{"created": 0, "skipped": 1, "stale_skipped_ids": [...]}`)
rather than a tie:

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

- `bd import`'s report prints `"created": 1` on every hop in this run that the
  import considered, including the pure tie (alongside `tie_kept_local_ids`)
  and the pure update (alongside `"updated": 1`). The counter does not appear
  to mean "issues created"; the `updated_issues[].changes` string ("status
  open → in_progress, metadata") is the field that actually described what
  happened. The one shape that does report `"created": 0` is a *stale* hop —
  B's row strictly newer than A's — which reports
  `{"created": 0, "skipped": 1, "stale_skipped_ids": [...]}`. So "created" is
  really "rows the import took as candidates", and the tie/stale/updated key
  next to it is what says the outcome.
- `updated_issues[].changes` names `metadata` as a changed field, i.e. the
  import report *does* notice provenance moved — on stdout, not in the
  journal.

## Verdict and implications

**Claim (a) as posed fails.** Provenance is durable *state* and is not visible
at the *journal* seam. `metadata.cn_dot` survives the upsert byte-identically
(i) and survives re-export unrewritten (iii), but sub-check (ii) — "reappears
on the resulting journal record, so a projector can recognise its own echo"
(epic `computenet-8kj` §1(a)) — is false for two independent reasons, either
of which alone is fatal:

1. the `events` table has no metadata column, and neither `old_value` nor
   `new_value` ever carries one; and
2. **`bd import` writes no journal event at all for an update.** A local `bd
   update` on the same row in the same workspace does. There is nothing to
   read provenance *off*.

(2) is the load-bearing one. (1) alone would be a shape problem fixable by a
column; (2) means the seam does not fire.

The mechanism BDS1 (`computenet-dqj`, scope addition 1) specifies —
"stamp `metadata.cn_dot` on replicated writes and **DROP any inbound journal
record carrying a `cn_dot` it already holds**" — is therefore not
implementable as written. There is no inbound journal record carrying a
`cn_dot`, and for the update case there is no inbound journal record.

### What this does to the amplification argument

The epic's motivating failure is an unbounded loop: "A's write imports into B,
B journals it as its own mutation, B mints a fresh dot, gossips it back…".
Measured against the rig, that argument has to be restated per event class,
and the restatement is worse news than the original, not better:

- **Imported updates: no loop, because there is no signal.** B journals
  nothing, so a journal-only projector mints nothing — and also *learns
  nothing*. The row silently diverges from the mirror. Termination here is
  bought with blindness, not with suppression.
- **Imported creates: the loop premise stands, unsuppressed.** The import does
  write a `created` row, so the projector is woken; the row carries `issue_id`
  and nothing else, so nothing in the record distinguishes an echo from a
  local create. Left alone, this is exactly the mint-and-gossip-back cycle the
  epic names.
- **Labels: extra events, still no provenance.** An imported labelled create
  yields `created` + one `label_added` per label. Event count per replicated
  write is 1, 1+n, or 0, so echo accounting cannot be done by counting either.

So the honest one-line consequence: **a journal-only projector is neither
echo-safe nor complete under a write-back topology.** Both properties fail,
and the completeness failure is the one BDS1's own test plan would not catch —
its test ("mirror state equals `bd export` of the same workspace") drives the
workspace with local `bd` mutations only, where the journal *is* complete. Run
that test against a workspace whose writes arrive by import and it fails; run
it as written and it passes while proving nothing about the replicated path.

### Implications for BDS1 (`computenet-dqj`)

Named, not adjudicated — adjudication belongs to `computenet-8kj.6`.

1. **Scope addition 1 must be rewritten before code.** Its suppression rule
   cites a field that does not exist on the record it reads. The *stamping*
   half survives intact (state carries `cn_dot` faithfully); the *dropping*
   half needs a different reader.
2. **"Beads is the sole dot minter" is not what breaks — the feed is.** The
   description's dot-identity mapping (journal `(replicaId, seq)` →
   `Timestamp(sourceId, counter)`) is untouched by this finding; it is the
   assumption that the journal observes *every* mutation that fails.
3. **The declared read path is unverified and partly contradicted.** BDS1
   names `GET /v0/beads/events:watch` plus `internal/eventsjournal/record.go`
   as its feed. In bd 1.1.2 as installed here there is no `bd events` command
   at all (`bd --help` lists none) and `bd sql` is unsupported in embedded
   mode. Whether the HTTP endpoint exists in this build was **not tested** —
   this section only establishes that no CLI journal reader exists and that
   the on-disk journal is the `events` table read via the `dolt` CLI. If the
   endpoint does exist, it is presumably a projection of that same table,
   which is the shape shown above to carry no metadata — an inference, not a
   measurement. Either way, BDS1 must verify its feed before building on it.
4. **Re-baseline interacts with this.** BDS1 handles
   `events_journal_truncated` by re-baselining from `bd export`. Since
   `bd export` *does* carry `cn_dot` (sub-check (iii)), re-baseline is the one
   path on which the projector reliably sees provenance — but it sees it as
   current state for the whole workspace, not as a change feed.

### Implications for BDS4 (`computenet-6wc`)

1. **The write half is unaffected.** BDS4 §1 (content replication through
   `bd import`, close as the removal interface) needs the store to accept and
   retain provenance, and it does. Nothing here argues against `bd import` as
   the *write* seam.
2. **The read-back half is what BDS0 was protecting, and it is unsound as
   specified.** BDS4 is bidirectional; it needs B's projector to observe B's
   store and to tell peer-originated content from B-originated content. Both
   depend on a feed that this section shows the `events` table is not.
3. **Provenance cannot be applied as a separate marking pass.** A hop that
   carries a new `cn_dot` and nothing else ties on `updated_at` and is
   discarded (`tie_kept_local_ids`). A stamp only travels attached to a write
   that also wins LWW. So any design that "marks a row as already-replicated"
   after the fact is closed off. Whether `--allow-stale` reopens it is claim
   (b)'s subject (`computenet-8kj.3`); this section only records that the
   plain path does not.
4. **The lease plane (§2) is untouched.** It is ephemeral, gossiped, and never
   goes through beads at all.
5. **The two §3 hazards (epic breakdown, auto-merge) are untouched.** They turn
   on idempotency of `bd create` and on lease re-checks, not on journal
   provenance.

### The alternative seam

Per the epic's rule for a failed claim (a). Marked **VERIFIED ON THE RIG** or
**PROPOSED, UNTESTED** — the distinction is the point.

#### Seam 1 — the Dolt commit graph, via `dolt_diff_issues`. **VERIFIED ON THE RIG.**

The `events` table is not the workspace's only history. Every `bd import`
produces its own Dolt commit, and Dolt's diff system tables expose, per
commit, the before/after rows **including `metadata`**. This is a
point-in-time change feed with provenance — precisely what sub-check (ii)
looked for and did not find in `events`.

Measured on a fresh rig (`init`; mutate `$X` in A; `sleep 1`; hop
`--dot A:7` — a create; mutate again; `sleep 1`; hop `--dot A:8` — an update,
the one that journals nothing):

```bash
cd "$B/.beads/embeddeddolt"/*/
dolt sql -r json -q "select to_id, diff_type, from_metadata, to_metadata, to_commit
                     from dolt_diff_issues
                     where to_commit in ('h493b8g7...','5ol7rs8t...')"
```

```json
{"rows": [
 {"diff_type":"modified","to_id":"bdsa-trn","to_commit":"5ol7rs8t...",
  "from_metadata":{"cn_dot":"A:7"},"to_metadata":{"cn_dot":"A:8"},
  "from_updated_at":"2026-08-13 15:29:00","to_updated_at":"2026-08-13 15:29:20"},
 {"diff_type":"added","to_id":"bdsa-trn","to_commit":"h493b8g7...",
  "to_metadata":{"cn_dot":"A:7"},"to_updated_at":"2026-08-13 15:29:00"}]}
```

Both hops are present — including the imported **update** that left zero rows
in `events` — each with `diff_type`, the full prior and posterior row, and
`cn_dot` on both sides. A projector reading this can compare `to_metadata.cn_dot`
against the dot it just wrote and drop its own echo, which is exactly the
operation BDS1 specifies.

Checkpointing works the same way, which is what BDS1 needs for resume. Also
verified:

```bash
dolt sql -r json -q "select to_id, diff_type, from_metadata, to_metadata, to_commit
                     from dolt_diff_issues
                     where to_commit_date > '2026-08-13 15:29:10' order by to_commit_date"
```

```json
{"rows": [{"diff_type":"modified","to_id":"bdsa-trn","to_commit":"5ol7rs8t...",
           "from_metadata":{"cn_dot":"A:7"},"to_metadata":{"cn_dot":"A:8"}}]}
```

— the checkpoint returns exactly the writes after it. (`dolt_log` timestamps
are UTC; the same query with a local-time boundary returns nothing, which is
how this was first got wrong.) `dolt log` also labels the provenance of the
commit itself: `bd import: 1 issues from stdin` versus
`bd: update (auto-commit) by MacBoo [bdsa-trn]`.

Risks to record, none of them measured away here:

- **It is not a `bd` surface.** Beads declares `internal/eventsjournal/record.go`
  stable for external consumers; `dolt_diff_*` is the storage layer underneath
  bd, with no such promise. A beads schema change breaks the projector
  silently. This is the main cost of the seam.
- **Commit granularity is a configuration, not a guarantee.** `bd --help`
  documents `--dolt-auto-commit off|on|batch`, where `batch` "defer[s] commits
  to `bd dolt commit`". Under batching, several mutations coalesce into one
  commit and the feed coarsens. Not tested — inferred from the flag's
  documented semantics. A projector on this seam must pin or verify the policy.
- **History compaction is this seam's `events_journal_truncated`.** `bd
  compact`, `bd gc` and `bd flatten` squash Dolt history by design. The
  re-baseline-from-`bd export` handling BDS1 already requires maps onto it, but
  the trigger to detect is a missing checkpoint commit, not a typed event. Not
  tested.
- **No watch.** This is polling `dolt_log` for new commits; nothing here
  established a push/notify path. `bd sql` is unusable in embedded mode, so
  access is the `dolt` CLI or a Dolt SQL connection against
  `<ws>/.beads/embeddeddolt/<prefix>/`.

#### Seam 2 — `bd history`, and the one-line beads patch it suggests. **PARTLY VERIFIED.**

**Verified:** `bd history <id> --json` *does* surface imported updates that
`events` misses — after the two hops above it returns two entries, one per
import commit, each with the issue as of that commit:

```json
{"CommitHash":"5ol7rs8t...","status":"in_progress","updated_at":"2026-08-13T15:29:20Z","metadata":null}
{"CommitHash":"h493b8g7...","status":"open","updated_at":"2026-08-13T15:29:00Z","metadata":null}
```

**Verified:** `metadata` is `null` in that projection while the underlying
commit *does* hold it — `select metadata from issues as of 'h493b8g7...'`
returns `{"cn_dot":"A:7"}`, and the later commit returns `{"cn_dot":"A:8"}`.
So bd's own history view drops provenance that its storage retains.

**Proposed, untested:** including `metadata` in the `bd history` projection is
therefore a small, additive beads patch over data that already exists, and it
would make Seam 1 reachable through a supported `bd` surface. `bd history` is
per-issue, so it is a verification and reconciliation instrument, not a feed;
turning it into one would additionally need an all-issues/since-checkpoint
form. Both are upstream changes and neither was attempted here.

#### Seam 3 — suppress in the writer, not the reader. **PROPOSED, UNTESTED.**

The process that would read the echo is the same process that wrote it:
ComputeNet runs `bd import` itself. It therefore already knows every
`(issue_id, cn_dot)` it applied, without reading anything back — and
`bd import`'s stdout confirms the write landed and that metadata moved
(`updated_issues[].changes` = `"status open → in_progress, metadata"`,
**verified**, recorded in the sub-check (ii) transcript). Keeping those pairs
in a writer-side processed-frontier and dropping the matching journal/diff
record on arrival needs no beads change at all.

Untested, and with two known gaps: it does not survive a projector restart
unless the frontier is persisted (BDS4 §3 already routes idempotency through
the KFX processed-frontier seam, `computenet-yh6.1`), and it says nothing
about writes made by a *third* party — a human running `bd update` by hand
between hops. It is complementary to Seam 1, not a substitute.

#### Seam 4 — a dedicated bd verb for replicated writes. **PROPOSED, UNTESTED.**

The epic's own suggestion (`bd apply --replicated`, or `bd import
--journal-provenance`). To fix the failure measured here it would have to do
both things `bd import` does not: emit a journal event for an update, and
carry the provenance onto that event. That is a strictly larger upstream
change than Seam 2's projection fix, and it is the only option that leaves the
`events` table as BDS1's feed. Nothing here tested it.

#### Insufficient, recorded so it is not re-proposed

- **Join `events.issue_id` back to `issues.metadata`.** The natural repair, and
  it fails twice: it is a current-state read, not point-in-time (by the time
  the projector reads, `cn_dot` is whatever the newest write left), and for
  imported updates there is no journal row to join *from*.
- **`dolt log` commit messages alone.** They do distinguish
  `bd import: …` from `bd: update (auto-commit) …`, but name neither the issue
  (on imports) nor the origin replica. They are a coarse filter, not
  provenance. Seam 1 uses the commit *graph*, not its messages.
- **Counting events.** 1 per create, 1+n for a labelled create, 0 per update.

### Recommendation carried to `computenet-8kj.6`

Claim (a) is a **fail with a verified fallback**, not a dead end: the BDS line
does not need re-founding, but BDS1's feed does need re-selecting before code
is written. The narrow reading — "is `metadata.cn_dot` readable at the journal
seam" — is answered no. The wide reading — "can a projector on this machine
recognise its own replicated write" — is answered yes, via Seam 1, at the cost
of depending on beads' storage layer rather than its declared-stable journal
contract. Whether that cost is acceptable, and whether Seam 2 should be
attempted upstream first, is `8kj.6`'s verdict to make, not this section's.

### What would overturn this verdict

Each of these is a single command away, and any one of them changes the
conclusion:

- **A journal event for an imported update, on any bd build.** Re-run the hop-2
  transcript and see `events` grow. That would restore claim (a)'s premise
  outright for the update case; the metadata-column failure would remain.
- **An `events` schema with a metadata (or metadata-bearing `new_value`)
  column** in a later beads version. `describe events` is the check.
- **A live `/v0/beads/events:watch` that carries more than the `events` table.**
  Untested here; if its record envelope carries metadata *and* imports emit
  records, BDS1's declared feed stands as written and Seam 1 is unnecessary.
- **`dolt_diff_issues` not populated under the real workspace's auto-commit
  policy**, or coalescing several logical writes into one commit. That would
  demote Seam 1 from verified to conditional, and it is the risk most likely to
  bite, since it was not tested outside the rig's default configuration.
- **A same-second write ordering that changes the tie result.** The 1s
  `updated_at` resolution is what makes provenance untravelable on its own;
  a finer-grained comparison in a later bd would change that adjacent finding.

### What did not work, in this subsection

- Filtering `dolt_diff_issues` by `to_commit_date` with a **local-time**
  boundary returns `{}` — `dolt_log`/`dolt_diff_*` timestamps are UTC while
  the rig's shell prints local time. A checkpointing projector that gets this
  wrong silently sees an empty feed rather than an error.
- `bd history --json` looks like the answer and is not: it reaches the
  imported update, but projects `metadata` as `null`.
