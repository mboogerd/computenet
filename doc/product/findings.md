# Product-lane findings

**Status**: Living

Findings from the `lane:product` epics (WKB2 and its successors), one entry
per settled question or catalogued failure, oldest first. New entries are
**appended at the end**; nothing above the insertion point is edited,
reordered, or deleted. This is the file `computenet-milestone-plan.md` §6
("Every lane writes findings to its own append-only `doc/<lane>/findings.md`,
citing G-ids, never editing `91-gap-analysis.md` or `CONCORDANCE.md`
directly", line 299) requires of the `lane:product` lane.

Append-only is the whole point of the file, not a filing convention. A
findings file whose past entries can be revised is a file in which an
inconvenient measurement can quietly stop existing, and then no later reader
can tell whether a number was derived or chosen. If a later entry contradicts
an earlier one, **append the contradiction** — say which entry it contradicts
and what changed — rather than correcting the earlier entry.

**A corrected entry is never marked as corrected — the correction is below
it.** That is the direct consequence of the rule above, and it is the one way
this file can mislead a reader who does not read it end to end: an entry
later found wrong still reads exactly as it was published, with no marker, no
strikethrough and no link forward, and the only record of the correction is a
later entry that names it. Before citing any entry, scan the `##` headings
that follow it for one that names it.

## 2026-09-24 — WKB2 — R4 settlement (G-51, 95 §R4)

Epic `computenet-7p8` (WKB2), breakdown of feature `computenet-t728h` (F1),
base commit `347cfb5c`. §9 risk 1 of the epic's design asked whether an
operator-facing staged applier contradicts `doc/spec/90-roadmap/95-research-plan.md`
§R4's preferred direction (1) ("keep partial+report as the only semantics —
evolution (53) already provides the transactional idiom (build candidate →
judge → swap)"). Settled with evidence, not parked:

- **Direction (1) is kept for the kernel, literally.** `GraphSpec.applyTo` /
  `applyRemote` (`kernel/src/main/kotlin/civictech/cell/graph/GraphDsl.kt`)
  keep partial+report as their only semantics; no transactional mode is added
  to `GraphSpec` (`[WKB2-21]`, pinned by `GraphSpecRemoteApplyTest` passing
  unmodified).
- **The write plane is direction (1)'s own idiom, orchestrated.** R4 prefers
  (1) *because* "evolution (53) already provides the transactional idiom
  (build candidate → judge → swap)". The staged applier is exactly that idiom
  over the unchanged verbs: build the whole new subgraph unexposed (STAGE =
  `applyRemote` of a spec with no live endpoint, so the kernel's partial
  result is inert), then one CUT-OVER (`connect` of boundary links;
  `Promotion.promote` for replacements). A pre-cut-over failure compensates
  by reverse-order `despawn` of refs the applier itself recorded — no journal
  reversal (direction 2's G-49 machinery), no membrane (direction 3's G-52).
- **Re-scope of §2.1's placement.** The compensating unwinder therefore lives
  in `civictech.inspect.edit`, not in `kernel/…/graph/` as §2.1 said: a
  kernel unwinder would be direction (2) *in the kernel* and would be the
  contradiction risk 1 feared. Only R4's second and third open items — cold
  structural pre-validation (F2) and a subscribable apply-progress surface
  (F4) — land in `civictech.cell.graph`, because 15-lifecycle's G-51
  paragraph names them as kernel semantics.
- **What stays with a human.** R4's Actions clause ends "close this as (1)
  with a spec note" — an edit to `95-research-plan.md` that `[WKB2-62]`
  forbids this epic from making. F13 (`computenet-n7iuo`) produces the
  failure-case catalogue that note would cite, appended below; the note
  itself is a follow-up doc-maintenance item, non-blocking.

Cites: G-51, R4, `[WKB2-21]`, `[WKB2-62]`.

## R4 failure-case catalogue

Entries are appended here by `[WKB2-61]` (feature F13, `computenet-n7iuo`),
one per deterministically-injected failure the write plane's tests exercise,
citing G-51 and R4. None yet.
