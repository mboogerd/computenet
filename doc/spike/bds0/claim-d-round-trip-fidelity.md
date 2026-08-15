# BDS0 claim (d) — round-trip fidelity: export → import → export

Epic `computenet-8kj` §1(d). The claim under test: labels, comments,
dependencies, metadata (nested JSON), priority and timestamps survive
`export → import → export` byte-identically modulo known-volatile fields, and
anything that does not is named.

Result in one line: **PASS, with zero named fidelity losses.** Every
replication-relevant field on a maximally-populated fixture — two labels,
three comments from two distinct authors, a `blocks` dependency, nested
metadata, priority 1, and a separately-closed issue with `closed_at` /
`close_reason` — round-tripped byte-identical on both a rig-stamped hop and an
unstamped plain `export | import` control, reproduced on a second, independent
rig root. The only field that ever differs between the A-side export and the
B-side re-export is `metadata.cn_dot`, and that difference is the rig's own
deliberate provenance stamp (claim (a)'s subject), not an artifact of the
round trip — the unstamped control proves this by producing a byte-identical
copy with no stamp at all. One anomaly was chased per the task's own
instruction and resolved: dependency `created_at` is off by exactly the local
UTC offset, a local-vs-UTC storage bug in `bd` 1.1.2, and it round-trips
*consistently* wrong rather than being further corrupted by the trip itself.

Everything below was executed. The whole sequence was re-run end to end on a
second, independently initialised rig root and produced the same outcomes
(issue ids differ per `init` — these are `mktemp`-fresh workspaces, so
substitute your own).

## Execution order — the prose order *is* the run order

1. `rig.sh smoke` (sanity check the rig itself), then `rig.sh init` (root 1)
2. Build the maximal fixture in workspace A: `Y` (a plain dependency target),
   `X` (two labels, three comments from two distinct actors, a `blocks`
   dependency on `Y`, nested `metadata`, priority 1), `Z` (created then
   closed with a reason)
3. Read `X`, `Y`, `Z` back from A's own export, before any hop — the baseline
   every comparison below is measured against
4. `sleep 1`, then the **rig path**: `rig.sh hop A B --dot A:3 X Y Z` (the dot
   equals `X`'s pre-existing `cn_dot`, so the stamp is idempotent for `X` and
   a fresh add for `Y`/`Z`, exactly as the task prescribes)
5. The **control path**, on the same root, into a third workspace `C` created
   directly with `bd init` (not through `rig.sh`, since `rig.sh init` only
   ever provisions `A` and `B`): `bd -C A --sandbox export | bd -C C
   --sandbox import -`, no stamping, no rig involvement at all
6. Re-export B and C, canonicalise every row with `jq -S -c`, and `diff`
   A-vs-B, A-vs-C, and B-vs-C
7. Chase the dependency `created_at` anomaly noted in the task description:
   compare it against the issue's own `updated_at` and against the local
   timezone offset
8. Repeat the rig hop a second time, unchanged inputs, to observe what an
   idempotent re-hop reports
9. The entire sequence (steps 1–7, fixture shapes identical down to the
   labels and comment texts) re-run on a second, independent rig root, to
   confirm the result is not a one-root fluke

Order sensitivity: step 5 must follow step 3 (the control reads A's export,
which must already contain the fixture) but is otherwise independent of step
4 — the two paths write to disjoint workspaces (`B` and `C`) so neither can
contaminate the other's result, which is exactly why a third workspace was
introduced rather than reusing `B` for the control.

Safety: every command below ran against a synthetic `mktemp -d` rig root via
`bd -C <ws> --sandbox`, `rig.sh` subcommands, or a manually-`bd init`'d
throwaway workspace under the same rig root. Nothing here read or wrote the
repository's live `.beads`.

## Environment, verified rather than assumed

```
$ cat "$BDS0_RIG_ROOT/bd-version.txt"
bd version 1.1.2 (Homebrew)
```

`bash scripts/spike/bds0/rig.sh smoke` passed before the run:

```
== rig.sh smoke: hop A B --dot 'A:1' bdsa-lzy ==
{"created": 1, "ids": ["bdsa-lzy"], "schema_version": 1, "skipped": 0, "source": "stdin"}
== rig.sh smoke: verify bdsa-lzy exists in B, stamped ==
== rig.sh smoke: journal B bdsa-lzy ==
{"rows": [{"actor":"MacBoo","created_at":"2026-08-15 09:27:34","event_type":"created",
           "id":"01a00451-a2a8-73fb-92d4-84f9de0f9ff0","issue_id":"bdsa-lzy",
           "new_value":"","old_value":""}]}
== rig.sh smoke: OK (1 journal event(s) for bdsa-lzy in B) ==
```

The flag surface this section depends on, read rather than assumed:

```
$ bd -C "$A" --sandbox import --help
Import issues from a JSONL file (newline-delimited JSON) into the database.
...
The importer accepts every field 'bd export' emits — see 'bd export' output
for the canonical schema. Only "title" is required; everything else is
optional.

$ bd -C "$A" --sandbox export --help
Export all issues to JSONL (newline-delimited JSON) format.
Each line is a complete JSON object representing one issue, including its
labels, dependencies, and comments.
```

So `bd`'s own documentation states the round-trip contract this section
tests: import accepts every field export emits.

Established by the sibling sections and relied on here rather than
re-derived: `bd import`'s LWW comparison is one-second wall-clock resolution
(claim (b)), so `sleep 1` appears between an A-side mutation and the hop that
carries it; and dependencies ride inline on the dependent's exported row, not
as a separate `_type` (claim (c)) — confirmed again below on `X`'s row.

Local timezone, read rather than assumed, needed for the anomaly chase in
step 7:

```
$ date; date +%z
Sat Aug 15 09:24:32 CEST 2026
+0200
```

## Setup — the maximal fixture

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"
A="$BDS0_RIG_ROOT/A"; B="$BDS0_RIG_ROOT/B"

Y=$(bd -C "$A" --sandbox create "Claim-d dependency target Y" --type task --silent)
X=$(bd -C "$A" --sandbox create "Claim-d maximal fixture X" --type task \
    --labels "alpha,beta" \
    --metadata '{"cn_dot":"A:3","extra":{"k":[1,2]}}' \
    --priority 1 --silent)
bd -C "$A" --sandbox dep add "$X" --blocked-by "$Y"
bd -C "$A" --sandbox --actor alice comment "$X" "first comment, actor alice"
bd -C "$A" --sandbox --actor bob   comment "$X" "second comment, actor bob"
bd -C "$A" --sandbox --actor alice comment "$X" "third comment, actor alice again"

Z=$(bd -C "$A" --sandbox create "Claim-d closed fixture Z" --type task --silent)
bd -C "$A" --sandbox close "$Z" --reason "closed for claim-d fixture"
```

```
Y=bdsa-qxw X=bdsa-szy Z=bdsa-bwg
✓ Added dependency: bdsa-szy (Claim-d maximal fixture X) depends on bdsa-qxw (Claim-d dependency target Y) (blocks)
✓ Comment added to bdsa-szy — Claim-d maximal fixture X   (×3)
✓ Closed bdsa-bwg — Claim-d closed fixture Z: closed for claim-d fixture
```

**Distinct comment authors are expressible** — the task description hedged
that this might not be true if `bd` derives the author from the actor. It
does derive the author from the actor, and `bd`'s own global `--actor` flag
lets a single script express it per call: `alice` and `bob` above are not
placeholders, they are what `bd -C "$A" --sandbox export` reports as each
comment's `author`, verified below.

A's export of the three fixture rows, before any hop — the baseline:

```json
{"_type":"issue","id":"bdsa-szy","title":"Claim-d maximal fixture X","status":"open",
 "priority":1,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-15T07:23:06Z","created_by":"MacBoo","updated_at":"2026-08-15T07:23:06Z",
 "metadata":{"extra":{"k":[1,2]},"cn_dot":"A:3"},"labels":["alpha","beta"],
 "dependencies":[{"issue_id":"bdsa-szy","depends_on_id":"bdsa-qxw","type":"blocks",
                  "created_at":"2026-08-15T09:23:06Z","created_by":"MacBoo","metadata":"{}"}],
 "comments":[
   {"id":"01a0044d-925f-75c0-aa59-8aad4d23606a","issue_id":"bdsa-szy","author":"alice",
    "text":"first comment, actor alice","created_at":"2026-08-15T07:23:08Z"},
   {"id":"01a0044d-9607-772b-b93d-f26d482be9dc","issue_id":"bdsa-szy","author":"bob",
    "text":"second comment, actor bob","created_at":"2026-08-15T07:23:09Z"},
   {"id":"01a0044d-9960-71f4-82c5-92eef9af7b4f","issue_id":"bdsa-szy","author":"alice",
    "text":"third comment, actor alice again","created_at":"2026-08-15T07:23:10Z"}],
 "dependency_count":1,"dependent_count":0,"comment_count":3}
{"_type":"issue","id":"bdsa-bwg","title":"Claim-d closed fixture Z","status":"closed",
 "priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-15T07:23:11Z","created_by":"MacBoo","updated_at":"2026-08-15T07:23:12Z",
 "closed_at":"2026-08-15T07:23:12Z","close_reason":"closed for claim-d fixture",
 "dependency_count":0,"dependent_count":0,"comment_count":0}
{"_type":"issue","id":"bdsa-qxw","title":"Claim-d dependency target Y","status":"open",
 "priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-15T07:23:04Z","created_by":"MacBoo","updated_at":"2026-08-15T07:23:04Z",
 "dependency_count":0,"dependent_count":1,"comment_count":0}
```

`Y`, which carries no labels, comments or dependencies, simply omits those
keys — export does not emit empty arrays for them. That is noted, not
flagged: no test below asks for an empty label/comment/dependency list to
survive, only a populated one, and `X`'s populated arrays are what the rest
of this section tracks.

## Comparison — canonical per-row `jq -S` diff

Per the feature's decided assumption, every comparison below is `jq -S -c`
(sorted keys, compact) piped to `diff`, one comparison text file per
workspace, sorted by row so JSONL line order is never itself a finding.

### The rig path — `hop A B --dot A:3`

```bash
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:3" "$X" "$Y" "$Z"
```

```json
{"created": 3, "ids": ["bdsa-szy","bdsa-bwg","bdsa-qxw"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

```bash
bd -C "$A" --sandbox export | jq -S -c --arg x "$X" --arg y "$Y" --arg z "$Z" \
  'select(.id==$x or .id==$y or .id==$z)' | sort > a_export.jsonl
bd -C "$B" --sandbox export | jq -S -c --arg x "$X" --arg y "$Y" --arg z "$Z" \
  'select(.id==$x or .id==$y or .id==$z)' | sort > b_export.jsonl
diff a_export.jsonl b_export.jsonl; echo "exit=$?"
```

```diff
1,2c1,2
< {"_type":"issue",...,"id":"bdsa-bwg",...,"owner":"mlboogerd@gmail.com","priority":2,...}
< {"_type":"issue",...,"id":"bdsa-qxw",...,"owner":"mlboogerd@gmail.com","priority":2,...}
---
> {"_type":"issue",...,"id":"bdsa-bwg",...,"metadata":{"cn_dot":"A:3"},"owner":"mlboogerd@gmail.com",...}
> {"_type":"issue",...,"id":"bdsa-qxw",...,"metadata":{"cn_dot":"A:3"},"owner":"mlboogerd@gmail.com",...}
exit=1
```

(Elided with `...` here for width; the full untruncated lines are in the
Setup section above and reappear verbatim below.) The diff has exactly two
hunks, both on `Y` and `Z`, both adding `"metadata":{"cn_dot":"A:3"}` — the
rig's own stamp, added because `Y` and `Z` had no prior `metadata` at all.
**`X`'s row produces zero diff**: it already carried `cn_dot:"A:3"` before the
hop, so the stamp is idempotent there and every other field — labels, all
three comments with their original ids and authors, the dependency, the
nested `extra.k` array, priority, every timestamp — is untouched.

### The control path — plain `export | import`, no stamping, no rig

A third workspace, `C`, created directly (not through `rig.sh`, which only
provisions `A`/`B`) so the control cannot share any state with the rig path:

```bash
C="$BDS0_RIG_ROOT/C"
mkdir -p "$C"
(cd "$C" && BD_NON_INTERACTIVE=1 bd init --prefix bdsc --skip-agents --skip-hooks)
bd -C "$A" --sandbox export | bd -C "$C" --sandbox import - --json
```

```json
{"created": 6, "ids": ["bdsa-szy","bdsa-bwg","bdsa-qxw","bdsa-3ga","bdsa-a99","bdsa-e9i"],
 "schema_version": 1, "skipped": 0, "source": "stdin"}
```

(The other three ids are `A`'s three `rig.sh init` seed issues, carried along
because this is a whole-workspace export, not a filtered one — expected, and
irrelevant to the fixture comparison below.)

```bash
bd -C "$C" --sandbox export | jq -S -c --arg x "$X" --arg y "$Y" --arg z "$Z" \
  'select(.id==$x or .id==$y or .id==$z)' | sort > c_export.jsonl
diff a_export.jsonl c_export.jsonl; echo "exit=$?"
```

```
exit=0
```

**Zero diff.** `A`'s export and `C`'s re-export of `X`, `Y` and `Z` are
byte-identical, sorted-key JSON, with no stamp of any kind involved. This is
the control the task asked for, and it settles the question the rig path
alone could not: the `metadata.cn_dot` difference seen on the rig path is
entirely the rig's own doing, not a round-trip artifact — with no stamping at
all, `metadata` (including the pre-existing `extra.k` nested array on `X`)
round-trips unchanged, and so does every other field.

A third diff, `B` vs `C`, isolates exactly the rig-induced delta and confirms
it is nothing but the stamp:

```bash
diff b_export.jsonl c_export.jsonl; echo "exit=$?"
```

```diff
1,2c1,2
< {"_type":"issue",...,"id":"bdsa-bwg",...,"metadata":{"cn_dot":"A:3"},...}
< {"_type":"issue",...,"id":"bdsa-qxw",...,"metadata":{"cn_dot":"A:3"},...}
---
> {"_type":"issue",...,"id":"bdsa-bwg",...,"owner":"mlboogerd@gmail.com",...}
> {"_type":"issue",...,"id":"bdsa-qxw",...,"owner":"mlboogerd@gmail.com",...}
exit=1
```

Same two hunks, same single field, mirrored — B has the stamp, C does not,
nothing else differs.

## Field-level classification

Per row (`X`, `Y`, `Z`), every field either compared identical on both paths
or is explained below. There is exactly one differing field across all three
issues and both paths, and it is classified known-volatile, not a fidelity
loss:

| Field | Rig path (A vs B) | Control path (A vs C) | Classification |
|---|---|---|---|
| `title`, `status`, `priority`, `issue_type`, `owner` | identical | identical | verified fidelity |
| `created_at`, `updated_at` | identical | identical | verified fidelity — **no import-side rewrite observed**, contrary to a plausible prior that import stamps its own time |
| `closed_at`, `close_reason` (on `Z`) | identical | identical | verified fidelity |
| `labels` (on `X`: `["alpha","beta"]`) | identical | identical | verified fidelity |
| `comments[]` — `id`, `author`, `text`, `created_at` (on `X`, three rows, two distinct authors) | identical, including original comment `id`s | identical | verified fidelity — comment ids are **not** re-minted on import |
| `dependencies[]` — `issue_id`, `depends_on_id`, `type`, `created_at`, `created_by` (on `X`) | identical | identical | verified fidelity |
| `dependencies[].metadata` | identical string `"{}"` | identical string `"{}"` | **noted quirk, not a loss**: it is a JSON string, not a nested object, on every export this section produced — round-trips unchanged either way |
| `metadata` (nested object, `extra.k:[1,2]`, on `X`) | identical | identical | verified fidelity |
| `metadata.cn_dot` (on `Y`, `Z`, which had none before) | **B gains it, A does not** | absent on both | **known-volatile — rig-injected, not round-trip-induced.** The control path proves the round trip itself does not add or alter `metadata`; the diff exists only because the rig's `hop` deliberately stamps every selected row, which is exactly claim (a)'s mechanism working as designed, not a fidelity gap in `bd import`/`export` |
| `dependency_count`, `dependent_count`, `comment_count` | identical | identical | verified fidelity |

**Zero fields are classified as a fidelity loss.**

## The dependency `created_at` anomaly, chased per the task's instruction

The task description flagged this from an earlier probe and asked to chase
it: `X`'s dependency `created_at` is `2026-08-15T09:23:06Z` while `X`'s own
`created_at`/`updated_at` are `2026-08-15T07:23:06Z` — same action, same
second, 2 hours apart.

```bash
jq -r --arg x "$X" 'select(.id==$x) | {issue_updated: .updated_at, dep_created: .dependencies[0].created_at}' a_export.jsonl
```

```json
{"issue_updated":"2026-08-15T07:23:06Z","dep_created":"2026-08-15T09:23:06Z"}
```

```bash
date +%z
```

```
+0200
```

**Confirmed local-vs-UTC storage bug, not a round-trip artifact.** The
offset is exactly the local timezone (CEST, UTC+2): the dependency's
`created_at` is local wall-clock time with a `Z` suffix appended as though it
were UTC, while the issue's own `created_at`/`updated_at` are correctly
UTC. It is wrong **at the source**, in `A`'s own export, before any hop — and
it survives both the rig path and the control path **byte-identically wrong**
(`09:23:06Z` in `A`, `B` and `C` alike; reconfirmed at
`09:25:59Z`/`07:25:59Z` on the second rig root below). So the round trip does
not introduce or compound this defect; it faithfully carries a value that was
already incorrect when exported. This is named as a `bd` defect for the
consolidated doc (`computenet-8kj.6`), not as a round-trip fidelity loss —
nothing in claim (d)'s test asked whether the *source* timestamp was correct,
only whether it survives the trip unchanged, and it does.

## Repeated hop — idempotent re-stamp, all three rows tie

Run immediately after the rig-path hop above, same ids, same `--dot`:

```bash
bash scripts/spike/bds0/rig.sh hop A B --dot "A:3" "$X" "$Y" "$Z"
```

```json
{"created": 3, "ids": ["bdsa-szy","bdsa-bwg","bdsa-qxw"], "schema_version": 1,
 "skipped": 0, "source": "stdin",
 "tie_kept_local_ids": ["bdsa-szy","bdsa-bwg","bdsa-qxw"]}
```

Consistent with claim (b)'s finding: identical content at the same
one-second resolution ties, and B's already-landed copy is kept, not
re-applied. Not a fidelity question — recorded because it confirms nothing in
this section accidentally depended on running the hop exactly once.

## Reproduction on an independent rig root

The entire sequence — fixture creation with the same shapes, labels, comment
texts and actors, the rig-path hop, and the unstamped control — was re-run
end to end on a second, independently initialised rig root, `bd version
1.1.2 (Homebrew)`. Outcomes:

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"   # root 2
# ... identical fixture-creation commands, X2/Y2/Z2 in place of X/Y/Z ...
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:3" "$X2" "$Y2" "$Z2"
```

```json
{"created": 3, "ids": ["bdsa-ah2","bdsa-8ri","bdsa-72g"], "schema_version": 1,
 "skipped": 0, "source": "stdin"}
```

```bash
diff a2_export.jsonl b2_export.jsonl; echo "exit=$?"   # rig path
```

```diff
1,2c1,2
< {"_type":"issue",...,"id":"bdsa-8ri",...}
< {"_type":"issue",...,"id":"bdsa-72g",...}
---
> {"_type":"issue",...,"id":"bdsa-8ri",...,"metadata":{"cn_dot":"A:3"},...}
> {"_type":"issue",...,"id":"bdsa-72g",...,"metadata":{"cn_dot":"A:3"},...}
exit=1
```

Same shape: only `Y2`/`Z2` gain the stamp, `X2` diffs zero.

```bash
bd -C "$A2" --sandbox export | bd -C "$C2" --sandbox import - --json   # unstamped control
diff a2_export.jsonl c2_export.jsonl; echo "exit=$?"
```

```json
{"created": 6, "ids": ["bdsa-ah2","bdsa-8ri","bdsa-72g","bdsa-n3c","bdsa-3e2","bdsa-zw1"],
 "schema_version": 1, "skipped": 0, "source": "stdin"}
```

```
exit=0
```

Zero diff on the control path, same as root 1. And the anomaly reproduces
exactly, at the new second:

```json
{"issue_updated":"2026-08-15T07:25:59Z","dep_created":"2026-08-15T09:25:59Z"}
```

Identical on every measured dimension: the round trip is lossless on both
paths, the only cross-workspace difference is the rig's own stamp, and the
dependency `created_at` local/UTC offset is systematic (again exactly +2h,
matching `date +%z`), not a one-off clock glitch.

## What did not work

- **A same-workspace control could not be used.** Reusing `B` for the
  unstamped control after the rig hop would have compared an
  already-populated workspace against itself and proven nothing (the second
  import would simply tie). A third workspace (`C`), created by hand with
  `bd init` rather than through `rig.sh` (which only provisions `A`/`B`), was
  required to get an independent unstamped copy.
- **The dependency `created_at` anomaly could not be attributed to the round
  trip.** It is present in `A`'s own first export, before any hop, so
  claim (d)'s instruments (export-vs-re-export diffing) cannot localise which
  `bd` code path produces it — only that it is wrong at the source and stable
  under both round-trip paths. Diagnosing *where inside `bd`* the local time
  leaks into a UTC-labelled field needs `bd`'s source or a matching bug
  report, neither of which this section had reason to pursue.
- **No fidelity loss was found to name**, which is itself a limit worth
  stating plainly: the task's framing ("every differing field goes in a table
  as either known-volatile... or fidelity loss") anticipated losses to
  classify, and the maximal fixture — two labels, three comments from two
  authors, one dependency, nested metadata, priority, a full close — produced
  exactly one differing field across two independent paths and two
  independent rig roots, and that field is the test rig's own deliberate
  provenance stamp. A narrower or larger fixture (more dependency types,
  deeper metadata nesting, non-ASCII comment text, an issue with *no* owner)
  was not tried and could in principle surface something this fixture did
  not reach.
- **`bd import`'s upsert path for a row that already exists with different
  content was not exercised here** — every fixture row was net-new to both
  `B` and `C` before its respective import, so this section is a **create**
  round trip throughout. Claim (c)'s C1b (open→closed **update**) and claim
  (b)'s LWW sub-checks already cover the update shape for a subset of these
  fields (`status`, `closed_at`, `close_reason`, `metadata`); an update-shaped
  round trip covering labels, comments and dependencies together was not
  separately re-run here, since nothing observed on the create path suggests
  the update path would differ for fields claim (b)/(c) did not already
  touch, but that inference was not measured in this section.
