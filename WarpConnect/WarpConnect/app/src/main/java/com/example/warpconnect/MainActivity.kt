package com.example.warpconnect

import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.warpconnect.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    // Region / Clean IP စာရင်းများ
    private data class ServerRegion(val name: String, val endpoint: String)
    private val regions = listOf(
        ServerRegion("🌐 Auto (Best Route)", "engage.cloudflareclient.com:2408"),
        ServerRegion("🇸🇬 Singapore", "162.159.192.1:2408"),
        ServerRegion("🇯🇵 Japan", "162.159.193.1:2408"),
        ServerRegion("🇭🇰 Hong Kong", "162.159.195.1:2408"),
        ServerRegion("🇺🇸 United States", "162.159.192.100:2408")
    )
    private var selectedRegion = regions[0]

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                vm.connect()
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.resetButton.setOnClickListener { vm.resetRegistration() }

        // Region Selector ကို သုံးနိုင်ရန် Status Text ကို နှိပ်လျှင် Popup ပွင့်စေခြင်း
        binding.statusHint.setOnClickListener { showRegionDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { render(it) }
            }
        }
    }

    private fun showRegionDialog() {
        if (vm.ui.value.state == ConnState.CONNECTED) {
            Toast.makeText(this, "Please disconnect before changing region", Toast.LENGTH_SHORT).show()
            return
        }

        val regionNames = regions.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select VPN Region")
            .setItems(regionNames) { _, which ->
                selectedRegion = regions[which]
                Toast.makeText(this, "Selected: ${selectedRegion.name}", Toast.LENGTH_SHORT).show()
                // Endpoint ပြောင်းလဲရန် Logic ထည့်သွင်းနိုင်သည်
            }
            .show()
    }

    private fun onConnectClicked() {
        when (vm.ui.value.state) {
            ConnState.DISCONNECTED -> {
                val intent = VpnService.prepare(this)
                if (intent != null) vpnPermission.launch(intent) else vm.connect()
            }
            ConnState.CONNECTED -> vm.disconnect()
            else -> Unit // busy
        }
    }

    private data class Style(
        @StringRes val status: Int,
        @StringRes val hint: Int,
        @ColorRes val dot: Int,
        @StringRes val button: Int,
        @ColorRes val buttonColor: Int,
    )

    private fun render(s: UiState) {
        val style = when (s.state) {
            ConnState.DISCONNECTED -> Style(
                R.string.status_disconnected, R.string.hint_disconnected,
                R.color.status_gray, R.string.btn_connect, R.color.warp_orange,
            )
            ConnState.REGISTERING -> Style(
                R.string.status_registering, R.string.hint_registering,
                R.color.status_amber, R.string.btn_wait, R.color.status_amber,
            )
            ConnState.CONNECTING -> Style(
                R.string.status_connecting, R.string.hint_connecting,
                R.color.status_amber, R.string.btn_wait, R.color.status_amber,
            )
            ConnState.CONNECTED -> Style(
                R.string.status_connected, R.string.hint_connected,
                R.color.status_green, R.string.btn_disconnect, R.color.status_green,
            )
            ConnState.DISCONNECTING -> Style(
                R.string.status_disconnecting, R.string.hint_disconnecting,
                R.color.status_amber, R.string.btn_wait, R.color.status_amber,
            )
        }

        val busy = s.state == ConnState.REGISTERING ||
            s.state == ConnState.CONNECTING ||
            s.state == ConnState.DISCONNECTING

        binding.statusText.setText(style.status)
        
        // Status Hint စာတန်းတွင် Selected Region အမည်ပါ ပြပေးခြင်း
        binding.statusHint.text = if (s.state == ConnState.DISCONNECTED) {
            "Tap here to change region: ${selectedRegion.name}"
        } else {
            getString(style.hint)
        }

        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, style.dot))

        binding.connectButton.apply {
            setText(style.button)
            backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, style.buttonColor))
            isEnabled = !busy
            alpha = if (busy) 0.7f else 1f
        }
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE

        binding.sentValue.text = Formatter.formatShortFileSize(this, s.bytesSent)
        binding.receivedValue.text = Formatter.formatShortFileSize(this, s.bytesReceived)
        binding.sentRate.text =
            getString(R.string.rate_format, Formatter.formatShortFileSize(this, s.upRate))
        binding.receivedRate.text =
            getString(R.string.rate_format, Formatter.formatShortFileSize(this, s.downRate))

        binding.infoText.text = if (s.deviceId != null) {
            getString(R.string.info_registered, s.deviceId, selectedRegion.endpoint)
        } else {
            getString(R.string.info_not_registered)
        }

        binding.errorText.apply {
            text = s.error
            visibility = if (s.error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        binding.resetButton.visibility =
            if (s.state == ConnState.DISCONNECTED && s.deviceId != null) View.VISIBLE
            else View.INVISIBLE
    }
}
