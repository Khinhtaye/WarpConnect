package com.example.warpconnect.data

import android.content.Context

/** Everything needed to build a WireGuard tunnel to Cloudflare WARP. */
data class WarpAccount(
    val deviceId: String,
    val accessToken: String,
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val endpoint: String,
    val addressV4: String,
    val addressV6: String?,
) {
    /** Standard wg-quick style config, parsed by the WireGuard library. */
    fun toWgQuickConfig(): String = buildString {
        val addresses = listOfNotNull("$addressV4/32", addressV6?.let { "$it/128" })
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = ${addresses.joinToString(", ")}")
        appendLine("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001")
        appendLine("MTU = 1280")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKey")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = $endpoint")
        appendLine("PersistentKeepalive = 25")
    }
}

/** Persists the registered WARP identity in app-private storage. */
class WarpStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("warp_account", Context.MODE_PRIVATE)

    fun load(): WarpAccount? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return WarpAccount(
            deviceId = id,
            accessToken = prefs.getString(KEY_TOKEN, null) ?: return null,
            privateKey = prefs.getString(KEY_PRIVATE, null) ?: return null,
            publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null,
            peerPublicKey = prefs.getString(KEY_PEER, null) ?: return null,
            endpoint = prefs.getString(KEY_ENDPOINT, null) ?: return null,
            addressV4 = prefs.getString(KEY_V4, null) ?: return null,
            addressV6 = prefs.getString(KEY_V6, null),
        )
    }

    fun save(account: WarpAccount) {
        prefs.edit()
            .putString(KEY_ID, account.deviceId)
            .putString(KEY_TOKEN, account.accessToken)
            .putString(KEY_PRIVATE, account.privateKey)
            .putString(KEY_PUBLIC, account.publicKey)
            .putString(KEY_PEER, account.peerPublicKey)
            .putString(KEY_ENDPOINT, account.endpoint)
            .putString(KEY_V4, account.addressV4)
            .putString(KEY_V6, account.addressV6)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_TOKEN = "token"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
        const val KEY_PEER = "peer_public_key"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_V4 = "address_v4"
        const val KEY_V6 = "address_v6"
    }
}
