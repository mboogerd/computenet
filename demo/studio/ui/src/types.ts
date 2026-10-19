export type Kind = 'text' | 'git' | 'exec' | 'program' | 'beads' | 'judge';
export type Config = Record<string, string | number | boolean | string[]>;
export interface StudioNode {
  id: string;
  kind: Kind;
  title: string;
  x: number;
  y: number;
  config: Config;
  ports: string[];
}
export interface Edge { id: string; from: string; to: string; port: string }
export interface Change { old: string | null; new: string; [key: string]: unknown }
export interface RunSummary {
  runId: string;
  startedAt: string;
  durationMs: number;
  exitCode: number;
  costUsd: number | null;
  outcome?: 'judged' | 'passed' | 'gated' | 'failed' | null;
}
export interface RunDetail extends RunSummary {
  inputs: Record<string, Change>;
  argv: string[];
  cwd: string;
  stdout: string;
  stderr: string;
  error: string | null;
  judgment?: Judgment | null;
}
export interface Judgment { answer: string; probability: number | null; reason: string | null }
export interface JudgeState { last: Judgment; passed: number; gated: number }
export interface PreviewState {
  codeHash: string;
  status: 'idle' | 'running' | 'failed';
  output: Change | null;
  error: string | null;
}
export interface NodeState {
  id: string;
  status: 'idle' | 'stale' | 'queued' | 'running' | 'generating' | 'failed' | 'gated';
  output: Change | null;
  inputs: Record<string, Change>;
  error: string | null;
  runCount: number;
  lastRun: RunSummary | null;
  text?: string;
  ref?: string;
  code?: string | null;
  codeHash?: string | null;
  approvedHash?: string | null;
  preview?: PreviewState | null;
  beads?: { watch: string; set: string } | null;
  judgment?: JudgeState | null;
}
export interface Graph { nodes: StudioNode[]; edges: Edge[]; state: Record<string, NodeState> }
export type Frame =
  | ({ type: 'snapshot' } & Graph)
  | { type: 'graph'; nodes: StudioNode[]; edges: Edge[] }
  | { type: 'state'; node: NodeState };

export interface Workflow {
  id: string;
  name: string;
  createdAt: string;
  nodeCount: number;
  status: 'ok' | 'failed';
  error: string | null;
}
