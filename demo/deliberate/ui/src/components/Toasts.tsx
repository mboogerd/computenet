import { For } from 'solid-js';
import { dismiss, toasts } from '../sync/toasts';

/** Where a failed Pause/Override says so, instead of silently reverting. */
export function Toasts() {
  return (
    <div class="toasts" role="alert" aria-live="assertive">
      <For each={toasts()}>
        {(t) => (
          <div class="toast">
            <span class="toast__msg">{t.msg}</span>
            <button type="button" class="toast__close" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
              ×
            </button>
          </div>
        )}
      </For>
    </div>
  );
}
