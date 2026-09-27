package com.sandman.doppler.api

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.sandman.doppler.model.DopplerDeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class DiscoveredDoppler(
    val name: String,
    val host: String,
    val port: Int,
    val dsn: String? = null
)

/**
 * Handles device discovery on the local network through two mechanisms:
 * 1. Network Service Discovery (mDNS / DNS-SD via Android NsdManager)
 * 2. Active subnet probing (scanning local Wi-Fi subnet /24 on port 3000/80)
 */
class DopplerDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDoppler>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDoppler>> = _discoveredDevices.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Start mDNS discovery for Doppler services (_doppler._tcp or _http._tcp)
     */
    fun startMdnsDiscovery(serviceType: String = "_http._tcp.") {
        stopMdnsDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
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

                            val device = DiscoveredDoppler(
                                name = name,
                                host = host,
                                port = port
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
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopMdnsDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            // Fallback or ignore if discovery service is unavailable
        }
    }

    fun stopMdnsDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {}
            discoveryListener = null
        }
    }

    /**
     * Active subnet probing: scans the current /24 Wi-Fi subnet for responsive Doppler HTTP endpoints
     */
    suspend fun probeSubnet(port: Int = 3000): List<DiscoveredDoppler> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddressInt = wifiManager.connectionInfo.ipAddress
        if (ipAddressInt == 0) return@withContext emptyList()

        @Suppress("DEPRECATION")
        val ipString = Formatter.formatIpAddress(ipAddressInt)
        val subnetPrefix = ipString.substringBeforeLast(".")

        val foundList = mutableListOf<DiscoveredDoppler>()

        // Probe hosts in parallel chunks across the subnet
        for (i in 1..254) {
            val candidateIp = "$subnetPrefix.$i"
            if (candidateIp == ipString) continue // skip self

            try {
                val request = Request.Builder()
                    .url("http://$candidateIp:$port/api/devices")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val device = DiscoveredDoppler(
                            name = "Doppler ($candidateIp)",
                            host = candidateIp,
                            port = port
                        )
                        foundList.add(device)
                        addDiscoveredDevice(device)
                    }
                }
            } catch (e: Exception) {
                // Host unreachable or not a Doppler; continue scan
            }
        }

        return@withContext foundList
    }

    private fun addDiscoveredDevice(device: DiscoveredDoppler) {
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.host == device.host && it.port == device.port }) {
            current.add(device)
            _discoveredDevices.value = current
        }
    }
}
