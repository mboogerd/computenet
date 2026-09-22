package civictech.demo.alignment

/**
 * The Board view (computenet-v48mn.2, carried into the v2 shell by computenet-0dvra.1, gated
 * and legend-ified by computenet-0dvra.4, rows/Discuss/spread mode by computenet-10mvq.2), one
 * slice of the served [PAGE]: its CSS, the `<section id="board">` markup and the `<script>`
 * defining `renderBoard()`.
 *
 * `#gate` (0dvra-D10): while `boardGate(t)` is not open, the card explains why — "rate
 * everything" with the viewer's own `rated`/`total`, or "waiting for the facilitator to
 * reveal" — and `#weights`/`#boardMode`/`#ranking`/`#discuss` stay hidden. `boardGate(t).pending`
 * (set until the viewer's `/me` is cached) is treated as UNKNOWN, not closed: the gate renders
 * nothing and `lastOpen` is left untouched, so the reveal animation never replays on a page load
 * where the board was already open before this session.
 *
 * `#weights` is a read-only legend for every viewer (swatch, name, weight, value/cost label) —
 * no input, no `PUT /weights`, no creator branch. `#ranking` (rows keyed by idea id and reused,
 * translated to their rank with a CSS transition) is otherwise v1 behaviour, made null-safe: a
 * segment whose `byDim[d].contribution` is `null` (a cost dimension, or a score-null row) gets
 * zero width and no title instead of throwing on `.toFixed`. Each row (10mvq-D3/D9/D11) also
 * carries a one-decimal score, a muted "N of M rated" line, a "value · cost" line when the topic
 * has a cost dimension, a "÷ cost" badge on the bar, and an agreement indicator (the split pill,
 * "agreed" or "mixed") for every row with rated data. `#discuss` (10mvq-D5) lists the split ideas
 * below the ranking. `#boardMode` (10mvq-D10) switches the bar cell between the score bars and a
 * spread view: a 1–9 axis with a mean±stdev band per dimension. On a false→true flip of
 * `boardGate(t).open` within a session (0dvra-D16), cached rows are dropped so every row
 * re-enters with a staggered fade/slide and the legend fades in; `prefers-reduced-motion:
 * reduce` disables the stagger and the slide.
 *
 * `renderBoard()` reads `currentTopic()`, `state.aggregates[t]`, `boardGate(t)` and, for the
 * Discuss rows' "decided: …" line (w0i5h-D12), the idea's `note` in `state.ideas`. `state.ratings`
 * — every participant's raw ratings, and so their names — is read in exactly one place in the
 * Board slice: `renderDrill()` in [DRILLDOWN_VIEW] (DrilldownView.kt), inside its
 * `boardGate(t).open` check (w0i5h-D8); nothing else here reads it or renders a participant name.
 * Bar segments are the API's `contribution`s, never recomputed; the bar scale is the topic's max ranked score. Spread bands and the agreement
 * indicator are layout/selection over the API's `n`/`mean`/`stdev`/`split` values, never a
 * recomputed statistic. Shared helper contract: the comment block at the top of the shell's
 * script in [AlignmentPage.kt]. No `$` anywhere.
 *
 * `#scatter` (computenet-10mvq.3, 10mvq-D12/D13) is the value-vs-cost scatter with the Pareto
 * frontier, defined in [SCATTER_VIEW] (ScatterView.kt) and concatenated onto [BOARD_MAIN] below
 * to form [BOARD_VIEW]; its root lives here, after `#discuss`, and is hidden together with the
 * board's other roots while the gate is closed. `renderBoard()` calls `renderScatter(t, ideas)`
 * when the gate is open.
 *
 * The split drill-down (computenet-w0i5h.2, w0i5h-D6/D7) is [DRILLDOWN_VIEW], concatenated after
 * [SCATTER_VIEW]: a row's split pill and each `#discuss` row are button-like (focusable, click or
 * Enter/Space) and call `openDrill(t, f.id, firstSplitDim(t, f))`. `renderBoard()` calls
 * `renderDrill()` after `renderScatter` while the gate is open and `closeDrill()` on each early
 * return (no topic, pending, closed), so the dialog is closed and unopenable while the Board is
 * gated for the viewer — the gate itself (`boardGate`, computenet-aa6gl's parked rule) is
 * consumed as-is.
 */
internal const val BOARD_MAIN = """
<style>
  #weights { display: flex; flex-wrap: wrap; gap: .4rem 1.1rem; align-items: center; font-size: var(--fs-2);
             padding-bottom: .7rem; margin-bottom: .4rem; border-bottom: 1px solid var(--line);
             transition: opacity .3s; }
  #weights.legend-enter { opacity: 0; }
  #weights .wlabel { color: var(--muted); font-size: var(--fs-1); }
  #weights .wcell { display: flex; align-items: center; gap: .35rem; }
  #weights .sw { width: .7rem; height: .7rem; border-radius: 3px; flex: 0 0 auto; }
  #weights .wdir { color: var(--muted); font-size: var(--fs-1); text-transform: uppercase; letter-spacing: .03em; }
  #weights .wv { font-variant-numeric: tabular-nums; color: var(--muted); }
  #gate .gate-card { text-align: center; padding: 2rem 1.5rem; }
  #gate .gate-card h3 { margin: 0 0 .4rem; }
  #gate .gate-card p { margin: 0; }
  #boardMode { display: flex; gap: .35rem; margin-bottom: .6rem; }
  #boardMode[hidden] { display: none; }
  #boardMode button { font-size: .75rem; padding: .25rem .7rem; border-radius: 999px; border: 1px solid var(--line);
                       background: var(--surface); color: var(--muted); cursor: pointer; }
  #boardMode button[aria-pressed="true"] { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
  #ranking { position: relative; }
  .rankrow { position: absolute; left: 0; right: 0; top: 0; height: 58px; background: var(--surface);
             box-sizing: border-box; display: flex; flex-direction: column; justify-content: center;
             gap: .15rem; padding: .3rem .3rem; font-size: var(--fs-2);
             border-bottom: 1px solid var(--line); will-change: transform;
             transition: transform .55s cubic-bezier(.22,1,.36,1), opacity .3s; }
  .rankrow.moving { z-index: 2; }
  .rankrow.enter, .rankrow.leave { opacity: 0; }
  .rankrow .main { display: grid; grid-template-columns: 1.6rem minmax(0,1fr) minmax(6rem,38%) 3rem 3.4rem;
                    gap: .6rem; align-items: center; }
  .rankrow .pos { text-align: right; font-weight: 700; color: var(--muted); font-variant-numeric: tabular-nums; }
  .rankrow .ttl { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rankrow .barcell { position: relative; min-width: 0; }
  .rankrow .stackwrap { display: flex; align-items: center; gap: .35rem; min-width: 0; }
  .rankrow .stack { flex: 1 1 auto; display: flex; height: .6rem; background: var(--track); border-radius: 4px; overflow: hidden; min-width: 0; }
  .rankrow .seg { height: 100%; transition: width .55s; }
  .rankrow .costbadge { flex: 0 0 auto; font-size: .65rem; padding: .05rem .4rem; border: 1px solid var(--cost-1);
                         color: var(--cost-1); border-radius: 4px; white-space: nowrap; }
  .rankrow .costbadge[hidden] { display: none; }
  .rankrow .spread { display: flex; flex-direction: column; justify-content: center; gap: 2px; min-width: 0; }
  .rankrow .spread[hidden] { display: none; }
  .rankrow .axisrow { position: relative; height: 4px; background: var(--track); border-radius: 2px; }
  .rankrow .axisguide { position: absolute; left: 50%; top: -3px; bottom: -3px; width: 1px; background: var(--line); }
  .rankrow .axisband { position: absolute; top: 0; bottom: 0; border-radius: 2px; }
  .rankrow .axisband.lo { opacity: .45; }
  .rankrow .axisband.hi { opacity: 1; }
  .rankrow .axistick { position: absolute; top: -2px; bottom: -2px; width: 2px; margin-left: -1px; background: var(--ink); }
  .rankrow .score { text-align: right; font-variant-numeric: tabular-nums; }
  .rankrow .pill { justify-self: start; font-size: .7rem; padding: .1rem .5rem; border-radius: 8px; white-space: nowrap; }
  .rankrow .pill.split { background: var(--warn-soft); color: var(--warn); }
  .rankrow .pill.agreed { color: var(--ok); border: 1px solid var(--ok); background: transparent; }
  .rankrow .pill.mixed { color: var(--muted); border: 1px solid var(--line); background: transparent; }
  .rankrow .pill[hidden] { display: none; }
  .rankrow .sub { display: flex; flex-direction: column; gap: .05rem; margin-left: calc(1.6rem + .6rem);
                  font-size: .72rem; color: var(--muted); }
  .rankrow .sub div[hidden] { display: none; }
  .rankrow.unranked { opacity: .5; }
  .rankrow.unranked .stack { visibility: hidden; } /* an unscored idea has no bar, not an empty one */
  .rankrow.unranked.enter, .rankrow.unranked.leave { opacity: 0; }
  #discuss { margin-top: 1rem; padding-top: .7rem; border-top: 1px solid var(--line); }
  #discuss[hidden] { display: none; }
  #discuss h3 { margin: 0 0 .5rem; font-size: var(--fs-2); }
  #discuss .drow { padding: .35rem 0; border-bottom: 1px solid var(--line); font-size: var(--fs-2); }
  #discuss .drow:last-child { border-bottom: none; }
  #discuss .drow .dttl { font-weight: 600; }
  #discuss .drow .dsplit { color: var(--muted); font-size: var(--fs-1); margin-top: .15rem; }
  #discuss .drow { cursor: pointer; border-radius: 4px; }
  #discuss .drow:hover .dttl { color: var(--accent); }
  #discuss .drow:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
  #discuss .drow .dnote { font-size: var(--fs-1); margin-top: .15rem; color: var(--ink);
                          white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  #discuss .drow .dnote[hidden] { display: none; }
  .rankrow .pill.split { cursor: pointer; }
  .rankrow .pill.split:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
  #board .note { color: var(--muted); font-size: var(--fs-1); margin-top: .6rem; }
  @media (prefers-reduced-motion: reduce) {
    #weights { transition: none; }
    .rankrow { transition: none; }
  }
</style>
<section id="board" class="pane view" hidden>
  <div id="gate" hidden></div>
  <div id="weights"></div>
  <div id="boardMode" hidden>
    <button type="button" data-mode="score" aria-pressed="true">score</button>
    <button type="button" data-mode="spread" aria-pressed="false">spread</button>
  </div>
  <div id="ranking"></div>
  <div id="discuss" hidden><h3>Discuss</h3></div>
  <div id="scatter" hidden></div>
  <p class="note">Bar segments show each dimension's weighted contribution to the score; a ÷ badge shows the cost divisor. The indicator marks how split the team is on an idea; Discuss lists the split ideas — open a split marker or a Discuss row to see every rating and record what the team decided. Switch to spread to see each dimension's rated range.</p>
</section>
<script>
// ── Board: the aggregate view ──────────────────────────────────────────────
// Reads currentTopic(), state.aggregates[t], boardGate(t) and the ideas'
// notes in state.ideas. state.ratings (and so any participant name) is read
// only by renderDrill() in the drill-down slice, inside its boardGate(t).open
// check — never by the code in this script. Ranked rows
// arrive server-sorted (score desc, rating count desc, id asc) followed by
// the unscored ones; the page never re-sorts and never recomputes a score, a
// contribution, a mean or a stdev.
const ROW = 58; // px, keep in sync with .rankrow height
const boardRows = new Map(); // topicId + '/' + ideaId -> reused row node
let boardIndex = new Map();
const lastOpen = {}; // topicId -> last known boardGate(t).open (undefined: never determined)
let boardMode = 'score'; // 'score' | 'spread', persisted below
try { if (sessionStorage.boardMode === 'spread') boardMode = 'spread'; } catch (e) { /* ignore */ }

function updateBoardModeButtons() {
  document.querySelectorAll('#boardMode button').forEach(b => {
    b.setAttribute('aria-pressed', b.dataset.mode === boardMode ? 'true' : 'false');
  });
}
updateBoardModeButtons();
document.querySelectorAll('#boardMode button').forEach(b => {
  b.addEventListener('click', () => {
    if (b.dataset.mode === boardMode) return;
    boardMode = b.dataset.mode;
    try { sessionStorage.boardMode = boardMode; } catch (e) { /* ignore */ }
    updateBoardModeButtons();
    renderBoard(); // re-render from the same state, no fetch
  });
});

function hasCostDim(t) { return t.dimensions.some(d => d.direction === 'cost'); }

function renderBoard() {
  const gateBox = document.getElementById('gate');
  const weightsBox = document.getElementById('weights');
  const modeBox = document.getElementById('boardMode');
  const rankingBox = document.getElementById('ranking');
  const discussBox = document.getElementById('discuss');
  const scatterBox = document.getElementById('scatter');
  const noteEl = document.querySelector('#board .note');
  const t = loaded ? currentTopic() : undefined;
  if (!t) {
    gateBox.hidden = true; gateBox.innerHTML = '';
    weightsBox.hidden = true; weightsBox.innerHTML = ''; delete weightsBox.dataset.shape;
    modeBox.hidden = true;
    rankingBox.hidden = true;
    discussBox.hidden = true;
    if (scatterBox) scatterBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    closeDrill();
    return;
  }
  const g = boardGate(t);
  if (g.pending) {
    // pending is UNKNOWN, not closed (AMENDS c): render nothing definitive and
    // never touch lastOpen, so the real state — once /me lands — cannot read
    // as a false-to-true flip and replay the reveal animation on page load.
    gateBox.hidden = true;
    weightsBox.hidden = true;
    modeBox.hidden = true;
    rankingBox.hidden = true;
    discussBox.hidden = true;
    if (scatterBox) scatterBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    closeDrill();
    return;
  }
  const was = lastOpen[t.id];
  const justOpened = was === false && g.open === true;
  lastOpen[t.id] = g.open;
  if (!g.open) {
    gateBox.hidden = false;
    weightsBox.hidden = true;
    modeBox.hidden = true;
    rankingBox.hidden = true;
    discussBox.hidden = true;
    if (scatterBox) scatterBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    renderGate(gateBox, g);
    closeDrill();
    return;
  }
  gateBox.hidden = true;
  weightsBox.hidden = false;
  modeBox.hidden = false;
  rankingBox.hidden = false;
  if (noteEl) noteEl.hidden = false;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (justOpened) {
    // drop cached rows so every row re-enters fresh, staggered below
    for (const row of boardRows.values()) row.remove();
    boardRows.clear();
    if (!reduceMotion) {
      weightsBox.style.transition = 'none';
      weightsBox.classList.add('legend-enter');
    }
  }
  renderLegend(t);
  if (justOpened && !reduceMotion) {
    void getComputedStyle(weightsBox).opacity;
    weightsBox.style.transition = '';
    weightsBox.classList.remove('legend-enter');
  }
  const agg = state.aggregates[t.id];
  const ideas = agg ? agg.ideas : [];
  const participants = agg ? agg.participants : 0;
  renderRanking(t, ideas, participants, justOpened && !reduceMotion);
  renderDiscuss(t, ideas);
  renderScatter(t, ideas);
  renderDrill();
}

function renderGate(box, g) {
  box.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'card gate-card';
  const h = document.createElement('h3');
  const p = document.createElement('p');
  p.className = 'muted';
  if (g.reason === 'reveal') {
    h.textContent = 'waiting for the facilitator to reveal';
    p.textContent = 'you have rated everything';
  } else {
    h.textContent = 'rate everything to see the board';
    p.textContent = g.rated + ' of ' + g.total + ' rated';
  }
  card.appendChild(h); card.appendChild(p);
  box.appendChild(card);
}

function renderLegend(t) {
  const box = document.getElementById('weights');
  const shape = t.id + '|' + t.dimensions.map(d => d.id + ':' + d.name + ':' + d.direction + ':' + d.weight).join(',');
  if (box.dataset.shape === shape) return;
  box.dataset.shape = shape;
  box.innerHTML = '';
  const lbl = document.createElement('span');
  lbl.className = 'wlabel';
  lbl.textContent = 'weights';
  box.appendChild(lbl);
  t.dimensions.forEach(d => {
    const cell = document.createElement('span');
    cell.className = 'wcell';
    const sw = document.createElement('span');
    sw.className = 'sw'; sw.style.background = dimColour(t, d);
    const name = document.createElement('span');
    name.textContent = d.name;
    const dir = document.createElement('span');
    dir.className = 'wdir';
    dir.textContent = d.direction === 'cost' ? 'cost' : 'value';
    const v = document.createElement('span');
    v.className = 'wv';
    v.textContent = (d.weight === null || d.weight === undefined) ? '—' : String(d.weight);
    cell.appendChild(sw); cell.appendChild(name); cell.appendChild(dir); cell.appendChild(v);
    box.appendChild(cell);
  });
}

/** The widest per-dimension stdev in idea.byDim, or -1 when byDim is empty (D9, D6 carry-over). */
function agreement(t, idea) {
  let max = -1;
  for (const d of t.dimensions) { const s = idea.byDim[d.id]; if (s && s.stdev > max) max = s.stdev; }
  return max;
}

/** "split on Impact (stdev 2.83)": the dimension(s) with the row's widest spread (D6). */
function splitTitle(t, idea) {
  const max = agreement(t, idea);
  const names = t.dimensions.filter(d => idea.byDim[d.id] && Math.abs(idea.byDim[d.id].stdev - max) < 1e-9).map(d => d.name);
  return 'split on ' + names.join(', ') + ' (stdev ' + max.toFixed(2) + ')';
}

/** The first dimension, in dimension order, at the row's widest stdev — the set splitTitle names (w0i5h-D7). */
function firstSplitDim(t, idea) {
  const max = agreement(t, idea);
  const d = t.dimensions.find(x => idea.byDim[x.id] && Math.abs(idea.byDim[x.id].stdev - max) < 1e-9);
  return d ? d.id : (t.dimensions[0] ? t.dimensions[0].id : null);
}

/** Opens the drill-down for an idea from the current frame's aggregate row (split pill, Discuss row). */
function boardOpenDrill(ideaId) {
  const t = currentTopic();
  const agg = t ? state.aggregates[t.id] : undefined;
  const f = agg ? agg.ideas.find(x => x.id === ideaId) : undefined;
  if (f) openDrill(t, f.id, firstSplitDim(t, f));
}

/** Makes node a button-like drill-down opener: click, Enter or Space (w0i5h-D7). */
function boardDrillOpener(node, ideaId, gated) {
  node.dataset.drillIdea = ideaId;
  node.addEventListener('click', () => { if (!gated || gated()) boardOpenDrill(ideaId); });
  node.addEventListener('keydown', e => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (gated && !gated()) return;
    e.preventDefault();
    boardOpenDrill(ideaId);
  });
}

/** Appends "split on <coloured dim names> (stdev s.ss)" as text + coloured spans (D5). */
function appendSplitTitle(container, t, idea) {
  const max = agreement(t, idea);
  const dims = t.dimensions.filter(d => idea.byDim[d.id] && Math.abs(idea.byDim[d.id].stdev - max) < 1e-9);
  container.appendChild(document.createTextNode('split on '));
  dims.forEach((d, i) => {
    if (i > 0) container.appendChild(document.createTextNode(', '));
    const span = document.createElement('span');
    span.textContent = d.name;
    span.style.color = dimColour(t, d);
    container.appendChild(span);
  });
  container.appendChild(document.createTextNode(' (stdev ' + max.toFixed(2) + ')'));
}

function renderRanking(t, ideas, participants, stagger) {
  const box = document.getElementById('ranking');
  const dims = t ? t.dimensions : [];
  const costy = t ? hasCostDim(t) : false;
  const scores = ideas.filter(f => f.score !== null).map(f => f.score);
  const max = scores.length ? Math.max(...scores) : 0; // D10 (v48mn): the top ranked score fills the bar
  const pct = v => ((v - 1) / 8) * 100; // 1..9 axis to 0..100%
  box.style.height = (ideas.length * ROW) + 'px';
  const seen = new Set();
  ideas.forEach((f, i) => {
    const key = t.id + '/' + f.id;
    seen.add(key);
    let row = boardRows.get(key), fresh = false;
    if (!row) {
      fresh = true;
      row = document.createElement('div');
      row.className = 'rankrow enter';
      row.innerHTML = '<div class="main">' +
                         '<div class="pos"></div><div class="ttl"></div>' +
                         '<div class="barcell">' +
                           '<div class="stackwrap"><div class="stack"></div><span class="costbadge" hidden></span></div>' +
                           '<div class="spread" hidden></div>' +
                         '</div>' +
                         '<div class="score"></div><span class="pill" hidden></span>' +
                       '</div>' +
                       '<div class="sub"><div class="raters" hidden></div><div class="vc" hidden></div><div class="reason" hidden></div></div>';
      // place without transition, flush that style, then fade in. A forced
      // style flush rather than backlog-triage's double requestAnimationFrame:
      // rAF is paused in a background tab, which would leave a fresh row
      // invisible there until the tab is shown.
      row.style.transition = 'none';
      row.style.transform = 'translateY(' + (i * ROW) + 'px)';
      if (stagger) {
        // 0dvra-D16: stagger this reveal's entrance only — the delay is
        // dropped once its own transition ends, so a later reorder of this
        // same row is never staggered.
        row.style.transitionDelay = (i * 40) + 'ms';
        row.addEventListener('transitionend', () => { row.style.transitionDelay = ''; }, { once: true });
      }
      const pillNode = row.querySelector('.pill');
      boardDrillOpener(pillNode, f.id, () => pillNode.classList.contains('split'));
      boardRows.set(key, row); box.appendChild(row);
      void getComputedStyle(row).opacity;
      row.style.transition = ''; row.classList.remove('enter');
    }
    row.style.transform = 'translateY(' + (i * ROW) + 'px)';
    const was = boardIndex.get(key);
    if (!fresh && was !== undefined && was !== i) {
      row.classList.add('moving'); // moving rows cross above resting ones
      clearTimeout(row._movingT);
      row._movingT = setTimeout(() => row.classList.remove('moving'), 600);
    }
    const ranked = f.rank !== null;
    const byDimKeys = Object.keys(f.byDim || {});
    const hasData = byDimKeys.length > 0;
    row.classList.toggle('unranked', !ranked);
    const ttl = row.querySelector('.ttl');
    ttl.textContent = f.title; ttl.title = f.title;

    // score bar: one segment per dimension, in dimension order; rebuilt only when the dimensions change
    const stack = row.querySelector('.stack');
    const spread = row.querySelector('.spread');
    const segShape = dims.map(d => d.id + ':' + d.direction).join(',');
    if (stack.dataset.shape !== segShape) {
      stack.dataset.shape = segShape;
      stack.innerHTML = '';
      dims.forEach(d => {
        const seg = document.createElement('div');
        seg.className = 'seg'; seg.style.background = dimColour(t, d); seg.style.width = '0';
        stack.appendChild(seg);
      });
    }
    if (spread.dataset.shape !== segShape) {
      spread.dataset.shape = segShape;
      spread.innerHTML = '';
      dims.forEach(() => {
        const axisrow = document.createElement('div');
        axisrow.className = 'axisrow';
        const guide = document.createElement('div'); guide.className = 'axisguide';
        const lo = document.createElement('div'); lo.className = 'axisband lo';
        const hi = document.createElement('div'); hi.className = 'axisband hi';
        const tick = document.createElement('div'); tick.className = 'axistick';
        axisrow.appendChild(guide); axisrow.appendChild(lo); axisrow.appendChild(hi); axisrow.appendChild(tick);
        spread.appendChild(axisrow);
      });
    }
    dims.forEach((d, j) => {
      const seg = stack.children[j];
      const s = ranked ? f.byDim[d.id] : undefined;
      // null-safe (0dvra-D7 carry-over hazard): contribution is null for a
      // cost dimension and for every dimension of a score-null row — a zero
      // segment with no title, never a throw on .toFixed.
      if (s && s.contribution !== null && s.contribution !== undefined && max > 0) {
        seg.style.width = (100 * s.contribution / max) + '%';
        seg.title = d.name + ': ' + s.contribution.toFixed(2);
      } else {
        seg.style.width = '0'; seg.removeAttribute('title');
      }
      const axisrow = spread.children[j];
      const lo = axisrow.querySelector('.axisband.lo');
      const hi = axisrow.querySelector('.axisband.hi');
      const tick = axisrow.querySelector('.axistick');
      const st = f.byDim[d.id];
      if (!st) {
        lo.style.width = '0'; hi.style.width = '0'; tick.style.left = '-999px';
        axisrow.removeAttribute('title');
        return;
      }
      const colour = dimColour(t, d);
      const bandLo = Math.max(1, st.mean - st.stdev);
      const bandHi = Math.min(9, st.mean + st.stdev);
      const loP = pct(bandLo), hiP = pct(bandHi), meanP = pct(st.mean);
      const aFrom = loP, aTo = Math.min(hiP, 50);
      const bFrom = Math.max(loP, 50), bTo = hiP;
      lo.style.left = aFrom + '%'; lo.style.width = Math.max(0, aTo - aFrom) + '%'; lo.style.background = colour;
      hi.style.left = bFrom + '%'; hi.style.width = Math.max(0, bTo - bFrom) + '%'; hi.style.background = colour;
      tick.style.left = meanP + '%'; tick.style.background = colour;
      axisrow.title = d.name + ': mean ' + st.mean.toFixed(1) + ' ± ' + st.stdev.toFixed(2) + ' (' + st.n + ')';
    });

    // score family (D3/D11) vs spread family (D10): only one shown at a time; score cell,
    // sub-lines and the indicator stay the same in either mode.
    const stackwrap = row.querySelector('.stackwrap');
    if (boardMode === 'spread') {
      stackwrap.hidden = true;
      spread.hidden = !hasData;
    } else {
      stackwrap.hidden = false;
      spread.hidden = true;
    }
    const badge = row.querySelector('.costbadge');
    if (ranked && costy) {
      badge.hidden = false;
      const costTxt = f.cost === null ? '—' : f.cost.toFixed(1);
      badge.textContent = '÷ ' + costTxt;
      const valueTxt = f.value === null ? '—' : f.value.toFixed(1);
      badge.title = 'value ' + valueTxt + ' ÷ cost ' + costTxt + ' = ' + f.score.toFixed(1);
    } else {
      badge.hidden = true;
      badge.removeAttribute('title');
    }
    row.querySelector('.score').textContent = ranked ? f.score.toFixed(1) : '—';

    // sub-lines (D3): "N of M rated" + "value · cost" for a ranked row; the not-rated-yet
    // reason for an unranked row that still carries data; nothing for a truly empty row.
    const ratersEl = row.querySelector('.raters');
    const vcEl = row.querySelector('.vc');
    const reasonEl = row.querySelector('.reason');
    if (ranked) {
      ratersEl.hidden = false;
      ratersEl.textContent = f.raters + ' of ' + participants + ' rated';
      if (costy) {
        vcEl.hidden = false;
        const valueTxt = f.value === null ? '—' : f.value.toFixed(1);
        const costTxt = f.cost === null ? '—' : f.cost.toFixed(1);
        vcEl.textContent = 'value ' + valueTxt + ' · cost ' + costTxt;
      } else {
        vcEl.hidden = true;
      }
      reasonEl.hidden = true;
    } else {
      ratersEl.hidden = true;
      vcEl.hidden = true;
      if (hasData) {
        reasonEl.hidden = false;
        reasonEl.textContent = f.value === null ? 'value not rated yet' : (f.cost === null ? 'cost not rated yet' : '');
      } else {
        reasonEl.hidden = true;
      }
    }
    row.querySelector('.pos').textContent = ranked ? String(f.rank) : '·';

    // agreement indicator (D9): every row with rated data, ranked or not.
    const indicator = row.querySelector('.pill');
    const opens = hasData && f.split === true;
    if (opens) {
      indicator.setAttribute('role', 'button');
      indicator.tabIndex = 0;
    } else {
      indicator.removeAttribute('role');
      indicator.removeAttribute('tabindex');
    }
    if (!hasData) {
      indicator.hidden = true;
      indicator.className = 'pill';
      indicator.removeAttribute('title');
    } else {
      indicator.hidden = false;
      if (f.split === true) {
        indicator.className = 'pill split';
        indicator.textContent = 'split';
        indicator.title = splitTitle(t, f) + ' — open to see every rating';
      } else {
        const a = agreement(t, f);
        if (a <= 1.0) {
          indicator.className = 'pill agreed';
          indicator.textContent = 'agreed';
        } else {
          indicator.className = 'pill mixed';
          indicator.textContent = 'mixed';
        }
        indicator.title = 'widest stdev ' + a.toFixed(2);
      }
    }
  });
  for (const [key, row] of boardRows) if (!seen.has(key)) {
    boardRows.delete(key);
    row.classList.add('leave');
    setTimeout(() => row.remove(), 350);
  }
  boardIndex = t ? new Map(ideas.map((f, i) => [t.id + '/' + f.id, i])) : new Map();
}

/**
 * #discuss (D5): every split idea, in ranking order, with its title, coloured splitTitle and —
 * when the idea has a note in state.ideas — a "decided: …" line (w0i5h-D12). Each row opens the
 * drill-down (w0i5h-D7); a row focused before the rebuild is focused again after it.
 */
function renderDiscuss(t, ideas) {
  const box = document.getElementById('discuss');
  if (!box) return;
  const splitIdeas = ideas.filter(f => f.split === true);
  const a = document.activeElement;
  const focusedIdea = a && a.classList && a.classList.contains('drow') && box.contains(a) ? a.dataset.drillIdea : null;
  box.querySelectorAll('.drow').forEach(n => n.remove());
  if (splitIdeas.length === 0) { box.hidden = true; return; }
  box.hidden = false;
  splitIdeas.forEach(f => {
    const row = document.createElement('div');
    row.className = 'drow';
    row.setAttribute('role', 'button');
    row.tabIndex = 0;
    const ttl = document.createElement('div');
    ttl.className = 'dttl';
    ttl.textContent = f.title;
    const sp = document.createElement('div');
    sp.className = 'dsplit';
    appendSplitTitle(sp, t, f);
    const nt = document.createElement('div');
    nt.className = 'dnote';
    const idea = state.ideas.find(i => i.topic === t.id && i.id === f.id);
    const text = idea && idea.note ? idea.note : '';
    if (text) {
      nt.textContent = 'decided: ' + (text.length > 120 ? text.slice(0, 120) + '…' : text);
      nt.title = text;
    } else {
      nt.hidden = true;
    }
    row.appendChild(ttl); row.appendChild(sp); row.appendChild(nt);
    boardDrillOpener(row, f.id, null);
    box.appendChild(row);
    if (focusedIdea === f.id) row.focus();
  });
}
</script>
"""

/**
 * [BOARD_MAIN] plus [SCATTER_VIEW] (ScatterView.kt) plus [DRILLDOWN_VIEW] (DrilldownView.kt), the
 * Board view's full slice served as part of [PAGE]. A plain `val` (like [PAGE] itself), not `const val`: the two concatenated raw
 * strings would otherwise risk the 65535-byte constant-pool cap as the Board grows.
 */
internal val BOARD_VIEW: String = BOARD_MAIN + SCATTER_VIEW + DRILLDOWN_VIEW
