import { createSignal } from 'solid-js';

/** `prefers-reduced-motion` (M3-FE ticket Implement §2: "Respect
 *  prefers-reduced-motion: static intensity styling instead of pulses").
 *  Unlike `solid/theme.ts`'s dark/light read (which is immediately
 *  superseded by a persisted manual override, so a one-time read at load is
 *  enough), there is no manual override for motion here — an OS-level
 *  toggle flipped mid-session is the only way this preference ever changes,
 *  so this stays live via `matchMedia`'s own `change` event rather than a
 *  load-once read. Guarded for non-browser test environments (this module
 *  is Solid-coupled and, like `theme.ts`, not unit-tested directly — see
 *  `util/flow.ts`'s `pulsesToRender` for the unit-tested pure decision). */
const mql = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : undefined;

const [prefersReducedMotion, setPrefersReducedMotion] = createSignal(mql?.matches ?? false);
export { prefersReducedMotion };

mql?.addEventListener('change', (e) => setPrefersReducedMotion(e.matches));
