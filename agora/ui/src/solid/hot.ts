import { createSignal, createEffect, onCleanup, type Accessor } from 'solid-js';
import { pulses } from './graph';
import type { Ref } from '../api/types';

/** True for ~2s after this node last had a large windowed credence swing
 *  (spec §5 hot-change). Reads the pulses store, which the sync layer only
 *  writes to for drifts past the PULSE threshold, and never on resync. */
export function pulsing(ref: Ref): Accessor<boolean> {
  const [on, setOn] = createSignal(false);
  createEffect(() => {
    const t = pulses[ref]; // track this ref
    if (!t) return;
    setOn(true);
    const id = setTimeout(() => setOn(false), 2000);
    onCleanup(() => clearTimeout(id));
  });
  return on;
}
