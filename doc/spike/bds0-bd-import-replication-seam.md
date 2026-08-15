# BDS0 finding — is `bd import` a sound replication write-seam?

Epic `computenet-8kj`. Consolidates the four claim sections
(`computenet-8kj.2` through `.5`) into one document. Each section below is a
condensed reproduction of its claim file — the pass/fail outcomes and "what
did not work" are preserved verbatim in substance; only prose is trimmed. The
full transcripts, execution order, and falsifiability trail for every
sub-check live in the linked claim file, not here.

## Verdict

**`bd import` IS NOT a sound replication write-seam as posed — but a sound
amended seam exists, and it is two instruments rather than one: read
provenance from `dolt_diff_issues` (the Dolt commit graph) for echo
suppression, and write with single-row `bd import --allow-stale` as the
ordering-authority instrument.** The BDS line is not re-founded by this
finding; BDS1 and BDS4 are re-scoped against that amended seam before they
are broken down.

Traced claim by claim:

- **Claim (a) — echo suppression: fails as posed, with a verified fallback.**
  The provenance stamp `metadata.cn_dot` survives the upsert byte-identically
  (i) and survives re-export unchanged (iii), so the *carrier* is sound. The
  seam breaks at the *feed*: the `events` table has no metadata column, and an
  imported **update** writes no journal event at all — so a projector reading
  the journal cannot recognise its own echo, and cannot even count hops. That
  is the half of `bd import` that is not sound. The verified fallback is
  `dolt_diff_issues`: it carries exactly the point-in-time provenance the
  journal lacks, and was exercised on the rig (labelled VERIFIED in the claim
  (a) file, independently re-measured in review). BDS1's *feed* therefore
  needs re-selecting — the events journal is not it.
- **Claim (b) — ordering authority: yes, with an unacceptable cost at the
  granularity the epic assumed.** `--allow-stale` genuinely imposes the
  incoming row in full (E2 total imposition), so ComputeNet *can* be the
  ordering authority. But it is a **per-import-run** switch with no per-row,
  per-id or predicate form, and under it the import report stops printing
  `stale_skipped_ids`, `updated` and `updated_issues` — so a bulk flagged
  import destroys never-gossiped local edits, including on bystander rows the
  sender never intended to touch, and reports nothing. Bulk `--allow-stale` is
  therefore the wrong instrument. The measured narrower instrument is
  **single-row `--allow-stale` import, preceded by a writer-side pre-flight
  against the destination's own export**: on the rig it imposed the dot-order
  winner while a never-gossiped local edit on a bystander issue survived
  intact. Its known price is 6 Dolt commits where the bundle path costs 1,
  which collides with BDS4 §2's migration-0055 rationale for avoiding a commit
  per write — that collision is BDS4's to resolve, not this spike's. Claim (b)
  also hands the line a second **known `bd` 1.1.2 defect**, independent of the
  seam choice and unfixed by the narrower instrument: `updated_at` is stored at
  one-second resolution, and an incoming sub-second part ≥ `.500` rounds *up*
  into the next second and overwrites the local row while the same import
  reports `tie_kept_local_ids` for it — so the report cannot be trusted to say
  what LWW actually did on a same-second write (E4).
- **Claim (c) — close replication: pass.** A peer's close replicates through
  `bd import` with no new `bd` surface, and the originate/replicate asymmetry
  is real and structural: guards fire on `bd close` and do not fire on the
  import path — demonstrated against a destination that held the identical
  guard-violating structure and had refused the same close one command
  earlier. The epic's *delete* premise is factually wrong in a direction that
  makes deletion more dangerous, not less: `bd delete` does exist in bd 1.1.2,
  is a real hard delete, and is anti-durable under bidirectional replication —
  the next hop from any peer that still holds the row resurrects it. `close`
  therefore remains the removal interface, as BDS4 already assumes.
- **Claim (d) — round-trip fidelity: clean PASS, zero named fidelity losses.**
  Labels, comments, dependencies, nested metadata, priority and timestamps all
  round-tripped byte-identically on a maximally-populated fixture and on an
  unstamped control, reproduced on a second independent rig root. It hands
  this feature one **known `bd` defect**, not a loss: dependency `created_at`
  is off by exactly the local UTC offset in bd 1.1.2 — a local-vs-UTC storage
  bug that round-trips *consistently* wrong. Any consumer of dependency
  timestamps across machines in different time zones must treat that field as
  unreliable until the defect is fixed upstream.

### The amended seam, stated once

| Concern | Instrument | Status |
|---|---|---|
| Echo suppression / provenance read | `dolt_diff_issues` (Dolt commit graph), **not** the events journal | VERIFIED on the rig (claim (a)) |
| Ordering-authority write | single-row `bd import --allow-stale`, one invocation per row, with a writer-side pre-flight against the destination's export | measured on the rig (claim (b)); cost 6 Dolt commits vs 1 for the bundle path |
| Removal | `bd close` (originate) / `bd import` (replicate); **never** `bd delete` | PASS (claim (c)) |
| Field fidelity | plain `export → import → export` | PASS, one known bd defect on dependency `created_at` (claim (d)) |

Both halves are the instruments the claim files actually verified. No third
instrument is proposed here, and neither half is a design sketch to be
re-derived downstream.

### Consequences for the BDS line

The epic's conditional — "IF claim (a) or claim (b) failed, THEN the finding
proposes the alternative seam and BDS1/BDS4 are flagged for re-scoping" —
**fires**, on both claims. Accordingly:

- **BDS1 (`computenet-dqj`)** must be re-scoped before breakdown: its feed is
  specified as the `bd` events journal, which claim (a) shows cannot carry
  echo suppression for imported updates. The projector's input becomes
  `dolt_diff_issues`.
- **BDS4 (`computenet-6wc`)** must be re-scoped before breakdown: its write
  path must be single-row `--allow-stale` with a writer-side pre-flight, and
  it must reconcile that instrument's per-row Dolt commit cost against its own
  §2 migration-0055 rationale.

Neither epic is rewritten by this feature; the re-scope signal is filed as a
comment on each.

## Environment

Sourced from the claim files' own "Environment, verified rather than assumed"
sections, not re-measured here:

- `bd version 1.1.2 (Homebrew)`
- Darwin arm64
- Rig entry point: `scripts/spike/bds0/rig.sh`. Documented smoke command:
  `bash scripts/spike/bds0/rig.sh smoke`, run and passing before each claim's
  transcripts.
- All runs executed against synthetic `mktemp -d` rig roots via
  `bd -C <ws> --sandbox` or a `rig.sh` subcommand. No claim file's run read or
  wrote the repository's live `.beads`.

## Claim (a) — echo suppression via `metadata.cn_dot`

The claim under test: a provenance stamp `metadata.cn_dot = "<replica>:<seq>"`
placed on a replicated write (i) survives `bd import`'s upsert verbatim on the
stored issue, (ii) is readable off the journal record for that upsert, so a
projector recognises its own echo, and (iii) survives re-export unchanged.

**Result in one line:** (i) pass, (ii) fail, (iii) pass. The value survives
the store and the re-export byte-identically; the journal cannot see it,
because the `events` table has no metadata column and imported *updates*
produce no journal record at all.

**Verdict in one line:** claim (a) fails as posed, with a verified fallback —
the Dolt commit graph (`dolt_diff_issues`) carries exactly the point-in-time
provenance `events` lacks, so BDS1's *feed* needs re-selecting, not the BDS
line re-founding.

Per-sub-check outcomes:

| Sub-check | Result |
|---|---|
| (i) stored issue | **PASS** — `metadata.cn_dot` byte-identical after upsert, `updated_at` undisturbed |
| (ii) journal record | **FAIL** — `events` table has no metadata column; an imported *update* writes no journal event at all (a local `bd update` on the same row does); event count per hop is 1 (create), 1+labels (labelled create), or 0 (update), so echo cannot be counted either |
| (iii) re-export | **PASS** — `cn_dot` unchanged and unrewritten through `bd export` |

An adjacent finding the claim depends on: re-hopping a row with only a new dot
and no other change ties on `updated_at` and is discarded
(`tie_kept_local_ids`) — provenance cannot be stamped as a separate marking
pass, only attached to a write that also wins LWW.

What did not work:

- `bd events` — no such command in bd 1.1.2.
- `bd sql -q ...` — `'bd sql' is not yet supported in embedded mode`.
- Reading `cn_dot` off any journal row — the `events` table has no metadata
  column, and `old_value`/`new_value` never carry it (empty for imports,
  scalar columns only for local edits).
- Observing an imported update in the journal at all.
- Stamping a new `cn_dot` onto an already-replicated row without also
  advancing `updated_at` — the import ties and keeps the local copy.
- Reproducing "two events" from a plain mutate-then-hop (a reviewer's prior,
  uninvestigated observation) — it reproduces only for rows carrying labels,
  as `created` + `label_added`, never for a plain row.

Full transcripts, the alternative-seam analysis (`dolt_diff_issues` verified
on the rig; `bd history`, writer-side suppression, and a dedicated bd verb
proposed but untested), and the falsifiability trail:
[`doc/spike/bds0/claim-a-echo-suppression.md`](bds0/claim-a-echo-suppression.md).

## Claim (b) — ordering authority and the true cost of `--allow-stale`

The claim under test: `bd import` adjudicates by wall-clock LWW, and
`--allow-stale` gives the write path authority to impose a clock-free
dot-order decision on the local store. Four sub-checks: E1 the unflagged
baseline, E2 whether `--allow-stale` actually imposes the incoming row, E3
whether it also clobbers genuinely newer local edits that were never
gossiped and at what granularity, E4 how one-second resolution adjudicates
same-second writes.

**Result in one line:** E1 pass, E2 pass, E3
pass-as-a-question-with-a-bad-answer, E4 pass with a defect.
`--allow-stale` does impose the incoming row completely — and it is a
per-import-run switch with no per-row form, so it clobbers every
never-gossiped local edit in the same bundle, silently: under the flag the
import report stops printing `stale_skipped_ids`, `updated` and
`updated_issues` altogether. E4 found a separate, independent defect: an
incoming `updated_at` whose sub-second part is ≥ `.500` rounds up into the
next second and overwrites the local row, while the same import reports
`tie_kept_local_ids` for it.

**Verdict in one line:** claim (b) is answered yes-with-an-unacceptable-cost
— `--allow-stale` does deliver ordering authority, but only at whole-bundle
granularity and with the loss report switched off, so the epic's conditional
fires and the replication write path needs a narrower instrument. One is
verified on the rig: a per-row `--allow-stale` invocation, preceded by a
writer-side pre-flight against the destination's own export.

Per-sub-check outcomes:

| Sub-check | Result |
|---|---|
| E1 baseline (newer overwrites, tie keeps local, older skipped) | **PASS** |
| E2 imposition | **PASS** — total: after the flagged import the stored row equals the incoming row in every compared field |
| E3 clobber | **Claim's premise fails: it clobbers, per run** — a never-gossiped local edit was destroyed, and so was an unrelated bystander row the sender never intended to touch; the flag has no per-row form |
| E4 same-second adjudication | **PASS, with an independent defect** — sub-second parts ≥ `.500` round up and overwrite while the report claims a tie |

The epic's conditional (§1(b): "if it clobbers, the replication write path
needs a narrower instrument and this spike must say so") is **triggered**.
The narrower instrument — writer-side pre-flight plus one `--allow-stale`
invocation per row — is **verified on the rig**; it costs 6 Dolt commits
where the bundle path costs 1, which collides with BDS4 §2's cited
migration-0055 rationale for avoiding a commit per write.

What did not work:

- `rig.sh hop` cannot forge `updated_at`, which every E1–E4 experiment needs
  — a manual `export | jq | import` pipeline substitutes.
- Narrowing `--allow-stale` to the intended rows — there is no per-row,
  per-id, or predicate form.
- Detecting the damage from the import report — under the flag it drops
  `stale_skipped_ids`, `updated`, and `updated_issues` entirely.
- Trusting `tie_kept_local_ids` — a sub-second `updated_at` of `.500`+
  reports a tie and overwrites anyway.
- Storing sub-second precision — accepted on input, never stored.
- Keeping the store's clock monotonic under `--allow-stale` — imposing an
  older row moves the stored `updated_at` backwards.
- Advancing a row's clock with a stamp-only write under `--allow-stale` — the
  new `cn_dot` lands but `updated_at` stays untouched, invisible to later LWW.
- `bd import --dry-run` as a staleness pre-flight — identical output with and
  without `--allow-stale`; it reports bundle contents, not adjudication.
- Getting a loss report out of any `--allow-stale` invocation, however
  narrow.

Full transcripts, the four-cost accounting, the narrower-instrument design
and its measured cost, and the independent re-run during review (reproduced
exactly on three fresh rig roots):
[`doc/spike/bds0/claim-b-ordering-authority.md`](bds0/claim-b-ordering-authority.md).

## Claim (c) — close replicates through `bd import`; hard delete does not

The claim under test: `status`, `closed_at`, and `close_reason` are ordinary
importable fields, so replicating a peer's close needs no new `bd` surface,
while *originating* a close goes through `bd close` where guards fire —
guards that must not re-fire on replication of an already-adjudicated fact.
Separately: `bd import` skips `tombstone` rows and `bd` has no delete verb,
so hard deletion is claimed out of scope for replication.

**Result in one line:** C1 pass, C2 pass (with one narrowing), C3 pass — the
load-bearing one — C4 pass on the mechanism but false on its stated premise.

**Verdict in one line:** claim (c) passes as posed on the three legs that
matter — a peer's close replicates with no new `bd` surface, and the
originate/replicate asymmetry is real and structural — while the epic's
delete premise is factually wrong in a way that makes deletion *more*
dangerous than the epic assumed, not less.

Per-sub-check outcomes:

| Sub-check | Result |
|---|---|
| C1 — a close in A replicates into B | **PASS** — on both the create shape and the update (open→closed) shape, `closed_at`/`close_reason` byte-identical, no `bd close` invocation in B |
| C2 — guards fire when *originating* | **PASS, with a narrowing** — both guards fire and mutate nothing, but the open-children guard is epic-only: a `task` parent with an open `parent-child` child closes without complaint |
| C3 — guards do **not** fire on replication | **PASS — the load-bearing one** — B held the identical guard-violating structure locally and B's own `bd close` refused the same close one command earlier; the forged bundle then imported unflagged and applied both closes silently |
| C4 — `tombstone` dropped; hard delete does not replicate | **Mechanism PASS; premise FALSE** — the drop is real (`{"created": 0, "skipped": 0}`, silent, unlike an unrecognised status which fails loudly); but `bd 1.1.2` **does** have `bd delete`, a real hard delete that leaves no tombstone and no trace in the export |

The accepted gap: deletion is expressed as absence, and a deleted row is
byte-identical on the wire to a row simply not part of a delta — measured,
an unfiltered hop of everything A holds after a delete in A left B's copy
open and alive; the seam carried the deletion nowhere. Under bidirectional
replication a hard delete is not merely non-replicating, it is
anti-durable — the next hop from any peer that still holds the row
re-creates it.

What did not work:

- `ErrCloseBlocked` / `ErrCloseOpenChildren` are not observable anywhere in
  output — only two prose strings plus exit 1.
- `bd close --json` does not produce a structured error (unlike
  `bd import --json`, which does).
- The open-children guard could not be provoked on a non-epic parent.
- `updated_issues[].changes` could not be used to verify `closed_at` — it
  omits the field even though it landed.
- The journal could not confirm any of this — imported updates write no
  event (per claim (a)).
- A `tombstone` row could not be made to do anything — not create, update,
  delete, or carry an unrelated field change.
- `bd delete` could not be made to replicate — there is no export
  representation of a deleted issue to hop.
- "Guards off on the import path" could not be tested as a configurable —
  `bd import` has no guard-related flag at all; it is the only behavior
  available.

Full transcripts, the epic-wording adjudication, the resurrection-hop probe,
and the falsifiability trail:
[`doc/spike/bds0/claim-c-close-replication.md`](bds0/claim-c-close-replication.md).

## Claim (d) — round-trip fidelity: export → import → export

The claim under test: labels, comments, dependencies, metadata (nested
JSON), priority, and timestamps survive `export → import → export`
byte-identically modulo known-volatile fields, and anything that does not is
named.

**Result in one line:** PASS, with zero named fidelity losses. Every
replication-relevant field on a maximally-populated fixture — two labels,
three comments from two distinct authors, a `blocks` dependency, nested
metadata, priority 1, and a separately-closed issue with `closed_at` /
`close_reason` — round-tripped byte-identical on both a rig-stamped hop and
an unstamped plain `export | import` control, reproduced on a second,
independent rig root. Claim (d) has no separate verdict line — this result
line serves as its verdict.

The only field that ever differed between the source export and the
destination re-export was `metadata.cn_dot`, and that is the rig's own
deliberate provenance stamp (claim (a)'s subject), not a round-trip
artifact — the unstamped control produced a byte-identical copy with no
stamp at all. One anomaly was chased per the task's own instruction and
resolved: dependency `created_at` is off by exactly the local UTC offset, a
local-vs-UTC storage bug in `bd` 1.1.2 that round-trips *consistently*
wrong rather than being further corrupted by the trip itself — named as a
`bd` defect, not a fidelity loss, since claim (d)'s test only asks whether
the trip preserves the value, not whether the source value was correct.

What did not work:

- A same-workspace control could not be used — reusing the destination
  workspace for the unstamped control would have compared an
  already-populated workspace against itself; a third, independently
  `bd init`'d workspace was required.
- The dependency `created_at` anomaly could not be attributed to the round
  trip — it is present in the source's own first export, before any hop, so
  claim (d)'s instruments cannot localise which `bd` code path produces it.
- No fidelity loss was found to name, which is itself a limit worth
  stating: a narrower or larger fixture (more dependency types, deeper
  metadata nesting, non-ASCII comment text, an issue with no owner) was not
  tried and could in principle surface something this fixture did not reach.
- `bd import`'s upsert path for a row that already exists with different
  content was not exercised here — every fixture row was net-new, so this
  section is a **create** round trip throughout; an update-shaped round trip
  covering labels, comments and dependencies together was not separately
  re-run, though nothing observed suggests the update path would differ for
  fields claims (b)/(c) did not already touch.

Full transcripts, the field-level classification table, and the
falsifiability trail:
[`doc/spike/bds0/claim-d-round-trip-fidelity.md`](bds0/claim-d-round-trip-fidelity.md).
