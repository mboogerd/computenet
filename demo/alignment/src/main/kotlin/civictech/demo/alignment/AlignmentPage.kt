package civictech.demo.alignment

/**
 * The two-section alignment page served at `/` (computenet-v48mn). Vanilla
 * HTML/JS, no framework, no build step — styling precedent is
 * `demo/backlog-triage/.../TriageApp.kt`'s `PAGE`.
 *
 * `#rate` (this task, computenet-v48mn.1) is the bias-safe personal view: it
 * reads only `state.topics` (dimension names, creator) and `/topics/{t}/me`
 * (the caller's own ratings) — never `state.ratings` or `state.aggregates`.
 * `#board` (computenet-v48mn.2) is the aggregate view: `#weights` (one cell
 * per dimension, a colour swatch doubling as the bar legend, and the weight —
 * a number input for the topic's creator, plain text for everyone else) and
 * `#ranking` (rows keyed by idea id and reused, translated to their rank with
 * a CSS transition as in backlog-triage). `renderBoard()` reads only
 * `state.topics` and `state.aggregates[t]` — never `state.ratings` — so no
 * participant name reaches the section. Bar segments are the API's
 * `contribution`s, never recomputed; the bar scale is the topic's max ranked
 * score, so the top row fills its bar. The server's 403 on `PUT .../weights`
 * stays the enforcement; the creator check here only decides editability.
 *
 * The script avoids `$` entirely so this stays a plain `const` raw string —
 * string concatenation throughout, no template literals.
 */
const val PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>alignment</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 900px; margin: 1.5rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; margin-bottom: .8rem; }
  header { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; margin-bottom: .8rem; }
  header label { color: var(--dim); font-size: .85rem; }
  input, select, textarea { padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  button { padding: .3rem .7rem; border: 1px solid var(--line); border-radius: 6px; background: #fff; cursor: pointer; font: inherit; }
  .tabs { display: flex; gap: .4rem; margin-bottom: .8rem; border-bottom: 1px solid var(--line); }
  .tabs button { border: none; border-radius: 0; padding: .5rem .9rem; background: none; font-size: .9rem; color: var(--dim); border-bottom: 2px solid transparent; margin-bottom: -1px; }
  .tabs button.active { color: var(--ink); border-bottom-color: var(--blue); font-weight: 600; }
  section[hidden] { display: none; }
  form.inline { display: flex; gap: .4rem; align-items: center; flex-wrap: wrap; margin-bottom: .8rem; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; margin-bottom: .8rem; }
  .card h3 { font-size: 1rem; margin: 0 0 .3rem; display: flex; justify-content: space-between; align-items: baseline; gap: .6rem; }
  .card h3 .count { font-size: .72rem; font-weight: normal; color: var(--dim); white-space: nowrap; }
  .toggle { border: none; background: none; color: var(--blue); cursor: pointer; padding: 0; font-size: .78rem; margin-bottom: .3rem; }
  .card pre { margin: 0 0 .5rem; padding: .5rem .7rem; background: #fafafa; border: 1px solid var(--line); border-radius: 6px;
              font-size: .78rem; line-height: 1.4; white-space: pre-wrap; word-break: break-word; }
  .row { display: grid; grid-template-columns: 9rem minmax(0,1fr) 2.2rem auto; gap: .6rem; align-items: center; padding: .25rem 0; font-size: .85rem; }
  .row label { color: var(--ink); }
  .row .v { text-align: right; font-variant-numeric: tabular-nums; color: var(--ink); }
  .row.unrated .v { color: var(--dim); }
  .row.unrated input[type=range] { opacity: .4; accent-color: var(--dim); }
  .row button { padding: .15rem .5rem; font-size: .75rem; }
  #weights { display: flex; flex-wrap: wrap; gap: .4rem 1.1rem; align-items: center; font-size: .85rem;
             padding-bottom: .7rem; margin-bottom: .4rem; border-bottom: 1px solid var(--line); }
  #weights .wlabel { color: var(--dim); font-size: .78rem; }
  #weights .wcell { display: flex; align-items: center; gap: .35rem; }
  #weights .sw { width: .7rem; height: .7rem; border-radius: 3px; flex: 0 0 auto; }
  #weights .wv { font-variant-numeric: tabular-nums; color: var(--dim); }
  #weights input { width: 4.2rem; padding: .15rem .35rem; }
  #ranking { position: relative; }
  .rankrow { position: absolute; left: 0; right: 0; top: 0; height: 36px; background: #fff;
             display: grid; grid-template-columns: 1.6rem minmax(0,1fr) minmax(6rem,38%) 3rem 3.4rem;
             gap: .6rem; align-items: center; padding: 0 .3rem; font-size: .85rem;
             border-bottom: 1px solid var(--line); will-change: transform;
             transition: transform .55s cubic-bezier(.22,1,.36,1), opacity .3s; }
  .rankrow.moving { z-index: 2; }
  .rankrow.enter, .rankrow.leave { opacity: 0; }
  .rankrow .pos { text-align: right; font-weight: 700; color: var(--dim); font-variant-numeric: tabular-nums; }
  .rankrow .ttl { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rankrow .stack { display: flex; height: .6rem; background: #f3f4f6; border-radius: 4px; overflow: hidden; }
  .rankrow .seg { height: 100%; transition: width .55s; }
  .rankrow .score { text-align: right; font-variant-numeric: tabular-nums; }
  .rankrow .pill { justify-self: start; font-size: .7rem; padding: .1rem .5rem; border-radius: 8px; background: #faeeda; color: #854f0b; }
  .rankrow .pill[hidden] { display: none; }
  .rankrow.unranked { opacity: .5; }
  .rankrow.unranked.enter, .rankrow.unranked.leave { opacity: 0; }
  #board .note { color: var(--dim); font-size: .75rem; margin-top: .6rem; }
</style>
</head>
<body>
<h1>Alignment</h1>
<header>
  <label>as <input id="participant" maxlength="40"></label>
  <label>topic <select id="topicSel"></select></label>
</header>
<div class="tabs">
  <button type="button" id="tabRate">Rate</button>
  <button type="button" id="tabBoard">Board</button>
</div>
<section id="rate">
  <form class="inline" id="topicForm">
    new topic <input id="topicTitle" placeholder="title">
    <input id="topicDims" placeholder="dimensions, e.g. Impact:2, Effort">
    <button>create</button>
  </form>
  <form class="inline" id="ideaForm">
    new idea <input id="ideaTitle" placeholder="title">
    <input id="ideaDesc" placeholder="description (optional)">
    <button>add</button>
  </form>
  <div id="ideas"></div>
  <template id="sliderRow"><div class="row"><label></label><input type="range" min="1" max="9" step="1"><span class="v"></span><button type="button">clear</button></div></template>
</section>
<section id="board" hidden>
  <div id="weights"></div>
  <div id="ranking"></div>
  <p class="note">Bar segments show each dimension's weighted contribution to the score. "split" marks ideas the team disagrees on.</p>
</section>
<script>
let state = { topics: [], ideas: [], ratings: [], aggregates: {} };
const who = document.getElementById('participant');
who.value = sessionStorage.participant || ('p-' + Math.random().toString(36).slice(2, 6));
sessionStorage.participant = who.value;
who.onchange = () => { sessionStorage.participant = who.value.trim(); renderRate(); renderBoard(); };
const me = () => who.value.trim();
const send = (method, url, body) => fetch(url, {
  method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
}).then(r => r.ok ? r.json() : r.json().then(j => { alert(j.error || r.status); throw j; }));

// ── tabs: #rate / #board, remembered in sessionStorage.tab ────────────────
const tabRate = document.getElementById('tabRate');
const tabBoard = document.getElementById('tabBoard');
const sectionRate = document.getElementById('rate');
const sectionBoard = document.getElementById('board');
function showTab(name) {
  sessionStorage.tab = name;
  sectionRate.hidden = name !== 'rate';
  sectionBoard.hidden = name !== 'board';
  tabRate.classList.toggle('active', name === 'rate');
  tabBoard.classList.toggle('active', name === 'board');
  if (name === 'rate') renderRate(); else renderBoard();
}
tabRate.onclick = () => showTab('rate');
tabBoard.onclick = () => showTab('board');

// ── topic selection, remembered in sessionStorage.topic ───────────────────
const sel = document.getElementById('topicSel');
sel.dataset.want = sessionStorage.topic || '';
const topic = () => sel.value;
sel.onchange = () => { sessionStorage.topic = sel.value; renderRate(); renderBoard(); };

document.getElementById('topicForm').onsubmit = e => {
  e.preventDefault();
  const dims = parseDims(topicDims.value);
  if (dims === null) { alert('each dimension weight must be a positive number'); return; }
  send('POST', '/topics', { creator: me(), title: topicTitle.value, dimensions: dims })
    .then(j => {
      topicTitle.value = ''; topicDims.value = ''; sel.dataset.want = j.id;
      // the topic's frame may already have arrived: select it now if it is known, else on the next frame
      renderTopics(); renderRate(); renderBoard();
    });
};
document.getElementById('ideaForm').onsubmit = e => {
  e.preventDefault();
  if (!topic()) return;
  send('POST', '/topics/' + topic() + '/ideas', { participant: me(), title: ideaTitle.value, description: ideaDesc.value })
    .then(() => { ideaTitle.value = ''; ideaDesc.value = ''; renderRate(); });
};

/** "Impact:2, Effort" -> [{name:"Impact",weight:2},{name:"Effort"}]; null on a bad weight. */
function parseDims(text) {
  const parts = text.split(',').map(s => s.trim()).filter(s => s);
  const dims = [];
  for (const part of parts) {
    const colon = part.indexOf(':');
    if (colon === -1) { dims.push({ name: part }); continue; }
    const name = part.slice(0, colon).trim();
    const raw = part.slice(colon + 1).trim();
    const weight = Number(raw);
    if (!name || !raw || !isFinite(weight) || weight <= 0) return null;
    dims.push({ name: name, weight: weight });
  }
  return dims;
}

function renderTopics() {
  const want = sel.dataset.want || sessionStorage.topic || sel.value;
  sel.innerHTML = '';
  for (const t of state.topics) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = t.title;
    sel.appendChild(o);
  }
  if (state.topics.some(t => t.id === want)) { sel.value = want; delete sel.dataset.want; }
  if (sel.value) sessionStorage.topic = sel.value;
}

// the rating panel reads only state.topics (dimension names, creator) and
// the bias-safe /me view: your own ratings, nothing aggregate, ever
let rateSeq = 0;
let expandedDesc = new Set();
function renderRate() {
  const box = document.getElementById('ideas');
  if (sectionRate.hidden) return;
  if (!topic() || !me()) { box.innerHTML = ''; return; }
  if (document.activeElement && document.activeElement.type === 'range') return; // don't yank a slider mid-drag
  const seq = ++rateSeq;
  fetch('/topics/' + topic() + '/me?participant=' + encodeURIComponent(me())).then(r => r.json()).then(view => {
    if (seq !== rateSeq) return;
    const t = state.topics.find(x => x.id === topic());
    const dims = t ? t.dimensions : [];
    box.innerHTML = '';
    for (const idea of view.ideas) {
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
        const apply = val => {
          if (val === null) {
            row.className = 'row unrated';
            input.value = '5';
            shown.textContent = '—';
            clearBtn.disabled = true;
          } else {
            row.className = 'row';
            input.value = String(val);
            shown.textContent = String(val);
            clearBtn.disabled = false;
          }
        };
        apply(v);
        input.oninput = () => { row.className = 'row'; shown.textContent = input.value; clearBtn.disabled = false; };
        input.onchange = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: Number(input.value) }).then(() => { input.blur(); renderRate(); });
        clearBtn.onclick = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: null }).then(() => renderRate());
        card.appendChild(row);
      }
      box.appendChild(card);
    }
  });
}

// ── Board: the aggregate view ──────────────────────────────────────────────
// Reads state.topics and state.aggregates[t] only — never state.ratings — so
// no participant name is ever rendered here. Ranked rows arrive server-sorted
// (score desc, rating count desc, id asc) followed by the unscored ones; the
// page never re-sorts and never recomputes a score or a contribution.
const PALETTE = ['#7f77dd', '#1d9e75', '#d85a30', '#2563eb', '#d4a017', '#c2417a', '#0e9aa7', '#6b8e23'];
const dimColour = i => PALETTE[i % PALETTE.length];
const ROW = 36; // px, keep in sync with .rankrow height
const boardRows = new Map(); // topicId + '/' + ideaId -> reused row node
let boardIndex = new Map();

function renderBoard() {
  const t = state.topics.find(x => x.id === topic());
  const agg = t ? state.aggregates[t.id] : null;
  renderWeights(t);
  renderRanking(t, agg ? agg.ideas : []);
}

function renderWeights(t) {
  const box = document.getElementById('weights');
  // D10: never rebuild or rewrite the row under a weight input being edited
  if (box.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
  if (!t) { box.innerHTML = ''; delete box.dataset.shape; return; }
  const editable = me() !== '' && me() === t.creator;
  const shape = t.id + '|' + editable + '|' + t.dimensions.map(d => d.id + ':' + d.name).join(',');
  if (box.dataset.shape !== shape) {
    box.dataset.shape = shape;
    box.innerHTML = '';
    const lbl = document.createElement('span');
    lbl.className = 'wlabel';
    lbl.textContent = editable ? 'weights (you created this topic)' : 'weights';
    box.appendChild(lbl);
    t.dimensions.forEach((d, i) => {
      const cell = document.createElement('span');
      cell.className = 'wcell';
      cell.dataset.dim = d.id;
      const sw = document.createElement('span');
      sw.className = 'sw'; sw.style.background = dimColour(i);
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
      // place without transition, then fade in
      row.style.transition = 'none';
      row.style.transform = 'translateY(' + (i * ROW) + 'px)';
      boardRows.set(key, row); box.appendChild(row);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        row.style.transition = ''; row.classList.remove('enter');
      }));
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
    const segShape = dims.map(d => d.id).join(',');
    if (stack.dataset.shape !== segShape) {
      stack.dataset.shape = segShape;
      stack.innerHTML = '';
      dims.forEach((d, j) => {
        const seg = document.createElement('div');
        seg.className = 'seg'; seg.style.background = dimColour(j); seg.style.width = '0';
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
  boardIndex = new Map(ideas.map((f, i) => [t.id + '/' + f.id, i]));
}

// SSE frames arrive in bursts (several hub broadcasts milliseconds apart);
// coalesce on a 60 ms tick as in backlog-triage so a burst re-renders once.
// The Board renders on every tick whether or not its tab is showing, so it is
// already laid out, without a stale-position animation, the moment it is shown.
let renderPending = false;
function scheduleRender() {
  if (renderPending) return;
  renderPending = true;
  setTimeout(() => { renderPending = false; renderTopics(); renderRate(); renderBoard(); }, 60);
}
new EventSource('/events').onmessage = e => {
  state = JSON.parse(e.data);
  scheduleRender();
};

showTab(sessionStorage.tab === 'board' ? 'board' : 'rate');
</script>
</body>
</html>
"""
