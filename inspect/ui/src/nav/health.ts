import type { GraphHealth, GraphLifecycle } from '../api/types';

export interface HealthPill {
  readonly kind: 'dead' | 'parked' | 'lifecycle';
  readonly label: string;
}

/** 10-target-v3.md Navigator: "health pills (n dead / n parked / hot /
 *  cold)"; M4-FE ticket Implement §2. A dead/parked pill is omitted entirely
 *  when its count is zero — a clean card shows no problem noise — but the
 *  lifecycle pill is always present (a card should always say whether its
 *  graph is hot or cold). `restarts` is part of {@link GraphHealth} but has
 *  no pill of its own here: neither 10-target-v3.md's Navigator section nor
 *  this ticket names one, so it is surfaced only via the card's tooltip
 *  (`Navigator.tsx`), not silently dropped from the UI. */
export function deriveHealthPills(health: GraphHealth, lifecycle: GraphLifecycle): HealthPill[] {
  const pills: HealthPill[] = [];
  if (health.deadLetters > 0) pills.push({ kind: 'dead', label: `${health.deadLetters} dead` });
  if (health.parked > 0) pills.push({ kind: 'parked', label: `${health.parked} parked` });
  pills.push({ kind: 'lifecycle', label: lifecycle });
  return pills;
}
