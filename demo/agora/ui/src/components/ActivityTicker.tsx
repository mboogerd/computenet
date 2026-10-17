import { createMemo, createSignal, onMount, onCleanup, For, Show } from 'solid-js';
import { ticker, nodes, setSelection, setFocal } from '../solid/graph';
import { labelOf } from '../util/label';
import './ActivityTicker.css';

const WINDOW_MS = 60_000;

/** "Where to look next" (spec §7). Reads the client-computed ticker feed
 *  (already deduped per node per window), shows how many nodes changed
 *  significantly in the last minute, and jumps to one on click. */
export default function ActivityTicker() {
  const [now, setNow] = createSignal(Date.now());
  onMount(() => {
    const id = setInterval(() => setNow(Date.now()), 3000);
    onCleanup(() => clearInterval(id));
  });

  const recent = createMemo(() => {
    const cutoff = now() - WINDOW_MS;
    return ticker().filter((e) => e.t >= cutoff);
  });
  const [open, setOpen] = createSignal(false);

  // Close the list on an outside click or Escape, like any other popover.
  let root: HTMLDivElement | undefined;
  const onDocDown = (e: PointerEvent) => {
    if (open() && root && !root.contains(e.target as Node)) setOpen(false);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') setOpen(false);
  };
  onMount(() => {
    document.addEventListener('pointerdown', onDocDown);
    document.addEventListener('keydown', onKey);
    onCleanup(() => {
      document.removeEventListener('pointerdown', onDocDown);
      document.removeEventListener('keydown', onKey);
    });
  });

  const jump = (ref: string) => {
    setFocal(ref);
    setSelection(ref);
    setOpen(false);
  };

  return (
    <Show when={recent().length}>
      <div class="ticker" ref={root}>
        <button
          class="ticker__pill pressable"
          aria-expanded={open()}
          title="Claims whose credence moved a lot in the last minute"
          onClick={() => setOpen((v) => !v)}
        >
          <span class="ticker__dot" />
          {recent().length} changed
        </button>
        <Show when={open()}>
          <ul class="ticker__list">
            <For each={recent().slice(0, 8)}>
              {(e) => (
                <li>
                  <button class="ticker__row" onClick={() => jump(e.ref)}>
                    <span class="ticker__label">
                      {nodes[e.ref] ? labelOf(nodes[e.ref]!) : e.ref.slice(0, 8)}
                    </span>
                    <span class="ticker__drift">▲{e.drift.toFixed(2)}</span>
                  </button>
                </li>
              )}
            </For>
          </ul>
        </Show>
      </div>
    </Show>
  );
}
