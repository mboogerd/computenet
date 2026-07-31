import type { Unreadable, StateProvenance } from '../api/types';
import { REMOTE_NOTICE } from './placement';

/** V1C-FE ticket Solution direction §2: `provenance`'s label.
 *
 *  The register is calm throughout — `checkpoint` is stale by construction
 *  and must say so at the value (not just in a tooltip or the meta row), but
 *  staleness here is a FACT about where the bytes came from, not a warning
 *  about the read: nothing failed, nothing needs retrying. `liveSuspended` in
 *  particular must not read as degraded — it is the most stable read in the
 *  graph, not a lesser one (nothing was woken, nothing resumed, no attention
 *  raised).
 *
 *  Forward tolerant, per `util/badges.ts`'s `capitalize`/`manifestBadge`
 *  precedent: an unrecognized future provenance string still renders
 *  verbatim rather than crashing or rendering blank. `null`/`undefined`
 *  return `null` — the caller must render nothing for it (V1C-FE ticket §0:
 *  "never default `provenance` to `'live'`"), not this function guessing. */
export function provenanceLabel(p: StateProvenance | null | undefined): string | null {
  if (p == null) return null;
  switch (p) {
    case 'live':
      return 'live';
    case 'liveSuspended':
      return 'suspended — read live from the cell’s own quiescent fold; nothing was woken';
    case 'checkpoint':
      return 'checkpoint — this is the cell’s state as of the host’s drain, not as of now';
    default:
      return p;
  }
}

/** `true` for the one `provenance` value ({@link provenanceLabel}) that is
 *  stale by construction and must be visually distinguished from a live or
 *  suspended read, per the ticket's "checkpoint is marked stale at the value
 *  ... not styled as a warning" pairing with `liveSuspended`. */
export function isStaleProvenance(p: StateProvenance | null | undefined): boolean {
  return p === 'checkpoint';
}

/** V1C-BE Part 4's exhaustive "what remains unreadable" table, mirrored here
 *  (V1C-FE ticket §Context) — never promise more than that table does.
 *  `'remote'` reuses `REMOTE_NOTICE` verbatim so the message agrees with the
 *  existing remote gate elsewhere in the panel, per the ticket's "reconcile
 *  ... rather than contradict it". */
const UNREADABLE_MESSAGES: Partial<Record<Unreadable, string>> = {
  migrating: 'Held for a repartition flip — the authoritative copy is on another host right now. Retrying will not help until the flip completes.',
  remote: REMOTE_NOTICE,
  notStateful: 'This cell holds no readable state.',
  unanswered: 'The read did not land in time — nothing was read. Worth retrying.',
  unknown: 'The server reports a reason this client build does not recognize.',
};

/** The `State` subsection's `unavailable` sentence. `reason == null` keeps
 *  the pre-V1C-BE sentence VERBATIM (V1C-FE ticket §2: "so `unreadable ==
 *  null` keeps the existing 'State unavailable for this cell.'" — an older
 *  server, or a field-less response, must render exactly what it always
 *  has). A `reason` this build does not recognize (a future value beyond
 *  even `'unknown'`) still renders truthfully, naming the raw string, rather
 *  than crashing or falling back to the generic sentence silently. */
export function unavailableMessage(reason: string | null | undefined): string {
  if (!reason) return 'State unavailable for this cell.';
  const known = UNREADABLE_MESSAGES[reason as Unreadable];
  if (known) return known;
  return `State unavailable for this cell (reason: ${reason}).`;
}

/** V1C-FE ticket Solution direction §2 — "the hardest wording in this
 *  ticket". `count` is the walk's running total of `page.exclusivesElided`.
 *  An ownership FACT, never phrased as truncation or as "load more to see
 *  them" — no further page will ever contain these entries
 *  (`20-wave-neutral-read-design.md` §3.3: "exclusive values are described,
 *  never paged"). */
export function exclusivesElidedLabel(count: number): string {
  const verb = count === 1 ? 'entry holds' : 'entries hold';
  return `${count} ${verb} exclusive values (Owned/Leased) — described, never copied. No further page will contain them.`;
}

/** `page.walkStable`'s three values, per V1C-BE ticket Part 2: `true` and
 *  `null` both render NOTHING here (a quiet page never says anything; `null`
 *  is not a `false` and must not be rendered as one) — only `false` earns a
 *  visible note, since it is the one case that changes what the accumulated
 *  value actually means (a smeared read, not a snapshot). */
export function walkStableNote(stable: boolean | null): string | null {
  if (stable !== false) return null;
  return 'Smeared read — this cell changed while paging. Every entry present for the whole walk is included; an entry added mid-walk may appear, one removed mid-walk after being passed over may be missing. Never torn, never duplicated.';
}

/** The page-walk counter — states only what the response supports (V1C-FE
 *  ticket Solution direction §1: "never print a total the server did not
 *  give you", and never reuse `$truncated`'s "showing N of M" phrasing,
 *  which describes a value the ENCODER abbreviated, not the walk's own
 *  coverage). */
export function pageCounterText(entriesTotal: number, hasMore: boolean): string {
  const n = entriesTotal.toLocaleString();
  const noun = entriesTotal === 1 ? 'entry' : 'entries';
  return hasMore ? `${n} ${noun} loaded — more available` : `${n} ${noun} — complete`;
}

/** The neutral inline note shown for one render right after an automatic
 *  410 restart (V1C-FE ticket Solution direction §1: "no error surfaced to
 *  the user"). */
export const WALK_RESTARTED_NOTE = 'This cell changed — the walk restarted from the first page.';

/** Shown when a second consecutive 410 stopped the walk rather than looping
 *  it. Neutral, not an error: nothing about the request failed, the cell
 *  just kept changing faster than the walk could keep a cursor valid. */
export const WALK_STUCK_NOTE = 'This cell keeps changing faster than the walk can keep up — try again in a moment.';
