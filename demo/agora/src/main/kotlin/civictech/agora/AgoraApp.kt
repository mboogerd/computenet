package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.durability.FileJournal
import civictech.cell.host.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.respond
import civictech.demo.shell.value
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.util.*

/**
 * The argumentation backend: JDK HttpServer + SSE over an [AgoraService]
 * (the demo transport idiom — no framework). With a `--journal` dir the app
 * is kill -9 safe: the structure log rebuilds the graph under its recorded
 * refs, the host journal replays the data, and a checkpoint compacts.
 */
class AgoraApp(port: Int = 8080, journalDir: File? = null) {

    private val registry = LocationRegistry()
    private val journal = journalDir?.let { FileJournal(File(it, "host.journal")) }
    private val host = ManagedHost(
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        journal = journal,
    )
    val service = AgoraService(
        host,
        registry,
        structureLog = journalDir?.let { File(it, "graph.jsonl") },
        onCredence = { _, _ -> broadcast() },
    )

    private val shell = DemoShell(port)
    val boundPort: Int get() = shell.boundPort

    @Serializable
    private data class NodeDto(
        val ref: String,
        val kind: String,
        val text: String? = null,
        val polarity: Polarity? = null,
        val source: String? = null,
        val target: String? = null,
        val head: Boolean = false,
        val credence: Double,
    )

    init {
        // No startup checkpoint: checkpoint runs on the management band and
        // would jump ahead of the still-staged replay frames, compacting the
        // journal down to PRE-replay state (data loss on the next restart).
        // Rebuild appends nothing (catch-ups are suppressed while the
        // structure log replays); replay dispatch does re-journal the derived
        // re-emissions it triggers — idempotent duplicates, bounded per
        // restart. ponytail: compaction needs a quiescence-safe checkpoint;
        // do it when journals actually get big.
        if (journal != null) host.recoverFrom(journal)
        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/graph") { it.respond(200, graphJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        shell.sse("/events") { graphJson() }
    }

    private fun graphJson(): String = Json.encodeToString(
        ListSerializer(NodeDto.serializer()),
        service.graph().map { node ->
            NodeDto(
                ref = node.ref.id.toString(),
                kind = node.info.kind.name,
                text = node.info.text,
                polarity = node.info.polarity,
                source = node.info.source?.id?.toString(),
                target = node.info.target?.id?.toString(),
                head = node.info.head,
                credence = node.credence,
            )
        },
    )

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
        fun ref(key: String): CellRef? = params[key]?.let { CellRef(UUID.fromString(it)) }
        try {
            when (params["action"]) {
                "claim" -> {
                    val ref = service.createClaim(params["text"] ?: return exchange.respond(400, "missing text"))
                    exchange.respond(200, """{"ref":"${ref.id}"}""", "application/json")
                }

                "edge" -> {
                    val source = ref("source") ?: return exchange.respond(400, "missing source")
                    val target = ref("target") ?: return exchange.respond(400, "missing target")
                    if (source == target)
                        return exchange.respond(400, "an edge cannot connect a node to itself")
                    val polarity = params["polarity"]?.uppercase()
                        ?.let { runCatching { Polarity.valueOf(it) }.getOrNull() }
                        ?: return exchange.respond(400, "polarity must be ATTACK or SUPPORT")
                    // A relation is one fact: re-asserting an identical
                    // (source, target, polarity) edge returns the existing one
                    // instead of stacking a second influence path (which would
                    // let a client inflate credence by re-posting). Stances on
                    // the shared edge are where its strength actually accrues.
                    val edge = service.findEdge(source, target, polarity)
                        ?: service.createEdge(source, target, polarity)
                    exchange.respond(200, """{"ref":"${edge.id}"}""", "application/json")
                }

                "stance" -> {
                    val id = ref("id") ?: return exchange.respond(400, "missing id")
                    val user = params["user"] ?: return exchange.respond(400, "missing user")
                    // Blank value clears the stance; anything non-numeric is a
                    // clean 400 rather than a leaked NumberFormatException.
                    val raw = params["value"]?.takeIf { it.isNotBlank() }
                    val value = if (raw == null) null
                        else raw.toDoubleOrNull()
                            ?: return exchange.respond(400, "stance must be a number between 0 and 1")
                    service.setStance(id, user, value)
                    exchange.respond(200, "ok")
                }

                "remove" -> {
                    service.remove(ref("id") ?: return exchange.respond(400, "missing id"))
                    exchange.respond(200, "ok")
                }

                else -> exchange.respond(400, "unknown action")
            }
        } catch (e: IllegalArgumentException) {
            exchange.respond(400, e.message ?: "bad request")
        }
    }

    // Behaves identically to the former guarded broadcast (`if (clients.isEmpty())
    // return`): graphJson() is pure, and forEach over an empty client list is
    // already a no-op — dropping the guard costs one wasted computation with
    // no observable difference.
    private fun broadcast() = shell.broadcast { graphJson() }

    fun start(): AgoraApp = apply { shell.start() }

    fun stop() = shell.stop()
}

fun main(args: Array<String>) {
    val port = demoPort(args)
    val journalDir = args.value("--journal")?.let { File(it).apply { mkdirs() } }

    val app = AgoraApp(port, journalDir).start()
    println("agora: http://localhost:${app.boundPort}")
    println(if (journalDir != null) "  journaling to $journalDir (kill -9 safe)" else "  volatile mode; add --journal <dir> to survive restarts")
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>agora — argumentation graph</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.3rem; }
  li { margin: .3rem 0; font-size: .9rem; }
  .bar { display: inline-block; width: 120px; height: .7rem; background: #eee; vertical-align: middle; margin: 0 .5rem; }
  .bar div { height: 100%; background: #4a7; }
  form { margin: .4rem 0; } input, select { margin-right: .3rem; }
  code { color: #888; font-size: .75rem; }
</style>
</head>
<body>
<h1>agora — argue, attack the argument, or attack the attack</h1>
<form id="claimForm"><input id="claimText" placeholder="new claim" size="40"><button>Claim</button></form>
<form id="edgeForm">
  <select id="edgeSource"></select>
  <select id="edgePolarity"><option>ATTACK</option><option>SUPPORT</option></select>
  <select id="edgeTarget"></select>
  <button>Link</button>
</form>
<form id="stanceForm">
  <select id="stanceId"></select>
  <input id="stanceValue" type="number" min="0" max="1" step="0.05" value="0.9">
  <button>Stance</button>
</form>
<ul id="nodes"></ul>
<script>
const user = sessionStorage.userId ??= Math.random().toString(36).slice(2, 8);
const post = body => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams(body) });
claimForm.onsubmit = e => { e.preventDefault(); post({action: 'claim', text: claimText.value}); claimText.value = ''; };
edgeForm.onsubmit = e => { e.preventDefault();
  post({action: 'edge', source: edgeSource.value, target: edgeTarget.value, polarity: edgePolarity.value}); };
stanceForm.onsubmit = e => { e.preventDefault();
  post({action: 'stance', id: stanceId.value, user, value: stanceValue.value}); };
const label = n => n.kind === 'CLAIM' ? (n.text || n.ref.slice(0, 8))
  : n.polarity + (n.head ? '⟳' : '') + ': ' + n.source.slice(0, 8) + '→' + n.target.slice(0, 8);
function render(nodes) {
  const byRef = Object.fromEntries(nodes.map(n => [n.ref, n]));
  const name = r => byRef[r] ? label(byRef[r]) : r.slice(0, 8);
  document.getElementById('nodes').innerHTML = nodes.map(n =>
    '<li>' + label(n) + '<span class="bar"><div style="width:' + (n.credence * 100).toFixed(0) + '%"></div></span>' +
    n.credence.toFixed(3) + ' <code>' + n.ref.slice(0, 8) + '</code>' +
    ' <button onclick="post({action: \'remove\', id: \'' + n.ref + '\'})">×</button></li>').join('');
  for (const sel of [edgeSource, edgeTarget, stanceId]) {
    const prev = sel.value;
    sel.innerHTML = nodes.map(n => '<option value="' + n.ref + '">' + label(n) + '</option>').join('');
    if ([...sel.options].some(o => o.value === prev)) sel.value = prev;
  }
}
new EventSource('/events').onmessage = e => render(JSON.parse(e.data));
fetch('/graph').then(r => r.json()).then(render);
</script>
</body>
</html>
"""
