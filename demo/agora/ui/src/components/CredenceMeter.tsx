import { bandFor, bandVar, BAND_LABEL } from '../styles/bands';
import './CredenceMeter.css';

/** Diverging credence meter: the neutral 0.5 midpoint is the axis, the bar
 *  grows toward "rejected" (left) or "accepted" (right) in the band's color.
 *  Dataviz "polarity" job → diverging pair + neutral midpoint. The exact value
 *  and band name stay in text beside it, so color is never the only channel. */
export default function CredenceMeter(props: { credence: number; labels?: boolean }) {
  const band = () => bandFor(props.credence);
  const c = () => Math.max(0, Math.min(1, props.credence));
  return (
    <div
      class="meter"
      role="meter"
      aria-valuemin={0}
      aria-valuemax={1}
      aria-valuenow={Number(c().toFixed(3))}
      aria-valuetext={`${c().toFixed(2)} — ${BAND_LABEL[band()]}`}
    >
      <div class="meter__track">
        <span class="meter__tick" style={{ left: '20%' }} />
        <span class="meter__tick" style={{ left: '40%' }} />
        <span class="meter__tick" style={{ left: '60%' }} />
        <span class="meter__tick" style={{ left: '80%' }} />
        <span
          class="meter__fill"
          classList={{ 'meter__fill--contested': band() === 'contested' }}
          style={{
            background: bandVar(band()),
            transform: `scaleX(${((c() - 0.5) * 2).toFixed(4)})`,
          }}
        />
        <span class="meter__mid" />
        <span class="meter__dotpos" style={{ transform: `translateX(${(c() * 100).toFixed(2)}%)` }}>
          <span class="meter__dot" style={{ background: bandVar(band()) }} />
        </span>
      </div>
      {props.labels && (
        <div class="meter__labels">
          <span>rejected</span>
          <span>contested</span>
          <span>accepted</span>
        </div>
      )}
    </div>
  );
}
