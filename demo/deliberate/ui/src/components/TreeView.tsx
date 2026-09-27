import { createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import { DEFAULT_CONSENSUS, type GraphDto } from '../api/types';
import { buildTree } from '../tree/buildTree';
import { agreementText, questionProgress, shown, STATUS_HINT, STATUS_LABEL, stoppedHint, stoppedText, verdict } from '../util/format';
import { createTween } from '../util/tween';
import { ClaimCard, Facts, indexTree, sideCounts, SpreadBand, useChildRefs, type Selection, type TreeIndex } from './ClaimCard';
import { OverrideControl } from './OverrideControl';

export function TreeView(props: { graph: GraphDto; root: string }) {
  const tree = createMemo(() => buildTree(props.graph, props.root));
  const index = createMemo<TreeIndex>(() => {
    const t = tree();
    return t ? indexTree(t) : new Map();
  });

  const [selected, setSelected] = createSignal<string>();
  const sel: Selection = {
    selected,
    toggle: (ref) => setSelected((cur) => (cur === ref ? undefined : ref)),
    members: () => props.graph.consensusMembers ?? DEFAULT_CONSENSUS,
  };
  onMount(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setSelected(undefined);
    };
    document.addEventListener('keydown', onKey);
    onCleanup(() => document.removeEventListener('keydown', onKey));
  });

  return (
    <Show when={tree()} fallback={<p class="empty">Waiting for the question to appear…</p>}>
      <Show when={props.root} keyed>
        {(root) => <Question root={root} index={index} graph={() => props.graph} sel={sel} />}
      </Show>
    </Show>
  );
}

/** The question is the hero: large text, one credence gauge, a plain verdict, and progress. */
function Question(props: { root: string; index: () => TreeIndex; graph: () => GraphDto; sel: Selection }) {
  const entry = () => props.index().get(props.root);
  const childRefs = useChildRefs(entry);
  const progress = createMemo(() => questionProgress(props.graph().nodes, props.root));
  const question = () => props.graph().questions.find((q) => q.root === props.root);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        // Label and number come from the same (animated) value, so they never disagree mid-glide.
        // The headline is the consensus of the credence rules; the band behind it is their spread.
        const tweened = createTween(() => shown(claim()));
        const v = () => verdict(tweened(), 'question');
        const counts = () => sideCounts(e().node);
        const open = () => props.sel.selected() === claim().ref;
        const busy = () => progress().active > 0;
        const override = () => claim().override ?? 'AUTO';

        return (
          <>
            <section class="hero" classList={{ 'is-open': open(), 'is-busy': busy() }} aria-label="Question">
              <h2 class="hero__q">{claim().text}</h2>

              <div
                class="gauge"
                title={`Credence: how likely the answer is yes, after weighing every argument — the consensus of the credence rules (${agreementText(claim())})`}
              >
                <span class="gauge__con" aria-hidden="true">no</span>
                <span class="gauge__track" aria-hidden="true">
                  <span class="gauge__fill" style={{ transform: `scaleX(${shown(claim())})` }} />
                  <SpreadBand node={claim()} class="gauge__band" />
                  <span class="gauge__mid" />
                </span>
                <span class="gauge__pro" aria-hidden="true">yes</span>
              </div>

              <div class="hero__row">
                <p class={`verdict verdict--${v().lean}`} aria-live="polite">
                  <span class="verdict__label">{v().label}</span>
                  <span class="verdict__sep" aria-hidden="true">·</span>
                  <span class="verdict__num">
                    {`${v().percent}%`}
                  </span>
                </p>
                <button
                  type="button"
                  class="more"
                  aria-expanded={open()}
                  aria-controls={`facts-${claim().ref}`}
                  onClick={() => props.sel.toggle(claim().ref)}
                >
                  {open() ? 'Less' : 'Details'}
                </button>
              </div>

              <div
                class="activity"
                classList={{ 'is-busy': busy() }}
                role="progressbar"
                aria-label="Claims settled"
                aria-valuemin={0}
                aria-valuemax={progress().total}
                aria-valuenow={progress().settled}
              >
                <span style={{ transform: `scaleX(${progress().fraction})` }} />
              </div>
              <p class="hero__meta">
                <span>
                  <span class="pro-text">{counts().pro} pro</span> · <span class="con-text">{counts().con} con</span>
                </span>
                <span>
                  {busy()
                    ? `deliberating · ${progress().settled} of ${progress().total} claims settled`
                    : `settled · ${progress().total} claims`}
                </span>
                <Show when={stoppedText(question())}>
                  {(text) => (
                    <span class="hero__stopped" title={stoppedHint(question())}>
                      {text()}
                    </span>
                  )}
                </Show>
                <Show when={claim().status && (claim().status === 'STOPPED' || claim().status === 'FAILED')}>
                  <span class="status status--halted" title={STATUS_HINT[claim().status!]}>
                    question {STATUS_LABEL[claim().status!]}
                  </span>
                </Show>
                <span class="card__spacer" />
                <span class="reveal" classList={{ 'is-pinned': override() !== 'AUTO' }}>
                  <OverrideControl id={claim().ref} value={override()} />
                </span>
              </p>

              <Show when={open()}>
                <Facts id={`facts-${claim().ref}`} claim={claim()} members={props.sel.members?.()} />
              </Show>
            </section>

            <Show
              when={childRefs().length > 0}
              fallback={<p class="empty empty--quiet">Proposers are drafting the first arguments…</p>}
            >
              <ul class="tree" aria-label="Deliberation tree">
                <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} sel={props.sel} />}</For>
              </ul>
            </Show>
          </>
        );
      }}
    </Show>
  );
}
