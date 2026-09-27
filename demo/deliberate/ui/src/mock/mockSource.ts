import { ACTIVE_STATUSES, DEFAULT_CONSENSUS, type GraphDto, type NodeDto, type Override, type Polarity } from '../api/types';

/** The backend's credence layers, and per-layer log-odds shifts that make the mock's rules disagree plausibly. */
const LAYERS = ['dfquad', 'wlo', 'jnb', 'woe', 'euler', 'qe', 'mlp'] as const;
const LAYER_SHIFT = [0, 0.18, -0.12, 0.08, 0.5, -0.4, 0.3];
const logit = (p: number) => Math.log(p / (1 - p));
const sigmoid = (z: number) => 1 / (1 + Math.exp(-z));
const clampP = (p: number) => Math.min(0.999, Math.max(0.001, p));

/** Mock credence layers around `credence`: disagreement grows with how far the claim is from neutral. */
export function mockLayers(n: NodeDto): Pick<NodeDto, 'credences' | 'consensus' | 'spreadLow' | 'spreadHigh'> {
  const z = logit(clampP(n.credence));
  const scale = 0.4 + Math.abs(z);
  const credences = Object.fromEntries(LAYERS.map((id, i) => [id, sigmoid(z + LAYER_SHIFT[i] * scale)]));
  const members = DEFAULT_CONSENSUS.map((id) => logit(clampP(credences[id])));
  const values = Object.values(credences);
  return {
    credences,
    consensus: sigmoid(members.reduce((a, b) => a + b, 0) / members.length),
    spreadLow: Math.min(...values),
    spreadHigh: Math.max(...values),
  };
}
import type { ConnState, GraphSource } from '../sync/source';

/** `?mock`: a scripted deliberation that grows over ~20 s, so the UI can be
 *  developed and eyeballed without the backend. `?mock=empty` starts with no
 *  questions (the welcome screen); asking anything then runs the script with
 *  that text. Overrides are applied locally so the control visibly works. */
export class MockSource implements GraphSource {
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private nodes = new Map<string, NodeDto>();
  private onGraph?: (g: GraphDto) => void;
  private nextQuestion = 0;

  constructor(
    private stepMs = 650,
    private empty = false,
  ) {}

  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    this.stop();
    onState('mock');
    this.onGraph = onGraph;
    this.nodes.clear();
    this.nextQuestion = 0;
    if (this.empty) {
      onGraph(this.snapshot());
      return;
    }
    seedFinished(this);
    this.run();
  }

  private run(questionText?: string): string {
    const { root, steps } = script(this, this.nextQuestion++, questionText);
    let i = 0;
    const tick = () => {
      if (i < steps.length) steps[i++]();
      this.onGraph?.(this.snapshot());
      if (i < steps.length) {
        let timer!: ReturnType<typeof setTimeout>;
        timer = setTimeout(() => {
          this.timers.delete(timer);
          tick();
        }, this.stepMs);
        this.timers.add(timer);
      }
    };
    tick();
    return root;
  }

  stop(): void {
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    this.onGraph = undefined;
  }

  async ask(text: string): Promise<string> {
    console.info('[mock] POST /question', { text });
    return this.run(text);
  }

  async override(id: string, mode: Override): Promise<void> {
    console.info('[mock] POST /override', { id, mode });
    const node = this.nodes.get(id);
    if (node?.kind !== 'CLAIM') throw new Error(`unknown claim ${id}`);
    const status = mode === 'STOP'
      ? 'STOPPED'
      : mode === 'AUTO' && node.status === 'STOPPED'
        ? 'QUEUED'
        : mode === 'EXPAND' && (node.status === undefined || !ACTIVE_STATUSES.has(node.status))
          ? 'EXPLORING'
          : node.status;
    this.set(id, { override: mode, status });
    this.onGraph?.(this.snapshot());
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

  /** EXP-03 UNDERCUT: [ref] attacks the link from argument [arg] to [parent]. */
  undercut(ref: string, root: string, arg: string, parent: string, text: string, proposer: string, patch: Partial<NodeDto> = {}): void {
    const link = `${arg}>${parent}`;
    const depth = this.nodes.get(arg)?.depth ?? 1;
    this.claim(ref, root, text, depth, proposer, { undercuts: link, ...patch });
    this.nodes.set(`${ref}>${link}`, { ref: `${ref}>${link}`, kind: 'EDGE', credence: 0.5, root, polarity: 'ATTACK', source: ref, target: link });
  }

  set(ref: string, patch: Partial<NodeDto>): void {
    const n = this.nodes.get(ref);
    if (n) {
      const next = { ...n, ...patch };
      // CTL-03: scripted in-flight results may still arrive, but STOP remains
      // terminal until the user returns the claim to AUTO.
      if (n.override === 'STOP' && patch.override === undefined) next.status = 'STOPPED';
      this.nodes.set(ref, next);
    }
  }

  edge(child: string, parent: string, strength: number): void {
    this.set(`${child}>${parent}`, { strength, credence: strength });
  }

  snapshot(): GraphDto {
    const nodes = [...this.nodes.values()].map((n) => ({ ...n }));
    // EXP-05 reach, derived as the backend does: root 1, child = parent × edge strength.
    const byRef = new Map(nodes.map((n) => [n.ref, n]));
    const parentEdge = new Map(nodes.filter((n) => n.kind === 'EDGE').map((e) => [e.source!, e]));
    const reachOf = (c: NodeDto): number | undefined => {
      if (c.depth === 0) return 1;
      const e = parentEdge.get(c.ref);
      const parent = e?.target === undefined ? undefined : byRef.get(e.target);
      const pr = parent ? reachOf(parent) : undefined;
      return pr === undefined || e?.strength === undefined ? undefined : pr * e.strength;
    };
    for (const n of nodes) if (n.kind === 'CLAIM') n.reach = reachOf(n);
    for (const n of nodes) Object.assign(n, mockLayers(n));
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
    return { questions, nodes, consensusMembers: [...DEFAULT_CONSENSUS] };
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

function script(
  source: MockSource,
  sequence: number,
  text = 'Should the Northfield library open on Sundays?',
): { root: string; steps: Array<() => void> } {
  const root = `q${sequence}`;
  const scoped = (ref: string) => (ref === 'q0' ? root : `${root}-${ref}`);
  const m = {
    claim: (ref: string, treeRoot: string, claimText: string, depth: number, proposer: string, patch?: Partial<NodeDto>) =>
      source.claim(scoped(ref), scoped(treeRoot), claimText, depth, proposer, patch),
    arg: (
      ref: string,
      treeRoot: string,
      parent: string,
      polarity: Polarity,
      claimText: string,
      proposer: string,
      patch?: Partial<NodeDto>,
    ) => source.arg(scoped(ref), scoped(treeRoot), scoped(parent), polarity, claimText, proposer, patch),
    set: (ref: string, patch: Partial<NodeDto>) => source.set(scoped(ref), patch),
    edge: (child: string, parent: string, strength: number) => source.edge(scoped(child), scoped(parent), strength),
    undercut: (ref: string, treeRoot: string, arg: string, parent: string, claimText: string, proposer: string, patch?: Partial<NodeDto>) =>
      source.undercut(scoped(ref), scoped(treeRoot), scoped(arg), scoped(parent), claimText, proposer, patch),
  };
  const q = 'q0';
  const steps = [
    () => m.claim(q, q, text, 0, 'question'),
    () => m.set(q, { status: 'JUDGING' }),
    () => m.set(q, { status: 'EXPLORING', plausibility: 0.5 }),
    () => m.arg('c1', q, q, 'SUPPORT', 'Weekend visitor counts at the Northfield library are the highest of the week.', 'claude'),
    () => m.arg('c2', q, q, 'ATTACK', 'Sunday opening would require paying the library staff a weekend wage premium.', 'codex'),
    () => m.arg('c3', q, q, 'SUPPORT', 'Students without quiet space at home need somewhere to study on Sundays.', 'codex'),
    () => {
      m.arg('c4', q, q, 'ATTACK', 'The volunteers who run the Northfield reading groups are unavailable on Sundays.', 'claude');
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
    () => m.arg('c5', q, q, 'SUPPORT', 'Libraries in neighbouring towns that open on Sundays report steady Sunday attendance.', 'claude'),
    () => {
      m.edge('c5', q, 0.66);
      m.set(q, { status: 'SATURATED', rounds: 2, proSaturation: 0.83, conSaturation: 0.76, credence: 0.6 });
      m.set('c1', { status: 'JUDGING' });
    },
    () => m.set('c1', { status: 'EXPLORING', relevance: 0.81 }),
    () => m.arg('c1a', q, 'c1', 'ATTACK', 'Most weekend visitors could come on Saturday instead.', 'codex'),
    () => {
      m.arg('c1b', q, 'c1', 'SUPPORT', 'The Saturday reading room at the Northfield library is full by noon on most weekends.', 'claude');
      m.edge('c1a', 'c1', 0.58);
      m.set('c2', { status: 'JUDGING' });
    },
    () => {
      m.edge('c1b', 'c1', 0.8);
      m.set('c1', { credence: 0.69 });
      m.undercut('c1bu', q, 'c1b', 'c1', 'The Saturday reading room lost half its seats to a renovation in the same months.', 'codex', {
        status: 'DEPTH_LIMIT', plausibility: 0.75, credence: 0.72,
      });
      m.set('c2', { status: 'PRUNED', relevance: 0.34, plausibility: 0.5, credence: 0.47 });
    },
    () => m.set('c3', { status: 'JUDGING' }),
    () => m.set('c3', { status: 'EXPLORING', relevance: 0.66, plausibility: 0.75 }),
    () => {
      m.arg('c3a', q, 'c3', 'ATTACK', 'The town hall study centre already opens on Sundays.', 'codex');
      m.set('c1', { status: 'SATURATED', rounds: 1, proSaturation: 0.77, conSaturation: 0.72 });
    },
    () => {
      m.edge('c3a', 'c3', 0.64);
      m.set('c3', { error: 'codex: timed out after 120 s', credence: 0.58 });
      m.set(q, { credence: 0.57 });
    },
    () => m.set('c3a', { status: 'JUDGING' }),
    () => m.set('c3a', { status: 'EXPLORING', relevance: 0.59, plausibility: 0.5 }),
    () => m.arg('c3a1', q, 'c3a', 'ATTACK', 'The town hall study centre has only twenty seats.', 'claude', { status: 'DEPTH_LIMIT' }),
    () => {
      m.edge('c3a1', 'c3a', 0.52);
      m.set('c3a', { status: 'SATURATED', rounds: 1, proSaturation: 0.71, conSaturation: 0.7, credence: 0.49 });
      m.set('c3', { status: 'ROUND_LIMIT', rounds: 3, proSaturation: 0.55, conSaturation: 0.62, duplicatesDropped: 2 });
      m.set('c4', { status: 'JUDGING' });
    },
    () => m.set('c4', { status: 'EXPLORING', relevance: 0.72, plausibility: 0.75, credence: 0.7 }),
    () => {
      m.arg('c4a', q, 'c4', 'ATTACK', 'Paid staff could run the reading groups on Sundays.', 'codex');
      m.set('c1a', { status: 'JUDGING' });
    },
    () => {
      m.arg('c3a2', q, 'c3a', 'SUPPORT', 'The town hall study centre is rarely full on Sundays.', 'codex', { status: 'QUEUED' });
      m.arg('c4b', q, 'c4', 'SUPPORT', 'The library budget does not cover paid reading-group staff.', 'codex', { status: 'FAILED', error: 'claude: exit 1; codex: timed out after 120 s' });
    },
    () => {
      m.edge('c3a2', 'c3a', 0.3);
      m.edge('c4b', 'c4', 0.35);
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
      m.set('c3a2', { status: 'BUDGET', plausibility: 0.5, credence: 0.5 });
    },
  ];
  return { root, steps };
}
