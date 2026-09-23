package com.example.warpconnect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.warpconnect.data.WarpAccount
import com.example.warpconnect.data.WarpApi
import com.example.warpconnect.data.WarpStore
import com.example.warpconnect.vpn.WarpTunnelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnState { DISCONNECTED, REGISTERING, CONNECTING, CONNECTED, DISCONNECTING }

data class UiState(
    val state: ConnState = ConnState.DISCONNECTED,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val upRate: Long = 0,
    val downRate: Long = 0,
    val error: String? = null,
    val deviceId: String? = null,
    val endpoint: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = WarpStore(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var pollJob: Job? = null

    init {
        WarpTunnelManager.initialize(app)
        val account = store.load()
        val up = WarpTunnelManager.isUp.value
        _ui.value = UiState(
            state = if (up) ConnState.CONNECTED else ConnState.DISCONNECTED,
            deviceId = account?.deviceId,
            endpoint = account?.endpoint,
        )
        if (up) startPolling()

        viewModelScope.launch {
            WarpTunnelManager.isUp.collect { isUp ->
                if (!isUp && _ui.value.state == ConnState.CONNECTED) {
                    stopPolling()
                    _ui.update { it.copy(state = ConnState.DISCONNECTED, upRate = 0, downRate = 0) }
                }
            }
        }
    }

    fun connect() {
        if (_ui.value.state != ConnState.DISCONNECTED) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(error = null) }
                val account = withContext(Dispatchers.IO) { store.load() } ?: registerNew()

                _ui.update { it.copy(state = ConnState.CONNECTING) }
                withContext(Dispatchers.IO) { WarpTunnelManager.up(account) }

                _ui.update {
                    it.copy(
                        state = ConnState.CONNECTED,
                        bytesSent = 0, bytesReceived = 0, upRate = 0, downRate = 0,
                    )
                }
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        state = ConnState.DISCONNECTED,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun disconnect() {
        if (_ui.value.state != ConnState.CONNECTED) return
        viewModelScope.launch {
            _ui.update { it.copy(state = ConnState.DISCONNECTING) }
            stopPolling()
            var error: String? = null
            try {
                withContext(Dispatchers.IO) { WarpTunnelManager.down() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            _ui.update {
                it.copy(state = ConnState.DISCONNECTED, upRate = 0, downRate = 0, error = error)
            }
        }
    }

    fun resetRegistration() {
        if (_ui.value.state != ConnState.DISCONNECTED) return
        store.clear()
        _ui.update { it.copy(deviceId = null, endpoint = null, error = null) }
    }

    /** Selected Region ၏ Endpoint IP ကို Account ထဲတွင် အစားထိုးပေးခြင်း */
    fun updateEndpoint(newEndpoint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentAccount = store.load()
            if (currentAccount != null) {
                val updatedAccount = currentAccount.copy(endpoint = newEndpoint)
                store.save(updatedAccount)
                _ui.update { it.copy(endpoint = newEndpoint) }
            }
        }
    }

    private suspend fun registerNew(): WarpAccount {
        _ui.update { it.copy(state = ConnState.REGISTERING) }
        val account = withContext(Dispatchers.IO) {
            WarpApi.register().also { store.save(it) }
        }
        _ui.update { it.copy(deviceId = account.deviceId, endpoint = account.endpoint) }
        return account
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var lastRx = -1L
            var lastTx = -1L
            while (isActive) {
                val stats = withContext(Dispatchers.IO) { WarpTunnelManager.stats() }
                if (stats != null) {
                    val (rx, tx) = stats
                    val down = if (lastRx >= 0) (rx - lastRx).coerceAtLeast(0) else 0L
                    val up = if (lastTx >= 0) (tx - lastTx).coerceAtLeast(0) else 0L
                    lastRx = rx
                    lastTx = tx
                    _ui.update {
                        it.copy(bytesReceived = rx, bytesSent = tx, downRate = down, upRate = up)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
