import {
  ACTIVE_STATUSES,
  DEFAULT_CONSENSUS,
  type FramingDto,
  type GraphDto,
  type NodeDto,
  type Override,
  type Polarity,
  type PositionDto,
  type QuestionDto,
} from '../api/types';

/** The backend's credence layers, and per-layer log-odds shifts that make the mock's rules disagree plausibly. */
const LAYERS = ['dfquad', 'wlo', 'jnb', 'woe', 'euler', 'qe', 'mlp', 'glo'] as const;
const LAYER_SHIFT = [0, 0.18, -0.12, 0.08, 0.5, -0.4, 0.3, 0.12];
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

/** Every layer at the node's own credence: what a node with no arguments looks like. */
export function flatLayers(n: NodeDto): Pick<NodeDto, 'credences' | 'consensus' | 'spreadLow' | 'spreadHigh'> {
  return {
    credences: Object.fromEntries(LAYERS.map((id) => [id, n.credence])),
    consensus: n.credence,
    spreadLow: n.credence,
    spreadHigh: n.credence,
  };
}

/** `?mock`: a scripted deliberation that grows over ~20 s, so the UI can be
 *  developed and eyeballed without the backend. `?mock=empty` starts with no
 *  questions (the welcome screen); asking anything then runs the script with
 *  that text and topic-neutral claims (the built-in example's claims fit only it). Overrides are applied locally so the control visibly works. */
export class MockSource implements GraphSource {
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private nodes = new Map<string, NodeDto>();
  private onGraph?: (g: GraphDto) => void;
  private nextQuestion = 0;
  /** CTL-05: paused questions; their script holds still until resumed. */
  private paused = new Set<string>();
  /** Model A: framed roots, by ref, with their mode/term and their positions' refs in order. */
  private framing = new Map<string, { mode: 'READINGS' | 'POSITIONS'; term?: string; positions: string[] }>();

  constructor(
    private stepMs = 650,
    private empty = false,
  ) {}

  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    this.stop();
    onState('mock');
    this.onGraph = onGraph;
    this.nodes.clear();
    this.paused.clear();
    this.framing.clear();
    this.nextQuestion = 0;
    if (this.empty) {
      onGraph(this.snapshot());
      return;
    }
    seedFinished(this);
    seedFramedPositions(this);
    this.run();
  }

  private run(questionText?: string): string {
    const { root, steps } = script(this, this.nextQuestion++, questionText);
    let i = 0;
    const tick = () => {
      if (i < steps.length && !this.paused.has(root)) steps[i++]();
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
    // A link (an EDGE) is steered like a claim (SPEC §3 "Links as claims").
    if (node?.status === undefined) throw new Error(`unknown claim ${id}`);
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

  async pause(root: string, paused: boolean): Promise<void> {
    console.info('[mock] POST /question/pause', { root, paused });
    if (this.nodes.get(root)?.depth !== 0) throw new Error(`unknown question ${root}`);
    if (paused) this.paused.add(root);
    else this.paused.delete(root);
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

  /**
   * [ref] argues [polarity] about [parent] — a claim, or a link (an EDGE ref,
   * then it says why that link holds or, ATTACK, undercuts it). Its edge is a
   * link too, with the claim-like fields the backend sends (SPEC §3 "Links as claims").
   */
  arg(ref: string, root: string, parent: string, polarity: Polarity, text: string, proposer: string, patch: Partial<NodeDto> = {}): void {
    const target = this.nodes.get(parent);
    const onLink = target?.kind === 'EDGE';
    const depth = (target?.depth ?? 0) + 1;
    this.claim(ref, root, text, depth, proposer, onLink ? { onLink: parent, ...(polarity === 'ATTACK' ? { undercuts: parent } : {}), ...patch } : patch);
    const relation = polarity === 'SUPPORT' ? 'is a reason for' : 'is a reason against';
    this.nodes.set(`${ref}>${parent}`, {
      ref: `${ref}>${parent}`, kind: 'EDGE', credence: 0.5, root, polarity, source: ref, target: parent,
      text: `“${text}” ${relation} “${target?.text ?? ''}”`, depth, status: 'QUEUED', override: 'AUTO', rounds: 0,
    });
  }

  /** The link from argument [arg] to [parent], as its EDGE ref. */
  link(arg: string, parent: string): string {
    return `${arg}>${parent}`;
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

  /**
   * Model A: mark [root] framed — its own claim goes FRAMED and has no
   * arguments of its own; [positions] (already created as root-like claims
   * with `positionOf: root`, in framing order) become its readings or
   * positions. `framingOf` reads it back into a `FramingDto` at snapshot time.
   */
  frame(root: string, mode: 'READINGS' | 'POSITIONS', positions: readonly string[], term?: string): void {
    this.framing.set(root, { mode, term, positions: [...positions] });
    this.set(root, { status: 'FRAMED' });
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
    // A link's reach is its argument's; its contribution adds how unsettled its strength is.
    for (const n of nodes) {
      if (n.kind !== 'EDGE') continue;
      const arg = n.source === undefined ? undefined : byRef.get(n.source);
      n.reach = arg?.reach;
      if (n.reach !== undefined && n.strength !== undefined) n.contribution = n.reach * 4 * n.strength * (1 - n.strength);
    }
    // Model C: a stand-in sensitivity shaped like the backend's (root 1, halved and signed per
    // edge by its strength — or, for the edge itself, by its argument's credence), not its arithmetic.
    const edgeOf = new Map(nodes.filter((n) => n.kind === 'EDGE').map((e) => [e.source!, e]));
    const sensitivityOf = (n: NodeDto): number | undefined => {
      if (n.kind === 'CLAIM' && n.depth === 0) return 1;
      const e = n.kind === 'EDGE' ? n : edgeOf.get(n.ref);
      const target = e?.target === undefined ? undefined : byRef.get(e.target);
      const above = target ? sensitivityOf(target) : undefined;
      if (above === undefined || e === undefined) return undefined;
      const sign = e.polarity === 'ATTACK' ? -1 : 1;
      const factor = n.kind === 'EDGE' ? (byRef.get(e.source!)?.credence ?? 0.5) : (e.strength ?? 0.5);
      return above * sign * 0.5 * factor;
    };
    for (const n of nodes) n.sensitivity = sensitivityOf(n);
    const targeted = new Set(nodes.filter((n) => n.kind === 'EDGE').map((e) => e.target));
    for (const n of nodes) {
      // Without arguments every rule keeps the first impression: no spread, by construction.
      const argued = targeted.has(n.ref);
      Object.assign(n, argued ? mockLayers(n) : flatLayers(n));
      if (!argued) {
        n.argumentsFirstCredences = { ...n.credences };
        n.argumentsFirstConsensus = n.consensus;
      } else {
        const first = n.kind === 'EDGE' ? n.strength : n.plausibility;
        const ordinary = n.consensus ?? n.credence;
        const standing = first === undefined ? ordinary : clampP(0.5 + ordinary - first);
        const view = mockLayers({ ...n, credence: standing });
        n.argumentsFirstCredences = view.credences;
        n.argumentsFirstConsensus = view.consensus;
      }
      if (n.status === 'JUDGING') n.activity = 'judging';
      else if (n.status === 'EXPLORING') n.activity = 'exploring';
    }
    // Model A: a reading/position is also depth 0 (explored as a root of its own; SPEC §3
    // "Framing (model A)"), so it is excluded here by `positionOf` — it is not a question.
    const roots = nodes.filter((n) => n.kind === 'CLAIM' && n.depth === 0 && n.positionOf === undefined);
    const questions = roots.map((r) => {
      const claims = nodes.filter((n) => n.kind === 'CLAIM' && n.root === r.ref);
      const framing = framingOf(this.framing.get(r.ref), byRef);
      return {
        root: r.ref,
        text: r.text ?? '',
        claims: claims.length,
        active: claims.some((c) => c.status !== undefined && ACTIVE_STATUSES.has(c.status)),
        paused: this.paused.has(r.ref),
        ...mockCost(claims),
        cruxes: mockCruxes(nodes.filter((n) => n.root === r.ref && n.ref !== r.ref)),
        // Model A: a framed question's model D fields live per position instead (SPEC §6).
        ...(framing ? { framing } : mockPrior(r)),
      };
    });
    return { questions, nodes, consensusMembers: [...DEFAULT_CONSENSUS] };
  }
}

/**
 * Model D: the first impression is the root's plausibility; "arguments alone"
 * moves ½ by as much as the arguments moved the root from it (a stand-in for
 * the backend's neutral-prior verdict), flagged when the two lean differently.
 */
export function mockPrior(root: NodeDto): { firstImpression?: number; neutralCredence?: number; verdictsDisagree?: boolean } {
  const first = root.plausibility;
  if (first === undefined) return {};
  const verdict = root.consensus ?? root.credence;
  const neutral = root.argumentsFirstConsensus ?? Math.min(0.999, Math.max(0.001, 0.5 + verdict - first));
  return { firstImpression: first, neutralCredence: neutral, verdictsDisagree: (verdict - 0.5) * (neutral - 0.5) < 0 };
}

/**
 * Model A: the `FramingDto` for a framed root, built from its recorded
 * mode/term/position refs and each position's own NodeDto (SPEC §6). A
 * position ref absent from the snapshot is left out (mirrors buildTree/
 * buildForest skipping a source claim that has not arrived yet).
 */
export function framingOf(
  spec: { mode: 'READINGS' | 'POSITIONS'; term?: string; positions: string[] } | undefined,
  byRef: ReadonlyMap<string, NodeDto>,
): FramingDto | undefined {
  if (!spec) return undefined;
  const nodes = spec.positions.map((ref) => byRef.get(ref)).filter((n): n is NodeDto => n !== undefined);
  const shares = spec.mode === 'POSITIONS' ? mockShares(nodes) : undefined;
  const positions: PositionDto[] = nodes.map((n, i) => ({
    ref: n.ref,
    text: n.text ?? '',
    credence: n.consensus ?? n.credence,
    ...mockPrior(n),
    ...(shares ? { share: shares[i] } : {}),
  }));
  return { mode: spec.mode, term: spec.term, positions };
}

/**
 * Model A, POSITIONS: the "softmax cell" mirrored in TS — score = logit(clamped
 * credence), temperature 1 → shares = normalised odds (exp(logit)/Σ, which is
 * the same as each position's own credence-odds normalised over the set).
 * Sums to 1; before any position has emitted (all credences equal) every
 * share is 1/n.
 */
export function mockShares(nodes: readonly NodeDto[]): number[] {
  const odds = nodes.map((n) => {
    const p = clampP(n.consensus ?? n.credence);
    return p / (1 - p);
  });
  const sum = odds.reduce((a, b) => a + b, 0);
  return sum === 0 ? nodes.map(() => 1 / nodes.length) : odds.map((o) => o / sum);
}

/** Model C: the top 3 of [nodes] by |sensitivity| × 4·p·(1 − p) (a link's p is its strength), as the backend ranks them. */
export function mockCruxes(nodes: readonly NodeDto[]): string[] {
  const score = (n: NodeDto): number => {
    const p = (n.kind === 'EDGE' ? n.strength : n.plausibility) ?? 0.5;
    return n.sensitivity === undefined ? 0 : Math.abs(n.sensitivity) * 4 * p * (1 - p);
  };
  return nodes
    .filter((n) => score(n) > 0)
    .sort((a, b) => score(b) - score(a))
    .slice(0, 3)
    .map((n) => n.ref);
}

/**
 * SPEC §12: plausible cost for a mock tree, shaped like the backend's — per
 * round one Claude and one Codex call per side, and a handful of Jev
 * requests per claim (prices as the backend's defaults, measured magnitudes).
 */
export function mockCost(claims: readonly NodeDto[]): Pick<QuestionDto, 'costUsd' | 'projectedUsd' | 'cost'> {
  const rounds = claims.reduce((a, c) => a + (c.rounds ?? 0), 0);
  const queued = claims.filter((c) => c.status !== undefined && ACTIVE_STATUSES.has(c.status)).length;
  const calls = 2 * rounds;
  const jevCalls = 2 * claims.length + 3 * rounds;
  const codexIn = 13_000 * calls;
  const codexCached = 8_400 * calls;
  const codexOut = 90 * calls;
  const codexUsd = ((codexIn - codexCached) * 4 + codexCached * 0.4 + codexOut * 20) / 1e6;
  const jevIn = 450 * jevCalls;
  const backends = [
    {
      backend: 'claude', models: ['claude-sonnet-5'], calls, inputTokens: 11_300 * calls, cachedInputTokens: 3_400 * calls,
      cacheWriteTokens: 7_900 * calls, outputTokens: 60 * calls, reasoningTokens: 0, usd: 0.031 * calls, unpricedCalls: 0,
      rate: 'as reported by Claude Code (total_cost_usd per call)', rateSource: 'Claude Code CLI', assumed: false,
      note: 'API-equivalent as reported by Claude Code; not your bill if you use a subscription.',
    },
    {
      backend: 'codex', models: ['gpt-5.6-sol'], calls, inputTokens: codexIn, cachedInputTokens: codexCached,
      cacheWriteTokens: 0, outputTokens: codexOut, reasoningTokens: 30 * calls, usd: codexUsd, unpricedCalls: 0,
      rate: '$4.00/1M input · $0.40/1M cached input · $20.00/1M output · cache writes 1.25× input · prompts over 272K tokens 2× input, 1.5× output',
      rateSource: 'developers.openai.com/api/docs/models/gpt-5.6-sol', rateDate: '2026-09-27', assumed: false,
      note: 'Reasoning tokens are billed as output. The input price is promotional through at least 2026-11-21.',
    },
    {
      backend: 'jev', models: ['jev-1.13.0'], calls: jevCalls, inputTokens: jevIn, cachedInputTokens: 0, cacheWriteTokens: 0,
      outputTokens: 12 * jevCalls, reasoningTokens: 0, usd: (jevIn * 0.042) / 1e6, unpricedCalls: 0,
      rate: '$0.042/1M input · output free', rateSource: 'third-party: OpenRouter typesafe/jev-1.13, MindStudio',
      rateDate: '2026-09-27', assumed: true, note: 'TypeSafe publishes no pricing; this is a third-party listing.',
    },
  ].filter((b) => b.calls > 0);
  const costUsd = backends.reduce((a, b) => a + b.usd, 0);
  const perRoundUsd = rounds >= 3 ? costUsd / rounds : undefined;
  return {
    costUsd,
    projectedUsd: perRoundUsd === undefined ? undefined : costUsd + queued * perRoundUsd,
    cost: { backends, rounds, queued, perRoundUsd },
  };
}

/**
 * Model A: this question is ambiguous, and is framed READINGS with two
 * readings (SPEC §3 "Framing (model A)") — the root goes FRAMED with no
 * arguments of its own; each reading is a root-like claim (`positionOf`
 * the question, depth 0, proposer "reading") with its own small subtree,
 * carrying over the finished example's old direct-on-root arguments.
 */
function seedFinished(m: MockSource): void {
  const q = 'w0';
  m.claim(q, q, 'Should our team adopt a four-day work week?', 0, 'question', { status: 'QUEUED' });
  m.claim('w0p1', q, 'A four-day week with the same total pay and fewer hours worked.', 0, 'reading', {
    positionOf: q, status: 'SATURATED', credence: 0.65, plausibility: 0.6, rounds: 1, proSaturation: 0.78, conSaturation: 0.7,
  });
  m.claim('w0p2', q, 'A four-day week that compresses the same weekly hours into fewer, longer days.', 0, 'reading', {
    positionOf: q, status: 'SATURATED', credence: 0.4, plausibility: 0.35, rounds: 1, proSaturation: 0.66, conSaturation: 0.6,
  });
  m.arg('w1', q, 'w0p1', 'SUPPORT', 'Trials report stable output with fewer hours worked.', 'claude', { status: 'SATURATED', credence: 0.71, plausibility: 0.75, relevance: 0.83, rounds: 1 });
  m.arg('w2', q, 'w0p1', 'ATTACK', 'Customer support coverage would drop on the fifth day.', 'codex', { status: 'PRUNED', credence: 0.42, plausibility: 0.5, relevance: 0.31 });
  m.edge('w1', 'w0p1', 0.72);
  m.edge('w2', 'w0p1', 0.48);
  m.arg('w3', q, 'w1', 'ATTACK', 'Trial participants self-selected and are not representative.', 'codex', { status: 'DEPTH_LIMIT', credence: 0.55, plausibility: 0.5 });
  m.edge('w3', 'w1', 0.61);
  // The link w1 → w0p1 explored as a claim: one reason it holds, one that it fails.
  const link = m.link('w1', 'w0p1');
  m.set(link, { status: 'ROUND_LIMIT', rounds: 1, credence: 0.64, triage: { ADD: 2 } });
  m.arg('w4', q, link, 'SUPPORT', 'The trials measured output with the same metrics used before the change.', 'claude', { status: 'DEPTH_LIMIT', credence: 0.7, plausibility: 0.75 });
  m.arg('w5', q, link, 'ATTACK', 'Output in the trials was measured over six months, too short to show attrition effects.', 'codex', { status: 'DEPTH_LIMIT', credence: 0.62, plausibility: 0.75 });
  m.edge('w4', link, 0.7);
  m.edge('w5', link, 0.66);
  m.set(m.link('w2', 'w0p1'), { status: 'PRUNED' });
  m.set(m.link('w3', 'w1'), { status: 'DEPTH_LIMIT' });
  // The second reading gets a small subtree of its own.
  m.arg('w6', q, 'w0p2', 'ATTACK', 'Compressed ten-hour days increase fatigue-related errors.', 'codex', { status: 'SATURATED', credence: 0.35, plausibility: 0.4, relevance: 0.6, rounds: 1 });
  m.edge('w6', 'w0p2', 0.58);
  m.frame(q, 'READINGS', ['w0p1', 'w0p2'], 'four-day week');
}

/**
 * Model A: an open question framed POSITIONS — three possible answers with
 * no privileged ordering claim, whose shares (softmax over their consensus)
 * the UI shows as a distribution instead of a single verdict.
 */
function seedFramedPositions(m: MockSource): void {
  const q = 'i0';
  m.claim(q, q, 'How many vehicles a day will the new river bridge carry at peak?', 0, 'question', { status: 'QUEUED' });
  // POSITIONS items are proposer "position" (READINGS items are "reading"; Claim.READING/Claim.POSITION).
  m.claim('i0p1', q, 'Under 5,000 vehicles a day.', 0, 'position', {
    positionOf: q, status: 'SATURATED', credence: 0.7, plausibility: 0.72, rounds: 1, proSaturation: 0.7, conSaturation: 0.6,
  });
  m.claim('i0p2', q, 'Between 5,000 and 15,000 vehicles a day.', 0, 'position', {
    positionOf: q, status: 'SATURATED', credence: 0.42, plausibility: 0.45, rounds: 1, proSaturation: 0.55, conSaturation: 0.5,
  });
  m.claim('i0p3', q, 'Over 15,000 vehicles a day.', 0, 'position', {
    positionOf: q, status: 'QUEUED', credence: 0.12, plausibility: 0.15,
  });
  m.arg('i1', q, 'i0p1', 'SUPPORT', 'Traffic on the current ferry route has stayed under 4,000 a day for a decade.', 'claude', { status: 'SATURATED', credence: 0.68, plausibility: 0.7, relevance: 0.75, rounds: 1 });
  m.edge('i1', 'i0p1', 0.7);
  m.arg('i2', q, 'i0p2', 'SUPPORT', 'Comparable bridges elsewhere settle in this band within two years of opening.', 'codex', { status: 'SATURATED', credence: 0.6, plausibility: 0.6, relevance: 0.62, rounds: 1 });
  m.edge('i2', 'i0p2', 0.62);
  m.frame(q, 'POSITIONS', ['i0p1', 'i0p2', 'i0p3']);
}

/** The scripted claims, by ref: the built-in example question's, and a topic-neutral set for any typed question. */
type ScriptText = Record<'c1' | 'c2' | 'c3' | 'c4' | 'c5' | 'c1a' | 'c1b' | 'c1bu' | 'c1h' | 'c1f' | 'c3a' | 'c3a1' | 'c3a2' | 'c4a' | 'c4b', string>;

/** `?mock`'s own example question, the only one its specific claims fit. */
export const EXAMPLE_QUESTION = 'Should the Northfield library open on Sundays?';

const EXAMPLE: ScriptText = {
  c1: 'Weekend visitor counts at the Northfield library are the highest of the week.',
  c2: 'Sunday opening would require paying the library staff a weekend wage premium.',
  c3: 'Students without quiet space at home need somewhere to study on Sundays.',
  c4: 'The volunteers who run the Northfield reading groups are unavailable on Sundays.',
  c5: 'Libraries in neighbouring towns that open on Sundays report steady Sunday attendance.',
  c1a: 'Most weekend visitors could come on Saturday instead.',
  c1b: 'The Saturday reading room at the Northfield library is full by noon on most weekends.',
  c1bu: 'The Saturday reading room lost half its seats to a renovation in the same months.',
  c1h: 'Visitors who come on weekends are mostly unable to visit on weekdays.',
  c1f: "Weekend visitor counts at the Northfield library are driven by Saturday children's events.",
  c3a: 'The town hall study centre already opens on Sundays.',
  c3a1: 'The town hall study centre has only twenty seats.',
  c3a2: 'The town hall study centre is rarely full on Sundays.',
  c4a: 'Paid staff could run the reading groups on Sundays.',
  c4b: 'The library budget does not cover paid reading-group staff.',
};

/** For a typed question: claims that read sensibly whatever was asked (and name no topic). */
export const GENERIC: ScriptText = {
  c1: 'The people most affected would clearly benefit.',
  c2: 'The cost is high compared with the likely gain.',
  c3: 'Similar changes elsewhere have mostly worked out well.',
  c4: 'It depends on people and resources that may not be available.',
  c5: 'It would be easy to reverse if it does not work out.',
  c1a: 'The same benefit could be had in a cheaper way.',
  c1b: 'Demand for that benefit already exceeds what is on offer.',
  c1bu: 'That demand was measured during an unusual period.',
  c1h: 'The benefit falls on exactly the people the question is about.',
  c1f: 'The benefit comes from a factor the change does not touch.',
  c3a: 'The places where it worked differ in important ways.',
  c3a1: 'Those differences are small in practice.',
  c3a2: 'Those differences were never measured carefully.',
  c4a: 'Others could step in to provide what is missing.',
  c4b: 'There is no budget to pay others to step in.',
};

function script(
  source: MockSource,
  sequence: number,
  asked?: string,
): { root: string; steps: Array<() => void> } {
  // A typed question gets the topic-neutral claims; only the built-in example gets its own.
  const T = asked === undefined ? EXAMPLE : GENERIC;
  const text = asked ?? EXAMPLE_QUESTION;
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
    /** The link from argument [arg] to [parent]: a ref to argue about, or to set. */
    link: (arg: string, parent: string) => source.link(scoped(arg), scoped(parent)),
  };
  /** Argue about the link [arg] → [parent] (SPEC §3 "Links as claims"). */
  const onLink = (ref: string, arg: string, parent: string, polarity: Polarity, claimText: string, proposer: string, patch?: Partial<NodeDto>) =>
    source.arg(scoped(ref), root, m.link(arg, parent), polarity, claimText, proposer, patch);
  const setLink = (arg: string, parent: string, patch: Partial<NodeDto>) => source.set(m.link(arg, parent), patch);
  const edgeOnLink = (ref: string, arg: string, parent: string, strength: number) => source.edge(scoped(ref), m.link(arg, parent), strength);
  const q = 'q0';
  const steps = [
    () => m.claim(q, q, text, 0, 'question'),
    () => m.set(q, { status: 'JUDGING' }),
    () => m.set(q, { status: 'EXPLORING', plausibility: 0.5 }),
    () => m.arg('c1', q, q, 'SUPPORT', T.c1, 'claude'),
    () => m.arg('c2', q, q, 'ATTACK', T.c2, 'codex'),
    () => m.arg('c3', q, q, 'SUPPORT', T.c3, 'codex'),
    () => {
      m.arg('c4', q, q, 'ATTACK', T.c4, 'claude');
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
    () => m.arg('c5', q, q, 'SUPPORT', T.c5, 'claude'),
    () => {
      m.edge('c5', q, 0.66);
      m.set(q, { status: 'SATURATED', rounds: 2, proSaturation: 0.83, conSaturation: 0.76, credence: 0.6 });
      m.set('c1', { status: 'JUDGING' });
    },
    () => m.set('c1', { status: 'EXPLORING', relevance: 0.81 }),
    () => m.arg('c1a', q, 'c1', 'ATTACK', T.c1a, 'codex'),
    () => {
      m.arg('c1b', q, 'c1', 'SUPPORT', T.c1b, 'claude');
      m.edge('c1a', 'c1', 0.58);
      m.set('c2', { status: 'JUDGING' });
    },
    () => {
      m.edge('c1b', 'c1', 0.8);
      m.set('c1', { credence: 0.69 });
      // EXP-03 UNDERCUT, re-targeted by triage: it attacks the link c1b → c1.
      onLink('c1bu', 'c1b', 'c1', 'ATTACK', T.c1bu, 'codex', {
        status: 'DEPTH_LIMIT', plausibility: 0.75, credence: 0.72,
      });
      m.set('c2', { status: 'PRUNED', relevance: 0.34, plausibility: 0.5, credence: 0.47 });
      // The link c1 → q is wide open (strength 0.78, contribution high): queued to be explored.
      setLink('c2', q, { status: 'PRUNED' });
    },
    () => {
      edgeOnLink('c1bu', 'c1b', 'c1', 0.66);
      setLink('c1b', 'c1', { credence: 0.62 });
      setLink('c1', q, { status: 'JUDGING' });
    },
    () => setLink('c1', q, { status: 'EXPLORING' }),
    () => {
      onLink('c1h', 'c1', q, 'SUPPORT', T.c1h, 'claude');
      onLink('c1f', 'c1', q, 'ATTACK', T.c1f, 'codex');
    },
    () => {
      edgeOnLink('c1h', 'c1', q, 0.7);
      edgeOnLink('c1f', 'c1', q, 0.72);
      setLink('c1', q, { credence: 0.71, rounds: 1, triage: { ADD: 2, DUPLICATE: 1 }, duplicatesDropped: 1 });
    },
    () => m.set('c3', { status: 'JUDGING' }),
    () => m.set('c3', { status: 'EXPLORING', relevance: 0.66, plausibility: 0.75 }),
    () => {
      m.arg('c3a', q, 'c3', 'ATTACK', T.c3a, 'codex');
      m.set('c1', { status: 'SATURATED', rounds: 1, proSaturation: 0.77, conSaturation: 0.72 });
    },
    () => {
      m.edge('c3a', 'c3', 0.64);
      m.set('c3', { error: 'codex: timed out after 120 s', credence: 0.58 });
      m.set(q, { credence: 0.57 });
    },
    () => m.set('c3a', { status: 'JUDGING' }),
    () => m.set('c3a', { status: 'EXPLORING', relevance: 0.59, plausibility: 0.5 }),
    () => m.arg('c3a1', q, 'c3a', 'ATTACK', T.c3a1, 'claude', { status: 'DEPTH_LIMIT' }),
    () => {
      m.edge('c3a1', 'c3a', 0.52);
      m.set('c3a', { status: 'SATURATED', rounds: 1, proSaturation: 0.71, conSaturation: 0.7, credence: 0.49 });
      m.set('c3', { status: 'ROUND_LIMIT', rounds: 3, proSaturation: 0.55, conSaturation: 0.62, duplicatesDropped: 2 });
      m.set('c4', { status: 'JUDGING' });
    },
    () => m.set('c4', { status: 'EXPLORING', relevance: 0.72, plausibility: 0.75, credence: 0.7 }),
    () => {
      m.arg('c4a', q, 'c4', 'ATTACK', T.c4a, 'codex');
      m.set('c1a', { status: 'JUDGING' });
    },
    () => {
      m.arg('c3a2', q, 'c3a', 'SUPPORT', T.c3a2, 'codex', { status: 'QUEUED' });
      m.arg('c4b', q, 'c4', 'SUPPORT', T.c4b, 'codex', { status: 'FAILED', error: 'claude: exit 1; codex: timed out after 120 s' });
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
      setLink('c1', q, { status: 'ROUND_LIMIT', proSaturation: 0.6, conSaturation: 0.55 });
      m.set('c1h', { status: 'DEPTH_LIMIT', plausibility: 0.75, credence: 0.75 });
      m.set('c1f', { status: 'DEPTH_LIMIT', plausibility: 0.5, credence: 0.5 });
      setLink('c1b', 'c1', { status: 'DEPTH_LIMIT' });
      setLink('c3', q, { status: 'PRUNED' });
      setLink('c4', q, { status: 'PRUNED' });
      setLink('c5', q, { status: 'PRUNED' });
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
