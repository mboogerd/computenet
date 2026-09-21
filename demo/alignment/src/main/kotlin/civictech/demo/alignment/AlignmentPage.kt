package civictech.demo.alignment

/**
 * The alignment page, served at `/` and `/t/{id}` (computenet-v48mn, v2 shell computenet-0dvra).
 * Vanilla HTML/CSS/JS, no framework, no build step, one served document.
 *
 * [PAGE] is concatenated from slices (computenet-0dvra-D1/D17), in this order:
 * - [SHELL_HEAD] (this file): doctype, `<head>` with the colour tokens (light + a
 *   `prefers-color-scheme: dark` override) and the shared CSS, the header (`#identity` chip,
 *   landing link, tab bar, `#phase`), the `#topics` landing, the "no such topic" card and
 *   the SHARED SCRIPT — helper functions only, no boot. Its opening comment block is the
 *   contract the view slices code against.
 * - [SETUP_VIEW] (SetupView.kt), [RATE_VIEW] (RateView.kt), [BOARD_VIEW] (BoardView.kt): each
 *   a view's `<style>`, `<section>` and `<script>`, which declares functions and wires its own
 *   static markup only.
 * - [SHELL_TAIL] (this file): the boot — `popstate`, the `/events` EventSource, the first route.
 *
 * Rules for every slice: no `$` anywhere (each is a plain raw string, no template literals in
 * the JS); no literal colour outside the two token blocks in [SHELL_HEAD] — views use
 * `var(--…)` and `dimColour(t, d)`.
 *
 * [PAGE] is a plain `val`, not `const val`: a constant is written into the class file as one
 * constant-pool entry, capped at 65535 bytes, and the joined page is headed past that as the
 * views grow; a `val` is concatenated once at class init.
 */
val PAGE: String = SHELL_HEAD + SETUP_VIEW + RATE_VIEW + BOARD_VIEW + SHELL_TAIL

private const val SHELL_HEAD = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>alignment</title>
<style>
  /* ── tokens: the ONLY place a literal colour may appear (light, then dark) ── */
  :root {
    color-scheme: light dark;
    --accent: #7c3aed; --accent-soft: #efe8fd; --accent-ink: #ffffff;
    --bg: #f4f5f9; --surface: #ffffff; --surface-2: #eef0f6;
    --ink: #151722; --muted: #5f6578; --line: #e0e3eb;
    --ok: #0f9f6e; --warn: #b45309; --warn-soft: #fdf0d9;
    --value-1: #2563eb; --value-2: #0d9488; --value-3: #16a34a; --value-4: #4f46e5;
    --cost-1: #ea580c; --cost-2: #dc2626; --cost-3: #ca8a04; --cost-4: #db2777;
    --unrated: #b4b9c6; --track: #e3e6ee;
    --shadow: 0 1px 2px rgba(20,24,40,.05), 0 6px 20px rgba(20,24,40,.06);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --accent: #b197fc; --accent-soft: #2a2143; --accent-ink: #14111f;
      --bg: #0d0f15; --surface: #161922; --surface-2: #1e222d;
      --ink: #e8eaf2; --muted: #979eb2; --line: #2a2f3c;
      --ok: #34d399; --warn: #fbbf24; --warn-soft: #3a2c0e;
      --value-1: #60a5fa; --value-2: #2dd4bf; --value-3: #4ade80; --value-4: #818cf8;
      --cost-1: #fb923c; --cost-2: #f87171; --cost-3: #facc15; --cost-4: #f472b6;
      --unrated: #4b5060; --track: #272b37;
      --shadow: 0 1px 2px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.28);
    }
  }
  /* ── scale (not colours) ── */
  :root {
    --radius: 8px; --radius-lg: 14px;
    --fs-1: .78rem; --fs-2: .875rem; --fs-3: 1rem; --fs-4: 1.45rem;
  }

  /* ── page ── */
  * { box-sizing: border-box; }
  [hidden] { display: none !important; }
  html { background: var(--bg); }
  body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; font-size: var(--fs-3); line-height: 1.45;
         color: var(--ink); background: var(--bg); max-width: 980px; margin: 0 auto; padding: 0 16px 3rem; }
  h1 { font-size: var(--fs-4); margin: 0; letter-spacing: -.01em; }
  h2 { font-size: var(--fs-3); margin: 0 0 .7rem; }
  a { color: var(--accent); }
  .muted { color: var(--muted); }
  .num, .count, .score { font-variant-numeric: tabular-nums; }
  input, select, textarea { padding: .4rem .6rem; border: 1px solid var(--line); border-radius: var(--radius);
                            font: inherit; font-size: var(--fs-2); color: var(--ink); background: var(--surface); }
  input:focus-visible, select:focus-visible, textarea:focus-visible, button:focus-visible, a:focus-visible {
    outline: 2px solid var(--accent); outline-offset: 1px; }
  input[type=range] { accent-color: var(--accent); padding: 0; border: none; background: none; }
  button { padding: .35rem .8rem; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface);
           color: var(--ink); cursor: pointer; font: inherit; font-size: var(--fs-2); }
  button:hover:not(:disabled) { border-color: var(--accent); }
  button:disabled { opacity: .5; cursor: default; }
  button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); font-weight: 600; }
  button.link { border: none; background: none; padding: 0 .2rem; color: var(--accent); font-size: var(--fs-1); }

  /* ── hierarchy: page → pane (a view) → card (an item) ── */
  .pane { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-lg);
          box-shadow: var(--shadow); padding: 1.1rem 1.2rem; }
  .card { background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--radius);
          padding: .8rem 1rem; margin-bottom: .8rem; }
  .card h3 { font-size: var(--fs-3); margin: 0 0 .3rem; display: flex; justify-content: space-between; align-items: baseline; gap: .6rem; }
  .card h3 .count { font-size: var(--fs-1); font-weight: normal; color: var(--muted); white-space: nowrap; }
  .card pre { margin: 0 0 .5rem; padding: .5rem .7rem; background: var(--surface); border: 1px solid var(--line); border-radius: 6px;
              font-size: var(--fs-1); line-height: 1.4; white-space: pre-wrap; word-break: break-word; }
  .toggle { border: none; background: none; color: var(--accent); cursor: pointer; padding: 0; font-size: var(--fs-1); margin-bottom: .3rem; }
  form.inline { display: flex; gap: .4rem; align-items: center; flex-wrap: wrap; margin-bottom: .8rem; font-size: var(--fs-2); }

  /* ── header ── */
  header.top { display: flex; align-items: center; gap: .6rem 1rem; flex-wrap: wrap; padding: 1rem 0 .9rem; }
  .brand { display: inline-flex; align-items: center; gap: .5rem; font-weight: 700; font-size: var(--fs-3);
           color: var(--ink); text-decoration: none; }
  .logo { width: 1.3rem; height: 1.3rem; border-radius: 6px;
          background: linear-gradient(135deg, var(--value-1), var(--accent) 55%, var(--cost-1)); }
  #crumb { display: flex; align-items: center; gap: .4rem; font-size: var(--fs-2); min-width: 0; }
  #crumb a { color: var(--muted); text-decoration: none; }
  #crumb a:hover { color: var(--accent); }
  #crumbTitle { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 16rem; }
  .spacer { flex: 1 1 auto; }
  .subbar { flex: 1 0 100%; display: flex; align-items: center; justify-content: space-between; gap: .6rem 1rem; flex-wrap: wrap; }
  .subbar:not(:has(> :not([hidden]))) { display: none; }
  #phase { display: flex; align-items: center; gap: .3rem; list-style: none; margin: 0; padding: 0; font-size: var(--fs-1); }
  #phase li { color: var(--muted); padding: .15rem .55rem; border-radius: 999px; }
  #phase li.arr { padding: 0; }
  #phase li.done { color: var(--ink); }
  #phase li.current { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
  #identity { display: inline-flex; align-items: center; gap: .4rem; padding: .25rem .35rem .25rem .7rem;
              border: 1px solid var(--line); border-radius: 999px; background: var(--surface); font-size: var(--fs-2); }
  #identity .dot { width: .5rem; height: .5rem; border-radius: 50%; background: var(--ok); }
  #identity b { font-weight: 600; }
  #idInput { width: 10rem; padding: .15rem .45rem; font-size: var(--fs-2); }

  /* ── tabs ── */
  .tabs { display: inline-flex; gap: .2rem; padding: .25rem; margin: 0; background: var(--surface-2);
          border: 1px solid var(--line); border-radius: 999px; }
  .tabs button { border: none; border-radius: 999px; padding: .35rem 1rem; background: none; color: var(--muted); font-weight: 600; }
  .tabs button.active { background: var(--surface); color: var(--ink); box-shadow: var(--shadow); }

  /* ── landing ── */
  .landing-head { margin: .4rem 0 1rem; }
  .landing-head p { margin: .25rem 0 0; }
  .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: .9rem; margin-bottom: 1.4rem; }
  .tcard { display: flex; flex-direction: column; gap: .45rem; padding: 1rem 1.1rem; color: var(--ink); text-decoration: none;
           background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-lg); box-shadow: var(--shadow);
           transition: transform .15s, border-color .15s; }
  .tcard:hover { transform: translateY(-2px); border-color: var(--accent); }
  .tc-title { font-weight: 700; font-size: var(--fs-3); }
  .tc-meta, .tc-prog { font-size: var(--fs-1); color: var(--muted); font-variant-numeric: tabular-nums; }
  .tc-dims { display: flex; flex-wrap: wrap; gap: .3rem; }
  .tc-dims span { font-size: .7rem; padding: .08rem .45rem; border-radius: 999px; color: var(--surface); font-weight: 600; }
  .tc-bar { height: .35rem; border-radius: 999px; background: var(--track); overflow: hidden; }
  .tc-bar span { display: block; height: 100%; width: 0; background: var(--accent); transition: width .4s; }
  .tcard.done .tc-bar span { background: var(--ok); }
  .tc-mine { align-self: flex-start; font-size: .68rem; padding: .05rem .45rem; border-radius: 999px;
             background: var(--accent-soft); color: var(--accent); font-weight: 600; }
  #noTopics { grid-column: 1 / -1; }
  #newTopic { max-width: 560px; }
  #newTopic .field { display: flex; flex-direction: column; gap: .35rem; margin-bottom: .8rem; font-size: var(--fs-2); }
  #newTopic .field > span { color: var(--muted); font-size: var(--fs-1); font-weight: 600; text-transform: uppercase; letter-spacing: .04em; }
  #ntDims { display: flex; flex-direction: column; gap: .4rem; }
  .ntrow { display: grid; grid-template-columns: .8rem minmax(0,1fr) auto auto; gap: .45rem; align-items: center; }
  .ntrow .sw { width: .8rem; height: .8rem; border-radius: 4px; }
  #ntAddDim { align-self: flex-start; }
  #newTopic .hint { font-size: var(--fs-1); color: var(--muted); margin: 0 0 .9rem; }

  @media (max-width: 640px) {
    #crumbTitle { max-width: 9rem; }
  }
  @media (prefers-reduced-motion: reduce) {
    .tcard, .tc-bar span { transition: none; }
  }
</style>
</head>
<body>
<header class="top">
  <a href="/" class="brand" data-nav><span class="logo" aria-hidden="true"></span>Alignment</a>
  <nav id="crumb" hidden><a href="/" data-nav>topics</a><span id="crumbSep" class="muted" aria-hidden="true">/</span><span id="crumbTitle"></span></nav>
  <span class="spacer"></span>
  <div id="identity">
    <span class="dot" aria-hidden="true"></span>
    <span id="idText">joined as <b id="idName"></b></span>
    <input id="idInput" maxlength="40" aria-label="your name" hidden>
    <button type="button" id="idChange" class="link">change</button>
  </div>
  <div class="subbar">
    <nav id="tabs" class="tabs" role="tablist" hidden>
      <button type="button" id="tabRate" role="tab">Rate</button>
      <button type="button" id="tabBoard" role="tab">Board</button>
      <button type="button" id="tabSetup" role="tab" hidden>Setup</button>
    </nav>
    <ol id="phase" hidden aria-label="phase">
      <li data-step="setup">Setup</li><li class="arr" aria-hidden="true">→</li>
      <li data-step="rating">Rating</li><li class="arr" aria-hidden="true">→</li>
      <li data-step="results">Results</li>
    </ol>
  </div>
</header>
<section id="topics" hidden>
  <div class="landing-head">
    <h1>Topics</h1>
    <p class="muted">Open a topic to rate its ideas, or start a new one.</p>
  </div>
  <div id="topicCards" class="cards"><p id="noTopics" class="muted" hidden>No topics yet. Start the first one below.</p></div>
  <form id="newTopic" class="pane" autocomplete="off">
    <h2>New topic</h2>
    <label class="field"><span>Title</span><input id="ntTitle" maxlength="200" placeholder="e.g. Q4 roadmap bets"></label>
    <div class="field">
      <span>Dimensions</span>
      <div id="ntDims"></div>
      <button type="button" id="ntAddDim">+ add dimension</button>
    </div>
    <p class="hint">Value dimensions raise an idea's score; cost dimensions divide it. Weights and anchors are set in Setup.</p>
    <button class="primary">Create topic</button>
  </form>
</section>
<section id="missing" class="pane" hidden>
  <h2>No such topic</h2>
  <p class="muted">There is no topic <code id="missingId"></code> here.</p>
  <a href="/" data-nav>back to topics</a>
</section>
<p id="loading" class="muted" hidden>loading…</p>
<script>
/* ═══ SHARED SCRIPT CONTRACT (computenet-0dvra.1) ═════════════════════════════
 * The view slices (SetupView.kt, RateView.kt, BoardView.kt) code against these
 * names and nothing else of the shell. Keep them stable; widen by adding.
 *
 * state            the last /events frame: {topics:[{id,title,creator,ideas,
 *                  boardVisibility,revealed,dimensions:[{id,name,weight,direction,
 *                  lowLabel,highLabel}]}], ideas:[{topic,id,title,description,
 *                  proposer}], ratings:[…], aggregates:{tid:{weights,participants,
 *                  ideas:[…]}}}. Replaced wholesale on every frame.
 * loaded           false until the first frame; render nothing topic-specific before.
 * me()             the viewer's name (sessionStorage.participant). Only the
 *                  #identity chip changes it; a change clears meCache and re-renders.
 * topicId()        the id after /t/ in location.pathname, else null (landing).
 * currentTopic()   state.topics entry for topicId(), or undefined.
 * isCreator(t)     true when t exists and me() === t.creator.
 * mayAddIdea(t)    t.ideas !== 'facilitator' || isCreator(t).
 * send(method, url, body)
 *                  JSON fetch; resolves the parsed body, alerts j.error and rejects
 *                  on a non-2xx.
 * fetchMe(tid)     Promise of the /topics/{tid}/me view ({topic, participant,
 *                  ideas:[{id,title,description,ratings:{dim:number|null},rated,
 *                  total}]}); the newest response for the current name is stored in
 *                  meCache[tid]. Use it instead of fetching /me yourself.
 * meCache          {tid: last /me view}.
 * myProgress(tid)  {rated, total} summed over meCache[tid].ideas, or null if uncached.
 * boardGate(t)     {open, reason, rated, total, pending} (0dvra-D10): done = every
 *                  cached idea has rated === total (vacuously true with no ideas);
 *                  open = done && (t.boardVisibility !== 'after-reveal' || t.revealed);
 *                  reason 'rate' (not done) | 'reveal' (done, unrevealed) | null (open).
 *                  Until meCache has the topic: {open:false, reason:'rate', rated:0,
 *                  total:0, pending:true}; otherwise pending is false. Applies to the
 *                  creator too.
 * dimColour(t, d)  'var(--value-N)' or 'var(--cost-N)' by d.direction (null counts as
 *                  value), N = 1 + (index of d among t's dimensions of that direction,
 *                  in dimension order) mod 4 (0dvra-D13). Unrated uses var(--unrated).
 * editing(root)    true when document.activeElement is an INPUT/TEXTAREA/SELECT inside
 *                  root — the focus guard: never rebuild a row being edited.
 * showTab(name)    'rate' | 'board' | 'setup'; persists sessionStorage.tab. 'setup'
 *                  shows as 'rate' for a non-creator.
 * scheduleRender() coalesces to one render per 60 ms tick. A tick renders the header
 *                  (chip, crumb, tabs), then on / the landing, else — once the topic
 *                  is known — renderSetup(), renderRate(), renderBoard() (each called
 *                  whether or not its tab is showing; Rate returns early when hidden,
 *                  Board lays itself out while hidden), renderPhase(); then fetchMe()
 *                  for the topic and, when it lands, renderBoard() and renderPhase()
 *                  again. Each render is isolated: one throwing does not stop the rest.
 * DOM              header: #identity (chip), #crumb, then a second row holding the
 *                  tab bar #tabs (#tabRate, #tabBoard, #tabSetup — the last shown only
 *                  when isCreator(currentTopic())) and #phase; landing #topics;
 *                  #missing; view roots #setup, #rate, #board (the Board's first
 *                  child #gate, empty and hidden, is its gate card root). The shell toggles the view roots'
 *                  `hidden`; views never do.
 * CSS              tokens --accent, --accent-soft, --accent-ink, --bg, --surface,
 *                  --surface-2, --ink, --muted, --line, --ok, --warn, --warn-soft,
 *                  --value-1..4, --cost-1..4, --unrated, --track, --shadow; scale
 *                  --radius, --radius-lg, --fs-1..4. Classes .pane (a view), .card
 *                  (an item inside it), button.primary, button.link, .muted, .num.
 *                  No literal colour outside the token blocks.
 * Scope            every slice's top-level let/const/function shares one global scope:
 *                  a view keeps its private globals prefixed with its name (rate…,
 *                  board…, setup…) or inside functions. Shell-internal names not
 *                  listed here (el, guard, go, route, renderShell, renderPhase, …)
 *                  may change; do not call them from a view.
 * ════════════════════════════════════════════════════════════════════════════ */
let state = { topics: [], ideas: [], ratings: [], aggregates: {} };
let loaded = false;
const el = id => document.getElementById(id);

if (!(sessionStorage.participant || '').trim()) sessionStorage.participant = 'p-' + Math.random().toString(36).slice(2, 6);
const me = () => (sessionStorage.participant || '').trim();

function topicId() {
  const m = location.pathname.match(/^\/t\/([^/]+)\/?/);
  if (!m) return null;
  try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
}
const currentTopic = () => { const id = topicId(); return id === null ? undefined : state.topics.find(t => t.id === id); };
const isCreator = t => !!t && me() !== '' && me() === t.creator;
const mayAddIdea = t => t.ideas !== 'facilitator' || isCreator(t);

const send = (method, url, body) => fetch(url, {
  method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
}).then(r => r.ok ? r.json() : r.json().then(j => { alert(j.error || r.status); throw j; }));

// ── the viewer's own /me view per topic ─────────────────────────────────────
const meCache = {};
const meSeq = {};
function fetchMe(tid) {
  const who = me();
  const seq = meSeq[tid] = (meSeq[tid] || 0) + 1;
  return fetch('/topics/' + encodeURIComponent(tid) + '/me?participant=' + encodeURIComponent(who))
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(view => {
      if (seq === meSeq[tid] && who === me()) meCache[tid] = view;
      return view;
    });
}
function myProgress(tid) {
  const view = meCache[tid];
  if (!view) return null;
  let rated = 0, total = 0;
  for (const i of view.ideas) { rated += i.rated; total += i.total; }
  return { rated: rated, total: total };
}
function boardGate(t) {
  const p = t ? myProgress(t.id) : null;
  if (!p) return { open: false, reason: 'rate', rated: 0, total: 0, pending: true };
  const done = meCache[t.id].ideas.every(i => i.rated === i.total);
  const open = done && (t.boardVisibility !== 'after-reveal' || t.revealed === true);
  return { open: open, reason: open ? null : (done ? 'reveal' : 'rate'), rated: p.rated, total: p.total, pending: false };
}

function dimColour(t, d) {
  const cost = d.direction === 'cost';
  const same = (t ? t.dimensions : [d]).filter(x => (x.direction === 'cost') === cost);
  const i = Math.max(0, same.findIndex(x => x.id === d.id));
  return 'var(--' + (cost ? 'cost' : 'value') + '-' + (1 + i % 4) + ')';
}

function editing(root) {
  const a = document.activeElement;
  return !!root && !!a && root.contains(a) && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.tagName === 'SELECT');
}

function guard(fn) { try { fn(); } catch (e) { console.error(e); } }

// ── routing: / is the landing, /t/{id} a topic ──────────────────────────────
let pendingTopic = null; // just created: its frame may lag the POST response
function go(path) {
  if (location.pathname !== path) history.pushState(null, '', path);
  window.scrollTo(0, 0);
  route();
}
function route() { renderShell(); scheduleRender(); }
document.addEventListener('click', e => {
  const a = e.target.closest('a[data-nav]');
  if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  e.preventDefault();
  go(a.getAttribute('href'));
});

// ── tabs ────────────────────────────────────────────────────────────────────
const TABS = [['rate', 'tabRate'], ['board', 'tabBoard'], ['setup', 'tabSetup']];
function activeTab() {
  const want = sessionStorage.tab;
  if (want === 'board') return 'board';
  if (want === 'setup' && isCreator(currentTopic())) return 'setup';
  return 'rate';
}
function showTab(name) {
  sessionStorage.tab = name;
  renderShell();
  const n = activeTab();
  guard(n === 'rate' ? renderRate : n === 'board' ? renderBoard : renderSetup);
}
for (const [name, id] of TABS) el(id).onclick = () => showTab(name);

// the frame for everything outside the views: chip, crumb, landing/missing, tab bar, view roots
function renderShell() {
  renderIdentity();
  const tid = topicId();
  const t = currentTopic();
  if (t && pendingTopic === tid) pendingTopic = null;
  const known = tid !== null && loaded && !!t;
  el('topics').hidden = tid !== null;
  el('missing').hidden = !(tid !== null && loaded && !t && pendingTopic !== tid);
  if (!el('missing').hidden) el('missingId').textContent = tid;
  el('loading').hidden = !(tid !== null && !known && el('missing').hidden);
  el('crumb').hidden = tid === null;
  el('crumbTitle').textContent = t ? t.title : '';
  el('crumbSep').hidden = !t;
  document.title = t ? t.title + ' · alignment' : 'alignment';
  el('tabs').hidden = !known;
  el('phase').hidden = !known;
  el('tabSetup').hidden = !(known && isCreator(t));
  const name = activeTab();
  for (const [n, id] of TABS) {
    const on = n === name;
    el(id).classList.toggle('active', on);
    el(id).setAttribute('aria-selected', String(on));
    const root = el(n);
    if (root) root.hidden = !known || !on;
  }
}

// ── phase indicator: Setup → Rating → Results (0dvra-D12), indicator only ──
function renderPhase() {
  const t = currentTopic();
  if (!t) return;
  const step = boardGate(t).open ? 'results' : state.ideas.some(i => i.topic === t.id) ? 'rating' : 'setup';
  const order = ['setup', 'rating', 'results'];
  for (const li of el('phase').querySelectorAll('li[data-step]')) {
    const k = order.indexOf(li.dataset.step), cur = order.indexOf(step);
    li.classList.toggle('current', k === cur);
    li.classList.toggle('done', k < cur);
    if (k === cur) li.setAttribute('aria-current', 'step'); else li.removeAttribute('aria-current');
  }
}

// ── identity chip: the only way to change who you are ──────────────────────
let editingName = false;
function renderIdentity() { if (!editingName) el('idName').textContent = me(); }
el('idChange').onclick = () => {
  editingName = true;
  const input = el('idInput');
  input.value = me();
  el('idText').hidden = true; el('idChange').hidden = true; input.hidden = false;
  input.focus(); input.select();
};
function endNameEdit(commit) {
  if (!editingName) return;
  editingName = false;
  const input = el('idInput');
  const v = input.value.trim();
  input.hidden = true; el('idText').hidden = false; el('idChange').hidden = false;
  if (commit && v && v !== me()) {
    sessionStorage.participant = v;
    for (const k of Object.keys(meCache)) delete meCache[k];
  }
  renderIdentity();
  route();
}
el('idInput').onkeydown = e => {
  if (e.key === 'Enter') { e.preventDefault(); endNameEdit(true); }
  else if (e.key === 'Escape') { e.preventDefault(); endNameEdit(false); }
};
el('idInput').onblur = () => endNameEdit(true);

// ── landing: one card per topic, plus New topic ────────────────────────────
const topicCards = new Map(); // topic id -> card node, updated in place
function renderLanding() {
  const box = el('topicCards');
  el('noTopics').hidden = !loaded || state.topics.length > 0;
  const seen = new Set();
  for (const t of state.topics) {
    seen.add(t.id);
    let c = topicCards.get(t.id);
    if (!c) {
      c = document.createElement('a');
      c.className = 'tcard';
      c.setAttribute('data-nav', '');
      c.innerHTML = '<span class="tc-title"></span><span class="tc-mine" hidden>you facilitate</span>' +
                    '<span class="tc-meta"></span><span class="tc-dims"></span>' +
                    '<span class="tc-bar"><span></span></span><span class="tc-prog">…</span>';
      topicCards.set(t.id, c);
    }
    c.href = '/t/' + encodeURIComponent(t.id);
    c.querySelector('.tc-title').textContent = t.title;
    c.querySelector('.tc-mine').hidden = !isCreator(t);
    const n = t.dimensions.length, m = state.ideas.filter(i => i.topic === t.id).length;
    c.querySelector('.tc-meta').textContent =
      n + (n === 1 ? ' dimension' : ' dimensions') + ' · ' + m + (m === 1 ? ' idea' : ' ideas');
    const dims = c.querySelector('.tc-dims');
    const shape = t.dimensions.map(d => d.id + ':' + d.direction + ':' + d.name).join(',');
    if (dims.dataset.shape !== shape) {
      dims.dataset.shape = shape;
      dims.innerHTML = '';
      for (const d of t.dimensions) {
        const s = document.createElement('span');
        s.textContent = d.name; s.style.background = dimColour(t, d);
        s.title = d.name + ' (' + (d.direction === 'cost' ? 'cost' : 'value') + ')';
        dims.appendChild(s);
      }
    }
    box.insertBefore(c, el('noTopics'));
    paintProgress(t.id);
    const tid = t.id;
    fetchMe(tid).then(() => paintProgress(tid), () => {});
  }
  for (const [id, c] of topicCards) if (!seen.has(id)) { topicCards.delete(id); c.remove(); }
}
function paintProgress(tid) {
  const c = topicCards.get(tid);
  const p = myProgress(tid);
  if (!c || !p) return;
  c.querySelector('.tc-prog').textContent = p.rated + ' of ' + p.total + ' rated';
  c.querySelector('.tc-bar span').style.width = (p.total ? 100 * p.rated / p.total : 0) + '%';
  c.classList.toggle('done', p.total > 0 && p.rated === p.total);
}

function addDimRow(name, direction) {
  const row = document.createElement('div');
  row.className = 'ntrow';
  row.innerHTML = '<span class="sw" aria-hidden="true"></span><input maxlength="80" aria-label="dimension name">' +
                  '<select aria-label="direction"><option value="value">value</option><option value="cost">cost</option></select>' +
                  '<button type="button" class="link" aria-label="remove dimension">remove</button>';
  row.querySelector('input').placeholder = name;
  row.querySelector('select').value = direction;
  row.querySelector('select').onchange = paintDimRows;
  row.querySelector('button').onclick = () => { row.remove(); paintDimRows(); };
  el('ntDims').appendChild(row);
  paintDimRows();
  return row;
}
// the swatches preview each row's family colour exactly as dimColour will assign it
function paintDimRows() {
  const rows = [...el('ntDims').children];
  const dims = rows.map((r, i) => ({ id: String(i), direction: r.querySelector('select').value }));
  const t = { dimensions: dims };
  rows.forEach((r, i) => {
    r.querySelector('.sw').style.background = dimColour(t, dims[i]);
    r.querySelector('button').hidden = rows.length === 1;
  });
}
el('ntAddDim').onclick = () => addDimRow('dimension name', 'value').querySelector('input').focus();
addDimRow('e.g. Impact', 'value');
addDimRow('e.g. Effort', 'cost');
el('newTopic').onsubmit = e => {
  e.preventDefault();
  const title = el('ntTitle').value.trim();
  const dims = [...el('ntDims').children]
    .map(r => ({ name: r.querySelector('input').value.trim(), direction: r.querySelector('select').value }))
    .filter(d => d.name);
  if (!title) { alert('give the topic a title'); return; }
  if (!dims.length) { alert('name at least one dimension'); return; }
  send('POST', '/topics', { creator: me(), title: title, dimensions: dims }).then(j => {
    el('ntTitle').value = '';
    el('ntDims').innerHTML = '';
    addDimRow('e.g. Impact', 'value');
    addDimRow('e.g. Effort', 'cost');
    sessionStorage.tab = 'setup';
    pendingTopic = j.id;
    go('/t/' + encodeURIComponent(j.id));
  }, () => {});
};

// ── render loop ─────────────────────────────────────────────────────────────
// SSE frames arrive in bursts; coalesce on a 60 ms tick so a burst re-renders once.
let renderPending = false;
function scheduleRender() {
  if (renderPending) return;
  renderPending = true;
  setTimeout(() => { renderPending = false; renderTick(); }, 60);
}
function renderTick() {
  renderShell();
  const tid = topicId();
  if (tid === null) { guard(renderLanding); return; }
  if (!loaded || !currentTopic()) return;
  guard(renderSetup); guard(renderRate); guard(renderBoard); guard(renderPhase);
  fetchMe(tid).then(() => { if (topicId() === tid) { guard(renderBoard); guard(renderPhase); } }, () => {});
}
</script>
"""

private const val SHELL_TAIL = """
<script>
// boot: every slice's functions exist by now
window.addEventListener('popstate', route);
new EventSource('/events').onmessage = e => {
  state = JSON.parse(e.data);
  loaded = true;
  scheduleRender();
};
route();
</script>
</body>
</html>
"""
