package civictech.demo.alignment

/**
 * The experimental Compare view (computenet-5eefp, ALN2.4), one slice of the served [PAGE]: its
 * `<style>`, the `<section id="compare">` root and the `<script>` defining `renderCompare()`.
 * Registered in the shell by computenet-5eefp.1; filled in by computenet-5eefp.2.
 *
 * The participant picks ONE dimension (`#cmpPicker`, persisted per topic in
 * `sessionStorage['cmpDim:' + topicId]`) and places every idea, as a compact title chip, on that
 * dimension's continuous 1–9 axis (`#cmpAxis`); unrated ideas wait in `#cmpTray`. A chip with
 * rating v sits at fraction (v − 1) / 8 of the track; a drop (or a click on the axis with a chip
 * selected) posts `1 + 8 · clamp(offset / length, 0, 1)` rounded to thousandths, a drop on the
 * tray posts null — exactly the request the Rate view's slider makes, so both views read one /me
 * view and write one endpoint (5eefp-D3…D6). No snapping and no numeric readout anywhere.
 *
 * Privacy (5eefp-D8): own chips always come from the viewer's /me view. The frame's
 * per-participant rating list is read in exactly ONE place, inside
 * `if (boardGate(t).open && cmpEl('cmpOthers').checked)`, for OTHER participants' ghost chips;
 * the frame's aggregates are never read.
 *
 * Layout (5eefp-D9…D11): own and ghost chips are laid into lanes on the cross axis so none
 * overlap (greedy, by measured size); moves animate by FLIP on the CSS `translate` property
 * (~180 ms, off under `prefers-reduced-motion: reduce`), fresh chips fade in. At most 640 px wide
 * the axis is vertical (1 at the top), which the script reads from the `--cmp-vertical` custom
 * property the media query sets, so CSS stays the single source of the orientation.
 *
 * Limits: a chip whose centred box would overflow an end of the axis is clamped inside it; its
 * exact position is then only the stem's (a thin line from the track to the chip), not the
 * chip's centre. On a very narrow viewport with many lanes the vertical axis scrolls sideways.
 *
 * Shared helper contract: the comment block at the top of the shell's script in [AlignmentPage.kt].
 * No `$` anywhere (a plain raw string, no template literals); no literal colour — only `:root`
 * tokens and `dimColour(t, d)`. Private globals get the `cmp` prefix; nothing shell-dependent runs
 * at top level (only static-markup wiring).
 */
internal const val COMPARE_VIEW = """
<style>
  #compare .cmpHint { font-size: var(--fs-1); margin: 0 0 .7rem; }
  #cmpPicker { display: flex; flex-wrap: wrap; gap: .4rem; margin-bottom: .8rem; }
  #cmpPicker button { border: 1.5px solid var(--cmp-c, var(--line)); border-radius: 999px; background: var(--surface);
                      color: var(--ink); font-weight: 600; }
  #cmpPicker button[aria-selected="true"] { background: var(--cmp-c); color: var(--surface); }
  #cmpDirection { margin: 0 0 .5rem; font-size: var(--fs-2); font-weight: 600; }
  .cmpArrow { display: inline-block; }
  #cmpAxis { --cmp-vertical: 0; position: relative; min-height: 7rem; margin: 0 0 .7rem; border-radius: var(--radius);
             transition: background-color .18s; }
  #cmpAxis .cmpTrack { position: absolute; left: 3rem; right: 3rem; top: 1.9rem; height: 6px; border-radius: 3px;
                       background: var(--track); }
  #cmpAxis .cmpTick { position: absolute; left: calc(var(--f) * 100%); top: -5px; width: 2px; height: 16px;
                      margin-left: -1px; border-radius: 1px; background: var(--line); }
  #cmpLow, #cmpHigh { position: absolute; top: 0; max-width: 45%; font-size: var(--fs-1); color: var(--muted);
                      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  #cmpLow { left: 0; }
  #cmpHigh { right: 0; text-align: right; }
  #cmpAxis.cmpTarget, #cmpTray.cmpTarget { background: var(--accent-soft); }
  .cmpChip { position: relative; display: inline-flex; align-items: center; gap: .3rem; width: max-content;
             max-width: 11rem; padding: .22rem .5rem; background: var(--surface); color: var(--ink);
             border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow);
             font-size: var(--fs-2); line-height: 1.3; cursor: grab; touch-action: none; user-select: none;
             -webkit-user-select: none; transition: translate .18s ease, opacity .18s ease; }
  #cmpAxis .cmpChip { position: absolute; left: 0; top: 0; }
  #cmpAxis .cmpChip::before { content: ''; position: absolute; left: var(--pin, 50%); bottom: 100%; width: 1px;
                              height: var(--stem, 0); background: var(--muted); opacity: .45; pointer-events: none; }
  .cmpChip .cmpTitle { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .cmpChip .cmpInfo { flex: 0 0 auto; width: 1.25rem; height: 1.25rem; padding: 0; border-radius: 50%;
                      font-size: .7rem; font-style: italic; font-weight: 700; line-height: 1; color: var(--accent); }
  .cmpChip.selected { outline: 2px solid var(--accent); outline-offset: 1px; }
  .cmpChip.dragging { cursor: grabbing; z-index: 1000 !important; }
  #cmpAxis .cmpChip.dragging::before { display: none; }
  .cmpChip.cmpEnter { opacity: 0; }
  .cmpChip.other { opacity: .6; border-style: dashed; background: none; box-shadow: none; color: var(--muted);
                   cursor: default; }
  #cmpOthersWrap { display: flex; align-items: center; gap: .4rem; margin: 0 0 .7rem; font-size: var(--fs-2); }
  #cmpTray { display: flex; flex-wrap: wrap; align-items: center; gap: .45rem; min-height: 3.4rem; padding: .6rem .7rem;
             border: 1.5px dashed var(--line); border-radius: var(--radius); transition: background-color .18s; }
  #cmpTray h3 { flex: 1 0 100%; margin: 0; font-size: var(--fs-1); font-weight: 600; text-transform: uppercase;
                letter-spacing: .04em; color: var(--muted); }
  #cmpTray .cmpEmpty { margin: 0; font-size: var(--fs-2); }
  #cmpDesc { position: fixed; z-index: 1100; left: 0; top: 0; width: max-content; max-width: min(22rem, calc(100vw - 16px));
             max-height: 50vh; overflow: auto; padding: .7rem .9rem; background: var(--surface); color: var(--ink);
             border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); font-size: var(--fs-2); }
  #cmpDesc h4 { margin: 0 0 .35rem; font-size: var(--fs-2); }
  #cmpDesc p { margin: 0; white-space: pre-wrap; word-break: break-word; }
  @media (max-width: 640px) {
    #cmpAxis { --cmp-vertical: 1; height: 30rem; min-height: 0; overflow-x: auto; overflow-y: hidden; }
    #cmpAxis .cmpTrack { left: .5rem; right: auto; top: 2.4rem; bottom: 2.4rem; width: 6px; height: auto; }
    #cmpAxis .cmpTick { left: -5px; top: calc(var(--f) * 100%); width: 16px; height: 2px; margin: -1px 0 0 0; }
    #cmpLow { top: 0; left: 0; }
    #cmpHigh { top: auto; bottom: 0; left: 0; right: auto; text-align: left; }
    #cmpLow, #cmpHigh { max-width: 100%; }
    #cmpAxis .cmpChip::before { left: auto; right: 100%; bottom: auto; top: var(--pin, 50%); width: var(--stem, 0); height: 1px; }
    .cmpChip { max-width: 9rem; }
    .cmpArrow { transform: rotate(90deg); }
  }
  @media (prefers-reduced-motion: reduce) {
    .cmpChip, #cmpAxis, #cmpTray { transition: none; }
  }
</style>
<section id="compare" class="pane view" hidden>
  <div id="cmpPicker" role="tablist" aria-label="dimension"></div>
  <p class="muted cmpHint">Drag each title to where it sits on this dimension, or click a title and then a point on the axis. Drop it on "unplaced" to clear it.</p>
  <p id="cmpDirection" class="muted"></p>
  <div id="cmpAxis"><span id="cmpLow"></span><span id="cmpHigh"></span><div class="cmpTrack" aria-hidden="true"></div></div>
  <label id="cmpOthersWrap" hidden><input type="checkbox" id="cmpOthers"> show everyone's placements</label>
  <div id="cmpTray"><h3>unplaced</h3><p class="cmpEmpty muted" hidden></p></div>
  <div id="cmpDesc" role="dialog" hidden></div>
</section>
<script>
// ── Compare (experimental): one dimension, every idea on its continuous 1..9 axis ──
// Own placements come from the viewer's /me view (fetchMe); writes are Rate's
// POST /topics/{t}/rate. Other participants' placements are read only inside
// the overlay gate in cmpPaint, and nothing aggregate is ever read here.
const cmpEl = id => document.getElementById(id);
let cmpSeq = 0;            // stale-response guard, like Rate's rateSeq
let cmpDragging = false;   // set from pointerdown to drop/cancel: renderCompare stands still
let cmpPosting = 0;        // rate POSTs in flight: their own resolution re-renders
let cmpSelected = null;    // idea id picked by the click fallback (survives re-render)
let cmpView = null;        // last /me view painted
let cmpTid = null;         // topic the chip maps belong to
let cmpActive = null;      // active dimension id
let cmpDescFor = null;     // idea id whose description is open
const cmpChips = new Map();  // idea id -> own chip node, reused across renders
const cmpGhosts = new Map(); // participant + newline + idea id -> ghost chip node
const CMP_GAP = 6;           // px between chips in a lane and between lanes

(function cmpTicks() {
  const track = cmpEl('cmpAxis').querySelector('.cmpTrack');
  for (let k = 0; k < 9; k++) {
    const tick = document.createElement('span');
    tick.className = 'cmpTick';
    tick.style.setProperty('--f', String(k / 8));
    track.appendChild(tick);
  }
})();

function renderCompare() {
  if (cmpEl('compare').hidden) return;
  if (cmpDragging || cmpPosting > 0) return;
  const tid = topicId();
  if (!tid || !me()) return;
  if (!currentTopic()) return;
  const seq = ++cmpSeq;
  fetchMe(tid).then(view => {
    if (seq !== cmpSeq || cmpDragging || cmpPosting > 0 || topicId() !== tid) return;
    const t = currentTopic();
    if (!t || cmpEl('compare').hidden) return;
    cmpView = view;
    cmpPaint(t, view, null);
  }, () => {});
}

// repaint from the cached /me view, no fetch (resize, overlay toggle, spring-back)
function cmpRepaint() {
  if (cmpDragging || cmpEl('compare').hidden) return;
  const t = currentTopic();
  if (!t || !cmpView || cmpView.topic !== t.id) return;
  cmpPaint(t, cmpView, null);
}

// override: {idea, value} painted in place of the view's value (the optimistic drop)
function cmpPaint(t, view, override) {
  cmpCloseDesc();
  const axis = cmpEl('cmpAxis'), tray = cmpEl('cmpTray');
  if (cmpTid !== t.id) {
    for (const n of cmpChips.values()) n.remove();
    for (const n of cmpGhosts.values()) n.remove();
    cmpChips.clear(); cmpGhosts.clear();
    cmpSelected = null; cmpTid = t.id;
  }
  const dims = t.dimensions;
  const key = 'cmpDim:' + t.id;
  const d = dims.find(x => x.id === sessionStorage[key]) || dims[0];
  cmpRenderPicker(t, d);
  cmpRenderCaption(t, d);
  cmpActive = d ? d.id : null;
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // FLIP, first: where every chip is drawn now
  const before = new Map();
  for (const n of cmpChips.values()) { before.set(n, n._before || n.getBoundingClientRect()); n._before = null; }
  for (const n of cmpGhosts.values()) before.set(n, n.getBoundingClientRect());

  const fresh = [];
  const onAxis = [];
  const seen = new Set();
  for (const idea of (d ? view.ideas : [])) {
    seen.add(idea.id);
    let chip = cmpChips.get(idea.id);
    if (!chip) { chip = cmpMakeChip(idea.id); cmpChips.set(idea.id, chip); fresh.push(chip); }
    chip.querySelector('.cmpTitle').textContent = idea.title;
    chip.title = idea.title;
    chip.querySelector('.cmpInfo').hidden = !idea.description;
    chip.classList.toggle('selected', cmpSelected === idea.id);
    let v = idea.ratings[d.id];
    if (override && override.idea === idea.id) v = override.value;
    if (v === null || v === undefined) {
      chip.style.left = ''; chip.style.top = ''; chip.style.zIndex = '';
      if (chip.parentNode !== tray) tray.appendChild(chip);
    } else {
      if (chip.parentNode !== axis) axis.appendChild(chip);
      onAxis.push({ node: chip, v: v });
    }
  }
  for (const [id, n] of cmpChips) if (!seen.has(id)) { cmpChips.delete(id); n.remove(); }
  if (cmpSelected !== null && !seen.has(cmpSelected)) cmpSelected = null;

  // the overlay: hidden and unchecked until the viewer's Board is open
  const wrap = cmpEl('cmpOthersWrap');
  wrap.hidden = !(d && boardGate(t).open);
  if (wrap.hidden) cmpEl('cmpOthers').checked = false;
  const ghostSeen = new Set();
  if (d && boardGate(t).open && cmpEl('cmpOthers').checked) {
    const titles = new Map(view.ideas.map(i => [i.id, i.title]));
    const others = state.ratings.filter(r => r.topic === t.id && r.dim === d.id && r.participant !== me());
    for (const r of others) {
      if (!titles.has(r.idea) || typeof r.value !== 'number') continue;
      const k = r.participant + '\n' + r.idea;
      ghostSeen.add(k);
      let g = cmpGhosts.get(k);
      if (!g) {
        g = document.createElement('div');
        g.className = 'cmpChip other cmpEnter';
        g.innerHTML = '<span class="cmpTitle"></span>';
        cmpGhosts.set(k, g); fresh.push(g);
        axis.appendChild(g);
      }
      g.querySelector('.cmpTitle').textContent = titles.get(r.idea);
      g.title = r.participant;
      onAxis.push({ node: g, v: r.value });
    }
  }
  for (const [k, n] of cmpGhosts) if (!ghostSeen.has(k)) { cmpGhosts.delete(k); n.remove(); }

  cmpLayout(axis, onAxis);
  const empty = tray.querySelector('.cmpEmpty');
  const inTray = [...cmpChips.values()].filter(n => n.parentNode === tray).length;
  empty.textContent = !d ? 'this topic has no dimensions yet' : view.ideas.length ? 'every idea is placed' : 'no ideas yet — add them in Rate';
  empty.hidden = inTray > 0;

  // FLIP, last/invert/play: slide each chip from where it was to where it now is
  const moved = [];
  if (!reduce) {
    for (const [n, b] of before) {
      if (!n.isConnected || !b || !b.width) continue;
      const a = n.getBoundingClientRect();
      if (!a.width) continue;
      const dx = b.left - a.left, dy = b.top - a.top;
      if (Math.abs(dx) < .5 && Math.abs(dy) < .5) continue;
      n.style.transition = 'none';
      n.style.translate = dx + 'px ' + dy + 'px';
      moved.push(n);
    }
  }
  if (moved.length || fresh.length) void document.body.offsetWidth; // one forced flush, as Board's enter
  for (const n of moved) { n.style.transition = ''; n.style.translate = '0px 0px'; }
  for (const n of fresh) n.classList.remove('cmpEnter');
}

function cmpRenderPicker(t, active) {
  const box = cmpEl('cmpPicker');
  const shape = t.id + '|' + t.dimensions.map(d => d.id + ':' + d.name + ':' + d.direction).join(',');
  if (box.dataset.shape !== shape) {
    box.dataset.shape = shape;
    box.innerHTML = '';
    for (const d of t.dimensions) {
      const b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('role', 'tab');
      b.dataset.dim = d.id;
      b.textContent = d.name;
      b.style.setProperty('--cmp-c', dimColour(t, d));
      b.onclick = () => {
        sessionStorage['cmpDim:' + t.id] = d.id;
        cmpSelected = null;
        cmpRepaint();
        renderCompare();
      };
      box.appendChild(b);
    }
  }
  for (const b of box.children) b.setAttribute('aria-selected', String(!!active && b.dataset.dim === active.id));
}

function cmpRenderCaption(t, d) {
  const cap = cmpEl('cmpDirection');
  cap.innerHTML = '';
  cmpEl('cmpLow').textContent = d && d.lowLabel ? '1 · ' + d.lowLabel : '1';
  cmpEl('cmpHigh').textContent = d && d.highLabel ? '9 · ' + d.highLabel : '9';
  if (!d) { cap.style.color = ''; cap.textContent = 'this topic has no dimensions yet'; return; }
  const arrow = document.createElement('span');
  arrow.className = 'cmpArrow'; arrow.setAttribute('aria-hidden', 'true'); arrow.textContent = '→';
  cap.appendChild(arrow);
  cap.appendChild(document.createTextNode(d.direction === 'cost'
    ? ' higher ' + d.name + ' is more cost and lowers the score'
    : ' higher ' + d.name + ' raises the score'));
  cap.style.color = dimColour(t, d);
}

// lanes: sort by position; each chip takes the first lane whose last chip ends
// (plus a gap) before this one starts; lanes stack away from the track
function cmpLayout(axis, items) {
  const vertical = getComputedStyle(axis).getPropertyValue('--cmp-vertical').trim() === '1';
  const track = axis.querySelector('.cmpTrack');
  const t0 = vertical ? track.offsetTop : track.offsetLeft;
  const len = vertical ? track.offsetHeight : track.offsetWidth;
  const boxMain = vertical ? axis.clientHeight : axis.clientWidth;
  const margin = vertical ? cmpEl('cmpLow').offsetHeight + 4 : 0;
  const trackEnd = vertical ? track.offsetLeft + track.offsetWidth : track.offsetTop + track.offsetHeight;
  const crossStart = trackEnd + 14;
  for (const it of items) {
    const n = it.node;
    const title = n.querySelector('.cmpTitle').textContent;
    const w = n.offsetWidth || Math.min(176, 24 + 7 * title.length);
    const h = n.offsetHeight || 28;
    it.main = vertical ? h : w;
    it.cross = vertical ? w : h;
    it.pin = t0 + (it.v - 1) / 8 * len;
    it.start = Math.max(margin, Math.min(boxMain - margin - it.main, it.pin - it.main / 2));
  }
  items.sort((a, b) => a.start - b.start || a.v - b.v);
  const ends = [], cross = [];
  for (const it of items) {
    let k = ends.findIndex(e => e + CMP_GAP <= it.start);
    if (k < 0) { k = ends.length; ends.push(0); cross.push(0); }
    ends[k] = it.start + it.main;
    cross[k] = Math.max(cross[k], it.cross);
    it.lane = k;
  }
  const offs = [];
  let acc = crossStart;
  for (let k = 0; k < cross.length; k++) { offs.push(acc); acc += cross[k] + CMP_GAP; }
  for (const it of items) {
    const n = it.node, c = offs[it.lane];
    n.style.left = (vertical ? c : it.start) + 'px';
    n.style.top = (vertical ? it.start : c) + 'px';
    n.style.zIndex = String(Math.max(1, 50 - it.lane)); // a lower lane covers the stems of higher ones
    n.style.setProperty('--pin', (it.pin - it.start) + 'px');
    n.style.setProperty('--stem', (c - trackEnd) + 'px');
  }
  axis.style.height = vertical ? '' : Math.max(acc + 8, crossStart + 40) + 'px';
}

function cmpMakeChip(id) {
  const c = document.createElement('div');
  c.className = 'cmpChip cmpEnter';
  c.tabIndex = 0;
  c.innerHTML = '<span class="cmpTitle"></span><button type="button" class="cmpInfo" aria-label="description">i</button>';
  const info = c.querySelector('.cmpInfo');
  info.addEventListener('pointerdown', e => e.stopPropagation());
  info.addEventListener('click', e => { e.stopPropagation(); cmpToggleDesc(id, c); });
  c.addEventListener('pointerdown', e => cmpPress(e, c, id));
  c.addEventListener('click', e => {
    e.stopPropagation();
    if (c._dragged) { c._dragged = false; return; }
    cmpSelect(id);
  });
  c.addEventListener('keydown', e => {
    if (e.target !== c) return;
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); cmpSelect(id); }
  });
  return c;
}

function cmpSelect(id) {
  cmpSelected = cmpSelected === id ? null : id;
  for (const [k, n] of cmpChips) n.classList.toggle('selected', k === cmpSelected);
}

function cmpHit(x, y) {
  const inside = r => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
  if (inside(cmpEl('cmpAxis').getBoundingClientRect())) return 'axis';
  if (inside(cmpEl('cmpTray').getBoundingClientRect())) return 'tray';
  return null;
}

// the drop arithmetic (5eefp-D3): continuous, clamped, thousandths, no snapping
function cmpValueAt(x, y) {
  const axis = cmpEl('cmpAxis');
  const r = axis.querySelector('.cmpTrack').getBoundingClientRect();
  const vertical = getComputedStyle(axis).getPropertyValue('--cmp-vertical').trim() === '1';
  const f = vertical ? (y - r.top) / r.height : (x - r.left) / r.width;
  return Math.round((1 + 8 * Math.max(0, Math.min(1, f))) * 1000) / 1000;
}

function cmpPress(e, chip, id) {
  if (e.button !== 0 || cmpActive === null) return;
  chip._dragged = false;
  cmpDragging = true;
  const sx = e.clientX, sy = e.clientY;
  let moved = false, r0 = null;
  try { chip.setPointerCapture(e.pointerId); } catch (err) { /* synthetic events */ }
  const mark = where => {
    cmpEl('cmpAxis').classList.toggle('cmpTarget', where === 'axis');
    cmpEl('cmpTray').classList.toggle('cmpTarget', where === 'tray');
  };
  const move = ev => {
    const dx = ev.clientX - sx, dy = ev.clientY - sy;
    if (!moved && Math.abs(dx) + Math.abs(dy) < 5) return;
    if (!moved) {
      moved = true;
      cmpCloseDesc();
      r0 = chip.getBoundingClientRect();
      chip.classList.add('dragging');
      chip.style.transition = 'none';
      chip.style.translate = '';
      chip.style.width = r0.width + 'px';
      chip.style.position = 'fixed';
    }
    chip.style.left = (r0.left + dx) + 'px';
    chip.style.top = (r0.top + dy) + 'px';
    mark(cmpHit(ev.clientX, ev.clientY));
  };
  const end = (ev, cancelled) => {
    chip.removeEventListener('pointermove', move);
    chip.removeEventListener('pointerup', up);
    chip.removeEventListener('pointercancel', cancel);
    mark(null);
    if (!moved) { cmpDragging = false; return; } // a click: the click handler selects
    chip._dragged = true;
    chip._before = chip.getBoundingClientRect();
    chip.classList.remove('dragging');
    chip.style.position = ''; chip.style.width = ''; chip.style.transition = '';
    cmpDragging = false;
    const where = cancelled ? null : cmpHit(ev.clientX, ev.clientY);
    if (where === 'axis') cmpCommit(id, cmpValueAt(ev.clientX, ev.clientY));
    else if (where === 'tray') cmpCommit(id, null);
    else { cmpRepaint(); renderCompare(); } // spring back, then apply any frame that arrived mid-drag
  };
  const up = ev => end(ev, false);
  const cancel = ev => end(ev, true);
  chip.addEventListener('pointermove', move);
  chip.addEventListener('pointerup', up);
  chip.addEventListener('pointercancel', cancel);
}

// one write path for drag and click: Rate's exact request, then a fresh /me
function cmpCommit(id, value) {
  const t = currentTopic(), tid = topicId(), dim = cmpActive;
  if (!t || !tid || dim === null) return;
  cmpSelected = null;
  if (cmpView && cmpView.topic === t.id) cmpPaint(t, cmpView, { idea: id, value: value }); // optimistic
  cmpPosting++;
  const done = () => { cmpPosting--; renderCompare(); };
  send('POST', '/topics/' + encodeURIComponent(tid) + '/rate',
    { participant: me(), idea: id, dim: dim, value: value }).then(done, done);
}

function cmpToggleDesc(id, chip) {
  const box = cmpEl('cmpDesc');
  if (!box.hidden && cmpDescFor === id) { cmpCloseDesc(); return; }
  const idea = cmpView && cmpView.ideas.find(i => i.id === id);
  if (!idea || !idea.description) return;
  box.innerHTML = '';
  const h = document.createElement('h4'); h.textContent = idea.title;
  const p = document.createElement('p'); p.textContent = idea.description;
  box.appendChild(h); box.appendChild(p);
  box.setAttribute('aria-label', idea.title);
  box.hidden = false;
  cmpDescFor = id;
  const r = chip.getBoundingClientRect();
  const bw = box.offsetWidth, bh = box.offsetHeight;
  let x = r.left, y = r.bottom + 6;
  if (y + bh > window.innerHeight - 8) y = r.top - bh - 6;
  x = Math.max(8, Math.min(window.innerWidth - bw - 8, x));
  y = Math.max(8, Math.min(window.innerHeight - bh - 8, y));
  box.style.left = x + 'px';
  box.style.top = y + 'px';
}
function cmpCloseDesc() {
  const box = cmpEl('cmpDesc');
  if (box.hidden) return;
  box.hidden = true; box.innerHTML = ''; cmpDescFor = null;
}

// ── static wiring (no shell state is touched until an event fires) ──
cmpEl('cmpAxis').addEventListener('click', e => {
  if (cmpSelected === null || e.target.closest('.cmpChip:not(.other)')) return;
  cmpCommit(cmpSelected, cmpValueAt(e.clientX, e.clientY));
});
cmpEl('cmpTray').addEventListener('click', e => {
  if (cmpSelected === null || e.target.closest('.cmpChip')) return;
  cmpCommit(cmpSelected, null);
});
cmpEl('cmpOthers').addEventListener('change', () => cmpRepaint());
document.addEventListener('click', e => {
  const box = cmpEl('cmpDesc');
  if (!box.hidden && !box.contains(e.target)) cmpCloseDesc();
});
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape' || cmpEl('compare').hidden) return;
  cmpCloseDesc();
  if (cmpSelected !== null) cmpSelect(cmpSelected);
});
window.addEventListener('scroll', cmpCloseDesc, { passive: true });
let cmpResizeQueued = false;
window.addEventListener('resize', () => {
  if (cmpResizeQueued) return;
  cmpResizeQueued = true;
  requestAnimationFrame(() => { cmpResizeQueued = false; cmpRepaint(); });
});
</script>
"""
