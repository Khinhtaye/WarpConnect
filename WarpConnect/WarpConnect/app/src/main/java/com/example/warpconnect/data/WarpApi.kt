package com.example.warpconnect.data

import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Cloudflare's WARP registration API.
 */
object WarpApi {
    private const val BASE_URL = "https://api.cloudflareclient.com"
    private const val DEFAULT_PORT = 2408

    private val API_VERSIONS = listOf(
        "v0a2158" to "a-6.3-2158",
        "v0a1922" to "a-6.3-1922"
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
            put("tos", Instant.now().toString()) // Replaced SimpleDateFormat
            put("type", "Android")
            put("model", "PC")
            put("locale", "en_US")
        }.toString()

        var lastError: Exception? = null
        for ((version, clientVersion) in API_VERSIONS) {
            try {
                val response = post(version, clientVersion, body)
                val account = parse(response, keyPair)
                // Best effort: enable WARP on the created registration
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
        val interfaceObj = config.getJSONObject("interface")

        // 1. Safe parsing for endpoint (Handles host or host:port)
        val endpointObj = peer.getJSONObject("endpoint")
        val host = endpointObj.optString("v4").ifEmpty { 
            endpointObj.optString("v6").ifEmpty { 
                endpointObj.optString("host") 
            } 
        }
        val endpoint = if (host.contains(":")) host else "$host:$DEFAULT_PORT"

        // 2. Safe parsing for addresses (Handles both JSON Object and JSON Array structures)
        var v4: String? = null
        var v6: String? = null

        val addressesObj = interfaceObj.optJSONObject("addresses")
        if (addressesObj != null) {
            v4 = addressesObj.optString("v4").takeIf { it.isNotBlank() }
            v6 = addressesObj.optString("v6").takeIf { it.isNotBlank() }
        } else {
            val addressesArray = interfaceObj.optJSONArray("addresses") ?: JSONArray()
            for (i in 0 until addressesArray.length()) {
                val addrObj = addressesArray.optJSONObject(i) ?: continue
                val addrStr = addrObj.optString("address", "")
                if (addrStr.contains(".")) v4 = addrStr
                else if (addrStr.contains(":")) v6 = addrStr
            }
        }

        return WarpAccount(
            deviceId = json.getString("id"),
            accessToken = json.getString("token"),
            privateKey = keyPair.privateKey.toBase64(),
            publicKey = keyPair.publicKey.toBase64(),
            peerPublicKey = peer.getString("public_key"),
            endpoint = endpoint,
            addressV4 = v4?.substringBefore('/') ?: throw IOException("Missing IPv4 address"),
            addressV6 = v6?.substringBefore('/')
        )
    }
}
