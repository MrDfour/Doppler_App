package com.sandman.doppler

import com.sandman.doppler.model.DopplerColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hex input for the custom colour picker.
 *
 * The clock accepts any `[r,g,b]` - `PUT hardware/high-display-color {"color":[10,20,30]}` was
 * verified working on hardware - so the five presets were the only thing limiting the app.
 * The picker is the UI half of that; this is the parsing half, and parsing user input is where
 * it can quietly go wrong.
 *
 * The bug this guards: [DopplerColor.fromHex] is lenient, coercing anything unparseable to
 * black. Wired to a text field it means a typo turns the clock's display off with no error
 * shown, and `#FFF` becomes a dark teal rather than white.
 */
class ColorHexParsingTest {

    @Test
    fun `a six digit hex parses to the same channels`() {
        assertEquals(DopplerColor(0x22, 0xD3, 0xEE), DopplerColor.fromHexOrNull("#22D3EE"))
        assertEquals(DopplerColor(0x22, 0xD3, 0xEE), DopplerColor.fromHexOrNull("22D3EE"))
    }

    @Test
    fun `surrounding whitespace and a lowercase prefix are tolerated`() {
        // Copy-paste from a colour picker routinely brings both.
        assertEquals(DopplerColor(0x10, 0x20, 0x30), DopplerColor.fromHexOrNull("  #102030 "))
        assertEquals(DopplerColor(0x10, 0x20, 0x30), DopplerColor.fromHexOrNull("  102030 "))
    }

    @Test
    fun `the three digit shorthand expands each nibble`() {
        // The case the lenient parser gets wrong: "#FFF" parses there as 0x000FFF.
        assertEquals(DopplerColor(255, 255, 255), DopplerColor.fromHexOrNull("#FFF"))
        assertEquals(DopplerColor(0xAA, 0xBB, 0xCC), DopplerColor.fromHexOrNull("#ABC"))
    }

    @Test
    fun `lowercase and uppercase hex agree`() {
        assertEquals(DopplerColor.fromHexOrNull("#aabbcc"), DopplerColor.fromHexOrNull("#AABBCC"))
    }

    @Test
    fun `black and white round trip through the picker's own formatting`() {
        // toHex() is what pre-fills the field, so the two must be inverses.
        assertEquals(DopplerColor(0, 0, 0), DopplerColor.fromHexOrNull(DopplerColor(0, 0, 0).toHex()))
        assertEquals(DopplerColor(255, 255, 255), DopplerColor.fromHexOrNull(DopplerColor(255, 255, 255).toHex()))
    }

    @Test
    fun `text that is not a colour is rejected rather than becoming black`() {
        // Each of these would silently yield black through the lenient parser.
        assertNull(DopplerColor.fromHexOrNull(""))
        assertNull(DopplerColor.fromHexOrNull("#"))
        assertNull(DopplerColor.fromHexOrNull("nope"))
        assertNull(DopplerColor.fromHexOrNull("#12G45Z"))
        assertNull(DopplerColor.fromHexOrNull("12345"))
        assertNull(DopplerColor.fromHexOrNull("1234567"))
        assertNull(DopplerColor.fromHexOrNull("#22 D3EE"))
    }

    @Test
    fun `a leading sign is not a colour`() {
        // The subtle hole. Kotlin's toLongOrNull(radix) delegates to Long.parseLong, which
        // accepts a leading '+' and '-'. Without an explicit digit check, "+123456" parses as
        // 0x123456 and "-123456" parses as a negative number whose shifts produce an arbitrary
        // colour - so validation is not redundant with the parse, it is what stops a signed
        // number from being read as a colour.
        assertNull(DopplerColor.fromHexOrNull("+123456"))
        assertNull(DopplerColor.fromHexOrNull("-123456"))
        assertNull(DopplerColor.fromHexOrNull("#+12345"))
        assertNull(DopplerColor.fromHexOrNull("#-12345"))
    }

    @Test
    fun `the lenient parser is the thing this avoids`() {
        // Pins the difference, so the strict parser is not quietly replaced by the lenient one
        // for convenience: this one turns a typo into black with no error.
        assertEquals(DopplerColor(0, 0, 0), DopplerColor.fromHex("nope"))
        assertNull(DopplerColor.fromHexOrNull("nope"))
    }

    @Test
    fun `every preset round trips so the picker never misreports the current colour`() {
        listOf(
            DopplerColor.CYAN,
            DopplerColor.AMBER,
            DopplerColor.DEEP_RED,
            DopplerColor.EMERALD,
            DopplerColor.PURPLE
        ).forEach { preset ->
            assertEquals(preset, DopplerColor.fromHexOrNull(preset.toHex()))
        }
    }
}