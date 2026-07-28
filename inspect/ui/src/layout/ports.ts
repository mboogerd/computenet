import type { Port } from '../api/types';
import type { LayoutNode } from './layered';

export interface PortAnchor {
  x: number;
  y: number;
}

/** Every port's connection point on its node's card: IN ports evenly spaced
 *  down the left edge, OUT ports down the right edge — the "sources
 *  left, sinks right" layout convention extended to individual ports.
 *  Analytic (a function of the fixed card size + port list), never a live
 *  DOM read, so it stays correct under SSR-less static layout too. */
export function portAnchors(node: LayoutNode, ports: readonly Port[]): Map<string, PortAnchor> {
  const anchors = new Map<string, PortAnchor>();
  const place = (list: readonly Port[], x: number) => {
    list.forEach((p, i) => {
      const y = node.y + ((i + 1) / (list.length + 1)) * node.h;
      anchors.set(p.name, { x, y });
    });
  };
  place(
    ports.filter((p) => p.dir === 'IN'),
    node.x,
  );
  place(
    ports.filter((p) => p.dir === 'OUT'),
    node.x + node.w,
  );
  return anchors;
}
