import { createEffect, createSignal, on, onCleanup, type Accessor } from 'solid-js';

/** easeOutCubic: fast start, gentle landing — numbers settle rather than snap. */
export const easeOut = (t: number): number => 1 - Math.pow(1 - Math.min(1, Math.max(0, t)), 3);

/** Value between `from` and `to` at progress t ∈ [0,1] of an eased tween. */
export const tweenAt = (from: number, to: number, t: number): number => from + (to - from) * easeOut(t);

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

/**
 * A signal that follows `target` but glides to each new value over `ms`.
 * Server rendering (tests) and reduced-motion users get the target directly.
 */
export function createTween(target: Accessor<number>, ms = 600): Accessor<number> {
  const [value, setValue] = createSignal(target());
  let frame = 0;
  createEffect(
    on(
      target,
      (to) => {
        if (frame) cancelAnimationFrame(frame);
        const from = value();
        if (reducedMotion() || from === to) {
          setValue(to);
          return;
        }
        const start = performance.now();
        const step = (now: number) => {
          const t = (now - start) / ms;
          setValue(t >= 1 ? to : tweenAt(from, to, t));
          if (t < 1) frame = requestAnimationFrame(step);
        };
        frame = requestAnimationFrame(step);
      },
      { defer: true },
    ),
  );
  onCleanup(() => {
    if (frame) cancelAnimationFrame(frame);
  });
  return value;
}
