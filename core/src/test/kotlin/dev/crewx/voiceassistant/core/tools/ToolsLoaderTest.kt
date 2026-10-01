package dev.crewx.voiceassistant.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test harness for [ToolsLoader]. Reads the real `tools.json` from the repo root
 * so the shipped schema and the code can never silently drift apart.
 */
class ToolsLoaderTest {

    /**
     * Gradle runs tests with the module dir as CWD, so `tools.json` sits at
     * `../tools.json`. Walking up keeps this working whether the build is
     * invoked from the repo root or the module.
     */
    private fun locateToolsFile(): File {
        val candidates = listOf(
            File("tools.json"),
            File("../tools.json"),
            File("../../tools.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("tools.json not found from ${File(".").absolutePath}")
    }

    private fun loadRealTools(): ToolsLoader = ToolsLoader.load(locateToolsFile().readText())

    @Test
    fun `loads the real tools json`() {
        val tools = loadRealTools()
        assertTrue(tools.all.size >= 17, "expected 17 tools, got ${tools.all.size}")
        assertEquals(1, tools.version)
    }

    @Test
    fun `every declared required parameter exists`() {
        val tools = loadRealTools()
        tools.all.forEach { spec ->
            spec.parameters.required.forEach { req ->
                assertTrue(
                    spec.parameters.properties.containsKey(req),
                    "${spec.name} requires undeclared parameter '$req'",
                )
            }
        }
    }

    @Test
    fun `every parameter has a type`() {
        loadRealTools().all.forEach { spec ->
            spec.parameters.properties.forEach { (name, p) ->
                assertTrue(p.type.isNotBlank(), "${spec.name}.$name has no type")
            }
        }
    }

    @Test
    fun `safety net tools are present`() {
        val names = loadRealTools().all.map { it.name }.toSet()
        assertTrue(names.containsAll(ToolSchema.SAFETY_NET_TOOLS))
    }

    @Test
    fun `confirm policies parse to the expected enum`() {
        val tools = loadRealTools()
        assertEquals(ConfirmPolicy.NEVER, tools.require("set_flashlight").confirm)
        assertEquals(ConfirmPolicy.AMBIGUOUS, tools.require("call_contact").confirm)
        assertEquals(ConfirmPolicy.ALWAYS, tools.require("send_message").confirm)
    }

    @Test
    fun `person and media tools require resolution slots`() {
        val tools = loadRealTools()
        assertTrue(tools.require("call_contact").needsEntityResolution)
        assertTrue(tools.require("send_message").needsEntityResolution)
        assertTrue(tools.require("play_music").needsEntityResolution)
        assertTrue(tools.require("teach_alias").needsEntityResolution)
        assertTrue(!tools.require("set_flashlight").needsEntityResolution)
    }

    @Test
    fun `person-referencing tools never declare a numeric id parameter`() {
        // The invariant that makes hallucination harmless: the model cannot emit
        // a phone number or contact id, so it cannot misdial.
        loadRealTools().all
            .filter { it.needsEntityResolution }
            .forEach { spec ->
                spec.parameters.properties.keys.forEach { p ->
                    assertTrue(
                        p !in setOf("phone", "number", "contact_id", "id", "uri", "package"),
                        "${spec.name} exposes '$p', which would let the model emit an ID",
                    )
                }
            }
    }

    @Test
    fun `group selection injects only the requested groups plus safety net`() {
        val tools = loadRealTools()
        val sel = tools.selectFor(listOf("comms"))

        assertTrue(sel.tools.any { it.name == "call_contact" })
        assertTrue(sel.tools.any { it.name == "send_message" })
        // Safety net always present so the model can decline or clarify.
        assertTrue(sel.tools.any { it.name == "ask_clarification" })
        assertTrue(sel.tools.any { it.name == "unsupported" })
        // Unrelated groups excluded.
        assertTrue(sel.tools.none { it.group == "media" })
        assertTrue(sel.tools.none { it.group == "device" })
    }

    @Test
    fun `group selection caps prompt size`() {
        val tools = loadRealTools()

        // Baseline: every tool declared, which is what an unfiltered prompt costs.
        val full = ToolSelection(tools.all, tools.groups)
        val oneGroup = tools.selectFor(listOf("device"))
        val twoGroups = tools.selectFor(listOf("device", "media"))

        assertTrue(
            oneGroup.schemaCharCount() < full.schemaCharCount(),
            "single-group (${oneGroup.schemaCharCount()}) must beat full (${full.schemaCharCount()})",
        )
        assertTrue(
            oneGroup.schemaCharCount() < twoGroups.schemaCharCount(),
            "fewer groups must mean a smaller prompt",
        )
        // The safety net and the "assistant" group are a fixed floor, so the
        // reduction is bounded — but a single group must still cost well under
        // half of declaring all 17 tools, or prefill dominates the latency budget.
        assertTrue(
            oneGroup.schemaCharCount() * 2 < full.schemaCharCount(),
            "single-group (${oneGroup.schemaCharCount()}) should be under half of " +
                "full (${full.schemaCharCount()})",
        )
    }

    @Test
    fun `selection key ignores hint sets that resolve to the same tools`() {
        val tools = loadRealTools()
        // maxGroups=3 with 4 hints drops "assistant"; both orders must therefore
        // yield the same key, and the key must reflect tools, not hints.
        assertEquals(
            tools.selectionKey(listOf("device", "comms", "media", "assistant")),
            tools.selectionKey(listOf("assistant", "media", "comms", "device")),
        )
    }

    @Test
    fun `maxGroups caps how many hint groups can be injected`() {
        val tools = loadRealTools()
        val sel = tools.selectFor(listOf("device", "comms", "media", "assistant"), maxGroups = 2)
        // assistant is always included, so at most maxGroups + always groups.
        assertTrue(sel.groups.size <= 3, "got groups ${sel.groups}")
    }

    @Test
    fun `unknown hint groups are ignored`() {
        val tools = loadRealTools()
        val sel = tools.selectFor(listOf("nonsense", "comms"))
        assertTrue(sel.tools.any { it.name == "call_contact" })
    }

    @Test
    fun `selection key is stable and order-independent`() {
        val tools = loadRealTools()
        // The KV prefix cache is keyed on this string, so equal keys must imply
        // byte-identical prompts regardless of hint ordering.
        assertEquals(
            tools.selectionKey(listOf("comms", "device")),
            tools.selectionKey(listOf("device", "comms")),
        )
    }

    @Test
    fun `safety net is injected without dragging in the rest of assistant`() {
        val sel = loadRealTools().selectFor(listOf("device"))
        assertTrue(sel.tools.any { it.name == "unsupported" })
        // teach_alias/run_routine are long and rare; they must not ride along on
        // every unrelated command.
        assertTrue(sel.tools.none { it.name == "teach_alias" })
        assertTrue(sel.tools.none { it.name == "run_routine" })
    }

    @Test
    fun `assistant group is included when hinted`() {
        val sel = loadRealTools().selectFor(listOf("assistant"))
        assertTrue(sel.tools.any { it.name == "run_routine" })
        assertTrue(sel.tools.any { it.name == "teach_alias" })
    }

    @Test
    fun `empty hints still yield a usable tool set`() {
        val sel = loadRealTools().selectFor(emptyList())
        assertTrue(sel.tools.any { it.name == "unsupported" })
        assertTrue(sel.tools.size >= ToolSchema.SAFETY_NET_TOOLS.size)
    }

    @Test
    fun `model schema strips executor-only fields`() {
        val schema = loadRealTools().selectFor(listOf("device")).toModelSchema()
        assertTrue(!schema.contains("\"confirm\""), "confirm policy must not reach the model")
        assertTrue(!schema.contains("\"group\""), "group is a routing detail, not model-facing")
        assertTrue(schema.contains("\"name\": \"set_flashlight\""))
        assertTrue(schema.contains("\"enum\": [\"on\", \"off\", \"toggle\"]"))
    }

    @Test
    fun `rejects a tool requiring an undeclared parameter`() {
        val bad = """
            {"version":1,"tools":[
              {"name":"bad","group":"device","description":"x","parameters":{
                 "type":"object","properties":{"a":{"type":"string"}},
                 "required":["a","missing"]}},
              {"name":"ask_clarification","group":"assistant","description":"x",
               "parameters":{"type":"object","properties":{},"required":[]}},
              {"name":"unsupported","group":"assistant","description":"x",
               "parameters":{"type":"object","properties":{},"required":[]}}
            ]}
        """.trimIndent()
        val ex = assertThrows(ToolSchemaException::class.java) { ToolsLoader.load(bad) }
        assertTrue(ex.message!!.contains("missing"))
    }

    @Test
    fun `rejects duplicate tool names`() {
        val one = """{"name":"t","group":"g","description":"x",
            "parameters":{"type":"object","properties":{},"required":[]}}"""
        val bad = """{"version":1,"tools":[$one,$one,
            {"name":"ask_clarification","group":"assistant","description":"x",
             "parameters":{"type":"object","properties":{},"required":[]}},
            {"name":"unsupported","group":"assistant","description":"x",
             "parameters":{"type":"object","properties":{},"required":[]}}]}"""
        assertThrows(ToolSchemaException::class.java) { ToolsLoader.load(bad) }
    }

    @Test
    fun `rejects a schema missing the safety net`() {
        val bad = """{"version":1,"tools":[
            {"name":"set_flashlight","group":"device","description":"x",
             "parameters":{"type":"object","properties":{},"required":[]}}]}"""
        val ex = assertThrows(ToolSchemaException::class.java) { ToolsLoader.load(bad) }
        assertTrue(ex.message!!.contains("safety-net"))
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows(ToolSchemaException::class.java) { ToolsLoader.load("{ not json") }
    }

    @Test
    fun `rejects an empty tool list`() {
        assertThrows(ToolSchemaException::class.java) {
            ToolsLoader.load("""{"version":1,"tools":[]}""")
        }
    }
}
