import type { ActivityEntry, ActivitySnapshot, Ref } from '../api/types';

/** Matches the server's own ring (contract: "at most 200 entries") so a
 *  long-running session's log can never grow past what one `GET /activity`
 *  would ever hand back. */
export const ACTIVITY_STORE_CAP = 200;

/** The V2 activity sync seam (20-api-contract.md-to-be "GET /activity",
 *  "activity" SSE events — frozen for this wave by V2-FE ticket "The
 *  contract shapes you code against"), mirroring `sync/errorStore.ts`: fetch
 *  the ring snapshot once, then append each SSE delta as it arrives.
 *  Framework-free — no Solid imports — so it is directly unit-testable.
 *
 *  Stored oldest-first internally (append = push, evict = shift), exactly
 *  like the wire's `GET /activity` body; `entries` exposes the newest-first
 *  view every consumer (the log panel, `entriesFor`) actually wants to
 *  render, computed on read rather than kept as a second copy. Bounded at
 *  {@link ACTIVITY_STORE_CAP}, evicting the oldest entry first — both on a
 *  fresh snapshot (defensive: trust but verify the wire's own cap) and on
 *  every appended delta. */
export class ActivityStore {
  /** Oldest-first. */
  private _entries: ActivityEntry[] = [];
  private subs = new Set<() => void>();

  /** Newest-first — the rendering order every consumer wants. */
  get entries(): readonly ActivityEntry[] {
    return [...this._entries].reverse();
  }

  /** One ref's entries, newest-first — the detail panel / filtered log's
   *  per-cell view. */
  entriesFor(ref: Ref): readonly ActivityEntry[] {
    return this.entries.filter((e) => e.ref === ref);
  }

  subscribe(fn: () => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  private notify(): void {
    for (const fn of this.subs) fn();
  }

  /** Replace the whole known world (initial `GET /activity`, or a later
   *  refetch — see `solid/activity.ts`, wired to the same topology
   *  `onSnapshot` handler `fetchErrorSnapshot` already uses, so a reconnect
   *  re-syncs the log too). */
  applySnapshot(snapshot: ActivitySnapshot): void {
    this._entries = snapshot.entries.slice(-ACTIVITY_STORE_CAP);
    this.notify();
  }

  /** One `activity` SSE delta — always an append, never a replace (each
   *  entry is one more thing that happened, like `ErrorStore`'s
   *  dead-letter/restart logs). */
  apply(entry: ActivityEntry): void {
    const next = [...this._entries, entry];
    if (next.length > ACTIVITY_STORE_CAP) next.shift();
    this._entries = next;
    this.notify();
  }
}
