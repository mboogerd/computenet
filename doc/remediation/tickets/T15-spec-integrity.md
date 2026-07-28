# T15 — Re-true three stale spec citations against landed code

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 1 · **Branches:** `ticket/T15`

## Context

The spec chapters under `doc/spec/` are supposed to describe the system as
landed, not as once-designed. Remediation wave T03 deleted several
dead-on-arrival abstractions from the kernel (zero production installs). Two
of those three deletions already got the correct spec treatment: `12-ports.md`
§Inlet policy tiers now marks `PolicyTier.GATE` "(specified, unimplemented)"
with a pointer to gap marker G-65 (`doc/spec/10-programming-model/12-ports.md:290-295`,
`doc/spec/90-roadmap/91-gap-analysis.md:44`), and `43-security.md` got a
correction plus a G-54 amendment for the deleted `BoundaryPolicy.admission`
predicate (`doc/spec/90-roadmap/91-gap-analysis.md:97`). A third T03 deletion —
`WritePosture`/`SAFETY_PARK`/`AVAILABLE_FENCED` — never got that treatment:
`42-replication.md` still describes the split in normative voice as if the
enum exists, and G-44 still defers work premised on it.

Separately, T11-C (commit `49fef6e`, "move AttentionPolicy from .host to
.control, kill control<->host cycle") relocated
`civictech.cell.control.AttentionPolicy` from `.host` to `.control` to break a
package cycle `doc/ARCHITECTURE.md` had flagged as anomalous. `34-scheduling.md`'s
own header already cites the correct destination once (`cell.control.AttentionSupport`/
`AttentionBand`/`AttentionScheduler`) but still cites the pre-move package for
the type itself, and G-63's proposal prose in `91-gap-analysis.md` still
describes the same move as a future step rather than recording it as done.

Finally, the inspector subsystem (`:inspect`, delivered via the
`97-inspector-plan/` run) ships several semantics that user code relies on but
that live only as prose in `97-inspector-plan/20-api-contract.md`: they carry
no EARS requirement id and no `concord/corpus/` scenario. `concord/corpus/DISPUTES.md`
is this repo's honesty ledger for exactly this situation — per its own header,
"when a requirement cannot be checked honestly against the current
driver/kernel binding, that is filed here — never patched into a scenario as a
weakened check or a silently-omitted assertion." `doc/spec/CONCORDANCE.md`'s
denominator-honesty doctrine (see the "Structural gap" entry at the bottom of
`DISPUTES.md`, and `CONCORDANCE.md`'s own generated preamble) means an
unlisted subsystem reads as silently clean rather than as a known, deliberate
exclusion. The audit that produced this ticket considered minting a new spec
chapter for the inspector and declined — the accepted fix is a DISPUTES.md
entry, not a new normative chapter.

## Problem

1. **`doc/spec/40-distribution/42-replication.md:333-338`** (§ "Leadership is
   a `LeaderMark` epoch fold" bullet list) states in normative voice:

   > **`WritePosture` split, declared per cell.** `AVAILABLE_FENCED` (default):
   > each partition's leader keeps serving... `SAFETY_PARK` (opt-in): a leader
   > that cannot confirm it is un-superseded parks writes...

   `grep -rn "WritePosture\|SAFETY_PARK\|AVAILABLE_FENCED" kernel/src/main
   nature/src/main wire/src/main` returns zero matches — the enum and both its
   values were deleted from every runtime module by T03. Nothing in the
   landed code declares a per-cell posture; every leader runs the
   always-fenced behavior the first bullet describes.

2. **`doc/spec/90-roadmap/91-gap-analysis.md:95`**, gap **G-44**, both the gap
   statement and the proposal reference the deleted enum: "...no
   follower-unpark rule under `SAFETY_PARK`..." and "...a witness-set-superset
   unpark rule for `SAFETY_PARK`..." — live proposal text for a mechanism that
   would gate an API surface no longer in the tree.

3. **`doc/spec/30-execution-model/34-scheduling.md:9`** cites `cell.host.AttentionPolicy`
   in the chapter's own `**Implementation**:` header line ("host mapping in
   `ManagedHost` + `cell.host.AttentionPolicy` (band dispatch, stride floor,
   NONE-window park/replay)"). The type now lives at
   `kernel/src/main/kotlin/civictech/cell/control/AttentionPolicy.kt`, package
   `civictech.cell.control` — moved by commit `49fef6e` ("T11-C: move
   AttentionPolicy from .host to .control, kill control<->host cycle"). The
   same header line already correctly cites `cell.control.AttentionSupport`/
   `AttentionBand`/`AttentionScheduler` two words earlier, so the line
   self-contradicts on where the type lives.

4. **`doc/spec/90-roadmap/91-gap-analysis.md:43`**, gap **G-63**'s proposal
   text still reads: "(1) move `AttentionPolicy` from `.host` to `.control` so
   the scheduling package stops needing a back-reference from `.host`" — as a
   proposed remediation sketch, present tense, no completion marker — even
   though that exact move landed in commit `49fef6e`.

5. The inspector's five user-relied semantics — all specified only in
   `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` prose, none with
   an EARS id, none with a `concord/corpus/` scenario:
   - topology snapshot/delta `seq` monotonicity (`20-api-contract.md:38`:
     `"seq": 412, // monotonic; SSE events carry seq > this`)
   - `Edge.fused` meaning (`20-api-contract.md:72`: `"fused": false // true:
     the producing endpoint has no emission point at all`)
   - `flow.rates` cadence and the rate-0-omitted rule (`20-api-contract.md:176`:
     "1 Hz batch...edges with no traffic that window are omitted (not sent as
     `rate: 0`)")
   - the cold predicate (`20-api-contract.md:127`: `"lifecycle": "hot" |
     "cold" // cold iff every member cell reports Node.lifecycle SUSPENDED`)
   - `data`-mode search bounds (`20-api-contract.md:30`: "bounded (50 cells /
     2s deadline / cold components skipped), and always returns a non-null
     `cost`")

   `civictech.concord.driver.kernel` is the only package permitted to import
   `civictech.cell.*` (AGENTS.md's concord boundary rule), and the driver SPI
   has no observation verbs over the inspector's HTTP/SSE surface — there is
   only one binding (`:inspect` itself), so nothing today could author an
   honest scenario against these semantics. Left unlisted, they read as clean
   coverage rather than a deliberate, tracked exclusion.

## Solution direction

1. **`42-replication.md`**: rewrite the `WritePosture` bullet (lines 333-338)
   to describe the landed always-fenced behavior as the only behavior — drop
   the "split, declared per cell" framing and the `SAFETY_PARK` branch as
   current-state prose. Mint one new gap marker in `91-gap-analysis.md`
   (next free id — `G-67` as of this writing; re-check for a newer max before
   writing to avoid a collision with concurrent work) recording that the
   opt-in `SAFETY_PARK` posture was speced but never implemented and was
   deleted with zero production installs by T03, following the G-65 exemplar's
   shape (`91-gap-analysis.md:44`): gap statement = what was speced and
   removed; proposal = it can be reintroduced with its first real user, not a
   redesign. Then reword G-44's `SAFETY_PARK` clause (both the gap statement's
   "no follower-unpark rule under SAFETY_PARK" and the proposal's "a
   witness-set-superset unpark rule for SAFETY_PARK") to stop presupposing the
   deleted enum — either drop the clause (single fenced posture has no unpark
   step to design) or reframe it as scoped to a future reintroduced posture,
   consistent with whatever the new G-67 marker says. Mirror the G-54
   amendment's approach (`91-gap-analysis.md:97`: an inline "**Residual**:
   ...was deleted (remediation T03)..." note within the existing row) if that
   reads cleaner than a full rewrite — either is acceptable, the point is G-44
   must no longer read as live design work against an API that does not
   exist.

2. **`34-scheduling.md`**: fix the header's `cell.host.AttentionPolicy`
   citation to `cell.control.AttentionPolicy` (line 9). Update G-63's proposal
   text in `91-gap-analysis.md` (line 43) so cut (1) — the `AttentionPolicy`
   move — reads as done (cite commit `49fef6e` or just past-tense it), leaving
   cut (2) (the `.host → .wire` inversion) as the only still-proposed item.

3. **`concord/corpus/DISPUTES.md`**: append one entry, following the file's
   existing entry conventions (see the "Structural gap: 13 normative chapters
   carry no requirement ids at all (T02-D)" entry at the bottom of the file
   for the closest precedent — a subsystem-level exclusion, not a
   per-scenario one). Name the five semantics listed in Problem item 5 with
   their `20-api-contract.md` locations, the reason (no Driver SPI observation
   verbs over the inspector's HTTP/SSE surface; only one binding exists today,
   so nothing could author an honest scenario), and the revisit trigger (a
   second binding of the inspector API contract appears, or the inspector
   subsystem becomes product surface rather than an internal debugging tool).
   Do not weaken this into a scenario with omitted assertions — the entry is
   the fix, per the file's own opening rule.

## Files expected to touch

- `doc/spec/40-distribution/42-replication.md` — rewrite the `WritePosture`
  bullet (lines 333-338) to landed always-fenced behavior
- `doc/spec/90-roadmap/91-gap-analysis.md` — new G-marker for the deleted
  posture; reword G-44's `SAFETY_PARK` clause; fix G-63's `AttentionPolicy`
  proposal text to past tense
- `doc/spec/30-execution-model/34-scheduling.md` — fix the line 9 package
  citation to `cell.control.AttentionPolicy`
- `concord/corpus/DISPUTES.md` — append the inspector-exclusion entry

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W3 items 2, 3, 5 — the accepted fix
  for each of the three problems this ticket implements
- `doc/architecture-decisions.md` findings A2, B4, B12 — the finding ledger
  rows this ticket closes
- `doc/spec/40-distribution/42-replication.md:320-370` — the full leader/
  follower section; read the whole thing, not just the `WritePosture` bullet,
  so the rewrite stays consistent with the surrounding always-fenced framing
  (RESTART/failover bullets already describe single-posture behavior)
- `doc/spec/90-roadmap/91-gap-analysis.md` G-44 (line 95) and G-63 (line 43)
  — the two rows to amend, plus G-65 (line 44) and G-54 (line 97) as the
  worked exemplars for "spec text for a T03-deleted API"
- `doc/spec/10-programming-model/12-ports.md:285-300` — the G-65 exemplar:
  how a deleted-with-zero-installs abstraction gets marked "(specified,
  unimplemented)" in the chapter prose itself, with a pointer to its gap
  marker, rather than silently rewritten away
- `concord/corpus/DISPUTES.md` — read in full for entry style/voice; the
  closing "Structural gap" entry (bottom of file) is the closest precedent
  for a subsystem-level (not per-scenario) exclusion
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — source of the
  five inspector semantics to name in the DISPUTES entry (`seq` at line 38,
  `fused` at line 72, `flow.rates` at line 176, cold predicate at line 127,
  search bounds at line 30)

Do not modify: `concord/src/**` (DocLints changes belong to a different
ticket — T16 — including the package-pointer lint that would eventually catch
citations like problem 3), `doc/ARCHITECTURE.md`, `AGENTS.md`, `README.md`
(owned by T14), `doc/spec/CONCORDANCE.md` (generated, never hand-edited), any
other `concord/` file, and any requirement id (`[NN-SLUG-nn]`) — this ticket
adds no ids and changes no existing ones.

## Acceptance criteria

- [ ] `grep -rn "WritePosture\|SAFETY_PARK\|AVAILABLE_FENCED" doc/spec/40-distribution/42-replication.md`
      contains no remaining normative-voice claim that the enum exists as
      current API (a reference inside the new gap marker's "this was deleted"
      framing, or inside G-44's reworded clause if it stays as a bounded
      historical/future-scoped mention, is acceptable — a bare restatement of
      the old split as present-tense behavior is not)
- [ ] A new gap marker exists in `91-gap-analysis.md` for the deleted
      `WritePosture`/`SAFETY_PARK` posture, using the next free `G-` id
- [ ] G-44 no longer describes `SAFETY_PARK` as work-in-progress against a
      live enum
- [ ] `34-scheduling.md`'s header cites `cell.control.AttentionPolicy`, not
      `cell.host.AttentionPolicy`
- [ ] G-63's proposal no longer describes the `AttentionPolicy` move as
      proposed/future work
- [ ] `concord/corpus/DISPUTES.md` has a new entry naming all five inspector
      semantics from Problem item 5, the reason, and a revisit trigger
- [ ] `./gradlew :concord:check` is green
- [ ] No files outside the "Files expected to touch" list are in the diff

## Verify

```bash
grep -rn "WritePosture\|SAFETY_PARK\|AVAILABLE_FENCED" kernel/src/main nature/src/main wire/src/main   # must stay zero (unchanged by this ticket, sanity check the premise)
grep -n "WritePosture\|SAFETY_PARK\|AVAILABLE_FENCED" doc/spec/40-distribution/42-replication.md doc/spec/90-roadmap/91-gap-analysis.md
grep -n "cell.host.AttentionPolicy\|cell.control.AttentionPolicy" doc/spec/30-execution-model/34-scheduling.md
grep -n "AttentionPolicy" doc/spec/90-roadmap/91-gap-analysis.md
./gradlew :concord:check
git status --porcelain -uall
```

## Report on completion

- Checks run and their results (including the `./gradlew :concord:check`
  output)
- The exact G-number minted, and confirmation it did not collide with
  concurrently landed work (re-`grep "^| G-" doc/spec/90-roadmap/91-gap-analysis.md`
  immediately before writing, not just at ticket-read time)
- Files actually touched, and any not in the claim above
- Anything specified here you could not do, and why
