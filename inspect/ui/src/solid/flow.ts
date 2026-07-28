import { createSignal } from 'solid-js';
import type { FlowRatesPayload } from '../api/types';
import { FlowStore } from '../sync/flowStore';

/** The pure store, mirroring `solid/errors.ts`'s `errorStore` export: read
 *  imperatively inside a memo/effect gated by `flowVersion()`. There is no
 *  snapshot fetch for flow (see api/types.ts's M3 section comment) — the
 *  store only ever changes via `onFlowRates` below, routed from
 *  `solid/state.ts`'s SSE event switch. */
export const flowStore = new FlowStore();

const [flowVersion, setFlowVersion] = createSignal(0);
flowStore.subscribe(() => setFlowVersion((v) => v + 1));
export { flowVersion };

/** Routed from `solid/state.ts`'s SSE event switch on every `flow.rates` event. */
export function onFlowRates(payload: FlowRatesPayload): void {
  flowStore.applyBatch(payload);
}
