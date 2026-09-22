package civictech.demo.alignment

/**
 * The Board's split drill-down (computenet-w0i5h.2, ALN2.5, w0i5h-D6..D13), concatenated onto
 * [BOARD_MAIN] and [SCATTER_VIEW] to form [BOARD_VIEW]: its `<style>`, the native
 * `<dialog id="drill">` markup and the `<script>` defining `openDrill(t, ideaId, dimId)`,
 * `closeDrill()` and `renderDrill()`. [BOARD_MAIN] (BoardView.kt) opens it from a row's split
 * pill or a `#discuss` row, calls `renderDrill()` after `renderScatter` while the gate is open and
 * `closeDrill()` on every early return, so the dialog never outlives an open Board.
 *
 * Gating and data (w0i5h-D8): `renderDrill()` closes the dialog and returns unless
 * `boardGate(t).open` (the shell's gate, consumed as-is — its completeness rule is the parked
 * question computenet-aa6gl) and the idea and dimension still exist. Only inside that check does
 * it read `state.ratings` (topic/idea/dim filtered) — the ONLY read of `state.ratings` in the
 * Board slice, and the only place the Board renders a participant name. Mean, stdev and n come
 * from the API's `state.aggregates[t].ideas[].byDim[dim]`, never recomputed; the one statistic
 * computed here is the median that selects the outlier(s) over the displayed raw dots (w0i5h-D10).
 *
 * `#drillDims` (w0i5h-D7): one button per topic dimension in dimension order, coloured
 * `dimColour(t, d)`, `aria-pressed` on the active one (`drillDim`), split dimensions suffixed
 * "split". `#drillPlot` (w0i5h-D9): a 1..9 axis with one focusable dot per rating at
 * `(v - 1) / 8`, stacked in up to four lanes by a deterministic sweep (sorted by value then
 * participant; a dot within 3% of a lane's previous dot moves to the next lane), so a re-render
 * never shuffles dots; each dot's `title`/`aria-label` is `participant · v.v`, and hover/focus
 * also shows it as a tooltip. `#drillOutlier` (w0i5h-D10): with n >= 3, every dot whose
 * |v - median| is the maximum (within 1e-9) is ringed and named "hear first: …", furthest first,
 * ties by name. One deliberate reading beyond the design text: when that maximum is 0 (everyone
 * gave the same value) nothing is ringed and the callout says so — ringing every dot as an
 * "outlier" would name nobody to hear first.
 *
 * `#drillNote` (w0i5h-D11): the per-idea note, editable iff `mayAddIdea(t)` (the idea policy,
 * w0i5h-D1); read-only with the save control hidden otherwise. Save (button or Ctrl/Cmd+Enter)
 * PUTs `{participant: me(), text}`; the textarea takes the server's answer and later frames. A
 * frame's note is applied only while `!editing(el('drill'))` and when it differs from
 * `drillShownNote`; while the textarea is focused it is held and `#drillNoteBy` says who changed it.
 *
 * Dialog mechanics (w0i5h-D13): `showModal()`; Escape, `#drillClose` or a backdrop click close it;
 * the `close` event resets the drill state and returns focus to the opening marker when it still
 * exists. Motion is a short fade, off under `prefers-reduced-motion: reduce`. No `$` anywhere, no
 * literal colour. Shared helper contract: the comment block at the top of the shell's script in
 * [AlignmentPage.kt].
 */
internal const val DRILLDOWN_VIEW = """
<style>
  #drill { padding: 0; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface);
           color: var(--ink); box-shadow: var(--shadow); width: min(38rem, calc(100vw - 32px)); max-width: none; }
  #drill[open] { animation: drillFade .18s ease-out; }
  #drill::backdrop { background: var(--ink); opacity: .35; }
  #drill .drill-body { padding: 1rem 1.2rem 1.1rem; }
  #drill .drill-head { display: flex; align-items: baseline; justify-content: space-between; gap: .8rem; margin-bottom: .6rem; }
  #drillTitle { margin: 0; font-size: var(--fs-3); min-width: 0; overflow-wrap: anywhere; }
  #drillDims { display: flex; flex-wrap: wrap; gap: .35rem; margin-bottom: .8rem; }
  #drillDims button { font-size: .75rem; padding: .2rem .65rem; border-radius: 999px; display: inline-flex; align-items: center; gap: .35rem;
                      color: var(--muted); }
  #drillDims button[aria-pressed="true"] { color: var(--ink); border-color: currentColor; font-weight: 600; }
  #drillDims .sw { width: .6rem; height: .6rem; border-radius: 3px; flex: 0 0 auto; }
  #drillDims .dsx { color: var(--warn); font-weight: 600; }
  #drillPlot { margin: 1.5rem 0 .3rem; } /* room above the top lane for a dot's tooltip */
  #drillPlot .drill-empty { color: var(--muted); font-size: var(--fs-2); padding: 1rem 0; text-align: center; }
  #drillPlot .drill-inner { position: relative; margin: 0 12px; }
  #drillPlot .drill-band { position: absolute; top: 0; border-radius: 4px; opacity: .16; }
  #drillPlot .drill-mean { position: absolute; top: 0; width: 2px; margin-left: -1px; opacity: .8; }
  #drillPlot .drill-dot { position: absolute; width: 12px; height: 12px; margin: -6px 0 0 -6px; border-radius: 50%;
                          border: 1.5px solid var(--surface); cursor: default; }
  #drillPlot .drill-dot:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  #drillPlot .drill-dot.outlier { box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px var(--warn); }
  #drillPlot .drill-dot::after { content: attr(data-label); position: absolute; bottom: 150%; left: 50%; transform: translateX(-50%);
                                 white-space: nowrap; font-size: var(--fs-1); padding: .1rem .4rem; border-radius: 6px;
                                 background: var(--ink); color: var(--surface); pointer-events: none; opacity: 0; z-index: 3; }
  #drillPlot .drill-dot:hover, #drillPlot .drill-dot:focus { z-index: 4; }
  #drillPlot .drill-dot:hover::after, #drillPlot .drill-dot:focus::after { opacity: 1; }
  #drillPlot .drill-axis { position: absolute; left: 0; right: 0; height: 1px; background: var(--line); }
  #drillPlot .drill-tick { position: absolute; width: 1px; height: 5px; background: var(--line); }
  #drillPlot .drill-tlabel { position: absolute; transform: translateX(-50%); font-size: .68rem; color: var(--muted);
                             font-variant-numeric: tabular-nums; }
  #drillPlot .drill-anchors { display: flex; justify-content: space-between; gap: 1rem; font-size: var(--fs-1); color: var(--muted);
                              margin: .1rem 0 0; }
  #drillStats { margin: .4rem 0 .15rem; font-size: var(--fs-2); color: var(--muted); font-variant-numeric: tabular-nums; }
  #drillOutlier { margin: 0 0 .9rem; font-size: var(--fs-2); }
  #drillOutlier.hear { color: var(--warn); font-weight: 600; }
  #drill .drill-notelabel { display: block; font-size: var(--fs-1); font-weight: 600; color: var(--muted);
                            text-transform: uppercase; letter-spacing: .04em; margin-bottom: .3rem; }
  #drillNote { width: 100%; min-height: 4.5rem; resize: vertical; }
  #drillNote[readonly] { background: var(--surface-2); color: var(--muted); }
  #drill .drill-noterow { display: flex; justify-content: space-between; align-items: center; gap: .8rem; margin-top: .45rem; }
  #drillNoteBy { font-size: var(--fs-1); color: var(--muted); }
  @keyframes drillFade { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  @media (prefers-reduced-motion: reduce) {
    #drill[open] { animation: none; }
  }
</style>
<dialog id="drill" aria-labelledby="drillTitle">
  <div class="drill-body">
    <div class="drill-head">
      <h3 id="drillTitle"></h3>
      <button type="button" id="drillClose" class="link" aria-label="close the drill-down">close</button>
    </div>
    <div id="drillDims" role="group" aria-label="dimension"></div>
    <div id="drillPlot"></div>
    <p id="drillStats"></p>
    <p id="drillOutlier" aria-live="polite"></p>
    <label class="drill-notelabel" for="drillNote">what the team decided</label>
    <textarea id="drillNote" maxlength="4000" rows="3" placeholder="record the decision for this idea"></textarea>
    <div class="drill-noterow">
      <span id="drillNoteBy"></span>
      <button type="button" id="drillNoteSave" class="primary">save note</button>
    </div>
  </div>
</dialog>
<script>
// ── Board drill-down: one idea × dimension, every participant's rating ──────
// The ONLY reader of state.ratings in the Board slice, and only inside
// renderDrill()'s boardGate(t).open check (w0i5h-D8). Mean/stdev/n are the
// API's byDim values; the median below only selects the outlier dots.
let drillTopic = null;      // topic id the dialog was opened for
let drillIdea = null;       // idea id shown, null while closed
let drillDim = null;        // active dimension id
let drillShownNote = null;  // the frame note last written into the textarea
let drillOpener = null;     // {kind: 'pill' | 'drow', idea} to refocus on close
const DRILL_LANES = 4;
const DRILL_LANE_PX = 18;

function openDrill(t, ideaId, dimId) {
  const dlg = el('drill');
  if (!dlg || !t || !boardGate(t).open) return;
  const a = document.activeElement;
  drillOpener = a && a.dataset && a.dataset.drillIdea !== undefined
    ? { kind: a.classList.contains('drow') ? 'drow' : 'pill', idea: a.dataset.drillIdea } : null;
  drillTopic = t.id;
  drillIdea = ideaId;
  drillDim = dimId || (t.dimensions[0] ? t.dimensions[0].id : null);
  drillShownNote = null;
  el('drillPlot').innerHTML = '';
  el('drillDims').innerHTML = ''; delete el('drillDims').dataset.shape;
  if (!dlg.open) dlg.showModal();
  renderDrill();
  if (dlg.open) el('drillClose').focus();
}

function closeDrill() {
  const dlg = el('drill');
  if (dlg && dlg.open) dlg.close(); // the close event resets the state
}

el('drill').addEventListener('close', () => {
  const op = drillOpener;
  drillTopic = null; drillIdea = null; drillDim = null; drillShownNote = null; drillOpener = null;
  el('drillPlot').innerHTML = '';
  if (!op) return;
  const back = [...document.querySelectorAll('#board [data-drill-idea]')]
    .find(n => n.dataset.drillIdea === op.idea && n.classList.contains(op.kind) && n.getClientRects().length > 0);
  if (back) back.focus();
});
// a click on the backdrop lands on the dialog itself (its body fills the box)
el('drill').addEventListener('click', e => { if (e.target === el('drill')) closeDrill(); });
el('drillClose').onclick = closeDrill;
el('drillNoteSave').onclick = drillSaveNote;
el('drillNote').addEventListener('keydown', e => {
  const enter = e.key === 'Enter' || e.code === 'Enter' || e.code === 'NumpadEnter';
  if (enter && (e.ctrlKey || e.metaKey)) { e.preventDefault(); drillSaveNote(); }
});

function drillSaveNote() {
  const t = currentTopic();
  if (!t || drillIdea === null || !mayAddIdea(t)) return;
  const idea = drillIdea;
  send('PUT', '/topics/' + encodeURIComponent(t.id) + '/ideas/' + encodeURIComponent(idea) + '/note',
       { participant: me(), text: el('drillNote').value }).then(j => {
    // the server's answer (trimmed text), not an optimistic copy; later frames keep it current
    if (drillIdea !== idea) return;
    el('drillNote').value = j.note;
    drillShownNote = j.note;
    renderDrill();
  }, () => {});
}

/** Split dimensions of an aggregate row: those at its widest stdev, as splitTitle names them. */
function drillSplitDims(t, f) {
  if (!f || f.split !== true) return new Set();
  const max = agreement(t, f);
  return new Set(t.dimensions.filter(d => f.byDim[d.id] && Math.abs(f.byDim[d.id].stdev - max) < 1e-9).map(d => d.id));
}

function renderDrill() {
  const dlg = el('drill');
  if (!dlg || !dlg.open) return;
  const t = loaded ? currentTopic() : undefined;
  if (!t || t.id !== drillTopic || el('board').hidden || !boardGate(t).open) { closeDrill(); return; }
  const idea = state.ideas.find(i => i.topic === t.id && i.id === drillIdea);
  const d = t.dimensions.find(x => x.id === drillDim);
  if (!idea || !d) { closeDrill(); return; }
  // gate open, idea and dimension present: the one state.ratings read in the Board slice
  const ratings = state.ratings.filter(r => r.topic === t.id && r.idea === drillIdea && r.dim === drillDim);
  const agg = state.aggregates[t.id];
  const row = agg ? agg.ideas.find(f => f.id === drillIdea) : undefined;
  el('drillTitle').textContent = idea.title;
  drillRenderDims(t, row);
  const outliers = drillOutliers(ratings);
  drillRenderPlot(t, d, ratings, row ? row.byDim[d.id] : undefined, outliers);
  drillRenderStats(row ? row.byDim[d.id] : undefined);
  drillRenderOutlier(ratings, outliers);
  drillRenderNote(t, idea);
}

function drillRenderDims(t, row) {
  const box = el('drillDims');
  const splits = drillSplitDims(t, row);
  const shape = t.dimensions.map(d => d.id + ':' + d.name + ':' + d.direction + ':' + (splits.has(d.id) ? 's' : '')).join(',');
  if (box.dataset.shape !== shape) {
    box.dataset.shape = shape;
    box.innerHTML = '';
    t.dimensions.forEach(d => {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.dim = d.id;
      const sw = document.createElement('span');
      sw.className = 'sw'; sw.style.background = dimColour(t, d);
      b.appendChild(sw);
      b.appendChild(document.createTextNode(d.name));
      if (splits.has(d.id)) {
        const sx = document.createElement('span');
        sx.className = 'dsx'; sx.textContent = 'split';
        b.appendChild(sx);
      }
      b.onclick = () => { if (drillDim !== d.id) { drillDim = d.id; renderDrill(); } };
      box.appendChild(b);
    });
  }
  for (const b of box.children) {
    const on = b.dataset.dim === drillDim;
    b.setAttribute('aria-pressed', on ? 'true' : 'false');
    const d = t.dimensions.find(x => x.id === b.dataset.dim);
    b.style.color = on && d ? dimColour(t, d) : '';
  }
}

/** Outlier set (w0i5h-D10): n >= 3, max |v - median| within 1e-9, furthest first, ties by name. */
function drillOutliers(ratings) {
  if (ratings.length < 3) return [];
  const vs = ratings.map(r => r.value).sort((a, b) => a - b);
  const n = vs.length;
  const median = n % 2 === 1 ? vs[(n - 1) / 2] : (vs[n / 2 - 1] + vs[n / 2]) / 2;
  const dist = r => Math.abs(r.value - median);
  const max = Math.max(...ratings.map(dist));
  if (max < 1e-9) return []; // everyone agrees: nobody to hear first
  return ratings.filter(r => Math.abs(dist(r) - max) < 1e-9)
    .sort((a, b) => (dist(b) - dist(a)) || (a.participant < b.participant ? -1 : a.participant > b.participant ? 1 : 0));
}

function drillRenderPlot(t, d, ratings, st, outliers) {
  const box = el('drillPlot');
  const focusedWho = document.activeElement && document.activeElement.classList.contains('drill-dot') && box.contains(document.activeElement)
    ? document.activeElement.dataset.who : null;
  box.innerHTML = '';
  if (ratings.length === 0) {
    const p = document.createElement('div');
    p.className = 'drill-empty';
    p.textContent = 'no ratings on this dimension yet';
    box.appendChild(p);
    return;
  }
  const colour = dimColour(t, d);
  const pct = v => ((v - 1) / 8) * 100;
  // deterministic lanes: sweep by value then participant; a dot within 3% of a
  // lane's previous dot tries the next lane, cycling over DRILL_LANES rows
  const dots = ratings.slice().sort((a, b) => (a.value - b.value) || (a.participant < b.participant ? -1 : a.participant > b.participant ? 1 : 0));
  const lastInLane = new Array(DRILL_LANES).fill(-Infinity);
  let prevLane = -1, maxLane = 0;
  const placed = dots.map(r => {
    const x = pct(r.value);
    let lane = -1;
    for (let k = 0; k < DRILL_LANES; k++) if (x - lastInLane[k] >= 3) { lane = k; break; }
    if (lane < 0) lane = (prevLane + 1) % DRILL_LANES;
    lastInLane[lane] = x; prevLane = lane; if (lane > maxLane) maxLane = lane;
    return { r: r, x: x, lane: lane };
  });
  const plotH = (maxLane + 1) * DRILL_LANE_PX + 8;
  const inner = document.createElement('div');
  inner.className = 'drill-inner';
  inner.style.height = (plotH + 20) + 'px';
  if (st) {
    const lo = Math.max(1, st.mean - st.stdev), hi = Math.min(9, st.mean + st.stdev);
    const band = document.createElement('div');
    band.className = 'drill-band';
    band.style.left = pct(lo) + '%'; band.style.width = Math.max(0, pct(hi) - pct(lo)) + '%';
    band.style.height = plotH + 'px'; band.style.background = colour;
    const mean = document.createElement('div');
    mean.className = 'drill-mean';
    mean.style.left = pct(st.mean) + '%'; mean.style.height = plotH + 'px'; mean.style.background = colour;
    mean.title = 'mean ' + st.mean.toFixed(1);
    inner.appendChild(band); inner.appendChild(mean);
  }
  const axis = document.createElement('div');
  axis.className = 'drill-axis'; axis.style.top = plotH + 'px';
  inner.appendChild(axis);
  for (let v = 1; v <= 9; v++) {
    const tick = document.createElement('div');
    tick.className = 'drill-tick'; tick.style.left = pct(v) + '%'; tick.style.top = plotH + 'px';
    const lbl = document.createElement('div');
    lbl.className = 'drill-tlabel'; lbl.style.left = pct(v) + '%'; lbl.style.top = (plotH + 6) + 'px';
    lbl.textContent = String(v);
    inner.appendChild(tick); inner.appendChild(lbl);
  }
  const ringed = new Set(outliers.map(r => r.participant));
  let refocus = null;
  placed.forEach(p => {
    const dot = document.createElement('div');
    dot.className = 'drill-dot' + (ringed.has(p.r.participant) ? ' outlier' : '');
    dot.tabIndex = 0;
    dot.setAttribute('role', 'img');
    const label = p.r.participant + ' · ' + p.r.value.toFixed(1);
    dot.title = label;
    dot.setAttribute('aria-label', label);
    dot.dataset.label = label;
    dot.dataset.who = p.r.participant;
    dot.style.left = p.x + '%';
    dot.style.top = (plotH - 4 - DRILL_LANE_PX / 2 - p.lane * DRILL_LANE_PX) + 'px';
    dot.style.background = colour;
    inner.appendChild(dot);
    if (focusedWho !== null && p.r.participant === focusedWho) refocus = dot;
  });
  box.appendChild(inner);
  // end anchors: the dimension's labels; with neither set the tick numbers already say 1 and 9
  if (d.lowLabel || d.highLabel) {
    const anchors = document.createElement('div');
    anchors.className = 'drill-anchors';
    const lowA = document.createElement('span'); lowA.textContent = d.lowLabel || '1';
    const highA = document.createElement('span'); highA.textContent = d.highLabel || '9';
    anchors.appendChild(lowA); anchors.appendChild(highA);
    box.appendChild(anchors);
  }
  if (refocus) refocus.focus();
}

function drillRenderStats(st) {
  const box = el('drillStats');
  if (!st) { box.textContent = 'no aggregate for this dimension yet'; return; }
  box.textContent = 'mean ' + st.mean.toFixed(1) + ' ± ' + st.stdev.toFixed(2) + ' · ' + st.n + (st.n === 1 ? ' rating' : ' ratings');
}

function drillRenderOutlier(ratings, outliers) {
  const box = el('drillOutlier');
  box.classList.toggle('hear', outliers.length > 0);
  if (ratings.length < 3) { box.textContent = 'need at least three ratings to name an outlier'; return; }
  if (outliers.length === 0) { box.textContent = 'everyone gave the same rating: no outlier to hear first'; return; }
  box.textContent = 'hear first: ' + outliers.map(r => r.participant + ' (' + r.value.toFixed(1) + ')').join(', ');
}

function drillRenderNote(t, idea) {
  const area = el('drillNote');
  const may = mayAddIdea(t);
  const text = idea.note || '';
  const by = idea.noteBy || '';
  area.readOnly = !may;
  el('drillNoteSave').hidden = !may;
  let held = false;
  if (text !== drillShownNote) {
    if (!may || !editing(el('drill'))) { area.value = text; drillShownNote = text; }
    else held = true; // the viewer is typing: hold the frame's text, never overwrite it
  }
  let line = text ? 'recorded by ' + by : 'no decision recorded yet';
  if (held) line += ' · changed by ' + (by || 'someone') + ' while you were typing; saving overwrites it';
  if (!may) line += ' · the facilitator records the decision';
  el('drillNoteBy').textContent = line;
}
</script>
"""
