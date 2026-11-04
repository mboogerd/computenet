package civictech.demo.studio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StudioBeadsTest {
    @Test fun `defaults and validation keep sources inputless and both sinks manual`() {
        val config = nodeConfig("beads", JsonObject(emptyMap()))
        assertEquals("ready", config.string("query")); assertEquals(60, config.long("intervalSec", 0))
        assertEquals(emptyList<String>(), inputPorts("beads", config))
        for (preset in listOf("bd-comment", "bd-create")) {
            val sink = nodeConfig("exec", obj("preset" to JsonPrimitive(preset)))
            assertFalse(sink.bool("auto")); assertEquals(listOf("in"), inputPorts("exec", sink))
        }
        for (invalid in listOf(obj("workspace" to JsonPrimitive("relative")), obj("query" to JsonPrimitive("delete")), obj("intervalSec" to JsonPrimitive(0))))
            assertThrows(StudioException::class.java) { nodeConfig("beads", invalid) }
    }

    @Test fun `all sink template fields contribute ordered unique input ports`() {
        val config = nodeConfig("exec", buildJsonObject {
            put("preset", "bd-create"); put("title", "{{a}} {{b.new}}"); put("description", "{{c.old}}")
            put("type", "{{a.type}}"); put("priority", "{{d}}" ); put("labels", "{{e}}")
        })
        assertEquals(listOf("a", "b", "c", "d", "e"), inputPorts("exec", config))
        val comment = nodeConfig("exec", buildJsonObject { put("preset", "bd-comment"); put("issue", "{{issue}}" ); put("text", "{{body}}") })
        assertEquals(listOf("issue", "body"), inputPorts("exec", comment))
    }

    private fun row(id: String, title: String = id, labels: List<String> = emptyList()) = buildJsonObject {
        put("id", id); put("title", title); put("status", "open"); put("priority", 2); put("issue_type", "task")
        put("labels", JsonArray(labels.map(::JsonPrimitive))); put("description", "ignored")
    }
    @Test fun `normalization sorts issues and labels and discards irrelevant fields`() {
        val a = row("a", labels = listOf("z", "b")); val b = row("b")
        val set = beadsSet(JsonArray(listOf(b, a)).toString())
        assertEquals(set, beadsSet(JsonArray(listOf(row("a", labels = listOf("b", "z", "b")), b)).toString()))
        val first = json.parseToJsonElement(set).jsonArray.first().jsonObject
        assertEquals(setOf("id", "title", "status", "priority", "type", "labels"), first.keys)
        assertEquals("a", first.string("id")); assertEquals("task", first.string("type"))
        assertEquals("[]", beadsSet("null"))
    }

    @Test fun `set deltas and coalescing describe the net change with first old and latest new`() {
        fun set(vararg rows: JsonObject) = beadsSet(JsonArray(rows.toList()).toString())
        val initial = set(row("a"), row("gone")); val middle = set(row("a", "updated"), row("one"))
        val latest = set(row("a", "updated"), row("one"), row("two"))
        val composed = composeBeadsOrChange(beadsChange(initial, middle, "/scratch"),
            JsonObject(beadsChange(middle, latest, "/scratch") + ("fired" to JsonPrimitive("timestamp"))))
        assertEquals(initial, composed.string("old")); assertEquals(latest, composed.string("new"))
        assertEquals("[\"one\",\"two\"]", composed["added"].toString())
        assertEquals("[\"gone\"]", composed["removed"].toString()); assertEquals("[\"a\"]", composed["changed"].toString())
        assertEquals("timestamp", composed.string("fired"))
        val transient = composeBeadsOrChange(beadsChange("[]", middle, "/scratch"), beadsChange(middle, "[]", "/scratch"))
        assertEquals(JsonArray(emptyList()), transient["added"]); assertEquals(JsonArray(emptyList()), transient["removed"])
        assertEquals(change("one", "three"), composeBeadsOrChange(change("one", "two"), change("two", "three")))
    }
}
