package com.example.warpconnect.vpn

import android.content.Context
import com.example.warpconnect.data.WarpAccount
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide wrapper around the WireGuard GoBackend.
 * A singleton so the tunnel state survives Activity / ViewModel recreation.
 */
object WarpTunnelManager {
    private var backend: GoBackend? = null

    private val _isUp = MutableStateFlow(false)
    val isUp: StateFlow<Boolean> = _isUp.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = "warp"

        // Also fires when the system revokes the VPN (e.g. another VPN app takes over).
        override fun onStateChange(newState: Tunnel.State) {
            _isUp.value = newState == Tunnel.State.UP
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (backend == null) backend = GoBackend(context.applicationContext)
    }

    private fun requireBackend(): GoBackend = checkNotNull(backend) { "Call initialize() first" }

    /** Blocking (resolves the endpoint host, starts wireguard-go) - call from Dispatchers.IO. */
    @Throws(Exception::class)
    fun up(account: WarpAccount) {
        val config = Config.parse(account.toWgQuickConfig().byteInputStream())
        requireBackend().setState(tunnel, Tunnel.State.UP, config)
        _isUp.value = true
    }

    @Throws(Exception::class)
    fun down() {
        requireBackend().setState(tunnel, Tunnel.State.DOWN, null)
        _isUp.value = false
    }

    /** Returns (bytesReceived, bytesSent) or null if unavailable. */
    fun stats(): Pair<Long, Long>? = try {
        val s = requireBackend().getStatistics(tunnel)
        s.totalRx() to s.totalTx()
    } catch (e: Exception) {
        null
    }
}
