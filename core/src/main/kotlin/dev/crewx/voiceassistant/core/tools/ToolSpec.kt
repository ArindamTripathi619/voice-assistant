package dev.crewx.voiceassistant.core.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Confirmation policy enforced by the executor. Never shown to the model. */
@Serializable
enum class ConfirmPolicy {
    @SerialName("never") NEVER,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("always") ALWAYS;

    companion object {
        fun from(raw: String?): ConfirmPolicy = when (raw?.lowercase()) {
            "always" -> ALWAYS
            "ambiguous" -> AMBIGUOUS
            else -> NEVER
        }
    }
}

/**
 * One tool declaration, parsed from `tools.json`.
 *
 * `group` drives prompt construction: only 1-3 groups are injected per query to
 * keep the prefix short and therefore cacheable on a CPU-bound device.
 */
@Serializable
data class ToolSpec(
    val name: String,
    val group: String,
    @SerialName("confirm") val confirmRaw: String? = null,
    val description: String,
    val parameters: ParameterSchema,
) {
    val confirm: ConfirmPolicy get() = ConfirmPolicy.from(confirmRaw)

    /** Tools every group must be able to fall back on. */
    val isSafetyNet: Boolean get() = name in ToolSchema.SAFETY_NET_TOOLS

    /** True when any parameter is a raw-spoken-string slot needing resolution. */
    val needsEntityResolution: Boolean
        get() = parameters.properties.keys.any { it in ToolSchema.RESOLUTION_SLOTS }
}

@Serializable
data class ParameterSchema(
    val type: String = "object",
    val properties: Map<String, ParamSpec> = emptyMap(),
    val required: List<String> = emptyList(),
)

/**
 * One parameter declaration.
 *
 * `enum` is kept as raw JSON rather than `List<String>` because schemas mix
 * value types: `sim` enumerates integers `[1, 2]` while `channel` enumerates
 * strings. Typed accessors below keep the model-facing schema correct for both.
 */
@Serializable
data class ParamSpec(
    val type: String = "string",
    val enum: List<JsonElement>? = null,
    val default: JsonElement? = null,
    val description: String? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val items: ParamSpec? = null,
) {
    /** Enum values as strings; numbers render without a decimal point. */
    val enumStrings: List<String>?
        get() = enum?.mapNotNull { element ->
            element.jsonPrimitive.contentOrNull
        }

    val enumInts: List<Int>?
        get() = enum?.mapNotNull { it.jsonPrimitive.intOrNull }

    val defaultString: String?
        get() = default?.jsonPrimitive?.contentOrNull

    val isEnum: Boolean get() = !enum.isNullOrEmpty()
}

@Serializable
data class ToolsFile(
    val version: Int = 1,
    val notes: List<String> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * A tool invocation emitted by either the fast path or the LLM.
 *
 * Invariant enforced by [ToolSpec.needsEntityResolution]: parameters named in
 * [RESOLUTION_SLOTS] hold *raw spoken strings*. The model never emits phone
 * numbers, contact IDs or URIs, so a hallucinated slot can only cause a
 * clarification prompt, never a wrong action.
 */
data class ToolCall(
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val confidence: Double = 1.0,
    val route: Route = Route.FAST,
) {
    fun argString(key: String): String? = args[key] as? String
    fun argInt(key: String): Int? = when (val v = args[key]) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.toIntOrNull()
        is Double -> v.toInt()
        else -> null
    }
    fun argBool(key: String, default: Boolean = false): Boolean = when (val v = args[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }
}

enum class Route { FAST, TIER1, TIER2, MANUAL }

/** What the router decided: execute this, or ask the user something. */
sealed interface RouteResult {
    data class Execute(val call: ToolCall) : RouteResult
    data class Clarify(val question: String, val candidates: List<String> = emptyList()) : RouteResult
    data class PendingConfirm(val call: ToolCall, val spokenSummary: String) : RouteResult
    data class Unsupported(val reason: String) : RouteResult
    data object NoMatch : RouteResult
}

object ToolSchema {
    /** Parameter names that carry raw spoken strings requiring resolution. */
    val RESOLUTION_SLOTS = setOf(
        "contact_ref", "from_ref", "query", "app_name", "alias", "name",
    )

    /** Always available so the model never has to invent an action. */
    val SAFETY_NET_TOOLS = setOf("ask_clarification", "unsupported")

    /** Group always injected, regardless of routing. */
    val ALWAYS_GROUPS = setOf("assistant")
}
