package cz.tadeasf.clipvault.sync.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * The phone's LAN as sync sees it: the site-local addresses of its Wi-Fi and Ethernet networks (never
 * cellular, whose 10.x addresses are carrier NAT), the current SSID for the allowlist, and a callback when
 * any of that changes ([watch] while the node runs).
 */
class NetworkWatch(context: Context, private val changed: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Design 5.4: site-local only (IPv4 private, IPv6 ULA). */
    @Suppress("DEPRECATION") // allNetworks: a LAN without internet isn't the default network, so activeNetwork misses it
    fun listenAddresses(): List<String> = cm.allNetworks.filter { lan(cm.getNetworkCapabilities(it)) }
        .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
        .map { it.address }
        .filter { (it is Inet4Address && it.isSiteLocalAddress) || (it is Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc) }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .distinct()
        .sorted()

    /**
     * The Wi-Fi network's name, or null when not on Wi-Fi or Android hides it: apps need location permission
     * (and location on) to see it (design 6.5).
     */
    @Suppress("DEPRECATION") // connectionInfo: the replacement needs a callback registered with FLAG_INCLUDE_LOCATION_INFO
    fun currentSsid(): String? = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }

    fun watch(on: Boolean) {
        if (on == (callback != null)) return
        if (!on) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = null
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // a LAN without internet still syncs
            .build()
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
        }.also { cm.registerNetworkCallback(request, it) }
    }

    private fun lan(caps: NetworkCapabilities?) = caps != null &&
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
}
