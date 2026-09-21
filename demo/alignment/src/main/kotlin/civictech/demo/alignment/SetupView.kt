package civictech.demo.alignment

/**
 * The facilitator's Setup view (computenet-0dvra-D5/D11/D14/D15), one slice of the served
 * [PAGE]: a `<section id="setup">` plus the `<script>` that defines `renderSetup()`.
 *
 * STUB (computenet-0dvra.1): the shell only needs the root and the render hook to exist; the
 * controls are computenet-0dvra.2's. The shell shows this section only on `/t/{id}`, only as
 * the Setup tab, and only while `isCreator(currentTopic())`. Code against the shared helper contract documented at the top of the shell's
 * script in [AlignmentPage.kt] (`state`, `me()`, `currentTopic()`, `dimColour(t, d)`,
 * `editing(root)`, `send(...)`, ...). Rules every view slice follows: no `$` anywhere (this is a
 * plain raw string); no literal colour — use the `:root` tokens (`var(--value-1)`, ...); a
 * view's own CSS goes in a `<style>` block inside this string; the script declares functions
 * and wires its own static markup only, never shell-dependent code at top level.
 */
internal const val SETUP_VIEW = """
<section id="setup" class="pane view" hidden>
  <h2>Setup</h2>
</section>
<script>
// Setup view (computenet-0dvra.2 fills this in). Called by the shell on every coalesced frame
// while a known topic route is showing, whether or not the Setup tab is active.
function renderSetup() {}
</script>
"""
