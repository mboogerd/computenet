import { createEffect, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { CruxesPanel } from './components/CruxesPanel';
import { DisagreementPanel } from './components/DisagreementPanel';
import { EmptyState } from './components/EmptyState';
import { Legend } from './components/Legend';
import { QuestionInput } from './components/QuestionInput';
import { QuestionList } from './components/QuestionList';
import { accumulate, Sparkline, type SparkState } from './components/Sparkline';
import { ThemeToggle } from './components/ThemeToggle';
import { Toasts } from './components/Toasts';
import { TreeView } from './components/TreeView';
import { conn, graph, source, startSync } from './sync/store';
import { isResearch, shown } from './util/format';

const CONN_LABEL = { connecting: 'connecting', live: 'live', reconnecting: 'reconnecting', mock: 'mock data' } as const;

export function App() {
  const [selected, setSelected] = createSignal<string>();
  const [asking, setAsking] = createSignal(false);
  const [askError, setAskError] = createSignal<string>();
  // Model D: only the consensus by default; each rule's values in the research view.
  const [research, setResearch] = createSignal(isResearch());
  // Session sparkline of the selected question's root credence: accumulated
  // client-side across snapshots, reset when a different question is selected.
  let sparkState: SparkState | undefined;
  const [sparkPoints, setSparkPoints] = createSignal<number[]>([]);

  onMount(() => onCleanup(startSync()));

  // Nothing chosen yet (or the chosen one vanished): follow the newest question.
  createEffect(() => {
    const qs = graph().questions;
    const sel = selected();
    if (qs.length === 0) return;
    if (sel === undefined || !qs.some((q) => q.root === sel)) setSelected(qs[qs.length - 1].root);
  });

  // One point per snapshot in which the selected question's shown root
  // credence changed; a different `root` starts a fresh series.
  createEffect(() => {
    const root = selected();
    if (root === undefined) return;
    const node = graph().nodes.find((n) => n.ref === root);
    sparkState = accumulate(sparkState, root, node === undefined ? undefined : shown(node));
    setSparkPoints(sparkState.points);
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
        <button
          type="button"
          class="iconbtn research"
          classList={{ 'is-on': research() }}
          aria-pressed={research()}
          aria-label="Research view: show each credence rule's values"
          title={research() ? "Hide each rule's values: show the consensus only" : "Research view: show each credence rule's values and how far they agree"}
          onClick={() => setResearch(!research())}
        >
          rules
        </button>
        <ThemeToggle />
      </header>

      <QuestionInput ask={ask} busy={asking()} error={askError()} />
      <QuestionList questions={graph().questions} selected={selected()} onSelect={setSelected} />

      <main class="stage">
        <Show when={selected()} fallback={<EmptyState onPick={(q) => void ask(q)} disabled={asking()} />}>
          {(root) => (
            <>
              <div class="qheader">
                <Sparkline points={sparkPoints()} />
              </div>
              <TreeView graph={graph()} root={root()} research={research()} />
              <CruxesPanel graph={graph()} root={root()} />
              <DisagreementPanel graph={graph()} root={root()} />
            </>
          )}
        </Show>
      </main>
      <Toasts />
    </div>
  );
}
