package com.campusmesh.android.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages the Local-Only Wi-Fi Hotspot on Android and publishes the HTTP Ktor service via mDNS.
 */
object WifiHotspotManager {
    private const val TAG = "WifiHotspotManager"
    private const val SERVICE_TYPE = "_http._tcp."
    private const val SERVICE_NAME = "Campus-Mesh"

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Exposed hotspot details for QR Code generation
    @Volatile var hotspotSsid: String? = null
        private set

    @Volatile var hotspotPassword: String? = null
        private set

    @Volatile var isHotspotActive: Boolean = false
        private set

    // Real, reachable IP of this device on the hotspot interface. NsdManager's registerService()
    // below publishes a DNS-SD *service* record, not a custom "campusmesh.local" *host* A record —
    // Android's public API doesn't let an app claim an arbitrary mDNS hostname, so
    // "http://campusmesh.local:8080" is not guaranteed to resolve in Safari/Chrome even though
    // the service itself is discoverable. This IP is the guaranteed-working fallback.
    @Volatile var hotspotGatewayIp: String? = null
        private set

    fun startHotspotAndMdns(context: Context) {
        if (isHotspotActive) {
            Log.d(TAG, "Hotspot is already active.")
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            Log.e(TAG, "WifiManager is not available.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        super.onStarted(reservation)
                        hotspotReservation = reservation
                        isHotspotActive = true

                        val config = reservation?.wifiConfiguration
                        if (config != null) {
                            hotspotSsid = config.SSID
                            hotspotPassword = config.preSharedKey
                            Log.i(TAG, "🔥 Local Hotspot started successfully. SSID: $hotspotSsid, PSK: $hotspotPassword")
                        } else {
                            Log.w(TAG, "Local Hotspot started but configuration was null.")
                        }

                        hotspotGatewayIp = findLocalIpv4Address()
                        Log.i(TAG, "🌐 Gateway URL for QR / manual entry: http://${hotspotGatewayIp ?: "unknown"}:8080")

                        // Register mDNS after Hotspot is up (best-effort; see hotspotGatewayIp doc above)
                        registerMdnsService(context)
                    }

                    override fun onStopped() {
                        super.onStopped()
                        Log.i(TAG, "🛑 Local Hotspot stopped.")
                        cleanUpHotspotState()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        Log.e(TAG, "❌ Local Hotspot starting failed with reason: $reason")
                        cleanUpHotspotState()
                    }
                }, Handler(Looper.getMainLooper()))
            } else {
                Log.w(TAG, "Local-Only Hotspot requires Android 8.0 (API 26) or higher.")
                // Fallback: register mDNS anyway on existing Wi-Fi network
                registerMdnsService(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting Local Hotspot: ${e.message}", e)
        }
    }

    fun stopHotspotAndMdns() {
        Log.i(TAG, "Stopping Hotspot and mDNS service...")
        unregisterMdnsService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotReservation?.close()
        }
        cleanUpHotspotState()
    }

    private fun registerMdnsService(context: Context) {
        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.apply {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                port = 8080
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG, "✅ mDNS service successfully registered: ${NsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "❌ mDNS registration failed: errorCode=$errorCode")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service unregistered.")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "❌ mDNS unregistration failed: errorCode=$errorCode")
                }
            }

            try {
                registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "mDNS registration crashed: ${e.message}")
            }
        }
    }

    private fun unregisterMdnsService() {
        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister mDNS: ${e.message}")
        } finally {
            registrationListener = null
            nsdManager = null
        }
    }

    private fun cleanUpHotspotState() {
        hotspotReservation = null
        hotspotSsid = null
        hotspotPassword = null
        hotspotGatewayIp = null
        isHotspotActive = false
    }

    /**
     * Finds this device's own IPv4 address on a local (non-loopback) interface — this is the
     * gateway address other devices on the same hotspot/Wi-Fi network use to reach our Ktor
     * server. Prefers interfaces that look like an AP/hotspot interface (ap*, wlan*, swlan*)
     * but falls back to the first non-loopback IPv4 address found.
     */
    private fun findLocalIpv4Address(): String? {
        return try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> java.util.Collections.list(iface.inetAddresses).map { iface.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }

            // Prefer typical hotspot/AP interface names first
            candidates.firstOrNull { (name, _) ->
                name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan")
            }?.second?.hostAddress
                ?: candidates.firstOrNull()?.second?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IPv4 address: ${e.message}")
            null
        }
    }
}
