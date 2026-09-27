import { createEffect, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { EmptyState } from './components/EmptyState';
import { Legend } from './components/Legend';
import { QuestionInput } from './components/QuestionInput';
import { QuestionList } from './components/QuestionList';
import { ThemeToggle } from './components/ThemeToggle';
import { Toasts } from './components/Toasts';
import { TreeView } from './components/TreeView';
import { conn, graph, source, startSync } from './sync/store';

const CONN_LABEL = { connecting: 'connecting', live: 'live', reconnecting: 'reconnecting', mock: 'mock data' } as const;

export function App() {
  const [selected, setSelected] = createSignal<string>();
  const [asking, setAsking] = createSignal(false);
  const [askError, setAskError] = createSignal<string>();

  onMount(() => onCleanup(startSync()));

  // Nothing chosen yet (or the chosen one vanished): follow the newest question.
  createEffect(() => {
    const qs = graph().questions;
    const sel = selected();
    if (qs.length === 0) return;
    if (sel === undefined || !qs.some((q) => q.root === sel)) setSelected(qs[qs.length - 1].root);
  });

  const ask = async (text: string): Promise<boolean> => {
    if (asking()) return false;
    setAsking(true);
    setAskError(undefined);
    try {
      const root = await source.ask(text);
      setSelected(root);
      return true;
    } catch (err) {
      setAskError(err instanceof Error ? err.message : String(err));
      return false;
    } finally {
      setAsking(false);
    }
  };

  return (
    <div class="app">
      <header class="top">
        <div class="brand">
          <span class="brand__mark" aria-hidden="true" />
          <h1>deliberate</h1>
        </div>
        <span class={`conn conn--${conn()}`} title={`Connection: ${CONN_LABEL[conn()]}`}>
          {CONN_LABEL[conn()]}
        </span>
        <Legend />
        <ThemeToggle />
      </header>

      <QuestionInput ask={ask} busy={asking()} error={askError()} />
      <QuestionList questions={graph().questions} selected={selected()} onSelect={setSelected} />

      <main class="stage">
        <Show when={selected()} fallback={<EmptyState onPick={(q) => void ask(q)} disabled={asking()} />}>
          {(root) => <TreeView graph={graph()} root={root()} />}
        </Show>
      </main>
      <Toasts />
    </div>
  );
}
