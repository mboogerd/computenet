/**
 * The `:demo:social` HTTP/SSE surface (SOC1, epic `computenet-07k`), decided
 * in feature `computenet-jo2jk` design jo2jk-D8: `/`, `/state`, `/op` and
 * `/events` through [civictech.demo.shell.DemoShell], copying the
 * `demo/skillmatch` `SkillMatchApp` idiom (observed at 237d8b99).
 *
 * `/state`'s shape is pinned by jo2jk-D6 and `/op`'s grammar by jo2jk-D7 —
 * both are a fixed contract this file implements, not a design choice made
 * here. See [SocialGraph] and [SnbPipeline] for the write/read paths this
 * class wires into HTTP.
 */
package civictech.demo.social

import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Interest
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import civictech.demo.shell.value
import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A single `/op` validation/action failure, reported as a 400 with [message]
 * (jo2jk-D7: a missing or non-numeric required param, or an unknown action,
 * always maps to `400 "missing <name>"`/`"unknown action"`). Extends
 * [IllegalArgumentException] so [handleOp] catches it in the same clause as
 * the [IllegalArgumentException]s [SocialGraph] itself throws for an unknown
 * referenced id.
 */
private class Bad(message: String) : IllegalArgumentException(message)

class SocialApp(
    port: Int = 8080,
    journalDir: File? = null,
    reader: (ManagedHost) -> BoundedReader = ::HostBoundedReader,
    // computenet-suj6a: overridable only so a test can drive the TIMEOUT branch
    // without a real 10s wait. `main` and every other caller take the default,
    // so production behavior (and its accepted 10s blast radius, see the arg's
    // own doc) is unchanged.
    private val shortReadTimeoutSeconds: Long = SHORT_READ_TIMEOUT,
    // 99qcg-D2: appended LAST so every existing positional/named construction
    // in other test files is unaffected. Null loads nothing (today's
    // behavior); non-null loads once, in `init`, before the shell routes.
    source: SnbSource? = null,
) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry, journal = KeyedCells.hostJournal(journalDir))

    val pipeline: SnbPipeline.Graph = SnbPipeline.build(host, journalDir, registry)
    val graph: SocialGraph = SocialGraph(host, pipeline)

    // 99qcg-D2/D10: null source means no dataset and no stream; /op action=step
    // then answers 400 and /state's applied/remaining stay 0/0.
    private val stream: UpdateStream? = source?.let {
        SocialLoader.load(it, graph)
        UpdateStream(it, graph)
    }

    // rx8om-D7/D8: the short-read seam. `reader` is a factory, not a value,
    // because `host` is built above and private to this constructor. Built
    // once and shared by the short reads and every feed session (8eb53).
    private val boundedReader: BoundedReader = reader(host)

    val shortReads: ShortReads = ShortReads(boundedReader, GraphLocator(graph, pipeline.families))

    /**
     * A scatter-gather feed for [viewer] over the authored cells [scope]
     * admits (feature `computenet-8eb53`). The scope is the caller's: this
     * app does not derive it from `knows`, and there is no `/feed` route yet.
     */
    fun feedSession(viewer: Long, scope: Interest.Ranges, pageLimit: Int = 200): FeedSession =
        FeedSession(viewer, scope, pipeline.families, registry, boundedReader, pageLimit)

    // One observe sink per static dimension set (jo2jk-D2), read the same way
    // SocialGraph reads its keyed families: sink.current() only.
    private val tags: ObservationSink<Set<Tag>> = host.observe(pipeline.statics.tags.ref, View.set<Tag>())
    private val tagClasses: ObservationSink<Set<TagClass>> =
        host.observe(pipeline.statics.tagClasses.ref, View.set<TagClass>())
    private val places: ObservationSink<Set<Place>> = host.observe(pipeline.statics.places.ref, View.set<Place>())
    private val organisations: ObservationSink<Set<Organisation>> =
        host.observe(pipeline.statics.organisations.ref, View.set<Organisation>())

    private val shell = DemoShell(port)

    @Volatile
    private var viewer: Long? = null

    val boundPort: Int get() = shell.boundPort

    init {
        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        shell.route("/person/") { handlePerson(it) }
        shell.route("/message/") { handleMessage(it) }
        shell.sse("/events") { stateJson() }

        // Wired after the shell exists, per SkillMatchApp's idiom: graph.onChange
        // fires on every settled change of every sink, present and future.
        graph.onChange { broadcast() }
    }

    fun start(): SocialApp = apply { shell.start() }

    fun stop() {
        shell.stop()
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    // --- /op ------------------------------------------------------------------

    /**
     * Parses and validates every `/op` param for the named [action] before
     * making the single [SocialGraph] call that mutates — so a 400 (missing
     * param, non-numeric id, unknown action) never mutates, and an unknown
     * referenced id (validated by [SocialGraph] itself before any write) is
     * reported the same way ([SOC1-HTTP-04]).
     */
    private fun handleOp(exchange: HttpExchange) {
        val params = parseForm(exchange)

        fun value(name: String): String = params[name] ?: throw Bad("missing $name")
        fun requiredLong(name: String): Long = value(name).toLongOrNull() ?: throw Bad("missing $name")
        fun optionalLong(name: String): Long = params[name]?.toLongOrNull() ?: 0L

        try {
            when (params["action"]) {
                "person" -> {
                    val id = requiredLong("id")
                    val firstName = value("firstName")
                    val lastName = value("lastName")
                    val creationDate = optionalLong("creationDate")
                    graph.addPerson(Person(id, firstName, lastName, creationDate = creationDate))
                }

                "knows" -> {
                    val a = requiredLong("a")
                    val b = requiredLong("b")
                    val date = optionalLong("date")
                    graph.addKnows(a, b, date)
                }

                "unknows" -> {
                    val a = requiredLong("a")
                    val b = requiredLong("b")
                    val date = optionalLong("date")
                    graph.removeKnows(a, b, date)
                }

                "forum" -> {
                    val id = requiredLong("id")
                    val title = value("title")
                    val moderator = requiredLong("moderator")
                    val creationDate = optionalLong("creationDate")
                    graph.addForum(Forum(id, title, moderator, creationDate))
                }

                "join" -> {
                    val person = requiredLong("person")
                    val forum = requiredLong("forum")
                    val date = optionalLong("date")
                    graph.joinForum(person, forum, date)
                }

                "post" -> {
                    val id = requiredLong("id")
                    val author = requiredLong("author")
                    val forum = requiredLong("forum")
                    val content = value("content")
                    val creationDate = optionalLong("creationDate")
                    graph.addPost(Message(id, author, creationDate, content, forumId = forum))
                }

                "comment" -> {
                    val id = requiredLong("id")
                    val author = requiredLong("author")
                    val replyOf = requiredLong("replyOf")
                    val content = value("content")
                    val creationDate = optionalLong("creationDate")
                    graph.addComment(Message(id, author, creationDate, content, replyOfId = replyOf))
                }

                "like" -> {
                    val person = requiredLong("person")
                    val message = requiredLong("message")
                    val date = optionalLong("date")
                    graph.addLike(Like(person, message, date))
                }

                "viewer" -> {
                    val person = requiredLong("person")
                    if (person !in graph.personIds()) throw Bad("unknown person $person")
                    viewer = person
                }

                "step" -> {
                    val s = stream ?: throw Bad("no stream")
                    val n = if (params.containsKey("n")) {
                        params["n"]?.toIntOrNull() ?: throw Bad("missing n")
                    } else {
                        1
                    }
                    s.step(n)
                }

                else -> throw Bad("unknown action")
            }
        } catch (e: IllegalArgumentException) {
            return exchange.respond(400, e.message ?: "bad request")
        }
        exchange.respond(200, "ok")
    }

    private fun parseForm(exchange: HttpExchange): Map<String, String> =
        exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }

    // --- /person/<id>[/messages|/friends], /message/<id>[/creator|/forum|/replies] (rx8om-D8) ---

    /**
     * Bounds every short read at the HTTP boundary (rx8om-D8's KDoc-pinned
     * contract): a page that never lands within [SHORT_READ_TIMEOUT] answers
     * 503 `{"refused":"TIMEOUT"}` — a name [StateReadResult.Reason] does not
     * carry, chosen here because the future itself, not the host, is what
     * failed to complete. [ShortReads] never blocks on its own; this is the
     * one place `:demo:social` does, deliberately.
     */
    private fun <T> respondOutcome(exchange: HttpExchange, future: CompletableFuture<ReadOutcome<T>>, body: (T) -> String) {
        val outcome = try {
            future.get(shortReadTimeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            exchange.respond(503, """{"refused":"TIMEOUT"}""", "application/json")
            return
        }
        when (outcome) {
            is ReadOutcome.Found -> exchange.respond(200, body(outcome.value), "application/json")
            ReadOutcome.Empty -> exchange.respond(200, """{"found":false}""", "application/json")
            is ReadOutcome.Refused ->
                exchange.respond(503, """{"refused":${esc(outcome.reason.name)}}""", "application/json")
        }
    }

    private fun handlePerson(exchange: HttpExchange) {
        val parts = exchange.requestURI.path.removePrefix("/person/").split("/").filter { it.isNotEmpty() }
        val id = parts.getOrNull(0)?.toLongOrNull()
        if (id == null) {
            exchange.respond(400, "bad id")
            return
        }
        when {
            parts.size == 1 ->
                respondOutcome(exchange, shortReads.is1(id)) { person -> """{"found":true,"person":${person.json()}}""" }

            parts.size == 2 && parts[1] == "messages" ->
                respondOutcome(exchange, shortReads.is2(id)) { messages ->
                    """{"found":true,"messages":${messages.joinToString(",", "[", "]") { it.json() }}}"""
                }

            parts.size == 2 && parts[1] == "friends" ->
                respondOutcome(exchange, shortReads.is3(id)) { friends ->
                    """{"found":true,"friends":${
                        friends.joinToString(",", "[", "]") { """{"id":${it.otherId},"creationDate":${it.creationDate}}""" }
                    }}"""
                }

            else -> exchange.respond(404, "not found")
        }
    }

    private fun handleMessage(exchange: HttpExchange) {
        val parts = exchange.requestURI.path.removePrefix("/message/").split("/").filter { it.isNotEmpty() }
        val id = parts.getOrNull(0)?.toLongOrNull()
        if (id == null) {
            exchange.respond(400, "bad id")
            return
        }
        when {
            parts.size == 1 ->
                respondOutcome(exchange, shortReads.is4(id)) { message -> """{"found":true,"message":${message.json()}}""" }

            parts.size == 2 && parts[1] == "creator" ->
                respondOutcome(exchange, shortReads.is5(id)) { creatorId -> """{"found":true,"creatorId":$creatorId}""" }

            parts.size == 2 && parts[1] == "forum" ->
                respondOutcome(exchange, shortReads.is6(id)) { forumId -> """{"found":true,"forumId":$forumId}""" }

            parts.size == 2 && parts[1] == "replies" ->
                respondOutcome(exchange, shortReads.is7(id)) { replies ->
                    """{"found":true,"replies":${
                        replies.joinToString(",", "[", "]") {
                            """{"message":${it.message.json()},"authorKnowsCreator":${it.authorKnowsCreator}}"""
                        }
                    }}"""
                }

            else -> exchange.respond(404, "not found")
        }
    }

    /** The full [Person], every field (IS1's pinned shape, rx8om-D8). */
    private fun Person.json(): String =
        """{"id":$id,"firstName":${esc(firstName)},"lastName":${esc(lastName)},"gender":${esc(gender)},""" +
            """"birthday":$birthday,"creationDate":$creationDate,"locationIp":${esc(locationIp)},""" +
            """"browserUsed":${esc(browserUsed)},"placeId":${placeId ?: "null"}}"""

    /** The one [Message] JSON shape every short-read response embeds (rx8om-D8). */
    private fun Message.json(): String =
        """{"id":$id,"creatorId":$creatorId,"creationDate":$creationDate,"content":${esc(content)},""" +
            """"forumId":${forumId ?: "null"},"replyOfId":${replyOfId ?: "null"}}"""

    // --- /state (jo2jk-D6: pinned shape, sorted, esc'd, bounded) ---------------

    private fun stateJson(): String {
        val personIds = graph.personIds()
        val forumIds = graph.forumIds()
        val messageIds = graph.messageIds()

        val knowsTotal = personIds.sumOf { id -> graph.personFacts(id).count { it is Knows } }
        val likesTotal = messageIds.sumOf { id -> graph.messageFacts(id).count { it is MessageFact.LikedBy } }
        val tagsTotal = tags.current().size

        val personsJson = personIds.take(STATE_LIMIT).joinToString(",", "[", "]") { id ->
            val facts = graph.personFacts(id)
            val profile = facts.filterIsInstance<PersonFact.Profile>().firstOrNull()?.person
            val name = profile?.let { "${it.firstName} ${it.lastName}" } ?: ""
            val knowsIds = facts.filterIsInstance<Knows>().map { it.otherId }.sorted()
            val authoredIds = graph.authored(id).map { it.id }.sorted()
            """{"id":$id,"name":${esc(name)},"knows":${longArrayJson(knowsIds)},"authored":${longArrayJson(authoredIds)}}"""
        }

        val forumsJson = forumIds.take(STATE_LIMIT).joinToString(",", "[", "]") { id ->
            val facts = graph.forumFacts(id)
            val title = facts.filterIsInstance<ForumFact.Info>().firstOrNull()?.forum?.title ?: ""
            val members = facts.filterIsInstance<ForumFact.Member>().map { it.personId }.sorted()
            val contains = facts.filterIsInstance<ForumFact.Contains>().map { it.messageId }.sorted()
            """{"id":$id,"title":${esc(title)},"members":${longArrayJson(members)},"contains":${longArrayJson(contains)}}"""
        }

        val messagesJson = messageIds.take(STATE_LIMIT).joinToString(",", "[", "]") { id ->
            val facts = graph.messageFacts(id)
            val body = facts.filterIsInstance<MessageFact.Body>().firstOrNull()?.message
            val replies = facts.filterIsInstance<MessageFact.Reply>().map { it.childId }.sorted()
            val likes = facts.filterIsInstance<MessageFact.LikedBy>().map { it.personId }.sorted()
            val replyOf = body?.replyOfId
            """{"id":$id,"creator":${body?.creatorId},"replyOf":${replyOf ?: "null"},"replies":${longArrayJson(replies)},"likes":${longArrayJson(likes)}}"""
        }

        return """{"viewer":${viewer ?: "null"},""" +
            """"counts":{"persons":${personIds.size},"knows":$knowsTotal,"forums":${forumIds.size},""" +
            """"messages":${messageIds.size},"likes":$likesTotal,"tags":$tagsTotal},""" +
            """"applied":${stream?.applied ?: 0},"remaining":${stream?.remaining ?: 0},""" +
            """"persons":$personsJson,"forums":$forumsJson,"messages":$messagesJson}"""
    }

    private fun longArrayJson(ids: List<Long>): String = ids.joinToString(",", "[", "]")

    private companion object {
        /** Bounds the three /state arrays; counts stay total (jo2jk-D6). */
        const val STATE_LIMIT = 200

        /**
         * Bounds a short read's future at the HTTP boundary (rx8om-D8).
         *
         * computenet-suj6a: DemoShell dispatches every route (`/state`,
         * `/events` included) on one thread (`server.executor = null`), so a
         * short read stuck past this bound stalls the whole app instance for
         * up to [SHORT_READ_TIMEOUT] seconds, not just the read that timed
         * out — verified in
         * `SocialReadRefusalTest`'s `a stuck short read times out ... and
         * stalls the dispatcher`. This is the accepted tradeoff for a demo
         * app (same shape as `DialogueApp.onDriver`'s `ACTION_TIMEOUT_MS`):
         * a shorter bound or a dedicated executor is deferred, not ruled out,
         * should a real stall ever make 10s too slow in practice.
         */
        const val SHORT_READ_TIMEOUT = 10L
    }
}

/**
 * `main`'s `--scale`/`--seed` load parameters (99qcg-D2): `--scale` defaults
 * to `0.05`, `--seed` to `42`. The defaults live here, not in [SocialApp]'s
 * constructor, so every existing test construction (source-less) is
 * unaffected.
 */
data class LaunchOptions(val scale: Double, val seed: Long) {
    companion object {
        const val DEFAULT_SCALE = 0.05
        const val DEFAULT_SEED = 42L

        fun parse(args: Array<String>): LaunchOptions {
            val scale = args.value("--scale")?.let {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("--scale must be numeric, got $it")
            } ?: DEFAULT_SCALE
            val seed = args.value("--seed")?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("--seed must be numeric, got $it")
            } ?: DEFAULT_SEED
            return LaunchOptions(scale, seed)
        }
    }
}

fun main(args: Array<String>) {
    val port = demoPort(args)
    val journalDir = args.value("--journal")?.let { File(it).apply { mkdirs() } }
    val opts = LaunchOptions.parse(args)
    val source = SnbGenerator(opts.seed, opts.scale)
    val app = SocialApp(port, journalDir, source = source).start()
    println("seed=${opts.seed}")
    println("scale=${opts.scale}")
    println("computenet social: http://localhost:${app.boundPort}")
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>social — SNB graph</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1080px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; align-items: stretch; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 260px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  form { display: flex; gap: .3rem; margin-bottom: .5rem; flex-wrap: wrap; }
  input { flex: 1; min-width: 60px; padding: .3rem .45rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  form button { padding: .3rem .6rem; border: none; border-radius: 6px; background: var(--blue); color: #fff; cursor: pointer; }
  .entry { margin: .3rem 0; font-size: .85rem; border-bottom: 1px solid var(--line); padding-bottom: .3rem; }
  .entry b { margin-right: .3rem; }
  #counts { font-size: .85rem; color: var(--dim); margin-bottom: .5rem; }
</style>
</head>
<body>
<h1>Social graph <small>SNB schema over keyed cells</small></h1>
<div id="counts"></div>
<div class="row">
  <div class="card">
    <h2>Person</h2>
    <form id="personForm"><input id="p_id" placeholder="id"><input id="p_first" placeholder="first"><input id="p_last" placeholder="last"><button>Add</button></form>
    <h2>Knows</h2>
    <form id="knowsForm"><input id="k_a" placeholder="a"><input id="k_b" placeholder="b"><button>Knows</button></form>
    <form id="unknowsForm"><input id="u_a" placeholder="a"><input id="u_b" placeholder="b"><button>Unknows</button></form>
    <h2>Viewer</h2>
    <form id="viewerForm"><input id="v_person" placeholder="person"><button>Set</button></form>
    <div id="persons"></div>
  </div>
  <div class="card">
    <h2>Forum</h2>
    <form id="forumForm"><input id="f_id" placeholder="id"><input id="f_title" placeholder="title"><input id="f_mod" placeholder="moderator"><button>Add</button></form>
    <h2>Join</h2>
    <form id="joinForm"><input id="j_person" placeholder="person"><input id="j_forum" placeholder="forum"><button>Join</button></form>
    <div id="forums"></div>
  </div>
  <div class="card">
    <h2>Post</h2>
    <form id="postForm"><input id="po_id" placeholder="id"><input id="po_author" placeholder="author"><input id="po_forum" placeholder="forum"><input id="po_content" placeholder="content"><button>Post</button></form>
    <h2>Comment</h2>
    <form id="commentForm"><input id="c_id" placeholder="id"><input id="c_author" placeholder="author"><input id="c_replyOf" placeholder="replyOf"><input id="c_content" placeholder="content"><button>Comment</button></form>
    <h2>Like</h2>
    <form id="likeForm"><input id="l_person" placeholder="person"><input id="l_message" placeholder="message"><button>Like</button></form>
    <div id="messages"></div>
  </div>
</div>
<script>
const op = body => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams(body) });

function bind(formId, action, fields) {
  document.getElementById(formId).onsubmit = e => {
    e.preventDefault();
    const body = { action };
    for (const [key, elId] of Object.entries(fields)) body[key] = document.getElementById(elId).value.trim();
    op(body);
  };
}
bind('personForm', 'person', { id: 'p_id', firstName: 'p_first', lastName: 'p_last' });
bind('knowsForm', 'knows', { a: 'k_a', b: 'k_b' });
bind('unknowsForm', 'unknows', { a: 'u_a', b: 'u_b' });
bind('viewerForm', 'viewer', { person: 'v_person' });
bind('forumForm', 'forum', { id: 'f_id', title: 'f_title', moderator: 'f_mod' });
bind('joinForm', 'join', { person: 'j_person', forum: 'j_forum' });
bind('postForm', 'post', { id: 'po_id', author: 'po_author', forum: 'po_forum', content: 'po_content' });
bind('commentForm', 'comment', { id: 'c_id', author: 'c_author', replyOf: 'c_replyOf', content: 'c_content' });
bind('likeForm', 'like', { person: 'l_person', message: 'l_message' });

function row(container, text) {
  const div = document.createElement('div'); div.className = 'entry'; div.textContent = text;
  container.appendChild(div);
}

new EventSource('/events').onmessage = e => {
  const s = JSON.parse(e.data);
  const counts = document.getElementById('counts');
  counts.textContent = 'viewer=' + s.viewer + ' persons=' + s.counts.persons + ' knows=' + s.counts.knows +
    ' forums=' + s.counts.forums + ' messages=' + s.counts.messages + ' likes=' + s.counts.likes + ' tags=' + s.counts.tags;

  const persons = document.getElementById('persons'); persons.innerHTML = '';
  for (const p of s.persons) row(persons, '#' + p.id + ' ' + p.name + ' knows=[' + p.knows.join(',') + '] authored=[' + p.authored.join(',') + ']');

  const forums = document.getElementById('forums'); forums.innerHTML = '';
  for (const f of s.forums) row(forums, '#' + f.id + ' ' + f.title + ' members=[' + f.members.join(',') + '] contains=[' + f.contains.join(',') + ']');

  const messages = document.getElementById('messages'); messages.innerHTML = '';
  for (const m of s.messages) row(messages, '#' + m.id + ' by ' + m.creator + ' replyOf=' + m.replyOf + ' replies=[' + m.replies.join(',') + '] likes=[' + m.likes.join(',') + ']');
};
</script>
</body>
</html>
"""
