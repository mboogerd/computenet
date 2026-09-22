package civictech.demo.alignment

/**
 * The Rate view (computenet-v48mn.1, carried into the v2 shell by computenet-0dvra.1), one
 * slice of the served [PAGE]: its CSS, the `<section id="rate">` markup and the `<script>`
 * defining `renderRate()`.
 *
 * The bias-safe personal view: it reads only `state.topics` (via `currentTopic()`) and the
 * caller's own `/topics/{t}/me` view (via the shell's `fetchMe`, which also feeds the board
 * gate's cache) — never `state.ratings` or `state.aggregates`.
 *
 * Carried over from v1 with only the adaptations the shell forces: the topic id comes from the
 * URL (`topicId()`), the "new topic" form moved to the landing, the fetch goes through
 * `fetchMe`, and literal colours became tokens. The restyle, policy-gated add-idea form and
 * anchor labels are computenet-0dvra.3's.
 *
 * computenet-10mvq.1 (ALN2.3, 10mvq-D1/D7/D8): the sliders are continuous (`step="any"`) with
 * no numeric readout — an unrated row draws no thumb and reads "not rated", the first
 * interaction places the thumb, `change` posts the value rounded to thousandths. The view is a
 * list when every card fits between the list's top and the viewport bottom, and one card at a
 * time behind the `#pager` otherwise — always the latter at `(max-width: 640px)`. The fit is
 * measured by building the list and switching inside the same task (no paint in between), and
 * re-measured only on a debounced resize, a breakpoint change or a changed set of idea ids —
 * never because an SSE frame arrived. The page index lives in `sessionStorage['rateIdx:' + tid]`.
 *
 * Shared helper contract: the comment block at the top of the shell's script in
 * [AlignmentPage.kt]. No `$` anywhere.
 */
internal const val RATE_VIEW = """
<style>
  #rate { --rate-cols: 9rem minmax(0,1fr) 5rem 3.8rem; }
  #rate .row { display: grid; grid-template-columns: var(--rate-cols); gap: .6rem; align-items: center; padding: .25rem 0; font-size: var(--fs-2); }
  #rate .row label { color: var(--ink); }
  #rate .row .v { text-align: right; font-size: var(--fs-1); color: var(--muted); white-space: nowrap; }
  #rate .row button { padding: .15rem .5rem; font-size: var(--fs-1); width: 100%; }
  #rate .anchors { display: grid; grid-template-columns: var(--rate-cols); column-gap: .6rem; margin: -.2rem 0 .5rem; font-size: var(--fs-1); color: var(--muted); }
  #rate .anchors span { grid-row: 1; grid-column: 2; }
  #rate .anchors span + span { justify-self: end; }

  /* continuous, no-thumb-until-rated slider (10mvq-D7); --c is the dimension's token, --p the fill */
  #rate input[type=range] { -webkit-appearance: none; appearance: none; width: 100%; height: 1.5rem; margin: 0; padding: 0;
                            border: none; background: transparent; cursor: pointer; --c: var(--accent); --p: 50%; }
  #rate input[type=range]:focus { outline: none; }
  #rate input[type=range]:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 999px; }
  #rate input[type=range]::-webkit-slider-runnable-track { box-sizing: border-box; height: 6px; border-radius: 999px;
    background: linear-gradient(to right, var(--c) 0 var(--p), var(--track) var(--p) 100%); }
  #rate input[type=range]::-moz-range-track { box-sizing: border-box; height: 6px; border-radius: 999px; background: var(--track); }
  #rate input[type=range]::-moz-range-progress { height: 6px; border-radius: 999px; background: var(--c); }
  #rate input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; margin-top: -5px;
    border-radius: 50%; background: var(--c); border: 2px solid var(--surface); box-shadow: 0 0 0 1px var(--c); }
  #rate input[type=range]::-moz-range-thumb { width: 12px; height: 12px; border-radius: 50%; background: var(--c);
    border: 2px solid var(--surface); box-shadow: 0 0 0 1px var(--c); }
  #rate .row.unrated input[type=range]::-webkit-slider-runnable-track { background: transparent; border: 1px dashed var(--unrated); }
  #rate .row.unrated input[type=range]::-moz-range-track { background: transparent; border: 1px dashed var(--unrated); }
  #rate .row.unrated input[type=range]::-moz-range-progress { background: transparent; }
  #rate .row.unrated input[type=range]::-webkit-slider-thumb { opacity: 0; }
  #rate .row.unrated input[type=range]::-moz-range-thumb { opacity: 0; }

  /* paged mode (10mvq-D1/D8) */
  #pager { display: flex; align-items: center; gap: .6rem; flex-wrap: wrap; margin-bottom: .8rem; font-size: var(--fs-2); }
  #pager #pagePos { font-variant-numeric: tabular-nums; color: var(--ink); min-width: 4.5rem; text-align: center; }
  #pager #pageProg { margin-left: auto; font-size: var(--fs-1); color: var(--muted); font-variant-numeric: tabular-nums; }

  @media (max-width: 640px) {
    #rate { --rate-cols: minmax(0,1fr) 5rem 3.8rem; }
    #rate .row label { grid-column: 1 / -1; }
    #rate .anchors span { grid-column: 1; }
    #pager #pageProg { flex-basis: 100%; margin-left: 0; }
  }
</style>
<section id="rate" class="pane view" hidden>
  <form class="inline" id="ideaForm">
    new idea <input id="ideaTitle" placeholder="title">
    <input id="ideaDesc" placeholder="description (optional)">
    <button>add</button>
  </form>
  <p class="muted" id="ideaGate" hidden>only the facilitator adds ideas here</p>
  <div id="pager" hidden>
    <button type="button" id="pagePrev">‹ previous</button>
    <span id="pagePos"></span>
    <button type="button" id="pageNext">next ›</button>
    <span id="pageProg"></span>
  </div>
  <div id="ideas"></div>
  <template id="sliderRow"><div class="row unrated"><label></label><input type="range" min="1" max="9" step="any"><span class="v"></span><button type="button">clear</button></div></template>
</section>
<script>
document.getElementById('ideaForm').onsubmit = e => {
  e.preventDefault();
  if (!topicId()) return;
  send('POST', '/topics/' + topicId() + '/ideas', { participant: me(), title: ideaTitle.value, description: ideaDesc.value })
    .then(() => { ideaTitle.value = ''; ideaDesc.value = ''; renderRate(); });
};

// the rating panel reads only state.topics (dimension names, creator) and
// the bias-safe /me view: your own ratings, nothing aggregate, ever
let rateSeq = 0;
let expandedDesc = new Set();
// tid -> {mode: 'list'|'page', key: sorted idea ids, narrow}; cleared only by rateRemeasure
const rateModes = {};
const rateNarrow = window.matchMedia('(max-width: 640px)');
let rateResizeTimer = 0;

function rateRemeasure() {
  for (const k of Object.keys(rateModes)) delete rateModes[k];
  renderRate();
}
window.addEventListener('resize', () => { clearTimeout(rateResizeTimer); rateResizeTimer = setTimeout(rateRemeasure, 150); });
if (rateNarrow.addEventListener) rateNarrow.addEventListener('change', rateRemeasure);

// the page index for a topic, clamped to [0, n-1] and written back
function rateIdx(tid, n) {
  let i = parseInt(sessionStorage.getItem('rateIdx:' + tid), 10);
  if (!(i >= 0)) i = 0;
  if (i > n - 1) i = Math.max(0, n - 1);
  sessionStorage.setItem('rateIdx:' + tid, String(i));
  return i;
}
function ratePage(step) {
  const tid = topicId();
  const view = tid === null ? null : meCache[tid];
  if (!view || document.getElementById('pager').hidden) return;
  const n = view.ideas.length;
  const i = Math.min(Math.max(rateIdx(tid, n) + step, 0), Math.max(0, n - 1));
  sessionStorage.setItem('rateIdx:' + tid, String(i));
  renderRate();
}
document.getElementById('pagePrev').onclick = () => ratePage(-1);
document.getElementById('pageNext').onclick = () => ratePage(1);
// ArrowLeft / ArrowRight page, unless a field (a slider included) has focus
document.addEventListener('keydown', e => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
  const rate = document.getElementById('rate');
  if (rate.hidden || document.getElementById('pager').hidden || editing(document.body)) return;
  e.preventDefault();
  ratePage(e.key === 'ArrowLeft' ? -1 : 1);
});

function rateCard(idea, t, dims, tid) {
  const card = document.createElement('div');
  card.className = 'card';
  const h = document.createElement('h3');
  const title = document.createElement('span');
  title.textContent = idea.title;
  const count = document.createElement('span');
  count.className = 'count';
  count.textContent = idea.rated + ' of ' + idea.total + ' rated';
  h.appendChild(title); h.appendChild(count);
  card.appendChild(h);
  if (idea.description) {
    const open = expandedDesc.has(idea.id);
    const toggle = document.createElement('button');
    toggle.type = 'button'; toggle.className = 'toggle';
    toggle.textContent = open ? 'hide description' : 'show description';
    const pre = document.createElement('pre');
    pre.textContent = idea.description;
    pre.hidden = !open;
    toggle.onclick = () => {
      const nowOpen = !expandedDesc.has(idea.id);
      if (nowOpen) expandedDesc.add(idea.id); else expandedDesc.delete(idea.id);
      pre.hidden = !nowOpen;
      toggle.textContent = nowOpen ? 'hide description' : 'show description';
    };
    card.appendChild(toggle);
    card.appendChild(pre);
  }
  for (const dim of dims) {
    const v = idea.ratings[dim.id];
    const row = document.getElementById('sliderRow').content.firstElementChild.cloneNode(true);
    row.querySelector('label').textContent = dim.name;
    const input = row.querySelector('input');
    const shown = row.querySelector('span');
    const clearBtn = row.querySelector('button');
    input.style.setProperty('--c', dimColour(t, dim));
    const fill = () => input.style.setProperty('--p', (100 * (Number(input.value) - 1) / 8) + '%');
    // rated: thumb at the value, empty value column; unrated: no thumb, "not rated", parked at 5
    const apply = val => {
      const unrated = val === null || val === undefined;
      row.className = unrated ? 'row unrated' : 'row';
      input.value = unrated ? '5' : String(val);
      shown.textContent = unrated ? 'not rated' : '';
      clearBtn.disabled = unrated;
      fill();
    };
    apply(v);
    input.oninput = () => { row.className = 'row'; shown.textContent = ''; clearBtn.disabled = false; fill(); };
    input.onchange = () => send('POST', '/topics/' + tid + '/rate',
      { participant: me(), idea: idea.id, dim: dim.id, value: Math.round(Number(input.value) * 1000) / 1000 })
      .then(() => { input.blur(); renderRate(); }, () => { input.blur(); renderRate(); });
    clearBtn.onclick = () => { apply(null); send('POST', '/topics/' + tid + '/rate',
      { participant: me(), idea: idea.id, dim: dim.id, value: null }).then(() => renderRate(), () => renderRate()); };
    card.appendChild(row);
    const low = dim.lowLabel || '';
    const high = dim.highLabel || '';
    if (low || high) {
      const anchors = document.createElement('div');
      anchors.className = 'anchors';
      const l = document.createElement('span'); l.textContent = low || '1';
      const hi = document.createElement('span'); hi.textContent = high || '9';
      anchors.appendChild(l); anchors.appendChild(hi);
      card.appendChild(anchors);
    }
  }
  return card;
}

// Does the whole list fit between its top and the viewport bottom? Builds it into #ideas and
// measures synchronously; the caller replaces it in the same task, so nothing paints between.
function rateFits(box, ideas, t, dims, tid) {
  document.getElementById('pager').hidden = true;
  box.innerHTML = '';
  for (const idea of ideas) box.appendChild(rateCard(idea, t, dims, tid));
  const r = box.getBoundingClientRect();
  // page offset rather than the raw client top, so a scrolled page measures like an unscrolled one
  return r.height <= window.innerHeight - (r.top + window.scrollY);
}

function renderRate() {
  const box = document.getElementById('ideas');
  const pager = document.getElementById('pager');
  if (document.getElementById('rate').hidden) return;
  if (!topicId() || !me()) { box.innerHTML = ''; pager.hidden = true; return; }
  if (document.activeElement && document.activeElement.type === 'range') return; // don't yank a slider mid-drag
  const seq = ++rateSeq;
  const tid = topicId();
  fetchMe(tid).then(view => {
    if (seq !== rateSeq) return;
    if (document.activeElement && document.activeElement.type === 'range') return; // a drag began mid-fetch
    const t = currentTopic();
    const dims = t ? t.dimensions : [];
    const form = document.getElementById('ideaForm');
    const gateLine = document.getElementById('ideaGate');
    const allowed = !t || mayAddIdea(t);
    form.hidden = !allowed;
    gateLine.hidden = allowed;
    const ideas = view.ideas;
    const n = ideas.length;
    const key = ideas.map(i => i.id).sort().join('\n');
    const narrow = rateNarrow.matches;
    let m = rateModes[tid];
    if (!m || m.key !== key || m.narrow !== narrow) {
      const mode = narrow ? 'page' : (rateFits(box, ideas, t, dims, tid) ? 'list' : 'page');
      m = rateModes[tid] = { mode: mode, key: key, narrow: narrow };
    }
    box.innerHTML = '';
    if (m.mode === 'list' || n === 0) {
      pager.hidden = true;
      for (const idea of ideas) box.appendChild(rateCard(idea, t, dims, tid));
      return;
    }
    const i = rateIdx(tid, n);
    box.appendChild(rateCard(ideas[i], t, dims, tid));
    let rated = 0, total = 0;
    for (const idea of ideas) { rated += idea.rated; total += idea.total; }
    document.getElementById('pagePos').textContent = (i + 1) + ' of ' + n;
    document.getElementById('pageProg').textContent = rated + ' of ' + total + ' rated';
    document.getElementById('pagePrev').disabled = i <= 0;
    document.getElementById('pageNext').disabled = i >= n - 1;
    pager.hidden = false;
  }, () => {});
}
</script>
"""
