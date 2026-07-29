/** Node card decorations (10-target-v3.md node card spec):
 *   - color chip: P/B/S letter glyph (never color alone)
 *   - manifest badges: short letters, D/GF/R/PT named explicitly by the
 *     M0-FE ticket for the four manifests it calls out; any other manifest
 *     (PULL_SERVING, GATED, or a future addition) still renders — via
 *     initials for underscore_separated names, else its first two
 *     characters — rather than being silently dropped. */

const COLOR_GLYPH: Record<string, string> = {
  PURE: 'P',
  BLOCKING: 'B',
  SUSPENDING: 'S',
};

export function colorGlyph(color: string | null): string {
  return (color && COLOR_GLYPH[color]) || '?';
}

const MANIFEST_BADGE: Record<string, string> = {
  DURABLE: 'D',
  GLITCH_FREE: 'GF',
  REPLICATED: 'R',
  PARTITIONED: 'PT',
};

export function manifestBadge(manifest: string): string {
  const known = MANIFEST_BADGE[manifest];
  if (known) return known;
  if (manifest.includes('_')) {
    return manifest
      .split('_')
      .map((w) => w[0])
      .join('')
      .slice(0, 3)
      .toUpperCase();
  }
  return manifest.slice(0, 2).toUpperCase();
}

/** Last dotted segment of a fully-qualified class name. */
export function shortType(typeFqn: string): string {
  const i = typeFqn.lastIndexOf('.');
  return i === -1 ? typeFqn : typeFqn.slice(i + 1);
}

/** V2-FE ticket Implement §10: "capitalize the server's string for display —
 *  the server sends lowercase band names; render whatever string arrives, do
 *  not switch exhaustively on a closed set, so an unknown future value still
 *  displays." A plain first-letter uppercase, not a lookup table — the whole
 *  point is that it works for a band name this client has never seen. */
export function capitalize(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}
