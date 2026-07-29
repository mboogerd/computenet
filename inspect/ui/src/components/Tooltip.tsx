import { For, Show, createEffect, createMemo, createSignal } from 'solid-js';
import { placeTooltip } from '../nav/tooltip';
import { prefersReducedMotion } from '../solid/motion';
import { TOOLTIP_ID, tooltip } from '../solid/tooltip';
import './Tooltip.css';

/** FE-TOOLTIPS ticket Solution direction §2: the one tooltip layer. Mounted
 *  once, near the app root (`app.tsx`), **outside** `.canvas__pan` — this is
 *  the structural answer to FE-CANVAS's transform: a `position: fixed`
 *  element positions relative to the *viewport*, unaffected by any ancestor
 *  CSS transform, so as long as every anchor rect this reads is itself
 *  already client-space (`getBoundingClientRect()`, or the live cursor
 *  position — see `solid/tooltip.ts`), no scene→client conversion is ever
 *  needed here. `nav/viewport.ts`'s `client = scene * scale + (x, y)`
 *  mapping is simply never invoked by this file.
 *
 *  Exactly one instance of the tooltip's own DOM node exists across a whole
 *  hover/focus session: `<Show>`'s accessor-child form below re-invokes its
 *  callback only when `tooltip()` flips between `null` and non-null, not on
 *  every value change — so retargeting from one anchor to another (skipping
 *  the controller's hover-intent delay) updates content/position in place
 *  rather than unmounting and remounting the element. That is also why the
 *  appear animation (below) plays once per session, not once per retarget. */
export default function Tooltip() {
  return (
    <Show when={tooltip()}>
      {(shown) => {
        let el: HTMLDivElement | undefined;
        const [tipSize, setTipSize] = createSignal({ w: 0, h: 0 });

        // Re-measure whenever the content changes (a new title/rows means a
        // new intrinsic size) — NOT on every anchor update, so a cursor-
        // follow edge tooltip's per-frame position change never forces a
        // reflow read. `shown().content` is a fresh object per `showTooltip`
        // call (Canvas.tsx builds a plain literal each time), so this
        // effect's dependency fires exactly on content changes, including a
        // same-anchor retarget with different text.
        createEffect(() => {
          shown().content;
          if (el) setTipSize({ w: el.offsetWidth, h: el.offsetHeight });
        });

        const placement = createMemo(() => {
          const view = { w: window.innerWidth, h: window.innerHeight };
          return placeTooltip(shown().anchor(), tipSize(), view, { prefer: shown().prefer });
        });

        return (
          <div
            id={TOOLTIP_ID}
            ref={el}
            role="tooltip"
            class="tooltip"
            classList={{ 'tooltip--animated': !prefersReducedMotion() }}
            data-placement={placement().placement}
            style={{ left: `${placement().x}px`, top: `${placement().y}px` }}
          >
            <Show when={shown().content.title}>
              <div class="tooltip__title">{shown().content.title}</div>
            </Show>
            <Show when={shown().content.rows.length > 0}>
              <dl class="tooltip__rows">
                <For each={shown().content.rows}>
                  {(row) => (
                    <>
                      <dt class="tooltip__label">{row.label}</dt>
                      <dd class="tooltip__value">{row.value}</dd>
                    </>
                  )}
                </For>
              </dl>
            </Show>
          </div>
        );
      }}
    </Show>
  );
}
