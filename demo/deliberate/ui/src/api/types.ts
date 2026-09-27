// Mirrors demo/deliberate/src/main/kotlin/civictech/deliberate/Dto.kt field
// for field; change both or neither. The backend encodes with
// `explicitNulls = false`, so every nullable Kotlin field is optional here.

export interface GraphDto {
  questions: QuestionDto[];
  nodes: NodeDto[];
}

export interface QuestionDto {
  root: string;
  text: string;
  /** Claims in this question's tree. */
  claims: number;
  /** true while any claim in the tree is QUEUED, JUDGING or EXPLORING. */
  active: boolean;
}

export type Status =
  | 'QUEUED'
  | 'JUDGING'
  | 'EXPLORING'
  | 'SATURATED'
  | 'ROUND_LIMIT'
  | 'PRUNED'
  | 'DEPTH_LIMIT'
  | 'BUDGET'
  | 'STOPPED'
  | 'FAILED';

export type Override = 'AUTO' | 'EXPAND' | 'STOP';

export type Polarity = 'SUPPORT' | 'ATTACK';

export interface NodeDto {
  ref: string;
  kind: string;
  /** Propagated agora credence, [0,1]. */
  credence: number;
  /** The question tree this node belongs to (its root claim ref). */
  root: string;
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
  /** Jev plausibility stance (CRED-01), once judged. */
  plausibility?: number;
  /** Jev relevance probability (EXP-05), judged when the argument was attached. */
  relevance?: number;
  /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent. */
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
  // --- EDGE only (child → parent) ---
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
