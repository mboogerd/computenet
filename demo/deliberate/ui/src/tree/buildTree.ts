import type { GraphDto, NodeDto } from '../api/types';

/** A claim together with the arguments attached to it. */
export interface TreeNode {
  claim: NodeDto;
  /**
   * Pro (SUPPORT) arguments first, then con (ATTACK), then undercutters of the
   * link attaching this claim to its parent; each group in first-appearance order.
   */
  children: ArgumentNode[];
}

/** One argument: the edge (child → parent) and the subtree of its source claim. */
export interface ArgumentNode {
  edge: NodeDto;
  node: TreeNode;
  /** EXP-03 UNDERCUT: `edge` attacks the link above this subtree's parent, not a claim. */
  undercut?: boolean;
}

/**
 * Builds the tree under `root` from a flat graph snapshot. Children of a claim
 * are the EDGE nodes whose `target` is that claim, each paired with its
 * `source` claim, followed by its undercutters: the claims whose edge targets
 * the edge attaching the claim to its parent (EXP-03 UNDERCUT — "this does
 * not show that"), rendered under the argument whose link they attack. Edges whose source claim is absent from the snapshot are
 * skipped (a frame can carry an edge before its source). Cycles cannot occur
 * in a deliberation tree, but a visited set guards against rendering forever
 * if one ever did. Returns undefined when the root claim is not present.
 */
export function buildTree(graph: GraphDto, root: string): TreeNode | undefined {
  const allClaims = new Map<string, NodeDto>();
  for (const n of graph.nodes) {
    if (n.kind === 'CLAIM') allClaims.set(n.ref, n);
  }
  const rootClaim = allClaims.get(root);
  if (!rootClaim) return undefined;

  // A snapshot contains every question. Scope both claims and edges to the
  // selected claim's question before following refs, so malformed or stale
  // cross-question edges cannot splice two trees together.
  const treeRoot = rootClaim.root;
  const claims = new Map<string, NodeDto>();
  const edgesByTarget = new Map<string, NodeDto[]>();
  for (const n of graph.nodes) {
    if (n.root !== treeRoot) continue;
    if (n.kind === 'CLAIM') {
      claims.set(n.ref, n);
    } else if (n.kind === 'EDGE' && n.target !== undefined && (n.polarity === 'SUPPORT' || n.polarity === 'ATTACK')) {
      const list = edgesByTarget.get(n.target);
      if (list) list.push(n);
      else edgesByTarget.set(n.target, [n]);
    }
  }

  const visited = new Set<string>();
  const build = (claim: NodeDto, via?: NodeDto): TreeNode => {
    visited.add(claim.ref);
    const edges = edgesByTarget.get(claim.ref) ?? [];
    const ordered: Array<[NodeDto, boolean]> = [
      ...edges.filter((e) => e.polarity === 'SUPPORT').map((e): [NodeDto, boolean] => [e, false]),
      ...edges.filter((e) => e.polarity === 'ATTACK').map((e): [NodeDto, boolean] => [e, false]),
      ...(via ? edgesByTarget.get(via.ref) ?? [] : []).map((e): [NodeDto, boolean] => [e, true]),
    ];
    const children: ArgumentNode[] = [];
    for (const [edge, undercut] of ordered) {
      const src = edge.source === undefined ? undefined : claims.get(edge.source);
      if (!src || visited.has(src.ref)) continue;
      children.push(undercut ? { edge, node: build(src, edge), undercut } : { edge, node: build(src, edge) });
    }
    return { claim, children };
  };

  return build(rootClaim);
}

/** Number of claims in a tree, including its root. */
export function countClaims(tree: TreeNode): number {
  return 1 + tree.children.reduce((sum, a) => sum + countClaims(a.node), 0);
}
