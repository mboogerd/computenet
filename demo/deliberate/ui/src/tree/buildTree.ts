import type { GraphDto, NodeDto } from '../api/types';

/** A claim together with the arguments attached to it. */
export interface TreeNode {
  claim: NodeDto;
  /** Pro (SUPPORT) arguments first, then con (ATTACK); each group in first-appearance order. */
  children: ArgumentNode[];
  /**
   * Model A: set only on the tree returned for a framed question's root — one
   * subtree per reading/position, in `QuestionDto.framing.positions` order,
   * each equal to what {@link buildTree} returns rooted at that position's
   * ref (see {@link buildForest}).
   */
  positions?: TreeNode[];
}

/**
 * One argument: the edge (child → parent) and the subtree of its source
 * claim. The edge is also a **link** — the claim "“child” is a reason
 * for/against “parent”" (SPEC §3 "Links as claims") — with arguments of its
 * own, kept here rather than under either claim.
 */
export interface ArgumentNode {
  edge: NodeDto;
  node: TreeNode;
  /** Arguments about this link: why it holds (SUPPORT) first, then why it fails (ATTACK: undercutters). */
  linkArgs: ArgumentNode[];
  /** This argument is about a link (it sits in its parent argument's `linkArgs`), not about a claim. */
  onLink?: boolean;
}

/**
 * Builds the tree under `root` from a flat graph snapshot. Arguments of a
 * node — a claim, or a link, which is an EDGE node — are the EDGE nodes whose
 * `target` is that node, each paired with its `source` claim; an argument's
 * own link carries the arguments whose edges target that edge (undercutters
 * and link supporters), so they render under the link, never under a claim.
 * Edges whose source claim is absent from the snapshot are skipped (a frame
 * can carry an edge before its source). Cycles cannot occur in a deliberation
 * tree, but a visited set guards against rendering forever if one ever did.
 * Returns undefined when the root claim is not present.
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
  /** The arguments whose edges target `ref` (a claim or an edge): pro first, then con. */
  const argsOf = (ref: string, onLink: boolean): ArgumentNode[] => {
    const edges = edgesByTarget.get(ref) ?? [];
    const out: ArgumentNode[] = [];
    for (const edge of [...edges.filter((e) => e.polarity === 'SUPPORT'), ...edges.filter((e) => e.polarity === 'ATTACK')]) {
      const src = edge.source === undefined ? undefined : claims.get(edge.source);
      if (!src || visited.has(src.ref)) continue;
      const node = build(src);
      const arg: ArgumentNode = { edge, node, linkArgs: argsOf(edge.ref, true) };
      out.push(onLink ? { ...arg, onLink } : arg);
    }
    return out;
  };
  const build = (claim: NodeDto): TreeNode => {
    visited.add(claim.ref);
    return { claim, children: argsOf(claim.ref, false) };
  };

  return build(rootClaim);
}

/** Number of claims in a tree, including its root and every argument about a link. */
export function countClaims(tree: TreeNode): number {
  const args = (list: ArgumentNode[]): number => list.reduce((sum, a) => sum + countClaims(a.node) + args(a.linkArgs), 0);
  return 1 + args(tree.children);
}

/**
 * {@link buildTree}, extended for a framed question (SPEC §3 "Framing
 * (model A)"): when `root`'s question carries `framing`, the returned root
 * node also carries `positions` — one subtree per reading/position, built
 * exactly as {@link buildTree} builds a root, in `framing.positions` order.
 * A position whose claim is absent from the snapshot is left out (a frame can
 * carry a position before its claim). An unframed question is returned
 * unchanged from {@link buildTree}.
 */
export function buildForest(graph: GraphDto, root: string): TreeNode | undefined {
  const tree = buildTree(graph, root);
  if (!tree) return undefined;
  const framing = graph.questions.find((q) => q.root === root)?.framing;
  if (!framing) return tree;
  const positions = framing.positions.map((p) => buildTree(graph, p.ref)).filter((t): t is TreeNode => t !== undefined);
  return { ...tree, positions };
}
