import { describe, expect, it } from 'vitest';
import { cellCount, parseWorkflowHash, resolveWorkflow, workflowHash } from '../src/workflows';
import { nodePath, workflowPath } from '../src/api';
import type { Workflow } from '../src/types';

const first: Workflow = { id: 'abcdefgh', name: 'First', createdAt: '2026-10-19T00:00:00Z', nodeCount: 2, status: 'ok', error: null };
const failed: Workflow = { ...first, id: '12345678', name: 'Failed', status: 'failed', error: 'Bad journal' };

describe('workflow navigation', () => {
  it('round trips generated and legacy ids', () => {
    for (const id of ['abcdefgh', '12345678', 'default']) expect(parseWorkflowHash(workflowHash(id))).toBe(id);
  });
  it.each(['', '#', '#/w/', '#/w/ABCDEFGH', '#/w/abc', '#/w/abcdefgh/nodes', '#/w/../graph', '#/w/%64efault', '#/other/abcdefgh', '#/w/abcdefgh?x'])('rejects malformed hash %s', hash => {
    expect(parseWorkflowHash(hash)).toBeNull();
  });
  it('opens the first workflow for missing invalid or deleted ids and handles an empty registry', () => {
    for (const hash of ['', '#invalid', '#/w/deleted1']) expect(resolveWorkflow(hash, [first, failed])).toBe(first.id);
    expect(resolveWorkflow('#/w/12345678', [first, failed])).toBe(failed.id);
    expect(resolveWorkflow('#/w/abcdefgh', [])).toBeNull();
    expect(resolveWorkflow('#/w/abcdefgh', [failed])).toBe(failed.id);
  });
  it('scopes requests and escapes node path segments', () => {
    expect(workflowPath(first.id)).toBe('/api/workflows/abcdefgh');
    expect(nodePath(first.id, 'node/1')).toBe('/api/workflows/abcdefgh/nodes/node%2F1');
  });
});

describe('workflow cell count', () => {
  it.each([[0, '0 cells'], [1, '1 cell'], [2, '2 cells'], [21, '21 cells']] as const)('formats %i as %s', (count, label) => {
    expect(cellCount(count)).toBe(label);
  });
});
