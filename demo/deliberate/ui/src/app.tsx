import { createEffect, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { QuestionInput } from './components/QuestionInput';
import { QuestionList } from './components/QuestionList';
import { TreeView } from './components/TreeView';
import { conn, graph, startSync } from './sync/store';

const CONN_LABEL = { connecting: 'connecting', live: 'live', reconnecting: 'reconnecting', mock: 'mock' } as const;

export function App() {
  const [selected, setSelected] = createSignal<string>();

  onMount(() => onCleanup(startSync()));

  // Nothing chosen yet (or the chosen one vanished): follow the newest question.
  createEffect(() => {
    const qs = graph().questions;
    const sel = selected();
    if (qs.length === 0) return;
    if (sel === undefined) setSelected(qs[qs.length - 1].root);
  });

  return (
    <div class="app">
      <header class="top">
        <div class="brand">
          <span class="brand__mark" aria-hidden="true" />
          <h1>deliberate</h1>
          <span class={`conn conn--${conn()}`} title={`Connection: ${CONN_LABEL[conn()]}`}>
            {CONN_LABEL[conn()]}
          </span>
        </div>
        <QuestionInput onAsked={setSelected} />
        <QuestionList questions={graph().questions} selected={selected()} onSelect={setSelected} />
      </header>

      <main class="stage">
        <Show
          when={selected()}
          fallback={
            <div class="empty empty--hero">
              <p>Ask a question. Two proposers argue both sides, Jev judges, and the tree grows here as they work.</p>
            </div>
          }
        >
          {(root) => <TreeView graph={graph()} root={root()} />}
        </Show>
      </main>
    </div>
  );
}
