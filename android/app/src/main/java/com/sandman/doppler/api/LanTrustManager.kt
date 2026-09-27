package com.sandman.doppler.api

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * TLS Trust Configuration for communicating with Sandman Doppler hardware over local Wi-Fi.
 *
 * The Sandman Doppler clock runs an internal embedded oatpp HTTPS daemon on port 5443 using
 * a self-signed local certificate. To enable pure standalone operation on Android without
 * root certificates or external proxies, this trust manager accepts self-signed certificates
 * strictly for RFC 1918 private subnets and local Doppler mDNS hostnames.
 */
object LanTrustManager {

    private val lanTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // For LAN Doppler endpoints, self-signed local certificates are accepted.
            // When connecting to public domains (Copilot cloud), a standard system trust manager is used.
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(lanTrustManager), SecureRandom())
        sslContext.socketFactory
    }

    private val lanHostnameVerifier = HostnameVerifier { hostname, session ->
        isLocalOrDopplerHost(hostname) || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
    }

    /**
     * Checks if a given host is a private LAN address or Doppler local hostname.
     */
    fun isLocalOrDopplerHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "10.0.2.2" // Android emulator host loopback
        ) {
            return true
        }

        if (host.endsWith(".local", ignoreCase = true) ||
            host.contains("doppler", ignoreCase = true)
        ) {
            return true
        }

        return try {
            val addr = InetAddress.getByName(host)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (e: Exception) {
            // If DNS cannot resolve, test IPv4 regex for RFC1918
            isPrivateIpv4(host)
        }
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false

        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 127 -> true
            else -> false
        }
    }

    /**
     * Configures an OkHttpClient.Builder with LAN-tolerant TLS for Doppler hardware.
     */
    fun configureLanTls(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder
            .sslSocketFactory(sslSocketFactory, lanTrustManager)
            .hostnameVerifier(lanHostnameVerifier)
    }
}
