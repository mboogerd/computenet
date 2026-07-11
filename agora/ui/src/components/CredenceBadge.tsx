import { bandFor, bandVar } from '../styles/bands';
import './CredenceBadge.css';

/** Compact band swatch + exact value on hover (spec §5). Used in node chrome,
 *  rows, chips, and the detail panel. */
export default function CredenceBadge(props: { credence: number; size?: 'sm' | 'md' }) {
  const band = () => bandFor(props.credence);
  return (
    <span
      class="credence-badge"
      classList={{ 'credence-badge--sm': props.size === 'sm' }}
      title={`credence ${props.credence.toFixed(3)}`}
    >
      <span
        class="credence-badge__swatch"
        classList={{ 'credence-badge__swatch--contested': band() === 'contested' }}
        style={{ background: bandVar(band()) }}
      />
      <span class="credence-badge__value">{props.credence.toFixed(2)}</span>
    </span>
  );
}
