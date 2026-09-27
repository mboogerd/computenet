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
  /** Jev plausibility stance (CRED-01), once judged. */
  plausibility?: number;
  /** Last Jev relevance probability (EXP-05), when judged. */
  relevance?: number;
  /** Last Jev saturation probabilities per side (EXP-04). */
  proSaturation?: number;
  conSaturation?: number;
  rounds?: number;
  duplicatesDropped?: number;
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
