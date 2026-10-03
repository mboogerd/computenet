// Mirrors demo/deliberate/src/main/kotlin/civictech/deliberate/Dto.kt field
// for field; change both or neither. The backend encodes with
// `explicitNulls = false`, so every nullable Kotlin field is optional here.

export interface GraphDto {
  questions: QuestionDto[];
  nodes: NodeDto[];
  /** SPEC §2: the fixed layers averaged into every node's `consensus`; always sent, see NodeDto. */
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
  /**
   * Why the tree stopped growing early: the human stopped the question (STOP on
   * its root, CTL-03), the claim budget (the hard cap), or — no work left — model
   * C's value-of-information stop, when it left a node DIMINISHING.
   */
  stoppedBy?: StoppedBy;
  /**
   * CTL-05: the question is paused — no new round starts until it is resumed.
   * Has a Kotlin default and is always sent; optional so older fixtures stay valid.
   */
  paused?: boolean;
  // SPEC §12. costUsd and cost have Kotlin defaults and are always sent;
  // optional so hand-written fixtures without them stay valid.
  /** USD spent on this question so far — the sum of every priced call (unpriced calls are left out). */
  costUsd?: number;
  /** costUsd + claims still to explore × mean cost per completed round; absent until 3 rounds completed. */
  projectedUsd?: number;
  /** What the figure is made of, per backend. */
  cost?: CostDto;
  /**
   * Model C, "what would change the answer": up to 3 node refs (claims below the
   * root, links as EDGE refs) with the highest |sensitivity| × 4·p·(1 − p), best
   * first. Has a Kotlin default and is always sent; optional so older fixtures stay valid.
   */
  cruxes?: string[];
  /**
   * Model D: Jev's plausibility of the question itself, before any argument —
   * its "first impression", which stays the root's prior. Absent until judged.
   */
  firstImpression?: number;
  /**
   * Model D, "what the arguments say": the root's headline credence with the same
   * arguments weighed from a neutral prior instead. Absent until the root emitted.
   */
  neutralCredence?: number;
  /**
   * Model D: the root's credence and `neutralCredence` fall on different sides of ½.
   * Has a Kotlin default and is always sent; optional so older fixtures stay valid.
   */
  verdictsDisagree?: boolean;
  /**
   * Model A: how the question was framed before its first round; absent when it
   * was explored as asked. When set, the question-level model D fields are absent
   * and each position carries its own.
   */
  framing?: FramingDto;
}

/** Model A: a framed question's readings (or positions), in the framer's order. */
export interface FramingDto {
  mode: 'READINGS' | 'POSITIONS';
  /** READINGS: the ambiguous term the readings resolve. */
  term?: string;
  positions: PositionDto[];
}

/** Model A: one reading or position — a claim explored as a root of its own. */
export interface PositionDto {
  /** Its CLAIM node's ref. */
  ref: string;
  text: string;
  /** Its headline credence (equal to its NodeDto's). */
  credence: number;
  /** Model D: Jev's plausibility of it, judged against the original question. */
  firstImpression?: number;
  /** Model D: its headline credence from a neutral prior. */
  neutralCredence?: number;
  /** Model D: its credence and neutralCredence fall on different sides of ½. */
  verdictsDisagree?: boolean;
  /** POSITIONS: its share of the consensus shares (they sum to 1); absent for READINGS. */
  share?: number;
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

export type StoppedBy = 'human' | 'budget' | 'voi';

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
  | 'FAILED'
  | 'FRAMED';

export type Override = 'AUTO' | 'EXPAND' | 'STOP';

export type Activity = 'exploring' | 'judging' | 'assessing';

export type Polarity = 'SUPPORT' | 'ATTACK';

export interface NodeDto {
  ref: string;
  kind: string;
  /** Propagated agora credence in the primary semantics layer (`--semantics`), [0,1]. */
  credence: number;
  /** The question tree this node belongs to (its root claim ref). */
  root: string;
  // The six fields below have Kotlin defaults and the backend always sends
  // them (encodeDefaults); they are optional here only so hand-written
  // fixtures without them stay valid. Read them through `shown()`/`spreadOf()`.
  /** SPEC §2 "Credence layers and consensus": propagated credence per semantics layer id. */
  credences?: Record<string, number>;
  /** Geometric-odds mean of the consensus member layers' credences (the headline number). */
  consensus?: number;
  /** Lowest and highest credence over all layers. */
  spreadLow?: number;
  spreadHigh?: number;
  /** Model D: local arguments-first credence per layer; equal to `credences` without incoming arguments. */
  argumentsFirstCredences?: Record<string, number>;
  /** Geometric-odds consensus of `argumentsFirstCredences`. */
  argumentsFirstConsensus?: number;
  // --- CLAIM, and EDGE as a link (SPEC §3 "Links as claims": text, depth,
  // status, override, reach, contribution, saturation, rounds,
  // duplicatesDropped, triage, error, activity) ---
  /** A claim's text; an edge's link text, "“<source>” is a reason for|against “<target>”". */
  text?: string;
  /** A link's depth is its argument's depth. */
  depth?: number;
  status?: Status;
  override?: Override;
  /**
   * What the node is doing right now, for the activity line: "exploring" (a
   * round in flight), "judging" (its plausibility, before its first round) or
   * "assessing" (its attach-time judgments); absent when idle.
   */
  activity?: Activity;
  // --- CLAIM only ---
  /** "question" for a root, else the proposer id ("claude" | "codex"). */
  proposer?: string;
  /** EXP-03: other proposers that proposed the same point (DUPLICATE) or the replaced wording (REPLACE). */
  alsoProposedBy?: string[];
  /** EXP-03 MERGE: true when this argument's text was rewritten together with an overlapping one. */
  merged?: boolean;
  /**
   * EXP-03 REFINE (model B): texts proposed as specific instances of or
   * evidence for this argument, recorded on it instead of as child claims.
   */
  evidence?: string[];
  /**
   * EXP-03 UNDERCUT: the ref of the EDGE this claim attacks — it denies that
   * that edge's source bears on its target. Its own edge's `target` is that ref.
   */
  undercuts?: string;
  /**
   * SPEC §3 "Links as claims": the ref of the EDGE whose link this claim argues
   * about — for (SUPPORT: why the connection holds) or against (ATTACK, then
   * also `undercuts`). Absent for an argument about a claim.
   */
  onLink?: string;
  /** Model A: on a reading/position, the ref of the framed question root it belongs to. */
  positionOf?: string;
  /** Jev plausibility stance (CRED-01), once judged. */
  plausibility?: number;
  /** Jev relevance probability (EXP-05), judged when the argument was attached. */
  relevance?: number;
  /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent (construction only). */
  quality?: number;
  /**
   * Shown only since model C (the queue follows sensitivity × 4·p·(1 − p)): reach × relevance × quality × 4·p·(1 − p),
   * p its plausibility (root = 1; model B); for a link, its argument's contribution without the 4·p·(1 − p) factor × 4·s·(1 − s), s its strength.
   */
  contribution?: number;
  /**
   * Model C: d headline(root) / d this node's credence — how far the question's
   * answer moves per unit move of this node (root ≈ 1; a link's is its edge's).
   * Absent until the sensitivity cells reached it.
   */
  sensitivity?: number;
  /** EXP-05 reach: product of Jev relation strengths along the path from the root (root = 1; a link: its argument's). */
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

/** The fixed layers a node's consensus averages over. */
export const DEFAULT_CONSENSUS: readonly string[] = ['wlo', 'jnb', 'woe'];
