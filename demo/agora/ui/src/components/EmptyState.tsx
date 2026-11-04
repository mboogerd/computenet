import { PlusIcon } from './Icons';
import './EmptyState.css';

/** First-run empty graph (spec §7). A rare, first-time moment, so it gets the
 *  staged entrance (illustration, title, copy, action — 100ms apart). */
export default function EmptyState() {
  const focusAdd = () =>
    document.querySelector<HTMLInputElement>('.add-claim__input')?.focus();
  return (
    <div class="empty-state">
      <svg class="empty-state__art" width="168" height="104" viewBox="0 0 168 104" aria-hidden="true">
        <path d="M44 30 L84 74" stroke="var(--edge-muted)" stroke-width="1.5" fill="none" />
        <path d="M124 30 L84 74" stroke="var(--edge-muted)" stroke-width="1.5" stroke-dasharray="5 3" fill="none" />
        <rect x="58" y="47" width="10" height="10" rx="2" transform="rotate(45 63 52)" fill="var(--band-accept-lean)" />
        <rect x="100" y="47" width="10" height="10" rx="2" transform="rotate(45 105 52)" fill="var(--band-reject-lean)" />
        <rect x="8" y="14" width="64" height="28" rx="8" fill="var(--surface)" stroke="var(--hairline-strong)" />
        <rect x="8" y="14" width="4" height="28" rx="2" fill="var(--accept-strong)" />
        <rect x="18" y="25" width="38" height="6" rx="3" fill="var(--surface-sunken)" />
        <rect x="96" y="14" width="64" height="28" rx="8" fill="var(--surface)" stroke="var(--hairline-strong)" />
        <rect x="96" y="14" width="4" height="28" rx="2" fill="var(--reject-strong)" />
        <rect x="106" y="25" width="42" height="6" rx="3" fill="var(--surface-sunken)" />
        <rect x="46" y="66" width="76" height="32" rx="8" fill="var(--surface)" stroke="var(--text-secondary)" stroke-dasharray="4 3" />
        <path d="M84 76v12M78 82h12" stroke="var(--text-secondary)" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      <h2>No claims yet</h2>
      <p>Start the debate by adding the first claim, then argue for or against it.</p>
      <button class="empty-state__cta pressable" onClick={focusAdd}>
        <PlusIcon size={16} stroke={2} />
        Add the first claim
      </button>
    </div>
  );
}
