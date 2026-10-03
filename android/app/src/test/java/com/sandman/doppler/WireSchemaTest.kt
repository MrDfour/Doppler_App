package com.sandman.doppler

import com.sandman.doppler.model.DopplerDayMode
import com.sandman.doppler.model.DopplerHighToLowTransition
import com.sandman.doppler.model.DopplerLightSensor
import com.sandman.doppler.model.DopplerLowToHighTransition
import com.sandman.doppler.model.DopplerSoundPreset
import com.sandman.doppler.model.DopplerSoundPresetMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire schemas, taken verbatim from a live clock (DSN Doppler-10caaebb) rather than from
 * `STANDALONE_IMPLEMENTATION_PLAN.md`, which disagrees with the device on every field here.
 *
 * This class of bug is invisible: `DopplerLocalApi` decodes with `ignoreUnknownKeys = true`,
 * so a model whose property name does not match the wire field does not throw - it silently
 * deserialises to the property's **default**. Six polled values were therefore permanently
 * showing fabricated defaults (light sensor stuck at 0, both ambient thresholds stuck at their
 * defaults, sound preset stuck at "Flat") while looking perfectly plausible.
 *
 * Each test asserts the round-trip against the real payload, so a rename that breaks the wire
 * name fails here instead of silently reverting to a default.
 */
class WireSchemaTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `light sensor reads the real sensor field`() {
        // Live: GET hardware/light-sensor -> {"sensor":1414}
        val parsed = json.decodeFromString<DopplerLightSensor>("""{"sensor":1414}""")
        assertEquals(1414, parsed.lightSensor)
    }

    @Test
    fun `day mode reads the real isDayMode field`() {
        // Live: GET hardware/day-mode -> {"isDayMode":true}
        // Asserted `false` on purpose: the model default is `true`, so asserting the value
        // that equals the default would pass even with the wire name reverted.
        assertTrue(!json.decodeFromString<DopplerDayMode>("""{"isDayMode":false}""").dayMode)
        assertTrue(json.decodeFromString<DopplerDayMode>("""{"isDayMode":true}""").dayMode)
    }

    @Test
    fun `sound preset reads the real preset field`() {
        // Live: GET hardware/sound-preset -> {"preset":"PRESET4"}
        // Docs claim "Flat"/"Rock"/"Pop"; the device reports PRESETn.
        assertEquals(
            "PRESET4",
            json.decodeFromString<DopplerSoundPreset>("""{"preset":"PRESET4"}""").soundPreset
        )
    }

    @Test
    fun `sound preset mode reads a number not a string`() {
        // Live: GET hardware/sound-preset-mode -> {"presetmode":1}
        // Docs claim {"soundPresetMode":"auto"|"manual"}. Asserted away from the default so
        // a reverted wire name fails instead of returning 1 and looking correct.
        assertEquals(
            0,
            json.decodeFromString<DopplerSoundPresetMode>("""{"presetmode":0}""").soundPresetMode
        )
        assertEquals(
            7,
            json.decodeFromString<DopplerSoundPresetMode>("""{"presetmode":7}""").soundPresetMode
        )
    }

    @Test
    fun `both ambient thresholds read the bare transition field`() {
        // Live: GET hardware/high-to-low-transition -> {"transition":230}
        // Live: GET hardware/low-to-high-transition  -> {"transition":270}
        // Same key on both endpoints; only the URL distinguishes them.
        // Values deliberately differ from the model defaults (230/270) so that reverting
        // the @SerialName makes these fail rather than silently return the default.
        assertEquals(
            11,
            json.decodeFromString<DopplerHighToLowTransition>("""{"transition":11}""")
                .highToLowTransition
        )
        assertEquals(
            99,
            json.decodeFromString<DopplerLowToHighTransition>("""{"transition":99}""")
                .lowToHighTransition
        )
    }

    /**
     * The write side matters just as much. `PUT hardware/high-to-low-transition` with the old
     * `{"highToLowTransition":...}` body returns HTTP 500 and changes nothing on the device;
     * with `{"transition":...}` it returns 200 and applies. Both were verified live.
     */
    @Test
    fun `threshold writes go out under the bare transition key`() {
        assertEquals(
            """{"transition":42}""",
            json.encodeToString(DopplerHighToLowTransition(42))
        )
        assertEquals(
            """{"transition":42}""",
            json.encodeToString(DopplerLowToHighTransition(42))
        )
    }

    @Test
    fun `a payload the model does not recognise still parses rather than throwing`() {
        // The reason these bugs were invisible: an unmatched field degrades to the default
        // instead of failing. Pin that behaviour so a future mismatch cannot crash a poll.
        val parsed = json.decodeFromString<DopplerLightSensor>("""{"totallyDifferent":99}""")
        assertEquals(0, parsed.lightSensor)
    }
}