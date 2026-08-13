# BDS0 claim (b) — ordering authority and the true cost of `--allow-stale`

Epic `computenet-8kj` §1(b). The claim under test: `bd import` adjudicates by
wall-clock LWW, and `--allow-stale` gives the BDS0 write path the authority to
impose a **clock-free dot-order decision** on the local store. The four
sub-checks:

- **E1 baseline** — the three unflagged outcomes, each reproduced on demand:
  strictly-newer incoming overwrites, equal `updated_at` keeps local
  (`tie_kept_local_ids`), older incoming is skipped (`stale_skipped_ids`).
- **E2 imposition** — with `--allow-stale`, does the older (dot-order-winning)
  row actually land, i.e. does B's stored issue afterwards *equal* the incoming
  row?
- **E3 the clobber question** — does `--allow-stale` also destroy a genuinely
  newer **local** edit that was never gossiped, and is its blast radius the
  whole import run or only the rows that are actually stale?
- **E4 same-second adjudication** — how one-second resolution decides two
  writes that differ only below a second.

Result in one line: **E1 pass, E2 pass, E3 pass-as-a-question-with-a-bad-answer,
E4 pass with a defect.** `--allow-stale` does impose the incoming row
completely — and it is a **per-import-run** switch with **no per-row form**, so
it clobbers every never-gossiped local edit in the same bundle, silently: under
the flag the import report stops printing `stale_skipped_ids`, `updated` and
`updated_issues` altogether, so the destroyed rows are not even named. E4 turned
up a separate, independent defect: an incoming `updated_at` whose sub-second
part is ≥ `.500` **rounds up** into the next second and overwrites the local
row, while the same import reports `tie_kept_local_ids` for it — the report says
the local row was kept and the local row was not kept.

The verdict, the BDS1/BDS4 implications and the alternative-instrument design
are deliberately **not** in this section; see the placeholder at the end.

Everything below was executed. The whole sequence was re-run end to end on a
second, independently initialised rig root and produced the same outcomes
(issue ids differ per `init` — they are `mktemp`-fresh workspaces, so
substitute your own).

## Execution order — the prose order *is* the run order, with one split

Every block below was produced by this one linear sequence on one rig root, in
exactly the order printed:

1. `init`; pick `X` (the plain seed); hop `--dot A:1` (a create) — Setup
2. **E1a** newer → **E1b** tie → **E1c** older, in that order on `X`
3. **E2** on the same `X`, then the **E2 stamp-only variant** and its no-flag
   control
4. **E3** on three *freshly created* issues `Z`, `Y`, `W`: seed hop, local edits
   in B, then the control import (no flag), then the `--allow-stale` import
5. **E4** on freshly created `Q`, then the isolated re-verification on freshly
   created `R`, then the rounding-boundary probe on freshly created `S`

Order-sensitivity, stated rather than left to be discovered:

- **E1 and E2 are strictly ordered and share one row.** Each block's forged
  `updated_at` is chosen relative to what its predecessor left stored, and each
  block below prints B's stored row so the precondition is visible. Running E1c
  before E1a, for instance, makes E1c an *overwrite* rather than a stale skip:
  E1c's forged `16:26:19Z` is "older" only relative to what E1a leaves stored
  (`16:56:19Z`), and it is still half an hour *newer* than the seed hop's `T`
  (`15:56:19Z`), so against a fresh setup it wins. Measured on a fresh rig root
  — the E1c command run immediately after the setup hop reported
  `{"created":1,"skipped":0,"updated":1,"updated_issues":[{"changes":"title, metadata",...}]}`
  and left the stored row at
  `{"title":"E1c incoming, forged older","updated_at":"2026-08-13T16:26:19Z","metadata":{"cn_dot":"A:older"}}`.
  It is not a create — `X` already exists in B from the setup hop.
- **E2 leaves B's `updated_at` moved backwards** (`16:56:19Z` → `16:26:19Z`).
  Anything run after E2 against `X` inherits that.
- **E3 and E4 are order-independent of E1/E2** because they act on issues
  created for them (`Z`, `Y`, `W`, `Q`, `R`, `S`); they are still internally
  ordered.
- **Inside E3, the control import runs before the `--allow-stale` import**, and
  it applies `Y`. The `--allow-stale` bundle therefore advances `Y`'s forged
  `updated_at` by one minute (`16:58:00Z` → `16:59:00Z`) so that `Y` is
  genuinely newer in *both* runs; `Z` and `W` are byte-identical between the
  two bundles, so for the clobber question the flag is the only variable.
- **E4-1/E4-2 ran on `Q` before the isolated re-verification on `R`.** `R`
  exists because the `Q` transcript mixed two effects (rounding and
  adjudication) and was not clean enough to rest a defect claim on; the `R`
  block is the one to read, and it is self-contained from a fresh hop. Both are
  printed — the messy one is not hidden.

No block in this section was measured on a separate rig root. The full-sequence
re-run on the second root (§"Reproduction on an independent rig root") is a
replay of all of the above, not a source of any individual transcript here.

## Environment, verified rather than assumed

```
$ cat "$BDS0_RIG_ROOT/bd-version.txt"
bd version 1.1.2 (Homebrew)
```

`bash scripts/spike/bds0/rig.sh smoke` passed before the run:
`== rig.sh smoke: OK (1 journal event(s) for bdsa-1h2 in B) ==`.

Safety: every command in this section ran against a synthetic `mktemp -d` rig
root via `bd -C <ws> --sandbox` or a `rig.sh` subcommand. Nothing here read or
wrote the repository's live `.beads`.

**Why the pipeline is hand-rolled rather than `rig.sh hop`.** `hop` stamps
`metadata.cn_dot` but cannot forge `updated_at`, and every experiment below
turns on a forged `updated_at`. So the hops that need forging are the manual
in-model equivalent —

```bash
bd -C "$A" --sandbox export | jq -c '<forge updated_at + cn_dot>' \
  | bd -C "$B" --sandbox import - --json [--allow-stale]
```

— which is the same three steps `hop` performs, with the `jq` filter extended.
`hop` is used unchanged wherever no forging is needed (every create/seed hop
below).

`bd import`'s own help states the rule this section is testing, and it is worth
quoting because it also states the *intent* of the flag:

```
By default a row only rewrites an existing local issue when its
updated_at is strictly newer. Older rows are skipped (reported as
stale_skipped_ids) and rows with the same updated_at keep every local
column — updated_at has second granularity, so a timestamp tie can be
two distinct same-second updates, and the local row wins the tie
(reported as tie_kept_local_ids; the row's labels/comments/dependencies
still merge). ... To deliberately restore an older snapshot, pass
--allow-stale, which imports every row even when it overwrites newer
local state.
```

Note "**restore an older snapshot**" and "**imports every row**". The flag is
documented as a bulk restore instrument. E3 measures what that means when it is
used as a replication instrument instead.

## Setup

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"
A="$BDS0_RIG_ROOT/A"; B="$BDS0_RIG_ROOT/B"
X=$(bd -C "$A" --sandbox list --json | jq -r '[.[]|select(.title|startswith("Plain"))][0].id')   # bdsa-3hi

bash scripts/spike/bds0/rig.sh hop A B --dot "A:1" "$X"
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

```bash
bd -C "$B" --sandbox show "$X" --json \
  | jq -c 'if type=="array" then .[0] else . end | {id,title,description,status,updated_at,metadata}'
```

```json
{"id":"bdsa-3hi","title":"Plain seeded task in bdsa","description":null,"status":"open",
 "updated_at":"2026-08-13T15:56:19Z","metadata":{"cn_dot":"A:1"}}
```

`X` did not exist in B before the hop, so this is a create. B's copy carries
A's `updated_at` (`15:56:19Z`) — call it `T`. Every forged value below is
expressed relative to it.

## E1 — the three unflagged outcomes: **PASS** (all three reproduced)

### E1a — incoming strictly newer: overwrite lands

Forged to `T + 1h` = `16:56:19Z`. Note that this is a **future** timestamp
relative to the wall clock of the run (`now(UTC)=2026-08-13T15:57:32Z`, printed
in E2 below), and `bd import` accepts and stores it without complaint — the
importer does no sanity check against the local clock. That is what makes the
whole forge-the-export technique work.

```bash
bd -C "$A" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x)
     | .updated_at="2026-08-13T16:56:19Z"
     | .title="E1a incoming, forged newer"
     | .metadata=((.metadata//{})+{cn_dot:"A:newer"})' \
 | bd -C "$B" --sandbox import - --json
```

The incoming row, in full:

```json
{"_type":"issue","id":"bdsa-3hi","title":"E1a incoming, forged newer","status":"open","priority":2,
 "issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T15:56:19Z",
 "created_by":"MacBoo","updated_at":"2026-08-13T16:56:19Z","dependency_count":0,
 "dependent_count":0,"comment_count":0,"metadata":{"cn_dot":"A:newer"}}
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "updated": 1,
 "updated_issues": [{"changes": "title, metadata", "id": "bdsa-3hi"}]}
```

```json
{"id":"bdsa-3hi","title":"E1a incoming, forged newer","updated_at":"2026-08-13T16:56:19Z",
 "metadata":{"cn_dot":"A:newer"}}
```

Overwrite lands; the stored `updated_at` becomes the incoming one, not an
import-time stamp.

### E1b — incoming equal `updated_at`: local kept, `tie_kept_local_ids`

**Precondition: run immediately after E1a** — B's row is at `16:56:19Z`, and
the forged value below is exactly that.

```bash
bd -C "$A" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x)
     | .updated_at="2026-08-13T16:56:19Z"
     | .title="E1b incoming, forged equal"
     | .metadata=((.metadata//{})+{cn_dot:"A:tie"})' \
 | bd -C "$B" --sandbox import - --json
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "tie_kept_local_ids": ["bdsa-3hi"]}
```

```json
{"id":"bdsa-3hi","title":"E1a incoming, forged newer","updated_at":"2026-08-13T16:56:19Z",
 "metadata":{"cn_dot":"A:newer"}}
```

The stored row is untouched: title is still E1a's, `cn_dot` is still `A:newer`.
Both the title *and* the metadata of the tie row were discarded — consistent
with claim (a)'s finding that a bare re-stamp cannot travel on a tie.

### E1c — incoming older: skipped, `stale_skipped_ids`

**Precondition: run immediately after E1b**, with B still at `16:56:19Z`.
Forged to `16:26:19Z` (30 minutes behind stored).

```bash
bd -C "$A" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x)
     | .updated_at="2026-08-13T16:26:19Z"
     | .title="E1c incoming, forged older"
     | .metadata=((.metadata//{})+{cn_dot:"A:older"})' \
 | bd -C "$B" --sandbox import - --json
```

```json
{"created": 0, "schema_version": 1, "skipped": 1, "source": "stdin",
 "stale_skipped_ids": ["bdsa-3hi"]}
```

```json
{"id":"bdsa-3hi","title":"E1a incoming, forged newer","updated_at":"2026-08-13T16:56:19Z",
 "metadata":{"cn_dot":"A:newer"}}
```

Stored row untouched. Note `"created": 0` here versus `"created": 1` on the tie
— the counter quirk claim (a) recorded (`created` counts candidate rows, and
the tie/stale/updated key beside it carries the outcome) holds, with the stale
path the one shape that reports `0`.

## E2 — `--allow-stale` imposition: **PASS**, and it is total

Same forged-older row as E1c, same forged `updated_at` (`16:26:19Z`), now with
`--allow-stale`, and carrying a changed `description` and `status` as well so
that "equals the incoming row" is testable on more than the title.

**Precondition: run immediately after E1c.** B's stored row before:

```json
{"id":"bdsa-3hi","title":"E1a incoming, forged newer","description":null,"status":"open",
 "updated_at":"2026-08-13T16:56:19Z","metadata":{"cn_dot":"A:newer"}}
```

```bash
bd -C "$A" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x)
     | .updated_at="2026-08-13T16:26:19Z"
     | .title="E2 incoming, forged older, allow-stale"
     | .description="imposed description from A"
     | .status="in_progress"
     | .metadata=((.metadata//{})+{cn_dot:"A:imposed"})' \
 | bd -C "$B" --sandbox import - --json --allow-stale
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

```
now(UTC)=2026-08-13T15:57:32Z
```

```json
{"id":"bdsa-3hi","title":"E2 incoming, forged older, allow-stale",
 "description":"imposed description from A","status":"in_progress",
 "updated_at":"2026-08-13T16:26:19Z","metadata":{"cn_dot":"A:imposed"}}
```

**Imposed, on the decided definition.** Every field of B's stored issue equals
the incoming row: `title`, `description`, `status`, `metadata.cn_dot`. So the
dot-order winner *can* be forced onto the local store.

Two things to record about *how*:

1. **The stored `updated_at` is the incoming one, not the local one and not an
   import-time stamp.** B's row went from `16:56:19Z` to `16:26:19Z` — the
   store's clock for that row moved **backwards** by thirty minutes. A
   subsequent unflagged import of anything between those two times would now
   win, where before it would have been skipped.
2. **The import report goes quiet.** Compare with E1a, which reported
   `"updated": 1` plus `updated_issues[].changes = "title, metadata"`. Under
   `--allow-stale` the same class of write reports neither `updated`,
   `updated_issues`, nor `stale_skipped_ids` — the report is
   indistinguishable from a clean create-only import. The help text advertises
   `updated_issues` as the mechanism by which "local state changed by an import
   is visible"; the flag that most needs that visibility is the one that
   removes it.

### E2 variant — does `--allow-stale` reopen the bare re-stamp claim (a) closed?

Claim (a) established that a new `cn_dot` cannot be stamped onto an
already-replicated row on its own, because the import ties. The variant asks
whether the flag lifts that. The incoming row here is B's *own* export of the
row with only `metadata.cn_dot` changed — so it is an exact tie on
`updated_at`, differing in metadata alone.

**Precondition: run immediately after E2**, with B at `16:26:19Z` and
`cn_dot=A:imposed`.

```bash
bd -C "$B" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x) | .metadata=((.metadata//{})+{cn_dot:"A:stamp-only"})' \
 | bd -C "$B" --sandbox import - --json --allow-stale
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

```json
{"title":"E2 incoming, forged older, allow-stale","updated_at":"2026-08-13T16:26:19Z",
 "metadata":{"cn_dot":"A:stamp-only"}}
```

**Yes — the bare stamp lands under the flag.** And the control, the identical
pipeline without `--allow-stale`, immediately after:

```bash
bd -C "$B" --sandbox export \
 | jq -c --arg x "$X" 'select(.id==$x) | .metadata=((.metadata//{})+{cn_dot:"A:stamp-only-noflag"})' \
 | bd -C "$B" --sandbox import - --json
```

```json
{"created": 1, "ids": ["bdsa-3hi"], "schema_version": 1, "skipped": 0, "source": "stdin",
 "tie_kept_local_ids": ["bdsa-3hi"]}
```

```json
{"title":"E2 incoming, forged older, allow-stale","updated_at":"2026-08-13T16:26:19Z",
 "metadata":{"cn_dot":"A:stamp-only"}}
```

`cn_dot` stays at `A:stamp-only` — the unflagged stamp did not land. So the
flag is the only difference, and it is what makes a metadata-only write
possible at all. (Note the tie also leaves `updated_at` unchanged in the
flagged case: a stamp-only import under `--allow-stale` does not advance the
row's clock, so it is invisible to any later LWW comparison.)

## E3 — the clobber question: **`--allow-stale` clobbers, and it is per-run**

The load-bearing experiment. Three issues, all created for this block, so it is
independent of E1/E2:

- `Z` = `bdsa-3pm` — the target: B holds a genuinely newer local edit that was
  never exported.
- `Y` = `bdsa-1p5` — an unrelated issue that A legitimately updated later; the
  row the sender actually wants delivered.
- `W` = `bdsa-tcy` — a bystander: also carries a newer never-gossiped local
  edit in B, and the sender has no intention of changing it. `W` is the blast
  radius probe.

```bash
Z=$(bd -C "$A" --sandbox create "E3 clobber target Z" --type task --silent)
Y=$(bd -C "$A" --sandbox create "E3 unrelated newer Y" --type task --silent)
W=$(bd -C "$A" --sandbox create "E3 bystander W" --type task --silent)
bash scripts/spike/bds0/rig.sh hop A B --dot "A:e3-seed" "$Z" "$Y" "$W"
```

```json
{"created": 3, "ids": ["bdsa-tcy","bdsa-1p5","bdsa-3pm"], "schema_version": 1,
 "skipped": 0, "source": "stdin"}
```

Now B makes two local edits and never exports them. The `sleep 1` is load
bearing: without it the local edit can land in the same wall-clock second as
the seed hop and would not be strictly newer.

```bash
sleep 1
bd -C "$B" --sandbox update "$Z" --description "B local edit at T+20, never exported"
bd -C "$B" --sandbox update "$W" --description "B local edit on bystander, never exported"
```

B's state before either import (`now(UTC)=2026-08-13T15:58:33Z`):

```json
{"id":"bdsa-3pm","title":"E3 clobber target Z","description":"B local edit at T+20, never exported","updated_at":"2026-08-13T15:58:29Z","metadata":{"cn_dot":"A:e3-seed"}}
{"id":"bdsa-1p5","title":"E3 unrelated newer Y","description":null,"updated_at":"2026-08-13T15:58:24Z","metadata":{"cn_dot":"A:e3-seed"}}
{"id":"bdsa-tcy","title":"E3 bystander W","description":"B local edit on bystander, never exported","updated_at":"2026-08-13T15:58:30Z","metadata":{"cn_dot":"A:e3-seed"}}
```

A's bundle forges `Z` and `W` into the past (`15:53:00Z` — the "T+5" role,
older than B's local edits) and `Y` into the future (`16:58:00Z` — the "T+30"
role, newer than B's copy):

```bash
build() {
bd -C "$A" --sandbox export | jq -c --arg z "$Z" --arg y "$Y" --arg w "$W" '
  select(.id==$z or .id==$y or .id==$w)
  | if .id==$z then .updated_at="2026-08-13T15:53:00Z"
                   | .description="A stale description (T+5)"
                   | .metadata=((.metadata//{})+{cn_dot:"A:9"})
    elif .id==$w then .updated_at="2026-08-13T15:53:00Z"
                   | .description="A stale bystander description"
                   | .metadata=((.metadata//{})+{cn_dot:"A:10"})
    else .updated_at="2026-08-13T16:58:00Z"
                   | .title="E3 unrelated newer Y (updated by A at T+30)"
                   | .metadata=((.metadata//{})+{cn_dot:"A:11"})
    end'
}
```

```json
{"_type":"issue","id":"bdsa-tcy","title":"E3 bystander W","status":"open","priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T15:58:26Z","created_by":"MacBoo","updated_at":"2026-08-13T15:53:00Z","dependency_count":0,"dependent_count":0,"comment_count":0,"description":"A stale bystander description","metadata":{"cn_dot":"A:10"}}
{"_type":"issue","id":"bdsa-1p5","title":"E3 unrelated newer Y (updated by A at T+30)","status":"open","priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T15:58:24Z","created_by":"MacBoo","updated_at":"2026-08-13T16:58:00Z","dependency_count":0,"dependent_count":0,"comment_count":0,"metadata":{"cn_dot":"A:11"}}
{"_type":"issue","id":"bdsa-3pm","title":"E3 clobber target Z","status":"open","priority":2,"issue_type":"task","owner":"mlboogerd@gmail.com","created_at":"2026-08-13T15:58:23Z","created_by":"MacBoo","updated_at":"2026-08-13T15:53:00Z","dependency_count":0,"dependent_count":0,"comment_count":0,"description":"A stale description (T+5)","metadata":{"cn_dot":"A:9"}}
```

### E3 control — the same bundle, no flag

```bash
build | bd -C "$B" --sandbox import - --json
```

```json
{"created": 1, "ids": ["bdsa-1p5"], "schema_version": 1, "skipped": 2, "source": "stdin",
 "stale_skipped_ids": ["bdsa-tcy", "bdsa-3pm"],
 "updated": 1,
 "updated_issues": [{"changes": "title, metadata", "id": "bdsa-1p5"}]}
```

```json
{"id":"bdsa-3pm","title":"E3 clobber target Z","description":"B local edit at T+20, never exported","updated_at":"2026-08-13T15:58:29Z","metadata":{"cn_dot":"A:e3-seed"}}
{"id":"bdsa-1p5","title":"E3 unrelated newer Y (updated by A at T+30)","description":null,"updated_at":"2026-08-13T16:58:00Z","metadata":{"cn_dot":"A:11"}}
{"id":"bdsa-tcy","title":"E3 bystander W","description":"B local edit on bystander, never exported","updated_at":"2026-08-13T15:58:30Z","metadata":{"cn_dot":"A:e3-seed"}}
```

**Unflagged, the adjudication is per row within one run.** `Y` was applied,
`Z` and `W` were skipped, and the report names exactly which. This is the
behavior a replication write path wants — and it is exactly the behavior that
loses the dot-order decision for `Z`.

### E3 main — the same bundle with `--allow-stale`

Identical bundle except `Y`'s forged `updated_at` advanced to `16:59:00Z`
(the control already applied `16:58:00Z`, so `Y` would otherwise be a tie here
and its outcome uninformative). `Z` and `W` are byte-identical to the control's
rows.

```bash
bd -C "$A" --sandbox export | jq -c --arg z "$Z" --arg y "$Y" --arg w "$W" '
  select(.id==$z or .id==$y or .id==$w)
  | if .id==$z then .updated_at="2026-08-13T15:53:00Z"
                   | .description="A stale description (T+5)"
                   | .metadata=((.metadata//{})+{cn_dot:"A:9"})
    elif .id==$w then .updated_at="2026-08-13T15:53:00Z"
                   | .description="A stale bystander description"
                   | .metadata=((.metadata//{})+{cn_dot:"A:10"})
    else .updated_at="2026-08-13T16:59:00Z"
                   | .title="E3 unrelated newer Y (updated by A at T+30)"
                   | .metadata=((.metadata//{})+{cn_dot:"A:11"})
    end' \
 | bd -C "$B" --sandbox import - --json --allow-stale
```

```json
{"created": 3, "ids": ["bdsa-tcy", "bdsa-1p5", "bdsa-3pm"], "schema_version": 1,
 "skipped": 0, "source": "stdin"}
```

```json
{"id":"bdsa-3pm","title":"E3 clobber target Z","description":"A stale description (T+5)","updated_at":"2026-08-13T15:53:00Z","metadata":{"cn_dot":"A:9"}}
{"id":"bdsa-1p5","title":"E3 unrelated newer Y (updated by A at T+30)","description":null,"updated_at":"2026-08-13T16:59:00Z","metadata":{"cn_dot":"A:11"}}
{"id":"bdsa-tcy","title":"E3 bystander W","description":"A stale bystander description","updated_at":"2026-08-13T15:53:00Z","metadata":{"cn_dot":"A:10"}}
```

Three facts, each measured:

1. **`Z`'s never-gossiped newer local edit is clobbered.** `"B local edit at
   T+20, never exported"` is gone, replaced by the T+5 value, and `Z`'s stored
   `updated_at` regressed from `15:58:29Z` to `15:53:00Z`. The local edit is
   not recoverable from anything the import printed; it exists only in B's Dolt
   history.
2. **`Y` lands, as intended.** The wanted row is delivered.
3. **`W`, the bystander, is clobbered identically** — same loss, on a row the
   sender never meant to touch. Its only sin was being in the same bundle.

So the answer to the granularity question: **`--allow-stale` is per import
run, not per row.** Two independent lines of evidence:

- Measured: within one run it overwrote `Z` (stale, wanted) and `W` (stale,
  unwanted) alike, and there is no bundle-level or row-level syntax by which
  they could have been distinguished.
- The CLI surface confirms there is no narrower form. `bd import --help` lists
  exactly four command flags:

  ```
        --allow-stale    Import rows even when older than the local issue (required to restore an older snapshot)
        --dedup          Skip lines whose title matches an existing open issue
        --dry-run        Show what would be imported without importing
    -h, --help           help for import
    -i, --input string   Read JSONL from a specific file
  ```

  Nothing scopes staleness per row, per id, or per predicate. The only way to
  narrow the blast radius from the outside is to narrow the *bundle* — one
  `bd import --allow-stale` invocation per row you actually intend to force.

**And the report does not name the damage.** The unflagged control listed
`stale_skipped_ids: ["bdsa-tcy","bdsa-3pm"]` and `updated_issues` with a
field-level `changes` string. The flagged run of the same bundle reported
`{"created": 3, "skipped": 0}` and nothing else — no `updated`, no
`updated_issues`, no list of which rows overwrote newer local state. A caller
that wanted to detect "I just destroyed two local edits" cannot do it from this
output.

## E4 — same-second adjudication: one-second resolution, plus a rounding defect

### E4-1/E4-2 on `Q` — the first, muddier pass

Fresh issue `Q` = `bdsa-clr`, hopped into B, stored at `2026-08-13T16:00:02Z`.

```bash
Q=$(bd -C "$A" --sandbox create "E4 same-second subject Q" --type task --silent)
bash scripts/spike/bds0/rig.sh hop A B --dot "A:e4-seed" "$Q"
# B stored updated_at = 2026-08-13T16:00:02Z
```

Incoming `2026-08-13T16:00:02.400Z` — same second, 400 ms later:

```json
{"created":1,"skipped":0,"updated":null,"tie_kept_local_ids":["bdsa-clr"],"stale_skipped_ids":null,"updated_issues":null}
{"title":"E4 same-second subject Q","updated_at":"2026-08-13T16:00:02Z"}
```

Tie; local kept; stored row untouched. **Sub-second precision is accepted by
the parser** — no error — and then discarded.

Incoming `2026-08-13T17:30:00.400Z` — strictly newer by ~90 minutes:

```json
{"created":1,"skipped":0,"updated":1,"tie_kept_local_ids":null,"stale_skipped_ids":null,"updated_issues":[{"changes":"title, metadata","id":"bdsa-clr"}]}
{"title":"E4-2 newer with subsecond","updated_at":"2026-08-13T17:30:00Z"}
```

Overwrite lands, and **the stored row carries `17:30:00Z`: the `.400` is gone.**
B's re-export agrees —

```bash
bd -C "$B" --sandbox export | jq -c --arg q "$Q" 'select(.id==$q) | {updated_at}'
```

```json
{"updated_at":"2026-08-13T17:30:00Z"}
```

— so the store keeps whole seconds only, on both the read and the export path.

The next push on `Q` produced a result that did not fit ("truncation"), so `Q`
was abandoned and the question re-run in isolation on `R`. `Q`'s four remaining
lines are printed here rather than dropped:

```
incoming 2026-08-13T17:30:00.900Z  -> {"tie_kept_local_ids":["bdsa-clr"]}   stored: title changed, updated_at 2026-08-13T17:30:01Z
incoming 2026-08-13T17:29:59.999Z  -> {"stale_skipped_ids":["bdsa-clr"]}    stored: unchanged
incoming 2026-08-13T18:00:00.900Z  -> {"updated":1}                          stored: title "E4-5 newer, 900ms", updated_at 2026-08-13T18:00:01Z
incoming 2026-08-13T18:00:00.100Z  -> {"created":1,"skipped":0}  (--allow-stale)  stored: title "E4-6 same second, earlier subsecond, allow-stale", updated_at 2026-08-13T18:00:00Z
```

The third line confirms the rounding on an unambiguous overwrite (`.900` stored
as `:01`), and the fourth is the `--allow-stale` case: an incoming
`18:00:00.100Z` against a stored `18:00:01Z` row is older by any reading, and
under the flag it is written anyway, dragging the stored `updated_at` back to
`18:00:00Z`.

The first line is the anomaly: a reported **tie** whose stored row nevertheless
changed, and whose `updated_at` came out one second *later* than anything in
the incoming row. That is what `R` isolates.

### E4-7 on `R` — the isolated re-verification

Fresh issue `R` = `bdsa-1ju`, hopped into B, then driven to a known whole-second
baseline. Each step below is the same pipeline, forging `updated_at`, `title`
and `description`.

Baseline, incoming `2026-08-13T20:00:00Z`:

```json
{"created":1,"skipped":0,"updated":1,"tie_kept_local_ids":null,"stale_skipped_ids":null,"updated_issues":[{"changes":"title, description, metadata","id":"bdsa-1ju"}]}
{"title":"E4-7 baseline at 20:00:00Z","description":"E4-7 baseline at 20:00:00Z","updated_at":"2026-08-13T20:00:00Z"}
```

**(A)** incoming `2026-08-13T20:00:00.400Z` — same second, `.400`:

```json
{"created":1,"skipped":0,"updated":null,"tie_kept_local_ids":["bdsa-1ju"],"stale_skipped_ids":null,"updated_issues":null}
{"title":"E4-7 baseline at 20:00:00Z","description":"E4-7 baseline at 20:00:00Z","updated_at":"2026-08-13T20:00:00Z"}
```

Reported tie, and the stored row genuinely is untouched. Report and store agree.

**(B)** incoming `2026-08-13T20:00:00.900Z` — same second, `.900`:

```json
{"created":1,"skipped":0,"updated":null,"tie_kept_local_ids":["bdsa-1ju"],"stale_skipped_ids":null,"updated_issues":null}
{"title":"E4-7b incoming .900","description":"E4-7b incoming .900","updated_at":"2026-08-13T20:00:01Z"}
```

**The report says `tie_kept_local_ids` and the local row was not kept.** Title
and description are the incoming row's, and `updated_at` is `20:00:01Z` — a
value that appears in neither the incoming row nor the prior stored row. The
sub-second part was **rounded to nearest**, `.900` → the next second, and the
rounded value beat the stored `20:00:00Z`. The report's adjudication and the
upsert's adjudication disagree.

**(C)** the same `.900` row again, now against the `20:00:01Z` stored row:

```json
{"created":0,"skipped":1,"updated":null,"tie_kept_local_ids":null,"stale_skipped_ids":["bdsa-1ju"],"updated_issues":null}
{"title":"E4-7b incoming .900","description":"E4-7b incoming .900","updated_at":"2026-08-13T20:00:01Z"}
```

Stale and correctly not written. So the disagreement is one-directional: it
appears when rounding *lifts* an incoming row across a second boundary that the
report's comparison does not see.

### E4-8 on `S` — the rounding boundary

Fresh issue `S` = `bdsa-rnh`, driven to a `2026-08-13T21:00:00Z` baseline:

```
incoming 2026-08-13T21:00:00.499Z -> {"tie_kept_local_ids":["bdsa-rnh"]}  stored: {"title":"baseline 21:00:00Z","updated_at":"2026-08-13T21:00:00Z"}
incoming 2026-08-13T21:00:00.500Z -> {"tie_kept_local_ids":["bdsa-rnh"]}  stored: {"title":"E4-8 .500","updated_at":"2026-08-13T21:00:01Z"}
```

The boundary is exactly `.500`: round-half-up, not truncation. `.499` is a real
tie (report and store agree); `.500` and above is a silent overwrite reported
as a tie.

Re-measured during review on an independent rig root (`S` = `bdsa-72b`), with
extra probes on either side of the boundary and on the shorter `.5` spelling.
Each line is driven from a fresh whole-second baseline, so the stored value
before each probe is the baseline shown:

```
baseline 21:00:00Z; incoming 21:00:00.499Z  -> {"tie_kept_local_ids":["bdsa-72b"]}  stored unchanged: "baseline 21:00:00Z" @ 21:00:00Z
             then;  incoming 21:00:00.500Z  -> {"tie_kept_local_ids":["bdsa-72b"]}  stored CHANGED:   "E4-8 .500"        @ 21:00:01Z
baseline 22:00:00Z; incoming 22:00:00.501Z  -> {"tie_kept_local_ids":["bdsa-72b"]}  stored CHANGED:   "E4 .501"          @ 22:00:01Z
baseline 23:00:00Z; incoming 23:00:00.4999Z -> {"tie_kept_local_ids":["bdsa-72b"]}  stored unchanged: "baseline 23:00:00Z" @ 23:00:00Z
             then;  incoming 23:00:00.5Z    -> {"tie_kept_local_ids":["bdsa-72b"]}  stored CHANGED:   "E4 .5 one digit"  @ 23:00:01Z
```

`.4999` still keeps local and `.5` written with a single digit rounds up
exactly as `.500` does, so the boundary is on the *value*, not on the number of
fractional digits given. And the reverse direction holds under both spellings:
re-importing the same `.5` row against the now-`23:00:01Z` stored row reported
`{"created":0,"skipped":1,"stale_skipped_ids":["bdsa-72b"]}` and wrote nothing.

### E4 summary of what one-second resolution actually does

- Two writes in the same second whose sub-second parts round to the **same**
  second: **tie**, local kept, report and store agree. This is the documented
  behavior and it holds.
- Two writes in the same second whose sub-second parts round to **different**
  seconds (incoming ≥ `.500`, stored `< .500` or whole): **incoming wins and
  overwrites**, while the report claims `tie_kept_local_ids`. A same-second
  write can therefore clobber a local row **without `--allow-stale`**, and the
  import report will say it did not.
- Sub-second precision is accepted on input and never stored: `bd show` and
  `bd export` both emit whole seconds only.
- `--allow-stale` overrides all of the above: incoming `18:00:00.100Z` against
  a stored `18:00:01Z` row was written, and the stored row took the rounded
  incoming value (`18:00:00Z`).

Not diagnosed here: whether the rounding happens in bd's timestamp parsing or
in the Dolt `datetime` column underneath it, and whether the report's
comparison reads the pre-rounding or post-rounding value. Both are one
implementation read away, and neither changes the observed behavior.

## Reproduction on an independent rig root

The whole sequence — E1a/E1b/E1c, E2, the E2 stamp-only variant, E3 control and
E3 `--allow-stale`, and the E4 baseline/`.400`/`.900`/`.900`-again ladder — was
re-run as one script against a second `rig.sh init` root
(`/var/folders/.../tmp.rYHuXN0cBU`, ids `bdsa-l0k`, `bdsa-3yp`, `bdsa-775`,
`bdsa-7cj`). Every outcome matched: `updated:1` / `tie_kept_local_ids` /
`stale_skipped_ids` in E1; total imposition and the silent report in E2; the
bare stamp landing under the flag in the E2 variant; `Z` and `W` skipped by the
control and both clobbered under the flag in E3; and in E4 the `.400` tie that
holds, the `.900` "tie" that overwrites and lands `20:00:01Z`, and the repeat
`.900` that is correctly stale.

## What did not work

No command in this run failed outright — there is no error transcript to show.
Everything in this list is a behavior that defeats an obvious use, or a
surprise, and each is measured above.

- **`rig.sh hop` cannot forge `updated_at`**, which every experiment here needs.
  The manual `export | jq | import` pipeline is the in-model substitute; `hop`
  was used unchanged for the create/seed hops.
- **Narrowing `--allow-stale` to the rows you actually mean.** There is no
  per-row, per-id or predicate form; `bd import --help` lists only
  `--allow-stale`, `--dedup`, `--dry-run`, `-i`. Measured consequence: the
  bystander `W` was destroyed alongside the intended `Z`.
- **Detecting the damage from the import report.** Under `--allow-stale` the
  report drops `stale_skipped_ids`, `updated` and `updated_issues` entirely —
  the one flag that can overwrite newer local state is the one that stops
  reporting which rows it overwrote.
- **Trusting `tie_kept_local_ids`.** With an incoming sub-second `updated_at`
  of `.500` or higher, the report says tie and the row is overwritten anyway
  (E4-7b, E4-8). The report is not a reliable record of what the import did.
- **Storing sub-second precision.** Accepted on input, never stored; `bd show`
  and `bd export` emit whole seconds.
- **Keeping the store's clock monotonic under `--allow-stale`.** Imposing an
  older row moves the stored `updated_at` backwards (E2: `16:56:19Z` →
  `16:26:19Z`; E3: `Z` from `15:58:29Z` to `15:53:00Z`). Any later LWW
  comparison on that row is against the regressed value.
- **Advancing a row's clock with a stamp-only write.** Under `--allow-stale` a
  metadata-only import lands the new `cn_dot` but leaves `updated_at` untouched
  (E2 variant), so the write is invisible to every subsequent LWW comparison.

Surprises worth recording:

- **`bd import` accepts a future `updated_at` without complaint** and stores it
  verbatim — no sanity check against the local clock. This is what makes the
  forge-the-export technique work at all, and it is also a live hazard: a peer
  with a fast clock wins every LWW comparison until real time catches up.
- **The `created` counter quirk claim (a) recorded reproduces exactly here**
  (`created` counts candidate rows; the stale path is the one shape reporting
  `0`), and `--allow-stale` makes it worse: `{"created": 3, "skipped": 0}` on
  the E3 bundle described three overwrites, one of which was a create-shaped
  count for a row that already existed and two of which destroyed local edits.

Explicitly **not tested** in this section, and therefore not claimed either way:

- Whether `--allow-stale` also bypasses the in-upsert guard the help text
  describes ("a local update that lands while the import is running is
  preserved"). Nothing here raced a local write against an import.
- How `labels`, `comments` and `dependencies` behave on the tie, stale and
  `--allow-stale` paths. The help says they still merge on a tie; this section
  measured scalar columns and `metadata` only.
- `--dry-run`, alone or combined with `--allow-stale`.
- Whether the same adjudication holds outside embedded mode, or under a
  `--dolt-auto-commit` policy other than the default.
- Whether closed/tombstone rows adjudicate the same way — that is claim (c).

## Verdict and implications

<!-- PLACEHOLDER — owned by the sibling task (computenet-8kj.3 follow-up).
     Do not fill in from this section: the verdict, the pass/fail roll-up, the
     BDS1/BDS4 implications and the narrower-instrument design are that task's,
     not this one's. -->
