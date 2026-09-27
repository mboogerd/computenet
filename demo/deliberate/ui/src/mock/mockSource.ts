import { ACTIVE_STATUSES, type GraphDto, type NodeDto, type Override, type Polarity } from '../api/types';
import type { ConnState, GraphSource } from '../sync/source';

/** `?mock`: a scripted deliberation that grows over ~15 s, so the UI can be
 *  developed and eyeballed without the backend. Commands are logged no-ops. */
export class MockSource implements GraphSource {
  private timer?: ReturnType<typeof setTimeout>;
  private nodes = new Map<string, NodeDto>();

  constructor(private stepMs = 550) {}

  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    onState('mock');
    this.nodes.clear();
    seedFinished(this);
    const steps = script(this);
    let i = 0;
    const tick = () => {
      if (i < steps.length) steps[i++]();
      onGraph(this.snapshot());
      if (i < steps.length) this.timer = setTimeout(tick, this.stepMs);
    };
    tick();
  }

  stop(): void {
    clearTimeout(this.timer);
  }

  async ask(text: string): Promise<string | undefined> {
    console.info('[mock] POST /question', { text });
    return undefined;
  }

  async override(id: string, mode: Override): Promise<void> {
    console.info('[mock] POST /override', { id, mode });
  }

  // --- model helpers used by the script ---

  claim(ref: string, root: string, text: string, depth: number, proposer: string, patch: Partial<NodeDto> = {}): void {
    this.nodes.set(ref, {
      ref,
      kind: 'CLAIM',
      credence: 0.5,
      root,
      text,
      depth,
      status: 'QUEUED',
      override: 'AUTO',
      proposer,
      rounds: 0,
      duplicatesDropped: 0,
      ...patch,
    });
  }

  arg(ref: string, root: string, parent: string, polarity: Polarity, text: string, proposer: string, patch: Partial<NodeDto> = {}): void {
    const depth = (this.nodes.get(parent)?.depth ?? 0) + 1;
    this.claim(ref, root, text, depth, proposer, patch);
    this.nodes.set(`${ref}>${parent}`, { ref: `${ref}>${parent}`, kind: 'EDGE', credence: 0.5, root, polarity, source: ref, target: parent });
  }

  set(ref: string, patch: Partial<NodeDto>): void {
    const n = this.nodes.get(ref);
    if (n) this.nodes.set(ref, { ...n, ...patch });
  }

  edge(child: string, parent: string, strength: number): void {
    this.set(`${child}>${parent}`, { strength, credence: strength });
  }

  snapshot(): GraphDto {
    const nodes = [...this.nodes.values()].map((n) => ({ ...n }));
    const roots = nodes.filter((n) => n.kind === 'CLAIM' && n.depth === 0);
    const questions = roots.map((r) => {
      const claims = nodes.filter((n) => n.kind === 'CLAIM' && n.root === r.ref);
      return {
        root: r.ref,
        text: r.text ?? '',
        claims: claims.length,
        active: claims.some((c) => c.status !== undefined && ACTIVE_STATUSES.has(c.status)),
      };
    });
    return { questions, nodes };
  }
}

function seedFinished(m: MockSource): void {
  const q = 'w0';
  m.claim(q, q, 'Should our team adopt a four-day work week?', 0, 'question', {
    status: 'SATURATED', credence: 0.58, plausibility: 0.5, rounds: 2, proSaturation: 0.81, conSaturation: 0.74,
  });
  m.arg('w1', q, q, 'SUPPORT', 'Trials report stable output with fewer hours worked.', 'claude', { status: 'SATURATED', credence: 0.71, plausibility: 0.75, relevance: 0.83, rounds: 1 });
  m.arg('w2', q, q, 'ATTACK', 'Customer support coverage would drop on the fifth day.', 'codex', { status: 'PRUNED', credence: 0.42, plausibility: 0.5, relevance: 0.31 });
  m.arg('w3', q, 'w1', 'ATTACK', 'Trial participants self-selected and are not representative.', 'codex', { status: 'DEPTH_LIMIT', credence: 0.55, plausibility: 0.5 });
  m.edge('w1', q, 0.72);
  m.edge('w2', q, 0.48);
  m.edge('w3', 'w1', 0.61);
}

function script(m: MockSource): Array<() => void> {
  const q = 'q0';
  return [
    () => m.claim(q, q, 'Should cities ban private cars from their centres?', 0, 'question'),
    () => m.set(q, { status: 'JUDGING' }),
    () => m.set(q, { status: 'EXPLORING', plausibility: 0.5 }),
    () => m.arg('c1', q, q, 'SUPPORT', 'Car-free centres measurably reduce air pollution and noise.', 'claude'),
    () => m.arg('c2', q, q, 'ATTACK', 'A ban would hurt small retailers who depend on drive-in customers.', 'codex'),
    () => m.arg('c3', q, q, 'SUPPORT', 'Freed road space can be given to transit, cycling and greenery.', 'codex'),
    () => {
      m.arg('c4', q, q, 'ATTACK', 'People with limited mobility rely on cars to reach the centre.', 'claude');
      m.edge('c1', q, 0.78);
    },
    () => {
      m.edge('c2', q, 0.55);
      m.edge('c3', q, 0.62);
      m.set(q, { credence: 0.61 });
    },
    () => {
      m.edge('c4', q, 0.7);
      m.set(q, { credence: 0.54, rounds: 1, proSaturation: 0.42, conSaturation: 0.51, duplicatesDropped: 1 });
      m.set('c1', { plausibility: 0.75, credence: 0.74 });
    },
    () => m.arg('c5', q, q, 'SUPPORT', 'Pedestrian zones tend to raise footfall for shops over time.', 'claude'),
    () => {
      m.edge('c5', q, 0.66);
      m.set(q, { status: 'SATURATED', rounds: 2, proSaturation: 0.83, conSaturation: 0.76, credence: 0.6 });
      m.set('c1', { status: 'JUDGING' });
    },
    () => m.set('c1', { status: 'EXPLORING', relevance: 0.81 }),
    () => m.arg('c1a', q, 'c1', 'ATTACK', 'Traffic may simply shift to the ring roads, moving pollution elsewhere.', 'codex'),
    () => {
      m.arg('c1b', q, 'c1', 'SUPPORT', 'Madrid and Oslo saw NO₂ fall after restricting central traffic.', 'claude');
      m.edge('c1a', 'c1', 0.58);
      m.set('c2', { status: 'JUDGING' });
    },
    () => {
      m.edge('c1b', 'c1', 0.8);
      m.set('c1', { credence: 0.69 });
      m.set('c2', { status: 'PRUNED', relevance: 0.34, plausibility: 0.5, credence: 0.47 });
    },
    () => m.set('c3', { status: 'JUDGING' }),
    () => m.set('c3', { status: 'EXPLORING', relevance: 0.66, plausibility: 0.75 }),
    () => {
      m.arg('c3a', q, 'c3', 'ATTACK', 'Transit capacity cannot be expanded quickly enough to absorb car trips.', 'codex');
      m.set('c1', { status: 'SATURATED', rounds: 1, proSaturation: 0.77, conSaturation: 0.72 });
    },
    () => {
      m.edge('c3a', 'c3', 0.64);
      m.set('c3', { error: 'codex: timed out after 120 s', credence: 0.58 });
      m.set(q, { credence: 0.57 });
    },
    () => m.set('c3a', { status: 'JUDGING' }),
    () => m.set('c3a', { status: 'EXPLORING', relevance: 0.59, plausibility: 0.5 }),
    () => m.arg('c3a1', q, 'c3a', 'ATTACK', 'Bus lanes can be painted in weeks, far faster than rail is built.', 'claude', { status: 'DEPTH_LIMIT' }),
    () => {
      m.edge('c3a1', 'c3a', 0.52);
      m.set('c3a', { status: 'SATURATED', rounds: 1, proSaturation: 0.71, conSaturation: 0.7, credence: 0.49 });
      m.set('c3', { status: 'ROUND_LIMIT', rounds: 3, proSaturation: 0.55, conSaturation: 0.62, duplicatesDropped: 2 });
      m.set('c4', { status: 'JUDGING' });
    },
    () => m.set('c4', { status: 'EXPLORING', relevance: 0.72, plausibility: 0.75, credence: 0.7 }),
    () => {
      m.arg('c4a', q, 'c4', 'ATTACK', 'Exemption permits for disabled drivers are standard in existing schemes.', 'codex');
      m.set('c1a', { status: 'JUDGING' });
    },
    () => {
      m.edge('c4a', 'c4', 0.75);
      m.set('c4', { credence: 0.52 });
      m.set('c1a', { status: 'PRUNED', relevance: 0.44, plausibility: 0.5 });
      m.set(q, { credence: 0.63 });
    },
    () => {
      m.set('c4', { status: 'SATURATED', rounds: 1, proSaturation: 0.74, conSaturation: 0.8 });
      m.set('c5', { status: 'STOPPED', override: 'STOP', plausibility: 0.5, credence: 0.56 });
      m.set('c1b', { status: 'DEPTH_LIMIT', plausibility: 0.75, credence: 0.76 });
      m.set('c4a', { status: 'BUDGET', plausibility: 0.75, credence: 0.75 });
    },
  ];
}
