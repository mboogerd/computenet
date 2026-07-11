import './EmptyState.css';

/** First-run empty graph (spec §7). */
export default function EmptyState() {
  const focusAdd = () =>
    document.querySelector<HTMLInputElement>('.add-claim__input')?.focus();
  return (
    <div class="empty-state">
      <h2>No claims yet</h2>
      <p>Start the debate by adding the first claim, then argue for or against it.</p>
      <button class="empty-state__cta" onClick={focusAdd}>
        Add the first claim
      </button>
    </div>
  );
}
