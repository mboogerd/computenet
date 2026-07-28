import { createSignal } from 'solid-js';
import type { DeadLetterEntry, ParkedEntry, RestartEntry } from '../api/types';
import { defaultErrorsTransport, type ErrorsTransport } from '../sync/errorsClient';
import { ErrorStore } from '../sync/errorStore';

/** The pure store, mirroring `solid/state.ts`'s `store` export: read
 *  imperatively inside a memo/effect gated by `errorVersion()`, the same
 *  structure-vs-value split `TopologyStore`/`structuralVersion` uses (here
 *  there is no structural/value distinction to make — every change is a
 *  value change — so one version signal covers the whole store). */
export const errorStore = new ErrorStore();

const [errorVersion, setErrorVersion] = createSignal(0);
errorStore.subscribe(() => setErrorVersion((v) => v + 1));
export { errorVersion };

let transport: ErrorsTransport = defaultErrorsTransport;

/** Test seam: swap the transport before calling {@link fetchErrorSnapshot}. */
export function setErrorsTransport(t: ErrorsTransport): void {
  transport = t;
}

/** `GET /api/inspect/errors`, fetched on connect (M2-FE ticket Implement §1)
 *  — called from `solid/state.ts`'s topology `onSnapshot` handler, which
 *  fires once at startup and again on any post-gap/-reconnect topology
 *  resync. The contract names no distinct errors resync marker, so piggy-
 *  backing on the topology resync (rather than a bespoke timer/retry here)
 *  gives the error store the same "fresh view" opportunity topology gets
 *  for free — consistent with F-5 (10-target-v3.md constraint 4): panels are
 *  already not guaranteed wave-aligned, so exact resync timing here is not
 *  load-bearing the way it is for the topology snapshot's own seq. */
export function fetchErrorSnapshot(): void {
  void transport.fetchSnapshot().then(
    (snapshot) => errorStore.applySnapshot(snapshot),
    (err) => console.error('inspect: error snapshot fetch failed', err),
  );
}

/** Routed from `solid/state.ts`'s SSE event switch, one per `error.*` kind. */
export function onErrorDeadLetter(entry: DeadLetterEntry): void {
  errorStore.applyDeadLetter(entry);
}

export function onErrorParked(entry: ParkedEntry): void {
  errorStore.applyParked(entry);
}

export function onErrorRestart(entry: RestartEntry): void {
  errorStore.applyRestart(entry);
}
