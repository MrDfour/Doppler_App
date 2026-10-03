package com.sandman.doppler.api

import kotlinx.coroutines.Dispatchers
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
    customClient: OkHttpClient? = null
) : DopplerLocalApi(
    host = host,
    port = port,
    dsn = dsn,
    localKey = "",
    customClient = customClient,
    useTls = true
) {
    /**
     * Absolute URL for a control-plane path.
     *
     * Honours the configured [host] rather than hardcoding the domain, so a test can
     * point the cloud path at a local stub. Production always resolves to
     * `control.sandmandoppler.com`.
     */
    protected open fun buildUrl(path: String): String = "https://$host/$dsn/$path"

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
            var response = try {
                client.newCall(reqBuilder.build()).execute()
            } catch (e: IOException) {
                throw DopplerException.LocalConnectionException(
                    "Failed to reach cloud control endpoint for $dsn", e
                )
            }
            // Access token may have expired: try one token refresh + retry on 401.
            if (response.code == 401 && refreshTokenProvider != null) {
                response.close()
                val fresh = refreshTokenProvider()
                if (!fresh.isNullOrBlank()) {
                    cloudAccessToken = fresh
                    reqBuilder.header("Authorization", "Bearer $fresh")
                    response = client.newCall(reqBuilder.build()).execute()
                }
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw DopplerException.ProtocolException(
                        "Cloud control for $path failed: HTTP ${resp.code}",
                        httpCode = resp.code
                    )
                }
                return@withContext resp.body?.string() ?: ""
            }
        }
    }

    private fun getHttpClientForCloud(): OkHttpClient = cloudClient

    companion object {
        private val cloudClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
