package dev.crewx.voiceassistant.core.fastpath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FastPathTest {

    private val fastPath = FastPath(
        relationTerms = setOf("wife", "husband", "brother", "mom", "dad", "son", "daughter"),
        appLabels = setOf("spotify", "whatsapp", "telegram", "maps", "camera", "chrome"),
    )

    private fun call(utterance: String): ToolCallDraft {
        val matched = fastPath.match(utterance)
        assertNotNull(matched, "expected a fast-path match for: $utterance")
        return matched!!
    }

    private fun arg(utterance: String, key: String): Any? = call(utterance).args[key]

    // ---------------------------------------------------------- flashlight --

    @Test
    fun `flashlight explicit on and off`() {
        assertEquals("on", arg("turn on the flashlight", "state"))
        assertEquals("off", arg("turn off the flashlight", "state"))
    }

    @Test
    fun `flashlight bare is a toggle`() {
        val call = call("flashlight")
        assertEquals("set_flashlight", call.tool)
        assertEquals("toggle", call.args["state"])
    }

    @Test
    fun `torch and flashlight are the same intent`() {
        assertEquals("on", arg("enable torch", "state"))
        assertEquals("off", arg("switch off the torch", "state"))
    }

    @Test
    fun `flashlight capitalisation is irrelevant`() {
        assertEquals("on", arg("TURN ON FLASHLIGHT", "state"))
    }

    // ----------------------------------------------------------- brightness --

    @Test
    fun `brightness absolute and relative`() {
        assertEquals("set", arg("set brightness to 40", "action"))
        assertEquals(40, arg("set brightness to 40", "percent"))
        assertEquals("up", arg("increase brightness", "action"))
        assertEquals("down", arg("decrease brightness", "action"))
    }

    @Test
    fun `brightness from spoken words and percent sign`() {
        assertEquals(30, arg("set brightness to thirty percent", "percent"))
        assertEquals(70, arg("brightness 70%", "percent"))
    }

    @Test
    fun `brightness auto`() {
        assertEquals("auto", arg("set brightness to auto", "action"))
    }

    @Test
    fun `out of range brightness falls through instead of clamping silently`() {
        // 150 is not a legal percent; guessing would be worse than routing to the LLM.
        assertNull(fastPath.match("set brightness to 150"))
    }

    @Test
    fun `brighter raises brightness and louder raises volume`() {
        assertEquals("up", arg("turn it brighter", "action"))
        assertEquals("set_brightness", call("turn it brighter").tool)
        // "louder" alone names no subsystem, so it is genuinely ambiguous: falling
        // through to the LLM beats picking one and being wrong.
        assertNull(fastPath.match("turn it louder"))
    }

    // --------------------------------------------------------------- volume --

    @Test
    fun `volume absolute relative and mute`() {
        assertEquals("set", arg("set volume to 20", "action"))
        assertEquals(20, arg("set volume to 20", "percent"))
        assertEquals("up", arg("volume up", "action"))
        assertEquals("down", arg("turn the volume down", "action"))
        assertEquals("mute", arg("mute the volume", "action"))
        assertEquals("unmute", arg("unmute", "action"))
    }

    @Test
    fun `volume stream is inferred when named`() {
        assertEquals("ring", arg("set ring volume to 30", "stream"))
        assertEquals("alarm", arg("set alarm volume to 30", "stream"))
        assertEquals("notification", arg("set notification volume to 30", "stream"))
    }

    @Test
    fun `volume stream defaults to media`() {
        assertEquals("media", arg("set volume to 55", "stream"))
    }

    // ------------------------------------------------------------- settings --

    @Test
    fun `wifi bluetooth and airplane mode`() {
        assertEquals("wifi", arg("turn on wifi", "setting"))
        assertEquals("on", arg("turn on wifi", "state"))
        assertEquals("off", arg("turn off bluetooth", "state"))
        assertEquals("bluetooth", arg("toggle bluetooth", "setting"))
        assertEquals("toggle", arg("toggle bluetooth", "state"))
        assertEquals("airplane_mode", arg("enable airplane mode", "setting"))
    }

    @Test
    fun `data maps to mobile_data not data`() {
        assertEquals("mobile_data", arg("turn off mobile data", "setting"))
    }

    @Test
    fun `do not disturb is a single setting not three tokens`() {
        assertEquals("do_not_disturb", arg("turn on dnd", "setting"))
    }

    // --------------------------------------------------------- alarm/timer --

    @Test
    fun `alarm with colon time`() {
        assertEquals("07:30", arg("set an alarm for 07:30", "time"))
        assertEquals("once", arg("set an alarm for 07:30", "repeat"))
    }

    @Test
    fun `alarm with spoken and compact times`() {
        assertEquals("06:30", arg("set an alarm for 6 30", "time"))
        assertEquals("19:05", arg("alarm at 1905", "time"))
    }

    @Test
    fun `alarm recurrence`() {
        assertEquals("daily", arg("set a daily alarm for 7 00", "repeat"))
        assertEquals("weekdays", arg("set a weekday alarm for 7 00", "repeat"))
        assertEquals("weekends", arg("set a weekend alarm for 9 00", "repeat"))
    }

    @Test
    fun `alarm label is carried as raw text`() {
        assertEquals("gym", arg("set an alarm for 7 00 for gym", "label"))
    }

    @Test
    fun `alarm without a parsable time falls through`() {
        assertNull(fastPath.match("set an alarm"))
    }

    @Test
    fun `timer duration in minutes seconds and hours`() {
        assertEquals(300, arg("set a timer for 5 minutes", "seconds"))
        assertEquals(45, arg("set a timer for 45 seconds", "seconds"))
        assertEquals(7200, arg("set a 2 hour timer", "seconds"))
    }

    // ----------------------------------------------------------- open app ----

    @Test
    fun `open app name is raw text not a package name`() {
        assertEquals("spotify", arg("open spotify", "app_name"))
        assertEquals("whatsapp", arg("launch whatsapp please", "app_name"))
        assertEquals("google maps", arg("open google maps", "app_name"))
    }

    @Test
    fun `open with no target falls through`() {
        assertNull(fastPath.match("open"))
    }

    // ---------------------------------------------------------------- media --

    @Test
    fun `media transport`() {
        assertEquals("pause", arg("pause the music", "action"))
        assertEquals("next", arg("next track", "action"))
        assertEquals("previous", arg("previous song", "action"))
        assertEquals("stop", arg("stop the music", "action"))
        assertEquals("like_current", arg("like this song", "action"))
    }

    @Test
    fun `shuffle on and off`() {
        assertEquals("shuffle_on", arg("turn on shuffle", "action"))
        assertEquals("shuffle_off", arg("turn off shuffle", "action"))
    }

    @Test
    fun `stop without media context does not hijack the intent`() {
        // "stop" alone is ambiguous; other verbs should still reach their own rules.
        assertEquals("off", arg("turn off the flashlight", "state"))
    }

    // ---------------------------------------------------------- group guess --

    @Test
    fun `group guessing routes comms for person references`() {
        assertTrue("comms" in fastPath.guessGroups("call my wife"))
        assertTrue("comms" in fastPath.guessGroups("text rohan"))
    }

    @Test
    fun `person intents are never resolved on the fast path`() {
        // The invariant that protects against misdials: the fast path detects that
        // a person is involved but refuses to guess who.
        assertNull(fastPath.match("call my wife"))
        assertNull(fastPath.match("text rohan about the meeting"))
    }

    @Test
    fun `group guessing covers device and media`() {
        assertTrue("device" in fastPath.guessGroups("turn on wifi"))
        assertTrue("media" in fastPath.guessGroups("play some music"))
        assertTrue("device" in fastPath.guessGroups("open spotify"))
    }

    @Test
    fun `blank and garbage input returns null rather than throwing`() {
        assertNull(fastPath.match(""))
        assertNull(fastPath.match("   "))
        assertNull(fastPath.match("!!! ???"))
    }
}
