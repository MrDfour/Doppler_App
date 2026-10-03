package com.sandman.doppler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud control-plane client for the Sandman Doppler.
 *
 * Mirrors every endpoint of [DopplerLocalApi] but targets
 * `https://control.sandmandoppler.com/{dsn}/...` using the Copilot cloud
 * access token obtained during onboarding. This is the path that works when the
 * clock's local oatpp daemon is not listening on the LAN (closed port 5443).
 *
 * Requests are serialized through the inherited [DopplerLocalApi.requestGate] to be
 * polite to the cloud's single control channel and to protect the clock's single-threaded
 * daemon, exactly as the LAN path does. The gate is priority-aware, so the background
 * poll cannot delay a user action on a high-latency link.
 */
open class DopplerCloudApi(
    host: String = "control.sandmandoppler.com",
    port: Int = 443,
    dsn: String,
    protected var cloudAccessToken: String,
    private val refreshTokenProvider: (suspend () -> String?)? = null,
    customClient: OkHttpClient? = null,
    capabilities: EndpointCapabilities = EndpointCapabilities()
) : DopplerLocalApi(
    host = host,
    port = port,
    dsn = dsn,
    localKey = "",
    customClient = customClient,
    useTls = true,
    capabilities = capabilities
) {
    /**
     * Absolute URL for a control-plane path.
     *
     * Honours the configured [host] rather than hardcoding the domain, so a test can
     * point the cloud path at a local stub. Production always resolves to
     * `control.sandmandoppler.com`.
     */
    protected open fun buildUrl(path: String): String = "https://$host/$dsn/$path"

    /** Guards the token refresh so concurrent 401s collapse into a single attempt. */
    private val refreshMutex = Mutex()

    /**
     * Set once a refresh attempt fails. The refresh token is not going to start working
     * on a later attempt within the same session, and retrying it per request is what
     * turned one expired token into a latency storm.
     */
    @Volatile
    private var refreshTokenExhausted = false

    /**
     * Executes one control-plane request under the shared [DopplerLocalApi.requestGate].
     *
     * The gate is what keeps requests to the clock serialized. It was previously missing
     * here even though this method replaces the parent's implementation wholesale, which
     * meant cloud-mode requests ran fully concurrently - the exact hazard the gate
     * exists to prevent, and the likely source of volume/setting "jumping" in cloud mode.
     *
     * Note this mirrors the parent rather than calling it: the cloud path swaps the
     * nonce/localKey handshake for a cloud Bearer token, so it cannot share the body.
     * It must keep honouring [priority] so poll reads still yield to user actions.
     */
    override suspend fun executeAuthenticatedRequest(
        method: String,
        path: String,
        jsonBody: String?,
        retryOn410: Boolean,
        priority: RequestGate.Priority
    ): String = requestGate.withRequest(priority) {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val url = buildUrl(path)
            val reqBuilder = Request.Builder().url(url)
                .header("Authorization", "Bearer $cloudAccessToken")
                .header("Accept", "application/json")

            val body = jsonBody?.toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )
            reqBuilder.apply {
                when (method.uppercase()) {
                    "GET" -> get()
                    "POST" -> post(body ?: "".toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    ))
                    "PUT" -> put(body ?: "".toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    ))
                    "DELETE" -> delete(body)
                    else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
                }
            }

            val client = getHttpClientForCloud()
            val call = client.newCall(reqBuilder.build())
            // Per-call deadline, so a hung poll read cannot occupy the gate for the
            // client's full read timeout. Priority matters here, not just queue order:
            // a request already executing blocks everyone, so a 30s stall on a
            // background read delays the user's write by 30s no matter how the gate
            // orders the queue behind it.
            call.timeout().timeout(deadlineMs(priority), TimeUnit.MILLISECONDS)

            var response = try {
                call.execute()
            } catch (e: IOException) {
                RequestTelemetry.record(
                    path = path,
                    priority = priority,
                    durationMs = elapsedMs(startedAt),
                    outcome = RequestTelemetry.Outcome.TIMEOUT_OR_IO_ERROR
                )
                throw DopplerException.LocalConnectionException(
                    "Failed to reach cloud control endpoint for $dsn", e
                )
            }
            // Access token may have expired: try one token refresh + retry on 401.
            if (response.code == 401 && refreshTokenProvider != null) {
                val rejectedToken = cloudAccessToken
                response.close()
                val fresh = refreshedTokenAfter(rejectedToken)
                if (!fresh.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $fresh")
                    val retry = client.newCall(reqBuilder.build())
                    retry.timeout().timeout(deadlineMs(priority), TimeUnit.MILLISECONDS)
                    response = try {
                        retry.execute()
                    } catch (e: IOException) {
                        throw DopplerException.LocalConnectionException(
                            "Failed to reach cloud control endpoint for $dsn", e
                        )
                    }
                }
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    RequestTelemetry.record(
                        path = path,
                        priority = priority,
                        durationMs = elapsedMs(startedAt),
                        outcome = RequestTelemetry.Outcome.HTTP_ERROR,
                        httpCode = resp.code
                    )
                    throw DopplerException.ProtocolException(
                        "Cloud control for $path failed: HTTP ${resp.code}",
                        httpCode = resp.code
                    )
                }
                RequestTelemetry.record(
                    path = path,
                    priority = priority,
                    durationMs = elapsedMs(startedAt),
                    outcome = RequestTelemetry.Outcome.OK,
                    httpCode = resp.code
                )
                return@withContext resp.body?.string() ?: ""
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    /**
     * Refreshes the cloud access token, collapsing a dead credential into one attempt.
     *
     * The 401 branch used to call [refreshTokenProvider] inline, once per request, while
     * holding [requestGate]. That is harmless when the refresh *succeeds* - the token is
     * written back and later requests are accepted - but it is a disaster when it fails:
     * a dead refresh token meant every one of the 22 poll reads paid a failed refresh
     * round-trip to `api.sandmandoppler.bycopilot.com` before retrying and failing again.
     * Each of those is another network call inside the gate, which is the erratic
     * 2s-to-30s behaviour reported on device.
     *
     * Two guards:
     * - **Fail fast.** Once a refresh fails, remember it. A credential that just failed
     *   will not start working mid-session, and retrying it per request cannot succeed.
     * - **Single-flight.** Concurrent 401s await one refresh rather than each starting
     *   their own. Defence in depth: [requestGate] already serializes requests, so this
     *   rarely fires, but it keeps the invariant local to the credential rather than
     *   depending on a lock three layers up.
     */
    private suspend fun refreshedTokenAfter(rejectedToken: String): String? {
        if (refreshTokenExhausted) return null
        return refreshMutex.withLock {
            // A concurrent request may have refreshed while this one waited for the lock.
            if (cloudAccessToken != rejectedToken) return@withLock cloudAccessToken
            if (refreshTokenExhausted) return@withLock null

            RequestTelemetry.tokenRefreshAttempted()
            val fresh = try {
                refreshTokenProvider?.invoke()
            } catch (e: Exception) {
                null
            }
            if (fresh.isNullOrBlank()) {
                refreshTokenExhausted = true
                null
            } else {
                cloudAccessToken = fresh
                fresh
            }
        }
    }

    private fun getHttpClientForCloud(): OkHttpClient = cloudClient

    companion object {
        /**
         * Whole-call deadline for a user-initiated request.
         *
         * Long enough to ride out a slow relay, far short of the old 30s read timeout
         * that made a stalled write look like a hang.
         */
        private const val INTERACTIVE_DEADLINE_MS = 10_000L

        /**
         * Whole-call deadline for a poll read.
         *
         * Deliberately aggressive. A poll value that arrives late is worthless - the next
         * poll is seconds away - so it is better to abandon the read quickly and free the
         * gate than to hold it hostage for the interactive request behind it.
         */
        private const val BACKGROUND_DEADLINE_MS = 5_000L

        private fun deadlineMs(priority: RequestGate.Priority): Long =
            if (priority == RequestGate.Priority.INTERACTIVE) INTERACTIVE_DEADLINE_MS
            else BACKGROUND_DEADLINE_MS

        private val cloudClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // Backstop only; the per-call deadline above is what actually applies.
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
