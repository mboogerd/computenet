import type { NodeRec } from '../api/types';

const short = (ref: string) => ref.slice(0, 8);

/** Display label for any node. Edges have no text on the wire yet, so we
 *  synthesize one (spec §6). */
export function labelOf(rec: NodeRec): string {
  if (rec.kind === 'CLAIM') return rec.text ?? short(rec.ref);
  const s = rec.source ? short(rec.source) : '?';
  const t = rec.target ? short(rec.target) : '?';
  return `${rec.polarity ?? 'EDGE'} · ${s} → ${t}`;
}
