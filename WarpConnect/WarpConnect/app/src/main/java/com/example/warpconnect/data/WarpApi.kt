package com.example.warpconnect.data

import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Cloudflare's (unofficial, undocumented) WARP registration API,
 * the same one used by the 1.1.1.1 app and by tools like wgcf.
 *
 * Flow: generate a Curve25519 key pair locally -> POST the public key to /reg ->
 * Cloudflare answers with our tunnel addresses, its public key and the endpoint.
 * The private key never leaves the device.
 */
object WarpApi {
    private const val BASE_URL = "https://api.cloudflareclient.com"
    private const val DEFAULT_PORT = 2408

    /**
     * (API path version, matching CF-Client-Version header). They are tried in order;
     * if Cloudflare retires one, add a newer pair at the top of this list.
     */
    private val API_VERSIONS = listOf(
        "v0a2158" to "a-6.3-2158",
        "v0a1922" to "a-6.3-1922",
    )

    private const val USER_AGENT = "okhttp/3.12.1"
    private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking call - run on Dispatchers.IO. */
    @Throws(IOException::class)
    fun register(): WarpAccount {
        val keyPair = KeyPair()
        val body = JSONObject().apply {
            put("key", keyPair.publicKey.toBase64())
            put("install_id", "")
            put("fcm_token", "")
            put("tos", isoNow())
            put("type", "Android")
            put("model", "PC")
            put("locale", "en_US")
        }.toString()

        var lastError: Exception? = null
        for ((version, clientVersion) in API_VERSIONS) {
            try {
                val response = post(version, clientVersion, body)
                val account = parse(response, keyPair)
                // Best effort: make sure WARP is switched on for this device.
                runCatching { enableWarp(version, clientVersion, account) }
                return account
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("WARP registration failed: ${lastError?.message}", lastError)
    }

    private fun post(version: String, clientVersion: String, body: String): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL/$version/reg")
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", clientVersion)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from $version: ${text.take(200)}")
            }
            JSONObject(text)
        }
    }

    private fun enableWarp(version: String, clientVersion: String, account: WarpAccount) {
        val request = Request.Builder()
            .url("$BASE_URL/$version/reg/${account.deviceId}")
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", clientVersion)
            .header("Authorization", "Bearer ${account.accessToken}")
            .patch(JSONObject().put("warp_enabled", true).toString().toRequestBody(JSON_TYPE))
            .build()
        client.newCall(request).execute().close()
    }

    private fun parse(json: JSONObject, keyPair: KeyPair): WarpAccount {
        val config = json.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")

        var endpoint = peer.getJSONObject("endpoint").getString("host")
        if (!endpoint.contains(":")) endpoint = "$endpoint:$DEFAULT_PORT"

        return WarpAccount(
            deviceId = json.getString("id"),
            accessToken = json.getString("token"),
            privateKey = keyPair.privateKey.toBase64(),
            publicKey = keyPair.publicKey.toBase64(),
            peerPublicKey = peer.getString("public_key"),
            endpoint = endpoint,
            addressV4 = addresses.getString("v4").substringBefore('/'),
            addressV6 = addresses.optString("v6").takeIf { it.isNotBlank() }?.substringBefore('/'),
        )
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
