import { createSignal } from 'solid-js';
import { createClaim } from '../api/commands';
import { notify } from '../solid/graph';
import { PlusIcon } from './Icons';
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
      notify(`Could not add claim: ${(err as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form class="add-claim" onSubmit={submit}>
      <input
        class="add-claim__input"
        placeholder="New claim…"
        aria-label="New claim"
        value={text()}
        onInput={(e) => setText(e.currentTarget.value)}
      />
      <button class="add-claim__btn pressable" disabled={busy() || !text().trim()}>
        <PlusIcon size={14} stroke={2} />
        Add
      </button>
    </form>
  );
}
