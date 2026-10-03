package com.sandman.doppler

import com.sandman.doppler.api.LocationResolver
import com.sandman.doppler.model.LocationLookupException
import com.sandman.doppler.model.PlaceCandidate
import com.sandman.doppler.model.formatCoordinate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Plain-text place lookup, the front half of the weather location feature.
 *
 * The clock accepts any `location` string verbatim - verified on an Enter Sandman,
 * which stored `27.1258,-104.9118`, `33980` and `Jimenez, Chihuahua, Mexico`
 * byte-identically. Nothing on the clock validates a postal code, so the whole
 * correctness of the feature lives in this file: resolve the user's text to
 * coordinates here, and only ever send coordinates on.
 *
 * That matters because postal codes are ambiguous between countries. `33980` is
 * simultaneously the postal code for Jimenez, Chihuahua and a valid US ZIP for Port
 * Charlotte, Florida. Passing the raw string straight through would have produced
 * silently wrong weather rather than an error.
 *
 * Provider split is load-bearing, not tidiness: Open-Meteo answers the numeric query
 * `33980` with "Pola de Laviana, Spain" and "Audenge, France", so postal codes must
 * go to Nominatim.
 */
class LocationResolverTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun resolver(): LocationResolver = LocationResolver(
        placeSearchUrl = server.url("/places").toString(),
        postalSearchUrl = server.url("/postals").toString()
    )

    // ---------- routing ----------

    @Test
    fun `a place name is searched on the place-name provider`() = runBlocking {
        server.dispatcher = jsonDispatcher(
            "/places" to OPEN_METEO_JIMENEZ,
            "/postals" to "[]"
        )
        val results = resolver().search("Jimenez, Chihuahua, Mexico")

        assertEquals("/places", server.takeRequest().path?.substringBefore('?'))
        assertEquals(1, results.size)
    }

    @Test
    fun `a numeric query is searched on the postal provider, never the place-name one`() = runBlocking {
        // If this regressed to the place-name provider, the real service answers with
        // Spanish and French towns for this exact query.
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.3}",
            "/postals" to NOMINATIM_33980
        )
        val results = resolver().search("33980")

        assertEquals("/postals", server.takeRequest().path?.substringBefore('?'))
        assertEquals(2, results.size)
    }

    @Test
    fun `classification separates postal codes from place names and coordinates`() {
        val r = resolver()
        assertTrue("a five digit code is a postal query", r.isPostalCodeQuery("33980"))
        assertTrue("spaces and hyphens are tolerated", r.isPostalCodeQuery("339 80"))
        assertTrue("a three digit code qualifies", r.isPostalCodeQuery("123"))
        assertTrue(
            "an alphanumeric postcode is left to the place-name search",
            !r.isPostalCodeQuery("SW1")
        )
        assertTrue(
            "a city name is not a postal query",
            !r.isPostalCodeQuery("Jimenez")
        )
        assertTrue(
            "an already-resolved coordinate pair is not re-geocoded",
            !r.isPostalCodeQuery("27.1258,-104.9118")
        )
    }

    @Test
    fun `a blank query never reaches the network`() = runBlocking {
        server.dispatcher = jsonDispatcher("/places" to OPEN_METEO_JIMENEZ, "/postals" to "[]")
        assertEquals(emptyList<PlaceCandidate>(), resolver().search("   "))
        assertEquals(0, server.requestCount)
    }

    // ---------- place-name parsing ----------

    @Test
    fun `place-name results carry region, country and timezone`() = runBlocking {
        server.dispatcher = jsonDispatcher("/places" to OPEN_METEO_JIMENEZ, "/postals" to "[]")
        val town = resolver().search("Jimenez, Chihuahua, Mexico").single()

        assertEquals("Jimenez", town.name)
        assertEquals("Chihuahua", town.admin)
        assertEquals("Mexico", town.country)
        assertEquals("MX", town.countryCode)
        assertEquals("America/Chihuahua", town.timezone)
        // Nominatim put the town at -104.9127; Open-Meteo's grid differs in the third
        // decimal, which is ~100m and irrelevant to a weather grid.
        assertEquals(27.11667, town.latitude, 1e-5)
        assertEquals(-104.95, town.longitude, 1e-5)
    }

    @Test
    fun `a response with no results key yields no candidates instead of failing`() = runBlocking {
        // Open-Meteo omits `results` entirely rather than sending an empty array.
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.184}",
            "/postals" to "[]"
        )
        assertEquals(emptyList<PlaceCandidate>(), resolver().search("Nowhere-at-all"))
    }

    @Test
    fun `a result missing coordinates is dropped rather than sent as a null location`() = runBlocking {
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"results\":[" +
                "{\"name\":\"NoCoords\",\"admin1\":\"Chihuahua\"}," +
                "{\"name\":\"Fine\",\"latitude\":20.0,\"longitude\":-100.0}]}",
            "/postals" to "[]"
        )
        val results = resolver().search("Mixed")

        assertEquals(listOf("Fine"), results.map { it.name })
    }

    // ---------- postal parsing and the ambiguity that motivates it ----------

    @Test
    fun `a colliding postal code returns both countries so the user chooses`() = runBlocking {
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.3}",
            "/postals" to NOMINATIM_33980
        )
        val results = resolver().search("33980")

        // Both matches must survive. Collapsing them to one is the original bug.
        assertEquals(listOf("MX", "US"), results.map { it.countryCode })
        assertEquals(
            "Jimenez",
            results.first { it.countryCode == "MX" }.name
        )
        assertEquals("Port Charlotte", results.first { it.countryCode == "US" }.name)
        assertEquals("Chihuahua", results.first { it.countryCode == "MX" }.admin)
    }

    @Test
    fun `postal coordinates are parsed from the string fields Nominatim returns`() = runBlocking {
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.3}",
            "/postals" to NOMINATIM_33980
        )
        val mexican = resolver().search("33980").first { it.countryCode == "MX" }

        // Nominatim sends lat/lon as JSON strings, unlike Open-Meteo's numbers.
        assertEquals(27.1254964, mexican.latitude, 1e-7)
        assertEquals(-104.9126760, mexican.longitude, 1e-7)
        assertEquals("33980", mexican.postalCode)
    }

    @Test
    fun `an unparseable coordinate is dropped rather than becoming a zero location`() = runBlocking {
        // Zero,zero is a real coordinate in the Gulf of Guinea. Silently substituting
        // it for a bad parse would point the clock at the ocean.
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.3}",
            "/postals" to """[{"name":"Bad","lat":"not-a-number","lon":"0",
                              "address":{"country":"Mexico","country_code":"mx"}}]"""
        )
        assertEquals(emptyList<PlaceCandidate>(), resolver().search("99999"))
    }

    // ---------- failures ----------

    @Test
    fun `a server error surfaces as a lookup failure`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(500).setBody("upstream is unwell")
        }
        try {
            runBlocking { resolver().search("Jimenez") }
            fail("expected a LocationLookupException")
        } catch (e: LocationLookupException) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `an unparseable body surfaces as a lookup failure rather than an empty list`() {
        server.dispatcher = jsonDispatcher("/places" to "<html>nope</html>", "/postals" to "[]")
        try {
            runBlocking { resolver().search("Jimenez") }
            fail("expected a LocationLookupException")
        } catch (e: LocationLookupException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `an empty list is not reported as an error`() = runBlocking {
        // Open-Meteo answers with an object that has no `results` key, not an array.
        server.dispatcher = jsonDispatcher(
            "/places" to "{\"generationtime_ms\":0.184}",
            "/postals" to "[]"
        )
        assertEquals(emptyList<PlaceCandidate>(), resolver().search("Jimenez"))
    }

    @Test
    fun `an ambiguous place name returns every candidate for the user to choose between`() = runBlocking {
        // Collapsing these to one arbitrary pick is how a parent ends up with weather for
        // Spain. Three countries come back here; all three must survive.
        server.dispatcher = jsonDispatcher(
            "/places" to OPEN_METEO_MULTIPLE,
            "/postals" to "[]"
        )
        val results = resolver().search("Jimenez")

        assertEquals(listOf("MX", "ES", "PH"), results.map { it.countryCode })
        assertEquals("Coahuila", results.first().admin)
        assertEquals("Europe/Madrid", results.first { it.countryCode == "ES" }.timezone)
    }

    // ---------- what actually gets written to the clock ----------

    @Test
    fun `the clock is sent a comma separated pair with no space`() = runBlocking {
        server.dispatcher = jsonDispatcher("/places" to OPEN_METEO_JIMENEZ, "/postals" to "[]")
        val wire = resolver().search("Jimenez, Chihuahua, Mexico").single().coordinateString

        assertEquals("27.1167,-104.95", wire)
        assertTrue("no space is sent", !wire.contains(' '))
        assertEquals(2, wire.split(',').size)
    }

    @Test
    fun `coordinate formatting strips trailing zeros and normalises negative zero`() {
        assertEquals("27.1258", formatCoordinate(27.1258))
        assertEquals("-104.9118", formatCoordinate(-104.9118))
        assertEquals("27.1167", formatCoordinate(27.11667))
        assertEquals("20", formatCoordinate(20.0))
        assertEquals("20.5", formatCoordinate(20.5))
        assertEquals("0", formatCoordinate(0.0))
        assertEquals("-0.00001".let { formatCoordinate(-0.00001) }, "0")
        assertEquals("180", formatCoordinate(180.0))
        assertEquals("-180", formatCoordinate(-180.0))
    }

    @Test
    fun `a lat lon pair round trips back to the same coordinates`() = runBlocking {
        server.dispatcher = jsonDispatcher("/places" to OPEN_METEO_JIMENEZ, "/postals" to "[]")
        val place = resolver().search("Jimenez, Chihuahua, Mexico").single()
        val parts = place.coordinateString.split(',')

        assertEquals(place.latitude, parts[0].toDouble(), 1e-4)
        assertEquals(place.longitude, parts[1].toDouble(), 1e-4)
    }

    @Test
    fun `a missing region does not leave a dangling separator in the label`() {
        val bare = PlaceCandidate(name = "Nowhere", latitude = 1.0, longitude = 2.0)
        // regionLine is the region alone. It must not borrow the name, or a place with
        // no region would render its name twice in the picker row.
        assertEquals("", bare.regionLine)
        assertEquals("Nowhere", bare.displayLabel)

        val partial = PlaceCandidate(name = "Somewhere", latitude = 1.0, longitude = 2.0, admin = "Region")
        assertEquals("Region", partial.regionLine)
        assertEquals("Somewhere - Region", partial.displayLabel)
        assertNull(partial.country)
    }

    private fun jsonDispatcher(vararg routes: Pair<String, String>): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                val body = routes.firstOrNull { it.first == path }?.second
                    ?: return MockResponse().setResponseCode(404).setBody("no route for $path")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }

    private companion object {
        val OPEN_METEO_JIMENEZ = """
            {"generationtime_ms":0.31,"results":[
              {"id":2957734,"name":"Jimenez","latitude":27.11667,"longitude":-104.95,
               "feature_code":"PPL","country_code":"MX","admin1":"Chihuahua",
               "timezone":"America/Chihuahua","country":"Mexico"}
            ]}
        """.trimIndent()

        /**
         * What the real service returns for a bare "Jimenez": eight candidates across
         * four countries. The picker has to show all of them.
         */
        val OPEN_METEO_MULTIPLE = """
            {"generationtime_ms":0.44,"results":[
              {"id":1,"name":"Jimenez","latitude":29.06975,"longitude":-100.67895,
               "country_code":"MX","admin1":"Coahuila","timezone":"America/Monterrey","country":"Mexico"},
              {"id":2,"name":"Jimenez de Jamuz","latitude":42.26569,"longitude":-5.92888,
               "country_code":"ES","admin1":"Castille and Leon","timezone":"Europe/Madrid","country":"Spain"},
              {"id":3,"name":"Jimenez","latitude":8.3365,"longitude":123.8383,
               "country_code":"PH","admin1":"Northern Mindanao","timezone":"Asia/Manila","country":"Philippines"}
            ]}
        """.trimIndent()

        val NOMINATIM_33980 = """
            [
              {"name":"Jimenez","lat":"27.1254964","lon":"-104.9126760","type":"postcode",
               "display_name":"33980, Jimenez, Chihuahua, Mexico",
               "address":{"postcode":"33980","state":"Chihuahua","country":"Mexico","country_code":"mx"}},
              {"name":"Port Charlotte","lat":"26.9860460","lon":"-82.0623926","type":"postcode",
               "display_name":"33980, Port Charlotte, Charlotte County, Florida, United States",
               "address":{"postcode":"33980","state":"Florida","country":"United States","country_code":"us"}}
            ]
        """.trimIndent()
    }
}