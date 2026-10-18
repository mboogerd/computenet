export type Kind = 'text' | 'git' | 'exec' | 'program';
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
}
export interface RunDetail extends RunSummary {
  inputs: Record<string, Change>;
  argv: string[];
  cwd: string;
  stdout: string;
  stderr: string;
  error: string | null;
}
export interface NodeState {
  id: string;
  status: 'idle' | 'stale' | 'queued' | 'running' | 'generating' | 'failed';
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
}
export interface Graph { nodes: StudioNode[]; edges: Edge[]; state: Record<string, NodeState> }
export type Frame =
  | ({ type: 'snapshot' } & Graph)
  | { type: 'graph'; nodes: StudioNode[]; edges: Edge[] }
  | { type: 'state'; node: NodeState };
