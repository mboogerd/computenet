import { createMemo, createSignal, For, Show } from 'solid-js';
import type { Polarity } from '../api/types';
import { createClaim, createEdge, remove } from '../api/commands';
import { graph, nodes, nodeOf, structuralVersion, selection, setSelection, notify } from '../solid/graph';
import { labelOf } from '../util/label';
import CredenceBadge from './CredenceBadge';
import StanceSlider from './StanceSlider';
import Sparkline from './Sparkline';
import './DetailPanel.css';

const HINT_KEY = 'agora.hint.edge';

/** Details-on-demand for ANY selected node — claim or edge (spec §2/§6). The
 *  edge case is the whole point: selecting an edge lets you argue about the
 *  link itself. "Argue" creates a fresh claim as the source and an edge from
 *  it to the selected node (target = the selected edge's ref for edge-on-edge). */
export default function DetailPanel() {
  const node = () => nodeOf(selection());
  const isEdge = () => node()?.kind === 'EDGE';

  const incoming = createMemo(() => {
    structuralVersion();
    const r = selection();
    return r ? [...(graph.incoming.get(r) ?? [])] : [];
  });

  const [text, setText] = createSignal('');
  const [busy, setBusy] = createSignal(false);
  const [err, setErr] = createSignal('');

  const [hintDismissed, setHintDismissed] = createSignal(localStorage.getItem(HINT_KEY) === '1');
  const dismissHint = () => {
    localStorage.setItem(HINT_KEY, '1');
    setHintDismissed(true);
  };

  async function argue(polarity: Polarity) {
    const t = text().trim();
    const target = selection();
    if (!t || !target) return;
    setBusy(true);
    setErr('');
    try {
      const { ref } = await createClaim(t);
      await createEdge(ref, target, polarity);
      setText('');
    } catch (e) {
      setErr((e as Error).message);
      notify(`Could not create argument: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  async function del() {
    const r = selection();
    if (!r) return;
    try {
      await remove(r);
      setSelection(null);
    } catch (e) {
      notify(`Could not remove: ${(e as Error).message}`);
    }
  }

  return (
    <Show when={node()}>
      {(n) => (
        <aside class="panel">
          <header class="panel__head">
            <span class="panel__kind">{isEdge() ? 'Argument (link)' : 'Claim'}</span>
            <button class="panel__close" title="Close" onClick={() => setSelection(null)}>
              ×
            </button>
          </header>

          <p class="panel__text">{labelOf(n())}</p>

          <div class="panel__credence">
            <span class="panel__field-label">Aggregate credence</span>
            <CredenceBadge credence={n().credence} />
          </div>

          <div class="panel__field">
            <span class="panel__field-label">Your stance</span>
            <StanceSlider nodeRef={n().ref} />
          </div>

          <Show when={isEdge() && !hintDismissed()}>
            <div class="panel__hint">
              You selected an <strong>argument</strong> — the link itself, not the claims it
              connects. Arguing here challenges or reinforces <em>the link</em>.
              <button class="panel__hint-x" onClick={dismissHint}>
                Got it
              </button>
            </div>
          </Show>

          <div class="panel__field">
            <span class="panel__field-label">
              {isEdge() ? 'Argue against this argument' : 'Add an argument'}
            </span>
            <textarea
              class="panel__input"
              rows="2"
              placeholder={isEdge() ? 'Why the link does (not) hold…' : 'A supporting or attacking claim…'}
              value={text()}
              onInput={(e) => setText(e.currentTarget.value)}
            />
            <div class="panel__actions">
              <button
                class="panel__btn panel__btn--attack"
                disabled={busy() || !text().trim()}
                onClick={() => argue('ATTACK')}
              >
                Attack
              </button>
              <button
                class="panel__btn panel__btn--support"
                disabled={busy() || !text().trim()}
                onClick={() => argue('SUPPORT')}
              >
                Support
              </button>
            </div>
            <Show when={err()}>
              <p class="panel__err">{err()}</p>
            </Show>
          </div>

          <div class="panel__field">
            <span class="panel__field-label">
              {isEdge() ? 'Challenges to this link' : 'Arguments about this'} ({incoming().length})
            </span>
            <Show
              when={incoming().length}
              fallback={<p class="panel__empty">None yet.</p>}
            >
              <ul class="panel__incoming">
                <For each={incoming()}>
                  {(edgeRef) => {
                    const e = () => nodes[edgeRef];
                    return (
                      <Show when={e()}>
                        <li>
                          <button class="panel__incoming-row" onClick={() => setSelection(edgeRef)}>
                            <span class="panel__incoming-label">{labelOf(e()!)}</span>
                            <CredenceBadge credence={e()!.credence} size="sm" />
                          </button>
                        </li>
                      </Show>
                    );
                  }}
                </For>
              </ul>
            </Show>
          </div>

          <div class="panel__field">
            <span class="panel__field-label">History (since you opened this page)</span>
            <Sparkline nodeRef={n().ref} credence={n().credence} />
          </div>

          <button class="panel__remove" onClick={del}>
            Remove this {isEdge() ? 'argument' : 'claim'}
          </button>
        </aside>
      )}
    </Show>
  );
}
