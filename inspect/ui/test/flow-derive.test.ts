import { describe, expect, it } from 'vitest';
import type { FlowEdgeState } from '../src/sync/flowStore';
import {
  deriveEdgeFlowOverlays,
  flowLabelText,
  flowTooltip,
  formatRoute,
  portFlowRows,
  pulseCountFor,
  pulseDurationMsFor,
  pulsesToRender,
  rateBand,
  type EdgeFlowOverlay,
  type FlowEdgeTarget,
  type PortFlowEdge,
} from '../src/util/flow';

function flowState(over: Partial<FlowEdgeState> = {}): FlowEdgeState {
  return { id: 'e1', rate: 10, lastWave: null, hop: null, ...over };
}

describe('rateBand', () => {
  it('band 1 for a trickle (below the band-2 threshold)', () => {
    expect(rateBand(0.1)).toBe(1);
    expect(rateBand(4.99)).toBe(1);
  });

  it('band 2 at and above the band-2 threshold, below band-3', () => {
    expect(rateBand(5)).toBe(2);
    expect(rateBand(24.9)).toBe(2);
  });

  it('band 3 at and above the band-3 threshold', () => {
    expect(rateBand(25)).toBe(3);
    expect(rateBand(1000)).toBe(3);
  });

  it('pulse count and duration step monotonically with band (do NOT animate per-message)', () => {
    expect(pulseCountFor(1)).toBeLessThan(pulseCountFor(2));
    expect(pulseCountFor(2)).toBeLessThan(pulseCountFor(3));
    // faster (shorter duration) at higher bands
    expect(pulseDurationMsFor(1)).toBeGreaterThan(pulseDurationMsFor(2));
    expect(pulseDurationMsFor(2)).toBeGreaterThan(pulseDurationMsFor(3));
  });
});

describe('deriveEdgeFlowOverlays', () => {
  const edge = (id: string, fused: boolean | null = null): FlowEdgeTarget => ({ id, fused });

  it('is empty for every edge when disabled — "when off, none of it renders"', () => {
    const overlays = deriveEdgeFlowOverlays([edge('e1')], () => flowState(), false);
    expect(overlays.size).toBe(0);
  });

  it('reports a fused edge as fused regardless of any flow-store entry for its id', () => {
    const overlays = deriveEdgeFlowOverlays([edge('e1', true)], () => flowState({ rate: 99 }), true);
    expect(overlays.get('e1')).toEqual({ kind: 'fused' });
  });

  it('reports an active edge with its rate and derived band', () => {
    const overlays = deriveEdgeFlowOverlays(
      [edge('e1', false)],
      () => flowState({ rate: 30, lastWave: { source: 'a', counter: 1 }, hop: 2 }),
      true,
    );
    expect(overlays.get('e1')).toEqual({
      kind: 'active',
      rate: 30,
      band: 3,
      lastWave: { source: 'a', counter: 1 },
      hop: 2,
    });
  });

  it('contributes nothing for an edge with no current reading (never seen, or decayed)', () => {
    const overlays = deriveEdgeFlowOverlays([edge('e1', false)], () => undefined, true);
    expect(overlays.has('e1')).toBe(false);
  });

  it('contributes nothing for an edge whose stored rate is exactly zero', () => {
    const overlays = deriveEdgeFlowOverlays([edge('e1', false)], () => flowState({ rate: 0 }), true);
    expect(overlays.has('e1')).toBe(false);
  });

  it('an edge with fused: null (M0-only topology) is treated as a normal (non-fused) edge', () => {
    const overlays = deriveEdgeFlowOverlays([edge('e1', null)], () => flowState({ rate: 5 }), true);
    expect(overlays.get('e1')?.kind).toBe('active');
  });
});

describe('pulsesToRender', () => {
  it('is 0 for a fused overlay (no pulses on a fused edge, ever)', () => {
    expect(pulsesToRender({ kind: 'fused' }, false)).toBe(0);
    expect(pulsesToRender({ kind: 'fused' }, true)).toBe(0);
  });

  it('equals the band pulse count for an active overlay when motion is allowed', () => {
    const overlay: EdgeFlowOverlay = { kind: 'active', rate: 30, band: 3, lastWave: null, hop: null };
    expect(pulsesToRender(overlay, false)).toBe(pulseCountFor(3));
  });

  it('is 0 for an active overlay under prefers-reduced-motion — "static intensity styling instead of pulses"', () => {
    const overlay: EdgeFlowOverlay = { kind: 'active', rate: 30, band: 3, lastWave: null, hop: null };
    expect(pulsesToRender(overlay, true)).toBe(0);
  });
});

describe('flowLabelText', () => {
  it('is "fused" for a fused overlay', () => {
    expect(flowLabelText({ kind: 'fused' })).toBe('fused');
  });

  it('is the rate formatted to one decimal + "/s" for an active overlay', () => {
    expect(flowLabelText({ kind: 'active', rate: 12.53, band: 2, lastWave: null, hop: null })).toBe('12.5/s');
  });
});

describe('formatRoute + flowTooltip', () => {
  const nameOf = (ref: string) => (ref === 'a:0' ? 'candSkills' : null);

  it('formats a route using cell names where known, else a shortened ref', () => {
    expect(formatRoute({ ref: 'a:0', port: 'outlet' }, { ref: 'b:0', port: 'inlet' }, nameOf)).toBe(
      'candSkills.outlet → b:0.inlet',
    );
  });

  it('uses the ticket\'s exact fused wording', () => {
    expect(flowTooltip('X.out → Y.in', { kind: 'fused' })).toBe('X.out → Y.in — fused — no observable messages');
  });

  it('includes wave, hop, and rate for an active overlay', () => {
    const overlay: EdgeFlowOverlay = {
      kind: 'active',
      rate: 12.5,
      band: 2,
      lastWave: { source: '9c41a2f0', counter: 288 },
      hop: 1,
    };
    expect(flowTooltip('X.out → Y.in', overlay)).toBe('X.out → Y.in — wave 9c41a2f0·288 · hop 1 · 12.5/s');
  });

  it('falls back to placeholders for an active overlay with no wave/hop yet', () => {
    const overlay: EdgeFlowOverlay = { kind: 'active', rate: 3, band: 1, lastWave: null, hop: null };
    expect(flowTooltip('X.out → Y.in', overlay)).toBe('X.out → Y.in — wave — · hop — · 3.0/s');
  });

  it('reports "no observed traffic" for an edge with no overlay at all', () => {
    expect(flowTooltip('X.out → Y.in', undefined)).toBe('X.out → Y.in — no observed traffic');
  });
});

describe('portFlowRows', () => {
  const ports = [
    { name: 'outlet', dir: 'OUT' as const, contractFqn: 'x' },
    { name: 'inlet', dir: 'IN' as const, contractFqn: 'x' },
  ];

  const edge = (over: Partial<PortFlowEdge> = {}): PortFlowEdge => ({
    id: 'e1',
    from: { ref: 'a:0', port: 'outlet' },
    to: { ref: 'b:0', port: 'inlet' },
    fused: null,
    ...over,
  });

  // An OUT port is one FanOutlet, and the server reports that one outlet's
  // emission count on *each* of its edges (M3-BE attribution: broadcast, so
  // duplicated across edges, never divided). Summing them would multiply the
  // port's true rate by its fan-out — measured live at M3-EVAL as
  // `jobSkills.outlet` emitting 6/s across 5 edges and reporting 30/s.
  it('reports an OUT port\'s emission rate once, not multiplied by its fan-out', () => {
    const rows = portFlowRows(
      ports,
      'a:0',
      [edge({ id: 'e1', to: { ref: 'b:0', port: 'inlet' } }), edge({ id: 'e2', to: { ref: 'c:0', port: 'inlet' } })],
      (id) => (id === 'e1' ? flowState({ id: 'e1', rate: 6 }) : flowState({ id: 'e2', rate: 6 })),
    );
    const outRow = rows.find((r) => r.port === 'outlet')!;
    expect(outRow.rate).toBe(6);
    expect(outRow.fused).toBe(false);
  });

  it('takes the highest reading when an OUT port\'s edges disagree (one bound mid-window)', () => {
    const rows = portFlowRows(
      ports,
      'a:0',
      [edge({ id: 'e1', to: { ref: 'b:0', port: 'inlet' } }), edge({ id: 'e2', to: { ref: 'c:0', port: 'inlet' } })],
      (id) => (id === 'e1' ? flowState({ id: 'e1', rate: 5 }) : flowState({ id: 'e2', rate: 7 })),
    );
    expect(rows.find((r) => r.port === 'outlet')!.rate).toBe(7);
  });

  // The IN direction is the opposite case: distinct upstream outlets, so the
  // readings are independent streams and genuinely add.
  it('sums an IN port\'s edges, which come from distinct producing outlets', () => {
    const rows = portFlowRows(
      ports,
      'b:0',
      [
        edge({ id: 'e1', from: { ref: 'a:0', port: 'outlet' }, to: { ref: 'b:0', port: 'inlet' } }),
        edge({ id: 'e2', from: { ref: 'c:0', port: 'outlet' }, to: { ref: 'b:0', port: 'inlet' } }),
      ],
      (id) => (id === 'e1' ? flowState({ id: 'e1', rate: 5 }) : flowState({ id: 'e2', rate: 7 })),
    );
    expect(rows.find((r) => r.port === 'inlet')!.rate).toBe(12);
  });

  it('reads the IN port for the edge terminating there, keyed on ref+port not just port name', () => {
    const rows = portFlowRows(ports, 'b:0', [edge({ id: 'e1' })], (id) => (id === 'e1' ? flowState({ id: 'e1', rate: 4 }) : undefined));
    const inRow = rows.find((r) => r.port === 'inlet')!;
    expect(inRow.rate).toBe(4);
  });

  it('labels a port fused when every edge touching it is fused, rate 0', () => {
    const rows = portFlowRows(ports, 'a:0', [edge({ id: 'e1', fused: true })], () => flowState({ rate: 999 }));
    const outRow = rows.find((r) => r.port === 'outlet')!;
    expect(outRow.fused).toBe(true);
    expect(outRow.rate).toBe(0);
  });

  it('a port with a mix of fused and active edges reports the active ones\' summed rate, not fused', () => {
    const rows = portFlowRows(
      ports,
      'a:0',
      [
        edge({ id: 'e1', to: { ref: 'b:0', port: 'inlet' }, fused: true }),
        edge({ id: 'e2', to: { ref: 'c:0', port: 'inlet' }, fused: false }),
      ],
      (id) => (id === 'e2' ? flowState({ id: 'e2', rate: 6 }) : undefined),
    );
    const outRow = rows.find((r) => r.port === 'outlet')!;
    expect(outRow.fused).toBe(false);
    expect(outRow.rate).toBe(6);
  });

  it('a port with no touching edges reports rate 0, not fused', () => {
    const rows = portFlowRows(ports, 'a:0', [], () => undefined);
    for (const r of rows) {
      expect(r.rate).toBe(0);
      expect(r.fused).toBe(false);
      expect(r.lastWave).toBeNull();
    }
  });

  it('reports the most-advanced (highest counter) wave among the port\'s edges', () => {
    const rows = portFlowRows(
      ports,
      'a:0',
      [edge({ id: 'e1', to: { ref: 'b:0', port: 'inlet' } }), edge({ id: 'e2', to: { ref: 'c:0', port: 'inlet' } })],
      (id) =>
        id === 'e1'
          ? flowState({ id: 'e1', rate: 1, lastWave: { source: 'x', counter: 5 } })
          : flowState({ id: 'e2', rate: 1, lastWave: { source: 'x', counter: 9 } }),
    );
    const outRow = rows.find((r) => r.port === 'outlet')!;
    expect(outRow.lastWave).toEqual({ source: 'x', counter: 9 });
  });
});
