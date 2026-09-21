package civictech.demo.alignment

/**
 * The Board view (computenet-v48mn.2, carried into the v2 shell by computenet-0dvra.1, gated
 * and legend-ified by computenet-0dvra.4), one slice of the served [PAGE]: its CSS, the
 * `<section id="board">` markup and the `<script>` defining `renderBoard()`.
 *
 * `#gate` (0dvra-D10): while `boardGate(t)` is not open, the card explains why — "rate
 * everything" with the viewer's own `rated`/`total`, or "waiting for the facilitator to
 * reveal" — and `#weights`/`#ranking` stay hidden. `boardGate(t).pending` (set until the
 * viewer's `/me` is cached) is treated as UNKNOWN, not closed: the gate renders nothing and
 * `lastOpen` is left untouched, so the reveal animation never replays on a page load where the
 * board was already open before this session.
 *
 * `#weights` is a read-only legend for every viewer (swatch, name, weight, value/cost label) —
 * no input, no `PUT /weights`, no creator branch. `#ranking` (rows keyed by idea id and reused,
 * translated to their rank with a CSS transition) is otherwise v1 behaviour, made null-safe: a
 * segment whose `byDim[d].contribution` is `null` (a cost dimension, or a score-null row) gets
 * zero width and no title instead of throwing on `.toFixed`. On a false→true flip of
 * `boardGate(t).open` within a session (0dvra-D16), cached rows are dropped so every row
 * re-enters with a staggered fade/slide and the legend fades in; `prefers-reduced-motion:
 * reduce` disables the stagger and the slide.
 *
 * `renderBoard()` reads only `currentTopic()`, `state.aggregates[t]` and `boardGate(t)` — never
 * `state.ratings`, never a participant name. Bar segments are the API's `contribution`s, never
 * recomputed; the bar scale is the topic's max ranked score. Shared helper contract: the
 * comment block at the top of the shell's script in [AlignmentPage.kt]. No `$` anywhere.
 */
internal const val BOARD_VIEW = """
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
  #ranking { position: relative; }
  .rankrow { position: absolute; left: 0; right: 0; top: 0; height: 36px; background: var(--surface);
             display: grid; grid-template-columns: 1.6rem minmax(0,1fr) minmax(6rem,38%) 3rem 3.4rem;
             gap: .6rem; align-items: center; padding: 0 .3rem; font-size: var(--fs-2);
             border-bottom: 1px solid var(--line); will-change: transform;
             transition: transform .55s cubic-bezier(.22,1,.36,1), opacity .3s; }
  .rankrow.moving { z-index: 2; }
  .rankrow.enter, .rankrow.leave { opacity: 0; }
  .rankrow .pos { text-align: right; font-weight: 700; color: var(--muted); font-variant-numeric: tabular-nums; }
  .rankrow .ttl { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rankrow .stack { display: flex; height: .6rem; background: var(--track); border-radius: 4px; overflow: hidden; }
  .rankrow .seg { height: 100%; transition: width .55s; }
  .rankrow .score { text-align: right; font-variant-numeric: tabular-nums; }
  .rankrow .pill { justify-self: start; font-size: .7rem; padding: .1rem .5rem; border-radius: 8px; background: var(--warn-soft); color: var(--warn); }
  .rankrow .pill[hidden] { display: none; }
  .rankrow.unranked { opacity: .5; }
  .rankrow.unranked .stack { visibility: hidden; } /* an unscored idea has no bar, not an empty one */
  .rankrow.unranked.enter, .rankrow.unranked.leave { opacity: 0; }
  #board .note { color: var(--muted); font-size: var(--fs-1); margin-top: .6rem; }
  @media (prefers-reduced-motion: reduce) {
    #weights { transition: none; }
    .rankrow { transition: none; }
  }
</style>
<section id="board" class="pane view" hidden>
  <div id="gate" hidden></div>
  <div id="weights"></div>
  <div id="ranking"></div>
  <p class="note">Bar segments show each dimension's weighted contribution to the score. "split" marks ideas the team disagrees on.</p>
</section>
<script>
// ── Board: the aggregate view ──────────────────────────────────────────────
// Reads currentTopic(), state.aggregates[t] and boardGate(t) only — never
// state.ratings — so no participant name is ever rendered here. Ranked rows
// arrive server-sorted (score desc, rating count desc, id asc) followed by
// the unscored ones; the page never re-sorts and never recomputes a score or
// a contribution.
const ROW = 36; // px, keep in sync with .rankrow height
const boardRows = new Map(); // topicId + '/' + ideaId -> reused row node
let boardIndex = new Map();
const lastOpen = {}; // topicId -> last known boardGate(t).open (undefined: never determined)

function renderBoard() {
  const gateBox = document.getElementById('gate');
  const weightsBox = document.getElementById('weights');
  const rankingBox = document.getElementById('ranking');
  const noteEl = document.querySelector('#board .note');
  const t = loaded ? currentTopic() : undefined;
  if (!t) {
    gateBox.hidden = true; gateBox.innerHTML = '';
    weightsBox.hidden = true; weightsBox.innerHTML = ''; delete weightsBox.dataset.shape;
    rankingBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    return;
  }
  const g = boardGate(t);
  if (g.pending) {
    // pending is UNKNOWN, not closed (AMENDS c): render nothing definitive and
    // never touch lastOpen, so the real state — once /me lands — cannot read
    // as a false-to-true flip and replay the reveal animation on page load.
    gateBox.hidden = true;
    weightsBox.hidden = true;
    rankingBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    return;
  }
  const was = lastOpen[t.id];
  const justOpened = was === false && g.open === true;
  lastOpen[t.id] = g.open;
  if (!g.open) {
    gateBox.hidden = false;
    weightsBox.hidden = true;
    rankingBox.hidden = true;
    if (noteEl) noteEl.hidden = true;
    renderGate(gateBox, g);
    return;
  }
  gateBox.hidden = true;
  weightsBox.hidden = false;
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
  renderRanking(t, agg ? agg.ideas : [], justOpened && !reduceMotion);
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

/** "split on Impact (stdev 2.83)": the dimension(s) with the row's widest spread (D6). */
function splitTitle(t, idea) {
  let max = -1;
  for (const d of t.dimensions) { const s = idea.byDim[d.id]; if (s && s.stdev > max) max = s.stdev; }
  const names = t.dimensions.filter(d => idea.byDim[d.id] && Math.abs(idea.byDim[d.id].stdev - max) < 1e-9).map(d => d.name);
  return 'split on ' + names.join(', ') + ' (stdev ' + max.toFixed(2) + ')';
}

function renderRanking(t, ideas, stagger) {
  const box = document.getElementById('ranking');
  const dims = t ? t.dimensions : [];
  const scores = ideas.filter(f => f.score !== null).map(f => f.score);
  const max = scores.length ? Math.max(...scores) : 0; // D10: the top ranked score fills the bar
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
      row.innerHTML = '<div class="pos"></div><div class="ttl"></div><div class="stack"></div>' +
                      '<div class="score"></div><span class="pill" hidden>split</span>';
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
    row.classList.toggle('unranked', !ranked);
    row.querySelector('.pos').textContent = ranked ? String(f.rank) : '·';
    const ttl = row.querySelector('.ttl');
    ttl.textContent = f.title; ttl.title = f.title;
    // one segment per dimension, in dimension order; rebuilt only when the dimensions change
    const stack = row.querySelector('.stack');
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
    });
    row.querySelector('.score').textContent = ranked ? f.score.toFixed(2) : '—';
    const pill = row.querySelector('.pill');
    pill.hidden = f.split !== true;
    if (f.split === true) pill.title = splitTitle(t, f); else pill.removeAttribute('title');
  });
  for (const [key, row] of boardRows) if (!seen.has(key)) {
    boardRows.delete(key);
    row.classList.add('leave');
    setTimeout(() => row.remove(), 350);
  }
  boardIndex = t ? new Map(ideas.map((f, i) => [t.id + '/' + f.id, i])) : new Map();
}
</script>
"""
