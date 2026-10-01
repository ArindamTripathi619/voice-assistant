package dev.crewx.voiceassistant

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.crewx.voiceassistant.core.fastpath.FastPath
import dev.crewx.voiceassistant.core.tools.ToolsLoader

/**
 * Temporary host for on-device verification of the pure-JVM core.
 *
 * Deliberately not the final assistant UI: it exists so the routing pipeline can
 * be exercised on hardware before any microphone, TTS, or Compose work is built
 * on top of a pipeline that has not been observed working.
 */
class MainActivity : Activity() {

    private lateinit var transcript: TextView
    private lateinit var result: TextView

    private val fastPath by lazy {
        FastPath(
            relationTerms = setOf("wife", "husband", "brother", "mom", "dad"),
            appLabels = setOf("spotify", "whatsapp", "telegram", "maps"),
        )
    }

    private val tools by lazy {
        val file = assets.open("tools.json").use { it.readBytes().decodeToString() }
        ToolsLoader.load(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transcript = TextView(this)
        result = TextView(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(transcript)
            addView(result)
        }
        setContentView(layout)

        val sample = "turn on the flashlight"
        transcript.text = sample
        val draft = fastPath.match(sample)
        val selection = tools.selectFor(fastPath.guessGroups(sample))
        result.text = buildString {
            append("tool: ").append(draft?.tool ?: "(no fast match)").append('\n')
            append("args: ").append(draft?.args ?: emptyMap<String, Any?>()).append('\n')
            append("injected groups: ").append(selection.groups).append('\n')
            append("injected tools: ").append(selection.toolNames.size)
            append(" / ").append(tools.all.size).append('\n')
            append("schema chars: ").append(selection.schemaCharCount())
        }
    }
}
