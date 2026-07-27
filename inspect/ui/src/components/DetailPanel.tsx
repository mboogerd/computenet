import { Show, createMemo } from 'solid-js';
import { nodes, selection } from '../solid/state';
import './DetailPanel.css';

/** M0-FE Implement §4: "selection state is wired now; the detail panel
 *  content arrives in M1 — render an empty right panel with the node's
 *  name." Everything past the name (descriptor/placement/state/flow/errors
 *  subsections, 10-target-v3.md "Selecting a node") is out of scope here. */
export default function DetailPanel() {
  const selectedNode = createMemo(() => {
    const ref = selection();
    return ref ? nodes[ref] : undefined;
  });

  return (
    <aside class="detail-panel">
      <Show when={selectedNode()} fallback={<p class="detail-panel__empty">Select a node to inspect it.</p>}>
        {(rec) => <h2 class="detail-panel__name">{rec().name ?? rec().ref}</h2>}
      </Show>
    </aside>
  );
}
