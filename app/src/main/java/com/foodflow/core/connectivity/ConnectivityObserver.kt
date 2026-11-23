package com.foodflow.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectivityStatus {
    ONLINE,
    OFFLINE,
    RESTORED
}

/**
 * Observes real Android network state and merges it with developer-controlled
 * offline simulation so all offline-first workflows can be tested deterministically.
 */
interface ConnectivityObserver {
    val isOnline: Flow<Boolean>
    val status: Flow<ConnectivityStatus>
    /** Returns current validated system connectivity combined with the simulated-offline switch. */
    fun isCurrentlyOnline(): Boolean
    /** Forces observed connectivity offline for deterministic local testing. */
    fun setSimulatedOffline(offline: Boolean)
    val isSimulatedOffline: StateFlow<Boolean>
}

@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context
) : ConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _simulatedOffline = MutableStateFlow(false)
    override val isSimulatedOffline: StateFlow<Boolean> = _simulatedOffline.asStateFlow()

    /** Emits validated default-network changes and unregisters its Android callback on cancellation. */
    private val systemOnlineFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            /** Rechecks the default network when Android reports a network became available. */
            override fun onAvailable(network: Network) {
                trySend(checkSystemNetwork())
            }

            /** Rechecks connectivity because another default network may remain active. */
            override fun onLost(network: Network) {
                trySend(checkSystemNetwork())
            }

            /** Emits validated internet state only for the currently active default network. */
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val isDefaultNetwork = connectivityManager.activeNetwork == network
                trySend(isDefaultNetwork && networkCapabilities.hasValidatedInternet())
            }
        }

        trySend(checkSystemNetwork())
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }

        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    /** Combines device connectivity with the forced-offline switch and suppresses duplicate values. */
    override val isOnline: Flow<Boolean> = combine(
        systemOnlineFlow,
        _simulatedOffline
    ) { systemOnline, forcedOffline ->
        systemOnline && !forcedOffline
    }.distinctUntilChanged()

    /** Converts online values into ONLINE, OFFLINE, or a one-time RESTORED transition. */
    override val status: Flow<ConnectivityStatus> = callbackFlow {
        var wasOffline = false
        isOnline.collect { online ->
            val emitted = when {
                !online -> {
                    wasOffline = true
                    ConnectivityStatus.OFFLINE
                }
                wasOffline -> {
                    wasOffline = false
                    ConnectivityStatus.RESTORED
                }
                else -> ConnectivityStatus.ONLINE
            }
            trySend(emitted)
        }
    }.distinctUntilChanged()

    /** Combines live system connectivity with the local forced-offline state. */
    override fun isCurrentlyOnline(): Boolean = !_simulatedOffline.value && checkSystemNetwork()

    /** Updates the test switch that suppresses otherwise available network connectivity. */
    override fun setSimulatedOffline(offline: Boolean) {
        _simulatedOffline.value = offline
    }

    /** Checks that the active Android network has both internet and validation capabilities. */
    private fun checkSystemNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasValidatedInternet()
    }

    /** Requires Android to report internet access and successful connectivity validation. */
    private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
