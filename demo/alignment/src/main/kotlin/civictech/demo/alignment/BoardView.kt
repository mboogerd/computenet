package civictech.demo.alignment

/**
 * The Board view (computenet-v48mn.2, carried into the v2 shell by computenet-0dvra.1), one
 * slice of the served [PAGE]: its CSS, the `<section id="board">` markup and the `<script>`
 * defining `renderBoard()`.
 *
 * The aggregate view: `#weights` (one cell per dimension, a colour swatch doubling as the bar
 * legend, and the weight — a number input for the topic's creator, plain text for everyone
 * else) and `#ranking` (rows keyed by idea id and reused, translated to their rank with a CSS
 * transition). `renderBoard()` reads only `state.topics` and `state.aggregates[t]` — never
 * `state.ratings` — so no participant name reaches the section. Bar segments are the API's
 * `contribution`s, never recomputed; the bar scale is the topic's max ranked score. The
 * server's 403 stays the enforcement; the creator check here only decides editability.
 *
 * Carried over from v1 with only the adaptations the shell forces: the topic comes from the URL
 * (`currentTopic()`), `dimColour(t, d)` replaces the JS palette, literal colours became tokens,
 * and an empty `#gate` placeholder is the section's first child. The legend, the gate card
 * (driven by the shell's `boardGate(t)`), the animated reveal and null-safe cost contributions
 * are computenet-0dvra.4's. Shared helper contract: the comment block at the top of the shell's
 * script in [AlignmentPage.kt]. No `$` anywhere.
 */
internal const val BOARD_VIEW = """
<style>
  #weights { display: flex; flex-wrap: wrap; gap: .4rem 1.1rem; align-items: center; font-size: var(--fs-2);
             padding-bottom: .7rem; margin-bottom: .4rem; border-bottom: 1px solid var(--line); }
  #weights .wlabel { color: var(--muted); font-size: var(--fs-1); }
  #weights .wcell { display: flex; align-items: center; gap: .35rem; }
  #weights .sw { width: .7rem; height: .7rem; border-radius: 3px; flex: 0 0 auto; }
  #weights .wv { font-variant-numeric: tabular-nums; color: var(--muted); }
  #weights input { width: 4.2rem; padding: .15rem .35rem; }
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
</style>
<section id="board" class="pane view" hidden>
  <div id="gate" hidden></div>
  <div id="weights"></div>
  <div id="ranking"></div>
  <p class="note">Bar segments show each dimension's weighted contribution to the score. "split" marks ideas the team disagrees on.</p>
</section>
<script>
// ── Board: the aggregate view ──────────────────────────────────────────────
// Reads state.topics and state.aggregates[t] only — never state.ratings — so
// no participant name is ever rendered here. Ranked rows arrive server-sorted
// (score desc, rating count desc, id asc) followed by the unscored ones; the
// page never re-sorts and never recomputes a score or a contribution.
const ROW = 36; // px, keep in sync with .rankrow height
const boardRows = new Map(); // topicId + '/' + ideaId -> reused row node
let boardIndex = new Map();

function renderBoard() {
  const t = currentTopic();
  const agg = t ? state.aggregates[t.id] : null;
  renderWeights(t);
  renderRanking(t, agg ? agg.ideas : []);
}

function renderWeights(t) {
  const box = document.getElementById('weights');
  // D10: never rebuild or rewrite the row under a weight input being edited
  if (box.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
  if (!t) { box.innerHTML = ''; delete box.dataset.shape; return; }
  const editable = isCreator(t);
  const shape = t.id + '|' + editable + '|' + t.dimensions.map(d => d.id + ':' + d.name + ':' + d.direction).join(',');
  if (box.dataset.shape !== shape) {
    box.dataset.shape = shape;
    box.innerHTML = '';
    const lbl = document.createElement('span');
    lbl.className = 'wlabel';
    lbl.textContent = editable ? 'weights (you created this topic)' : 'weights';
    box.appendChild(lbl);
    t.dimensions.forEach(d => {
      const cell = document.createElement('span');
      cell.className = 'wcell';
      cell.dataset.dim = d.id;
      const sw = document.createElement('span');
      sw.className = 'sw'; sw.style.background = dimColour(t, d);
      const name = document.createElement('span');
      name.textContent = d.name;
      cell.appendChild(sw); cell.appendChild(name);
      if (editable) {
        const input = document.createElement('input');
        input.type = 'number'; input.min = '0.5'; input.step = '0.5';
        input.setAttribute('aria-label', d.name + ' weight');
        input.onchange = () => {
          const w = Number(input.value);
          if (input.value.trim() === '' || !isFinite(w) || w <= 0) {
            alert('a weight must be a number greater than 0');
            input.blur(); renderBoard();
            return;
          }
          // success: keep focus (the typed value is what was stored; the row
          // refreshes on blur). Refusal (403 alerted by send): restore it.
          send('PUT', '/topics/' + t.id + '/weights', { creator: me(), dim: d.id, weight: w })
            .catch(() => { input.blur(); renderBoard(); });
        };
        input.onblur = () => scheduleRender();
        cell.appendChild(input);
      } else {
        const v = document.createElement('span');
        v.className = 'wv';
        cell.appendChild(v);
      }
      box.appendChild(cell);
    });
  }
  // same shape: refresh the values in place
  for (const d of t.dimensions) {
    const cell = box.querySelector('[data-dim="' + CSS.escape(d.id) + '"]');
    if (!cell) continue;
    const shown = d.weight === null ? '' : String(d.weight);
    const input = cell.querySelector('input');
    if (input) { if (input.value !== shown) input.value = shown; }
    else cell.querySelector('.wv').textContent = shown === '' ? '—' : shown;
  }
}

/** "split on Impact (stdev 2.83)": the dimension(s) with the row's widest spread (D6). */
function splitTitle(t, idea) {
  let max = -1;
  for (const d of t.dimensions) { const s = idea.byDim[d.id]; if (s && s.stdev > max) max = s.stdev; }
  const names = t.dimensions.filter(d => idea.byDim[d.id] && Math.abs(idea.byDim[d.id].stdev - max) < 1e-9).map(d => d.name);
  return 'split on ' + names.join(', ') + ' (stdev ' + max.toFixed(2) + ')';
}

function renderRanking(t, ideas) {
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
      if (s && max > 0) {
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
