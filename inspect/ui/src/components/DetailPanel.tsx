import { For, Show, createMemo, type JSX } from 'solid-js';
import { colorGlyph, manifestBadge, shortType } from '../util/badges';
import { cellDetail, cellState, detailError, detailLoading, stateError, stateLoading } from '../solid/detail';
import { selection, setSelection } from '../solid/state';
import ValueView from './ValueView';
import './DetailPanel.css';

/** M1-FE ticket Implement §1: the detail panel always stacks all four
 *  subsections on selection — no perspective switching (that was v2; see
 *  10-target-v3.md "The v3 model"). Selection persists across toggle
 *  changes because it is an independent signal from the toggle set (nothing
 *  here reacts to `solid/toggles.ts`). */
export default function DetailPanel() {
  return (
    <aside class="detail-panel">
      <Show when={selection()} fallback={<p class="detail-panel__empty">Select a node to inspect it.</p>}>
        {(ref) => (
          <>
            <div class="detail-panel__head">
              <h2 class="detail-panel__name">{cellDetail()?.name ?? ref()}</h2>
              <button
                class="icon-btn detail-panel__close"
                title="Close (deselect)"
                onClick={() => setSelection(null)}
              >
                ×
              </button>
            </div>
            <DescriptorSection />
            <StateSection />
            <FlowSection />
            <ErrorsSection />
          </>
        )}
      </Show>
    </aside>
  );
}

function Section(props: { title: string; children: JSX.Element }) {
  return (
    <section class="detail-section">
      <h3 class="detail-section__title">{props.title}</h3>
      <div class="detail-section__body">{props.children}</div>
    </section>
  );
}

/** 10-target-v3.md "Selecting a node shows all of its properties": class,
 *  color, manifests, ports, process host, network host, generation,
 *  lifecycle, attention band — from `GET /cell/{ref}` (`CellDetail`), not
 *  the topology store, per the ticket's explicit source. */
function DescriptorSection() {
  return (
    <Section title="Descriptor & placement">
      <Show when={!detailLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show
          when={cellDetail()}
          fallback={<p class="detail-section__status detail-section__status--error">{describeError(detailError())}</p>}
        >
          {(d) => (
            <dl class="descriptor-grid">
              <dt>Class</dt>
              <dd class="mono" title={d().typeFqn}>
                {shortType(d().typeFqn)}
              </dd>

              <dt>Color</dt>
              <dd>
                <span class="detail-chip" data-color={d().color ?? 'unknown'} title={d().color ?? 'unknown'}>
                  {colorGlyph(d().color)}
                </span>
              </dd>

              <dt>Manifests</dt>
              <dd>
                <Show when={d().manifests.length} fallback="—">
                  <div class="detail-badges">
                    <For each={d().manifests}>{(m) => <span class="detail-badge" title={m}>{manifestBadge(m)}</span>}</For>
                  </div>
                </Show>
              </dd>

              <dt>Ports</dt>
              <dd>
                <Show when={d().ports.length} fallback="—">
                  <ul class="ports-list">
                    <For each={d().ports}>
                      {(p) => (
                        <li>
                          <span class="mono">{p.name}</span> <span class="detail-muted">({p.dir})</span>
                        </li>
                      )}
                    </For>
                  </ul>
                </Show>
              </dd>

              <dt>Process host</dt>
              <dd class="mono">{d().host ?? '—'}</dd>

              <dt>Network host</dt>
              <dd class="mono">{d().net ?? '—'}</dd>

              <dt>Generation</dt>
              <dd>{d().generation}</dd>

              <dt>Lifecycle</dt>
              <dd>{d().lifecycle}</dd>

              <dt>Attention</dt>
              <dd>{d().attention ?? '—'}</dd>

              <dt>Links</dt>
              <dd>
                in {d().links.inbound} · out {d().links.outbound} · taps {d().links.taps}
              </dd>
            </dl>
          )}
        </Show>
      </Show>
    </Section>
  );
}

/** M1-FE ticket Implement §2: observe/state fetch is driven from
 *  `solid/detail.ts` (wired to `selection()`); this section only renders
 *  what it is handed. The fixed footnote and staleness/frontier chip are
 *  part of this subsection specifically — F-5 (10-target-v3.md constraint
 *  4): "cross-panel wave alignment is NOT guaranteed". */
function StateSection() {
  const frontierLabel = createMemo(() => {
    const f = cellState()?.frontier;
    return f ? `${f.source.slice(0, 8)} · ${f.counter}` : '—';
  });

  return (
    <Section title="State">
      <Show when={!stateLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show
          when={cellState()}
          fallback={<p class="detail-section__status detail-section__status--error">{describeError(stateError())}</p>}
        >
          {(s) => (
            <>
              <div class="state-meta">
                <span class="state-meta__frontier mono" title="frontier stamp (source · counter)">
                  {frontierLabel()}
                </span>
                <span class="state-meta__stale">{s().staleMs}ms stale</span>
                <span class="state-meta__kind">{s().kind}</span>
              </div>
              <Show
                when={s().kind !== 'unavailable'}
                fallback={<p class="detail-section__status">State unavailable for this cell.</p>}
              >
                <ValueView value={s().value} />
              </Show>
            </>
          )}
        </Show>
      </Show>
      <p class="detail-section__footnote">per-cell consistent — cross-panel alignment not guaranteed</p>
    </Section>
  );
}

function FlowSection() {
  return (
    <Section title="Flow">
      <p class="detail-section__status">arrives with the Flow milestone</p>
    </Section>
  );
}

function ErrorsSection() {
  return (
    <Section title="Errors">
      <p class="detail-section__status">arrives with the Errors milestone</p>
    </Section>
  );
}

function describeError(err: unknown): string {
  if (err instanceof Error) return err.message;
  return err ? String(err) : 'failed to load';
}
