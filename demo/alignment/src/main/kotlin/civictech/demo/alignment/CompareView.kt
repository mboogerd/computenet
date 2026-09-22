package civictech.demo.alignment

/**
 * The experimental Compare view (computenet-5eefp, ALN2.4), one slice of the served [PAGE]: its
 * `<style>`, the `<section id="compare">` root and the `<script>` defining `renderCompare()`.
 *
 * This task (computenet-5eefp.1) is the STUB: it registers the tab and lands the view's static
 * roots (5eefp-D1/D2/D7/D8) so the page compiles, the shell can toggle `#compare` like any other
 * view root, and the page smoke test can pin the roots — but `renderCompare()` does nothing yet
 * and the `<style>` block is empty. Task computenet-5eefp.2 (blocked on this one) fills both in:
 * the dimension picker, the continuous 1–9 axis with drag/click placement, the unplaced tray, the
 * per-chip description popover and the post-reveal overlay of other participants' placements
 * (5eefp-D3 … D11).
 *
 * Shared helper contract: the comment block at the top of the shell's script in [AlignmentPage.kt].
 * No `$` anywhere (a plain raw string, no template literals); no literal colour — only `:root`
 * tokens and `dimColour(t, d)`, both left to task 2. Private globals get the `cmp` prefix.
 */
internal const val COMPARE_VIEW = """
<style>
</style>
<section id="compare" class="pane view" hidden>
  <div id="cmpPicker" role="tablist" aria-label="dimension"></div>
  <p id="cmpDirection" class="muted"></p>
  <div id="cmpAxis"><span id="cmpLow"></span><span id="cmpHigh"></span></div>
  <label id="cmpOthersWrap" hidden><input type="checkbox" id="cmpOthers"> show everyone's placements</label>
  <div id="cmpTray"><h3>unplaced</h3></div>
  <div id="cmpDesc" role="dialog" hidden></div>
</section>
<script>
// stub (computenet-5eefp.1): computenet-5eefp.2 fills this in (5eefp-D2 … D11).
function renderCompare() {}
</script>
"""
