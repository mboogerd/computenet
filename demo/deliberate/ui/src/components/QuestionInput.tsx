import { createSignal, Show } from 'solid-js';
import { source } from '../sync/store';

export function QuestionInput(props: { onAsked: (root: string) => void }) {
  const [text, setText] = createSignal('');
  const [busy, setBusy] = createSignal(false);
  const [error, setError] = createSignal<string>();

  const submit = async (e: Event) => {
    e.preventDefault();
    const t = text().trim();
    if (!t || busy()) return;
    setBusy(true);
    setError(undefined);
    try {
      const root = await source.ask(t);
      setText('');
      if (root) props.onAsked(root);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form class="ask" onSubmit={submit}>
      <label class="visually-hidden" for="ask-input">Your question</label>
      <input
        id="ask-input"
        class="ask__input"
        type="text"
        placeholder="Ask a question to deliberate…"
        autocomplete="off"
        value={text()}
        onInput={(e) => setText(e.currentTarget.value)}
        disabled={busy()}
      />
      <button class="ask__submit" type="submit" disabled={busy() || !text().trim()}>
        {busy() ? 'Asking…' : 'Deliberate'}
      </button>
      <Show when={error()}>
        <p class="ask__error" role="alert">{error()}</p>
      </Show>
    </form>
  );
}
