package com.sandman.doppler

import com.sandman.doppler.model.DopplerDeviceState
import com.sandman.doppler.security.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The fabricated-IP class of bug, found by probing a real clock on the LAN.
 *
 * `TokenStore.savedIpAddress` returned a hardcoded `192.168.1.142` when nothing had ever
 * been discovered. It was never anybody's clock, but the UI rendered it as though it had
 * been discovered - and because it was never blank it also made `hasRequiredCredentials`
 * permanently true for the host component, so the "do we have what we need" check could
 * never fail on the very thing it existed to check.
 *
 * The invariant: with nothing stored, the app reports no host rather than inventing one.
 */
@RunWith(RobolectricTestRunner::class)
class NoFabricatedHostTest {

    private lateinit var store: TokenStore

    @Before
    fun setUp() {
        store = TokenStore(org.robolectric.RuntimeEnvironment.getApplication())
        store.clearCredentials()
    }

    @Test
    fun `an undiscovered host reads as blank rather than a plausible ip`() {
        // A default that looks real is the bug: it reads as discovered in the UI and
        // tells the user their clock is at an address nobody ever discovered.
        assertEquals("", store.savedIpAddress)
        assertFalse(store.hasDiscoveredLanHost)
    }

    @Test
    fun `full validation fails while the host is unknown`() {
        // The assertion that would have caught the original bug. With a hardcoded
        // default, `savedIpAddress.isNotBlank()` was true before anything had ever been
        // discovered, so this check passed unconditionally.
        store.savedDsn = "Doppler-10caaebb"
        store.authToken = "some-local-key"
        assertTrue(store.isConfigured)
        assertFalse(
            "a DSN and key must not satisfy full validation without a host",
            store.hasValidConfig()
        )
    }

    @Test
    fun `a real host from the cloud localkey response counts as discovered`() {
        store.savedDsn = "Doppler-10caaebb"
        store.authToken = "some-local-key"
        store.savedIpAddress = "192.168.11.107"
        store.savedPort = 443
        assertTrue(store.hasDiscoveredLanHost)
        assertTrue(store.hasValidConfig())
    }

    @Test
    fun `the unset sentinel is not treated as a discovered host`() {
        store.savedIpAddress = "0.0.0.0"
        assertFalse(
            "0.0.0.0 means discovery failed, not that a host was found",
            store.hasDiscoveredLanHost
        )
    }

    @Test
    fun `device state does not invent a plausible looking ip`() {
        assertEquals("", DopplerDeviceState().ipAddress)
    }

    @Test
    fun `device state default port is the documented protocol port`() {
        // 5443 is a real protocol default, unlike an invented IP. Keeping it is fine;
        // keeping a fake address is not.
        assertEquals(5443, DopplerDeviceState().port)
    }
}
