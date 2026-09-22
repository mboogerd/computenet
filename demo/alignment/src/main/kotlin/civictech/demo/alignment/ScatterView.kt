package civictech.demo.alignment

/**
 * The value-vs-cost scatter (computenet-10mvq.3, ALN2.3, 10mvq-D12), concatenated onto
 * [BOARD_MAIN] to form [BOARD_VIEW]: its `<style>` and the `<script>` defining
 * `renderScatter(t, ideas)`. No markup of its own — the root `<div id="scatter">` lives in
 * [BOARD_MAIN] (BoardView.kt), which calls `renderScatter` and hides it together with the
 * board's other roots when the gate is closed.
 *
 * Shown only when the topic has a dimension with `direction === 'cost'` and at least one idea
 * has both `value` and `cost` non-null; otherwise `#scatter` stays `hidden` (set by the caller).
 * The plot is inline SVG built with `document.createElementNS` — no template literal, no `$` —
 * rebuilt on every call: it has no inputs of its own, so no focus guard is needed. x = cost,
 * y = value, both over 1..9 (x left-to-right, y bottom-to-top), axes in `--line`, axis labels
 * "cost →" / "↑ value" and the four quadrant labels (split at cost 5 / value 5) in `--muted`.
 *
 * The frontier (Pareto, non-dominated set over the API's own `value`/`cost`, never recomputed —
 * feature non-goal 2): idea i is dominated when some other plottable idea j has
 * `value_j >= value_i && cost_j <= cost_i` with at least one strict. Frontier ideas draw larger
 * in `--accent` with a title-ellipsis label and are joined by one `<polyline>` in ascending cost
 * order (a single frontier point draws no line); equal points can both be on the frontier.
 * Dominated ideas draw as small `--muted` circles. Every circle carries a `<title>` tooltip
 * "Title — value v.v · cost c.c".
 *
 * `renderScatter` reads only the `t` and `ideas` (the aggregate rows) it is given — never
 * `state.ratings`, never a participant name (10mvq-D6). Shared helper contract: the comment
 * block at the top of the shell's script in [AlignmentPage.kt]. No `$` anywhere.
 */
internal const val SCATTER_VIEW = """
<style>
  #scatter { margin-top: 1rem; padding-top: .7rem; border-top: 1px solid var(--line); }
  #scatter[hidden] { display: none; }
  #scatter h3 { margin: 0 0 .5rem; font-size: var(--fs-2); }
  #scatter svg { width: 100%; max-width: 32rem; height: auto; display: block; }
  #scatter .sc-axis { stroke: var(--line); stroke-width: 1; }
  #scatter .sc-axislabel { fill: var(--muted); font-size: 10px; }
  #scatter .sc-quadlabel { fill: var(--muted); font-size: 10px; }
  #scatter .sc-dominated { fill: var(--muted); }
  #scatter .sc-frontier { fill: var(--accent); }
  #scatter .sc-frontierlabel { fill: var(--ink); font-size: 10px; }
  #scatter .sc-frontierline { fill: none; stroke: var(--accent); stroke-width: 1.5; }
</style>
<script>
// ── Scatter: value-vs-cost with the Pareto frontier (10mvq-D12) ────────────
// Reads only the (t, ideas) it is called with — never state.ratings, never a
// participant name. Built fresh with createElementNS on every call: there is
// no input inside it, so no focus guard is needed.
const SCATTER_NS = 'http://www.w3.org/2000/svg';
const SCATTER_SIZE = 320; // px, square plot
const SCATTER_PAD = { left: 34, right: 14, top: 14, bottom: 30 };

function scatterEligible(t) {
  return !!t && t.dimensions.some(d => d.direction === 'cost');
}

function scatterPlottable(ideas) {
  return ideas.filter(f => f.value !== null && f.value !== undefined && f.cost !== null && f.cost !== undefined);
}

/** true when `b` dominates `a`: at least as good on both axes, strictly better on one. */
function scatterDominates(a, b) {
  const ge = b.value >= a.value && b.cost <= a.cost;
  const strict = b.value > a.value || b.cost < a.cost;
  return ge && strict;
}

function scatterFrontier(plottable) {
  return plottable.filter(a => !plottable.some(b => b !== a && scatterDominates(a, b)));
}

function scatterEllipsize(title) {
  return title.length > 18 ? title.slice(0, 18) + '…' : title;
}

function scatterEl(tag, attrs) {
  const node = document.createElementNS(SCATTER_NS, tag);
  for (const k in attrs) node.setAttribute(k, String(attrs[k]));
  return node;
}

function renderScatter(t, ideas) {
  const box = document.getElementById('scatter');
  if (!box) return;
  const plottable = scatterEligible(t) ? scatterPlottable(ideas || []) : [];
  box.innerHTML = '';
  if (!scatterEligible(t) || plottable.length === 0) { box.hidden = true; return; }
  box.hidden = false;

  const heading = document.createElement('h3');
  heading.textContent = 'Value vs. cost';
  box.appendChild(heading);

  const w = SCATTER_SIZE, h = SCATTER_SIZE;
  const plotW = w - SCATTER_PAD.left - SCATTER_PAD.right;
  const plotH = h - SCATTER_PAD.top - SCATTER_PAD.bottom;
  // x = cost 1..9 left to right; y = value 1..9 bottom to top
  const px = v => SCATTER_PAD.left + ((v - 1) / 8) * plotW;
  const py = v => SCATTER_PAD.top + plotH - ((v - 1) / 8) * plotH;

  const svg = scatterEl('svg', { viewBox: '0 0 ' + w + ' ' + h, preserveAspectRatio: 'xMidYMid meet' });

  // quadrant split lines at cost 5 / value 5
  const xMid = px(5), yMid = py(5);
  const vLine = scatterEl('line', { class: 'sc-axis', x1: xMid, y1: SCATTER_PAD.top, x2: xMid, y2: h - SCATTER_PAD.bottom });
  const hLine = scatterEl('line', { class: 'sc-axis', x1: SCATTER_PAD.left, y1: yMid, x2: w - SCATTER_PAD.right, y2: yMid });
  svg.appendChild(vLine); svg.appendChild(hLine);

  // plot border (axes)
  const border = scatterEl('rect', {
    class: 'sc-axis', fill: 'none',
    x: SCATTER_PAD.left, y: SCATTER_PAD.top, width: plotW, height: plotH
  });
  svg.appendChild(border);

  // axis labels
  const xLabel = scatterEl('text', { class: 'sc-axislabel', x: SCATTER_PAD.left + plotW / 2, y: h - 6, 'text-anchor': 'middle' });
  xLabel.textContent = 'cost →';
  const yLabel = scatterEl('text', {
    class: 'sc-axislabel', x: 10, y: SCATTER_PAD.top + plotH / 2, 'text-anchor': 'middle',
    transform: 'rotate(-90 10 ' + (SCATTER_PAD.top + plotH / 2) + ')'
  });
  yLabel.textContent = '↑ value';
  svg.appendChild(xLabel); svg.appendChild(yLabel);

  // quadrant labels: Quick wins (top-left), Big bets (top-right), Fill-ins (bottom-left), Money pits (bottom-right).
  // A label is nudged deeper into its quadrant when a plotted circle already occupies that
  // corner (thresholds mirror the frontier label's own nearTop/nearRight flip below), so
  // neither the circle nor a frontier label collides with the fixed quadrant label text
  // (computenet-obpl4).
  const quadInset = 6, cornerX = 40, cornerY = 16, cornerShift = 22;
  const nearLeftTop = plottable.some(f => px(f.cost) < SCATTER_PAD.left + cornerX && py(f.value) < SCATTER_PAD.top + cornerY);
  const nearRightTop = plottable.some(f => px(f.cost) > w - SCATTER_PAD.right - cornerX && py(f.value) < SCATTER_PAD.top + cornerY);
  const nearLeftBottom = plottable.some(f => px(f.cost) < SCATTER_PAD.left + cornerX && py(f.value) > h - SCATTER_PAD.bottom - cornerY);
  const nearRightBottom = plottable.some(f => px(f.cost) > w - SCATTER_PAD.right - cornerX && py(f.value) > h - SCATTER_PAD.bottom - cornerY);
  const quads = [
    { text: 'Quick wins', x: SCATTER_PAD.left + quadInset, y: SCATTER_PAD.top + 12 + (nearLeftTop ? cornerShift : 0), anchor: 'start' },
    { text: 'Big bets', x: w - SCATTER_PAD.right - quadInset, y: SCATTER_PAD.top + 12 + (nearRightTop ? cornerShift : 0), anchor: 'end' },
    { text: 'Fill-ins', x: SCATTER_PAD.left + quadInset, y: h - SCATTER_PAD.bottom - 6 - (nearLeftBottom ? cornerShift : 0), anchor: 'start' },
    { text: 'Money pits', x: w - SCATTER_PAD.right - quadInset, y: h - SCATTER_PAD.bottom - 6 - (nearRightBottom ? cornerShift : 0), anchor: 'end' }
  ];
  quads.forEach(q => {
    const t2 = scatterEl('text', { class: 'sc-quadlabel', x: q.x, y: q.y, 'text-anchor': q.anchor });
    t2.textContent = q.text;
    svg.appendChild(t2);
  });

  const frontier = scatterFrontier(plottable);
  const frontierSet = new Set(frontier);
  const dominated = plottable.filter(f => !frontierSet.has(f));

  // dominated: small muted circles with a tooltip, drawn first (under the frontier)
  dominated.forEach(f => {
    const c = scatterEl('circle', { class: 'sc-dominated', cx: px(f.cost), cy: py(f.value), r: 4 });
    const title = scatterEl('title', {});
    title.textContent = f.title + ' — value ' + f.value.toFixed(1) + ' · cost ' + f.cost.toFixed(1);
    c.appendChild(title);
    svg.appendChild(c);
  });

  // frontier polyline in ascending cost order (single point: no line)
  if (frontier.length > 1) {
    const ordered = frontier.slice().sort((a, b) => a.cost - b.cost);
    const points = ordered.map(f => px(f.cost) + ',' + py(f.value)).join(' ');
    const line = scatterEl('polyline', { class: 'sc-frontierline', points: points });
    svg.appendChild(line);
  }

  // frontier: larger accent circles with a title-ellipsis label and a tooltip
  frontier.forEach(f => {
    const cx = px(f.cost), cy = py(f.value);
    const c = scatterEl('circle', { class: 'sc-frontier', cx: cx, cy: cy, r: 6 });
    const title = scatterEl('title', {});
    title.textContent = f.title + ' — value ' + f.value.toFixed(1) + ' · cost ' + f.cost.toFixed(1);
    c.appendChild(title);
    svg.appendChild(c);
    // keep the label inside the plot: flip below when too close to the top edge,
    // flip left (anchor 'end') when too close to the right edge
    const nearTop = cy - 8 < SCATTER_PAD.top + 8;
    const nearRight = cx > w - SCATTER_PAD.right - 40;
    const labelX = nearRight ? cx - 8 : cx + 8;
    const labelY = nearTop ? cy + 14 : cy - 8;
    const label = scatterEl('text', {
      class: 'sc-frontierlabel', x: labelX, y: labelY, 'text-anchor': nearRight ? 'end' : 'start'
    });
    label.textContent = scatterEllipsize(f.title);
    svg.appendChild(label);
  });

  box.appendChild(svg);
}
</script>
"""
