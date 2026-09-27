import { createMemo, Show } from 'solid-js';
import type { GraphDto } from '../api/types';
import { buildTree } from '../tree/buildTree';
import { ClaimCard, indexTree } from './ClaimCard';

export function TreeView(props: { graph: GraphDto; root: string }) {
  const tree = createMemo(() => buildTree(props.graph, props.root));
  const index = createMemo(() => {
    const t = tree();
    return t ? indexTree(t) : new Map();
  });
  return (
    <Show when={tree()} fallback={<p class="empty">Waiting for the question to appear…</p>}>
      <ul class="tree" aria-label="Deliberation tree">
        <Show when={props.root} keyed>
          {(root) => <ClaimCard claimRef={root} index={index} isRoot />}
        </Show>
      </ul>
    </Show>
  );
}
