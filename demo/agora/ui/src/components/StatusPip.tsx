import { conn } from '../solid/graph';
import './StatusPip.css';

/** Connection status (spec §7). EventSource auto-reconnects; the on-connect
 *  snapshot is the whole resync story, so this is purely informational. */
export default function StatusPip() {
  return (
    <span
      class="status-pip"
      classList={{
        'is-live': conn() === 'live',
        'is-connecting': conn() === 'connecting',
        'is-reconnecting': conn() === 'reconnecting',
      }}
      title={`connection: ${conn()}`}
    >
      <span class="status-pip__dot" />
      {conn()}
    </span>
  );
}
