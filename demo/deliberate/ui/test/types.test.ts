import { expect, it } from 'vitest';
import type { BackendCostDto, CostDto, GraphDto, NodeDto, Override, QuestionDto, Status } from '../src/api/types';

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
  stoppedBy?: 'budget' | 'diminishing';
  costUsd?: number;
  projectedUsd?: number;
  cost?: CostDto;
};
type ExpectedCostDto = { complete?: boolean; backends: BackendCostDto[]; rounds: number; queued: number; perRoundUsd?: number };
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
  | 'PRUNED' | 'DEPTH_LIMIT' | 'BUDGET' | 'DIMINISHING' | 'STOPPED' | 'FAILED';
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
  text?: string;
  depth?: number;
  status?: Status;
  override?: Override;
  proposer?: string;
  alsoProposedBy?: string[];
  merged?: boolean;
  undercuts?: string;
  plausibility?: number;
  relevance?: number;
  quality?: number;
  contribution?: number;
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

it('mirrors the Dto.kt wire contract field for field', () => {
  expect([graphMatches, questionMatches, statusMatches, overrideMatches, nodeMatches, costMatches, backendCostMatches]).toEqual([
    true, true, true, true, true, true, true,
  ]);
});
