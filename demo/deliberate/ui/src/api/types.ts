// Mirrors demo/deliberate/src/main/kotlin/civictech/deliberate/Dto.kt field
// for field; change both or neither. The backend encodes with
// `explicitNulls = false`, so every nullable Kotlin field is optional here.

export interface GraphDto {
  questions: QuestionDto[];
  nodes: NodeDto[];
  /** SPEC §2: the layers averaged into every node's `consensus` (`--consensus`); always sent, see NodeDto. */
  consensusMembers?: string[];
}

export interface QuestionDto {
  root: string;
  text: string;
  /** Claims in this question's tree. */
  claims: number;
  /** true while any claim in the tree is QUEUED, JUDGING or EXPLORING. */
  active: boolean;
  // EXP-10. yieldRounds has a Kotlin default and is always sent; optional so
  // hand-written fixtures without it stay valid.
  /** Non-root rounds whose yield was recorded (rounds that asked for at least one proposal). */
  yieldRounds?: number;
  /** Mean yield of the last `yieldWindow` recorded rounds (all of them while there are fewer). */
  yieldRecent?: number;
  /** Mean yield of every recorded round before those; absent until there are more than `yieldWindow`. */
  yieldEarlier?: number;
  /** Why the tree stopped growing early: the claim budget, or diminishing returns that halted queued work. */
  stoppedBy?: StoppedBy;
  // SPEC §12. costUsd and cost have Kotlin defaults and are always sent;
  // optional so hand-written fixtures without them stay valid.
  /** USD spent on this question so far — the sum of every priced call (unpriced calls are left out). */
  costUsd?: number;
  /** costUsd + claims still to explore × mean cost per completed round; absent until 3 rounds completed. */
  projectedUsd?: number;
  /** What the figure is made of, per backend. */
  cost?: CostDto;
}

/** SPEC §12: the details behind a question's cost figure. */
export interface CostDto {
  /** One entry per backend that made at least one call for the question, in claude, codex, jev order. */
  backends: BackendCostDto[];
  /** Rounds completed in the question (over all its claims). */
  rounds: number;
  /** Claims still QUEUED, JUDGING or EXPLORING — the projection assumes one more round each. */
  queued: number;
  /** Mean USD per completed round; absent until 3 rounds completed. */
  perRoundUsd?: number;
}

export interface BackendCostDto {
  /** "claude" | "codex" | "jev". */
  backend: string;
  models: string[];
  calls: number;
  /** Every input token, cached reads and cache writes included. */
  inputTokens: number;
  cachedInputTokens: number;
  cacheWriteTokens: number;
  /** Every output token, reasoning included. */
  outputTokens: number;
  reasoningTokens: number;
  /** USD of the priced calls; absent when none could be priced. */
  usd?: number;
  /** Calls left out of every total because they could not be priced. */
  unpricedCalls: number;
  /** The price applied, in words. */
  rate: string;
  rateSource: string;
  /** When the price was looked up (ISO date); absent for a cost the backend reported itself. */
  rateDate?: string;
  /** true when the price is not a published price of the provider. */
  assumed: boolean;
  /** A caveat to show with it (e.g. Claude's subscription note). */
  note?: string;
}

export type StoppedBy = 'budget' | 'diminishing';

export type Status =
  | 'QUEUED'
  | 'JUDGING'
  | 'EXPLORING'
  | 'SATURATED'
  | 'ROUND_LIMIT'
  | 'PRUNED'
  | 'DEPTH_LIMIT'
  | 'BUDGET'
  | 'DIMINISHING'
  | 'STOPPED'
  | 'FAILED';

export type Override = 'AUTO' | 'EXPAND' | 'STOP';

export type Polarity = 'SUPPORT' | 'ATTACK';

export interface NodeDto {
  ref: string;
  kind: string;
  /** Propagated agora credence in the primary semantics layer (`--semantics`), [0,1]. */
  credence: number;
  /** The question tree this node belongs to (its root claim ref). */
  root: string;
  // The four fields below have Kotlin defaults and the backend always sends
  // them (encodeDefaults); they are optional here only so hand-written
  // fixtures without them stay valid. Read them through `shown()`/`spreadOf()`.
  /** SPEC §2 "Credence layers and consensus": propagated credence per semantics layer id. */
  credences?: Record<string, number>;
  /** Geometric-odds mean of the consensus member layers' credences (the headline number). */
  consensus?: number;
  /** Lowest and highest credence over all layers. */
  spreadLow?: number;
  spreadHigh?: number;
  // --- CLAIM only ---
  text?: string;
  depth?: number;
  status?: Status;
  override?: Override;
  /** "question" for a root, else the proposer id ("claude" | "codex"). */
  proposer?: string;
  /** EXP-03: other proposers that proposed the same point (DUPLICATE) or the replaced wording (REPLACE). */
  alsoProposedBy?: string[];
  /** EXP-03 MERGE: true when this argument's text was rewritten together with an overlapping one. */
  merged?: boolean;
  /**
   * EXP-03 UNDERCUT: the ref of the EDGE this claim attacks — it denies that
   * that edge's source bears on its target. Its own edge's `target` is that ref.
   */
  undercuts?: string;
  /** Jev plausibility stance (CRED-01), once judged. */
  plausibility?: number;
  /** Jev relevance probability (EXP-05), judged when the argument was attached. */
  relevance?: number;
  /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent (construction only). */
  quality?: number;
  /** SPEC §3 "Exploration order" priority: reach × relevance × quality (root = 1). */
  contribution?: number;
  /** EXP-05 reach: product of Jev relation strengths along the path from the root (root = 1). */
  reach?: number;
  /** Last Jev saturation probabilities per side (EXP-04). */
  proSaturation?: number;
  conSaturation?: number;
  rounds?: number;
  duplicatesDropped?: number;
  /** EXP-03: triage actions taken on this claim's proposals, by action name (ADD, DUPLICATE, …). */
  triage?: Record<string, number>;
  error?: string;
  // --- EDGE only (child → parent; the parent is an EDGE for an undercutter) ---
  polarity?: string;
  source?: string;
  target?: string;
  /** Jev relation strength stance (CRED-02), once judged. */
  strength?: number;
}

/** POST /question response. */
export interface QuestionCreated {
  root: string;
}

/** Statuses during which the explorer is still working on a claim. */
export const ACTIVE_STATUSES: ReadonlySet<Status> = new Set(['QUEUED', 'JUDGING', 'EXPLORING']);

/** The layers a node's consensus averages over by default (`--consensus`). */
export const DEFAULT_CONSENSUS: readonly string[] = ['wlo', 'jnb', 'woe'];
