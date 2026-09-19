/**
 * Loads an [SnbSource]'s [StaticSlice] into a [SocialGraph], in fixed field
 * order (99qcg-D4, [SOC1-GEN-04]). This object never inspects which
 * [SnbSource] implementation it was given — [SnbGenerator] (this task) and
 * `DatagenCsvSource` (a sibling task) are interchangeable here.
 */
package civictech.demo.social

object SocialLoader {
    /** Loads [source]'s static slice into [graph], in [StaticSlice]'s own field order. */
    fun load(source: SnbSource, graph: SocialGraph) {
        val slice = source.staticSlice()

        slice.tagClasses.forEach { graph.addTagClass(it) }
        slice.tags.forEach { graph.addTag(it) }
        slice.places.forEach { graph.addPlace(it) }
        slice.organisations.forEach { graph.addOrganisation(it) }
        slice.persons.forEach { graph.addPerson(it) }
        slice.knows.forEach { graph.addKnows(it.a, it.b, it.creationDate) }
        slice.forums.forEach { graph.addForum(it) }
        slice.memberships.forEach { graph.joinForum(it.personId, it.forumId, it.creationDate) }
        slice.messages.forEach { message ->
            if (message.forumId != null) graph.addPost(message) else graph.addComment(message)
        }
        slice.likes.forEach { graph.addLike(it) }
    }
}
