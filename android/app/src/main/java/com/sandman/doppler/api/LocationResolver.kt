package com.sandman.doppler.api

import com.sandman.doppler.model.LocationLookupException
import com.sandman.doppler.model.PlaceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
private data class OpenMeteoPlace(
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val country: String? = null,
    val country_code: String? = null,
    val admin1: String? = null,
    val timezone: String? = null
)

/** Open-Meteo omits `results` entirely when nothing matched, rather than sending `[]`. */
@Serializable
private data class OpenMeteoResponse(val results: List<OpenMeteoPlace>? = null)

@Serializable
private data class NominatimAddress(
    val state: String? = null,
    val country: String? = null,
    val country_code: String? = null,
    val postcode: String? = null
)

@Serializable
private data class NominatimPlace(
    val name: String? = null,
    val lat: String? = null,
    val lon: String? = null,
    val display_name: String? = null,
    val address: NominatimAddress? = null
)

/**
 * Turns whatever the user types into coordinates.
 *
 * Two providers, chosen by what was typed:
 *
 * - **Place names** go to Open-Meteo's geocoding API. No key, no signup, global.
 * - **Postal codes** go to OpenStreetMap's Nominatim, because Open-Meteo mangles
 *   numeric queries - it answered `33980` with "Pola de Laviana, Spain" and
 *   "Audenge, France". Nominatim resolves it correctly *and* returns every country
 *   that claims the code, which is exactly the disambiguation the user needs: it
 *   returns Jimenez, Chihuahua, Mexico and Port Charlotte, Florida as two separate
 *   candidates rather than silently picking one.
 *
 * The result is always a list, never a single guess. A bare "Jimenez" matches eight
 * places across Mexico, Spain, the Philippines and Costa Rica, so the caller has to
 * show the choice.
 *
 * These are third-party internet calls to hosts unrelated to the clock. They
 * deliberately do **not** pass through the clock's serialized request gate - that gate
 * exists because the hardware cannot take concurrent requests, and nothing here
 * touches the hardware.
 *
 * @param userAgent Nominatim's usage policy requires an identifying User-Agent.
 */
class LocationResolver(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val placeSearchUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
    private val postalSearchUrl: String = "https://nominatim.openstreetmap.org/search",
    private val userAgent: String = "SandmanDopplerApp/1.0",
    private val maxResults: Int = 10
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Resolve free-text input to candidate places.
     *
     * @return zero or more candidates, best match first. Empty means "nothing matched",
     *   which is a normal outcome and not an error.
     * @throws LocationLookupException when the provider could not be reached.
     */
    suspend fun search(rawQuery: String): List<PlaceCandidate> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return@withContext emptyList()

        if (isPostalCodeQuery(query)) searchPostalCode(query) else searchPlaceName(query)
    }

    /**
     * True when the input is plausibly a postal code: digits, spaces and hyphens only.
     *
     * Alphanumeric codes (UK "SW1", Canada "G2J") deliberately fall through to the
     * place-name search, which handles them correctly.
     */
    internal fun isPostalCodeQuery(query: String): Boolean {
        val compact = query.filterNot { it == ' ' || it == '-' }
        return compact.length in 3..10 && compact.all { it.isDigit() }
    }

    private suspend fun searchPlaceName(query: String): List<PlaceCandidate> {
        val url = placeSearchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", maxResults.toString())
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()

        val body = execute(url.toString(), "place name")
        val parsed = runCatching { json.decodeFromString<OpenMeteoResponse>(body) }
            .getOrElse { throw LocationLookupException("Could not read the place results", it) }

        return parsed.results.orEmpty().mapNotNull { place ->
            val name = place.name ?: return@mapNotNull null
            val lat = place.latitude ?: return@mapNotNull null
            val lon = place.longitude ?: return@mapNotNull null
            PlaceCandidate(
                name = name,
                latitude = lat,
                longitude = lon,
                admin = place.admin1,
                country = place.country,
                countryCode = place.country_code?.uppercase(),
                timezone = place.timezone
            )
        }
    }

    private suspend fun searchPostalCode(query: String): List<PlaceCandidate> {
        val url = postalSearchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", maxResults.toString())
            .addQueryParameter("addressdetails", "1")
            // Keep country names in English so the picker rows are consistent with
            // the place-name results, which come back as "Mexico" not "México".
            .addQueryParameter("accept-language", "en")
            .addQueryParameter("postalcode", query.filterNot { it == ' ' || it == '-' })
            .build()

        val body = execute(url.toString(), "postal code")
        val array = runCatching { json.decodeFromString<JsonArray>(body) }
            .getOrElse { throw LocationLookupException("Could not read the postal results", it) }

        return array.mapNotNull { element ->
            val place = runCatching { json.decodeFromJsonElement(NominatimPlace.serializer(), element) }
                .getOrNull() ?: return@mapNotNull null
            val lat = place.lat?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = place.lon?.toDoubleOrNull() ?: return@mapNotNull null
            val address = place.address
            PlaceCandidate(
                name = place.name ?: place.display_name?.substringBefore(',')?.trim().orEmpty(),
                latitude = lat,
                longitude = lon,
                admin = address?.state,
                country = address?.country,
                countryCode = address?.country_code?.uppercase(),
                postalCode = address?.postcode
            )
        }.filter { it.name.isNotBlank() }
    }

    private fun execute(url: String, what: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LocationLookupException(
                        "The $what search failed (HTTP ${response.code})"
                    )
                }
                response.body?.string()
                    ?: throw LocationLookupException("The $what search returned no data")
            }
        } catch (e: IOException) {
            throw LocationLookupException(
                "Could not reach the $what search. Check the internet connection.", e
            )
        }
    }
}