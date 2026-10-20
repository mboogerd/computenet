import { createSignal, onMount, Show } from 'solid-js';
import { validateWorkflowName } from './workflows';

export default function NewWorkflowForm(props: { create: (name: string) => Promise<void>; cancel: () => void }) {
  const [name, setName] = createSignal('');
  const [error, setError] = createSignal<string | null>(null);
  const [saving, setSaving] = createSignal(false);
  let input!: HTMLInputElement;
  onMount(() => input.focus());
  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (saving()) return;
    const validation = validateWorkflowName(name());
    setError(validation);
    if (validation) { input.focus(); return; }
    setSaving(true);
    try { await props.create(name().trim()); }
    catch (error) { setError(error instanceof Error ? error.message : String(error)); }
    finally { setSaving(false); }
  };
  return <form id="new-workflow-form" class="workflow-form" onSubmit={submit} onKeyDown={event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); props.cancel(); }
  }}>
    <label class="field"><span>Workflow name (1–80 characters)</span>
      <input ref={input} autofocus value={name()} aria-invalid={!!error()} aria-describedby={error() ? 'workflow-name-error' : undefined}
        onInput={event => { setName(event.currentTarget.value); setError(null); }} />
    </label>
    <Show when={error()}><p id="workflow-name-error" class="error-box" role="alert">{error()}</p></Show>
    <div class="dialog-actions"><button type="button" onClick={props.cancel}>Cancel</button>
      <button type="submit" class="primary" disabled={saving()}>{saving() ? 'Creating…' : 'Create'}</button></div>
  </form>;
}
