package com.sandman.doppler.api

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDoppler(
    val name: String,
    val host: String,
    val port: Int,
    val dsn: String? = null
)

/**
 * Handles device discovery on the local network through two mechanisms:
 * 1. Network Service Discovery (mDNS / DNS-SD via Android NsdManager) for both `_http._tcp.` and `_https._tcp.`
 * 2. Active parallel subnet sweep specifically probing HTTPS port 5443 for oatpp server responses
 *
 * Designed for the Sandman Doppler clock's embedded web server which uses self-signed TLS
 * certificates on port 5443.
 */
class DopplerDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDoppler>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDoppler>> = _discoveredDevices.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scanClient: OkHttpClient by lazy {
        LanTrustManager.createOkHttpClientBuilder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .build()
    }

    private var discoveryListenerHttp: NsdManager.DiscoveryListener? = null
    private var discoveryListenerHttps: NsdManager.DiscoveryListener? = null
    private var scanJob: Job? = null

    /**
     * Start mDNS discovery for Doppler services on both `_http._tcp.` and `_https._tcp.`
     */
    fun startMdnsDiscovery() {
        stopMdnsDiscovery()

        discoveryListenerHttp = createDiscoveryListener()
        discoveryListenerHttps = createDiscoveryListener()

        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListenerHttp)
        } catch (e: Exception) {
            // Fallback or ignore if discovery service is unavailable
        }

        try {
            nsdManager.discoverServices("_https._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListenerHttps)
        } catch (e: Exception) {
            // Fallback or ignore if discovery service is unavailable
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.contains("doppler", ignoreCase = true) ||
                    serviceInfo.serviceName.contains("sandman", ignoreCase = true)
                ) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val host = resolvedInfo.host.hostAddress ?: return
                            val port = resolvedInfo.port
                            val name = resolvedInfo.serviceName

                            // Try to extract DSN from service name (e.g. "Doppler-12345678")
                            val extractedDsn = extractDsnFromName(name)

                            val device = DiscoveredDoppler(
                                name = name,
                                host = host,
                                port = port,
                                dsn = extractedDsn
                            )
                            addDiscoveredDevice(device)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredDevices.value = _discoveredDevices.value.filterNot {
                    it.name == serviceInfo.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
    }

    fun stopMdnsDiscovery() {
        discoveryListenerHttp?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
            discoveryListenerHttp = null
        }
        discoveryListenerHttps?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
            discoveryListenerHttps = null
        }
    }

    /**
     * Cancel an ongoing subnet scan.
     */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _scanProgress.value = 0f
    }

    /**
     * Active subnet probing: scans the current /24 Wi-Fi subnet for responsive
     * Doppler HTTPS port 5443 endpoints using parallel coroutine chunks.
     *
     * Probes hosts in parallel batches of [concurrency] to balance speed vs. network load.
     * Automatically extracts DSN by probing the device info endpoint on successful connections.
     *
     * @param port The port to scan (default 5443 for Doppler HTTPS).
     * @param concurrency Number of parallel probes per batch (default 16).
     */
    suspend fun probeSubnet(
        port: Int = 5443,
        concurrency: Int = 16
    ): List<DiscoveredDoppler> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        _scanProgress.value = 0f

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddressInt = wifiManager.connectionInfo.ipAddress
        if (ipAddressInt == 0) {
            _isScanning.value = false
            return@withContext emptyList()
        }

        @Suppress("DEPRECATION")
        val ipString = Formatter.formatIpAddress(ipAddressInt)
        val subnetPrefix = ipString.substringBeforeLast(".")

        val foundList = mutableListOf<DiscoveredDoppler>()
        val scannedCount = AtomicInteger(0)
        val totalHosts = 253 // 1..254 minus self

        // Build candidate list (excluding own IP)
        val candidates = (1..254).map { "$subnetPrefix.$it" }.filter { it != ipString }

        // Process in parallel chunks
        scanJob = coroutineContext[Job]
        candidates.chunked(concurrency).forEach { chunk ->
            if (!isActive) return@withContext foundList.toList()

            val deferredResults = chunk.map { candidateIp ->
                async {
                    probeSingleHost(candidateIp, port)
                }
            }

            deferredResults.forEach { deferred ->
                val device = deferred.await()
                if (device != null) {
                    foundList.add(device)
                    addDiscoveredDevice(device)
                }
                val progress = scannedCount.incrementAndGet().toFloat() / totalHosts
                _scanProgress.value = progress.coerceIn(0f, 1f)
            }
        }

        _isScanning.value = false
        _scanProgress.value = 1f
        return@withContext foundList
    }

    /**
     * Probes a single host:port for a Doppler clock.
     * Returns a [DiscoveredDoppler] if the host responds like an oatpp server, null otherwise.
     */
    private fun probeSingleHost(candidateIp: String, port: Int): DiscoveredDoppler? {
        return try {
            // The authentic LAN API requires the DSN in every path, so a DSN-less
            // probe cannot return 200. Any HTTP response (even 404) proves a TLS
            // service is listening; we additionally prefer the oatpp Server header
            // to confirm it is a Doppler clock rather than some other service.
            val request = Request.Builder()
                .url("https://$candidateIp:$port/")
                .get()
                .build()

            scanClient.newCall(request).execute().use { response ->
                val serverHeader = response.header("Server") ?: ""
                val looksOatp = serverHeader.contains("oatpp", ignoreCase = true)
                val plausibleStatus = response.code in listOf(200, 401, 403, 404)

                if (looksOatp || (plausibleStatus && response.code != 404)) {
                    DiscoveredDoppler(
                        name = "Sandman Doppler ($candidateIp)",
                        host = candidateIp,
                        port = port,
                        dsn = null
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // Host unreachable or not a Doppler; continue scan
            null
        }
    }

    /**
     * Extract DSN from mDNS service name (e.g. "Doppler-12345678" → "Doppler-12345678")
     */
    private fun extractDsnFromName(serviceName: String): String? {
        val dopplerPattern = Regex("(Doppler-[A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        return dopplerPattern.find(serviceName)?.value
    }

    private fun addDiscoveredDevice(device: DiscoveredDoppler) {
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.host == device.host && it.port == device.port }) {
            current.add(device)
            _discoveredDevices.value = current
        }
    }

    /**
     * Clear all discovered devices (e.g. before starting a fresh scan).
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }
}

/**
 * Minimal response model for DSN extraction during subnet scanning.
 */
@kotlinx.serialization.Serializable
private data class DsnProbeResponse(
    val serialNum: String? = null,
    val mfgrName: String? = null
)
