import { describe, expect, it } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { buildTree, countClaims, type TreeNode } from '../src/tree/buildTree';
import flat from './fixtures/flat.json';
import deep from './fixtures/deep.json';

const flatGraph = flat as GraphDto;
const deepGraph = deep as GraphDto;

const shape = (t: TreeNode): unknown => ({
  ref: t.claim.ref,
  children: t.children.map((a) => ({ via: a.edge.ref, pol: a.edge.polarity, node: shape(a.node) })),
});

describe('buildTree', () => {
  it('orders pro before con, stable by first appearance within each side', () => {
    const tree = buildTree(flatGraph, 'c0')!;
    expect(tree.children.map((a) => a.edge.ref)).toEqual(['e0', 'e2', 'e1']);
    expect(tree.children.map((a) => a.node.claim.ref)).toEqual(['c1', 'c3', 'c2']);
  });

  it('skips an edge whose source claim is missing from the snapshot', () => {
    const tree = buildTree(flatGraph, 'c0')!;
    expect(tree.children.some((a) => a.edge.ref === 'e9')).toBe(false);
    expect(countClaims(tree)).toBe(4);
  });

  it('builds a multi-level tree and ignores other questions', () => {
    expect(shape(buildTree(deepGraph, 'q')!)).toEqual({
      ref: 'q',
      children: [
        {
          via: 'q-a',
          pol: 'SUPPORT',
          node: {
            ref: 'a',
            children: [
              {
                via: 'a-a1',
                pol: 'ATTACK',
                node: { ref: 'a1', children: [{ via: 'a1-a1x', pol: 'SUPPORT', node: { ref: 'a1x', children: [] } }] },
              },
            ],
          },
        },
        {
          via: 'q-b',
          pol: 'ATTACK',
          node: { ref: 'b', children: [{ via: 'b-b1', pol: 'ATTACK', node: { ref: 'b1', children: [] } }] },
        },
      ],
    });
    expect(countClaims(buildTree(deepGraph, 'other')!)).toBe(1);
  });

  it('returns undefined when the root is absent', () => {
    expect(buildTree(flatGraph, 'nope')).toBeUndefined();
    expect(buildTree({ questions: [], nodes: [] }, 'c0')).toBeUndefined();
  });

  it('can root at a subtree', () => {
    expect(countClaims(buildTree(deepGraph, 'a')!)).toBe(3);
  });

  it('terminates on a malformed cyclic snapshot', () => {
    const g: GraphDto = {
      questions: [],
      nodes: [
        { ref: 'p', kind: 'CLAIM', credence: 0.5, root: 'p' },
        { ref: 'r', kind: 'CLAIM', credence: 0.5, root: 'p' },
        { ref: 'pr', kind: 'EDGE', credence: 0.5, root: 'p', polarity: 'SUPPORT', source: 'r', target: 'p' },
        { ref: 'rp', kind: 'EDGE', credence: 0.5, root: 'p', polarity: 'SUPPORT', source: 'p', target: 'r' },
      ],
    };
    expect(countClaims(buildTree(g, 'p')!)).toBe(2);
  });
});
