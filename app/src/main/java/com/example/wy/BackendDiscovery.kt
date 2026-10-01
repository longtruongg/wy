package com.example.wy


import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

object BackendDiscovery {
    private const val TAG = "BackendDiscovery"
    private const val SERVICE_TYPE = "_wytracker._tcp."
    private const val PREFS = "backend_discovery"
    private const val KEY_IP = "last_known_ip"
    private const val KEY_PORT = "last_known_port"

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Last successfully resolved address, persisted across restarts/reboots. */
    fun cachedHost(context: Context): String =
        prefs(context).getString(KEY_IP, BASE_IP) ?: BASE_IP

    fun cachedPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, BASE_PORT)

    /** Call this on wifi-connect, before a flush attempt. Non-blocking, updates cache async. */
    fun discover(context: Context, timeoutMs: Long = 4000L) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager
        stopDiscovery() // avoid double-registration if called twice

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host.hostAddress ?: return
                        val port = info.port
                        Log.i(TAG, "resolved backend: $host:$port")
                        prefs(context).edit()
                            .putString(KEY_IP, host)
                            .putInt(KEY_PORT, port)
                            .apply()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        // NSD has no built-in timeout; stop it yourself after a few seconds
        android.os.Handler(context.mainLooper).postDelayed({ stopDiscovery() }, timeoutMs)
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}