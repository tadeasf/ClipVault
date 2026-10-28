package cz.tadeasf.clipvault.sync.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.node.Discovery
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * mDNS through the platform's NsdManager (design 4), the Android side of the desktop's JmDNS discovery. A
 * random instance name per node start (design 5.4); `pairing=1` in the TXT record while a code is shown
 * (pairing.md 5). Browsing starts on the first [candidates] call and keeps running, so resolved services
 * stay cached between dial rounds.
 */
class NsdDiscovery(context: Context, c: Crypto) : Discovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val instance = "cv-" + c.randomBytes(4).toHexString()

    // Wi-Fi firmware may drop multicast (mDNS answers) unless an app holds this lock.
    private val multicast = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("clipvault-mdns")?.apply { setReferenceCounted(false) }
    private var registration: NsdManager.RegistrationListener? = null
    private var browse: NsdManager.DiscoveryListener? = null

    private class Found(val address: String, val pairing: Boolean)

    private val found = ConcurrentHashMap<String, Found>() // service name -> resolved address
    private val resolver = Executors.newSingleThreadExecutor() // pre-34 resolveService takes one request at a time

    override fun advertise(port: Int, pairing: Boolean) {
        unregister()
        multicast?.acquire()
        val info = NsdServiceInfo().apply {
            serviceName = instance
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            if (pairing) setAttribute("pairing", "1")
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.i(TAG, "mDNS registration failed: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }.also { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    override suspend fun candidates(): List<String> {
        startBrowsing()
        return found.values.map { it.address }.distinct()
    }

    /** `host:port` of devices showing a pairing code, waiting up to [timeoutMillis] for the first. */
    suspend fun pairingCandidates(timeoutMillis: Long = 10_000): List<String> {
        startBrowsing()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val hits = found.values.filter { it.pairing }.map { it.address }.distinct()
            if (hits.isNotEmpty() || System.currentTimeMillis() >= deadline) return hits
            delay(250)
        }
    }

    @Synchronized
    private fun startBrowsing() {
        if (browse != null) return
        multicast?.acquire()
        browse = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.i(TAG, "mDNS browse failed: $error")
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != instance) resolver.execute { resolve(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
            }
        }.also { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    /** Blocks the resolver thread until this service is resolved (or 5 s pass). */
    private fun resolve(info: NsdServiceInfo) {
        val done = java.util.concurrent.CountDownLatch(1)
        val store = { resolved: NsdServiceInfo ->
            hostOf(resolved)?.let { host ->
                found[resolved.serviceName] = Found(host + ":" + resolved.port, resolved.attributes["pairing"]?.decodeToString() == "1")
            }
            Unit
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(error: Int) = done.countDown()
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    store(resolved)
                    done.countDown()
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            // Not [resolver]: it is blocked in await() below, so the callback would only run after the timeout.
            nsd.registerServiceInfoCallback(info, { it.run() }, callback)
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) = done.countDown()
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    store(resolved)
                    done.countDown()
                }
            })
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else @Suppress("DEPRECATION") listOfNotNull(info.host)
        // IPv4 first: the desktop advertises both, and its listener may only be bound to site-local IPv4.
        val a = addresses.firstOrNull { it !is Inet6Address } ?: addresses.firstOrNull() ?: return null
        return if (a is Inet6Address) "[${a.hostAddress?.substringBefore('%')}]" else a.hostAddress
    }

    private fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    @Synchronized
    override fun close() {
        unregister()
        runCatching { multicast?.release() }
        browse?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browse = null
        found.clear()
        resolver.shutdownNow()
    }

    companion object {
        const val SERVICE_TYPE = "_clipvault._tcp"
        private const val TAG = "ClipVaultSync"
    }
}
