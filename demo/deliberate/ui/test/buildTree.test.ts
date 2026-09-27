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

  it('does not splice cross-question or incomplete edges into the selected tree', () => {
    const g: GraphDto = {
      questions: [],
      nodes: [
        { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'own', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'foreign', kind: 'CLAIM', credence: 0.5, root: 'other' },
        { ref: 'own-q', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'own', target: 'q' },
        { ref: 'foreign-q', kind: 'EDGE', credence: 0.5, root: 'other', polarity: 'ATTACK', source: 'foreign', target: 'q' },
        { ref: 'pending-q', kind: 'EDGE', credence: 0.5, root: 'q', source: 'foreign', target: 'q' },
      ],
    };
    expect(buildTree(g, 'q')!.children.map((a) => a.node.claim.ref)).toEqual(['own']);
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

  it('attaches an undercutter under the argument whose link it attacks', () => {
    const g: GraphDto = {
      questions: [],
      nodes: [
        { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'a', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'a-q', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'q' },
        { ref: 'x', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'x-a', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'x', target: 'a' },
        { ref: 'u', kind: 'CLAIM', credence: 0.5, root: 'q', undercuts: 'a-q' },
        { ref: 'u-aq', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'u', target: 'a-q' },
        { ref: 'v', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'v-u', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'v', target: 'u' },
      ],
    };
    const tree = buildTree(g, 'q')!;
    // the root holds one argument; the undercutter is not a con of the root
    expect(tree.children.map((c) => c.node.claim.ref)).toEqual(['a']);
    const a = tree.children[0].node;
    expect(a.children.map((c) => [c.node.claim.ref, c.undercut ?? false])).toEqual([
      ['x', false],
      ['u', true],
    ]);
    // an undercutter is explored like a claim: its own arguments hang under it
    expect(a.children[1].node.children.map((c) => c.node.claim.ref)).toEqual(['v']);
    expect(countClaims(tree)).toBe(5);
  });
});
