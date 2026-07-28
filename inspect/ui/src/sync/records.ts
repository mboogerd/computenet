import type { Color, Dir, Edge, EdgeRole, Lifecycle, Node, Port, Ref } from '../api/types';

/** Client-side normalized records. Treat as immutable — the diff (diff.ts)
 *  reuses the previous object for anything byte-for-byte unchanged, and the
 *  rest of the app (Solid stores, the layout memo) leans on `prev === next`
 *  to mean "nothing changed here". */
export interface NodeRec {
  ref: Ref;
  name: string | null;
  typeFqn: string;
  color: Color;
  manifests: readonly string[];
  ports: readonly Port[];
  host: string | null;
  net: string | null;
  lifecycle: Lifecycle;
  generation: number;
  graph: string | null;
}

export interface EdgeRec {
  id: string;
  from: { ref: Ref; port: string };
  to: { ref: Ref; port: string };
  role: EdgeRole;
  fused: boolean | null;
}

export function normalizeNode(dto: Node): NodeRec {
  return {
    ref: dto.ref,
    name: dto.name ?? null,
    typeFqn: dto.typeFqn,
    color: dto.color ?? null,
    manifests: dto.manifests ?? [],
    ports: dto.ports ?? [],
    host: dto.host ?? null,
    net: dto.net ?? null,
    lifecycle: dto.lifecycle,
    generation: dto.generation,
    graph: dto.graph ?? null,
  };
}

export function normalizeEdge(dto: Edge): EdgeRec {
  return {
    id: dto.id,
    from: { ref: dto.from.ref, port: dto.from.port },
    to: { ref: dto.to.ref, port: dto.to.port },
    role: dto.role,
    fused: dto.fused ?? null,
  };
}

function portsEqual(a: readonly Port[], b: readonly Port[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].name !== b[i].name || a[i].dir !== b[i].dir || a[i].contractFqn !== b[i].contractFqn) return false;
  }
  return true;
}

function arrayEqual(a: readonly string[], b: readonly string[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

export function nodeEqual(a: NodeRec, b: NodeRec): boolean {
  return (
    a.name === b.name &&
    a.typeFqn === b.typeFqn &&
    a.color === b.color &&
    a.host === b.host &&
    a.net === b.net &&
    a.lifecycle === b.lifecycle &&
    a.generation === b.generation &&
    a.graph === b.graph &&
    arrayEqual(a.manifests, b.manifests) &&
    portsEqual(a.ports, b.ports)
  );
}

export function edgeEqual(a: EdgeRec, b: EdgeRec): boolean {
  return (
    a.from.ref === b.from.ref &&
    a.from.port === b.from.port &&
    a.to.ref === b.to.ref &&
    a.to.port === b.to.port &&
    a.role === b.role &&
    a.fused === b.fused
  );
}
