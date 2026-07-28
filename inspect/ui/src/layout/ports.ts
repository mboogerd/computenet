import type { Dir, Port } from '../api/types';
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

/**
 * Where an edge attaches when the node declares no such port: the middle of
 * the card's outgoing (right) or incoming (left) side.
 *
 * Until M5 the canvas simply dropped such an edge, which was invisible because
 * every cell in the pilot had a KSP descriptor naming its ports (M3-EVAL open
 * question 1). M5-NET makes it bite for real: a peer-announced cell arrives as
 * a bare `CellRef`, so it has no descriptor, no port list, and its mirrored
 * edges carry raw port ids — and the declared cross-boundary edge lands on a
 * port name that cell never reported. Anchoring on the card is the honest
 * rendering: the edge exists and reaches this cell, and the client is not
 * pretending to know which port.
 */
export function cardAnchor(node: LayoutNode, dir: Dir): PortAnchor {
  return dir === 'IN'
    ? { x: node.x, y: node.y + node.h / 2 }
    : { x: node.x + node.w, y: node.y + node.h / 2 };
}
