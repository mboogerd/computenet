package civictech.demo.studio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class StudioArgvTest {
    @Test fun `tokenizer handles quotes escapes empty args and substitutes after tokenization`() {
        val template = "printf '%s' \"prefix {{in}} suffix\" '' a\\ b 'literal \\\\ x'"
        val tokens = tokenize(template)
        assertEquals(listOf("printf", "%s", "prefix {{in}} suffix", "", "a b", "literal \\\\ x"), tokens)
        val argv = tokens.map { substitute(it, mapOf("in" to change("old", "; rm -rf /"))) }
        assertEquals("prefix ; rm -rf / suffix", argv[2]); assertEquals(6, argv.size)
        assertEquals("old|; rm -rf /|", substitute("{{in.old}}|{{in.new}}|{{missing}}", mapOf("in" to change("old", "; rm -rf /"))))
        assertEquals("author", substitute("{{in.author-name}}", mapOf("in" to obj("author-name" to JsonPrimitive("author")))))
        assertEquals(listOf("echo", "x"), tokenize("echo \\\n x"))
        assertThrows(StudioException::class.java) { tokenize("echo 'oops") }
        assertThrows(StudioException::class.java) { tokenize("echo \\") }
    }

    @Test fun `ports are ordered unique roots and preset defaults disable automatic agent runs`() {
        val c = nodeConfig("exec", obj("command" to JsonPrimitive("echo {{z.repo}} {{a.old}} {{z}}")))
        assertEquals(listOf("z", "a"), inputPorts("exec", c))
        assertFalse(nodeConfig("exec", obj("preset" to JsonPrimitive("claude"))).bool("auto"))
        assertFalse(nodeConfig("exec", obj("preset" to JsonPrimitive("codex"))).bool("auto"))
        assertTrue(c.bool("auto"))
    }

    @Test fun `claude argv confines tools turns budget and prompt`() {
        val cfg = nodeConfig("exec", buildJsonObject { put("preset", "claude"); put("model", "model"); put("maxTurns", 3); put("maxBudgetUsd", 0.5) })
        val args = claudeArgv(cfg, "prompt ; rm -rf /")
        fun value(flag: String) = args[args.indexOf(flag) + 1]
        assertEquals("Read,Grep,Glob", value("--tools")); assertEquals("none", value("--permission-prompts"))
        assertEquals("3", value("--max-turns")); assertEquals("0.5", value("--max-budget-usd")); assertEquals("model", value("--model"))
        assertTrue(args.containsAll(listOf("--safe-mode", "--restricted", "--strict-mcp-config", "--no-session-persistence")))
        assertEquals(listOf("--", "prompt ; rm -rf /"), args.takeLast(2))
        val none = claudeArgv(cfg, "generate", noTools = true)
        assertEquals("", none[none.indexOf("--tools") + 1])
    }

    @Test fun `claude string budget renders as a number argument`() {
        val cfg = nodeConfig("exec", buildJsonObject { put("preset", "claude"); put("maxBudgetUsd", "1.5") })
        val args = claudeArgv(cfg, "prompt")
        assertEquals("1.5", args[args.indexOf("--max-budget-usd") + 1])
    }

    @Test fun `codex blank model preserves user configured default`() {
        val args = codexArgv(nodeConfig("exec", buildJsonObject { put("preset", "codex"); put("model", "") }), "prompt", Path.of("/tmp/last.txt"))
        assertFalse("--ignore-user-config" in args)
        assertFalse("--strict-config" in args)
        assertFalse("-m" in args)
        assertEquals("read-only", args[args.indexOf("-s") + 1])
    }

    @Test fun `codex argv is ephemeral read only and codegen disables command tools`() {
        val cfg = nodeConfig("exec", buildJsonObject { put("preset", "codex"); put("model", "model"); put("effort", "high") })
        val out = Path.of("/tmp/last.txt")
        val args = codexArgv(cfg, "prompt", out)
        assertEquals("read-only", args[args.indexOf("-s") + 1]); assertTrue("model_reasoning_effort=\"high\"" in args)
        assertFalse("--ignore-user-config" in args); assertFalse("--strict-config" in args)
        assertEquals("model", args[args.indexOf("-m") + 1])
        assertTrue("--ephemeral" in args); assertEquals(out.toString(), args[args.indexOf("-o") + 1])
        assertEquals(listOf("--", "prompt"), args.takeLast(2))
        assertTrue(codexArgv(cfg, "generate", out, noTools = true).containsAll(listOf("shell_tool", "unified_exec")))
    }
}
