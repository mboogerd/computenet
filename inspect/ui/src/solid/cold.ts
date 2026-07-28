import { createSignal } from 'solid-js';
import { isGraphCold } from '../nav/cold';
import { defaultWakeTransport, type WakeAck, type WakeTransport } from '../sync/coldClient';
import { fetchGraphs, graphs } from './graphs';
import { currentGraphId } from './routeState';

/** M5-COLD (ticket Implement §2) — the client half of cold graphs.
 *
 *  A leaf module by design: it depends only on `solid/graphs.ts` (the
 *  `GraphList` the server's coldness is reported in) and `solid/routeState.ts`
 *  (which graph the user is inside). That keeps it importable from
 *  `solid/detail.ts` — which must consult it before subscribing to anything —
 *  without recreating the `state.ts` ↔ `detail.ts` cycle those two modules were
 *  already split apart to avoid. */

/** Is the graph currently on screen cold? The single gate: it dims the canvas,
 *  raises the cold screen, and — the part that matters for P6 — suppresses the
 *  observe subscription selection would otherwise create. */
export function currentGraphCold(): boolean {
  return isGraphCold(currentGraphId(), graphs());
}

/** True between pressing Wake and the graph actually reporting hot. The screen
 *  stays up throughout: the wake is a management call enqueued on the hosts'
 *  own queues, so "requested" and "awake" are genuinely different moments and
 *  the UI does not pretend otherwise. */
const [waking, setWaking] = createSignal(false);
const [wakeError, setWakeError] = createSignal<unknown>(null);
/** The last 202 body — how much the wake actually resumed (see {@link WakeAck}). */
const [lastWake, setLastWake] = createSignal<WakeAck | null>(null);
/** Whether the confirmation dialog is open. Waking is never one click. */
const [confirmingWake, setConfirmingWake] = createSignal(false);
export { confirmingWake, lastWake, wakeError, waking };

let transport: WakeTransport = defaultWakeTransport;

/** Test seam: swap the transport before calling {@link wakeGraph}. */
export function setWakeTransport(t: WakeTransport): void {
  transport = t;
}

/** Open the confirmation dialog — what the "Wake to inspect" button does.
 *  Nothing has been requested at this point. */
export function askToWake(): void {
  setWakeError(null);
  setConfirmingWake(true);
}

export function cancelWake(): void {
  setConfirmingWake(false);
}

/** Confirmed: `POST /graph/{id}/wake`.
 *
 *  Refetching the graph list on the ack is belt-and-braces, not the mechanism:
 *  the server announces the transition as `lifecycle` events plus a
 *  `graphs.changed`, and `solid/state.ts` already refetches on that. This makes
 *  the common case feel immediate instead of waiting up to the server's poll
 *  interval, and costs one metadata request. */
export function wakeGraph(): void {
  const id = currentGraphId();
  setConfirmingWake(false);
  if (!id) return;
  setWaking(true);
  setWakeError(null);
  void transport.wake(id).then(
    (ack) => {
      setWaking(false);
      setLastWake(ack);
      fetchGraphs();
    },
    (err) => {
      setWaking(false);
      setWakeError(err);
      console.error('inspect: wake failed', err);
    },
  );
}

/** Reset the wake UI — used when leaving a graph, so a failed wake's error
 *  does not follow the user to the next one. */
export function clearWake(): void {
  setConfirmingWake(false);
  setWaking(false);
  setWakeError(null);
  setLastWake(null);
}
