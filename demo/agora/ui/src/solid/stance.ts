import { createStore, produce, unwrap } from 'solid-js/store';
import type { Ref } from '../api/types';
import { userId } from '../api/commands';

/** The current user's stances — a DEVICE-LOCAL memory, because the wire never
 *  echoes per-user stances back (only aggregate credence). The StanceSlider
 *  binds here, never to credence, so there is nothing to reconcile when live
 *  credence updates arrive (spec §6). Persisted so it survives reload. */
const KEY = `agora.stance.${userId()}`;

function load(): Record<Ref, number> {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}');
  } catch {
    return {};
  }
}

const [stances, setStances] = createStore<Record<Ref, number>>(load());

export function localStance(ref: Ref): number | undefined {
  return stances[ref];
}

export function setLocalStance(ref: Ref, value: number | null): void {
  setStances(
    produce((s) => {
      if (value === null) delete s[ref];
      else s[ref] = value;
    }),
  );
  localStorage.setItem(KEY, JSON.stringify(unwrap(stances)));
}
