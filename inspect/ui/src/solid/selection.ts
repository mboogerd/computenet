import { createSignal } from 'solid-js';
import type { Ref } from '../api/types';

/** Selected node ref, or null. Its own module so both `state.ts` (topology
 *  sync — clears selection when the selected node vanishes) and `detail.ts`
 *  (M1 detail/state subscription lifecycle) can read/write it without one
 *  importing the other. `state.ts` re-exports these two for existing
 *  consumers (`Canvas`, `DetailPanel`) that already import them from there. */
const [selection, setSelection] = createSignal<Ref | null>(null);
export { selection, setSelection };
