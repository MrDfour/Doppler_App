package com.sandman.doppler

import com.sandman.doppler.api.DopplerException
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.model.OverrideVerdict
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the custom-display override support probe.
 *
 * The probe exists to answer one question: when an override does nothing on the clock,
 * is that because the firmware has no route for it, or because we sent a bad payload?
 * Only the HTTP status can tell those apart, so these tests pin the status -> verdict
 * classification and the guarantee that one failing probe never masks the other.
 */
class OverrideSupportProbeTest {

    private val textPath = "hardware/display-text"
    private val digitsPath = "hardware/small-display-digits"

    private class ProbeApi(
        var textError: Exception? = null,
        var digitsError: Exception? = null
    ) : com.sandman.doppler.api.DopplerLocalApi(host = "192.168.1.100", port = 5443, dsn = "Doppler-12345678") {
        var textCalls = 0
        var digitsCalls = 0
        var lastText: String? = null
        var lastDigits: Int? = null

        override suspend fun displayText(
            text: String,
            duration: Int,
            speed: Int,
            color: DopplerColor
        ): String {
            textCalls++
            lastText = text
            textError?.let { throw it }
            return ""
        }

        override suspend fun displaySmallDigits(
            number: Int,
            duration: Int,
            color: DopplerColor
        ): String {
            digitsCalls++
            lastDigits = number
            digitsError?.let { throw it }
            return ""
        }
    }

    private fun protocolError(code: Int) =
        DopplerException.ProtocolException("failed: HTTP $code", httpCode = code)

    @Test
    fun `a 2xx is reported as ACCEPTED_UNVERIFIED not SUPPORTED`() = runTest {
        val repository = DopplerRepository(ProbeApi())

        val results = repository.probeOverrideSupport()

        assertEquals(2, results.size)
        // Regression guard: these overrides have no GET counterpart, so a 2xx cannot
        // prove the clock rendered anything. Calling it SUPPORTED overclaimed.
        assertTrue(results.all { it.verdict == OverrideVerdict.ACCEPTED_UNVERIFIED })
        // Success path does not surface a specific status, only the 2xx range.
        assertTrue(results.all { it.httpCode == null })
    }

    @Test
    fun `the unverified verdict tells the user to check the clock`() = runTest {
        val repository = DopplerRepository(ProbeApi())

        val result = repository.probeOverrideSupport().first()

        assertTrue(result.detail.contains("NOT", ignoreCase = true))
        assertTrue(result.detail.contains("clock", ignoreCase = true))
    }

    @Test
    fun `probe reports NOT_SUPPORTED when the clock has no route for the override`() = runTest {
        val api = ProbeApi(
            textError = protocolError(404),
            digitsError = protocolError(404)
        )
        val repository = DopplerRepository(api)

        val results = repository.probeOverrideSupport()

        assertTrue(results.all { it.verdict == OverrideVerdict.NOT_SUPPORTED })
        assertTrue(results.all { it.httpCode == 404 })
    }

    @Test
    fun `probe reports REJECTED when the route exists but refuses the payload`() = runTest {
        val repository = DopplerRepository(
            ProbeApi(
                textError = protocolError(400),
                digitsError = protocolError(422)
            )
        )

        val results = repository.probeOverrideSupport()

        // This is the verdict that must NOT be reported as a firmware gap: the clock
        // routed the request and rejected us, so the fix is on the payload side.
        assertTrue(results.all { it.verdict == OverrideVerdict.REJECTED })
        assertEquals(listOf(400, 422), results.map { it.httpCode })
    }

    @Test
    fun `probe reports UNKNOWN for an unhandled status`() = runTest {
        val repository = DopplerRepository(
            ProbeApi(
                textError = protocolError(500),
                digitsError = protocolError(503)
            )
        )

        val results = repository.probeOverrideSupport()

        assertTrue(results.all { it.verdict == OverrideVerdict.UNKNOWN })
        assertEquals(listOf(500, 503), results.map { it.httpCode })
    }

    @Test
    fun `probe reports UNKNOWN and a null code on a transport failure`() = runTest {
        val repository = DopplerRepository(
            ProbeApi(
                textError = DopplerException.LocalConnectionException("wifi down"),
                digitsError = DopplerException.LocalConnectionException("wifi down")
            )
        )

        val results = repository.probeOverrideSupport()

        assertTrue(results.all { it.verdict == OverrideVerdict.UNKNOWN })
        assertTrue(results.all { it.httpCode == null })
    }

    @Test
    fun `a failing probe does not prevent the other override from being probed`() = runTest {
        val api = ProbeApi(textError = protocolError(404))
        val repository = DopplerRepository(api)

        val results = repository.probeOverrideSupport()

        assertEquals(OverrideVerdict.NOT_SUPPORTED, results[0].verdict)
        assertEquals(OverrideVerdict.ACCEPTED_UNVERIFIED, results[1].verdict)
        // Both requests were actually attempted, in order.
        assertEquals(1, api.textCalls)
        assertEquals(1, api.digitsCalls)
    }

    @Test
    fun `probe labels and paths match the two documented overrides`() = runTest {
        val repository = DopplerRepository(ProbeApi())

        val results = repository.probeOverrideSupport()

        assertEquals(textPath, results[0].path)
        assertEquals(digitsPath, results[1].path)
        assertEquals("Scrolling Text Override", results[0].label)
        assertEquals("Mini Display Override", results[1].label)
    }

    @Test
    fun `probe sends a visible payload so a supported verdict is observable on the clock`() = runTest {
        val api = ProbeApi()
        val repository = DopplerRepository(api)

        repository.probeOverrideSupport()

        assertEquals("PROBE", api.lastText)
        assertNotNull(api.lastDigits)
        assertEquals(0, api.lastDigits)
    }

    @Test
    fun `probe surfaces the failure detail for the diagnostics log`() = runTest {
        val repository = DopplerRepository(ProbeApi(textError = protocolError(405)))

        val result = repository.probeOverrideSupport().first()

        assertTrue(result.detail.contains("405"))
    }

    @Test
    fun `protocol exception keeps a null code when the caller supplies none`() {
        val legacy = DopplerException.ProtocolException("no status here")

        assertNull(legacy.httpCode)
    }
}
