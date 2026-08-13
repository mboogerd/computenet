# BDS0 claim (c) — close replicates through `bd import`; hard delete does not

Epic `computenet-8kj` §1(c). The claim under test: `status`, `closed_at` and
`close_reason` are ordinary importable fields, so replicating a peer's close
needs no new `bd` surface — while *originating* a close goes through `bd
close`, where the guards fire. Replication applies an already-adjudicated
fact, so the guards must **not** re-fire there. Separately: `bd import` skips
`tombstone` rows and `bd` has no delete verb, so hard deletion is out of scope
for replication and close is the removal interface.

Four sub-checks, and the result in one line: **C1 pass, C2 pass (with one
narrowing), C3 pass — the load-bearing one — C4 pass on the mechanism but
*false on its stated premise*.**

- **C1** a close in A replicates into B with `closed_at` and `close_reason`
  byte-identical and no `bd close` invocation in B. **Pass**, on both the
  create shape and the update shape.
- **C2** `bd close` guards fire when originating. **Pass**, but the
  open-children guard is **epic-only**: a `task` parent with an open
  `parent-child` child closes without complaint. Neither error string names
  `ErrCloseBlocked` or `ErrCloseOpenChildren`; the observed text is recorded
  below.
- **C3** the same guards do **not** fire on the replication path. **Pass**,
  for both guard shapes, with the identical structure present locally in B
  and B's own `bd close` refusing the same close one command earlier.
- **C4** a `tombstone` row is dropped by `bd import`. **Pass** — and
  specifically dropped, not rejected: an unrecognised status (`banana`) fails
  validation loudly, while `tombstone` disappears silently. But the premise
  "`bd` has no delete verb" is **false**: `bd 1.1.2` has `bd delete`, a real
  hard delete. It leaves no tombstone row in the export at all, so the
  conclusion (hard delete does not replicate) survives for a different reason
  than the epic gives.

Everything below was executed. The whole sequence was re-run end to end on a
second, independently initialised rig root and produced the same outcomes
(issue ids differ per `init` — these are `mktemp`-fresh workspaces, so
substitute your own).

## Execution order — the prose order *is* the run order

Unlike claim (a), no block here is presented out of sequence. The linear
sequence that produced every transcript below, in this order:

1. `rig.sh smoke`, then `rig.sh init` (root 1); pick `X` = A's plain seed
2. **C1**: `bd close X` in A, `sleep 1`, hop `--dot A:1` (a *create* in B)
3. **C1b**: create `Y` in A, hop `--dot A:2` (create, open), close `Y` in A,
   hop `--dot A:3` (an *update* in B)
4. **C2**: create `P`(epic)/`K`(child) and `Q`/`R`(blocker) in A, wire deps,
   attempt both closes in A; then the epic-only probe (`P2`/`K2`)
5. **C3**: hop `P K Q R` into B `--dot A:4`; **control** — B's own `bd close`
   on `P` and `Q`; then import the forged close bundle unflagged
6. **C4a/b/c**: forged `tombstone` for a new id; forged `tombstone` for `Y`
   with a newer `updated_at`; the `banana` control
7. **C4d**: create `Z` in A, hop `--dot A:7`, `bd delete Z --force` in A,
   re-export, then a full unfiltered hop `--dot A:8`

Order sensitivity, stated rather than left to be discovered:

- **Step 5's control must precede the forged import.** Both `bd close`
  attempts in B fail with exit 1 and mutate nothing (verified in the
  transcript), so the control is safe to run first — but running it *after*
  the forged import would prove nothing, because `P` and `Q` are closed by
  then and the guards have no open structure left to object to.
- **Step 4 must precede step 5.** The deps that C3's guards need in B ride
  inline on the dependent's exported row (see C3), so B only acquires the
  structure by hopping A's already-wired rows.
- **Step 6 must follow step 3.** C4b needs an existing row in B to attempt to
  overwrite; `Y` is that row, and its stored `updated_at` is the one C4b's
  forged timestamp must beat.
- **Step 7's hop is unfiltered — and its ordering had to be measured, not
  assumed.** It re-imports every A row into B; in the recorded run that
  reported `{"created": 8, "skipped": 2, tie: 4, stale: 2}`, the two stale
  skips being `P` and `Q`, whose forged closes made B's copies strictly newer
  than A's. So it does **not** overwrite C3's forged state, and repeating it
  is a no-op: an immediate second unfiltered hop reported
  `{"created": 8, "skipped": 2, tie: 8, stale: 2}` and changed no stored
  field — not even `metadata.cn_dot`, which stayed at each row's earlier
  stamp, because a tied row is kept local wholesale rather than merged.
  Placing this hop *before* the forged import would not have destroyed C3
  either: it is a superset of step 4's hop, leaving `P`/`Q` open at A's own
  `updated_at` — exactly the precondition C3's forged bundle was built to
  beat. The one ordering step 7 genuinely requires is that it follow `bd
  delete Z`; a hop before the delete would simply carry `Z` again and say
  nothing about deletion.
- **C2's probe (`P2`/`K2`) closes `P2` for real.** It is a separate pair of
  issues touched by nothing else, so it is order-independent — but it does
  leave a closed issue behind in A, which is why the unfiltered hop in step 7
  reports more created rows than the sub-checks alone created.

No block in this document was measured on a separate rig root except the
reproduction run, which says so explicitly and re-ran the *entire* sequence.

## Environment, verified rather than assumed

```
$ cat "$BDS0_RIG_ROOT/bd-version.txt"
bd version 1.1.2 (Homebrew)
```

`bash scripts/spike/bds0/rig.sh smoke` passed before the run:

```
== rig.sh smoke: hop A B --dot 'A:1' bdsa-kn6 ==
{"created": 1, "ids": ["bdsa-kn6"], "schema_version": 1, "skipped": 0, "source": "stdin"}
== rig.sh smoke: verify bdsa-kn6 exists in B, stamped ==
== rig.sh smoke: journal B bdsa-kn6 ==
{"rows": [{"actor":"MacBoo","created_at":"2026-08-13 18:54:56","event_type":"created",
           "id":"019ffc0c-5bb6-74f9-9a60-9bebc2dd70a7","issue_id":"bdsa-kn6",
           "new_value":"","old_value":""}]}
== rig.sh smoke: OK (1 journal event(s) for bdsa-kn6 in B) ==
```

The flag surface this section depends on, read rather than assumed:

```
$ bd -C "$A" --sandbox close --help
Flags:
  -f, --force                Force close pinned issues or unsatisfied gates
  -r, --reason string        Reason for closing
      --reason-file string   Read close reason from file (use - for stdin)
  ... (elided: --claim-next, --continue, --no-auto, --session, --suggest-next)

$ bd -C "$B" --sandbox import --help
Flags:
      --allow-stale    Import rows even when older than the local issue (required to restore an older snapshot)
      --dedup          Skip lines whose title matches an existing open issue
      --dry-run        Show what would be imported without importing
  -i, --input string   Read JSONL from a specific file
```

So `-r/--reason` is the flag `bd 1.1.2` accepts, and `bd import` has **no**
guard-related flag at all — no `--force`, no validation toggle. That matters
for C3: whatever import does with a guard-violating close, it does
unconditionally.

The status vocabulary, which C4 rests on:

```
$ bd -C "$A" --sandbox statuses
Built-in statuses:
  ○ open           [active]  Available to work (default)
  ◐ in_progress    [wip   ]  Actively being worked on
  ● blocked        [wip   ]  Blocked by a dependency
  ❄ deferred       [frozen]  Deliberately put on ice for later
  ✓ closed         [done  ]  Completed
  📌 pinned         [frozen]  Persistent, stays open indefinitely
  ◇ hooked         [wip   ]  Attached to an agent's hook

No custom statuses configured.
```

**`tombstone` is not a status `bd` will name.** It is nonetheless recognised
on the import path — see C4.

Established by the sibling sections and relied on here rather than
re-derived: `bd import`'s LWW comparison is one-second wall-clock, so a
same-second mutate-then-hop ties (`sleep 1` appears throughout below); the
report's `"created"` counter means *candidate rows*, not rows created; and
`bd import` writes **no journal event for an update**, which is why every
verification below reads `bd show`/`bd export` and not the journal.

Safety: every command in this section ran against a synthetic `mktemp -d` rig
root via `bd -C <ws> --sandbox` or a `rig.sh` subcommand. Nothing here read or
wrote the repository's live `.beads`.

## Setup

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"
A="$BDS0_RIG_ROOT/A"; B="$BDS0_RIG_ROOT/B"
X=$(bd -C "$A" --sandbox list --json \
    | jq -r '[.[]|select(.title|startswith("Plain"))][0].id')   # bdsa-dow
```

## C1 — a close in A replicates into B: **PASS**

### C1a — the create shape

`X` does not exist in B before the hop (B is seeded with its own `bdsb-*`
issues), so this hop creates B's copy already-closed.

```bash
bd -C "$A" --sandbox close "$X" --reason "done"
```

```
✓ Closed bdsa-dow — Plain seeded task in bdsa: done
```

A's stored row and the export row it produces:

```json
{"id":"bdsa-dow","status":"closed","closed_at":"2026-08-13T16:56:11Z",
 "close_reason":"done","updated_at":"2026-08-13T16:56:11Z"}
```

```json
{"_type":"issue","id":"bdsa-dow","title":"Plain seeded task in bdsa","status":"closed",
 "priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-13T16:55:31Z","created_by":"MacBoo",
 "updated_at":"2026-08-13T16:56:11Z","closed_at":"2026-08-13T16:56:11Z",
 "close_reason":"done","dependency_count":0,"dependent_count":0,"comment_count":0}
```

Both `closed_at` and `close_reason` are first-class exported fields. Hop:

```bash
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:1" "$X"
```

```json
{"created": 1, "ids": ["bdsa-dow"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

B's copy, with **no `bd close` invocation in B**:

```json
{"id":"bdsa-dow","status":"closed","closed_at":"2026-08-13T16:56:11Z",
 "close_reason":"done","updated_at":"2026-08-13T16:56:11Z","metadata":{"cn_dot":"A:1"}}
```

`closed_at` is byte-identical to A's (`2026-08-13T16:56:11Z`), `close_reason`
is `"done"`, and B's re-export carries them onward unchanged:

```json
{"_type":"issue","id":"bdsa-dow","title":"Plain seeded task in bdsa","status":"closed",
 "priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-13T16:55:31Z","created_by":"MacBoo",
 "updated_at":"2026-08-13T16:56:11Z","closed_at":"2026-08-13T16:56:11Z",
 "close_reason":"done","metadata":{"cn_dot":"A:1"},
 "dependency_count":0,"dependent_count":0,"comment_count":0}
```

### C1b — the update shape, which is the one that actually matters

A create-shaped close proves less than it looks: it never exercises the
transition. So a second subject `Y` was replicated **open** first, then closed
in A and re-hopped, so that B's import is a genuine open→closed update.

```bash
Y=$(bd -C "$A" --sandbox create "C1b subject: closed in A after replication" \
    --type task --silent)                                  # bdsa-5pk
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:2" "$Y"    # creates B's copy, open
```

```json
{"id":"bdsa-5pk","status":"open","closed_at":null,"close_reason":null,
 "updated_at":"2026-08-13T16:56:44Z"}
```

```bash
sleep 1
bd -C "$A" --sandbox close "$Y" --reason "superseded by C1b control"
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:3" "$Y"
```

```json
{"created": 1, "ids": ["bdsa-5pk"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "updated": 1,
 "updated_issues": [{"changes": "status open → closed, close_reason, metadata",
                     "id": "bdsa-5pk"}]}
```

B's stored row after the update-shaped import:

```json
{"id":"bdsa-5pk","status":"closed","closed_at":"2026-08-13T16:56:50Z",
 "close_reason":"superseded by C1b control","updated_at":"2026-08-13T16:56:50Z",
 "metadata":{"cn_dot":"A:3"}}
```

A's, for comparison: `{"closed_at":"2026-08-13T16:56:50Z","close_reason":"superseded by C1b control"}`
— identical.

Two details worth stating because they are easy to assume wrongly:

- **The report's `changes` string does not mention `closed_at`.** It names
  `status open → closed, close_reason, metadata`. `closed_at` nevertheless
  landed byte-identically. The `changes` string is a partial summary and is
  not safe to use as an assertion surface.
- **B's journal is unchanged by the update**, still exactly `["created"]` from
  the first hop — consistent with claim (a)'s finding that imported updates
  journal nothing. A close is not special here; it is invisible to the journal
  like every other imported update.

No `bd close`, `bd update`, or any other write command was invoked in B for
either subject. Replicating a close needed no beads surface beyond `import`.

## C2 — the guards fire when *originating*: **PASS**, with a narrowing

Entirely inside workspace A. Four fresh issues, one epic parent with an open
child, one blocked issue with an open blocker:

```bash
P=$(bd -C "$A" --sandbox create "C2 parent P" --type epic --silent)          # bdsa-9vd
K=$(bd -C "$A" --sandbox create "C2 open child K" --type task --silent)      # bdsa-zwu
Q=$(bd -C "$A" --sandbox create "C2 blocked Q" --type task --silent)         # bdsa-4h1
R=$(bd -C "$A" --sandbox create "C2 open blocker R" --type task --silent)    # bdsa-cvj

bd -C "$A" --sandbox dep add "$K" "$P" --type parent-child
bd -C "$A" --sandbox dep add "$Q" --blocked-by "$R"
```

```
✓ Added dependency: bdsa-zwu (C2 open child K) depends on bdsa-9vd (C2 parent P) (parent-child)
✓ Added dependency: bdsa-4h1 (C2 blocked Q) depends on bdsa-cvj (C2 open blocker R) (blocks)
```

Both closes, verbatim, with exit codes:

```bash
bd -C "$A" --sandbox close "$P" --reason "attempt with open child"; echo "exit=$?"
bd -C "$A" --sandbox close "$Q" --reason "attempt while blocked"; echo "exit=$?"
```

```
cannot close epic bdsa-9vd: 1 open child issue(s); close children first or use --force to override
exit=1
cannot close bdsa-4h1: blocked by open issues [bdsa-cvj] (use --force to override)
exit=1
```

**Neither message names `ErrCloseBlocked` or `ErrCloseOpenChildren`.** Those
are presumably internal Go error identifiers; the user-visible surface is the
two strings above. Anything downstream that wants to detect these conditions
matches on prose or on exit status, not on a named code — and `--json` does
not help:

```bash
bd -C "$A" --sandbox --json close "$Q" --reason "attempt while blocked"; echo "exit=$?"
```

```
cannot close bdsa-4h1: blocked by open issues [bdsa-cvj] (use --force to override)
exit=1
```

The same bare string on stderr, no JSON error object, exit 1. (Contrast C4c
below, where `bd import --json` *does* emit a structured `{"error": ...}`.)

### The narrowing: the open-children guard is epic-only

The guard's own message says "cannot close **epic** …", so the obvious probe:
the same `parent-child` structure with a `task` parent.

```bash
P2=$(bd -C "$A" --sandbox create "C2 probe: task parent P2" --type task --silent)   # bdsa-82z
K2=$(bd -C "$A" --sandbox create "C2 probe: open child K2" --type task --silent)    # bdsa-bl8
bd -C "$A" --sandbox dep add "$K2" "$P2" --type parent-child
bd -C "$A" --sandbox close "$P2" --reason "attempt with open child, task parent"; echo "exit=$?"
```

```
✓ Added dependency: bdsa-bl8 (C2 probe: open child K2) depends on bdsa-82z (C2 probe: task parent P2) (parent-child)
✓ Closed bdsa-82z — C2 probe: task parent P2: attempt with open child, task parent
exit=0
```

```json
{"id":"bdsa-82z","status":"closed","closed_at":"2026-08-13T16:57:36Z",
 "close_reason":"attempt with open child, task parent"}
```

So "close with open children" is refused for `issue_type: epic` and permitted
for `issue_type: task`, with the same dependency type and the same open child.
The epic's phrasing ("a parent with open children") is broader than the
implemented guard. Anything reasoning about which closes `bd` will originate
must condition on issue type.

## C3 — the guards do **not** fire on the replication path: **PASS**

This is the consequential sub-check. If import enforced originate-time guards
on already-adjudicated facts, replication of closes would not be sound.

### The structure exists locally in B

Dependencies are not separate export rows — they ride inline on the
*dependent's* issue row, so hopping A's wired rows reproduces the structure in
B:

```bash
bd -C "$A" --sandbox export | jq -r '._type' | sort | uniq -c
```

```
  10 issue
```

```json
{"_type":"issue","id":"bdsa-4h1","title":"C2 blocked Q","status":"open",...,
 "dependencies":[{"issue_id":"bdsa-4h1","depends_on_id":"bdsa-cvj","type":"blocks",
                  "created_at":"2026-08-13T18:57:14Z","created_by":"MacBoo","metadata":"{}"}],
 "dependency_count":1,...}
{"_type":"issue","id":"bdsa-zwu","title":"C2 open child K","status":"open",...,
 "dependencies":[{"issue_id":"bdsa-zwu","depends_on_id":"bdsa-9vd","type":"parent-child",
                  "created_at":"2026-08-13T18:57:13Z","created_by":"MacBoo","metadata":"{}"}],
 ...}
```

```bash
bash scripts/spike/bds0/rig.sh hop A B --dot "A:4" "$P" "$K" "$Q" "$R"
```

```json
{"created": 4, "ids": ["bdsa-cvj","bdsa-4h1","bdsa-zwu","bdsa-9vd"],
 "schema_version": 1, "skipped": 0, "source": "stdin"}
```

B now holds the structure the guards would check:

```json
{"id":"bdsa-4h1","status":"open","dependencies":[{"id":"bdsa-cvj","dependency_type":"blocks"}]}
{"id":"bdsa-9vd","status":"open","issue_type":"epic","dependent_count":1}
```

```bash
bd -C "$B" --sandbox children "$P"
```

```
○ bdsa-9vd ● P2 [epic] C2 parent P
└── ○ bdsa-zwu ● P2 C2 open child K
```

### Control — B's own `bd close` refuses these exact closes

**Precondition: run this before the forged import** (step 5 of the execution
order). Both attempts exit 1 and mutate nothing, verified immediately after:

```bash
bd -C "$B" --sandbox close "$P" --reason "B-local originate attempt"; echo "exit=$?"
bd -C "$B" --sandbox close "$Q" --reason "B-local originate attempt"; echo "exit=$?"
```

```
cannot close epic bdsa-9vd: 1 open child issue(s); close children first or use --force to override
exit=1
cannot close bdsa-4h1: blocked by open issues [bdsa-cvj] (use --force to override)
exit=1
```

```json
{"id":"bdsa-9vd","status":"open","closed_at":null}
{"id":"bdsa-4h1","status":"open","closed_at":null}
```

So the guards are live in B, on these two ids, at this moment. Whatever the
next command does, it does it against a workspace that has just refused to do
it itself.

### The forged bundle

A will not originate these closes (C2 proved it), so the bundle is forged from
A's export — the in-model manual pipeline both sibling sections used, since the
rig's `hop` cannot edit row contents. `updated_at` is set strictly newer than
B's copies so the LWW comparison cannot be what decides the outcome:

```bash
NEWTS=$(date -u -v+60S +%Y-%m-%dT%H:%M:%SZ)     # 2026-08-13T16:59:30Z
bd -C "$A" --sandbox export \
 | jq -c --arg p "$P" --arg q "$Q" --arg ts "$NEWTS" \
   'select(.id==$p or .id==$q)
    | .status="closed" | .closed_at=$ts | .close_reason="adjudicated in A (forged)"
    | .updated_at=$ts | .metadata=((.metadata//{})+{cn_dot:"A:5"})' \
 > forged.jsonl
```

```json
{"_type":"issue","id":"bdsa-4h1","title":"C2 blocked Q","status":"closed","priority":2,
 "issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T16:57:11Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T16:59:30Z",
 "dependencies":[{"issue_id":"bdsa-4h1","depends_on_id":"bdsa-cvj","type":"blocks",
                  "created_at":"2026-08-13T18:57:14Z","created_by":"MacBoo","metadata":"{}"}],
 "dependency_count":1,"dependent_count":0,"comment_count":0,
 "closed_at":"2026-08-13T16:59:30Z","close_reason":"adjudicated in A (forged)",
 "metadata":{"cn_dot":"A:5"}}
{"_type":"issue","id":"bdsa-9vd","title":"C2 parent P","status":"closed","priority":2,
 "issue_type":"epic","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T16:57:09Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T16:59:30Z",
 "dependency_count":0,"dependent_count":0,"comment_count":0,
 "closed_at":"2026-08-13T16:59:30Z","close_reason":"adjudicated in A (forged)",
 "metadata":{"cn_dot":"A:5"}}
```

Note the forged `Q` row still carries its `blocks` dependency, and the forged
`P` row is still the epic whose child `K` is open in B. Nothing about the
bundle hides the guard-violating structure from the importer.

Imported **unflagged** — no `--allow-stale`, no force of any kind (there is
none to pass):

```bash
bd -C "$B" --sandbox import - --json < forged.jsonl; echo "exit=$?"
```

```json
{"created": 2, "ids": ["bdsa-4h1","bdsa-9vd"], "schema_version": 1, "skipped": 0,
 "source": "stdin", "updated": 2,
 "updated_issues": [
   {"changes": "status open → closed, close_reason, metadata", "id": "bdsa-4h1"},
   {"changes": "status open → closed, close_reason, metadata", "id": "bdsa-9vd"}]}
exit=0
```

B's stored rows:

```json
{"id":"bdsa-9vd","status":"closed","closed_at":"2026-08-13T16:59:30Z",
 "close_reason":"adjudicated in A (forged)","updated_at":"2026-08-13T16:59:30Z",
 "metadata":{"cn_dot":"A:5"}}
{"id":"bdsa-4h1","status":"closed","closed_at":"2026-08-13T16:59:30Z",
 "close_reason":"adjudicated in A (forged)","updated_at":"2026-08-13T16:59:30Z",
 "metadata":{"cn_dot":"A:5"}}
```

And the structure that provoked the guards is untouched — the child and the
blocker are still open, so the "violation" persists in B after the import:

```json
{"id":"bdsa-zwu","status":"open"}
{"id":"bdsa-cvj","status":"open"}
```

**Both guard shapes: applied, silently, with no warning.** The import report
says nothing about the guard condition; it reads exactly like C1b's ordinary
close replication. The originate/replicate asymmetry the epic assumes is real
and is implemented by the guards living in the `close` command path rather
than in the write path.

Two honest limits on this result:

- It shows the guards *do not run* on import. It does **not** show that
  import performs any compensating consistency work. It performs none: `P`
  is a closed epic with an open child in B, and B is content with that. The
  guards are the only thing that would have objected, and they are not on
  this path.
- The forged rows also make B's copies strictly newer, which is what lets the
  write land at all. That is LWW (claim (b)), not a guard decision — the
  point of forging a newer timestamp was to remove ordering as a confounder,
  so that "applied" could only mean "the guards did not object".

## C4 — `tombstone` rows are dropped; hard delete does not replicate: **PASS on the mechanism, false on the premise**

### C4a — a `tombstone` row for a new id is dropped silently

```bash
printf '%s\n' '{"_type":"issue","id":"bdsa-tomb1","title":"C4a forged tombstone, new id",
 "status":"tombstone","priority":2,"issue_type":"task","created_at":"2026-08-13T16:00:00Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T16:00:00Z"}' > tomb1.jsonl
bd -C "$B" --sandbox import - --json < tomb1.jsonl; echo "exit=$?"
```

```json
{"created": 0, "schema_version": 1, "skipped": 0, "source": "stdin"}
exit=0
```

```
Error fetching bdsa-tomb1: no issue found matching "bdsa-tomb1"
```

Exit 0, no issue created — and note **`"skipped": 0`**. The row is not counted
as skipped, is not listed in any `*_ids` array, and produces no diagnostic. It
simply is not there.

### C4b — a `tombstone` row for an existing id, forged newer, does not delete or alter it

The subject is `Y` (`bdsa-5pk`), closed in B by C1b, stored `updated_at`
`2026-08-13T16:56:50Z`. The forged row carries `2026-08-13T17:01:00Z`, so LWW
would let it win:

```bash
NEWTS=$(date -u -v+120S +%Y-%m-%dT%H:%M:%SZ)    # 2026-08-13T17:01:00Z
bd -C "$A" --sandbox export \
 | jq -c --arg y "$Y" --arg ts "$NEWTS" \
   'select(.id==$y)|.status="tombstone"|.updated_at=$ts
    |.metadata=((.metadata//{})+{cn_dot:"A:6"})' > tomb2.jsonl
bd -C "$B" --sandbox import - --json < tomb2.jsonl; echo "exit=$?"
```

```json
{"_type":"issue","id":"bdsa-5pk","title":"C1b subject: closed in A after replication",
 "status":"tombstone","priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com",
 "created_at":"2026-08-13T16:56:44Z","created_by":"MacBoo","updated_at":"2026-08-13T17:01:00Z",
 "closed_at":"2026-08-13T16:56:50Z","close_reason":"superseded by C1b control",
 "dependency_count":0,"dependent_count":0,"comment_count":0,"metadata":{"cn_dot":"A:6"}}
```

```json
{"created": 0, "schema_version": 1, "skipped": 0, "source": "stdin"}
exit=0
```

B's copy afterwards — unchanged in every field, including `updated_at` and the
`cn_dot` from the earlier hop:

```json
{"id":"bdsa-5pk","status":"closed","closed_at":"2026-08-13T16:56:50Z",
 "close_reason":"superseded by C1b control","updated_at":"2026-08-13T16:56:50Z",
 "metadata":{"cn_dot":"A:3"}}
```

So the drop happens *before* the LWW comparison and before any field merge: a
newer `tombstone` row cannot even carry an incidental field update through.

### C4c — control: an unrecognised status is rejected loudly, so the drop is specific

Without this control, C4a/C4b would be equally explained by "import ignores
rows with any status it doesn't know". It doesn't:

```bash
printf '%s\n' '{"_type":"issue","id":"bdsa-ban1","title":"C4c control: unknown status banana",
 "status":"banana","priority":2,"issue_type":"task","created_at":"2026-08-13T16:00:00Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T16:00:00Z"}' > ban1.jsonl
bd -C "$B" --sandbox import - --json < ban1.jsonl; echo "exit=$?"
```

```json
{"error": "import failed: validation failed for issue bdsa-ban1: invalid status: banana",
 "schema_version": 1}
exit=1
```

The whole import fails, exit 1, structured error. `tombstone` is therefore
**specifically recognised** by the import path and deliberately dropped — even
though `bd statuses` does not list it as a status a user can set.

### C4d — the premise is wrong: `bd delete` exists, and it replicates nothing

The epic states "`bd` has no delete verb". `bd 1.1.2` does:

```
$ bd -C "$A" --sandbox delete --help
Delete one or more issues and clean up all references to them.
This command will:
1. Remove all dependency links (any type, both directions) involving the issues
2. Update text references to "[deleted:ID]" in directly connected issues
3. Permanently delete the issues from the database

This is a destructive operation that cannot be undone. Use with caution.
...
Flags:
      --cascade            Recursively delete all dependent issues
      --dry-run            Preview what would be deleted without making changes
  -f, --force              Actually delete (without this flag, shows preview)
      --from-file string   Read issue IDs from file (one per line)
```

`bd delete` is a real hard delete, not a soft one. What it is *not* is
replicable — measured:

```bash
Z=$(bd -C "$A" --sandbox create "C4d subject: hard-deleted in A after replication" \
    --type task --silent)                                  # bdsa-53y
sleep 1
bash scripts/spike/bds0/rig.sh hop A B --dot "A:7" "$Z"    # B's copy: open
bd -C "$A" --sandbox delete "$Z" --force
```

```
✓ Deleted bdsa-53y
  Removed 0 dependency link(s)
  Updated text references in 0 issue(s)
```

A's export no longer mentions `Z` at all — no row, no tombstone, nothing:

```bash
bd -C "$A" --sandbox export | grep -c "$Z"
```

```
0
```

And a full, unfiltered hop of everything A has into B leaves B's copy
untouched and alive:

```bash
bash scripts/spike/bds0/rig.sh hop A B --dot "A:8"
```

The report was reshaped through `jq -c '{created,updated,skipped,tie:(.tie_kept_local_ids//[]|length),stale:(.stale_skipped_ids//[]|length)}'`
for brevity — this is a summary of the report, not its raw text:

```json
{"created":8,"updated":null,"skipped":2,"tie":4,"stale":2}
```

```json
{"id":"bdsa-53y","status":"open","title":"C4d subject: hard-deleted in A after replication"}
```

(The two stale skips are `P` and `Q`, whose forged closes made B's copies
newer — see the execution-order note.)

So the epic's conclusion holds and its reason does not. Hard deletion does not
replicate because **deletion is expressed as absence**, and absence is
indistinguishable from "not in this delta" in a JSONL export. A peer would
have to diff whole snapshots to notice, and the seam offers no tombstone to
carry the fact. Close remains the only removal signal that survives a hop —
not because `bd` lacks a delete verb, but because its delete verb leaves
nothing to replicate.

## Reproduction on an independent rig root

The entire sequence (steps 1–7 above, in order) was re-run as a single script
against a second `rig.sh init` root, `bd version 1.1.2 (Homebrew)`. Outcomes,
verbatim from that run:

```
ROOT=/var/folders/.../tmp.bgHitJP2fx  bd version 1.1.2 (Homebrew)
C1  B: {"status":"closed","closed_at":"2026-08-13T17:00:26Z","close_reason":"done"}
C1  A: {"status":"closed","closed_at":"2026-08-13T17:00:26Z","close_reason":"done"}
C1b import report: {"updated":1,"changes":"status open → closed, close_reason, metadata"}
C1b B: {"status":"closed","closed_at":"2026-08-13T17:00:37Z","close_reason":"superseded"}
C2  close P: cannot close epic bdsa-s3b: 1 open child issue(s); close children first or use --force to override
C2  close Q: cannot close bdsa-3cv: blocked by open issues [bdsa-nr3] (use --force to override)
C2  probe: task parent with open child CLOSED (guard epic-only)
C3  B control close P: cannot close epic bdsa-s3b: 1 open child issue(s); close children first or use --force to override
C3  B control close Q: cannot close bdsa-3cv: blocked by open issues [bdsa-nr3] (use --force to override)
C3  import: {"updated":2,"changes":["status open → closed, close_reason, metadata",
                                    "status open → closed, close_reason, metadata"]}
C3  B P: {"status":"closed","closed_at":"2026-08-13T17:01:57Z","close_reason":"adjudicated in A (forged)"}
C3  B Q: {"status":"closed","closed_at":"2026-08-13T17:01:57Z","close_reason":"adjudicated in A (forged)"}
C3  B K/R still open: open open
C4a import: {"created":0,"skipped":0}  show: Error fetching bdsa-tomb1: no issue found matching "bdsa-tomb1"
C4b import: {"created":0,"skipped":0}
C4b B Y: {"status":"closed","updated_at":"2026-08-13T17:00:37Z"}
C4c import: "import failed: validation failed for issue bdsa-ban1: invalid status: banana"
C4d A export mentions Z: 0
C4d B still has Z: {"id":"bdsa-8f0","status":"open"}
```

Identical on every sub-check. One deliberate difference: the reproduction's
forged C3 bundle omits the `cn_dot` stamp, so its `changes` string reports
`metadata` as changed because the stamp from the earlier hop was *removed*
rather than replaced. That is an artifact of the reproduction script, not a
different behavior — the close itself landed the same way.

## What did not work

- **`ErrCloseBlocked` / `ErrCloseOpenChildren` are not observable.** Neither
  name appears in any output. The user-visible surface is two prose strings
  plus exit 1.
- **`bd close --json` does not produce a structured error.** It prints the
  same bare message to stderr and exits 1. (`bd import --json` does emit
  `{"error": ...}` — the two commands disagree about this.)
- **The open-children guard could not be provoked on a non-epic parent.** A
  `task` parent with an open `parent-child` child closes cleanly, exit 0.
- **`updated_issues[].changes` could not be used to verify `closed_at`.** It
  names `status`, `close_reason` and `metadata` and omits `closed_at`, which
  landed anyway. It is a summary, not an audit record.
- **The journal could not confirm any of this.** Imported updates write no
  event (established in claim (a) and re-observed here: B's journal for `Y`
  stayed `["created"]` across the close-carrying import). All verification is
  `bd show` / `bd export`.
- **A `tombstone` row could not be made to do anything** — not create, not
  update, not delete, not even carry an unrelated field change on a strictly
  newer `updated_at`.
- **`bd delete` could not be made to replicate.** There is no export
  representation of a deleted issue to hop; the row is simply gone from the
  source and unaffected at the destination.
- **`bd import` has no guard-related flag** to turn guard checking on, so
  "guards off on the import path" could not be tested as a configurable — it
  is the only behavior available.

Surprises worth recording, observed but not diagnosed here:

- **`tombstone` is not in `bd statuses`, yet the import path knows it.** An
  unlisted status that is silently honoured on one path and rejected on
  another (`banana`) implies `tombstone` is a real internal concept in `bd`
  1.1.2 with no user-facing way to produce one. Where it *is* produced — if
  anywhere — was not investigated.
- **A dropped `tombstone` row is invisible in the report**: `"created": 0,
  "skipped": 0` and no id list. An importer that fed a bundle of 100 rows, one
  of them a tombstone, would get a report indistinguishable from having sent
  99.
- **Dependencies ride inline on the dependent's exported row**, not as
  separate `_type` records (`bd export` emitted only `_type: "issue"`). Graph
  structure therefore replicates as a side effect of replicating the
  dependent, and a hop that selects only the parent moves no edges.
- **`bd delete` rewrites text references in surviving issues to
  `[deleted:ID]`** (per its help text; not exercised here, as the deleted
  subject had no referencing issues). That is a content mutation of *other*
  rows, which would replicate as ordinary field updates — a delete in A can
  therefore change unrelated rows in B without the deletion itself
  replicating. Untested here; flagged.
- **`bd reopen` exists** and "emits a Reopened event", so the close/reopen
  pair is asymmetric in journaling terms on the originate path. Not exercised;
  noted because any close-replication design will eventually meet reopen.

<!-- VERDICT PLACEHOLDER — the "Verdict and implications" subsection is owned
     by the follow-up task (computenet-8kj.4, task 2) and is appended here.
     Do not write it in this task. -->
