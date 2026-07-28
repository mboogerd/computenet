// M4-FE ticket Implement §1: "two screens — Home (navigator) and Graph
// (existing canvas), with graph id + selected ref + toggle set encoded in
// the URL hash (no router library; hash-parse like agora does)." Modeled on
// demo/agora/ui/src/app.tsx's manual `location.hash` parse/format — no
// router dependency (M4-FE ticket Exclusions: "No router/state libraries").
//
// Shape: '#/' (or '', or bare '#') for Home; '#/g/<graphId>/<ref>/<toggles>'
// for a Graph screen, where <ref> is the empty segment when nothing is
// selected and <toggles> is a comma-joined list of active toggle keys
// (canonicalized to TOGGLE_KEYS order on write; order-independent, deduped
// on read). graphId/ref are each independently percent-encoded — a cell ref
// (`<uuid>:<instanceId>`) survives unescaped in practice, but this does not
// assume that of every future graph/ref id shape.
//
// Framework-free (no Solid import) so the parse/format pair is directly
// unit-testable (test/route.test.ts) — the Solid wiring (`solid/route.ts`)
// is a thin, untested-by-design layer over this, same split as
// `sync/client.ts` vs `solid/state.ts`.

/** The toggle keys that round-trip through the hash — exactly the four
 *  *functional* overlay toggles (`solid/toggles.ts`); "Network hosts" stays
 *  disabled/always-false through M5 (10-target-v3.md toggle table), so it
 *  has no real signal to serialize and is deliberately absent here rather
 *  than faked. */
export type ToggleKey = 'hosts' | 'flow' | 'errors' | 'state';
export const TOGGLE_KEYS: readonly ToggleKey[] = ['hosts', 'flow', 'errors', 'state'];

export interface HomeRoute {
  readonly screen: 'home';
}

export interface GraphRoute {
  readonly screen: 'graph';
  readonly graphId: string;
  readonly ref: string | null;
  readonly toggles: readonly ToggleKey[];
}

export type Route = HomeRoute | GraphRoute;

export const HOME_ROUTE: HomeRoute = { screen: 'home' };

/** Parse `location.hash` (leading '#' included, exactly as the browser hands
 *  it) into a {@link Route}. Any shape this function does not recognize —
 *  including a bare '#', '#/', or a hand-edited/stale hash — falls back to
 *  Home rather than throwing; a malformed URL must never crash the app. */
export function parseHash(hash: string): Route {
  const body = hash.replace(/^#\/?/, '');
  if (!body) return HOME_ROUTE;

  const parts = body.split('/');
  if (parts[0] !== 'g' || !parts[1]) return HOME_ROUTE;

  const graphId = decodeURIComponent(parts[1]);
  const refPart = parts[2] ?? '';
  const ref = refPart ? decodeURIComponent(refPart) : null;
  const togglePart = parts[3] ?? '';
  // Canonicalized to TOGGLE_KEYS order (and deduped for free, since
  // TOGGLE_KEYS itself has no duplicates) by filtering the fixed key list
  // against what's present, rather than mapping the raw split — an unknown
  // token (a stale/future toggle key) is silently dropped, additive
  // evolution in the same spirit as the SSE event contract.
  const present = new Set(togglePart ? togglePart.split(',') : []);
  const toggles = TOGGLE_KEYS.filter((k) => present.has(k));

  return { screen: 'graph', graphId, ref, toggles };
}

/** Inverse of {@link parseHash}: always produces a `#/...` string that
 *  `parseHash` reads back as an equal (canonicalized) Route — see
 *  test/route.test.ts's round-trip cases. Trailing empty segments (no
 *  selection, no toggles) are trimmed so a bare graph view formats as
 *  `#/g/<id>` rather than `#/g/<id>//`. */
export function formatHash(route: Route): string {
  if (route.screen === 'home') return '#/';

  const toggles = TOGGLE_KEYS.filter((t) => route.toggles.includes(t));
  const parts = ['g', encodeURIComponent(route.graphId), route.ref ? encodeURIComponent(route.ref) : '', toggles.join(',')];
  while (parts.length > 2 && parts[parts.length - 1] === '') parts.pop();
  return `#/${parts.join('/')}`;
}
