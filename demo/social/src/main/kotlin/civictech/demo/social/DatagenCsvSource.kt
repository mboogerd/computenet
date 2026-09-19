/**
 * Reads an LDBC SNB *datagen* interactive (v0.3.x) CSV export from a
 * directory and exposes it as an [SnbSource] (SOC1, epic `computenet-07k`,
 * feature `computenet-99qcg`, task `computenet-99qcg.2`, design 99qcg-D9,
 * [SOC1-GEN-04]).
 *
 * ## Layout pinned by this reader
 *
 * Files are pipe-separated with a header row; columns are looked up BY NAME
 * so an unknown column is ignored, and a missing required column throws
 * `IllegalStateException("<file>: missing column <name>")`. Only the
 * columns SOC1's types carry are read. Sources are named per file below,
 * read 2026-09-19 from `github.com/ldbc/ldbc_snb_datagen_hadoop@main`
 * unless noted otherwise.
 *
 * **Static/bulk entity and edge files** (everything created before the
 * update window — these become [StaticSlice]):
 *
 * - `person_0_0.csv`: `id|firstName|lastName|gender|birthday|creationDate|locationIP|browserUsed`
 *   — `serializer/snb/csv/dynamicserializer/person/CsvBasicDynamicPersonSerializer.java`.
 * - `person_knows_person_0_0.csv`: `Person.id|Person.id|creationDate`, same
 *   source. `Knows` is undirected and datagen may emit both directions of a
 *   pair; this reader normalises every row to `(min,max)` and de-duplicates
 *   rather than assuming one direction.
 * - `forum_0_0.csv`: `id|title|creationDate` — no moderator column; the
 *   moderator lives in `forum_hasModerator_person_0_0.csv`
 *   (`serializer/snb/csv/dynamicserializer/activity/CsvBasicDynamicActivitySerializer.java`,
 *   same for every file below through `person_likes_comment`).
 * - `forum_hasModerator_person_0_0.csv`: `Forum.id|Person.id`.
 * - `forum_hasMember_person_0_0.csv`: `Forum.id|Person.id|joinDate`.
 * - `forum_containerOf_post_0_0.csv`: `Forum.id|Post.id`.
 * - `post_0_0.csv`: `id|imageFile|creationDate|locationIP|browserUsed|language|content|length`.
 * - `post_hasCreator_person_0_0.csv`: `Post.id|Person.id`.
 * - `comment_0_0.csv`: `id|creationDate|locationIP|browserUsed|content|length`.
 * - `comment_hasCreator_person_0_0.csv`: `Comment.id|Person.id`.
 * - `comment_replyOf_post_0_0.csv` / `comment_replyOf_comment_0_0.csv`:
 *   `Comment.id|Post.id` / `Comment.id|Comment.id` — a comment appears in
 *   exactly one of the two files (the serializer branches on
 *   `comment.replyOf() == comment.postId()`). The latter file's header
 *   repeats `Comment.id` for both columns (the replying comment and its
 *   parent comment), so this reader reads that one positionally instead of
 *   by name — the only file it does not look up purely by column name.
 * - `person_likes_post_0_0.csv` / `person_likes_comment_0_0.csv`:
 *   `Person.id|Post.id|creationDate` / `Person.id|Comment.id|creationDate`.
 * - `person_isLocatedIn_place_0_0.csv`: `Person.id|Place.id` — **optional**;
 *   a missing file leaves every [Person.placeId] `null` rather than
 *   throwing, since that field is itself nullable and no SOC1 rule requires
 *   it.
 * - `tagclass_0_0.csv` (`id|name|isSubclassOf`), `tag_0_0.csv`
 *   (`id|name|TagClass.id`), `place_0_0.csv` (`id|name|type|isPartOf`),
 *   `organisation_0_0.csv` (`id|name|type|Place.id`): **not** independently
 *   confirmed against datagen source this task — the static-dimension
 *   serializer source wasn't reached in this session. This is the
 *   implementer's best reading of the general SNB dimension-table shape,
 *   flagged per 99qcg-D9's "if no source is reachable, pin your best
 *   reading and say so".
 *
 * **Update-stream files** (everything created during the update window —
 * these become the [UpdateEvent] sequence). The *real* datagen update
 * stream is a headerless, positionally-encoded
 * `date|dependantDate|typeOrdinal|eventData` line per event, pinned from
 * `serializer/UpdateEventSerializer.java` and
 * `hadoop/UpdateEvent.java`'s `UpdateEventType` enum — whose declaration
 * order (`ADD_PERSON, ADD_LIKE_POST, ADD_LIKE_COMMENT, ADD_FORUM,
 * ADD_FORUM_MEMBERSHIP, ADD_POST, ADD_COMMENT, ADD_FRIENDSHIP`) already
 * matches IU1..IU8 in order. This reader does **not** reproduce that binary
 * positional shape; it is this task's own simplification: both
 * `updateStream_0_0_person.csv` and `updateStream_0_0_forum.csv` share ONE
 * header-named CSV shape — a union of every arm's fields, most cells blank
 * per row (`type|creationDate|personId|firstName|lastName|gender|birthday|`
 * `locationIp|browserUsed|placeId|otherPersonId|postId|commentId|forumId|`
 * `title|moderatorId|content|replyOfId`) — kept honest by naming every column.
 * `type` holds `IU1`..`IU8` textually; a `type` outside that set throws
 * `IllegalStateException` naming the file and the type (interactive SNB has
 * no arm beyond IU1-8). `updateStream_0_0_person.csv` carries IU1, IU2,
 * IU3, IU6, IU7, IU8; `updateStream_0_0_forum.csv` carries IU4, IU5 — this
 * person/forum split (forum-centric events in the forum file) is this
 * reader's own inference, not independently confirmed from source.
 *
 * [staticSlice] and [updates] are computed once and cached; [updates] runs
 * [validateReferences] over the merged, [UpdateEventOrder]-sorted stream
 * before returning it, mirroring [SnbGenerator].
 */
package civictech.demo.social

import java.io.File

class DatagenCsvSource(private val dir: File) : SnbSource {

    private data class Loaded(val slice: StaticSlice, val updates: List<UpdateEvent>)

    private val loaded: Loaded by lazy { load() }

    override fun staticSlice(): StaticSlice = loaded.slice

    override fun updates(): Sequence<UpdateEvent> = loaded.updates.asSequence()

    // --- small pipe-separated, header-named table reader -----------------

    private class Table(val file: File, private val header: List<String>, val rows: List<List<String>>) {
        private val columnIndex: Map<String, Int> = header.withIndex().associate { (i, name) -> name to i }

        fun column(name: String): Int =
            columnIndex[name] ?: throw IllegalStateException("${file.name}: missing column $name")

        fun cell(row: List<String>, name: String): String = row[column(name)]
    }

    private fun readTable(relPath: String, required: Boolean = true): Table? {
        val file = File(dir, relPath)
        if (!file.exists()) {
            if (required) throw IllegalStateException("missing file $relPath")
            return null
        }
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalStateException("${file.name}: file has no header row")
        val header = lines[0].split("|")
        val rows = lines.drop(1).map { it.split("|") }
        return Table(file, header, rows)
    }

    // --- static slice ------------------------------------------------------

    private fun load(): Loaded {
        val slice = loadStaticSlice()
        val updates = loadUpdates().sortedWith(UpdateEventOrder)
        validateReferences(slice, updates.asSequence())
        return Loaded(slice, updates)
    }

    private fun loadStaticSlice(): StaticSlice {
        val tagClasses = loadTagClasses()
        val tags = loadTags()
        val places = loadPlaces()
        val organisations = loadOrganisations()
        val persons = loadPersons()
        val knows = loadKnows()
        val moderatorByForum = loadEdgeMap("forum_hasModerator_person_0_0.csv", "Forum.id", "Person.id")
        val forums = loadForums(moderatorByForum)
        val memberships = loadMemberships()
        val messages = loadMessages()
        val likes = loadLikes()

        return StaticSlice(
            tagClasses = tagClasses,
            tags = tags,
            places = places,
            organisations = organisations,
            persons = persons.sortedBy { it.id },
            knows = knows,
            forums = forums.sortedBy { it.id },
            memberships = memberships.sortedWith(compareBy({ it.creationDate }, { it.personId }, { it.forumId })),
            messages = messages.sortedWith(compareBy({ it.creationDate }, { it.id })),
            likes = likes.sortedWith(compareBy({ it.creationDate }, { it.personId }, { it.messageId })),
        )
    }

    private fun loadTagClasses(): List<TagClass> {
        val table = readTable("tagclass_0_0.csv") ?: return emptyList()
        return table.rows.map { row ->
            TagClass(
                id = table.cell(row, "id").toLong(),
                name = table.cell(row, "name"),
                parentId = table.cell(row, "isSubclassOf").toLongOrNull(),
            )
        }
    }

    private fun loadTags(): List<Tag> {
        val table = readTable("tag_0_0.csv") ?: return emptyList()
        return table.rows.map { row ->
            Tag(
                id = table.cell(row, "id").toLong(),
                name = table.cell(row, "name"),
                tagClassId = table.cell(row, "TagClass.id").toLong(),
            )
        }
    }

    private fun loadPlaces(): List<Place> {
        val table = readTable("place_0_0.csv") ?: return emptyList()
        return table.rows.map { row ->
            Place(
                id = table.cell(row, "id").toLong(),
                name = table.cell(row, "name"),
                type = table.cell(row, "type"),
                partOfId = table.cell(row, "isPartOf").toLongOrNull(),
            )
        }
    }

    private fun loadOrganisations(): List<Organisation> {
        val table = readTable("organisation_0_0.csv") ?: return emptyList()
        return table.rows.map { row ->
            Organisation(
                id = table.cell(row, "id").toLong(),
                name = table.cell(row, "name"),
                type = table.cell(row, "type"),
                placeId = table.cell(row, "Place.id").toLong(),
            )
        }
    }

    private fun loadPersons(): List<Person> {
        val table = readTable("person_0_0.csv")!!
        val placeByPerson = loadEdgeMap("person_isLocatedIn_place_0_0.csv", "Person.id", "Place.id", required = false)
        return table.rows.map { row ->
            val id = table.cell(row, "id").toLong()
            Person(
                id = id,
                firstName = table.cell(row, "firstName"),
                lastName = table.cell(row, "lastName"),
                gender = table.cell(row, "gender"),
                birthday = table.cell(row, "birthday").toLong(),
                creationDate = table.cell(row, "creationDate").toLong(),
                locationIp = table.cell(row, "locationIP"),
                browserUsed = table.cell(row, "browserUsed"),
                placeId = placeByPerson[id],
            )
        }
    }

    /** Reads a two-column `<fromColumn>|<toColumn>[|...]` edge file into a `from -> to` map. */
    private fun loadEdgeMap(relPath: String, fromColumn: String, toColumn: String, required: Boolean = true): Map<Long, Long> {
        val table = readTable(relPath, required) ?: return emptyMap()
        return table.rows.associate { row -> table.cell(row, fromColumn).toLong() to table.cell(row, toColumn).toLong() }
    }

    private fun loadKnows(): List<KnowsEdge> {
        val table = readTable("person_knows_person_0_0.csv")!!
        // Two columns share the same name ("Person.id") in the real file, so the by-name
        // column map only keeps the last one; split the endpoints positionally instead.
        val fromIdx = 0
        val toIdx = 1
        val dateIdx = table.column("creationDate")
        val pairs = LinkedHashMap<Pair<Long, Long>, Long>()
        for (row in table.rows) {
            val x = row.getOrElse(fromIdx) { throw IllegalStateException("${table.file.name}: missing column Person.id") }.toLong()
            val y = row.getOrElse(toIdx) { throw IllegalStateException("${table.file.name}: missing column Person.id") }.toLong()
            val date = row[dateIdx].toLong()
            val pair = if (x < y) x to y else y to x
            pairs.putIfAbsent(pair, date)
        }
        return pairs.map { (pair, date) -> KnowsEdge(pair.first, pair.second, date) }
            .sortedWith(compareBy({ it.a }, { it.b }))
    }

    private fun loadForums(moderatorByForum: Map<Long, Long>): List<Forum> {
        val table = readTable("forum_0_0.csv")!!
        return table.rows.map { row ->
            val id = table.cell(row, "id").toLong()
            Forum(
                id = id,
                title = table.cell(row, "title"),
                moderatorId = moderatorByForum[id]
                    ?: throw IllegalStateException("forum_hasModerator_person_0_0.csv: missing moderator for forum $id"),
                creationDate = table.cell(row, "creationDate").toLong(),
            )
        }
    }

    private fun loadMemberships(): List<Membership> {
        val table = readTable("forum_hasMember_person_0_0.csv") ?: return emptyList()
        return table.rows.map { row ->
            Membership(
                personId = table.cell(row, "Person.id").toLong(),
                forumId = table.cell(row, "Forum.id").toLong(),
                creationDate = table.cell(row, "joinDate").toLong(),
            )
        }
    }

    private fun loadMessages(): List<Message> {
        val postTable = readTable("post_0_0.csv")!!
        val postCreator = loadEdgeMap("post_hasCreator_person_0_0.csv", "Post.id", "Person.id")
        // forum_containerOf_post_0_0.csv is Forum.id|Post.id, many posts per forum, so read it
        // directly into a Post.id -> Forum.id map rather than via loadEdgeMap's 1:1 assumption.
        val postForum: Map<Long, Long> = readTable("forum_containerOf_post_0_0.csv")!!.let { table ->
            table.rows.associate { row -> table.cell(row, "Post.id").toLong() to table.cell(row, "Forum.id").toLong() }
        }
        val posts = postTable.rows.map { row ->
            val id = postTable.cell(row, "id").toLong()
            Message(
                id = id,
                creatorId = postCreator[id]
                    ?: throw IllegalStateException("post_hasCreator_person_0_0.csv: missing creator for post $id"),
                creationDate = postTable.cell(row, "creationDate").toLong(),
                content = postTable.cell(row, "content"),
                forumId = postForum[id]
                    ?: throw IllegalStateException("forum_containerOf_post_0_0.csv: missing forum for post $id"),
                replyOfId = null,
            )
        }

        val commentTable = readTable("comment_0_0.csv")!!
        val commentCreator = loadEdgeMap("comment_hasCreator_person_0_0.csv", "Comment.id", "Person.id")
        val replyOfPost = loadEdgeMap("comment_replyOf_post_0_0.csv", "Comment.id", "Post.id", required = false)
        // comment_replyOf_comment_0_0.csv's header repeats "Comment.id" for both columns
        // (a comment replying to a comment), so the by-name column map would collapse them
        // to the same index; read the two positionally instead.
        val replyOfComment: Map<Long, Long> = readTable("comment_replyOf_comment_0_0.csv", required = false)?.let { table ->
            table.rows.associate { row -> row[0].toLong() to row[1].toLong() }
        } ?: emptyMap()
        val comments = commentTable.rows.map { row ->
            val id = commentTable.cell(row, "id").toLong()
            val replyOfId = replyOfPost[id] ?: replyOfComment[id]
                ?: throw IllegalStateException(
                    "comment_replyOf_post_0_0.csv/comment_replyOf_comment_0_0.csv: missing reply target for comment $id",
                )
            Message(
                id = id,
                creatorId = commentCreator[id]
                    ?: throw IllegalStateException("comment_hasCreator_person_0_0.csv: missing creator for comment $id"),
                creationDate = commentTable.cell(row, "creationDate").toLong(),
                content = commentTable.cell(row, "content"),
                forumId = null,
                replyOfId = replyOfId,
            )
        }

        return posts + comments
    }

    private fun loadLikes(): List<Like> {
        val postLikesTable = readTable("person_likes_post_0_0.csv", required = false)
        val commentLikesTable = readTable("person_likes_comment_0_0.csv", required = false)
        val likes = ArrayList<Like>()
        if (postLikesTable != null) {
            for (row in postLikesTable.rows) {
                likes += Like(
                    personId = postLikesTable.cell(row, "Person.id").toLong(),
                    messageId = postLikesTable.cell(row, "Post.id").toLong(),
                    creationDate = postLikesTable.cell(row, "creationDate").toLong(),
                )
            }
        }
        if (commentLikesTable != null) {
            for (row in commentLikesTable.rows) {
                likes += Like(
                    personId = commentLikesTable.cell(row, "Person.id").toLong(),
                    messageId = commentLikesTable.cell(row, "Comment.id").toLong(),
                    creationDate = commentLikesTable.cell(row, "creationDate").toLong(),
                )
            }
        }
        return likes
    }

    // --- update stream -------------------------------------------------

    private fun loadUpdates(): List<UpdateEvent> =
        loadUpdateFile("updateStream_0_0_person.csv") + loadUpdateFile("updateStream_0_0_forum.csv")

    private fun loadUpdateFile(relPath: String): List<UpdateEvent> {
        val table = readTable(relPath) ?: return emptyList()
        return table.rows.map { row -> parseUpdateEvent(table, row) }
    }

    private fun parseUpdateEvent(table: Table, row: List<String>): UpdateEvent {
        fun cell(name: String) = table.cell(row, name)
        fun cellOrNull(name: String): String? = cell(name).ifBlank { null }

        val type = cell("type")
        val creationDate = cell("creationDate").toLong()
        return when (type) {
            "IU1" -> IU1AddPerson(
                Person(
                    id = cell("personId").toLong(),
                    firstName = cell("firstName"),
                    lastName = cell("lastName"),
                    gender = cell("gender"),
                    birthday = cell("birthday").toLong(),
                    creationDate = creationDate,
                    locationIp = cell("locationIp"),
                    browserUsed = cell("browserUsed"),
                    placeId = cellOrNull("placeId")?.toLong(),
                ),
            )
            "IU2" -> IU2LikePost(
                personId = cell("personId").toLong(),
                postId = cell("postId").toLong(),
                creationDate = creationDate,
            )
            "IU3" -> IU3LikeComment(
                personId = cell("personId").toLong(),
                commentId = cell("commentId").toLong(),
                creationDate = creationDate,
            )
            "IU4" -> IU4AddForum(
                Forum(
                    id = cell("forumId").toLong(),
                    title = cell("title"),
                    moderatorId = cell("moderatorId").toLong(),
                    creationDate = creationDate,
                ),
            )
            "IU5" -> IU5AddMembership(
                personId = cell("personId").toLong(),
                forumId = cell("forumId").toLong(),
                creationDate = creationDate,
            )
            "IU6" -> IU6AddPost(
                Message(
                    id = cell("postId").toLong(),
                    creatorId = cell("personId").toLong(),
                    creationDate = creationDate,
                    content = cell("content"),
                    forumId = cell("forumId").toLong(),
                    replyOfId = null,
                ),
            )
            "IU7" -> IU7AddComment(
                Message(
                    id = cell("commentId").toLong(),
                    creatorId = cell("personId").toLong(),
                    creationDate = creationDate,
                    content = cell("content"),
                    forumId = null,
                    replyOfId = cell("replyOfId").toLong(),
                ),
            )
            "IU8" -> IU8AddFriendship(
                a = cell("personId").toLong(),
                b = cell("otherPersonId").toLong(),
                creationDate = creationDate,
            )
            else -> throw IllegalStateException("${table.file.name}: unknown update event type '$type'")
        }
    }
}
