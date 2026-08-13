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

Verdict in one line: **claim (b) is answered yes-with-an-unacceptable-cost** —
`--allow-stale` does deliver ordering authority, but only at whole-bundle
granularity and with the loss report switched off, so the epic's conditional
fires and the replication write path needs a narrower instrument. One is
**verified on the rig**: a per-row `--allow-stale` invocation, preceded by a
writer-side pre-flight against B's own export. Its cost is measured, and it
collides with the very Dolt-commit-per-write pattern BDS4 §2 cites migration
0055 to avoid. See "Verdict and implications" at the end; the sub-checks come
first.

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

Everything asserted below traces to a transcript in this section, to a
transcript in `claim-a-echo-suppression.md`, or to a probe transcript printed
in this subsection. Where a proposal was not measured, it says so in its own
heading.

### Pass/fail, per sub-check

| Sub-check | Result | What was actually established |
|---|---|---|
| **E1 baseline** — the three unflagged outcomes | **PASS** | All three reproduced on demand: strictly-newer overwrites (`updated: 1`), equal `updated_at` keeps local (`tie_kept_local_ids`), older is skipped (`stale_skipped_ids`). The documented rule holds — subject to E4. |
| **E2 imposition** — does `--allow-stale` impose the dot-order winner? | **PASS** | Total, on the decided definition: after the flagged import B's stored `title`, `description`, `status` and `metadata.cn_dot` all equal the incoming row. The authority claim (b) asks for exists. |
| **E3 clobber** — does it destroy never-gossiped newer local edits, and at what granularity? | **The claim's premise fails: it clobbers, per run** | `Z`'s never-gossiped local edit was destroyed, and so was bystander `W`'s — a row the sender never intended to change. The flag is a **per-import-run** switch with **no per-row form** (`bd import --help` offers only `--allow-stale`, `--dedup`, `--dry-run`, `-i`). |
| **E4 same-second** — how one-second resolution adjudicates | **PASS, with an independent defect found** | Sub-second parts that round to the same second tie, as documented. Sub-second parts ≥ `.500` **round half-up across the second boundary and overwrite**, while the same import reports `tie_kept_local_ids`. Boundary measured at exactly `.500` (`.4999` keeps local; `.5`, `.500`, `.501` all clobber). |

So the claim as the epic poses it — "test whether `--allow-stale` lets the
dot-order winner be imposed, and what it costs" — is answered in both halves:
**it can be imposed (E2), and the cost is that the instrument cannot be aimed
(E3).**

### What `--allow-stale` actually costs, stated once

Four costs, each measured above, in descending order of how much they
constrain a replication write path:

1. **No aim.** The unit of "force this" is the *bundle*, not the row. A single
   bundle containing one row the OR-map says must win and twenty rows it says
   nothing about will impose all twenty-one. Bystander `W` is the proof.
2. **No loss report.** Under the flag the report drops `updated`,
   `updated_issues` and `stale_skipped_ids` and prints
   `{"created": N, "skipped": 0}` — indistinguishable from a clean create-only
   import. The one flag that can destroy newer local state is the one that
   stops naming what it destroyed. A caller cannot detect the damage from the
   output, and the destroyed value survives only in B's Dolt history.
3. **The store's clock regresses.** The stored `updated_at` becomes the
   *incoming* one, so imposing an older row moves the row's clock backwards
   (E2: `16:56:19Z` → `16:26:19Z`; E3: `Z` from `15:58:29Z` to `15:53:00Z`).
   Every later LWW comparison on that row is against the regressed value, so
   one imposition silently widens the window in which a *third* party's stale
   write will also win.
4. **`updated_at` is not trustworthy even unflagged.** E4's rounding defect is
   independent of the flag: a same-second write with `≥ .500` sub-second
   precision clobbers a local row on the plain path and the report calls it a
   tie. So "don't use `--allow-stale` and you are safe" is false.

Cross-referencing claim (a) rather than re-deriving: claim (a) found that a new
`cn_dot` cannot be stamped onto an already-replicated row on its own, because
the import ties. **This section refines that**: the E2 stamp-only variant shows
`--allow-stale` *does* land a bare re-stamp — but the flagged tie leaves
`updated_at` untouched, so the stamped write advances no clock and is invisible
to every subsequent LWW comparison. Claim (a)'s conclusion survives in the form
that matters (a marking pass cannot be made *ordering-visible*), and the
`created`-counter meaning claim (a) established (`created` counts candidate
rows; the outcome is in the tie/stale/updated key beside it) reproduced here
unchanged.

### The epic's conditional: **triggered**

Epic `computenet-8kj` §1(b) hard-codes it: "does it also clobber genuinely
newer *local* edits that were never gossiped? **If it does, the replication
write path needs a narrower instrument than `--allow-stale` and this spike must
say so.**"

It does — measured in E3, on two separate rows, one of which the sender never
intended to touch, and reproduced on a second independent rig root. So this
section says so:

> **The BDS0/BDS4 replication write path must not use bare
> `bd import --allow-stale` over a multi-row bundle.** The instrument it needs
> is one whose blast radius is exactly the set of rows the OR-map adjudicated,
> and which reports what it overwrote.

What follows names and sketches the candidates, per the feature's out-of-scope
clause. **Designing the instrument is not this section's job** and is not done
here.

### The narrower instrument

Marked **VERIFIED ON THE RIG** or **PROPOSED, UNTESTED** — the distinction is
the point, and it is the discipline claim (a)'s verdict was accepted for.

#### Instrument 1 — writer-side pre-flight + one `--allow-stale` invocation per row. **VERIFIED ON THE RIG.**

The observation that makes it work: `--allow-stale`'s granularity is the
*bundle*, and the writer controls the bundle. Narrowing the bundle to one row
narrows the blast radius to one row.

Measured on a fresh rig root (`tmp.bTRFAYvsaz`), reusing E3's exact scenario —
`Z` the intended target with a never-gossiped newer local edit, `Y` a
legitimately newer row, `W` the bystander with its own never-gossiped local
edit. B before:

```json
{"id":"bdsa-i2l","title":"P clobber target Z","description":"B local edit on Z, never exported","updated_at":"2026-08-13T16:20:54Z","metadata":{"cn_dot":"A:p-seed"}}
{"id":"bdsa-u4u","title":"P unrelated newer Y","description":null,"updated_at":"2026-08-13T16:20:49Z","metadata":{"cn_dot":"A:p-seed"}}
{"id":"bdsa-104","title":"P bystander W","description":"B local edit on bystander W, never exported","updated_at":"2026-08-13T16:20:54Z","metadata":{"cn_dot":"A:p-seed"}}
```

Step 1 — the whole bundle **unflagged**, which applies everything LWW already
agrees with and *names the rest*:

```bash
build | bd -C "$B" --sandbox import - --json
```

```json
{"created": 1, "ids": ["bdsa-u4u"], "schema_version": 1, "skipped": 2, "source": "stdin",
 "stale_skipped_ids": ["bdsa-104", "bdsa-i2l"],
 "updated": 1, "updated_issues": [{"changes": "title, metadata", "id": "bdsa-u4u"}]}
```

Step 2 — **one** flagged invocation, carrying only the row the OR-map says must
win:

```bash
build | jq -c --arg z "$Z" 'select(.id==$z)' | bd -C "$B" --sandbox import - --json --allow-stale
```

```json
{"created": 1, "ids": ["bdsa-i2l"], "schema_version": 1, "skipped": 0, "source": "stdin"}
```

B after:

```json
{"id":"bdsa-i2l","title":"P clobber target Z","description":"A stale description (T+5)","updated_at":"2026-08-13T15:53:00Z","metadata":{"cn_dot":"A:9"}}
{"id":"bdsa-u4u","title":"P unrelated newer Y (updated by A)","description":null,"updated_at":"2026-08-13T16:58:00Z","metadata":{"cn_dot":"A:11"}}
{"id":"bdsa-104","title":"P bystander W","description":"B local edit on bystander W, never exported","updated_at":"2026-08-13T16:20:54Z","metadata":{"cn_dot":"A:p-seed"}}
```

**`Z` imposed as intended, `Y` delivered, and `W`'s never-gossiped local edit
survives.** That is the exact E3 outcome with the collateral damage removed,
and the only change is the shape of the invocation. The `stale_skipped_ids`
list from step 1 is also the *inventory* of rows on which the writer now has to
consult the OR-map — the information the flagged path refuses to print is
available from the unflagged pass over the same bundle.

**Verified pre-flight, independent of the report.** The writer can compute the
adjudication itself before importing anything, from B's own export — it does not
have to trust or provoke the import report. Measured on rig root
`tmp.Ipl4pAbSzs`:

```bash
bd -C "$B" --sandbox export | jq -c 'select(._type=="issue")|{id,updated_at,metadata}' > b-state.jsonl
jq -c -s --slurpfile local b-state.jsonl '
  ($local|map({key:.id,value:.})|from_entries) as $L
  | map({id:.id, incoming:.updated_at, local:($L[.id].updated_at // null),
         verdict: (if $L[.id]==null then "create"
                   elif .updated_at > $L[.id].updated_at then "would-overwrite"
                   elif .updated_at == $L[.id].updated_at then "would-tie"
                   else "would-be-skipped-stale" end)})' bundle.jsonl
```

```json
[{"id":"bdsa-ypz","incoming":"2026-08-13T15:53:00Z","local":"2026-08-13T16:22:10Z","verdict":"would-be-skipped-stale"},
 {"id":"bdsa-ddo","incoming":"2026-08-13T15:53:00Z","local":"2026-08-13T16:22:09Z","verdict":"would-be-skipped-stale"}]
```

Both rows correctly predicted, and both were in fact skipped by the real
unflagged import. This is the seam adjacent to claim (a)'s **Seam 3**
(writer-side suppression): the same process runs the import, so it can also
decide the import.

**The measured costs, none of them argued away:**

- **One process and one Dolt commit per forced row.** Measured on rig root
  `tmp.GwXRrGYqeL`, six stale rows against six never-gossiped local edits, each
  path run from equivalent fresh state:

  ```
  6 x single-row --allow-stale : 4.458s ; dolt commits added: 6
  one bundle of 6 --allow-stale: 0.958s ; dolt commits added: 1
  ```

  ≈ 0.74 s and one commit per row, versus ≈ 0.16 s and one-sixth of a commit
  per row for the bundle. The instrument is linear in *rows*, not in bundles.
  This is the load-bearing cost and it is a BDS4 constraint, not a performance
  footnote — see the BDS4 implications below.
- **The flagged step still reports nothing.** Measured (same rig root): a
  single-row `--allow-stale` import that overwrote a strictly newer local edit
  (`"B local edit Z round 2"` at `16:22:18Z`, replaced by `"A stale Z"` at
  `15:53:00Z`) reported `{"created": 1, "ids": ["bdsa-ddo"], "skipped": 0}` and
  nothing else. Narrowing the bundle narrows the *damage*, not the *silence* —
  the writer knows what it overwrote only because it chose the row.
- **The pre-flight is inherently racy.** Between reading B's export and running
  the import, a local `bd update` can land. This section did not race a local
  write against an import (already listed as untested above), so the size of
  that window is unmeasured.
- **The rounding defect is not fixed by this instrument.** It applies to the
  unflagged step 1 as much as to any other plain import.

#### Instrument 2 — forge the outbound `updated_at` so plain LWW agrees with dot order. **PARTLY VERIFIED, and not recommended.**

**Verified:** the mechanism works and needs no flag at all. E1a landed an
overwrite purely by forging `updated_at` an hour into the future, and the
importer accepted a timestamp ahead of its own wall clock
(`now(UTC)=2026-08-13T15:57:32Z`) without complaint. Every experiment in this
section is itself a demonstration that the outbound row's `updated_at` fully
controls the adjudication.

**Verified costs, and they are severe:**

- It **falsifies the timestamp**. `updated_at` stops being when the issue
  changed and becomes an encoding of dot order. Every human and every other
  tool reading the workspace — `bd list`, `bd ready`, the `/work` skill, a
  person answering "when did this change" — reads the lie.
- It **collides with E4** in both directions. Whole-second storage means dot
  order has to be encoded in units of one second; and any forged value whose
  sub-second part is ≥ `.500` silently rounds into the next second, which
  corrupts the encoding at exactly the granularity it needs.
- It is a **one-way ratchet**. To always win, forged values must always
  increase, so the workspace's clock runs ahead of real time permanently —
  which is the same hazard as the fast-clock peer recorded in the surprises
  above, deliberately induced.

Recorded so it is not re-proposed as the cheap option: it is cheap, it works,
and it destroys the meaning of a field the rest of beads depends on.

#### Instrument 3 — a `bd`-side row-level ordering override. **PROPOSED, UNTESTED.**

The shape the measurements point at, since the outside-in options all trade
something real: an upstream beads change giving `bd import` row-level
authority, e.g.

- a per-row `"allow_stale": true` key honoured in the import JSONL, or an
  `--allow-stale-ids <id,...>` scope flag, so one invocation can carry a
  bundle while forcing only the adjudicated rows — this removes Instrument 1's
  commit-per-row cost, which is its only serious defect; and
- an import report that names overwritten rows **under** `--allow-stale`
  (`updated_issues` already carries a field-level `changes` string on the
  unflagged path; the flagged path just stops emitting it). This is the
  smallest possible fix to cost 2 and is additive.

Nothing here tested either. Both are strictly larger than a ComputeNet-side
change and neither is required for BDS4 to proceed, because Instrument 1 is
verified and sufficient — they are the way to *lower its cost*, not to make it
possible. Whether to attempt them upstream is `computenet-8kj.6`'s call.

#### Insufficient, recorded so it is not re-proposed

- **`bd import --dry-run` as a pre-flight staleness probe.** It does not
  adjudicate. Measured (rig root `tmp.bTRFAYvsaz`), on the bundle whose real
  unflagged import reported `stale_skipped_ids: ["bdsa-104","bdsa-i2l"]`:

  ```bash
  build | bd -C "$B" --sandbox import - --json --dry-run
  build | bd -C "$B" --sandbox import - --json --dry-run --allow-stale
  ```

  ```json
  {"created": 3, "dry_run": true, "schema_version": 1, "skipped": 0, "source": "stdin"}
  {"created": 3, "dry_run": true, "schema_version": 1, "skipped": 0, "source": "stdin"}
  ```

  Both forms report the same thing, both report `skipped: 0`, and neither names
  a stale row. B was verified unchanged after both, so the flag is safe — it is
  just uninformative. It reports what the bundle *contains*, not what the
  import would *do*. (This refines the "explicitly not tested" list above,
  which listed `--dry-run` as untested; it is now tested, alone and combined,
  and it is not the pre-flight.) The verified pre-flight is the writer-side
  comparison against B's export shown under Instrument 1.
- **Reading the flagged import report to learn what was destroyed.** Measured
  three times over (E3 bundle, single-row P5, and the E2 imposition): the
  flagged report never names an overwritten row.
- **A separate "mark as replicated" pass under the flag.** The E2 stamp-only
  variant lands the new `cn_dot` but leaves `updated_at` untouched, so the mark
  is invisible to every later LWW comparison. It cannot be used to make a row
  ordering-safe after the fact.

### Implications for BDS1 (`computenet-dqj`)

Named, not adjudicated — adjudication belongs to `computenet-8kj.6`.

1. **BDS1's core is untouched by claim (b), and that is worth stating plainly.**
   BDS1 is a read-only, single-node projector: "there is no echo problem
   because ComputeNet never writes into the map", and its checkpoint is a
   per-replica journal `seq`, not a timestamp. Nothing measured here disturbs
   either. Claim (b) is a *write-path* finding; claim (a) is the one that hits
   BDS1's feed.
2. **BDS1 must not adopt `updated_at` as an ordering key anywhere.** E4 makes
   it unsound at the resolution BDS1 would need, and E2/E3 make it
   non-monotonic once BDS4 exists (an imposed row's `updated_at` moves
   backwards). BDS1's design already uses dot order, so this is a constraint to
   record, not a change to make.
3. **BDS1's equality test needs a caveat once write-back exists.** Its test is
   "mirror state equals `bd export` of the same workspace" driven by scripted
   local `bd` mutations. On that path the test is sound. Run the same test on a
   workspace whose writes arrive by `bd import --allow-stale` and the mirror's
   ordering and the store's `updated_at` are no longer two views of one fact —
   a row can be older in the store than the mirror says it is. This is the same
   shape of gap claim (a) found in BDS1's test plan, arriving from the other
   side.
4. **Re-baseline from `bd export` is unaffected and remains reliable.** Export
   emits whole seconds and preserves `metadata` (claim (a) sub-check (iii)), so
   nothing here degrades the truncation-recovery path.

### Implications for BDS4 (`computenet-6wc`)

1. **BDS4 §1's open question is now closed, against `--allow-stale`.** Its text
   says: "BDS0 establishes whether `--allow-stale` is the right instrument or
   whether it over-reaches by clobbering un-gossiped local edits; follow its
   finding." The finding: **it over-reaches.** BDS4 must specify the write path
   as *unflagged bundle import for rows LWW already agrees with, plus one
   `--allow-stale` invocation per row the OR-map adjudicated against the local
   clock* (Instrument 1, verified), and must not specify a flagged bulk import.
2. **The new constraint BDS4 did not anticipate: this reintroduces
   commit-per-write on the content plane.** BDS4 §2 quotes migration 0055 to
   argue that heartbeats-as-row-updates meant "a Dolt commit per heartbeat …
   the dominant source of unbounded reachable history and of the constant write
   traffic that starves large catch-up merges" — and takes that as the reason
   the lease plane must leave beads. Measured here: the narrow instrument costs
   **one Dolt commit per forced row** (6 rows → 6 commits, versus 1 for the
   bundle). Content replication is far lower-frequency than heartbeats, so this
   is not the same order of hazard — but it is the same *mechanism*, on the
   plane BDS4 keeps inside beads, and BDS4 should size it rather than inherit
   it silently. Two mitigations are visible and **neither was tested**:
   `--dolt-auto-commit batch` (flagged as a risk in claim (a)'s Seam 1 for the
   opposite reason — it coarsens the change feed), and Instrument 3's
   `--allow-stale-ids` scope, which would collapse N invocations back to one.
3. **BDS4 cannot use the import report as its write acknowledgment.** The
   flagged report names nothing it overwrote, and the unflagged report actively
   lies on the E4 rounding path (`tie_kept_local_ids` for a row it overwrote).
   Any "did my replicated write land?" check must be a read-back, not a report
   parse. This compounds claim (a)'s finding that imported updates journal
   nothing: neither the report nor the journal is a reliable acknowledgment,
   and the surviving verified confirmation surface is the Dolt commit graph
   (claim (a) Seam 1) or a plain `bd show`/`bd export` read-back.
4. **Local edits destroyed by a replicated write are recoverable only from Dolt
   history.** E3's clobbered description exists nowhere in bd's output. If
   BDS4 wants a "your local edit lost to a peer" signal — which the
   compare-and-abort discipline in §2 suggests it should want on the content
   plane too — it must capture the prior value itself, in the pre-flight, before
   the import. The pre-flight already reads it (Instrument 1's `b-state.jsonl`).
5. **`updated_at` stops being a clock under BDS4 and starts being a
   replication artifact.** Imposed rows move it backwards. Anything that reads
   it as "when did this change" — a human, `bd list` ordering, the `/work`
   skill's staleness heuristics, `bv --robot-alerts`' stale-issue detection —
   is reading a value BDS4 rewrote for ordering reasons. BDS4 should state this
   as an accepted consequence or reject it, the same way it accepts partition
   as a property rather than a defect.
6. **A same-second clobber can happen with no flag at all.** E4's `≥ .500`
   rounding means BDS4's *unflagged* step-1 import can destroy a local edit and
   report a tie. Since BDS4 replicates from real peer writes, whose
   `updated_at` values it does not control, this is reachable in production and
   not just under forging. It is also the one finding here that is a plain
   **beads defect** rather than a design tension, and it is worth reporting
   upstream independently of whether the BDS line proceeds.
7. **Untouched by claim (b):** the lease plane (§2) never goes through
   `bd import` at all, and the two §3 hazards (epic breakdown, auto-merge) turn
   on `bd create` idempotency and lease re-checks, not on import ordering.
   Hard deletes remain out of scope; whether tombstones adjudicate the same way
   is claim (c)'s.

### Recommendation carried to `computenet-8kj.6`

Claim (b) is **not** a blocker for the BDS line, and it is **not** a clean pass
either. The narrow reading — "can the OR-map's clock-free decision be imposed
on the local store?" — is answered **yes**. The wide reading — "is
`--allow-stale` the instrument for it?" — is answered **no**, and the epic's
conditional therefore fires. A verified replacement exists that needs no beads
change (Instrument 1), so BDS4 can be written against a measured seam rather
than an assumed one; what `8kj.6` has to weigh is whether one Dolt commit per
replicated row is acceptable at BDS4's expected write rate, and whether the E4
rounding defect should be fixed upstream before the line proceeds. Those two
judgments, and the overall go/re-scope, are `8kj.6`'s and are deliberately not
made here.

### What would overturn this verdict

Each is a single command away, and any one changes the conclusion:

- **A per-row or per-id staleness scope in a later bd.** `bd import --help` is
  the whole check. It would retire Instrument 1's commit-per-row cost and make
  the "narrower instrument" clause satisfiable inside one invocation.
- **`--allow-stale` reporting `updated_issues`/`stale_skipped_ids` in a later
  bd.** Re-run the E3 flagged import and read the report. That removes cost 2
  outright and makes the flag auditable, though not aimable.
- **A fix to the `≥ .500` round-half-up, or a report that reads the
  post-rounding value.** Re-run the E4-8 ladder on `S`. Either fix makes the
  unflagged path honest again and removes BDS4 implication 6.
- **Sub-second `updated_at` actually stored.** `bd show` and `bd export` are
  the check. That would make Instrument 2 (forged outbound timestamps)
  materially less bad, since dot order could be encoded below the second.
- **A clock sanity check rejecting future `updated_at` on import.** Re-run
  E1a. It would kill the forging technique this whole section is built on
  (every transcript here would need re-deriving) and would also close the
  fast-clock-peer hazard.
- **`bd import` under a non-default `--dolt-auto-commit` policy collapsing the
  per-row invocations into one commit.** Untested here; it would remove
  Instrument 1's only serious cost, and is the single most valuable follow-up
  measurement.
- **A local write racing an import.** The help text claims a local update
  landing mid-import is preserved. Nothing here raced one. If that guard also
  fires under `--allow-stale`, the clobber window is narrower than E3 suggests;
  if it does not, it is wider.

### What did not work, in this subsection

- **`bd import --dry-run` as a staleness pre-flight.** Reports
  `{"created": 3, "dry_run": true, "skipped": 0}` with no `stale_skipped_ids`,
  identically with and without `--allow-stale`, on a bundle whose real
  unflagged import skipped two rows as stale. It reports bundle contents, not
  adjudication. (This is new measurement; the section above listed `--dry-run`
  as untested.)
- **Getting a loss report out of any `--allow-stale` invocation, however
  narrow.** A single-row flagged import that destroyed a strictly newer local
  edit still reported only `{"created": 1, "skipped": 0}`.
- **Comparing commit counts between the bundle path and the per-row path on a
  workspace that had already received the bundle.** The rows were then
  byte-identical, the re-import was a no-op, and Dolt added **zero** commits —
  a plausible-looking `0 vs 1` that means nothing. The measurement was redone
  from equivalent fresh state on both paths (`6 vs 1`), and the confounded
  first attempt is recorded here so the number is not re-derived wrongly.

Safety, restated for the probes in this subsection: all of them ran against
fresh `mktemp -d` rig roots (`tmp.bTRFAYvsaz`, `tmp.Ipl4pAbSzs`,
`tmp.GwXRrGYqeL`) via `bd -C <ws> --sandbox` and `rig.sh`. Nothing read or
wrote the repository's live `.beads`. Unlike the E1–E4 transcripts above, these
instrument probes were each measured on **one** rig root at authoring time —
stated so the reproduction claim in §"Reproduction on an independent rig root"
is not read as covering them.

**Re-run during review** on three further fresh `mktemp -d` roots
(`tmp.axz0Hql8ja`, `tmp.sJxZABlslH`, `tmp.fZtbwAkBAw`), independently of the
authoring run, on darwin/arm64 with bd 1.1.2:

- **Instrument 1 reproduces exactly.** Same E3 scenario, new ids
  (`Z=bdsa-f2m`, `Y=bdsa-1kb`, `W=bdsa-aru`). Step 1 unflagged:
  `{"created":1,"ids":["bdsa-1kb"],"skipped":2,"stale_skipped_ids":["bdsa-aru","bdsa-f2m"],"updated":1}`.
  Step 2, single flagged row `Z`: `{"created":1,"ids":["bdsa-f2m"],"skipped":0}`
  and nothing else. B after: `Z` imposed (`"A stale description (T+5)"`,
  `updated_at` regressed to `15:53:00Z`, `cn_dot=A:9`), `Y` delivered, and
  **`W`'s never-gossiped local edit intact** at `cn_dot=A:p-seed`.
- **The writer-side pre-flight reproduces**, run as printed above: it predicted
  `would-be-skipped-stale` for both probe rows and the real unflagged import
  then skipped exactly those two.
- **The commit-per-forced-row cost reproduces**, measured independently on two
  fresh roots from equivalent state (`dolt log --oneline` count before/after,
  six rows forced on each path): per-row `added: 6` commits in `3.792s`, bundle
  `added: 1` commit in `0.517s`, both with all six rows forced. The commit
  counts match the authoring run exactly; the wall-clock figures are lower on
  a less loaded machine, so treat `6 vs 1` as the finding and the seconds as
  indicative.
- **The `--dry-run` finding reproduces**: `{"created":3,"dry_run":true,"skipped":0}`
  identically with and without `--allow-stale`, on a bundle whose real unflagged
  import then reported `stale_skipped_ids` for two rows; B was unchanged after
  both dry runs.
- **`bd import --help`'s flag list is unchanged** from the four quoted in E3.
