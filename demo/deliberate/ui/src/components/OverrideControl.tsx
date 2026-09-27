import { createEffect, createMemo, createSignal, For, on } from 'solid-js';
import type { Override } from '../api/types';
import { source } from '../sync/store';

const MODES: Override[] = ['AUTO', 'EXPAND', 'STOP'];
const MODE_LABEL: Record<Override, string> = { AUTO: 'Auto', EXPAND: 'Expand', STOP: 'Stop' };
export const MODE_HINT: Record<Override, string> = {
  AUTO: 'Let Jev decide whether this claim is worth exploring',
  EXPAND: 'Explore this claim regardless of what Jev thinks',
  STOP: 'Stop exploring this claim',
};

export function OverrideControl(props: { id: string; value: Override }) {
  // Optimistic until the next frame reports a different server value.
  const [pending, setPending] = createSignal<Override>();
  // Memo so a new frame carrying the same value does not clear `pending`.
  const server = createMemo(() => props.value);
  createEffect(on(server, () => setPending(undefined), { defer: true }));
  const shown = () => pending() ?? server();

  const choose = async (mode: Override) => {
    if (mode === shown()) return;
    setPending(mode);
    try {
      await source.override(props.id, mode);
    } catch (err) {
      console.warn('deliberate: override failed', err);
      setPending(undefined);
    }
  };

  return (
    <div class="seg" role="group" aria-label="Exploration override">
      <For each={MODES}>
        {(m) => (
          <button
            type="button"
            class="seg__btn"
            classList={{ 'is-on': shown() === m, [`seg__btn--${m.toLowerCase()}`]: true }}
            aria-pressed={shown() === m}
            title={MODE_HINT[m]}
            onClick={(e) => {
              e.stopPropagation();
              void choose(m);
            }}
          >
            {MODE_LABEL[m]}
          </button>
        )}
      </For>
    </div>
  );
}
