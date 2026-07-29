import { createSignal } from 'solid-js';
import type { ActivityEntry } from '../api/types';
import { defaultActivityTransport, type ActivityTransport } from '../sync/activityClient';
import { ActivityStore } from '../sync/activityStore';

/** The pure store, mirroring `solid/errors.ts`'s `errorStore` export: read
 *  imperatively inside a memo/effect gated by `activityVersion()`. */
export const activityStore = new ActivityStore();

const [activityVersion, setActivityVersion] = createSignal(0);
activityStore.subscribe(() => setActivityVersion((v) => v + 1));
export { activityVersion };

let transport: ActivityTransport = defaultActivityTransport;

/** Test seam: swap the transport before calling {@link fetchActivitySnapshot}. */
export function setActivityTransport(t: ActivityTransport): void {
  transport = t;
}

/** `GET /api/inspect/activity`, fetched on connect — called from
 *  `solid/state.ts`'s topology `onSnapshot` handler, the same place
 *  `fetchErrorSnapshot` is called from, so a reconnect/post-gap resync
 *  re-syncs the activity log too. */
export function fetchActivitySnapshot(): void {
  void transport.fetchSnapshot().then(
    (snapshot) => activityStore.applySnapshot(snapshot),
    (err) => console.error('inspect: activity snapshot fetch failed', err),
  );
}

/** Routed from `solid/state.ts`'s SSE event switch, one per `activity` frame. */
export function onActivity(entry: ActivityEntry): void {
  activityStore.apply(entry);
}
