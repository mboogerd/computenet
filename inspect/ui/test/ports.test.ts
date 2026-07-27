import { describe, expect, it } from 'vitest';
import type { Port } from '../src/api/types';
import type { LayoutNode } from '../src/layout/layered';
import { portAnchors } from '../src/layout/ports';

const node: LayoutNode = { ref: 'a', layer: 0, slot: 0, x: 100, y: 40, w: 200, h: 60 };

describe('portAnchors', () => {
  it('places IN ports on the left edge and OUT ports on the right edge', () => {
    const ports: Port[] = [
      { name: 'inlet', dir: 'IN', contractFqn: 'x' },
      { name: 'outlet', dir: 'OUT', contractFqn: 'x' },
    ];
    const anchors = portAnchors(node, ports);
    expect(anchors.get('inlet')!.x).toBe(node.x);
    expect(anchors.get('outlet')!.x).toBe(node.x + node.w);
  });

  it('spaces multiple ports of the same direction evenly and within the card height', () => {
    const ports: Port[] = [
      { name: 'left', dir: 'IN', contractFqn: 'x' },
      { name: 'right', dir: 'IN', contractFqn: 'x' },
    ];
    const anchors = portAnchors(node, ports);
    const left = anchors.get('left')!.y;
    const right = anchors.get('right')!.y;
    expect(left).toBeLessThan(right);
    expect(left).toBeGreaterThan(node.y);
    expect(right).toBeLessThan(node.y + node.h);
  });
});
