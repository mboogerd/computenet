import { createSignal } from 'solid-js';
import { createClaim } from '../api/commands';
import './AddClaim.css';

export default function AddClaim() {
  const [text, setText] = createSignal('');
  const [busy, setBusy] = createSignal(false);

  async function submit(e: Event) {
    e.preventDefault();
    const t = text().trim();
    if (!t) return;
    setBusy(true);
    try {
      await createClaim(t);
      setText('');
    } catch (err) {
      console.error('create claim failed', err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form class="add-claim" onSubmit={submit}>
      <input
        class="add-claim__input"
        placeholder="New claim…"
        value={text()}
        onInput={(e) => setText(e.currentTarget.value)}
      />
      <button class="add-claim__btn" disabled={busy() || !text().trim()}>
        Add
      </button>
    </form>
  );
}
