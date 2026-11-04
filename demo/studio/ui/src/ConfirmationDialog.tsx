import { createUniqueId, onCleanup, onMount } from 'solid-js';
import { Portal } from 'solid-js/web';

export type Confirmation = { message: string; label: string; run: () => unknown };

export default function ConfirmationDialog(props: { message: string; label: string; cancel: () => void; accept: () => void }) {
  const titleId = createUniqueId();
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  let cancelButton!: HTMLButtonElement;
  let acceptButton!: HTMLButtonElement;
  const key = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopImmediatePropagation(); props.cancel();
    } else if (event.key === 'Tab') {
      event.preventDefault();
      (document.activeElement === cancelButton ? acceptButton : cancelButton).focus();
    }
  };
  const containFocus = (event: FocusEvent) => {
    if (event.target !== cancelButton && event.target !== acceptButton) cancelButton.focus();
  };
  onMount(() => {
    cancelButton.focus();
    document.addEventListener('keydown', key, true);
    document.addEventListener('focusin', containFocus);
  });
  onCleanup(() => {
    document.removeEventListener('keydown', key, true);
    document.removeEventListener('focusin', containFocus);
    if (trigger?.isConnected) trigger.focus();
  });
  return <Portal><div class="dialog-backdrop" onClick={event => { if (event.target === event.currentTarget) props.cancel(); }}>
    <div class="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <h2 id={titleId}>{props.message}</h2>
      <div class="dialog-actions">
        <button ref={cancelButton} onClick={props.cancel}>Cancel</button>
        <button ref={acceptButton} class="danger" onClick={props.accept}>{props.label}</button>
      </div>
    </div>
  </div></Portal>;
}
