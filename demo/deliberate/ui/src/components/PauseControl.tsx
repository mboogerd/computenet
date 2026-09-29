import { createEffect, createMemo, createSignal, on } from 'solid-js';
import { source } from '../sync/store';
import { failed, toast } from '../sync/toasts';

export const PAUSE_HINT = {
  pause: 'Pause this question: rounds in flight finish, no new round starts (Expand on a claim still runs it)',
  resume: 'Resume this question: queued claims are explored again',
} as const;

/**
 * CTL-05: pauses or resumes the question. A native button (Tab, Space/Enter)
 * whose text names the action it takes; optimistic until the next frame
 * reports the server's value, and a failure reverts it and says so in a toast.
 */
export function PauseControl(props: { root: string; paused: boolean }) {
  const [pending, setPending] = createSignal<boolean>();
  const server = createMemo(() => props.paused);
  createEffect(on(server, () => setPending(undefined), { defer: true }));
  const shown = () => pending() ?? server();

  const toggle = async () => {
    const next = !shown();
    setPending(next);
    try {
      await source.pause(props.root, next);
    } catch (err) {
      setPending(undefined);
      toast(failed(`${next ? 'pause' : 'resume'} this question`, err));
    }
  };

  return (
    <button
      type="button"
      class="pause"
      classList={{ 'is-on': shown() }}
      title={shown() ? PAUSE_HINT.resume : PAUSE_HINT.pause}
      onClick={(e) => {
        e.stopPropagation();
        void toggle();
      }}
    >
      {shown() ? 'Resume' : 'Pause'}
    </button>
  );
}
