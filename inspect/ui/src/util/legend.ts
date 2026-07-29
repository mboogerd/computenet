/** Legend content for the canvas encodings that carry no on-screen key of
 *  their own (V0-FE ticket Problem (c); `10-design-notes.md`'s "Mock-up
 *  references": "per-perspective legends" among the mock-up features the
 *  shipped UI lacks). `components/Legend.tsx` renders whatever this module
 *  decides applies for the current toggle state.
 *
 *  Three entries are intrinsic to every node card/edge and are never gated
 *  by a toggle: the cell-color glyph, the manifest badges, and the edge
 *  consume/observe line style — none of those depend on any overlay being
 *  on. The remaining five each only make sense once their corresponding
 *  overlay toggle is on, mirroring the toggle-aware derivation style
 *  already used for badges/pills (`util/errors.ts`'s `cellErrorBadges`,
 *  `util/flow.ts`'s `deriveEdgeFlowOverlays`): a plain boolean parameter
 *  per toggle, no signal import here, so this stays callable and testable
 *  without a Solid runtime. */

export interface LegendEntry {
  id: string;
  label: string;
  detail: string;
}

const CELL_COLOR: LegendEntry = {
  id: 'cell-color',
  label: 'Cell color',
  detail: 'P pure · B blocking · S suspending — letter glyph, never color alone',
};

const MANIFEST_BADGE: LegendEntry = {
  id: 'manifest-badge',
  label: 'Manifest badges',
  detail: 'D durable · GF glitch-free · R replicated · PT partitioned (other manifests still render, as initials)',
};

const EDGE_ROLE: LegendEntry = {
  id: 'edge-role',
  label: 'Edge line',
  detail: 'solid = consume · dashed = observe · doubled line = fused (no observable messages)',
};

const HOST_HULL: LegendEntry = {
  id: 'host-hull',
  label: 'Process hosts',
  detail: 'solid hull groups cells running on the same process host',
};

const NET_HULL: LegendEntry = {
  id: 'net-hull',
  label: 'Network hosts',
  detail: 'dashed hull groups cells sharing a network/peer, nested around process hulls',
};

const EDGE_FLOW: LegendEntry = {
  id: 'edge-flow',
  label: 'Flow',
  detail: 'pulses/rate label on edges with observed traffic, stepped by rate band',
};

const ERROR_BADGE: LegendEntry = {
  id: 'error-badge',
  label: 'Errors',
  detail: 'red badge = dead letters/restarts on a cell · amber "n parked" pill = parked traffic on an edge',
};

const STATE_CHIP: LegendEntry = {
  id: 'state-chip',
  label: 'State chip',
  detail: 'cardinality · frontier (source · counter) · staleness, for the selected cell',
};

/** Always visible, regardless of toggle state. */
const ALWAYS_ON: readonly LegendEntry[] = [CELL_COLOR, MANIFEST_BADGE, EDGE_ROLE];

/** Which legend entries apply for the given toggle state. Order: the
 *  always-on entries first, then the toggle-gated ones in `ToggleBar.tsx`'s
 *  `TOGGLES` order (hosts, net, flow, errors, state). */
export function legendEntries(
  showHosts: boolean,
  showNet: boolean,
  showFlow: boolean,
  showErrors: boolean,
  showState: boolean,
): LegendEntry[] {
  const entries = [...ALWAYS_ON];
  if (showHosts) entries.push(HOST_HULL);
  if (showNet) entries.push(NET_HULL);
  if (showFlow) entries.push(EDGE_FLOW);
  if (showErrors) entries.push(ERROR_BADGE);
  if (showState) entries.push(STATE_CHIP);
  return entries;
}
