// Credence -> discrete band (agora-ui-design-spec.md §5). The 5 bands read
// faster than a continuous gradient; the exact value stays on the badge/panel.
// Band keys match the --band-* CSS custom properties in tokens.css.

export type Band =
  | 'reject-strong'
  | 'reject-lean'
  | 'contested'
  | 'accept-lean'
  | 'accept-strong';

export function bandFor(credence: number): Band {
  if (credence < 0.2) return 'reject-strong';
  if (credence < 0.4) return 'reject-lean';
  if (credence < 0.6) return 'contested';
  if (credence < 0.8) return 'accept-lean';
  return 'accept-strong';
}

/** The CSS var holding this band's fill, e.g. `var(--band-contested)`. */
export function bandVar(band: Band): string {
  return `var(--band-${band})`;
}

export const BAND_LABEL: Record<Band, string> = {
  'reject-strong': 'Strongly rejected',
  'reject-lean': 'Leaning rejected',
  contested: 'Contested / neutral',
  'accept-lean': 'Leaning accepted',
  'accept-strong': 'Strongly accepted',
};
