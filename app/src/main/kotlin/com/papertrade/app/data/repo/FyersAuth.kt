package com.papertrade.app.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.papertrade.app.BuildConfig
import com.papertrade.app.data.market.FyersCredentials
import com.papertrade.app.data.market.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

enum class FyersAuthState { NOT_SET_UP, NEEDS_LOGIN, READY }

/**
 * FYERS login that renews itself, so a non-technical user never handles tokens.
 *
 * - Full login (in-app WebView, [loginUrl] → [completeLogin]) returns an access token (~1 day) and a refresh token (~15 days).
 * - [accessToken] silently swaps the refresh token + PIN for a new access token when the old one has expired.
 * - When the refresh token itself expires, [state] becomes NEEDS_LOGIN and the UI asks for one more login.
 *
 * Stored in EncryptedSharedPreferences (Android Keystore backed). The raw app secret is never stored: FYERS only needs
 * appIdHash = SHA-256("appId:secret"), which is computed once at setup (or at build time from local.properties).
 * Endpoints/fields follow FYERS API v3 docs (validate-authcode, validate-refresh-token); verify against https://fyers.in/api-docs/v3/.
 */
class FyersAuth(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api-t1.fyers.in/api/v3",
    private val clock: () -> Instant = { Instant.now() },
) : FyersCredentials {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, "secure_credentials", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<FyersAuthState> = _state

    // Values saved on the device win; otherwise fall back to what was baked in from local.properties at build time.
    private fun str(key: String, fallback: String = "") = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }
    private fun appIdNow() = str("app_id", BuildConfig.FYERS_APP_ID)
    private fun appIdHash() = str("app_id_hash", BuildConfig.FYERS_APP_ID_HASH)
    fun redirectUri() = str("redirect_uri", BuildConfig.FYERS_REDIRECT_URI)
    fun isSetUp() = appIdNow() != null && appIdHash() != null && redirectUri() != null

    /** When the 15-day refresh token runs out (i.e. the next full login is due), or null if not logged in. */
    fun loginValidUntil(): Instant? = str("refresh_token")?.let { tokenExpiry(it) ?: Instant.ofEpochMilli(prefs.getLong("refresh_obtained", 0)).plus(Duration.ofDays(15)) }

    private fun currentState() = when {
        !isSetUp() -> FyersAuthState.NOT_SET_UP
        str("access_token") != null && !expired(str("access_token")!!, accessFallbackExpiry()) -> FyersAuthState.READY
        str("refresh_token") != null && str("pin") != null && loginValidUntil()?.isAfter(clock()) == true -> FyersAuthState.READY
        else -> FyersAuthState.NEEDS_LOGIN
    }
    private fun publish() { _state.value = currentState() }

    /** One-time setup by the app owner. Only the hash of the secret is kept. Clears any existing login. */
    fun setup(appId: String, secret: String, redirectUri: String) {
        val id = appId.trim()
        prefs.edit().clear().putString("app_id", id).putString("app_id_hash", sha256("$id:${secret.trim()}"))
            .putString("redirect_uri", redirectUri.trim()).apply()
        publish()
    }

    fun loginUrl(state: String = "papertrade"): String? {
        val id = appIdNow() ?: return null; val redirect = redirectUri() ?: return null
        return "$apiBase/generate-authcode?client_id=${enc(id)}&redirect_uri=${enc(redirect)}&response_type=code&state=${enc(state)}"
    }

    /** Exchanges the auth code from the login redirect for tokens, and remembers the PIN for daily renewals. */
    suspend fun completeLogin(authCode: String, pin: String): Result<Unit> = runCatching {
        val hash = appIdHash() ?: throw ProviderException("FYERS is not set up", ProviderException.Kind.NOT_CONFIGURED)
        val resp = post("validate-authcode", buildJsonObject { put("grant_type", "authorization_code"); put("appIdHash", hash); put("code", authCode) })
        val access = resp.string("access_token") ?: throw ProviderException("FYERS login failed: ${resp.string("message") ?: "no access token"}", ProviderException.Kind.AUTH)
        mutex.withLock {
            prefs.edit().putString("access_token", access).putLong("access_obtained", clock().toEpochMilli())
                .putString("refresh_token", resp.string("refresh_token")).putLong("refresh_obtained", clock().toEpochMilli())
                .putString("pin", pin.trim()).apply()
        }
        publish()
    }

    override suspend fun appId(): String? = appIdNow()

    /** A usable access token, renewing it first if it has expired. Null means a full login is needed. */
    override suspend fun accessToken(): String? = mutex.withLock {
        val t = str("access_token")
        if (t != null && !expired(t, accessFallbackExpiry())) t else refreshLocked()
    }

    /** FYERS rejected the current token early (e.g. logged in elsewhere): drop it and try one renewal. */
    override suspend fun onTokenRejected(): Boolean = mutex.withLock {
        prefs.edit().remove("access_token").apply()
        refreshLocked() != null
    }

    fun logout() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("pin").remove("access_obtained").remove("refresh_obtained").apply()
        publish()
    }

    private suspend fun refreshLocked(): String? {
        val refresh = str("refresh_token"); val pin = str("pin"); val hash = appIdHash()
        if (refresh == null || pin == null || hash == null || loginValidUntil()?.isAfter(clock()) != true) { publish(); return null }
        val resp = post("validate-refresh-token", buildJsonObject {
            put("grant_type", "refresh_token"); put("appIdHash", hash); put("refresh_token", refresh); put("pin", pin)
        }) // network errors propagate and keep the refresh token for the next attempt
        val access = resp.string("access_token")
        if (access == null) {
            // FYERS refused the refresh token or PIN: only a full login can fix this.
            prefs.edit().remove("access_token").remove("refresh_token").apply()
            publish(); return null
        }
        prefs.edit().putString("access_token", access).putLong("access_obtained", clock().toEpochMilli()).apply()
        publish()
        return access
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$apiBase/$path").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw ProviderException("Unparseable FYERS login response (HTTP ${r.code})") }
            }
        } catch (e: IOException) {
            throw ProviderException("Network error while renewing FYERS login: ${e.message}", ProviderException.Kind.NETWORK, e)
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    /** Refresh 2 minutes early so a request never goes out with a token that dies mid-flight. */
    private fun expired(token: String, fallback: Instant) = !(tokenExpiry(token) ?: fallback).isAfter(clock().plusSeconds(120))

    /** If the token's own expiry can't be read: FYERS access tokens die around 06:00 IST the next morning. */
    private fun accessFallbackExpiry(): Instant {
        val ist = ZoneId.of("Asia/Kolkata")
        val obtained = Instant.ofEpochMilli(prefs.getLong("access_obtained", 0)).atZone(ist)
        val sixAm = obtained.toLocalDate().atTime(LocalTime.of(6, 0)).atZone(ist)
        return (if (obtained.isBefore(sixAm)) sixAm else sixAm.plusDays(1)).toInstant()
    }

    /** FYERS tokens are JWTs; read the `exp` claim without verifying (the server verifies). */
    private fun tokenExpiry(token: String): Instant? = runCatching {
        val payload = String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        (json.parseToJsonElement(payload).jsonObject["exp"] as JsonPrimitive).content.toLong().let(Instant::ofEpochSecond)
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
