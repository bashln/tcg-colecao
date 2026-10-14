package app.tcg.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Real Android event sources for the sync triggers. Each flow emits the current truth first
 * (so a late subscriber is never stale) and then only genuine changes; [SyncTriggerBinder]
 * is what decides those emissions mean "sync now".
 */
object SyncTriggerSources {

    /** Emits true while the app is in the foreground, false once it is backgrounded. */
    fun foreground(): Flow<Boolean> = callbackFlow {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                trySend(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                trySend(false)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        awaitClose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }.conflate().distinctUntilChanged()

    /** Emits true while any usable (validated) network is reachable, false otherwise. */
    @Suppress("TooGenericExceptionCaught")
    fun networkAvailable(context: Context): Flow<Boolean> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(currentlyAvailable(connectivity))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(hasUsableInternet(caps))
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Some restricted profiles refuse registration; assume online and let the
            // sync layer surface real failures rather than permanently blocking sync.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        trySend(currentlyAvailable(connectivity))
        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }.conflate().distinctUntilChanged()

    private fun currentlyAvailable(connectivity: ConnectivityManager): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return hasUsableInternet(caps)
    }

    private fun hasUsableInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
