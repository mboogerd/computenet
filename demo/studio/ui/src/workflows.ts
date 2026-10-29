import type { Workflow } from './types';

export function parseWorkflowHash(hash: string): string | null {
  return /^#\/w\/(default|[a-z0-9]{8})$/.exec(hash)?.[1] ?? null;
}
export const workflowHash = (id: string) => `#/w/${id}`;
export function resolveWorkflow(hash: string, workflows: Workflow[]): string | null {
  const id = parseWorkflowHash(hash);
  return workflows.find(workflow => workflow.id === id)?.id ?? workflows[0]?.id ?? null;
}

export function validateWorkflowName(value: string): string | null {
  const length = value.trim().length;
  return length >= 1 && length <= 80 ? null : 'Workflow name must be 1–80 characters.';
}

export const cellCount = (count: number): string => `${count} ${count === 1 ? 'cell' : 'cells'}`;
