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

    // Region / WireGuard Config Data Structure
    data class ServerRegion(
        val name: String,
        val flag: String,
        val address: String,
        val privateKey: String,
        val publicKey: String,
        val endpoint: String,
        val dns: String = "1.1.1.1"
    )

    private val regions = listOf(
        ServerRegion(
            name = "Auto (Cloudflare WARP)",
            flag = "🌐",
            address = "172.16.0.2/32",
            privateKey = "",
            publicKey = "bmXOC+F1FxEMF9mTpd24F2I6ARKTXvoE9y8gb76v4U8=",
            endpoint = "engage.cloudflareclient.com:2408"
        ),
        ServerRegion(
            name = "Singapore",
            flag = "🇸🇬",
            address = "10.2.0.2/32",
            privateKey = "SG_PRIVATE_KEY_HERE",
            publicKey = "SG_PUBLIC_KEY_HERE",
            endpoint = "162.159.192.1:2408"
        ),
        ServerRegion(
            name = "Japan",
            flag = "🇯🇵",
            address = "10.3.0.2/32",
            privateKey = "JP_PRIVATE_KEY_HERE",
            publicKey = "JP_PUBLIC_KEY_HERE",
            endpoint = "162.159.193.1:2408"
        )
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
        
        // Status Hint စာတန်းကို နှိပ်လျှင် Region ရွေးခွင့်ပေးခြင်း
        binding.statusHint.setOnClickListener { showRegionDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { render(it) }
            }
        }
    }

    private fun showRegionDialog() {
        if (vm.ui.value.state == ConnState.CONNECTED) {
            Toast.makeText(this, "Region မပြောင်းမီ VPN ကို ခဏ ပိတ်ပေးပါ", Toast.LENGTH_SHORT).show()
            return
        }

        val regionNames = regions.map { "${it.flag} ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select VPN Region")
            .setItems(regionNames) { _, which ->
                selectedRegion = regions[which]
                Toast.makeText(this, "Selected: ${selectedRegion.name}", Toast.LENGTH_SHORT).show()
                
                // ViewModel သို့ Config သစ် ပေးပို့ခြင်း
                vm.updateConfiguration(
                    address = selectedRegion.address,
                    privateKey = selectedRegion.privateKey,
                    publicKey = selectedRegion.publicKey,
                    endpoint = selectedRegion.endpoint,
                    dns = selectedRegion.dns
                )
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
            else -> Unit
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
        
        binding.statusHint.text = if (s.state == ConnState.DISCONNECTED) {
            "Tap here to change region: ${selectedRegion.flag} ${selectedRegion.name}"
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
