import type { Frame, Graph, NodeState, StudioNode } from './types';

export const emptyGraph = (): Graph => ({ nodes: [], edges: [], state: {} });

export function reduceFrame(graph: Graph, frame: Frame): Graph {
  switch (frame.type) {
    case 'snapshot': return { nodes: frame.nodes, edges: frame.edges, state: frame.state };
    case 'graph': {
      const ids = new Set(frame.nodes.map(node => node.id));
      return {
        nodes: frame.nodes, edges: frame.edges,
        state: Object.fromEntries(Object.entries(graph.state).filter(([id]) => ids.has(id))),
      };
    }
    case 'state': return { ...graph, state: { ...graph.state, [frame.node.id]: frame.node } };
  }
}

export interface Point { x: number; y: number }
export const CARD_WIDTH = 260;
export const PORT_TOP = 82;
export const PORT_GAP = 28;
export function outputPoint(node: StudioNode): Point {
  return { x: node.x + CARD_WIDTH, y: node.y + PORT_TOP };
}
export function inputPoint(node: StudioNode, port: string): Point {
  return { x: node.x, y: node.y + PORT_TOP + Math.max(0, node.ports.indexOf(port)) * PORT_GAP };
}
export function wirePath(from: Point, to: Point): string {
  const bend = Math.max(60, Math.abs(to.x - from.x) * 0.5);
  return `M ${from.x} ${from.y} C ${from.x + bend} ${from.y}, ${to.x - bend} ${to.y}, ${to.x} ${to.y}`;
}
export function outputPreview(text: string | null | undefined, maxLines = 4, maxChars = 320): string {
  if (!text) return '';
  const lines = text.split('\n');
  const visible = lines.slice(0, maxLines).join('\n');
  return visible.slice(0, maxChars) + (lines.length > maxLines || visible.length > maxChars ? '…' : '');
}

/** A failed candidate needs an explicit user confirmation in the panel. */
export function promotionAction(state?: NodeState): 'ready' | 'confirm' | 'disabled' {
  if (!state?.preview || state.preview.codeHash !== state.codeHash || state.codeHash === state.approvedHash) return 'disabled';
  return state.preview.status === 'idle' ? 'ready' : state.preview.status === 'failed' ? 'confirm' : 'disabled';
}
