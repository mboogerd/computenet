import { expect, it } from 'vitest';
import type {
  BackendCostDto, CostDto, FramingDto, GraphDto, NodeDto, Override, PositionDto, QuestionDto, Status,
} from '../src/api/types';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2
  ? (<T>() => T extends B ? 1 : 2) extends <T>() => T extends A ? 1 : 2
    ? true
    : false
  : false;

type ExpectedGraphDto = { questions: QuestionDto[]; nodes: NodeDto[]; consensusMembers?: string[] };
type ExpectedQuestionDto = {
  root: string;
  text: string;
  claims: number;
  active: boolean;
  yieldRounds?: number;
  yieldRecent?: number;
  yieldEarlier?: number;
  stoppedBy?: 'human' | 'budget' | 'voi';
  paused?: boolean;
  costUsd?: number;
  projectedUsd?: number;
  cost?: CostDto;
  cruxes?: string[];
  firstImpression?: number;
  neutralCredence?: number;
  verdictsDisagree?: boolean;
  framing?: FramingDto;
};
type ExpectedFramingDto = { mode: 'READINGS' | 'POSITIONS'; term?: string; positions: PositionDto[] };
type ExpectedPositionDto = {
  ref: string;
  text: string;
  credence: number;
  firstImpression?: number;
  neutralCredence?: number;
  verdictsDisagree?: boolean;
  share?: number;
};
type ExpectedCostDto = { backends: BackendCostDto[]; rounds: number; queued: number; perRoundUsd?: number };
type ExpectedBackendCostDto = {
  backend: string;
  models: string[];
  calls: number;
  inputTokens: number;
  cachedInputTokens: number;
  cacheWriteTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  usd?: number;
  unpricedCalls: number;
  rate: string;
  rateSource: string;
  rateDate?: string;
  assumed: boolean;
  note?: string;
};
type ExpectedStatus =
  | 'QUEUED' | 'JUDGING' | 'EXPLORING' | 'SATURATED' | 'ROUND_LIMIT'
  | 'PRUNED' | 'DEPTH_LIMIT' | 'BUDGET' | 'DIMINISHING' | 'STOPPED' | 'FAILED' | 'FRAMED';
type ExpectedOverride = 'AUTO' | 'EXPAND' | 'STOP';
type ExpectedNodeDto = {
  ref: string;
  kind: string;
  credence: number;
  root: string;
  credences?: Record<string, number>;
  consensus?: number;
  spreadLow?: number;
  spreadHigh?: number;
  argumentsFirstCredences?: Record<string, number>;
  argumentsFirstConsensus?: number;
  text?: string;
  depth?: number;
  status?: Status;
  override?: Override;
  activity?: 'exploring' | 'judging' | 'assessing';
  proposer?: string;
  alsoProposedBy?: string[];
  merged?: boolean;
  evidence?: string[];
  undercuts?: string;
  onLink?: string;
  positionOf?: string;
  plausibility?: number;
  relevance?: number;
  quality?: number;
  contribution?: number;
  sensitivity?: number;
  reach?: number;
  proSaturation?: number;
  conSaturation?: number;
  rounds?: number;
  duplicatesDropped?: number;
  triage?: Record<string, number>;
  error?: string;
  polarity?: string;
  source?: string;
  target?: string;
  strength?: number;
};

const graphMatches: Equal<GraphDto, ExpectedGraphDto> = true;
const questionMatches: Equal<QuestionDto, ExpectedQuestionDto> = true;
const statusMatches: Equal<Status, ExpectedStatus> = true;
const overrideMatches: Equal<Override, ExpectedOverride> = true;
const nodeMatches: Equal<NodeDto, ExpectedNodeDto> = true;
const costMatches: Equal<CostDto, ExpectedCostDto> = true;
const backendCostMatches: Equal<BackendCostDto, ExpectedBackendCostDto> = true;
const framingMatches: Equal<FramingDto, ExpectedFramingDto> = true;
const positionMatches: Equal<PositionDto, ExpectedPositionDto> = true;

it('mirrors the Dto.kt wire contract field for field', () => {
  expect([
    graphMatches, questionMatches, statusMatches, overrideMatches, nodeMatches, costMatches, backendCostMatches,
    framingMatches, positionMatches,
  ]).toEqual([true, true, true, true, true, true, true, true, true]);
});
