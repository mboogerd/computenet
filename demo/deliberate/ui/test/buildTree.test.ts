import { describe, expect, it } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { buildForest, buildTree, countClaims, type TreeNode } from '../src/tree/buildTree';
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

  it('places link arguments under the link, never under a claim', () => {
    const g: GraphDto = {
      questions: [],
      nodes: [
        { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'a', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'a-q', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'q', text: '“a” is a reason for “q”' },
        { ref: 'x', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'x-a', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'x', target: 'a' },
        { ref: 'u', kind: 'CLAIM', credence: 0.5, root: 'q', undercuts: 'a-q', onLink: 'a-q' },
        { ref: 'u-aq', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'u', target: 'a-q' },
        { ref: 'h', kind: 'CLAIM', credence: 0.5, root: 'q', onLink: 'a-q' },
        { ref: 'h-aq', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'h', target: 'a-q' },
        { ref: 'v', kind: 'CLAIM', credence: 0.5, root: 'q' },
        { ref: 'v-u', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'SUPPORT', source: 'v', target: 'u' },
        { ref: 'w', kind: 'CLAIM', credence: 0.5, root: 'q', onLink: 'h-aq' },
        { ref: 'w-haq', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'w', target: 'h-aq' },
      ],
    };
    const tree = buildTree(g, 'q')!;
    // the root holds one argument, and so does `a`: the link's arguments belong to neither
    expect(tree.children.map((c) => c.node.claim.ref)).toEqual(['a']);
    const arg = tree.children[0];
    expect(arg.node.children.map((c) => c.node.claim.ref)).toEqual(['x']);
    // the link a → q: why it holds first, then the undercutter
    expect(arg.linkArgs.map((c) => [c.node.claim.ref, c.edge.polarity, c.onLink])).toEqual([
      ['h', 'SUPPORT', true],
      ['u', 'ATTACK', true],
    ]);
    // an argument about a link is explored like a claim, and has a link of its own
    expect(arg.linkArgs[1].node.children.map((c) => c.node.claim.ref)).toEqual(['v']);
    expect(arg.linkArgs[0].linkArgs.map((c) => c.node.claim.ref)).toEqual(['w']);
    expect(arg.node.children[0].linkArgs).toEqual([]);
    expect(countClaims(tree)).toBe(7);
  });
});

describe('buildForest', () => {
  const framed: GraphDto = {
    questions: [
      {
        root: 'q',
        text: 'Do fish sleep?',
        claims: 5,
        active: false,
        framing: {
          mode: 'READINGS',
          term: 'sleep',
          positions: [
            { ref: 'p1', text: 'Do fish enter a rest state?', credence: 0.6 },
            { ref: 'p2', text: 'Do fish show REM-like brain activity?', credence: 0.2 },
          ],
        },
      },
    ],
    nodes: [
      { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'Do fish sleep?', depth: 0, status: 'FRAMED' },
      { ref: 'p1', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Do fish enter a rest state?', depth: 0, positionOf: 'q', status: 'SATURATED' },
      { ref: 'p2', kind: 'CLAIM', credence: 0.2, root: 'q', text: 'Do fish show REM-like brain activity?', depth: 0, positionOf: 'q', status: 'QUEUED' },
      { ref: 'a1', kind: 'CLAIM', credence: 0.7, root: 'q', text: 'Fish become unresponsive at night.', depth: 1, status: 'SATURATED' },
      { ref: 'a1-p1', kind: 'EDGE', credence: 0.7, root: 'q', polarity: 'SUPPORT', source: 'a1', target: 'p1', strength: 0.7 },
    ],
  };

  it('returns one subtree per position, in framing order, each equal to buildTree at its ref', () => {
    const forest = buildForest(framed, 'q')!;
    expect(forest.claim.ref).toBe('q');
    expect(forest.children).toEqual([]);
    expect(forest.positions?.map((t) => t.claim.ref)).toEqual(['p1', 'p2']);
    expect(forest.positions?.[0]).toEqual(buildTree(framed, 'p1'));
    expect(forest.positions?.[1]).toEqual(buildTree(framed, 'p2'));
    // p1 has an argument; p2 (no arguments yet) is a leaf.
    expect(forest.positions?.[0].children.map((a) => a.node.claim.ref)).toEqual(['a1']);
    expect(forest.positions?.[1].children).toEqual([]);
  });

  it('leaves out a position whose framing order lists it but whose claim has not arrived', () => {
    const torn: GraphDto = {
      ...framed,
      nodes: framed.nodes.filter((n) => n.ref !== 'p2'),
    };
    const forest = buildForest(torn, 'q')!;
    expect(forest.positions?.map((t) => t.claim.ref)).toEqual(['p1']);
  });

  it('returns the plain buildTree result, with no positions field, for an unframed question', () => {
    const forest = buildForest(flatGraph, 'c0');
    expect(forest).toEqual(buildTree(flatGraph, 'c0'));
    expect(forest?.positions).toBeUndefined();
  });

  it('returns undefined when the root is absent, same as buildTree', () => {
    expect(buildForest(framed, 'nope')).toBeUndefined();
  });
});
