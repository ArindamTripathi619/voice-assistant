package dev.crewx.voiceassistant.core.fastpath

import dev.crewx.voiceassistant.core.text.TextNormalizer

/**
 * Rule-based intent matcher for the deterministic tier.
 *
 * Scope is deliberate. This handles the high-frequency, unambiguous commands
 * where a 270M model would only add latency and a failure mode: flashlight,
 * brightness, volume, connectivity toggles, alarms, timers, media transport and
 * "open app". Person-referencing intents are *detected* here (so the router knows
 * to inject the `comms` group) but deliberately **not** resolved — that is the
 * resolver's job, and guessing a name is how you misdial.
 *
 * Every rule is anchored on normalized tokens rather than free-form regex over
 * the raw string, so "Turn ON the flashlight" and "flashlight on" collapse to the
 * same token sequence before matching.
 */
class FastPath(
    private val relationTerms: Set<String> = emptySet(),
    private val appLabels: Set<String> = emptySet(),
) {

    private data class Rule(
        val id: String,
        val groups: Set<String>,
        val match: (Tokens) -> Boolean,
        val build: (Tokens) -> ToolCallDraft?,
    )

    /** Normalized tokens plus the original text, for slot extraction. */
    private class Tokens(val raw: String, val norm: String, val words: List<String>) {
        fun has(vararg any: String): Boolean = words.any { it in any }

        fun hasAll(vararg any: String): Boolean = any.all { it in words }

        /** True when any token starts with [prefix]; captures truncated words. */
        fun hasPrefix(prefix: String): Boolean = words.any { it.startsWith(prefix) }

        fun indexOf(token: String): Int = words.indexOf(token)

        /** Position of the first token satisfying [p]. */
        fun indexOfFirst(p: (String) -> Boolean): Int = words.indexOfFirst(p)
    }

    private val rules: List<Rule> = buildRules()

    /**
     * @return a draft tool call, or null when no rule applies (route to the LLM).
     */
    fun match(utterance: String): ToolCallDraft? {
        val norm = TextNormalizer.normalize(utterance)
        if (norm.isEmpty()) return null
        val t = Tokens(utterance, norm, norm.split(' ').filter { it.isNotEmpty() })
        if (t.words.isEmpty()) return null

        // First matching rule wins, so ordering below is specificity order:
        // specific multi-token intents before generic ones.
        for (rule in rules) {
            if (!rule.match(t)) continue
            val call = rule.build(t) ?: continue
            return ToolCallDraft(
                tool = call.tool,
                args = call.args,
                groups = rule.groups,
            )
        }
        return null
    }

    /**
     * Cheap group guess used to decide which tool schemas the LLM sees.
     * Independent of [match], so an unmatched utterance still routes sensibly.
     */
    fun guessGroups(utterance: String): Set<String> {
        val norm = TextNormalizer.normalize(utterance)
        val words = norm.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptySet()
        val groups = linkedSetOf<String>()

        if (words.any { it in relationTerms }) groups += "comms"
        if (words.any { it in PERSON_VERBS }) groups += "comms"
        if (words.any { it in MEDIA_VERBS }) groups += "media"
        if (words.any { it in DEVICE_VERBS } || words.any { it in SETTING_TOKENS }) {
            groups += "device"
        }
        if (words.any { it in appLabels }) groups += "device"
        return groups
    }

    private fun buildRules(): List<Rule> = listOf(
        // ---------------------------------------------------- media transport --
        mediaPauseRule(), mediaNextRule(), mediaPrevRule(), mediaStopRule(),
        mediaShuffleRule(), mediaLikeRule(),

        // ------------------------------------------------- flashlight, state --
        flashlightRule(),

        // --------------------------------------------------------- brightness --
        brightnessRule(),

        // ------------------------------------------------------------ volume --
        volumeRule(),

        // ------------------------------------------------- connectivity toggles --
        toggleSettingRule(),

        // ------------------------------------------------------ alarms, timers --
        alarmRule(), timerRule(),

        // --------------------------------------------------------- open app ----
        openAppRule(),
    )

    // ------------------------------------------------------------------ rules

    private fun mediaPauseRule() = Rule(
        id = "media.pause",
        groups = setOf("media"),
        match = { it.hasPrefix("paus") || it.words.any { w -> w.startsWith("paus") } },
        build = { ToolCallDraft("media_control", mapOf("action" to "pause")) },
    )

    private fun mediaNextRule() = Rule(
        id = "media.next",
        groups = setOf("media"),
        match = { it.words.any { w -> w == "next" || w == "skip" || w.startsWith("skip") } },
        build = { ToolCallDraft("media_control", mapOf("action" to "next")) },
    )

    private fun mediaPrevRule() = Rule(
        id = "media.previous",
        groups = setOf("media"),
        match = {
            it.words.any { w -> w == "previous" || w == "prev" } ||
                (it.words.any { w -> w == "last" } && it.words.any { w -> w == "song" || w == "track" })
        },
        build = { ToolCallDraft("media_control", mapOf("action" to "previous")) },
    )

    private fun mediaStopRule() = Rule(
        id = "media.stop",
        groups = setOf("media"),
        match = { it.words.any { w -> w == "stop" || w == "halt" } && it.hasAny(MUSIC_CONTEXT) },
        build = { ToolCallDraft("media_control", mapOf("action" to "stop")) },
    )

    private fun mediaShuffleRule() = Rule(
        id = "media.shuffle",
        groups = setOf("media"),
        match = { it.words.any { w -> w == "shuffle" || w == "random" } },
        build = { draft ->
            val on = draft.words.any { it in ON_WORDS } &&
                !draft.words.any { it in OFF_WORDS }
            ToolCallDraft(
                "media_control",
                mapOf("action" to if (on) "shuffle_on" else "shuffle_off"),
            )
        },
    )

    private fun mediaLikeRule() = Rule(
        id = "media.like",
        groups = setOf("media"),
        match = {
            it.words.any { w -> w.startsWith("like") } &&
                it.words.any { w -> w == "song" || w == "track" || w == "this" || w == "it" }
        },
        build = { ToolCallDraft("media_control", mapOf("action" to "like_current")) },
    )

    private fun flashlightRule() = Rule(
        id = "device.flashlight",
        groups = setOf("device"),
        match = { it.words.any { w -> FLASHLIGHT_WORDS.any { f -> w.startsWith(f) } } },
        build = { t ->
            val state = when {
                t.words.any { it in OFF_WORDS } -> "off"
                t.words.any { it in ON_WORDS } -> "on"
                else -> "toggle"
            }
            ToolCallDraft("set_flashlight", mapOf("state" to state))
        },
    )

    private fun brightnessRule() = Rule(
        id = "device.brightness",
        groups = setOf("device"),
        match = { t -> t.words.any { it.startsWith("bright") || it.startsWith("screen") } },
        build = { t ->
            // "screen" alone is too weak a trigger; require a brightness verb too.
            val hasVerb = t.words.any { it in BRIGHTNESS_VERBS }
            if (!hasVerb && !t.words.any { it.startsWith("bright") }) return@Rule null

            val pct = extractPercent(t)
            when {
                pct != null -> ToolCallDraft("set_brightness", mapOf("action" to "set", "percent" to pct))
                t.words.any { it in UP_WORDS } ->
                    ToolCallDraft("set_brightness", mapOf("action" to "up"))
                t.words.any { it in DOWN_WORDS } ->
                    ToolCallDraft("set_brightness", mapOf("action" to "down"))
                t.words.any { it in AUTO_WORDS } ->
                    ToolCallDraft("set_brightness", mapOf("action" to "auto"))
                else -> null
            }
        },
    )

    private fun volumeRule() = Rule(
        id = "device.volume",
        groups = setOf("device"),
        match = { t ->
            // "unmute" on its own names a volume action, so it must reach this rule
            // even without the word "volume".
            t.words.any { it.startsWith("volume") || it == "sound" } ||
                t.words.any { it in UNMUTE_WORDS }
        },
        build = { t ->
            val pct = extractPercent(t)
            when {
                t.words.any { it in MUTE_WORDS } ->
                    ToolCallDraft("set_volume", mapOf("action" to "mute"))
                t.words.any { it in UNMUTE_WORDS } ->
                    ToolCallDraft("set_volume", mapOf("action" to "unmute"))
                pct != null ->
                    ToolCallDraft(
                        "set_volume",
                        mapOf<String, Any?>(
                            "action" to "set",
                            "percent" to pct,
                            "stream" to (volumeStream(t) ?: "media"),
                        ),
                    )
                t.words.any { it in UP_WORDS } ->
                    ToolCallDraft(
                        "set_volume",
                        mapOf<String, Any?>("action" to "up", "stream" to (volumeStream(t) ?: "media")),
                    )
                t.words.any { it in DOWN_WORDS } ->
                    ToolCallDraft(
                        "set_volume",
                        mapOf<String, Any?>("action" to "down", "stream" to (volumeStream(t) ?: "media")),
                    )
                else -> null
            }
        },
    )

    private fun toggleSettingRule() = Rule(
        id = "device.toggle_setting",
        groups = setOf("device"),
        match = { t -> t.words.any { it in SETTING_TOKENS } },
        build = { t ->
            val setting = t.words.firstOrNull { it in SETTING_TOKENS } ?: return@Rule null
            val state = when {
                t.words.any { it in OFF_WORDS } -> "off"
                t.words.any { it in ON_WORDS } -> "on"
                t.words.any { it in TOGGLE_WORDS } -> "toggle"
                else -> "toggle"
            }
            ToolCallDraft(
                "toggle_setting",
                mapOf("setting" to SETTING_TOKENS.getValue(setting), "state" to state),
            )
        },
    )

    private fun alarmRule() = Rule(
        id = "device.alarm",
        groups = setOf("device"),
        match = { t -> t.words.any { it.startsWith("alarm") } },
        build = { t ->
            val time = extractTime(t) ?: return@Rule null
            // Speech recognition usually drops the plural, so accept both forms.
            val repeat = when {
                t.words.any { it in RECUR_EVERY_DAY } -> "daily"
                t.words.any { it in RECUR_WEEKDAYS } -> "weekdays"
                t.words.any { it in RECUR_WEEKENDS } -> "weekends"
                else -> "once"
            }
            val args = mutableMapOf<String, Any?>("time" to time, "repeat" to repeat)
            val label = extractAlarmLabel(t)
            if (label != null) args["label"] = label
            ToolCallDraft("set_alarm", args)
        },
    )

    private fun timerRule() = Rule(
        id = "device.timer",
        groups = setOf("device"),
        match = { t -> t.words.any { it.startsWith("timer") } },
        build = { t ->
            val seconds = extractDurationSeconds(t) ?: return@Rule null
            ToolCallDraft("set_timer", mapOf("seconds" to seconds))
        },
    )

    private fun openAppRule() = Rule(
        id = "device.open_app",
        groups = setOf("device"),
        match = { t ->
            t.words.any { it in OPEN_VERBS } ||
                t.words.any { it.startsWith("open") } ||
                t.words.any { it.startsWith("launch") }
        },
        build = { t ->
            val idx = t.words.indexOfFirst { it in OPEN_VERBS || it.startsWith("open") || it.startsWith("launch") }
            if (idx < 0) return@Rule null
            val name = t.words.drop(idx + 1)
                .filterNot { it in STOP_FOR_APP }
                .joinToString(" ")
            if (name.isEmpty()) return@Rule null
            ToolCallDraft("open_app", mapOf("app_name" to name))
        },
    )

    // ---------------------------------------------------------- slot helpers

    private fun Tokens.hasAny(candidates: Set<String>): Boolean = words.any { it in candidates }

    /** Pulls a percentage from "40", "40%", "forty percent", "to 40". */
    private fun extractPercent(t: Tokens): Int? {
        for ((index, word) in t.words.withIndex()) {
            word.toIntOrNull()?.let { n ->
                if (n in 0..100) return n
            }
            if (word.startsWith("percent")) {
                t.words.getOrNull(index - 1)?.toIntOrNull()
                    ?.takeIf { it in 0..100 }?.let { return it }
            }
        }
        WORD_NUMBERS.forEach { (word, value) ->
            if (t.words.contains(word)) return value
        }
        return null
    }

    /** Extracts "HH:MM", "630" (6:30), or "6 30" / "6 o'clock". */
    private fun extractTime(t: Tokens): String? {
        t.words.forEach { w ->
            TIME_COLON.find(w)?.let { m ->
                val hh = m.groupValues[1].toIntOrNull()
                val mm = m.groupValues[2].toIntOrNull()
                if (hh != null && mm != null && hh in 0..23 && mm in 0..59) {
                    return "%02d:%02d".format(hh, mm)
                }
            }
        }
        // Compact form: 630 -> 06:30, 730 -> 07:30
        t.words.forEach { w ->
            if (w.length == 3 || w.length == 4) {
                val n = w.toIntOrNull() ?: return@forEach
                val mm = n % 100
                val hh = n / 100
                if (n > 0 && mm in 0..59 && hh in 0..23) {
                    return "%02d:%02d".format(hh, mm)
                }
            }
        }
        // Spoken form: "6 30" or "6 o'clock"
        val idx = t.indexOf("oclock") ?: t.indexOfFirst { it.startsWith("oclock") }
        if (idx > 0) {
            val hh = t.words[idx - 1].toIntOrNull()
            if (hh != null && hh in 0..23) return "%02d:00".format(hh)
        }
        for (i in 0 until t.words.size - 1) {
            val hh = t.words[i].toIntOrNull()
            val mm = t.words[i + 1].toIntOrNull()
            if (hh != null && mm != null && hh in 0..23 && mm in 0..59 && mm < 60) {
                return "%02d:%02d".format(hh, mm)
            }
        }
        return null
    }

    /**
     * Alarm label = free text after the last "for", minus time and recurrence
     * tokens.
     *
     * Anchoring on the *last* "for" is what makes "alarm for 7 00 for gym" yield
     * "gym"; anchoring on the first one swallows the time as part of the label.
     */
    private fun extractAlarmLabel(t: Tokens): String? {
        val idx = t.words.lastIndexOf("for")
        if (idx < 0) return null
        val label = t.words.drop(idx + 1)
            .filterNot { it in TIME_NOISE }
            .filterNot { it.any { ch -> ch.isDigit() } }
            .joinToString(" ")
        return label.ifEmpty { null }
    }

    /** "5 minutes" / "90 seconds" / "2 hours" -> seconds. */
    private fun extractDurationSeconds(t: Tokens): Int? {
        for (i in t.words.indices) {
            val n = t.words[i].toIntOrNull() ?: continue
            if (n <= 0) continue
            val unit = t.words.getOrNull(i + 1) ?: continue
            val mult = when {
                unit.startsWith("sec") || unit.startsWith("second") -> 1
                unit.startsWith("min") || unit.startsWith("minute") -> 60
                unit.startsWith("hour") -> 3600
                else -> continue
            }
            val seconds = n * mult
            if (seconds in 1..86_400) return seconds
        }
        // "an hour and a half" is out of scope; a bare small number is a guess.
        return null
    }

    private fun volumeStream(t: Tokens): String? = when {
        t.words.any { it.startsWith("ring") || it.startsWith("call") } -> "ring"
        t.words.any { it.startsWith("alarm") } -> "alarm"
        t.words.any { it.startsWith("notification") } -> "notification"
        else -> null
    }

    companion object {
        private val TIME_COLON = Regex("^(\\d{1,2}):(\\d{2})$")

        private val ON_WORDS = setOf("on", "enable", "enabled", "start", "activate", "open")
        private val OFF_WORDS = setOf("off", "disable", "disabled", "stop", "deactivate", "close")
        private val TOGGLE_WORDS = setOf("toggle", "switch", "flip")
        private val UP_WORDS = setOf("up", "increase", "raise", "higher", "louder", "brighter", "boost", "more")
        private val DOWN_WORDS = setOf("down", "decrease", "lower", "reduce", "quieter", "dim", "dimmer", "less")
        private val AUTO_WORDS = setOf("auto", "automatic", "automatically")
        private val MUTE_WORDS = setOf("mute", "silent", "silence")
        private val UNMUTE_WORDS = setOf("unmute", "unsilence")

        private val BRIGHTNESS_VERBS = setOf(
            "set", "increase", "decrease", "raise", "lower", "change", "make",
            "bright", "darker", "dim",
        )

        private val OPEN_VERBS = setOf("open", "launch", "start", "run")

        private val FLASHLIGHT_WORDS = setOf("flashlight", "torch", "flash")

        /** spoken token -> `toggle_setting` enum value from tools.json */
        private val SETTING_TOKENS: Map<String, String> = mapOf(
            "wifi" to "wifi", "wi" to "wifi",
            "data" to "mobile_data", "mobile" to "mobile_data",
            "bluetooth" to "bluetooth", "bt" to "bluetooth",
            "airplane" to "airplane_mode", "flight" to "airplane_mode",
            "hotspot" to "hotspot", "tethering" to "hotspot",
            "nfc" to "nfc",
            "location" to "location", "gps" to "location",
            "dnd" to "do_not_disturb", "disturb" to "do_not_disturb",
            "disturbance" to "do_not_disturb",
            "rotate" to "auto_rotate", "rotation" to "auto_rotate",
        )

        private val DEVICE_VERBS = setOf("turn", "toggle", "switch", "enable", "disable", "set")
        private val MEDIA_VERBS = setOf("play", "pause", "resume", "skip", "next", "previous", "shuffle")
        private val PERSON_VERBS = setOf("call", "phone", "ring", "text", "message", "send", "whatsapp", "telegram")

        private val MUSIC_CONTEXT = setOf("music", "song", "track", "audio", "playback", "spotify", "it")
        private val STOP_FOR_APP = setOf("please", "app", "the", "for", "me")
        private val TIME_NOISE = setOf(
            "for", "at", "on", "every", "each", "am", "pm", "oclock",
            "hour", "hours", "min", "mins", "minute", "minutes",
            "alarm", "reminder", "remind", "wake",
        )
        private val RECUR_EVERY_DAY = setOf("daily", "everyday", "each", "day")
        private val RECUR_WEEKDAYS = setOf("weekday", "weekdays", "week")
        private val RECUR_WEEKENDS = setOf("weekend", "weekends")

        private val WORD_NUMBERS: Map<String, Int> = mapOf(
            "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40,
            "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
            "full" to 100, "max" to 100, "maximum" to 100, "half" to 50, "minimum" to 0,
        )
    }
}

/**
 * A matched intent before entity resolution and confirmation.
 *
 * Raw string slots ([ToolSchema.RESOLUTION_SLOTS]) are filled from the utterance
 * verbatim — the same invariant the LLM path obeys.
 */
data class ToolCallDraft(
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val groups: Set<String> = emptySet(),
)
