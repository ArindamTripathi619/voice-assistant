package dev.crewx.voiceassistant.core.tools

import kotlinx.serialization.json.Json

/**
 * Loads and validates `tools.json`, then serves the subset of tools a given
 * utterance needs.
 *
 * Two responsibilities that must not be split:
 *
 *  1. **Validation.** A tool whose `required` names a parameter it does not
 *     declare is unusable — the model cannot fill a slot that has no schema, and
 *     the resulting JSON is unparseable. Caught at load time, not at runtime on
 *     the phone.
 *
 *  2. **Prompt minimization.** All 17 tools cost prefill on every call. On a
 *     Snapdragon 695 that prefill is a real fraction of the latency budget, so
 *     [selectFor] injects only the groups a query plausibly needs, plus the
 *     safety net. The returned group key is stable for a given set, which lets
 *     the caller cache the KV state of the schema prefix.
 */
class ToolsLoader private constructor(
    val all: List<ToolSpec>,
    private val byName: Map<String, ToolSpec>,
    private val byGroup: Map<String, List<ToolSpec>>,
    val version: Int,
    val notes: List<String>,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    companion object {
        /**
         * @throws ToolSchemaException if the file is malformed or internally
         *         inconsistent (unknown required param, duplicate name, empty set).
         */
        fun load(jsonText: String): ToolsLoader {
            val file = try {
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString(ToolsFile.serializer(), jsonText)
            } catch (e: Exception) {
                throw ToolSchemaException("tools.json is not parseable: ${e.message}", e)
            }

            if (file.tools.isEmpty()) throw ToolSchemaException("tools.json declares no tools")

            val dupes = file.tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) {
                throw ToolSchemaException("duplicate tool names: ${dupes.joinToString()}")
            }

            file.tools.forEach { spec ->
                spec.parameters.required.forEach { req ->
                    if (!spec.parameters.properties.containsKey(req)) {
                        throw ToolSchemaException(
                            "tool '${spec.name}' requires undeclared parameter '$req'",
                        )
                    }
                }
                if (spec.group.isBlank()) {
                    throw ToolSchemaException("tool '${spec.name}' has a blank group")
                }
            }

            val missingSafety = ToolSchema.SAFETY_NET_TOOLS - file.tools.map { it.name }.toSet()
            if (missingSafety.isNotEmpty()) {
                throw ToolSchemaException(
                    "tools.json must include safety-net tools: ${missingSafety.joinToString()}",
                )
            }

            return ToolsLoader(
                all = file.tools,
                byName = file.tools.associateBy { it.name },
                byGroup = file.tools.groupBy { it.group },
                version = file.version,
                notes = file.notes,
            )
        }
    }

    fun tool(name: String): ToolSpec? = byName[name]

    fun require(name: String): ToolSpec =
        byName[name] ?: throw ToolSchemaException("unknown tool '$name'")

    val groups: Set<String> get() = byGroup.keys

    fun inGroup(group: String): List<ToolSpec> = byGroup[group].orEmpty()

    /** Tools in [groups], de-duplicated, in declaration order. */
    fun forGroups(groups: Collection<String>): List<ToolSpec> {
        val wanted = groups.toSet() + ToolSchema.ALWAYS_GROUPS
        return all.filter { it.group in wanted }
    }

    /**
     * Tools in [groups] plus the safety net, but *without* the rest of the
     * assistant group.
     *
     * This is the path the router actually uses. `teach_alias` and `run_routine`
     * are rare, comparatively long declarations, and always injecting them costs
     * prefill on every single command — so they ride along only when the
     * utterance hints at the assistant group, while `ask_clarification` and
     * `unsupported` are always present so the model can never be forced to
     * invent an action.
     */
    fun forGroupsWithSafetyNet(groups: Collection<String>): List<ToolSpec> {
        val requested = groups.toSet()
        // An explicitly requested "assistant" pulls in teach_alias/run_routine;
        // an implicit one (always-injected safety net) does not.
        val wanted = if (ToolSchema.ALWAYS_GROUPS.any { it in requested }) {
            requested
        } else {
            requested - ToolSchema.ALWAYS_GROUPS
        }
        return all.filter { it.group in wanted || it.name in ToolSchema.SAFETY_NET_TOOLS }
    }

    /**
     * Pick the minimal tool set for an utterance.
     *
     * [hintGroups] comes from the fast path's cheap lexical guess. Passing an
     * empty hint still yields the safety-net tools, so the LLM can always decline
     * or ask rather than hallucinate.
     *
     * @param maxGroups hard cap on hinted groups, to bound prompt size.
     */
    fun selectFor(hintGroups: Collection<String>, maxGroups: Int = 3): ToolSelection {
        val hints = rankGroups(hintGroups).take(maxGroups)
        return ToolSelection(
            tools = forGroupsWithSafetyNet(hints),
            groups = (hints + ToolSchema.ALWAYS_GROUPS).toSortedSet(),
        )
    }

    /**
     * Orders hint groups deterministically by their first appearance in
     * `tools.json`, then by name.
     *
     * This must not depend on the order the caller supplied hints in: the fast
     * path collects them by scanning utterance tokens, so ["device","comms"] and
     * ["comms","device"] arise from semantically identical requests. Ramping by
     * caller order would make `maxGroups` truncation non-deterministic and would
     * shatter the KV prefix cache across equivalent utterances.
     */
    private fun rankGroups(hintGroups: Collection<String>): List<String> {
        val groupOrder = LinkedHashMap<String, Int>()
        all.forEachIndexed { index, spec ->
            groupOrder.putIfAbsent(spec.group, index)
        }
        return hintGroups.map { it.lowercase() }
            .filter { it in byGroup }
            .distinct()
            .sortedWith(
                compareBy({ groupOrder[it] ?: Int.MAX_VALUE }, { it }),
            )
    }

    /**
     * Stable cache key for the injected schema prefix.
     *
     * The KV prefix cache is keyed on this string, so equal keys must imply
     * byte-identical prompts. Derived from the selected tool names rather than
     * the hint groups, because two different hint sets can select the same tools.
     */
    fun selectionKey(hintGroups: Collection<String>, maxGroups: Int = 3): String =
        selectFor(hintGroups, maxGroups).toolNames.joinToString("|")
}

data class ToolSelection(
    val tools: List<ToolSpec>,
    val groups: Set<String>,
) {
    val toolNames: List<String> get() = tools.map { it.name }

    /** JSON Schema declarations for the model, stripped of executor-only fields. */
    fun toModelSchema(): String = tools.joinToString(",\n") { spec ->
        buildString {
            append("  {\"name\": \"").append(spec.name).append("\", ")
            append("\"description\": \"").append(spec.description).append("\", ")
            append("\"parameters\": {")
            append("\"type\": \"object\", ")
            val props = spec.parameters.properties
            append("\"properties\": {")
            append(props.entries.joinToString(", ") { (k, v) ->
                val parts = mutableListOf("\"type\": \"${v.type}\"")
                // Numeric enums (e.g. sim: [1,2]) must stay numeric in the schema,
                // or the model emits "1" as a string and the executor has to guess.
                v.enum?.let { values ->
                    parts.add("\"enum\": [${values.joinToString(", ") { it.toString() }}]")
                }
                v.default?.let { parts.add("\"default\": $it") }
                v.items?.let { parts.add("\"items\": {\"type\": \"${it.type}\"}") }
                "\"$k\": {${parts.joinToString(", ")}}"
            })
            append("}")
            if (spec.parameters.required.isNotEmpty()) {
                append(", \"required\": [${spec.parameters.required.joinToString(", ") { "\"$it\"" }}]")
            }
            append("}}")
        }
    }

    /** Rough prefill proxy: total characters of injected schema. */
    fun schemaCharCount(): Int = toModelSchema().length
}

class ToolSchemaException(message: String, cause: Throwable? = null) : Exception(message, cause)
